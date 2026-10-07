package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeCodeExtensionsAdapterTest {
  @TempDir Path temporary;
  @Test void persistedSessionResumesWithToolFreeSubscriptionBoundaryAndConfirmedNativeUuid()throws Exception {
    Path root=Files.createDirectory(temporary.resolve("project"));Path file=Files.writeString(root.resolve("A.txt"),"before\n");
    var properties=new ClaudeCodeProperties();properties.setEnabled(true);properties.setPersistSessions(true);properties.setNativeSessionDirectory(temporary.resolve("sessions"));
    var cwd=new ArrayList<Path>();var ids=new ArrayList<String>();var calls=new AtomicInteger();
    var runner=new ExternalAgentProcessRunner(){@Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier cancelled){
      if(command.contains("--version"))return output("2.1.286 (Claude Code)");if(command.contains("--help"))return output("supported");if(command.contains("auth"))return output("{\"authMethod\":\"oauth_token\"}");
      assertTrue(command.contains("--safe-mode"));assertEquals("",command.get(command.indexOf("--tools")+1));assertTrue(command.contains("--strict-mcp-config"));assertFalse(command.contains("--no-session-persistence"));
      String id=command.contains("--resume")?command.get(command.indexOf("--resume")+1):command.get(command.indexOf("--session-id")+1);ids.add(id);cwd.add(directory);calls.incrementAndGet();
      return output("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\""+id+"\",\"structured_output\":{\"summary\":\"ok\",\"findings\":[],\"warnings\":[]}}");
    }};
    var adapter=new ClaudeCodeExternalAgentExecutor(properties,runner);
    var first=adapter.execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.REVIEW,"review",root,file,"","run1","one"),()->false);
    assertTrue(first.success(),first.summary());assertNotNull(first.externalSessionId());assertTrue(Files.isDirectory(cwd.getFirst()));
    var second=adapter.execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.REVIEW,"continue",root,file,"","run2","two",first.externalSessionId()),()->false);
    assertTrue(second.success(),second.summary());assertEquals(first.externalSessionId(),second.externalSessionId());assertEquals(cwd.getFirst(),cwd.getLast());assertEquals(2,calls.get());
    Path foreign=Files.createDirectory(temporary.resolve("foreign"));Path foreignFile=Files.writeString(foreign.resolve("A.txt"),"before\n");
    assertFalse(adapter.execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.REVIEW,"continue",foreign,foreignFile,"","run3","three",first.externalSessionId()),()->false).success());assertEquals(2,calls.get());
  }
  @Test void fixProposalIsStructuredWithoutApplyingAndUnavailableSessionNeverStartsCli()throws Exception {
    Path root=Files.createDirectory(temporary.resolve("root"));Path file=Files.writeString(root.resolve("A.txt"),"before\n");var paid=new AtomicInteger();
    var properties=new ClaudeCodeProperties();properties.setEnabled(true);properties.setFixProposalsEnabled(true);
    var runner=new ExternalAgentProcessRunner(){@Override public Output run(List<String> command,Path directory,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier cancelled){
      if(command.contains("--version"))return output("2.1.286 (Claude Code)");if(command.contains("--help"))return output("supported");if(command.contains("auth"))return output("{\"authMethod\":\"claude.ai\"}");
      paid.incrementAndGet();assertTrue(command.get(command.indexOf("--json-schema")+1).contains("expectedText"));
      return output("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"summary\":\"proposal\",\"findings\":[],\"warnings\":[],\"proposal\":{\"path\":\"A.txt\",\"expectedText\":\"before\\n\",\"replacement\":\"after\\n\"}}}");
    }};
    var adapter=new ClaudeCodeExternalAgentExecutor(properties,runner);
    var fix=adapter.execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.PROPOSE_FIX,"propose",root,file,"","run","one"),()->false);
    assertTrue(fix.success(),fix.summary());assertNotNull(fix.proposedChange());assertEquals("before\n",Files.readString(file));assertEquals("after\n",fix.proposedChange().replacement());
    assertEquals(ExternalAgentResult.Status.REJECTED,adapter.execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.REVIEW,"resume",root,file,"","other","two",UUID.randomUUID().toString()),()->false).status());assertEquals(1,paid.get());
  }
  static ExternalAgentProcessRunner.Output output(String value){return new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS,value,"",0,1,false);}
}
