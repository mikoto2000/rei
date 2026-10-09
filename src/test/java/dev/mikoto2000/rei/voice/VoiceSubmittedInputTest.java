package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.ui.shell.*;

@Tag("integration")
class VoiceSubmittedInputTest {
  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);
  final List<Runnable> tasks = new ArrayList<>();
  final List<String> prompts = new ArrayList<>();
  final List<ConversationInput> submitted = new ArrayList<>();
  final ByteArrayOutputStream display = new ByteArrayOutputStream();
  Terminal terminal;
  ProjectService projects;
  ShellConversationService shell;
  VoiceDeliveryService delivery;
  ConversationTarget target;
  ProjectClient client;
  boolean reject;

  @BeforeEach void setup() throws Exception {
    projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var router = new ConversationInputRouter(task -> {
      if (reject) throw new RejectedExecutionException("fixture");
      tasks.add(task);
    }, (owner, prompt, mailbox) -> prompts.add(prompt));
    shell = new ShellConversationService(projects,
        new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")), clock),
        router::submit, false, router, clock);
    delivery = new VoiceDeliveryService(shell, clock, new VoiceEventPublisher());
    client = projects.newClient();
    try (var scope = client.open()) {
      target = shell.captureTarget();
      delivery.bind(shell.captureClient(), target);
    }
    terminal = new DumbTerminal("submitted-voice", Terminal.TYPE_DUMB,
        new ByteArrayInputStream(new byte[0]), display, StandardCharsets.UTF_8);
    var reader = LineReaderBuilder.builder().terminal(terminal).build();
    var output = new JLineShellEventOutput(reader);
    delivery.onSubmitted(input -> {
      submitted.add(input);
      // The UI uses the display/acceptance time, not the earlier speech timestamp.
      output.printBlock(UserInputFrame.format(input.text(), LocalTime.of(1, 37, 16)));
    });
  }

  @AfterEach void closeTerminal() throws Exception { terminal.close(); }

  ConversationInput input(String text) {
    return new ConversationInput(UUID.randomUUID(), InputSource.VOICE, target, text, clock.instant().minusSeconds(60));
  }

  @Test void automaticAcceptedVoicePrintsKeyboardFrameExactlyOnce() {
    var input = input("音声入力のテスト");
    delivery.accept(input);
    delivery.accept(input);
    assertThat(submitted).containsExactly(input);
    String ls = System.lineSeparator();
    assertThat(display.toString(StandardCharsets.UTF_8)).isEqualTo(ls + "┌ User (01:37:16)" + ls + "音声入力のテスト" + ls + "└" + ls + ls);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly(input.text());
  }

  @Test void confirmationPrintsOnlyTheAcceptedCorrection() {
    delivery.setConfirmation(true);
    var input = input("未確認の認識文");
    delivery.accept(input);
    assertThat(display.toString(StandardCharsets.UTF_8)).isEmpty();
    delivery.confirm(input.inputId(), "訂正した文章\n2行目");
    assertThat(submitted).singleElement().satisfies(accepted -> {
      assertThat(accepted.inputId()).isEqualTo(input.inputId());
      assertThat(accepted.createdAt()).isEqualTo(input.createdAt());
      assertThat(accepted.text()).isEqualTo("訂正した文章\n2行目");
    });
    assertThat(display.toString(StandardCharsets.UTF_8)).contains("訂正した文章", "2行目").doesNotContain("未確認の認識文");
    assertThatThrownBy(() -> delivery.confirm(input.inputId(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).hasSize(1);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("訂正した文章\n2行目");
  }

  @Test void cancelInvalidCorrectionAndTargetChangeDoNotPrintSubmittedInput() {
    delivery.setConfirmation(true);
    var cancelled = input("cancelled");
    delivery.accept(cancelled);
    assertThat(delivery.cancel(cancelled.inputId())).isTrue();
    assertThatThrownBy(() -> delivery.confirm(cancelled.inputId(), null)).isInstanceOf(IllegalArgumentException.class);
    var pending = input("pending");
    delivery.accept(pending);
    assertThatThrownBy(() -> delivery.confirm(pending.inputId(), "/exit")).isInstanceOf(IllegalArgumentException.class);
    try (var scope = client.open()) { shell.newConversation(); }
    assertThatThrownBy(() -> delivery.confirm(pending.inputId(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(submitted).isEmpty();
    assertThat(display.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @Test void failedAdmissionIsNotPrintedAndSuccessfulRetryIsPrintedOnce() {
    delivery.setConfirmation(true);
    var input = input("original");
    delivery.accept(input);
    reject = true;
    assertThatThrownBy(() -> delivery.confirm(input.inputId(), "corrected")).isInstanceOf(RejectedExecutionException.class);
    assertThat(submitted).isEmpty();
    reject = false;
    delivery.confirm(input.inputId(), null);
    assertThat(submitted).extracting(ConversationInput::text).containsExactly("corrected");
  }

  @Test void fullQueueDoesNotPrintRejectedInput() {
    delivery.accept(input("one"));
    delivery.accept(input("two"));
    delivery.accept(input("three"));
    assertThatThrownBy(() -> delivery.accept(input("rejected"))).isInstanceOf(RejectedExecutionException.class);
    assertThat(submitted).hasSize(3);
    assertThat(display.toString(StandardCharsets.UTF_8)).doesNotContain("rejected");
  }

  @Test void listenerFailureAndUnsubscriptionCannotResubmitAcceptedVoice() {
    delivery.onSubmitted(input -> { throw new IllegalStateException("display failed"); });
    var extra = new ArrayList<ConversationInput>();
    var subscription = delivery.onSubmitted(extra::add);
    var first = input("first");
    delivery.accept(first);
    subscription.close();
    delivery.accept(first);
    delivery.accept(input("second"));
    assertThat(submitted).hasSize(2);
    assertThat(extra).containsExactly(first);
  }
}
