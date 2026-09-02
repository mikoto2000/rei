package dev.mikoto2000.rei.event;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ProfileEventLogSubscriber {

  private final AgentEventBus eventBus;
  private final ProfileEventLogStore logStore;
  private AgentEventBus.Subscription subscription;

  @PostConstruct
  void subscribe() {
    subscription = eventBus.subscribe(logStore);
  }

  @PreDestroy
  void unsubscribe() {
    if (subscription != null) {
      subscription.unsubscribe();
    }
  }
}
