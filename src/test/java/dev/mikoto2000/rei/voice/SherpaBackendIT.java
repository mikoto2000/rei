package dev.mikoto2000.rei.voice;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/** Explicit opt-in local integration test; no model downloads or microphone recording. */
class SherpaBackendIT {
  @Test void actualTurboFp32JapaneseWavAndNativeResourceRelease() throws Exception {
    String configured = System.getProperty("rei.voice.test.bundle");
    assertThat(configured).as("Supply verified local bundle with -Drei.voice.test.bundle").isNotBlank();
    Path bundle = Path.of(configured);
    float[] samples;
    try (var source = AudioSystem.getAudioInputStream(bundle.resolve("models/japanese.wav").toFile());
         var pcm = AudioFormatConverter.toPcm16(source)) {
      samples = VoicePcm.decode(pcm.readAllBytes());
    }
    try (var factory = new SherpaBackendFactory(() -> bundle)) {
      for (int cycle=0;cycle<3;cycle++) {
        try (var backend = factory.open(VoiceSettings.defaults())) {
          var assembler = new SpeechSegmentAssembler(VoiceSettings.defaults(), Clock.systemUTC());
          var results = new ArrayList<String>();
          float[] padded = Arrays.copyOf(samples, samples.length+32000);
          for (int offset=0;offset+512<=padded.length;offset+=512) {
            var frame = Arrays.copyOfRange(padded,offset,offset+512);
            var decision = assembler.accept(frame,backend.vad().probability(frame));
            if (decision.segment()!=null) results.add(backend.recognizer().recognize(decision.segment()));
          }
          assertThat(results).isNotEmpty();
          assertThat(String.join("",results)).contains("こんにちは","音声入力","日本語");
        }
      }
    }
  }
}