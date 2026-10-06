package dev.mikoto2000.rei.core;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TestReportDiagnosisTest {
  @TempDir Path root;
  private void report(String text)throws Exception {Files.writeString(root.resolve("TEST.xml"),text);}
  @Test void observesFailureErrorSkippedAndPreservesReportIdentity()throws Exception {
    report("""
        <testsuite tests="3" failures="1" errors="1" skipped="1">
          <testcase classname="example.Sample" name="assertion"><failure type="AssertionError" message="expected 2">Caused by: assertion</failure></testcase>
          <testcase classname="example.Sample" name="crash"><error type="IllegalStateException">broken</error></testcase>
          <testcase classname="example.Sample" name="skip"><skipped/></testcase>
        </testsuite>
        """);
    var result=new TestReportDiagnosisService().read(root,"TEST.xml");
    assertEquals(3,result.observed().tests());assertEquals(1,result.observed().failures());
    assertEquals(1,result.observed().errors());assertEquals(1,result.observed().skipped());
    assertEquals("example.Sample#assertion",result.failedTests().getFirst().test());
    assertEquals("FAILURE",result.failedTests().getFirst().kind());assertEquals("ERROR",result.failedTests().get(1).kind());
    assertTrue(result.sha256().matches("[a-f0-9]{64}"));assertNotNull(result.modifiedAt());assertFalse(result.partial());
    assertTrue(result.warnings().stream().anyMatch(w->w.contains("current process")));
    assertFalse(result.nextActions().isEmpty());
  }
  @Test void detectsIncompleteCountsAndNestedSuitesWithoutDoubleCounting()throws Exception {
    report("<testsuites tests='9' failures='0' errors='0' skipped='0'><testsuite tests='1'><testcase classname='A' name='ok'/></testsuite></testsuites>");
    var result=new TestReportDiagnosisService().read(root,"TEST.xml");
    assertEquals(1,result.observed().tests());assertEquals(9,result.reported().tests());assertTrue(result.partial());
    report("<testsuite><testsuite><testcase name='nested'/></testsuite></testsuite>");
    result=new TestReportDiagnosisService().read(root,"TEST.xml");assertEquals(1,result.observed().tests());
    assertNull(result.reported());assertTrue(result.partial());
  }
  @Test void rejectsDoctypeMalformedXmlAndOutsideOrSensitivePaths()throws Exception {
    var service=new TestReportDiagnosisService();
    report("<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///private'>]><testsuite>&x;</testsuite>");
    assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<testsuite>");assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<testsuite tests='1' failures='0' errors='0' skipped='0'><properties><testsuite><testcase name='not-a-result'/></testsuite></properties></testsuite>");
    assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    assertThrows(IllegalArgumentException.class,()->service.read(root,"../outside.xml"));
    assertThrows(IllegalArgumentException.class,()->service.read(root,".aws/credentials"));
    assertThrows(IllegalArgumentException.class,()->service.read(root,root.resolve("TEST.xml").toString()));
  }
  @Test void redactsBeforeClippingAndBoundsEvidenceWithoutCopyingOutputsOrProperties()throws Exception {
    String issue="<testcase classname='A' name='bad'><failure message='token=secret-value' type='AssertionError'>password=hidden-value "+"x".repeat(2200)+"</failure></testcase>";
    report("<testsuite tests='25' failures='25' errors='0' skipped='0'>"+issue.repeat(25)+"<system-out>private output</system-out><properties><property value='private property'/></properties></testsuite>");
    var result=new TestReportDiagnosisService().read(root,"TEST.xml");
    assertEquals(25,result.observed().failures());assertEquals(24,result.failedTests().size());assertTrue(result.partial());
    assertEquals(2048,result.failedTests().getFirst().detail().length());
    assertFalse(result.toString().contains("secret-value"));assertFalse(result.toString().contains("hidden-value"));
    assertFalse(result.toString().contains("private output"));assertFalse(result.toString().contains("private property"));
  }
  @Test void boundsBytesDepthNodesCasesAndRejectsInvalidCountOrRoot()throws Exception {
    var service=new TestReportDiagnosisService();
    report("x".repeat(1048577));assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<testsuite>".repeat(70)+"</testsuite>".repeat(70));assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<testsuite>"+"<testcase name='x'/>".repeat(9000)+"</testsuite>");assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<testsuite tests='1025' failures='0' errors='0' skipped='0'>"+"<testcase name='x'/>".repeat(1025)+"</testsuite>");
    var result=service.read(root,"TEST.xml");assertEquals(1024,result.observed().tests());assertTrue(result.partial());
    report("<testsuite tests='-1'/>");assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
    report("<unrelated/>");assertThrows(java.io.IOException.class,()->service.read(root,"TEST.xml"));
  }
  @Test void completeEmptyReportIsOnlyASavedObservationAndDigestChangesWithContent()throws Exception {
    report("<testsuite tests='0' failures='0' errors='0' skipped='0'/>");
    var service=new TestReportDiagnosisService();var first=service.read(root,"TEST.xml");
    assertFalse(first.partial());assertTrue(first.failedTests().isEmpty());
    report("<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase name='ok'/></testsuite>");
    var second=service.read(root,"TEST.xml");assertNotEquals(first.sha256(),second.sha256());
    assertFalse(second.partial());assertTrue(second.nextActions().getFirst().contains("current process"));
  }
  @Test void interruptedReadPropagatesCancellation()throws Exception {
    report("<testsuite/>");
    Thread.currentThread().interrupt();
    try {assertThrows(java.util.concurrent.CancellationException.class,()->new TestReportDiagnosisService().read(root,"TEST.xml"));}
    finally {Thread.interrupted();}
  }
  @Test void realToolUsesCapturedProjectAndExplicitReadOnlySubAgentAccess()throws Exception {
    report("<testsuite tests='1' failures='1' errors='0' skipped='0'><testcase name='bad'><failure message='expected'/></testcase></testsuite>");
    Files.createDirectories(root.resolve("target/surefire-reports"));Files.copy(root.resolve("TEST.xml"),root.resolve("target/surefire-reports/TEST.xml"));
    var callback=java.util.Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(new Tools()).build().getToolCallbacks())
        .filter(c->c.getToolDefinition().name().equals("diagnoseTestReport")).findFirst().orElseThrow();
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",root))) {
      String response=callback.call("{\"path\":\"target/surefire-reports/TEST.xml\"}");
      assertTrue(response.contains("FAILURE"));assertTrue(response.contains("target/surefire-reports/TEST.xml"));
      assertEquals(1,new Tools().diagnoseTestReport("TEST.xml").observed().failures());
    }
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
    assertEquals(java.util.Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("diagnoseTestReport"));
    var child=new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(java.util.Set.of("diagnoseTestReport"));
    assertTrue(child.effectiveTools(java.util.List.of()).isEmpty());child.validate(java.util.List.of("diagnoseTestReport"));
  }
}
