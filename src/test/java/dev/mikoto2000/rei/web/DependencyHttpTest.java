package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.net.http.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class DependencyHttpTest {
  @TempDir Path dir;
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class,DependencyController.class})
  static class Config {
    @Bean PersistentDependencyRepository dependencies(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
      return new PersistentDependencyRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+Path.of(directory).resolve("dependencies.db")),Clock.systemUTC());
    }
    @Bean DependencyObservationService observations(){return mock(DependencyObservationService.class);}
  }
  @Test void authenticatedVersionedAnswerPersistsWithoutDispatchAndRejectsReplay() throws Exception {
    var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
    String id=null;
    for(int restart=0;restart<2;restart++) {
      try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
        int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        var repo=context.getBean(PersistentDependencyRepository.class);
        if(restart==0)id=repo.create(new AgentRunContext("run","session",dir,"p"),new DependencySpec(DependencySpec.Kind.USER_ANSWER,"Choose A or B",null),Duration.ofHours(1),List.of()).id();
        String path="/projects/p/dependencies/"+id;
        assertEquals(401,ReadHttpTest.send(client,port,path,"GET",null,false).statusCode());
        assertEquals(401,ReadHttpTest.send(client,port,path+"/answer","POST","{\"expectedVersion\":0,\"answer\":\"B\"}",false).statusCode());
        assertEquals(200,ReadHttpTest.send(client,port,"/projects/p/dependencies","GET",null,true).statusCode());
        assertEquals(400,ReadHttpTest.send(client,port,"/projects/other/dependencies/"+id,"GET",null,true).statusCode());
        if(restart==0) {
          assertEquals(400,ReadHttpTest.send(client,port,path+"/answer","POST","{\"answer\":\"B\"}",true).statusCode());
          assertEquals(409,ReadHttpTest.send(client,port,path+"/answer","POST","{\"expectedVersion\":99,\"answer\":\"B\"}",true).statusCode());
          var accepted=ReadHttpTest.send(client,port,path+"/answer","POST","{\"expectedVersion\":0,\"answer\":\"A\"}",true);
          assertEquals(200,accepted.statusCode());assertTrue(accepted.body().contains("\"answer\":\"A\""));
        }
        assertEquals(409,ReadHttpTest.send(client,port,path+"/answer","POST","{\"expectedVersion\":0,\"answer\":\"B\"}",true).statusCode());
        assertEquals("A",repo.get("p",id).answer());
        assertEquals(DependencyState.WAITING,repo.get("p",id).state());
        assertEquals(200,ReadHttpTest.send(client,port,path+"/history","GET",null,true).statusCode());
        verify(context.getBean(DependencyObservationService.class),times(restart==0?1:0)).flushFacts();
        verify(context.getBean(DependencyObservationService.class),never()).inspect(anyString(),anyString(),anyBoolean());
      }
    }
  }
}
