package dev.mikoto2000.rei.web;

import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class CheckpointHttpSmokeTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiConfiguration.class,SecurityConfig.class,ChatController.class,RunController.class,CheckpointController.class,
      ApiExceptionHandler.class,PersistentCheckpointRepository.class,PersistentCheckpointService.class,CheckpointProperties.class})
  @ImportAutoConfiguration({org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
      org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class})
  static class Config {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean AgentEventFactory events(Clock clock){return new AgentEventFactory(clock);}
    @Bean InMemoryAgentEventBus bus(){return new InMemoryAgentEventBus();}
    @Bean CommandCancellationService cancellation(){return new CommandCancellationService();}
    @Bean(destroyMethod="shutdownNow") ExecutorService executor(){return Executors.newVirtualThreadPerTaskExecutor();}
    @Bean CompletableFuture<String> terminal(){return new CompletableFuture<>();}
    @Bean(name="memoryConsolidationDataSource") javax.sql.DataSource source(@Value("${rei.data-dir}") String directory){
      var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+Path.of(directory).resolve("checkpoint.db"));return source;
    }
    @Bean CheckpointReconciler reconciler(){return new CheckpointReconciler(null);}
    @Bean dev.mikoto2000.rei.core.working.WorkingSet working(){return new dev.mikoto2000.rei.core.working.WorkingSet();}
    @Bean ConversationInputRouter router(ExecutorService executor,PersistentCheckpointService checkpoints,InMemoryAgentEventBus bus,
        AgentEventFactory events,CompletableFuture<String> terminal,dev.mikoto2000.rei.core.working.WorkingSet working,
        dev.mikoto2000.rei.conversation.ConversationTurnStore turns) {
      return new ConversationInputRouter(executor,(owner,prompt,input)->{
        try(var scope=AgentRunScope.open(owner)) {
          turns.start(owner,prompt);checkpoints.start(owner,prompt);bus.publish(events.runStarted(owner.runId(),"smoke",null));
          if(checkpoints.context(owner.runId()).isEmpty()) {
            bus.publishBoundary(events.toolStarted("write","writeMultiFile","artifact"));
            try{Files.writeString(owner.projectRoot().resolve("artifact.txt"),"before interruption");}catch(java.io.IOException e){throw new RuntimeException(e);}
            working.recordRead(owner.projectRoot().resolve("artifact.txt"));bus.publishBoundary(events.toolCompleted("write","writeMultiFile",0,"artifact saved"));
            bus.publishBoundary(events.toolStarted("external","deploy","simulated in-flight operation; no real deployment"));
            turns.finish(owner,dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.CANCELLED);
            checkpoints.finish(owner,"CANCELLED");bus.publish(events.runCancelled(owner.runId(),null));
          }else {
            // Resume's safe inspection path, with no repeat of the unknown operation.
            bus.publishBoundary(events.toolStarted("read","readMultiFile","artifact"));
            bus.publishBoundary(events.toolCompleted("read","readMultiFile",0,"artifact inspected"));
            turns.finish(owner,dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED);
            checkpoints.finish(owner,"COMPLETED");bus.publish(events.runCompleted(owner.runId(),0));
          }
          terminal.complete(owner.runId());
        }catch(Throwable error){terminal.completeExceptionally(error);throw error;}
      });
    }
  }
  @Test void httpAppRestartStateChangeInspectResumeAndAuthentication() throws Exception {
    String project=null,task=null,previousRun=null;var json=new com.fasterxml.jackson.databind.ObjectMapper();
    for(int restart=0;restart<2;restart++) {
      var application=new SpringApplication(Config.class);WebApplication.configure(application,"checkpoint-smoke-key");
      try(var context=application.run("--rei.data-dir="+root,"--rei.web.port=0","--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        if(restart==0) {
          project=context.getBean(ProjectRegistry.class).resolve(root).id();
          var result=request(client,port,"/api/v1/chat","{\"projectId\":\""+project+"\",\"message\":\"long task\"}",true);
          assertEquals(202,result.statusCode());previousRun=json.readTree(result.body()).path("runId").asText();
          assertEquals(previousRun,context.getBean(CompletableFuture.class).get(10,TimeUnit.SECONDS));
          task=context.getBean(PersistentCheckpointRepository.class).list(project).getFirst().taskId();
        }else {
          String base="/api/v1/projects/"+project+"/checkpoints";
          assertEquals(401,request(client,port,base,null,false).statusCode());
          var listed=request(client,port,base,null,true);assertEquals(200,listed.statusCode());assertEquals(task,json.readTree(listed.body()).get(0).path("taskId").asText());
          var inspection=request(client,port,base+"/"+task+"/reconciliation",null,true);assertEquals(200,inspection.statusCode());
          var check=json.readTree(inspection.body());assertFalse(check.path("changed").isEmpty());assertEquals("CONFIRMATION_REQUIRED",check.path("decision").asText());
          assertFalse(context.getBean(CompletableFuture.class).isDone());
          var accepted=request(client,port,base+"/"+task+"/resume","{}",true);assertEquals(202,accepted.statusCode());
          String resumed=json.readTree(accepted.body()).path("runId").asText();assertNotEquals(previousRun,resumed);
          assertEquals(resumed,context.getBean(CompletableFuture.class).get(10,TimeUnit.SECONDS));
          var saved=context.getBean(PersistentCheckpointRepository.class).get(project,task);
          assertEquals(previousRun,saved.resumedFromRunId());assertFalse(context.getBean(PersistentCheckpointRepository.class).leased(project,task));
          assertEquals("changed after interruption",Files.readString(root.resolve("artifact.txt")));
          assertEquals(1,saved.operations().stream().filter(o->o.status()==PersistentCheckpoint.OperationStatus.UNKNOWN).count());
        }
      }
      if(restart==0)Files.writeString(root.resolve("artifact.txt"),"changed after interruption");
    }
  }
  private HttpResponse<String> request(HttpClient client,int port,String path,String body,boolean authenticated) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
    if(authenticated)request.header("Authorization","Bearer checkpoint-smoke-key");
    if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();
    return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
}
