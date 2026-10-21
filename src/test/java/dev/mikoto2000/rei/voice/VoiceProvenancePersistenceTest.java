package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 音声情報の来歴状態の保存の結果と状態の契約を検証するテスト。 */
class VoiceProvenancePersistenceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 永続化された実行について、音声安全性を復元することを確認する。 */
  @Test
  void durableRunRestoresVoiceSafetyAfterRegistryRestart() {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);
    var voice = new AgentRunContext("voice-run", "session", root, "project").asVoiceInput();
    try (var registry = new RunRegistry(clock, source)) {
      registry.register(voice);
    }
    try (var registry = new RunRegistry(clock, source)) {
      assertThat(registry.get("voice-run").context().voiceInput()).isTrue();
    }
  }

  /** 音声を保持することを確認する。 */
  @Test
  void checkpointCopiesAndResumesPreserveVoiceButLegacyDefaultsToText() {
    var original =
        PersistentCheckpoint.initial("task", "project", "session", "run", root, "request");
    assertThat(original.voiceInput()).isFalse();
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var fields =
        mapper.convertValue(
            original, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    fields.put("voiceInput", true);
    var voice = mapper.convertValue(fields, PersistentCheckpoint.class);
    assertThat(voice.revision(1).voiceInput()).isTrue();
    assertThat(voice.resume("next").voiceInput()).isTrue();
    fields.remove("voiceInput");
    assertThat(mapper.convertValue(fields, PersistentCheckpoint.class).voiceInput()).isFalse();
  }
}
