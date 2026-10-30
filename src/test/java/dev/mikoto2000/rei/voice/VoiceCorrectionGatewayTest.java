package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声補正受付窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class VoiceCorrectionGatewayTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 音声による対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void asrCorrectionGatewayUserFrameHistoryAndFinalToolApprovalShareAcceptedVoiceInput()
      throws Exception {
    var clock = Clock.systemUTC();
    var tasks = new ConcurrentLinkedQueue<Runnable>();
    var accepted = new CopyOnWriteArrayList<ConversationInput>();
    var frames = new CopyOnWriteArrayList<String>();
    var turns = ConversationTurnStore.inMemory();
    var owners = new ArrayList<AgentRunContext>();
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)),
            new AgentEventFactory(clock),
            event -> {});
    var router =
        new ConversationInputRouter(
            tasks::add,
            (owner, prompt, mailbox) -> {
              owners.add(owner);
              turns.start(owner, prompt);
              assertThatThrownBy(
                      () ->
                          guard.check(
                              "writeMultiFile",
                              "{\"files\":[{\"path\":\"target.txt\",\"content\":\"test\"}]}",
                              owner))
                  .isInstanceOf(ToolPermissionException.class);
              turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "answer");
            });
    var projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")), clock),
            router::submit,
            false,
            router,
            clock);
    var events = new VoiceEventPublisher();
    var delivery = new VoiceDeliveryService(shell, clock, events);
    var client = projects.newClient();
    ConversationTarget target;
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(client, target);
    }
    var p = new VoiceCorrectionProperties();
    p.setEnabled(true);
    var source = new VoiceInputCoordinatorTest.Source();
    try (var correction =
            new VoiceCorrectionService(
                p,
                i ->
                    new VoiceCorrectionContext.Snapshot(
                        target.project().id(), "Rei", List.of("微分"), List.of()),
                (raw, context) -> VoiceCorrectionValidatorTest.corrected(raw, "勾配の話"),
                events);
        var voice =
            new VoiceInputCoordinator(
                d -> source,
                s ->
                    new VoiceBackend(
                        new VoiceActivityDetector() {
                          /** {@code probability} の条件を再現するための補助情報を返す。 */
                          public float probability(float[] frame) {
                            return frame[0] > .2f ? 1 : 0;
                          }

                          /** 検証で利用した対象の終了を扱う。 */
                          public void close() {}
                        },
                        new SpeechRecognizer() {
                          /** {@code recognize} の条件を再現するための補助情報を返す。 */
                          public String recognize(SpeechSegment segment) {
                            return "交配の話";
                          }

                          /** 検証で利用した対象の終了を扱う。 */
                          public void close() {}
                        }),
                delivery::accept,
                events,
                clock,
                delivery::targetIsCurrent,
                System::nanoTime)) {
      voice.configureCorrection(correction, delivery::requireReview);
      delivery.onSubmitted(
          input -> {
            correction.submitted(input);
            frames.add(
                dev.mikoto2000.rei.ui.shell.UserInputFrame.format(input.text(), LocalTime.NOON)
                    .toString());
            accepted.add(input);
          });
      voice.start(
          target,
          new AudioDevice("device", "fake", "fake", "vendor", "1"),
          VoiceSettings.defaults());
      source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(accepted).hasSize(1));
      assertThat(accepted.getFirst().text()).isEqualTo("勾配の話");
      assertThat(accepted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(frames.getFirst()).contains("User", "勾配の話").doesNotContain("交配の話");
      tasks.remove().run();
      assertThat(owners.getFirst().voiceInput()).isTrue();
      assertThat(turns.read(target.sessionId()).getFirst().request())
          .isEqualTo(accepted.getFirst().text());
      var trace = correction.traces().getFirst();
      assertThat(trace.original()).isEqualTo("交配の話");
      assertThat(trace.adopted()).isEqualTo("勾配の話");
      assertThat(trace.submitted()).isEqualTo("勾配の話");
      assertThat(trace.inputId()).isEqualTo(accepted.getFirst().inputId());
    }
  }
}
