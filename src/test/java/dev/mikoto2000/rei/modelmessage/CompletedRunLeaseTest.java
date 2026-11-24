package dev.mikoto2000.rei.modelmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;

@org.junit.jupiter.api.Tag("integration")
class CompletedRunLeaseTest {
 @TempDir Path directory;
 @Test void completionReleasesBorrowedRunLeasesButKeepsOtherOwnersScopesAndDurablePins() throws Exception {
  var source=new org.sqlite.SQLiteDataSource();
  source.setUrl("jdbc:sqlite:"+directory.resolve("leases.db"));
  var store=new ModelMessagePayloadStore(source);
  var scope=new ModelMessagePayloadStore.Scope("project:fixture",directory.toString(),"chat");
  var other=new ModelMessagePayloadStore.Scope("project:fixture",directory.toString(),"other-chat");
  var borrowed=store.save(scope,"older","source",new UserMessage("borrowed"));
  var own=store.save(scope,"completed","own",new UserMessage("own"));
  var foreign=store.save(other,"completed","foreign",new UserMessage("foreign"));
  store.publish(scope,borrowed,ModelMessagePayloadStore.ReferenceKind.RUN,"older");
  store.publish(scope,borrowed,ModelMessagePayloadStore.ReferenceKind.RUN,"completed");
  store.publish(scope,own,ModelMessagePayloadStore.ReferenceKind.RUN,"completed");
  store.publish(other,foreign,ModelMessagePayloadStore.ReferenceKind.RUN,"completed");
  for(var kind:java.util.List.of(ModelMessagePayloadStore.ReferenceKind.APPROVAL,
      ModelMessagePayloadStore.ReferenceKind.CHECKPOINT,ModelMessagePayloadStore.ReferenceKind.HISTORY))
    store.publish(scope,own,kind,"durable");
  assertThat(store.releaseRun(scope,"completed")).isEqualTo(2);
  assertThat(store.releaseRun(scope,"completed")).isZero();
  for(var reference:java.util.List.of(borrowed,own))
    assertThatThrownBy(()->store.load(scope,reference,ModelMessagePayloadStore.ReferenceKind.RUN,"completed"))
      .isInstanceOf(IllegalStateException.class);
  assertThat(store.load(scope,borrowed,ModelMessagePayloadStore.ReferenceKind.RUN,"older").getText()).isEqualTo("borrowed");
  assertThat(store.load(other,foreign,ModelMessagePayloadStore.ReferenceKind.RUN,"completed").getText()).isEqualTo("foreign");
  for(var kind:java.util.List.of(ModelMessagePayloadStore.ReferenceKind.APPROVAL,
      ModelMessagePayloadStore.ReferenceKind.CHECKPOINT,ModelMessagePayloadStore.ReferenceKind.HISTORY))
    assertThat(store.load(scope,own,kind,"durable").getText()).isEqualTo("own");
  try(var connection=java.sql.DriverManager.getConnection(source.getUrl());var statement=connection.createStatement()) {
    statement.executeUpdate("UPDATE model_message_payloads SET staged_until=0");
  }
  assertThat(store.collectExpired()).isZero();
  store.release(scope,borrowed,ModelMessagePayloadStore.ReferenceKind.RUN,"older");
  assertThat(store.collectExpired()).isEqualTo(1);
 }
}
