package dev.mikoto2000.rei.voice;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
class VoiceProvenancePersistenceTest {
  @TempDir Path root;
  @Test void durableRunRestoresVoiceSafetyAfterRegistryRestart() {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("runs.db"));
    var clock=Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"),ZoneOffset.UTC);
    var voice=new AgentRunContext("voice-run","session",root,"project").asVoiceInput();
    try(var registry=new RunRegistry(clock,source)){registry.register(voice);}
    try(var registry=new RunRegistry(clock,source)){assertThat(registry.get("voice-run").context().voiceInput()).isTrue();}
  }
  @Test void checkpointCopiesAndResumesPreserveVoiceButLegacyDefaultsToText() {
    var original=PersistentCheckpoint.initial("task","project","session","run",root,"request");
    assertThat(original.voiceInput()).isFalse();
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var fields=mapper.convertValue(original,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    fields.put("voiceInput",true);var voice=mapper.convertValue(fields,PersistentCheckpoint.class);
    assertThat(voice.revision(1).voiceInput()).isTrue();assertThat(voice.resume("next").voiceInput()).isTrue();
    fields.remove("voiceInput");assertThat(mapper.convertValue(fields,PersistentCheckpoint.class).voiceInput()).isFalse();
  }
}