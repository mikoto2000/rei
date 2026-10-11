package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientHistoryTest {
  @TempDir Path directory;
  @Test void concurrentClientsOwnDifferentFilesAndNeverMerge()throws Exception {
    Path firstFile,secondFile;
    try(var first=new ClientHistory(directory);var second=new ClientHistory(directory)) {
      first.record("hello");second.record("other");
      assertNotEquals(first.file(),second.file());
      firstFile=first.file();secondFile=second.file();
    }
    assertEquals(java.util.List.of("hello"),Files.readAllLines(firstFile));
    assertEquals(java.util.List.of("other"),Files.readAllLines(secondFile));
  }
  @Test void explicitSensitiveInputAndDisabledHistoryAreNeverWritten()throws Exception {
    try(var history=new ClientHistory(directory)) {
      history.record("/config api-key super-secret");history.record("Authorization: Bearer abc");history.record("/history off");
      history.enabled(false);history.record("private message");
      assertEquals(0,Files.size(history.file()));
    }
  }
  @Test void restoresCompletedHistoryWithoutReadingAnActiveClientsFile()throws Exception {
    try(var first=new ClientHistory(directory)){first.record("completed");}
    try(var next=new ClientHistory(directory)) {
      assertEquals(java.util.List.of("completed"),next.entries());next.record("still-active");
      try(var other=new ClientHistory(directory)){assertEquals(java.util.List.of("completed"),other.entries());}
    }
  }
  @Test void rawConfiguredAuthenticationKeyIsExcludedWithoutRelyingOnItsFormat()throws Exception {
    try(var history=new ClientHistory(directory,"opaque-credential-value")) {
      history.record("please use opaque-credential-value");
      assertFalse(history.accepts("opaque-credential-value"));
      assertEquals(0,Files.size(history.file()));
    }
  }
}
