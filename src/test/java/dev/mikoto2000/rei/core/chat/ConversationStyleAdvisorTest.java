package dev.mikoto2000.rei.core.chat;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConversationStyleAdvisorTest {
 @Test void normalIsIdentityAndConversationPreservesCharacterToolsAndUser() {
  var options=org.springframework.ai.openai.OpenAiChatOptions.builder().model("same-model").maxTokens(4096).build();
  var system=new SystemMessage("あなたはれいです。既存のキャラクターを維持する。");var user=new UserMessage("長い説明が必要です");
  var request=ChatClientRequest.builder().prompt(new Prompt(List.of(system,user),options)).build();
  assertThat(new ConversationStyleAdvisor(ResponseStyle.NORMAL).before(request,mock(AdvisorChain.class))).isSameAs(request);
  var actual=new ConversationStyleAdvisor(ResponseStyle.CONVERSATION).before(request,mock(AdvisorChain.class));
  assertThat(actual.prompt().getSystemMessage().getText()).startsWith(system.getText()).contains("短め","箇条書き","承認","最後まで","省略しない");
  assertThat(actual.prompt().getUserMessage()).isSameAs(user);assertThat(actual.prompt().getOptions()).isSameAs(options);
  var response=ChatClientResponse.builder().chatResponse(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("重要な結果\n".repeat(1000)))))).build();
  assertThat(new ConversationStyleAdvisor(ResponseStyle.CONVERSATION).after(response,mock(AdvisorChain.class))).isSameAs(response);
 }
 @Test void noBaseSystemStillAddsStyleWithoutChangingPromptHistory() {
  var user=new UserMessage("こんにちは");var request=ChatClientRequest.builder().prompt(new Prompt(user)).build();
  var actual=new ConversationStyleAdvisor(ResponseStyle.CONVERSATION).before(request,mock(AdvisorChain.class));
  assertThat(actual.prompt().getSystemMessage().getText()).contains("会話スタイル");
  assertThat(request.prompt().getInstructions()).containsExactly(user);
 }
}
