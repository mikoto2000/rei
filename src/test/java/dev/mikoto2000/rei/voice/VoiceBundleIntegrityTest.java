package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声まとまり整合性の結果と状態の契約を検証するテスト。 */
class VoiceBundleIntegrityTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void incompleteAndCorruptBundleCannotInitializeNativeRuntime() throws Exception {
    try (var factory = new SherpaBackendFactory(() -> directory)) {
      assertThatThrownBy(() -> factory.open(VoiceSettings.defaults()))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("jvm.jar");
      Files.write(directory.resolve("jvm.jar"), new byte[187490]);
      assertThatThrownBy(() -> factory.open(VoiceSettings.defaults()))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("SHA-256 mismatch");
    }
  }

  /** 音声による対話で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void vadSkipsLargeAsrHashesButAsrIncludesItsExternalWeights() {
    assertThat(
            SherpaBackendFactory.roleAssets(true, false).stream()
                .map(VoiceModelManifest.Asset::path))
        .containsExactly("jvm.jar", "native.jar", "models/silero_vad.onnx");
    assertThat(
            SherpaBackendFactory.roleAssets(false, true).stream()
                .map(VoiceModelManifest.Asset::path))
        .containsExactly(
            "jvm.jar",
            "native.jar",
            "models/turbo-encoder.onnx",
            "models/turbo-encoder.weights",
            "models/turbo-decoder.onnx",
            "models/turbo-tokens.txt");
    assertThat(SherpaBackendFactory.roleAssets(true, true))
        .isEqualTo(VoiceModelManifest.pinned().assets());
  }

  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void commandDeadlineEncompassesSequentialWorkersWithBoundedRecognition() {
    assertThat(VoiceRuntimeLimits.COMMAND_STARTUP)
        .isGreaterThan(VoiceRuntimeLimits.VAD_STARTUP.plus(VoiceRuntimeLimits.ASR_STARTUP));
    assertThat(VoiceRuntimeLimits.ASR_REQUEST).isEqualTo(java.time.Duration.ofMinutes(3));
    assertThat(VoiceRuntimeLimits.VAD_REQUEST).isEqualTo(java.time.Duration.ofSeconds(5));
  }
}
