package dev.mikoto2000.rei.conversation;

import java.nio.file.Path;
import dev.mikoto2000.rei.application.session.SessionRepository;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** Shared by the interactive Shell and the Web application. */
@Configuration(proxyBeanMethods = false)
public class SessionHistoryConfiguration {
  @Bean dev.mikoto2000.rei.application.session.SessionLifecycle sessionLifecycle(SessionRepository repository, java.time.Clock clock) {
    return new dev.mikoto2000.rei.application.session.SessionLifecycle(repository, clock);
  }
  @Bean dev.mikoto2000.rei.application.session.SessionQueryService sessionQueryService(SessionRepository repository,
      ConversationTurnStore turns) {
    return new dev.mikoto2000.rei.application.session.SessionQueryService(repository, turns);
  }
  @Bean ConversationTurnStore conversationTurnStore(@Value("${rei.data-dir}") String directory) {
    return new ConversationTurnStore(Path.of(directory));
  }
  @Bean SessionRepository sessionRepository(@Value("${rei.data-dir}") String directory) {
    return new FileSessionRepository(Path.of(directory).resolve("sessions.json"));
  }
}
