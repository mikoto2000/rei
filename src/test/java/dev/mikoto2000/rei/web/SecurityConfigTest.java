package dev.mikoto2000.rei.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {
  @Configuration(proxyBeanMethods = false) @EnableWebMvc
  @Import({SecurityConfig.class, Endpoint.class})
  static class Config {
    @Bean ApiKeyProperties apiKeyProperties() { return new ApiKeyProperties("test-secret"); }
  }
  @RestController static class Endpoint {
    @RequestMapping("/api/v1/probe") String probe() { return "ok"; }
    @GetMapping("/actuator/health") String health() { return "ok"; }
  }

  @Test void bearerAuthenticationIsStatelessAndAllowsPostAndAsyncDispatch() {
    new WebApplicationContextRunner().withUserConfiguration(Config.class).run(context -> {
      MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
      mvc.perform(get("/actuator/health")).andExpect(status().isOk());
      for (String header : new String[] {"", "Basic test-secret", "Bearer ", "Bearer wrong"}) {
        var response = mvc.perform(get("/api/v1/probe").header("Authorization", header))
            .andExpect(status().isUnauthorized()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("test-secret", "wrong");
        assertThat(response.getRequest().getSession(false)).isNull();
      }
      mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
      for (var request : java.util.List.of(get("/api/v1/probe"), post("/api/v1/probe"),
          get("/api/v1/probe").with(request -> { request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC); return request; }))) {
        var result = mvc.perform(request.header("Authorization", "Bearer test-secret"))
            .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
      }
    });
  }
}
