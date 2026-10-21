package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 音声Advancedの結果と状態の契約を検証するテスト。 */
class VoiceAdvancedTest {
  /** {@code target} の条件を再現するための補助情報を返す。 */
  ConversationTarget target(String session) {
    return new ConversationTarget(
        new ProjectContext("00000000-0000-0000-0000-000000000001", "p", Path.of(".")), session);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void standaloneWakeExpiresAfterSixtySeconds() {
    var instant = new AtomicReference<>(Instant.EPOCH);
    var clock =
        new Clock() {
          /** 検証用に、音声による対話で参照するタイムゾーンを返す。 */
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          /** {@code instant} の条件を再現するための補助情報を返す。 */
          public Instant instant() {
            return instant.get();
          }
        };
    var gate = new VoiceWakeGate(clock);
    var o =
        new VoiceAdvancedOptions(
            true, "れい", false, false, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    gate.filter("れい", target("s"), o);
    instant.set(Instant.EPOCH.plusSeconds(60));
    assertThat(gate.filter("こんにちは", target("s"), o)).isEmpty();
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void defaultsAreOffAndInvalidOptionsCannotEnableAnything() {
    var o = VoiceAdvancedOptions.defaults();
    assertThat(o.wakeEnabled()).isFalse();
    assertThat(o.interruptEnabled()).isFalse();
    assertThat(o.ttsEnabled()).isFalse();
    assertThatThrownBy(() -> new VoiceAdvancedOptions(true, "", false, false, o.ttsVoice(), 800))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VoiceAdvancedOptions(false, "れい", false, true, "", 800))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VoiceAdvancedOptions(false, "れい", false, false, o.ttsVoice(), 249))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 待機の解除について、先行部分境界条件を必要とすることを確認する。 */
  @Test
  void wakeRequiresPrefixBoundaryAndPreservesLiteralCommandText() {
    var gate = new VoiceWakeGate(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    var o = VoiceAdvancedOptions.defaults();
    assertThat(gate.filter("Ａ.txt を確認", target("s"), o)).contains("Ａ.txt を確認");
    o = new VoiceAdvancedOptions(true, "れい", false, false, o.ttsVoice(), 800);
    assertThat(gate.filter("これはれいへの依頼", target("s"), o)).isEmpty();
    assertThat(gate.filter("れいめいを確認", target("s"), o)).isEmpty();
    assertThat(gate.filter("レイ、Ａ.txt を確認", target("s"), o)).contains("Ａ.txt を確認");
    assertThat(gate.filter("れい、こんにちは", target("s"), o)).contains("こんにちは");
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void standaloneWakeArmsOneUtteranceAndCannotCrossSession() {
    var gate = new VoiceWakeGate(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    var o = VoiceAdvancedOptions.defaults();
    o = new VoiceAdvancedOptions(true, "れい", false, false, o.ttsVoice(), 800);
    assertThat(gate.filter("れい。", target("s"), o)).isEmpty();
    assertThat(gate.filter("こんにちは", target("s"), o)).contains("こんにちは");
    assertThat(gate.filter("次の入力", target("s"), o)).isEmpty();
    gate.filter("れい", target("s"), o);
    assertThat(gate.filter("こんにちは", target("other"), o)).isEmpty();
    gate.filter("れい", target("s"), o);
    gate.reset();
    assertThat(gate.filter("こんにちは", target("s"), o)).isEmpty();
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void echoSuppressionInvalidatesSpanningRecognitionAndIncludesTail() {
    var tick = new AtomicLong();
    var gate = new VoiceAudioGate(tick::get);
    long before = gate.epoch();
    assertThat(gate.allowed(before)).isTrue();
    try (var playback = gate.playback(Duration.ofMillis(800))) {
      assertThat(gate.suppressed()).isTrue();
      assertThat(gate.allowed(before)).isFalse();
    }
    assertThat(gate.suppressed()).isTrue();
    tick.set(800_000_000L);
    assertThat(gate.suppressed()).isFalse();
    assertThat(gate.allowed(before)).isFalse();
    assertThat(gate.allowed(gate.epoch())).isTrue();
  }
}
