package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** バックエンド起動単位の識別子の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class BackendInstanceConfiguration {
  /** Web APIによる操作で用いるバックエンド起動単位の識別子を返す。 */
  @Bean
  @DependsOn("storageMigrationLease")
  BackendInstance backendInstance(org.springframework.core.env.Environment environment) {
    return new BackendInstance(
        new BackendEndpointStore(Path.of(environment.getRequiredProperty("rei.data-dir"))));
  }

  /** 実行状況を利用先で把握できるよう、バックエンド接続先を通知する。 */
  @Bean
  ApplicationListener<ApplicationReadyEvent> publishBackendEndpoint(BackendInstance instance) {
    return event -> {
      if (event.getApplicationContext().getBean(BackendInstance.class) != instance) return;
      var environment = event.getApplicationContext().getEnvironment();
      String address = environment.getProperty("server.address", "127.0.0.1");
      // Discovery is deliberately local. Existing explicit LAN settings are never changed.
      if (!(address.equals("127.0.0.1")
          || address.equals("0.0.0.0")
          || address.equals("localhost")))
        return; // Preserve existing explicitly configured non-local API deployments.
      try {
        instance.ready(Integer.parseInt(environment.getRequiredProperty("local.server.port")));
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot publish backend endpoint", error);
      }
    };
  }
}
