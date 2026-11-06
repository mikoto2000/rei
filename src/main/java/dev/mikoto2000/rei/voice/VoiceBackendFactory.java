package dev.mikoto2000.rei.voice;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 音声バックエンドの情報を共通の形式で生成する。 */
@FunctionalInterface
public interface VoiceBackendFactory {
  /** 必要な対象を利用できるよう、音声バックエンド生成元の利用を開始する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Native or isolated backends can report different checked startup failures.")
  VoiceBackend open(VoiceSettings settings) throws Exception;
}
