package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.read.ReadQueryService;
import dev.mikoto2000.rei.briefing.*;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.skills.*;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.search.*;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ReadHttpTest {
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class, ReadController.class})
  static class Config {
    @Bean ReadQueryService readQueries() throws Exception {
      var feeds = mock(FeedService.class); when(feeds.list()).thenReturn(List.of());
      var skills = mock(AgentSkillRepository.class); when(skills.findAll()).thenReturn(List.of());
      var profile = mock(ProfileEventLogStore.class);
      when(profile.summarize()).thenReturn(new ProfileEventLogStore.ProfileSummary(Path.of("secret"), 0, null, null, Map.of(), Map.of()));
      var search = mock(SearchKnowledgeService.class);
      when(search.search("test",3,5,null,null)).thenReturn(new SearchKnowledgeResult("test",List.of(),WebSearchContext.primaryOnly(List.of()),null));
      var briefing = mock(BriefingService.class);
      when(briefing.today()).thenReturn(new DailyBriefing(LocalDate.of(2026,1,1),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"today",List.of(),List.of()));
      return new ReadQueryService(feeds,skills,profile,search,briefing);
    }
  }
  @TempDir Path directory;
  @Test void realHttpContractsAndSecurityStayClosed() throws Exception {
    var app = new SpringApplication(Config.class); WebApplication.configure(app,"integration-key");
    try(var context=app.run("--rei.web.port=0","--rei.data-dir="+directory,"--logging.config=classpath:web-test-logback.xml"); var client=HttpClient.newHttpClient()) {
      int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      for(String path:List.of("/feed","/skills","/profile","/briefing")) {
        assertThat(send(client,port,path,"GET",null,false).statusCode()).isEqualTo(401);
        var response=send(client,port,path,"GET",null,true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.body()).doesNotContain("secret","skillFile","directory");
      }
      var result=send(client,port,"/search","POST","{\"query\":\"test\"}",true);
      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.body()).properties()).extracting(Map.Entry::getKey)
          .containsExactlyInAnyOrder("query","vectorResults","webResults");
      assertThat(send(client,port,"/search","POST","{\"query\":\"test\"}",false).statusCode()).isEqualTo(401);
      for(String path:List.of("/feed/99","/skills/unknown","/sh","/config","/task","/schedule","/model","/project/cd"))
        assertThat(send(client,port,path,"GET",null,true).statusCode()).isEqualTo(404);
      for(String path:List.of("/feed","/skills","/profile"))
        assertThat(send(client,port,path,"POST","{}",true).statusCode()).isEqualTo(405);
    }
  }
  static HttpResponse<String> send(HttpClient client,int port,String path,String method,String body,boolean auth) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(10));
    if(auth) builder.header("Authorization","Bearer integration-key");
    if(body!=null) builder.header("Content-Type","application/json");
    return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
  }
}
