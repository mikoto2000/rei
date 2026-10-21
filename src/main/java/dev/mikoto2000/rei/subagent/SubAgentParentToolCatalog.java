package dev.mikoto2000.rei.subagent;

import java.util.ArrayList;
import java.util.List;

/**
 * 補助エージェントへの委譲に必要な補助エージェント親ツール利用可能な項目を扱う。
 *
 * <p>Advertise only enabled parent features; callbacks still enforce authority at execution time.
 */
public final class SubAgentParentToolCatalog {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SubAgentParentToolCatalog() {}

  /** 補助エージェントへの委譲で用いる名前一覧を返す。 */
  public static List<String> names(SubAgentProperties settings) {
    var names = new ArrayList<>(List.of("delegateTask", "delegateTasks"));
    if (settings.isDurableEnabled()) {
      names.addAll(
          List.of(
              "listDurableSubAgents", "getDurableSubAgent", "resumeSubAgent", "reconcileSubAgent"));
      if (settings.isDagEnabled())
        names.addAll(List.of("delegateTaskGraph", "getSubAgentGraph", "resumeSubAgentGraph"));
      if (settings.isConsensusEnabled()) {
        names.add("compareSubAgentAnswers");
        if (settings.isConsensusJudgeEnabled()) names.add("judgeSubAgentAnswers");
      }
    }
    return List.copyOf(names);
  }
}
