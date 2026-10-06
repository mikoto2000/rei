package dev.mikoto2000.rei.core.process;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildTestFailureDiagnosisTest {
  RunCommandResult result(String status,Integer exit,String out,String err,boolean timeout) {
    return new RunCommandResult(status,"foreground","foreground",exit,out,err,null,null,timeout,null);
  }
  @Test void extractsReportedTestsAndCausesWithStreamEvidence() {
    var value=result("failed",1,"[ERROR] demo.WidgetTest.fails -- Time elapsed: 0.1 s <<< FAILURE!\n[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0",
        "java.lang.IllegalStateException: wrapper\nCaused by: java.io.IOException: disk unavailable",false).diagnosis();
    assertEquals("FAILED",value.outcome());assertEquals("TEST_FAILURE",value.category());
    assertTrue(value.failedTests().contains("demo.WidgetTest.fails"));
    assertTrue(value.causes().stream().anyMatch(e->e.source().equals("stderr") && e.text().contains("disk unavailable")));
    assertFalse(value.nextActions().isEmpty());
  }
  @Test void distinguishesCompilerDependencyAndUnknownExitWithoutInventingCause() {
    assertEquals("COMPILATION_FAILURE",result("failed",1,"[ERROR] /repo/App.java:[12,3] cannot find symbol","",false).diagnosis().category());
    assertEquals("DEPENDENCY_FAILURE",result("failed",1,"[ERROR] Could not resolve dependencies for project demo","",false).diagnosis().category());
    var unknown=result("failed",7,"","",false).diagnosis();
    assertEquals("UNKNOWN",unknown.category());assertTrue(unknown.evidence().isEmpty());assertTrue(unknown.causes().isEmpty());
  }
  @Test void timeoutRunningAndKilledAreProcessFactsNotTestFailures() {
    assertEquals("TIMED_OUT",result("failed",-1,"","",true).diagnosis().outcome());
    assertEquals("RUNNING",result("running",null,"","",false).diagnosis().outcome());
    var killed=new BackgroundProcessSnapshot("proc",1,BackgroundProcessStatus.KILLED,137,Instant.EPOCH,Instant.EPOCH,1,List.of(),List.of(),true,"killed");
    assertEquals("CANCELLED",killed.diagnosis().outcome());
    var missing=new BackgroundProcessSnapshot("missing",-1,BackgroundProcessStatus.FAILED,null,null,null,0,List.of(),List.of(),false,"process not found");
    assertEquals("UNKNOWN",missing.diagnosis().outcome());
  }
  @Test void redactsBeforeEvidenceClippingAndBoundsAnalysis() {
    var value=result("failed",1,"Caused by: java.lang.Exception: token=secret-value", "password=\"secret with spaces\" java.lang.Exception: failed",false).diagnosis();
    assertFalse(value.toString().contains("secret-value"));assertFalse(value.toString().contains("secret with spaces"));
    var oversized=result("failed",1,"x".repeat(70000),"",false).diagnosis();
    assertTrue(oversized.partial());assertEquals("UNKNOWN",oversized.category());
    var many=result("failed",1,"Caused by: java.lang.Exception: failed\n".repeat(100),"",false).diagnosis();
    assertTrue(many.partial());assertTrue(many.evidence().size()<=24);assertTrue(many.causes().size()<=8);
  }
  @Test void successfulOutputDoesNotTurnOrdinaryWordsIntoFailuresAndJsonIncludesDiagnosis() {
    var success=result("completed",0,"failure recovery example\nTests run: 2, Failures: 0, Errors: 0, Skipped: 0","",false);
    assertEquals("NONE",success.diagnosis().category());assertEquals("SUCCEEDED",success.diagnosis().outcome());
    String json=new org.springframework.ai.tool.execution.DefaultToolCallResultConverter().convert(success,null);
    assertTrue(json.contains("\"diagnosis\""));assertTrue(json.contains("SUCCEEDED"));
  }
  @Test void testClassSummaryIsNotAReportedTestNameAndNegativeTestOutputDoesNotOverrideExitCode() {
    var value=result("failed",1,"[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in demo.AppTest","",false).diagnosis();
    assertEquals("TEST_FAILURE",value.category());assertTrue(value.failedTests().isEmpty());
    var negative=result("completed",0,"java.lang.IllegalStateException: expected negative case","",false).diagnosis();
    assertEquals("SUCCEEDED",negative.outcome());assertEquals("EXCEPTION_REPORTED",negative.category());
    assertTrue(negative.warnings().stream().anyMatch(w->w.contains("exit code 0")));
  }
  @Test void aRealManagedProcessReturnsDiagnosticEvidenceWithoutStartingAnotherCommand() throws Exception {
    var manager=new BackgroundProcessManager(new dev.mikoto2000.rei.core.service.SystemShellService());
    try {
      String runtimeExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java").toString();
      var started=manager.spawnCommandLine(List.of(runtimeExecutable,"-cp",System.getProperty("java.class.path"),FailureFixture.class.getName()),java.nio.file.Path.of(".").toAbsolutePath());
      var observed=manager.await(started.processId(),java.time.Duration.ofSeconds(10));
      assertEquals("FAILED",observed.diagnosis().outcome());assertEquals("TEST_FAILURE",observed.diagnosis().category());
      assertTrue(observed.diagnosis().failedTests().contains("demo.AppTest.fail"));
      assertTrue(observed.diagnosis().causes().stream().anyMatch(e->e.source().equals("stderr")));
    }finally{manager.shutdown();}
  }
  public static class FailureFixture {
    public static void main(String[] args){System.out.println("[ERROR] demo.AppTest.fail -- Time elapsed: 0.1 s <<< FAILURE!");System.err.println("Caused by: java.lang.IllegalStateException: fixture failure");System.exit(1);}
  }
}
