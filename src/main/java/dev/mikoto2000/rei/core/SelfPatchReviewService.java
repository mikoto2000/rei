package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.externalagent.*;

/** Explicit bounded test -> static patch review -> final test cycle. Never claims semantic correctness. */
public final class SelfPatchReviewService {
  public record Request(String testCommand,Integer timeoutSeconds) {}
  public record Finding(String path,long line,String kind) {}
  public record Snapshot(String version,List<String> changedFiles,List<String> untracked,boolean complete,List<String> warnings) {
    public Snapshot { changedFiles=List.copyOf(changedFiles);untracked=List.copyOf(untracked);warnings=List.copyOf(warnings); }
  }
  public record Review(boolean complete,List<Finding> findings,List<String> warnings) {
    public Review { findings=List.copyOf(findings);warnings=List.copyOf(warnings); }
  }
  public record TestObservation(String status,Integer exitCode,boolean timedOut,boolean logsTruncated,BuildTestFailureDiagnosis diagnosis) {}
  public record Result(String root,String status,String patchVersion,String currentVersion,List<String> changedFiles,
      TestObservation initialTest,Review review,TestObservation finalTest,List<String> nextActions,List<String> warnings) {}
  @FunctionalInterface public interface Capture { Snapshot capture(Path root,long deadline)throws IOException; }
  @FunctionalInterface public interface Reviewer { Review review(Path root,Snapshot snapshot,long deadline)throws IOException; }
  @FunctionalInterface public interface Tester { TestObservation test(Path root,String command,Duration timeout)throws IOException; }
  private final Capture capture;private final Reviewer reviewer;private final Tester tester;
  public SelfPatchReviewService(Capture capture,Reviewer reviewer,Tester tester){this.capture=capture;this.reviewer=reviewer;this.tester=tester;}
  public SelfPatchReviewService(SystemShellService shell) {
    var processes=new ExternalAgentProcessRunner();var inspector=new GitPatchInspector(processes);
    capture=inspector::capture;reviewer=inspector::review;
    tester=(root,command,timeout)->{
      var output=processes.run(shell.shellCommandLine(shell.resolveShell(System.getenv(),System.getProperty("os.name")),command),
          root,"",timeout,timeout,65536,()->Thread.currentThread().isInterrupted());
      if(output.status()==ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException("self-review cancelled");
      boolean timedOut=Set.of(ExternalAgentResult.Status.TOTAL_TIMEOUT,ExternalAgentResult.Status.INACTIVITY_TIMEOUT).contains(output.status());
      String status=output.status()==ExternalAgentResult.Status.SUCCESS?"completed":"failed";
      return new TestObservation(status,output.exitCode(),timedOut,output.truncated(),
          BuildTestFailureDiagnosis.command(status,output.exitCode(),timedOut,output.stdout(),output.stderr(),null));
    };
  }
  public Result verify(Path directory,Request request)throws IOException {
    return verify(directory,request,System.nanoTime()+Duration.ofSeconds(180).toNanos());
  }
  Result verify(Path directory,Request request,long deadline)throws IOException {
    RunCancellation.propagate(null);
    if(request==null || request.testCommand()==null || request.testCommand().isBlank() || request.testCommand().length()>4096)
      throw new IllegalArgumentException("An explicit test command of 1 to 4096 characters is required");
    int seconds=request.timeoutSeconds()==null?30:request.timeoutSeconds();
    if(seconds<1 || seconds>60)throw new IllegalArgumentException("Each test timeout must be 1 to 60 seconds");
    Path root=directory.toRealPath();remaining(deadline,1);
    Snapshot first=null,current=null;TestObservation initial=null,last=null;Review review=null;
    var warnings=new LinkedHashSet<String>();
    warnings.add("Static patch checks and explicit command exit only; semantic review and test selection remain the caller's responsibility");
    try {
      first=current=capture.capture(root,deadline);warnings.addAll(current.warnings());
      if(!current.complete())return result(root,"BLOCKED",first,current,initial,review,last,warnings);
      if(current.changedFiles().isEmpty())return result(root,"NO_PATCH",first,current,initial,review,last,warnings);
      initial=tester.test(root,request.testCommand(),remaining(deadline,seconds));RunCancellation.propagate(null);
      if(!passed(initial))return result(root,"INITIAL_TEST_FAILED",first,current,initial,review,last,warnings);
      current=capture.capture(root,deadline);warnings.addAll(current.warnings());
      if(!same(first,current))return result(root,"PATCH_CHANGED",first,current,initial,review,last,warnings);
      review=reviewer.review(root,current,deadline);RunCancellation.propagate(null);warnings.addAll(review.warnings());
      if(!review.complete())return result(root,"REVIEW_INCOMPLETE",first,current,initial,review,last,warnings);
      if(!review.findings().isEmpty())return result(root,"FIX_REQUIRED",first,current,initial,review,last,warnings);
      current=capture.capture(root,deadline);warnings.addAll(current.warnings());
      if(!same(first,current))return result(root,"PATCH_CHANGED",first,current,initial,review,last,warnings);
      last=tester.test(root,request.testCommand(),remaining(deadline,seconds));RunCancellation.propagate(null);
      if(!passed(last))return result(root,"FINAL_TEST_FAILED",first,current,initial,review,last,warnings);
      current=capture.capture(root,deadline);RunCancellation.propagate(null);remaining(deadline,1);warnings.addAll(current.warnings());
      return result(root,same(first,current)?"VERIFIED_CHECKS":"PATCH_CHANGED",first,current,initial,review,last,warnings);
    }catch(IOException error){RunCancellation.propagate(error);warnings.add("Patch inspection unavailable or shared cycle budget exhausted");return result(root,"BLOCKED",first,current,initial,review,last,warnings);}
  }
  static Duration remaining(long deadline,int seconds)throws IOException {
    RunCancellation.propagate(null);long nanos=deadline-System.nanoTime();if(nanos<=0)throw new IOException("Cycle budget exhausted");
    return Duration.ofNanos(Math.min(nanos,Duration.ofSeconds(seconds).toNanos()));
  }
  private static boolean same(Snapshot first,Snapshot current){return current.complete() && first.version().equals(current.version());}
  private static boolean passed(TestObservation test){return test!=null && test.status().equals("completed") && Objects.equals(test.exitCode(),0) && !test.timedOut() && !test.logsTruncated();}
  private static Result result(Path root,String status,Snapshot first,Snapshot current,TestObservation initial,Review review,TestObservation last,Set<String> warnings) {
    var actions=switch(status) {
      case "VERIFIED_CHECKS" -> List.<String>of();
      case "FIX_REQUIRED" -> List.of("Fix the reported patch findings using the existing editing tools, then invoke the complete self-review cycle again");
      case "INITIAL_TEST_FAILED","FINAL_TEST_FAILED" -> List.of("Inspect the test diagnosis, fix or clarify the failure, then repeat the complete cycle");
      case "PATCH_CHANGED" -> List.of("The patch changed or became incomplete during verification; repeat the complete cycle on the current worktree");
      case "NO_PATCH" -> List.of("Implement a patch before requesting this verification cycle");
      default -> List.of("Inspect the incomplete review or repository constraints before requesting another cycle");
    };
    return new Result(root.toString(),status,first==null?"":first.version(),current==null?"":current.version(),
        first==null?List.of():first.changedFiles(),initial,review,last,actions,List.copyOf(warnings));
  }
}
