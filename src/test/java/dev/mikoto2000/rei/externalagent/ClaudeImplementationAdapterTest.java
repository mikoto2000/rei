package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClaudeImplementationAdapterTest {
  @TempDir Path root;
  @Test void implementationIsToolFreeEphemeralAndReturnsOnlyStructuredChanges()throws Exception {
    Path file=Files.writeString(root.resolve("A.txt"),"before\n");String hash=ImplementationProposal.sha256(Files.readAllBytes(file));
    var properties=new ClaudeCodeProperties();properties.setEnabled(true);properties.setImplementationEnabled(true);properties.setPersistSessions(true);
    var runner=new ExternalAgentProcessRunner(){@Override public Output run(List<String> command,Path cwd,String input,Duration total,Duration idle,int bytes,java.util.function.BooleanSupplier cancelled){
      if(command.contains("--version"))return ClaudeCodeExtensionsAdapterTest.output("2.1.286 (Claude Code)");if(command.contains("--help"))return ClaudeCodeExtensionsAdapterTest.output("supported");if(command.contains("auth"))return ClaudeCodeExtensionsAdapterTest.output("{\"authMethod\":\"claude.ai\"}");
      assertEquals("",command.get(command.indexOf("--tools")+1));assertTrue(command.contains("--no-session-persistence"));assertFalse(command.contains("--resume"));assertTrue(command.get(command.indexOf("--json-schema")+1).contains("expectedSha256"));assertTrue(input.contains(hash));
      return ClaudeCodeExtensionsAdapterTest.output("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"summary\":\"proposal\",\"findings\":[],\"warnings\":[],\"implementation\":{\"edits\":[{\"path\":\"A.txt\",\"expectedSha256\":\""+hash+"\",\"replacement\":\"after\\n\"}]}}}");
    }};
    var result=new ClaudeCodeExternalAgentExecutor(properties,runner).execute(new ExternalAgentRequest(ExternalAgentRequest.Agent.CLAUDE,ExternalAgentRequest.Action.IMPLEMENT,"implement",root,file,"","run","id"),()->false);
    assertTrue(result.success(),result.summary());assertNotNull(result.implementation());assertNull(result.externalSessionId());assertEquals("before\n",Files.readString(file));
  }
}
