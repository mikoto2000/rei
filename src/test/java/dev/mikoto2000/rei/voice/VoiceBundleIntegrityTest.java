package dev.mikoto2000.rei.voice;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class VoiceBundleIntegrityTest {
  @TempDir Path directory;
  @Test void incompleteAndCorruptBundleCannotInitializeNativeRuntime() throws Exception {
    try(var factory=new SherpaBackendFactory(()->directory)){
      assertThatThrownBy(()->factory.open(VoiceSettings.defaults())).isInstanceOf(java.io.IOException.class).hasMessageContaining("jvm.jar");
      Files.write(directory.resolve("jvm.jar"),new byte[187490]);
      assertThatThrownBy(()->factory.open(VoiceSettings.defaults())).isInstanceOf(java.io.IOException.class).hasMessageContaining("SHA-256 mismatch");
    }
  }
  @Test void vadSkipsLargeAsrHashesButAsrIncludesItsExternalWeights() {
    assertThat(SherpaBackendFactory.roleAssets(true,false).stream().map(VoiceModelManifest.Asset::path))
      .containsExactly("jvm.jar","native.jar","models/silero_vad.onnx");
    assertThat(SherpaBackendFactory.roleAssets(false,true).stream().map(VoiceModelManifest.Asset::path))
      .containsExactly("jvm.jar","native.jar","models/turbo-encoder.onnx","models/turbo-encoder.weights","models/turbo-decoder.onnx","models/turbo-tokens.txt");
    assertThat(SherpaBackendFactory.roleAssets(true,true)).isEqualTo(VoiceModelManifest.pinned().assets());
  }
  @Test void commandDeadlineEncompassesSequentialWorkersWithBoundedRecognition() {
    assertThat(VoiceRuntimeLimits.COMMAND_STARTUP).isGreaterThan(VoiceRuntimeLimits.VAD_STARTUP.plus(VoiceRuntimeLimits.ASR_STARTUP));
    assertThat(VoiceRuntimeLimits.ASR_REQUEST).isEqualTo(java.time.Duration.ofMinutes(3));
    assertThat(VoiceRuntimeLimits.VAD_REQUEST).isEqualTo(java.time.Duration.ofSeconds(5));
  }
}