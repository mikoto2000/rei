package dev.mikoto2000.rei.voice;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class VoiceFp32DefaultTest {
  @Test void productionBundleUsesPinnedTurboFp32AndCannotReuseInt8Approval() {
    var manifest=VoiceModelManifest.pinned();
    assertThat(manifest.id()).contains("fp32");
    assertThat(manifest.assets()).extracting(VoiceModelManifest.Asset::path)
        .contains("models/turbo-encoder.onnx","models/turbo-decoder.onnx")
        .noneMatch(path->path.contains(".int8."));
    assertThat(manifest.totalBytes()).isEqualTo(3247195692L);
    assertThat(manifest.assets().stream().filter(a->a.path().equals("models/turbo-encoder.onnx")).findFirst().orElseThrow().sha256())
        .isEqualTo("1b960f278564fb8bbacd544d4f85f4dd6d8a64d3aa89543d8f2c4021c926f976");
  }
}