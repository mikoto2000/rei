/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapted for Rei to retain bounded message and legacy SQLite behavior.
// Source: Spring AI 2.1.0; modifications remove deprecated API dependencies.
package dev.mikoto2000.rei.chatmemory;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Memory is retrieved added as a collection of messages to the prompt
 *
 * @author Christian Tzolov
 * @author Mark Pollack
 * @author Thomas Vitale
 * @since 1.0.0
 */
public final class ConversationMemoryAdvisor implements BaseChatMemoryAdvisor {

  @Override
  public String getName() {
    return "MessageChatMemoryAdvisor";
  }

  private final ConversationMemory chatMemory;

  private final int order;

  private final Scheduler scheduler;

  private ConversationMemoryAdvisor(ConversationMemory chatMemory, int order, Scheduler scheduler) {
    Assert.notNull(chatMemory, "chatMemory cannot be null");
    Assert.notNull(scheduler, "scheduler cannot be null");
    this.chatMemory = chatMemory;
    this.order = order;
    this.scheduler = scheduler;
  }

  @Override
  public int getOrder() {
    return this.order;
  }

  @Override
  public Scheduler getScheduler() {
    return this.scheduler;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
    String conversationId = getConversationId(chatClientRequest.context());

    // 1. Retrieve the chat memory for the current conversation.
    List<Message> memoryMessages = this.chatMemory.get(conversationId);

    // 2. Advise the request messages list.
    List<Message> promptMessages = chatClientRequest.prompt().getInstructions();
    List<Message> processedMessages = new ArrayList<>();
    if (!isMemoryAlreadyInPrompt(promptMessages, memoryMessages)) {
      processedMessages.addAll(memoryMessages);
    }
    processedMessages.addAll(promptMessages);

    // 2.1. Ensure system message, if present, appears first in the list.
    for (int i = 0; i < processedMessages.size(); i++) {
      if (processedMessages.get(i) instanceof SystemMessage) {
        Message systemMessage = processedMessages.remove(i);
        processedMessages.add(0, systemMessage);
        break;
      }
    }

    // 3. Create a new request with the advised messages.
    ChatClientRequest processedChatClientRequest =
        chatClientRequest
            .mutate()
            .prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
            .build();

    // 4. Add the new user message to the conversation memory.
    Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
    this.chatMemory.add(conversationId, userMessage);

    return processedChatClientRequest;
  }

  private static boolean isMemoryAlreadyInPrompt(
      List<Message> promptMessages, List<Message> memoryMessages) {
    if (memoryMessages.isEmpty()) {
      return true;
    }
    if (promptMessages.size() < memoryMessages.size()) {
      return false;
    }
    for (int offset = 0; offset <= promptMessages.size() - memoryMessages.size(); offset++) {
      if (startsWith(promptMessages, memoryMessages, offset)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWith(List<Message> messages, List<Message> prefix, int offset) {
    if (messages.size() - offset < prefix.size()) {
      return false;
    }
    for (int i = 0; i < prefix.size(); i++) {
      if (!messages.get(i + offset).equals(prefix.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public ChatClientResponse after(
      ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
    List<Message> assistantMessages = new ArrayList<>();
    var response = chatClientResponse.chatResponse();
    if (response != null) {
      assistantMessages = response.getResults().stream().map(g -> (Message) g.getOutput()).toList();
    }
    this.chatMemory.add(this.getConversationId(chatClientResponse.context()), assistantMessages);
    return chatClientResponse;
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
    // Get the scheduler from BaseAdvisor
    Scheduler scheduler = this.getScheduler();

    // Process the request with the before method
    return Mono.just(chatClientRequest)
        .publishOn(scheduler)
        .map(request -> this.before(request, streamAdvisorChain))
        .flatMapMany(streamAdvisorChain::nextStream)
        .transform(flux ->
            new ChatClientMessageAggregator()
                .aggregateChatClientResponse(
                    flux, response -> this.after(response, streamAdvisorChain)));
  }

  public static Builder builder(ConversationMemory chatMemory) {
    return new Builder(chatMemory);
  }

  public static final class Builder {

    private int order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;

    private Scheduler scheduler = BaseAdvisor.DEFAULT_SCHEDULER;

    private final ConversationMemory chatMemory;

    private Builder(ConversationMemory chatMemory) {
      Assert.notNull(chatMemory, "chatMemory cannot be null");
      this.chatMemory = chatMemory;
    }

    /**
     * Set the order.
     *
     * @param order the order
     * @return the builder
     */
    public Builder order(int order) {
      this.order = order;
      return this;
    }

    public Builder scheduler(Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Build the advisor.
     *
     * @return the advisor
     */
    public ConversationMemoryAdvisor build() {
      return new ConversationMemoryAdvisor(this.chatMemory, this.order, this.scheduler);
    }
  }
}
