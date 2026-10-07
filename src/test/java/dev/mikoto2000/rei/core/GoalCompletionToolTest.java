package dev.mikoto2000.rei.core;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
class GoalCompletionToolTest {
  @Test void modelEntryCanOnlyAttachForCapturedRunAndCannotChooseHumanAuthority()throws Exception{
    var gate=mock(GoalCompletionGate.class);var tools=new Tools();tools.setGoalCompletionGate(gate);var owner=new AgentRunContext("run","session",Path.of("."),"project");var proof=new GoalCompletionGate.Proof(null,List.of());
    try(var scope=AgentRunScope.open(owner)){tools.getGoalCompletionDefinition("goal");tools.attachGoalCompletionEvidence("goal",proof);}verify(gate).inspect(owner,"goal");verify(gate).attach(owner,"goal",proof,false);verifyNoMoreInteractions(gate);
    var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null));assertEquals(Set.of(ActionCapability.READ),policy.capabilities("getGoalCompletionDefinition"));assertEquals(Set.of(ActionCapability.LOCAL_WRITE),policy.capabilities("attachGoalCompletionEvidence"));
  }
}
