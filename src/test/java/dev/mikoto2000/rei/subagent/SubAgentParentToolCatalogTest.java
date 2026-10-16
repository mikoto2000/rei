package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubAgentParentToolCatalogTest {
  @Test
  void defaultsKeepOrdinaryDelegationAndHideDisabledManagementFeatures() {
    assertEquals(
        List.of("delegateTask", "delegateTasks"),
        SubAgentParentToolCatalog.names(new SubAgentProperties()));
  }

  @Test
  void featureDependenciesAreAppliedToEveryOptionalToolGroup() {
    var settings = new SubAgentProperties();
    settings.setDagEnabled(true);
    settings.setConsensusEnabled(true);
    settings.setConsensusJudgeEnabled(true);
    assertEquals(
        List.of("delegateTask", "delegateTasks"), SubAgentParentToolCatalog.names(settings));
    settings.setDurableEnabled(true);
    var names = SubAgentParentToolCatalog.names(settings);
    assertEquals(11, names.size());
    assertTrue(names.contains("judgeSubAgentAnswers"));
    assertTrue(names.contains("getSubAgentGraph"));
    settings.setConsensusEnabled(false);
    assertFalse(SubAgentParentToolCatalog.names(settings).contains("compareSubAgentAnswers"));
    assertFalse(SubAgentParentToolCatalog.names(settings).contains("judgeSubAgentAnswers"));
    settings.setConsensusEnabled(true);
    settings.setConsensusJudgeEnabled(false);
    assertTrue(SubAgentParentToolCatalog.names(settings).contains("compareSubAgentAnswers"));
    assertFalse(SubAgentParentToolCatalog.names(settings).contains("judgeSubAgentAnswers"));
    settings.setDagEnabled(false);
    assertFalse(SubAgentParentToolCatalog.names(settings).contains("delegateTaskGraph"));
  }
}
