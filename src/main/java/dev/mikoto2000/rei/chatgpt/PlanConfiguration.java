package dev.mikoto2000.rei.chatgpt;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanProperties.class)
@ConditionalOnProperty(name = "rei.chatgpt-plan.enabled", havingValue = "true")
public class PlanConfiguration {
  @Bean
  PlanTransport planTransport() {
    return new PlanTransport.Http();
  }

  @Bean
  ProtectedCredentialStore planCredentialStore(PlanProperties p) {
    return new ProtectedCredentialStore(p.credentialDirectory(), new WindowsDpapiProtector());
  }

  @Bean
  PlanOAuth planOAuth(ProtectedCredentialStore s, PlanTransport t, Clock c) {
    return new PlanOAuth(s, t, new PlanOAuth.OpenAiIdentityVerifier(), c);
  }

  @Bean
  PlanClient planClient(PlanOAuth o, PlanTransport t, Clock c) {
    return new PlanClient(o, t, c);
  }

  @Bean
  PlanLogin planLogin(PlanOAuth o) {
    return new PlanLogin(
        o,
        uri -> {
          try {
            java.awt.Desktop.getDesktop().browse(uri);
          } catch (Exception e) {
            throw ChatGptException.of(ChatGptException.Code.AUTH_INVALID);
          }
        });
  }
}
