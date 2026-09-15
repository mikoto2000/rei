package dev.mikoto2000.rei.core.working;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;

/**
 * Working Set の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class WorkingSetConfiguration {

  @org.springframework.context.annotation.Scope(value = "reiConversation", proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public WorkingSet workingSet(Clock clock, AgentEventFactory events, AgentEventBus eventBus) {
    var workingSet = new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, clock, events, eventBus);
    var directory = dev.mikoto2000.rei.core.project.ProjectStorage.currentDirectory().resolve("working-set");
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    String conversation = dev.mikoto2000.rei.llm.ConversationIds.currentChat();
    String cli = project == null ? dev.mikoto2000.rei.llm.ConversationIds.chat()
        : project.conversationId(dev.mikoto2000.rei.llm.ConversationIds.chat());
    // Preserve the CLI's existing file while giving each other conversation its own storage.
    String filename = conversation.equals(cli) ? "files.json"
        : "sessions/" + java.util.UUID.nameUUIDFromBytes(conversation.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json";
    workingSet.enablePersistence(directory.resolve(filename));
    return workingSet;
  }
}
