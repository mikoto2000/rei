package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 音声ツール権限の結果と状態の契約を検証するテスト。 */
class VoiceToolPermissionTest {
  /** {@code text} の条件を再現するための補助情報を返す。 */
  AgentRunContext text() {
    return new AgentRunContext("run", "session", Path.of("."), "project");
  }

  /** 検証で使用する利用条件の確認を、指定された条件に合わせて用意する。 */
  ToolPermissionGuard guard(ToolPermissionProperties properties, List<AgentEvent> events) {
    return new ToolPermissionGuard(
        new ToolPermissionPolicy(properties),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void recognizedSpeechNeverTurnsAutomaticSubmissionIntoMutatingToolApproval() {
    var events = new ArrayList<AgentEvent>();
    var guard = guard(new ToolPermissionProperties(false, null, null, null), events);
    var voice = text().asVoiceInput();
    assertThatCode(() -> guard.check("readFile", voice)).doesNotThrowAnyException();
    assertThatCode(() -> guard.check("webSearch", voice)).doesNotThrowAnyException();
    for (String tool :
        List.of("writeMultiFile", "deleteFile", "executeShellCommand", "sendMessage", "unknownMcp"))
      assertThatThrownBy(
              () ->
                  guard.check(
                      tool,
                      tool.equals("writeMultiFile")
                          ? "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}"
                          : tool.equals("deleteFile") ? "{\"pathStr\":\"a.txt\"}" : "{}",
                      voice))
          .isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(5);
    assertThatCode(
            () ->
                guard.check(
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}",
                    text()))
        .doesNotThrowAnyException();
    assertThat(voice.requestSource()).isEqualTo(AgentRunContext.RequestSource.SHELL);
    assertThat(voice.mode()).isEqualTo(AgentRunContext.Mode.EXCLUSIVE);
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void administratorRelabelingCannotMakeUncertainVoiceMutationReadOnly() {
    var events = new ArrayList<AgentEvent>();
    var guard =
        guard(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.READ),
                Set.of(),
                Map.of("unknownMcp", Set.of(ActionCapability.READ))),
            events);
    assertThatThrownBy(() -> guard.check("unknownMcp", text().asVoiceInput()))
        .isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(1);
  }

  /** ツール利用の権限制御で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void existingDenyAndConcurrentModeRestrictionsRemainDeny() {
    var events = new ArrayList<AgentEvent>();
    var guard =
        guard(
            new ToolPermissionProperties(
                true, Set.of(), Set.of(ActionCapability.DESTRUCTIVE), Map.of()),
            events);
    assertThatThrownBy(() -> guard.check("deleteFile", text().asVoiceInput()))
        .isInstanceOf(ToolPermissionException.class);
    var restricted =
        new AgentRunContext(
                "run",
                "session",
                Path.of("."),
                "project",
                AgentRunContext.RequestSource.SHELL,
                AgentRunContext.Mode.CONVERSATION)
            .asVoiceInput();
    assertThatThrownBy(() -> guard.check("readFile", restricted))
        .isInstanceOf(ToolPermissionException.class);
    assertThat(events).hasSize(2);
  }

  /** ツール利用の権限制御で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void speechProvenanceRoundTripsButLegacyContextIsText() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var voice = text().asVoiceInput();
    assertThat(
            mapper.readValue(mapper.writeValueAsString(voice), AgentRunContext.class).voiceInput())
        .isTrue();
    var legacy = mapper.valueToTree(text());
    ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("voiceInput");
    assertThat(mapper.treeToValue(legacy, AgentRunContext.class).voiceInput()).isFalse();
  }
}
