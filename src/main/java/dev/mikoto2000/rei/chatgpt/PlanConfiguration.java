package dev.mikoto2000.rei.chatgpt;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 計画の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  PlanProperties.class,
  PlanDelegationProperties.class,
  PlanExecutionProperties.class,
  PlanAppServerProperties.class
})
@ConditionalOnProperty(name = "rei.chatgpt-plan.enabled", havingValue = "true")
public class PlanConfiguration {
  /** ChatGPTとの連携で用いるCodexアプリケーションサーバーバックエンドを返す。 */
  @Bean
  @ConditionalOnProperty(name = "rei.chatgpt-plan.app-server.enabled", havingValue = "true")
  dev.mikoto2000.rei.subagent.CodexAppServerSubAgentBackend codexAppServerBackend(
      PlanAppServerProperties settings,
      PlanClient client,
      PlanProperties plan,
      PlanDelegationProperties automatic,
      dev.mikoto2000.rei.subagent.SubAgentRegistry registry,
      dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions,
      dev.mikoto2000.rei.core.service.CommandCancellationService cancellation,
      dev.mikoto2000.rei.event.AgentEventFactory events,
      dev.mikoto2000.rei.event.AgentEventPublisher publisher,
      Clock clock,
      dev.mikoto2000.rei.application.run.RunRegistry runs) {
    PlanAppServerClient.Launcher launcher =
        settings.executionMode().equals("windows")
            ? new WindowsPlanAppServerLauncher(settings)
            : new WslPlanAppServerLauncher(settings);
    var adapter = new PlanAppServerClient(client, launcher, permissions);
    var backend =
        new dev.mikoto2000.rei.subagent.ChatGptSubAgentBackend(
            adapter,
            plan,
            registry,
            permissions,
            cancellation,
            events,
            publisher,
            clock,
            runs,
            "codex-app-server");
    backend.configureAutomatic(automatic);
    return new dev.mikoto2000.rei.subagent.CodexAppServerSubAgentBackend(backend);
  }

  /** 依頼を進める方針を決めるため、通信経路の計画を組み立てる。 */
  @Bean
  PlanTransport planTransport() {
    return new PlanTransport.Http();
  }

  /** 依頼を進める方針を決めるため、認証情報保存先の計画を組み立てる。 */
  @Bean
  ProtectedCredentialStore planCredentialStore(PlanProperties p) {
    return new ProtectedCredentialStore(p.credentialDirectory(), new WindowsDpapiProtector());
  }

  /** 依頼を進める方針を決めるため、O認証の計画を組み立てる。 */
  @Bean
  PlanOAuth planOAuth(ProtectedCredentialStore s, PlanTransport t, Clock c) {
    return new PlanOAuth(s, t, new PlanOAuth.OpenAiIdentityVerifier(), c);
  }

  /** 依頼を進める方針を決めるため、クライアントの計画を組み立てる。 */
  @Bean
  PlanClient planClient(PlanOAuth o, PlanTransport t, Clock c) {
    return new PlanClient(o, t, c);
  }

  /** 依頼を進める方針を決めるため、ログインの計画を組み立てる。 */
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
