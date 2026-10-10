package dev.mikoto2000.rei.voice;
import java.time.*;import java.nio.file.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.application.input.*;import dev.mikoto2000.rei.core.project.ProjectContext;
class VoiceAdvancedTest {
 ConversationTarget target(String session){return new ConversationTarget(new ProjectContext("00000000-0000-0000-0000-000000000001","p",Path.of(".")),session);}
 @Test void standaloneWakeExpiresAfterSixtySeconds(){
  var instant=new AtomicReference<>(Instant.EPOCH);
  var clock=new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return instant.get();}};
  var gate=new VoiceWakeGate(clock);var o=new VoiceAdvancedOptions(true,"れい",false,false,VoiceAdvancedOptions.defaults().ttsVoice(),800);
  gate.filter("れい",target("s"),o);instant.set(Instant.EPOCH.plusSeconds(60));
  assertThat(gate.filter("こんにちは",target("s"),o)).isEmpty();
 }
 @Test void defaultsAreOffAndInvalidOptionsCannotEnableAnything(){
  var o=VoiceAdvancedOptions.defaults();assertThat(o.wakeEnabled()).isFalse();assertThat(o.interruptEnabled()).isFalse();assertThat(o.ttsEnabled()).isFalse();
  assertThatThrownBy(()->new VoiceAdvancedOptions(true,"",false,false,o.ttsVoice(),800)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new VoiceAdvancedOptions(false,"れい",false,true,"",800)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new VoiceAdvancedOptions(false,"れい",false,false,o.ttsVoice(),249)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void wakeRequiresPrefixBoundaryAndPreservesLiteralCommandText(){
  var gate=new VoiceWakeGate(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));var o=VoiceAdvancedOptions.defaults();
  assertThat(gate.filter("Ａ.txt を確認",target("s"),o)).contains("Ａ.txt を確認");
  o=new VoiceAdvancedOptions(true,"れい",false,false,o.ttsVoice(),800);
  assertThat(gate.filter("これはれいへの依頼",target("s"),o)).isEmpty();
  assertThat(gate.filter("れいめいを確認",target("s"),o)).isEmpty();
  assertThat(gate.filter("レイ、Ａ.txt を確認",target("s"),o)).contains("Ａ.txt を確認");
  assertThat(gate.filter("れい、こんにちは",target("s"),o)).contains("こんにちは");
 }
 @Test void standaloneWakeArmsOneUtteranceAndCannotCrossSession(){
  var gate=new VoiceWakeGate(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));var o=VoiceAdvancedOptions.defaults();o=new VoiceAdvancedOptions(true,"れい",false,false,o.ttsVoice(),800);
  assertThat(gate.filter("れい。",target("s"),o)).isEmpty();assertThat(gate.filter("こんにちは",target("s"),o)).contains("こんにちは");
  assertThat(gate.filter("次の入力",target("s"),o)).isEmpty();gate.filter("れい",target("s"),o);
  assertThat(gate.filter("こんにちは",target("other"),o)).isEmpty();
  gate.filter("れい",target("s"),o);gate.reset();assertThat(gate.filter("こんにちは",target("s"),o)).isEmpty();
 }
 @Test void echoSuppressionInvalidatesSpanningRecognitionAndIncludesTail(){
  var tick=new AtomicLong();var gate=new VoiceAudioGate(tick::get);long before=gate.epoch();assertThat(gate.allowed(before)).isTrue();
  try(var playback=gate.playback(Duration.ofMillis(800))){assertThat(gate.suppressed()).isTrue();assertThat(gate.allowed(before)).isFalse();}
  assertThat(gate.suppressed()).isTrue();tick.set(800_000_000L);assertThat(gate.suppressed()).isFalse();assertThat(gate.allowed(before)).isFalse();
  assertThat(gate.allowed(gate.epoch())).isTrue();
 }
}
