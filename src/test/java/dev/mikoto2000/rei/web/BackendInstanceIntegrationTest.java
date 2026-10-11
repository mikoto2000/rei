package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.launcher.*;

@Tag("integration")
class BackendInstanceIntegrationTest {
  @TempDir Path root;
  @Configuration(proxyBeanMethods=false)
  @Import({BackendInstanceConfiguration.class,InstanceController.class,SecurityConfig.class})
  @ImportAutoConfiguration({
      org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration.class,
      org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
      org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class})
  static class Config {
    @Bean static dev.mikoto2000.rei.storage.StorageMigrationConfiguration.StartupGate storageStartupGate() {
      return new dev.mikoto2000.rei.storage.StorageMigrationConfiguration.StartupGate();
    }
  }
  @Test void actualHttpReadyIdentitySurvivesRestartAndLeaseAndEndpointCloseTogether() throws Exception {
    var store = new BackendEndpointStore(root);
    String storageId = null, previousInstance = null;
    for (int restart=0;restart<2;restart++) {
      var app = new SpringApplication(Config.class);
      WebApplication.configure(app,"integration-secret");
      try (var context = app.run("--rei.data-dir="+root,"--rei.web.port=0",
          "--logging.config=classpath:web-test-logback.xml"); var client = HttpClient.newHttpClient()) {
        var endpoint = store.read().orElseThrow();
        assertTrue(BackendOwnership.isOwned(root));
        assertEquals(BackendConnectionProbe.Status.READY,
            new BackendConnectionProbe(client).check(endpoint,"integration-secret",true).status());
        assertEquals(BackendConnectionProbe.Status.AUTHENTICATION_FAILED,
            new BackendConnectionProbe(client).check(endpoint,"wrong",true).status());
        if (storageId == null) storageId = endpoint.storageId();
        else assertEquals(storageId,endpoint.storageId());
        assertNotEquals(previousInstance,endpoint.instanceId());
        previousInstance = endpoint.instanceId();
      }
      assertTrue(store.read().isEmpty());
      assertFalse(BackendOwnership.isOwned(root));
    }
  }
}
