package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 音声Fp32既定の結果と状態の契約を検証するテスト。 */
class VoiceFp32DefaultTest {
  /** 音声による対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void productionBundleUsesPinnedTurboFp32AndCannotReuseInt8Approval() {
    var manifest = VoiceModelManifest.pinned();
    assertThat(manifest.id()).contains("fp32");
    assertThat(manifest.assets())
        .extracting(VoiceModelManifest.Asset::path)
        .contains("models/turbo-encoder.onnx", "models/turbo-decoder.onnx")
        .noneMatch(path -> path.contains(".int8."));
    assertThat(manifest.totalBytes()).isEqualTo(3247195692L);
    assertThat(
            manifest.assets().stream()
                .filter(a -> a.path().equals("models/turbo-encoder.onnx"))
                .findFirst()
                .orElseThrow()
                .sha256())
        .isEqualTo("1b960f278564fb8bbacd544d4f85f4dd6d8a64d3aa89543d8f2c4021c926f976");
  }
}
