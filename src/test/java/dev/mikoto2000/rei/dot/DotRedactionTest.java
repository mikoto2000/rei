package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DotRedactionTest {
  @Test
  void credentialAssignmentsBearerAndPrivateKeysAreRemoved() {
    String safe =
        DotPayload.safe(
            "Authorization: Bearer dummy-credential token=hidden password=dummy-password");
    for (String secret : List.of("dummy-credential", "hidden", "dummy-password"))
      assertFalse(safe.contains(secret), safe);
    assertFalse(
        DotPayload.safe("-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----")
            .contains("not-a-key"));
  }

  @Test
  void driveUnixAndUncPathsAreRemoved() {
    assertEquals("[LOCAL_PATH]", DotPayload.safe("C:\\Users\\private\\file.txt"));
    assertEquals("[LOCAL_PATH]", DotPayload.safe("/home/private/file.txt"));
    assertEquals("[LOCAL_PATH]", DotPayload.safe("\\\\server\\private\\file.txt"));
  }

  @Test
  void sanitizationCannotExpandFieldsBeyondTheirByteLimit() {
    assertThrows(
        DotException.class,
        () -> DotPayload.publication(Map.of("instruction", "/a ".repeat(5000))));
  }
}
