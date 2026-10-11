package dev.mikoto2000.rei.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;

class InstanceControllerTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false) @EnableWebMvc
  @Import({SecurityConfig.class,InstanceController.class})
  static class Config {
    @Bean ApiKeyProperties apiKeyProperties() { return new ApiKeyProperties("test-secret"); }
  }
  @Test void onlyAuthenticatedClientsSeeReadyInstanceAndNeverCredentials() throws Exception {
    var instance = new BackendInstance(new BackendEndpointStore(root));
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true")
        .withBean(BackendInstance.class,()->instance).withUserConfiguration(Config.class).run(context -> {
          var mvc = MockMvcBuilders.webAppContextSetup(context)
              .addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
          mvc.perform(get("/api/v1/instance")).andExpect(status().isUnauthorized());
          mvc.perform(get("/api/v1/instance").header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
          mvc.perform(get("/api/v1/instance").header("Authorization","Bearer test-secret")).andExpect(status().isServiceUnavailable());
          instance.ready(8123);
          mvc.perform(get("/api/v1/instance").header("Authorization","Bearer test-secret"))
              .andExpect(status().isOk()).andExpect(jsonPath("$.instanceId").value(instance.get().instanceId()))
              .andExpect(jsonPath("$.storageId").value(instance.get().storageId()))
              .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-secret"))));
        });
  }
}
