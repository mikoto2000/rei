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
}