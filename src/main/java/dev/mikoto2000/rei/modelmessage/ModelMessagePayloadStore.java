package dev.mikoto2000.rei.modelmessage;

import javax.sql.DataSource;
import org.springframework.ai.chat.messages.Message;

/** Durable protocol messages; callers publish references before making them visible. */
public final class ModelMessagePayloadStore {
  public record Scope(String project, String rootRun, String conversation) {}
  public record Ref(String id, String sha256) {}

  public ModelMessagePayloadStore(DataSource source) {}

  public Ref save(Scope scope, String run, String key, Message message) {
    throw new IllegalStateException("Model message persistence unavailable");
  }

  public Message load(Scope scope, Ref ref) {
    throw new IllegalStateException("Model message persistence unavailable");
  }
}
