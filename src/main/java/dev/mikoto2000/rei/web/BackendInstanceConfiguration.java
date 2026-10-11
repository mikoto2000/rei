package dev.mikoto2000.rei.web;

import java.nio.file.Path;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class BackendInstanceConfiguration {
  @Bean @DependsOn("storageMigrationLease")
  BackendInstance backendInstance(org.springframework.core.env.Environment environment) {
    return new BackendInstance(new BackendEndpointStore(Path.of(environment.getRequiredProperty("rei.data-dir"))));
  }
  @Bean ApplicationListener<ApplicationReadyEvent> publishBackendEndpoint(BackendInstance instance) {
    return event -> {
      if (event.getApplicationContext().getBean(BackendInstance.class) != instance) return;
      var environment = event.getApplicationContext().getEnvironment();
      String address = environment.getProperty("server.address","127.0.0.1");
      // Discovery is deliberately local. Existing explicit LAN settings are never changed.
      if (!(address.equals("127.0.0.1") || address.equals("0.0.0.0") || address.equals("localhost")))
        return; // Preserve existing explicitly configured non-local API deployments.
      try { instance.ready(Integer.parseInt(environment.getRequiredProperty("local.server.port"))); }
      catch (java.io.IOException error) { throw new IllegalStateException("Cannot publish backend endpoint",error); }
    };
  }
}
