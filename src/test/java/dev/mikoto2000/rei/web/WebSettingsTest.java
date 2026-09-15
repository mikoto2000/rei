package dev.mikoto2000.rei.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class WebSettingsTest {
  @Test void serverSettingsFollowCanonicalWebProperties() throws Exception {
    var env = new StandardEnvironment();
    for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")))
      env.getPropertySources().addLast(source);
    assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
    assertThat(env.getProperty("server.port")).isEqualTo("8080");
    env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("rei.web.bind-address", "0.0.0.0", "rei.web.port", 9090)));
    assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
    assertThat(env.getProperty("server.port")).isEqualTo("9090");
    assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(env.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
  }
}
