package dev.mikoto2000.rei.slack;

import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import com.slack.api.*;
import com.slack.api.socket_mode.*;
import com.slack.api.util.http.SlackHttpClient;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventBus;

/** One backend owns one socket. All initialization and network work is off the startup thread. */
final class SlackSdkConnection implements SlackSocketLifecycle.Connection {
  private FileChannel lockFile;private FileLock lock;private Slack slack;private SocketModeClient socket;
  private AgentEventBus.Subscription subscription;private SlackRuntime runtime;private SdkSlackTransport transport;
  static SlackSdkConnection open(SlackProperties p,Path root,Clock clock,SessionRepository repository,
      ProjectRegistry projects,RunRegistry registry,RunService runs,ConversationInputRouter router,AgentEventBus bus) {
    var connection=new SlackSdkConnection();
    try {
      Files.createDirectories(root);
      connection.lockFile=FileChannel.open(root.resolve("slack-backend.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
      connection.lock=connection.lockFile.tryLock();if(connection.lock==null)throw new SlackSetupException(SlackSetupException.Code.ANOTHER_BACKEND);
      var config=new SlackConfig();config.setStatsEnabled(false);config.setHttpClientResponseHandlers(List.of());
      var http=new okhttp3.OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(5)).readTimeout(java.time.Duration.ofSeconds(10))
          .callTimeout(java.time.Duration.ofSeconds(15)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
      connection.slack=Slack.getInstance(config,new SlackHttpClient(http));
      var methods=connection.slack.methods(System.getenv("SLACK_BOT_TOKEN"));var auth=methods.authTest(r->r);
      if(!auth.isOk()||auth.getUserId()==null)throw new SlackSetupException(SlackSetupException.Code.AUTHENTICATION_FAILED);
      if(p.getAllowedWorkspaceIds()==null||!p.getAllowedWorkspaceIds().contains(auth.getTeamId()))throw new SlackSetupException(SlackSetupException.Code.WORKSPACE_NOT_ALLOWED);
      if(p.getAllowedUserIds()==null||p.getAllowedUserIds().size()>128)throw new SlackSetupException(SlackSetupException.Code.USER_VALIDATION_FAILED);
      var humans=new HashSet<String>();
      for(String user:p.getAllowedUserIds()) {
        if(Thread.currentThread().isInterrupted())throw new SlackSetupException(SlackSetupException.Code.CONNECTION_FAILED);
        var result=methods.usersInfo(r->r.user(user));
        if(!result.isOk()||result.getUser()==null)throw new SlackSetupException(SlackSetupException.Code.USER_VALIDATION_FAILED);
        var identity=result.getUser();if(!identity.isBot()&&!identity.isAppUser()&&!identity.isDeleted()&&!identity.isWorkflowBot()&&!identity.isConnectorBot())humans.add(user);
      }
      if(humans.isEmpty())throw new SlackSetupException(SlackSetupException.Code.NO_HUMAN_USERS);
      if(p.getDmProjectId()!=null&&!p.getDmProjectId().isBlank()&&projects.resolveById(p.getDmProjectId()).isEmpty())throw new SlackSetupException(SlackSetupException.Code.PROJECT_NOT_REGISTERED);
      for(String id:p.getChannelProjects().values())if(projects.resolveById(id).isEmpty())throw new SlackSetupException(SlackSetupException.Code.PROJECT_NOT_REGISTERED);
      var verified=new SlackProperties();verified.setEnabled(true);verified.setAllowedWorkspaceIds(Set.of(auth.getTeamId()));verified.setAllowedUserIds(Set.copyOf(humans));
      verified.setAllowedChannelIds(p.getAllowedChannelIds()==null?Set.of():Set.copyOf(p.getAllowedChannelIds()));verified.setChannelProjects(Map.copyOf(p.getChannelProjects()));verified.setDmProjectId(p.getDmProjectId());
      var store=new SlackStore(root);
      store.recoverSending();
      // Separate lifecycle object shares the repository, without changing Shell notification selection.
      connection.runtime=new SlackRuntime(verified,store,auth.getUserId(),projects,new SessionLifecycle(repository,clock),registry,runs,router,clock);
      connection.transport=new SdkSlackTransport(methods,auth.getTeamId());
      var receiver=new SlackEnvelopeReceiver(connection.runtime,auth.getTeamId());
      connection.subscription=bus.subscribeBoundary(event->{try{connection.runtime.onEvent(event);}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(SlackSdkConnection.class).warn("Slack reply persistence unavailable (details omitted)");}});
      connection.socket=connection.slack.socketMode(System.getenv("SLACK_APP_TOKEN"),SocketModeClient.Backend.JavaWebSocket);
      connection.socket.addWebSocketMessageListener(raw->{String ack=receiver.receive(raw);if(ack!=null) {
        try{connection.socket.sendSocketModeResponse(com.slack.api.socket_mode.response.AckResponse.builder().envelopeId(ack).build());}
        catch(RuntimeException failed){org.slf4j.LoggerFactory.getLogger(SlackSdkConnection.class).warn("Slack ACK delivery unavailable; receipt retained");}
      }});
      connection.socket.setAutoReconnectEnabled(true);connection.socket.connect();return connection;
    }catch(Exception error) {
      connection.close();
      if(error instanceof SlackSetupException setup)throw setup;
      throw new SlackSetupException(error instanceof OverlappingFileLockException?SlackSetupException.Code.ANOTHER_BACKEND:SlackSetupException.Code.CONNECTION_FAILED);
    }
  }
  @Override public void pump(){runtime.tick();runtime.send(transport);}
  @Override public void close() {
    if(socket!=null){try{socket.close();}catch(Exception ignored){}socket=null;}
    if(subscription!=null){subscription.unsubscribe();subscription=null;}
    if(slack!=null){try{slack.close();}catch(Exception ignored){}slack=null;}
    if(lock!=null){try{lock.release();}catch(Exception ignored){}lock=null;}
    if(lockFile!=null){try{lockFile.close();}catch(Exception ignored){}lockFile=null;}
  }
}
