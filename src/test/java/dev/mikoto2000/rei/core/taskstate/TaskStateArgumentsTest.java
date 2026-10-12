package dev.mikoto2000.rei.core.taskstate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.AgentEventFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskStateArgumentsTest {
  @Test void stringListsAreRejectedBeforeApprovalRequestOrConsumption() {
    var approvals=mock(ToolApprovalRepository.class);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),new AgentEventFactory(Clock.systemUTC()),event->{});
    guard.setApprovals(approvals);
    String input="{\"goal\":\"review\",\"status\":\"IN_PROGRESS\",\"completed\":[\"read\"],\"pending\":\"delegate\\\", \\\"verify\"}";
    var error=assertThrows(InvalidToolArgumentsException.class,()->guard.check("updateTaskState",input,new AgentRunContext("run","session",Path.of("."),"project")));
    assertTrue(error.getMessage().contains("pending: must be a JSON array of strings"));
    assertTrue(error.getMessage().contains("[\"review\",\"verify\"]"));verifyNoInteractions(approvals);
  }
  @Test void allTypeProblemsAreReportedTogetherWithoutInputValues() {
    var error=assertThrows(InvalidToolArgumentsException.class,()->TaskStateArguments.validate("updateTaskState","{\"goal\":{},\"status\":\"private-secret\",\"completed\":\"private-completed\",\"pending\":[17,false,{}]}"));
    for(String path:List.of("goal:","status:","completed:","pending[0]:","pending[1]:","pending[2]:"))assertTrue(error.getMessage().contains(path));
    assertFalse(error.getMessage().contains("private-secret"));assertFalse(error.getMessage().contains("private-completed"));
  }
  @Test void optionalAndIgnoredValuesKeepExistingTaskStateCompatibility() {
    for(String status:List.of("IDLE","IN_PROGRESS","COMPLETED","BLOCKED")) {
      assertDoesNotThrow(()->TaskStateArguments.validate("updateTaskState","{\"status\":\""+status+"\"}"));
      assertDoesNotThrow(()->TaskStateArguments.validate("updateTaskState","{\"goal\":null,\"status\":\""+status+"\",\"completed\":null,\"pending\":[null,\"\",\"review\"]}"));
    }
    assertDoesNotThrow(()->TaskStateArguments.validate("updateTaskState","{\"goal\":\"\",\"status\":\"IN_PROGRESS\",\"completed\":[],\"pending\":[]}"));
    assertDoesNotThrow(()->TaskStateArguments.validate("readMultiFile","any unsupported tool input"));
  }
  @Test void malformedDuplicateTrailingAndNonObjectJsonAreRejected() {
    for(String input:List.of("not json","[]","null","{\"status\":\"IDLE\",\"status\":\"IN_PROGRESS\"}","{\"status\":\"IDLE\"} {}"))
      assertThrows(InvalidToolArgumentsException.class,()->TaskStateArguments.validate("updateTaskState",input));
  }
  @Test void diagnosticsRemainBoundedForLargeInvalidArrays() {
    var error=assertThrows(InvalidToolArgumentsException.class,()->TaskStateArguments.validate("updateTaskState","{\"status\":\"IN_PROGRESS\",\"pending\":["+String.join(",",Collections.nCopies(1000,"false"))+"]}"));
    assertTrue(error.getMessage().contains("992 additional items"));assertTrue(error.getMessage().length()<1200);
  }
}
