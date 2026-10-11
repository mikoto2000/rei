package dev.mikoto2000.rei.web;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class ConcurrentChatHttpTest {
  @TempDir Path directory;
  static class Control {final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);}
  @Configuration(proxyBeanMethods=false)
  @Import(WebApiIntegrationTest.Config.class)
  static class Config {
    @Bean Control control(){return new Control();}
    @Bean("memoryConsolidationDataSource") javax.sql.DataSource data(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String path) {
      var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+Path.of(path).resolve("runs.db"));return source;
    }
    @Bean @Primary ConversationInputRouter concurrentRouter(ExecutorService executor,AgentEventBus bus,AgentEventFactory events,
        CommandCancellationService cancellation,ConversationTurnStore turns,Control control) {
      return new ConversationInputRouter(executor,(owner,prompt,input)->{
        try(var binding=AgentRunScope.open(owner)) {
        cancellation.begin(Thread.currentThread());turns.start(owner,prompt);
        bus.publish(events.runStarted(owner.runId(),"fixture",null).withOwnership(owner));
        try {
          if(prompt.equals("long task")){control.started.countDown();control.release.await();}
          input.discardAndFinish();
          if(prompt.equals("failure")) {
            turns.finish(owner,ConversationTurnStore.Status.FAILED);
            bus.publish(events.runFailed(owner.runId(),new ErrorInformation("FixtureFailure","failed",null)).withOwnership(owner));
          } else {
            turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"answer");
            bus.publish(events.runCompleted(owner.runId(),1).withOwnership(owner));
          }
        }catch(InterruptedException stopped){
          input.discardAndFinish();turns.finish(owner,ConversationTurnStore.Status.CANCELLED);
          bus.publish(events.runCancelled(owner.runId(),null).withOwnership(owner));
        }finally {cancellation.clear();}
        }
      });
    }
  }
  @Test void localHttpSeparatesParallelConversationFailureGuidanceSessionEndCancelAndRestart() throws Exception {
    String questionId;
    try(var client=HttpClient.newHttpClient()) {
      var application=new SpringApplication(Config.class);WebApplication.configure(application,"integration-key");
      try(var context=application.run("--rei.web.port=0","--rei.data-dir="+directory,"--rei.conversation.concurrent-enabled=true","--logging.config=classpath:web-test-logback.xml")) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        String project=context.getBean(ProjectRegistry.class).resolve(directory).id();
        var task=chat(client,port,project,null,"long task","EXCLUSIVE");String taskId=task.get("runId").asText(),session=task.get("sessionId").asText();
        assertThat(context.getBean(Control.class).started.await(3,TimeUnit.SECONDS)).isTrue();
        var conflict=send(client,port,"/api/v1/chat","{\"projectId\":\""+project+"\",\"sessionId\":\""+session+"\",\"message\":\"question\",\"mode\":\"CONVERSATION\"}");
        assertThat(conflict.statusCode()).isEqualTo(409);assertThat(conflict.body()).contains(taskId,session);
        var question=chat(client,port,project,null,"question","CONVERSATION");questionId=question.get("runId").asText();
        assertThat(questionId).isNotEqualTo(taskId);awaitStatus(client,port,questionId,"COMPLETED");
        assertThat(send(client,port,"/api/v1/runs/"+taskId,null).body()).contains("RUNNING");
        var failure=chat(client,port,project,null,"failure","CONVERSATION");awaitStatus(client,port,failure.get("runId").asText(),"FAILED");
        assertThat(send(client,port,"/api/v1/runs/"+taskId+"/input","{\"projectId\":\""+project+"\",\"sessionId\":\""+session+"\",\"message\":\"guidance\"}").statusCode()).isEqualTo(202);
        assertThat(send(client,port,"/api/v1/sessions/"+session+"/end","{\"projectId\":\""+project+"\"}").statusCode()).isEqualTo(200);
        assertThat(send(client,port,"/api/v1/runs/"+taskId,null).body()).contains("RUNNING");
        var reader=chat(client,port,project,null,"read","READ_ONLY");String readId=reader.get("runId").asText();
        assertThat(send(client,port,"/api/v1/runs/"+readId,null).body()).contains("QUEUED");
        assertThat(send(client,port,"/api/v1/runs/"+taskId+"/cancel","{}").statusCode()).isEqualTo(202);
        awaitStatus(client,port,readId,"COMPLETED");
      }
      var reopened=new SpringApplication(Config.class);WebApplication.configure(reopened,"integration-key");
      try(var context=reopened.run("--rei.web.port=0","--rei.data-dir="+directory,"--rei.conversation.concurrent-enabled=true","--logging.config=classpath:web-test-logback.xml")) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        assertThat(send(client,port,"/api/v1/runs/"+questionId,null).body()).contains("COMPLETED","CONVERSATION");
        assertThat(send(client,port,"/api/v1/runs/"+questionId+"/events",null).statusCode()).isEqualTo(409);
      }
    }
  }
  private com.fasterxml.jackson.databind.JsonNode chat(HttpClient client,int port,String project,String session,String message,String mode)throws Exception {
    var body=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("projectId",project).put("message",message).put("mode",mode);
    if(session!=null)body.put("sessionId",session);
    var receipt=send(client,port,"/api/v1/chat",body.toString());assertThat(receipt.statusCode()).isEqualTo(202);
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(receipt.body());
  }
  private void awaitStatus(HttpClient client,int port,String run,String status)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    do {if(send(client,port,"/api/v1/runs/"+run,null).body().contains(status))return;Thread.sleep(10);}while(System.nanoTime()<end);
    throw new AssertionError("Run did not reach "+status);
  }
  private HttpResponse<String> send(HttpClient client,int port,String path,String body)throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer integration-key");
    if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
  }
}
