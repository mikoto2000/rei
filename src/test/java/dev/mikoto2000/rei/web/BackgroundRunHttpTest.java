package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.image.*;
import dev.mikoto2000.rei.summarize.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import java.nio.file.Path;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class BackgroundRunHttpTest {
  @Configuration(proxyBeanMethods=false)
  @Import({WebApiIntegrationTest.Config.class, BackgroundRunConfiguration.class, BackgroundRunController.class})
  static class Config {
    @Bean WebPageSummarizerService summaries() {
      var service=mock(WebPageSummarizerService.class);
      when(service.summarize(any())).thenAnswer(a->new SummaryResult(a.getArgument(0),"summary",null)); return service;
    }
    @Bean ImageGenerationService images() {
      var service=mock(ImageGenerationService.class);
      when(service.generate(any())).thenAnswer(a->ImageGenerationResult.success(((ImageGenerationRequest)a.getArgument(0)).outputPath())); return service;
    }
    @Bean ImageProperties properties() { return new ImageProperties(); }
  }
  @TempDir Path directory;
  @Test void authenticatedBackgroundRunsReuseRealSseAndRunContracts() throws Exception {
    var app=new SpringApplication(Config.class); WebApplication.configure(app,"integration-key");
    try(var context=app.run("--rei.web.port=0","--rei.data-dir="+directory,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
      int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      var project=context.getBean(dev.mikoto2000.rei.core.project.ProjectRegistry.class).resolve(directory);
      for(String path:new String[]{"/summaries","/images"}) {
        String body="{\"projectId\":\""+project.id()+"\",\""+(path.equals("/summaries")?"url":"prompt")+"\":\""+(path.equals("/summaries")?"https://example.com":"draw")+"\"}";
        assertThat(ReadHttpTest.send(client,port,path,"POST",body,false).statusCode()).isEqualTo(401);
        var response=ReadHttpTest.send(client,port,path,"POST",body,true); assertThat(response.statusCode()).isEqualTo(202);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertThat(json.size()).isEqualTo(1);
        var location=response.headers().firstValue("Location").orElseThrow(); assertThat(location).isEqualTo("/api/v1/runs/"+json.path("runId").asText());
        var stream=ReadHttpTest.send(client,port,location.substring(7)+"/events","GET",null,true);
        assertThat(stream.statusCode()).isEqualTo(200); assertThat(stream.body()).contains("agent.run.completed");
        var status=ReadHttpTest.send(client,port,location.substring(7),"GET",null,true);
        assertThat(status.body()).contains("COMPLETED","\"sessionId\":null","\"turnId\":null");
      }
      assertThat(ReadHttpTest.send(client,port,"/summaries","POST","{\"projectId\":\"unknown\",\"url\":\"https://example.com\"}",true).statusCode()).isEqualTo(404);
    }
  }
}
