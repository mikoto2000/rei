package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.net.http.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.launcher.*;

@Tag("integration")
class ConversationAdmissionIntegrationTest {
  @TempDir Path root;
  static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
  @Configuration(proxyBeanMethods=false)
  @Import({BackendInstanceConfiguration.class,InstanceController.class,BackendStopController.class,ChatController.class,
      RunController.class,ApiExceptionHandler.class,SecurityConfig.class,dev.mikoto2000.rei.conversation.SessionHistoryConfiguration.class})
  @ImportAutoConfiguration({org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class})
  static class Config {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean ProjectRegistry projects(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String root){return new ProjectRegistry(Path.of(root,"projects.json"));}
    @Bean AgentEventBus bus(){return new InMemoryAgentEventBus();}
    @Bean AgentEventFactory events(Clock clock){return new AgentEventFactory(clock);}
    @Bean Queue<Runnable> pending(){return new java.util.concurrent.ConcurrentLinkedQueue<>();}
    @Bean ConversationInputRouter router(Queue<Runnable> pending,AgentEventBus bus,AgentEventFactory events){return new ConversationInputRouter(pending::add,(context,prompt,queue)->bus.publish(events.runCompleted(context.runId(),0).withOwnership(context)));}
    @Bean RunRegistry runs(Clock clock){return new RunRegistry(clock);}
    @Bean SessionRegistry sessions(Clock clock){return new SessionRegistry(clock);}
    @Bean dev.mikoto2000.rei.core.service.CommandCancellationService cancellation(){return new dev.mikoto2000.rei.core.service.CommandCancellationService();}
    @Bean RunService runService(RunRegistry runs,AgentEventBus bus,AgentEventFactory events,dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,ConversationInputRouter router){return new RunService(runs,bus,events,cancellation,router::cancelQueued);}
    @Bean ChatSubmitService chat(ProjectRegistry projects,SessionRegistry sessions,RunRegistry runs,RunService service,ConversationInputRouter router,dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle){return new ChatSubmitService(projects,sessions,runs,lifecycle,(context,prompt)->router.submit(context,prompt,work->service.execute(context,work)),true);}
  }
  private org.springframework.context.ConfigurableApplicationContext start() {
    var application=new SpringApplication(Config.class);WebApplication.configure(application,"fixture-secret");
    return application.run("--rei.data-dir="+root,"--server.port=0","--rei.conversation.concurrent-enabled=true","--logging.config=classpath:web-test-logback.xml");
  }
  private HttpRequest request(BackendEndpoint endpoint,String path){return HttpRequest.newBuilder(endpoint.uri().resolve(path)).header("Authorization","Bearer fixture-secret").header("Content-Type","application/json").build();}
  private HttpRequest post(BackendEndpoint endpoint,String path,String body,String key) {
    var builder=HttpRequest.newBuilder(endpoint.uri().resolve(path)).header("Authorization","Bearer fixture-secret").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    if(key!=null)builder.header("Idempotency-Key",key);return builder.build();
  }
  @Test void realHttpConcurrentKeysBusySessionsAndRestartNeverDoubleDispatch()throws Exception {
    String run,session,project;
    try(var http=HttpClient.newHttpClient()) {
      try(var context=start()) {
        var endpoint=context.getBean(BackendInstance.class).get();project=context.getBean(ProjectRegistry.class).resolve(Files.createDirectory(root.resolve("project"))).id();
        String body=JSON.writeValueAsString(Map.of("projectId",project,"message","hello","mode","CONVERSATION"));
        var first=http.sendAsync(post(endpoint,"/api/v1/chat",body,"same"),HttpResponse.BodyHandlers.ofString());
        var second=http.sendAsync(post(endpoint,"/api/v1/chat",body,"same"),HttpResponse.BodyHandlers.ofString());
        var accepted=first.get();assertEquals(202,accepted.statusCode());assertEquals(202,second.get().statusCode());assertEquals(accepted.body(),second.get().body());
        var value=JSON.readTree(accepted.body());run=value.path("runId").asText();session=value.path("sessionId").asText();
        assertEquals(1,context.getBean("pending",Queue.class).size());
        var changed=http.send(post(endpoint,"/api/v1/chat",body.replace("hello","changed"),"same"),HttpResponse.BodyHandlers.ofString());assertEquals(409,changed.statusCode());
        String continued=JSON.writeValueAsString(Map.of("projectId",project,"sessionId",session,"message","next","mode","CONVERSATION"));
        var busy=http.send(post(endpoint,"/api/v1/chat",continued,"new"),HttpResponse.BodyHandlers.ofString());assertEquals(409,busy.statusCode());assertEquals(run,JSON.readTree(busy.body()).path("runId").asText());
        var cancelled=http.send(post(endpoint,"/api/v1/runs/"+run+"/cancel","{}",null),HttpResponse.BodyHandlers.ofString());assertEquals(202,cancelled.statusCode());
        assertEquals("CANCELLED",context.getBean(RunService.class).get(run).status().name());
        var newRun=http.send(post(endpoint,"/api/v1/chat",continued,"next"),HttpResponse.BodyHandlers.ofString());assertEquals(202,newRun.statusCode());
      }
      try(var restarted=start()) {
        var endpoint=restarted.getBean(BackendInstance.class).get();
        var receipt=http.send(request(endpoint,"/api/v1/chat/receipts/same"),HttpResponse.BodyHandlers.ofString());assertEquals(200,receipt.statusCode());assertEquals(run,JSON.readTree(receipt.body()).path("runId").asText());
        String body=JSON.writeValueAsString(Map.of("projectId",project,"message","hello","mode","CONVERSATION"));
        assertEquals(202,http.send(post(endpoint,"/api/v1/chat",body,"same"),HttpResponse.BodyHandlers.ofString()).statusCode());assertTrue(restarted.getBean("pending",Queue.class).isEmpty());
      }
    }
  }
  @Test void identityMismatchCannotStopBackendAndAcknowledgedStopReleasesLease()throws Exception {
    try(var context=start();var http=HttpClient.newHttpClient()) {
      var endpoint=context.getBean(BackendInstance.class).get();
      assertEquals(409,http.send(post(endpoint,"/api/v1/instance/stop",JSON.writeValueAsString(Map.of("instanceId",UUID.randomUUID().toString(),"storageId",endpoint.storageId())),null),HttpResponse.BodyHandlers.ofString()).statusCode());
      assertTrue(context.isActive());
      assertEquals(202,http.send(post(endpoint,"/api/v1/instance/stop",JSON.writeValueAsString(Map.of("instanceId",endpoint.instanceId(),"storageId",endpoint.storageId())),null),HttpResponse.BodyHandlers.ofString()).statusCode());
      long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(BackendOwnership.isOwned(root)&&System.nanoTime()<deadline)Thread.sleep(25);
      assertFalse(BackendOwnership.isOwned(root));assertTrue(new BackendEndpointStore(root).read().isEmpty());
    }
  }
}
