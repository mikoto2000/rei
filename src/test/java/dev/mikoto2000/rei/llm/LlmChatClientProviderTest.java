package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmChatClientProviderTest {

  @Test
  void agentSkillsAreEnabledOnlyForChatFeature() {
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.CHAT)).isTrue();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.SEARCH)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.MEMORY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.BLUESKY_REPLY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.FEED_SUMMARY)).isFalse();
    assertThat(LlmChatClientProvider.supportsAgentSkills(LlmFeature.BRIEFING)).isFalse();
  }
}
