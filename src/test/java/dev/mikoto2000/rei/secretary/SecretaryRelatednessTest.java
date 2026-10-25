package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretaryRelatednessTest {
  @Test
  void localConceptsRelateSynonymsAcrossLanguagesWithoutAnyModel() {
    assertTrue(SecretaryRelatedness.related("旅の準備", List.of(), "travel planning", List.of()));
    assertTrue(SecretaryRelatedness.related("調査", List.of("release"), "情報収集", List.of()));
    assertFalse(SecretaryRelatedness.related("旅の準備", List.of(), "コードレビュー", List.of()));
    assertFalse(SecretaryRelatedness.related("初めての資料", List.of(), "別の説明", List.of()));
  }

  @Test
  void sharedUserClassificationIsAReportedRelationNotAnAutomaticMerge() {
    assertTrue(
        SecretaryRelatedness.related("first", List.of("Release"), "second", List.of("release")));
    assertFalse(SecretaryRelatedness.related("first", List.of(), "second", List.of()));
  }
}
