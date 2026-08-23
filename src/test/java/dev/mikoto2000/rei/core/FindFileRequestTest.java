package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class FindFileRequestTest {

  @Test
  void acceptsKeywordsWithOptionalLimit() {
    assertDoesNotThrow(() -> new FindFileRequest(List.of("feed", "summary"), null));
    assertDoesNotThrow(() -> new FindFileRequest(List.of("feed"), 200));
  }

  @Test
  void rejectsMissingKeywordsAndInvalidLimits() {
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of(" "), null));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of("feed"), 0));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of("feed"), 201));
  }
}
