package dev.mikoto2000.rei.conversation;

import dev.mikoto2000.rei.application.session.SessionRepository;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;

/**
 * 会話履歴の構成要素を接続し、設定に従って利用できるようにする。
 *
 * <p>Shared by the interactive Shell and the Web application.
 */
@Configuration(proxyBeanMethods = false)
@Import(dev.mikoto2000.rei.storage.StorageMigrationConfiguration.class)
public class SessionHistoryConfiguration {
  /** 会話履歴と受付記録の管理で用いる会話受付保存先を返す。 */
  @Bean
  @DependsOn("storageMigrationLease")
  ConversationAdmissionStore conversationAdmissionStore(
      @Value("${rei.data-dir}") String directory,
      java.time.Clock clock,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventBus>
          events) {
    var admissions = new ConversationAdmissionStore(Path.of(directory), clock);
    events.ifAvailable(admissions::observe);
    return admissions;
  }

  /** 会話履歴と受付記録の管理で用いる会話受付から終了までの管理を返す。 */
  @Bean
  dev.mikoto2000.rei.application.session.SessionLifecycle sessionLifecycle(
      SessionRepository repository,
      java.time.Clock clock,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.core.project.ProjectService>
          projects,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.memory.service.AutoSleepService>
          autoSleep,
      ConversationAdmissionStore admissions) {
    var lifecycle = new dev.mikoto2000.rei.application.session.SessionLifecycle(repository, clock);
    lifecycle.setAdmissions(admissions);
    lifecycle.onSelected(
        context -> projects.ifAvailable(project -> project.rememberConversation(context)));
    lifecycle.onEnded(
        session -> autoSleep.ifAvailable(service -> service.afterSessionEnd(session)));
    return lifecycle;
  }

  /** 会話履歴と受付記録の管理で用いる会話検索条件サービスを返す。 */
  @Bean
  dev.mikoto2000.rei.application.session.SessionQueryService sessionQueryService(
      SessionRepository repository, ConversationTurnStore turns) {
    return new dev.mikoto2000.rei.application.session.SessionQueryService(repository, turns);
  }

  /** 会話履歴と受付記録の管理で用いる会話一回のやり取り保存先を返す。 */
  @Bean
  ConversationTurnStore conversationTurnStore(@Value("${rei.data-dir}") String directory) {
    return new SqliteConversationTurnStore(Path.of(directory));
  }

  /** 会話履歴と受付記録の管理で用いる会話保存と検索の窓口を返す。 */
  @Bean
  SessionRepository sessionRepository(@Value("${rei.data-dir}") String directory) {
    return new SqliteSessionRepository(Path.of(directory));
  }
}
