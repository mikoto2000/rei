package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchCompactionTest {
  @Test
  void compactDeliveryReferencesSectionsWithoutRepeatingSource() throws Exception {
    var result = new Tools.SearchAndReadResult("a", List.of(new Tools.SearchMatch(0, 1, "same 日本語"),
        new Tools.SearchMatch(1, 1, "same 日本語")),
        List.of(new Tools.ReadSection(1, 1, List.of("same 日本語"), false)), null, false, false,
        "v", null, 0, List.of(), 0, true);
    String wire = new SearchReadResultConverter().convert(List.of(result), List.class);
    var tree = dev.mikoto2000.rei.storage.StorageDatabase.JSON.readTree(wire).get(0);
    assertEquals(0, tree.path("matches").get(0).path("sectionIndex").asInt(-1));
    assertFalse(tree.path("matches").get(0).has("content"));
    assertTrue(tree.path("sections").get(0).path("content").get(0).asText().contains("日本語"));
    assertTrue(dev.mikoto2000.rei.core.contextbudget.TokenEstimator.tokenizerAware().text(wire)
        < dev.mikoto2000.rei.core.contextbudget.TokenEstimator.tokenizerAware().text(
            dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(List.of(result))));
  }
}
