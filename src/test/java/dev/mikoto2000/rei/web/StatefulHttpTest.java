package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.state.StatefulOperationService;
import dev.mikoto2000.rei.application.read.ReadQueryService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.reminder.ReminderService;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import java.nio.file.Path;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@org.junit.jupiter.api.Tag("integration")
class StatefulHttpTest {
  @Configuration(proxyBeanMethods=false)
  @Import({BackgroundRunHttpTest.Config.class,StatefulController.class,ReadController.class})
  static class Config {
    @Bean StatefulOperationService operations(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
      var data=new org.sqlite.SQLiteDataSource();data.setUrl("jdbc:sqlite:"+Path.of(directory).resolve("operations.db"));
      return new StatefulOperationService(new FeedService(data),new ReminderService(data),new InterestUpdateService(data),
          new MemoryService(data,new MemoryProperties(true,20,80,10,3,2000,60,new MemoryProperties.ExpiryDefaults(30,365))),mock(AgentSkillRepository.class));
    }
    @Bean ReadQueryService queries(StatefulOperationService ops) {
      return new ReadQueryService(ops.feeds(),ops.skills(),mock(dev.mikoto2000.rei.event.ProfileEventLogStore.class),
          mock(dev.mikoto2000.rei.search.SearchKnowledgeService.class),mock(dev.mikoto2000.rei.briefing.BriefingService.class));
    }
  }
  @TempDir Path directory;
  @Test void realHttpWritesRequireKeyPersistAndDoNotExposeDangerousOperations() throws Exception {
    var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
    for(int restart=0;restart<2;restart++) {
      try(var context=app.run("--rei.web.port=0","--rei.data-dir="+directory,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        for(String path:new String[]{"/feed","/reminders","/interests","/memories","/skills/reload"})
          assertThat(ReadHttpTest.send(client,port,path,"POST","{}",false).statusCode()).isEqualTo(401);
        if(restart==0) {
          var created=ReadHttpTest.send(client,port,"/feed","POST","{\"url\":\"https://example.com/feed\"}",true);
          assertThat(created.statusCode()).isEqualTo(201);assertThat(created.headers().firstValue("Location")).contains("/api/v1/feed/1");
          assertThat(created.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
          assertThat(ReadHttpTest.send(client,port,"/feed/1","PATCH","{\"enabled\":false}",true).statusCode()).isEqualTo(200);
        }
        assertThat(ReadHttpTest.send(client,port,"/feed/1","GET",null,true).body()).contains("\"enabled\":false");
        for(String path:new String[]{"/sh","/config/init","/project/add","/project/remove","/project/cd","/subagent/init","/subagent/validate",
            "/embed/add","/embed/delete","/task/auth","/task/add","/task/list","/task/done","/task/delete","/schedule/auth","/schedule/refresh-token",
            "/bsky/reply","/model","/models","/feed/import-opml","/memories/export","/profile","/skills"})
          assertThat(ReadHttpTest.send(client,port,path,"POST","{}",true).statusCode()).isIn(404,405);
        for(String path:new String[]{"/feed/1","/reminders/1","/memories/unknown"})
          assertThat(ReadHttpTest.send(client,port,path,"DELETE",null,false).statusCode()).isEqualTo(401);
        if(restart==1) assertThat(ReadHttpTest.send(client,port,"/feed/1","DELETE",null,true).statusCode()).isEqualTo(204);
      }
    }
  }
}
