package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CodexImplementationAdapterTest {
  @TempDir Path root;
  @Test void readonlyStructuredImplementationUsesSharedBudgetAndNeverAcceptsMalformedOutput()throws Exception {
    Files.writeString(root.resolve("A.txt"),"before");var calls=new AtomicInteger();var tokens=new AtomicInteger();
    String hash=ImplementationProposal.sha256("before".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var runner=new ExternalAgentProcessRunner(){@Override public Output run(List<String> command,Path cwd,String input,java.time.Duration total,java.time.Duration idle,int limit,java.util.function.BooleanSupplier cancelled){
      if(command.contains("--help"))return new Output(ExternalAgentResult.Status.SUCCESS,"--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json","",0,0,false);
      assertTrue(command.contains("default_permissions=\"rei_review\""));assertTrue(input.contains("Do not modify"));assertTrue(input.contains(hash));
      assertTrue(command.contains("--ephemeral"),"Implementation never inherits a persisted review session");
      String text="{\"summary\":\"proposal\",\"findings\":[],\"warnings\":[],\"implementation\":{\"edits\":[{\"path\":\"A.txt\",\"expectedSha256\":\""+hash+"\",\"replacement\":\"after\"}]}}";
      try{return new Output(ExternalAgentResult.Status.SUCCESS,"{\"type\":\"turn.started\"}\n"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("type","item.completed","item",Map.of("type","agent_message","text",text)))+"\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}","",0,1,false);}catch(Exception e){throw new RuntimeException(e);}
    }};
    var budget=new dev.mikoto2000.rei.llm.ModelCallBudget(){public void run(){calls.incrementAndGet();}public boolean tokenLimitEnabled(){return true;}public void recordTotalTokens(Integer count){tokens.set(count);}};
    var request=new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX,ExternalAgentRequest.Action.IMPLEMENT,"implement",root,root.resolve("A.txt"),hash,"run","delegation");
    var properties=new CodexProperties();properties.setPersistSessions(true);
    var result=new CodexExternalAgentExecutor(properties,runner).execute(request,()->false,budget);
    assertTrue(result.success());assertNotNull(result.implementation());assertEquals("after",result.implementation().edits().getFirst().replacement());assertEquals(1,calls.get());assertEquals(5,tokens.get());assertNull(result.forEvaluation().implementation());
    var malformed=new ExternalAgentProcessRunner(){@Override public Output run(List<String> c,Path p,String i,java.time.Duration t,java.time.Duration d,int l,java.util.function.BooleanSupplier x){return c.contains("--help")?new Output(ExternalAgentResult.Status.SUCCESS,"--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json","",0,0,false):new Output(ExternalAgentResult.Status.SUCCESS,"unstructured","",0,0,false);}};
    assertEquals(ExternalAgentResult.Status.FAILED,new CodexExternalAgentExecutor(new CodexProperties(),malformed).execute(request,()->false).status());
  }
}
