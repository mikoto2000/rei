package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Clock;
import dev.mikoto2000.rei.llm.capture.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
class CaptureCommandsControllerTest {
 @TempDir Path directory;
 @Test void reservationsAreClientAndSessionSpecificAndRawRequiresExplicitApproval()throws Exception {
  var registry=new ProjectRegistry(directory.resolve("projects.json"));var project=registry.resolve(Files.createDirectory(directory.resolve("project")));
  var lifecycle=new SessionLifecycle(new dev.mikoto2000.rei.conversation.FileSessionRepository(directory.resolve("sessions.json")),Clock.systemUTC());var session=lifecycle.create(project,"capture");
  var store=new CaptureStore();var api=new CaptureCommandsController(store,registry,lifecycle);String first=java.util.UUID.randomUUID().toString(),second=java.util.UUID.randomUUID().toString();
  api.next(first,new CaptureCommandsController.Scope(project.id(),session.sessionId()));
  assertFalse(store.accept("cli:"+second,session.sessionId(),"submission","other"));
  assertTrue(store.accept("cli:"+first,session.sessionId(),"submission","run"));
  var attempt=store.prepare(store.logicalCall("run",null),"secret-original".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/plain",null);
  assertThrows(IllegalArgumentException.class,()->api.raw(attempt.attemptId(),new CaptureCommandsController.Approval(false)));
  assertTrue(api.raw(attempt.attemptId(),new CaptureCommandsController.Approval(true)).text().contains("secret-original"));
  var output=directory.resolve("body.txt");api.export(attempt.attemptId(),new CaptureCommandsController.Export(output.toString(),true));assertEquals("secret-original",Files.readString(output));
  api.export(attempt.attemptId(),new CaptureCommandsController.Export(output.toString(),true));assertEquals("secret-original",Files.readString(output));
  api.clear();assertTrue(store.sessions().isEmpty());
 }
}
