package dev.mikoto2000.rei.core.stagnation;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
@org.junit.jupiter.api.Tag("integration")
class EvidenceProgressTest {
  @TempDir Path root;
  java.util.List<ProgressEvidence> observe(ProgressEvaluator e,String tool,String result){return e.afterTool(tool,"{}",result,e.beforeTool(tool,"{}"));}
  @Test void searchCallsAndNewUrlsAloneDoNotProveProgress(){
    var e=new ProgressEvaluator(root);
    assertThat(observe(e,"webSearch","[{\"url\":\"https://one.example\",\"title\":\"new URL\",\"snippet\":\"metadata only\"}]" )).isEmpty();
    assertThat(observe(e,"webSearchAndRead","{\"query\":\"new query\",\"results\":[],\"searchQueries\":1,\"pageAttempts\":1}" )).isEmpty();
    assertThat(observe(e,"fetchUrlContent","{\"success\":false,\"errorType\":\"timeout\"}" )).isEmpty();
  }
  @Test void actualBodyDeduplicatesAcrossUrlsToolsAndChangingMetadata(){
    var e=new ProgressEvaluator(root);
    String body="{\"results\":[{\"url\":\"https://one.example\",\"content\":\"useful fact\",\"retrievedAt\":\"yesterday\"}]}";
    assertThat(observe(e,"webSearchAndRead",body)).extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.NEW_INFORMATION);
    assertThat(observe(e,"webSearchAndRead",body.replace("yesterday","today"))).isEmpty();
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"finalUrl\":\"https://two.example\",\"content\":\"useful fact\"}")).isEmpty();
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"content\":\"new useful fact\"}")).hasSize(1);
  }
  @Test void hybridQuestionAndAssessmentAreNotEvidenceButBodyIs(){
    var e=new ProgressEvaluator(root);
    assertThat(observe(e,"searchKnowledge","Question: changed\nWeb retrieval: {\"reason\":\"unknown\"}")).isEmpty();
    assertThat(observe(e,"searchKnowledge","Question: changed\nWeb external_untrusted sourceType=primary: {\"url\":\"https://one.example\",\"content\":\"useful fact\"}")).hasSize(1);
    assertThat(observe(e,"webSearchAndRead","{\"results\":[{\"content\":\"useful fact\"}]}")).isEmpty();
  }
  @Test void appliedChangeSetNeedsTheActualProposedRevisionAndCountsOnce()throws Exception{
    var e=new ProgressEvaluator(root);Files.writeString(root.resolve("A"),"after");
    String sha=new dev.mikoto2000.rei.goal.FileGoalVerifier().fingerprint(root.toRealPath(),"A").sha256();
    String receipt="{\"id\":\"change-1\",\"path\":\"A\",\"status\":\"APPLIED\",\"baselineSha256\":\""+"0".repeat(64)+"\",\"proposedSha256\":\""+sha+"\",\"currentSha256\":\""+sha+"\"}";
    assertThat(observe(e,"applyTextChangeSet",receipt)).extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.STATE_CHANGED);
    assertThat(observe(e,"applyTextChangeSet",receipt)).isEmpty();
    Files.writeString(root.resolve("A"),"changed later");assertThat(observe(e,"applyTextChangeSet",receipt)).isEmpty();
  }
  @Test void progressFingerprintNeverReadsOutsideProject()throws Exception{
    Path outside=Files.createTempFile("rei-progress-outside-",".txt");
    try{
      Files.writeString(outside,"before");var e=new ProgressEvaluator(root);
      String args=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("path",outside.toString()));
      var before=e.beforeTool("writeTextFile",args);Files.writeString(outside,"after");
      assertThat(e.afterTool("writeTextFile",args,"{\"success\":true}",before)).isEmpty();
    }finally{Files.deleteIfExists(outside);}
  }
  @Test void progressCannotReplenishReplanOrModelBudgets(){
    var budget=new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,3);
    var context=new RunExecutionContext("run",budget,new ProgressEvaluator(root),new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),e->{});
    for(int i=0;i<4;i++){context.beginIteration();context.endIteration();}context.requestReplan();
    for(int i=0;i<2;i++){
      context.beginIteration();context.recordTool("fetchUrlContent","{}","{\"success\":true,\"content\":\"new fact-"+i+"\"}",context.evaluator().beforeTool("fetchUrlContent","{}"));context.endIteration();
    }
    assertThat(context.detector().replanCount()).isZero(); // Recovery is local; the absolute budget is separate.
    for(int i=0;i<4;i++){context.beginIteration();context.endIteration();}
    org.assertj.core.api.Assertions.assertThatThrownBy(context::requestReplan).hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(budget.replanCount()).isEqualTo(1);
    for(int i=0;i<3;i++)context.consumeNextLlmCall();
    org.assertj.core.api.Assertions.assertThatThrownBy(context::consumeNextLlmCall).hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
  }
  @Test void evidenceIncludesCurrentRevisionAndOldConstructorRemainsCompatible()throws Exception{
    Files.writeString(root.resolve("A"),"before");var e=new ProgressEvaluator(root);String args="{\"path\":\"A\"}";
    var before=e.beforeTool("writeTextFile",args);Files.writeString(root.resolve("A"),"after");
    var evidence=e.afterTool("writeTextFile",args,"{\"success\":true}",before).getFirst();
    assertThat(evidence.revision()).isEqualTo(new dev.mikoto2000.rei.goal.FileGoalVerifier().fingerprint(root.toRealPath(),"A").sha256());
    assertThat(new ProgressEvidence(ProgressEvent.NEW_INFORMATION,"legacy","tool").revision()).isNull();
    assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(evidence)).contains("revision");
  }
  @Test void metadataOnlyRecoveryStillHasNoUsefulWebEvidence(){
    var e=new ProgressEvaluator(root);observe(e,"fetchUrlContent","{\"success\":false,\"errorType\":\"timeout\"}");
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"finalUrl\":\"https://one.example\"}")).isEmpty();
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"content\":\"useful body\"}")).extracting(ProgressEvidence::kind).contains(ProgressEvent.ERROR_RESOLVED,ProgressEvent.NEW_INFORMATION);
  }
  @Test void hugeFilesDoNotCauseUnboundedProgressHashing()throws Exception{
    Files.writeString(root.resolve("large"),"a".repeat(1048577));var e=new ProgressEvaluator(root);String args="{\"path\":\"large\"}";
    var before=e.beforeTool("writeTextFile",args);Files.writeString(root.resolve("large"),"b".repeat(1048577));
    assertThat(e.afterTool("writeTextFile",args,"{\"success\":true}",before)).isEmpty();
  }
  @Test void ledgerSaturationNeverMakesAnOldBodyNewAgain(){
    var e=new ProgressEvaluator(root);
    for(int i=0;i<1024;i++)assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"content\":\"fact-"+i+"\"}")).hasSize(1);
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"content\":\"overflow\"}")).isEmpty();
    assertThat(observe(e,"fetchUrlContent","{\"success\":true,\"content\":\"fact-0\"}")).isEmpty();
  }  @Test void webEvidenceNamesTheSourceAndHashesTheActualBody()throws Exception{
    var e=new ProgressEvaluator(root);var evidence=observe(e,"fetchUrlContent","{\"success\":true,\"finalUrl\":\"https://one.example\",\"content\":\"useful fact\"}").getFirst();
    String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("useful fact".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(evidence.revision()).isEqualTo(sha);assertThat(evidence.source()).startsWith("web-source:");
  }
  @Test void commandAliasesWithIdenticalOutputDoNotCreateNewEvidence(){
    var e=new ProgressEvaluator(root);String result="{\"exitCode\":0,\"stdout\":\"one observation\"}";
    assertThat(e.afterTool("runCommand","{\"command\":\"first\"}",result,e.beforeTool("runCommand","{}"))).hasSize(1);
    assertThat(e.afterTool("runCommand","{\"command\":\"second\"}",result,e.beforeTool("runCommand","{}"))).isEmpty();
  }  @Test void actualHybridCallbackDeduplicatesBodyWhenQueryAndAssessmentChange()throws Exception{
    var service=org.mockito.Mockito.mock(dev.mikoto2000.rei.search.SearchKnowledgeService.class);
    var page=new dev.mikoto2000.rei.websearch.WebSearchPage("title","https://one.example","snippet",null,"actual callback body");
    org.mockito.Mockito.when(service.search(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.eq(1),org.mockito.ArgumentMatchers.eq(0.5),org.mockito.ArgumentMatchers.isNull()))
      .thenAnswer(inv->new dev.mikoto2000.rei.search.SearchKnowledgeResult(inv.getArgument(0),java.util.List.of(),dev.mikoto2000.rei.websearch.WebSearchContext.primaryOnly(java.util.List.of(page)),null));
    var callback=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new dev.mikoto2000.rei.search.SearchTools(service)).build().getToolCallbacks()[0];
    String args="{\"query\":\"first\",\"vectorTopK\":1,\"webTopK\":1,\"threshold\":0.5,\"source\":null}";
    var context=new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("testContext",true));
    String first=callback.call(args,context),second=callback.call(args.replace("first","second"),context);
    assertThat(first).isNotEqualTo(second);var e=new ProgressEvaluator(root);
    assertThat(observe(e,"searchKnowledge",first)).hasSize(1);assertThat(observe(e,"searchKnowledge",second)).isEmpty();
  }
  @Test void oldEvidenceJsonAndMixedWebResultsRemainCompatible()throws Exception{
    var old=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"kind\":\"NEW_INFORMATION\",\"description\":\"old\",\"source\":\"tool\"}",ProgressEvidence.class);
    assertThat(old.revision()).isNull();var e=new ProgressEvaluator(root);
    assertThat(observe(e,"webSearchAndRead","{\"results\":[{\"success\":false,\"errorType\":\"timeout\"},{\"content\":\"valid sibling\"}]}")).hasSize(1);
    assertThat(observe(e,"searchKnowledge","Question: changed\nVector: - source=doc | docId=1 | chunk=0 | score=0.5 | snippet=actual indexed fact")).hasSize(1);
    assertThat(observe(e,"searchKnowledge","Question: other\nVector: - source=doc | docId=1 | chunk=0 | score=0.9 | snippet=actual indexed fact")).isEmpty();
  }  @Test void sameFileLinesReadThroughDifferentToolsAreNotNewInformation(){
    var e=new ProgressEvaluator(root);String args="{\"path\":\"A\"}";
    assertThat(observe(e,"readMultiFile","[{\"path\":\"A\",\"content\":[\"one\",\"two\"]}]")).hasSize(1);
    assertThat(e.afterTool("readTextFile",args,"one\ntwo\n",e.beforeTool("readTextFile",args))).isEmpty();
    assertThat(e.afterTool("readTextFile",args.replace("A","./A"),"one\ntwo\n",e.beforeTool("readTextFile",args))).isEmpty();
    assertThat(e.afterTool("readTextFile",args,"one\nnew line\n",e.beforeTool("readTextFile",args))).hasSize(1);
  }}
