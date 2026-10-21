package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import org.junit.jupiter.api.Test;

/** 経験の記録権限の結果と状態の契約を検証するテスト。 */
class EpisodePermissionTest {
  /** 過去の経験の活用で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void retrievalToolsAreIntrinsicallyReadOnly() {
    for (String tool :
        java.util.List.of(
            "searchMemoryHistory", "episodeGet", "episodeSources", "episodeProcessingStatus"))
      assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly(tool), tool);
  }
}
