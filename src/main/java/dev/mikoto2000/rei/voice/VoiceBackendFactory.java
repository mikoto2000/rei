package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.net.URISyntaxException;

/** 音声バックエンドの情報を共通の形式で生成する。 */
@FunctionalInterface
public interface VoiceBackendFactory {
  /** 必要な対象を利用できるよう、音声バックエンド生成元の利用を開始する。 */
  VoiceBackend open(VoiceSettings settings)
      throws IOException, ReflectiveOperationException, URISyntaxException, InterruptedException;
}
