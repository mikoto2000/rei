package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContinuationLinesTest {
  @Test
  void evictsByEstimatedSizeAndSkipsOversizedEntries() {
    var cache = new ContinuationLines();
    var first = key("first");
    var second = key("second");
    var oversized = key("oversized");
    var lines = Collections.nCopies(70_000, "");

    cache.put(first, lines);
    cache.put(second, lines);
    cache.put(oversized, Collections.nCopies(140_000, ""));

    assertNull(cache.get(first));
    assertEquals(lines, cache.get(second));
    assertNull(cache.get(oversized));
  }

  @Test
  void evictsTheLeastRecentlyUsedEntryAtTheCountLimit() {
    var cache = new ContinuationLines();
    var lines = List.of("source");
    for (int i = 0; i < 32; i++) cache.put(key("file" + i), lines);
    assertEquals(lines, cache.get(key("file0")));

    cache.put(key("new"), lines);

    assertEquals(lines, cache.get(key("file0")));
    assertNull(cache.get(key("file1")));
  }

  private static ContinuationLines.Key key(String path) {
    return new ContinuationLines.Key(null, Path.of("."), Path.of(path), "version", null);
  }
}
