package dev.mikoto2000.rei.subagent;

import java.util.ArrayList;
import java.util.List;

/** Advertise only enabled parent features; callbacks still enforce authority at execution time. */
public final class SubAgentParentToolCatalog {
  private SubAgentParentToolCatalog() {}

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
