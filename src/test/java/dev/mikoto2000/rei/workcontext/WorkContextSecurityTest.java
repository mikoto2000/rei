package dev.mikoto2000.rei.workcontext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.web.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class WorkContextSecurityTest {
  @Configuration(proxyBeanMethods=false) @EnableWebMvc
  @Import({SecurityConfig.class,WorkContextController.class,ApiExceptionHandler.class})
  static class Config {
    @Bean ApiKeyProperties key(){return new ApiKeyProperties("test-secret");}
    @Bean WorkContextService service(){return mock(WorkContextService.class);}
    @Bean WorkContextGit git(){return mock(WorkContextGit.class);}
    @Bean WorkContextProperties properties(){return new WorkContextProperties(false,true,1200,12000,2,20);}
  }
  @Test void allWorkContextReadsAndWritesRequireExistingBearerAuthentication() {
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withUserConfiguration(Config.class).run(context->{
      var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
      for(String path:new String[]{"/api/v1/projects/a/work-context","/api/v1/projects/a/work-context/summary","/api/v1/projects/a/work-context/history","/api/v1/projects/a/work-context/history/1"}) {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
      }
      mvc.perform(post("/api/v1/sessions/s/work-context/update")).andExpect(status().isUnauthorized());
      verifyNoInteractions(context.getBean(WorkContextService.class));
    });
  }
}
