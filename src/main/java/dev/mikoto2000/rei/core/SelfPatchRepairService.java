package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Apply only explicitly supplied saved proposals after a complete failed check; rerun the whole cycle. */
public final class SelfPatchRepairService {
  public record Repair(String id,String proposalSha256) {}
  public record Request(String testCommand,Integer timeoutSeconds,List<Repair> repairs) {
    public Request{repairs=repairs==null?List.of():List.copyOf(repairs);}
  }
  public record Receipt(String id,String status,String proposedSha256,String currentSha256) {}
  public record Result(String root,String status,List<SelfPatchReviewService.Result> rounds,List<Receipt> repairs,List<String> warnings) {
    public Result{rounds=List.copyOf(rounds);repairs=List.copyOf(repairs);warnings=List.copyOf(warnings);}
  }
  @FunctionalInterface public interface Cycle {SelfPatchReviewService.Result verify(Path root,SelfPatchReviewService.Request request,long deadline)throws IOException;}
  @FunctionalInterface public interface Fixer {Receipt apply(Path root,Repair repair,long deadline)throws IOException;}
  private final Cycle cycle;
  private final SelfPatchReviewService.Capture capture;
  private final Fixer fixer;
  public SelfPatchRepairService(Cycle cycle,SelfPatchReviewService.Capture capture,Fixer fixer){this.cycle=cycle;this.capture=capture;this.fixer=fixer;}
  public Result verify(Path directory,Request request)throws IOException {
    RunCancellation.propagate(null);validate(request);Path root=directory.toRealPath();
    long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();
    var rounds=new ArrayList<SelfPatchReviewService.Result>();var receipts=new ArrayList<Receipt>();
    var warnings=new ArrayList<String>();warnings.add("Explicit saved fixes and static patch/test checks only; no semantic correctness or test coverage guarantee");
    var test=new SelfPatchReviewService.Request(request.testCommand(),request.timeoutSeconds());
    try {
      for(int index=0;;index++) {
        SelfPatchReviewService.remaining(deadline,1);
        var result=cycle.verify(root,test,deadline);RunCancellation.propagate(null);rounds.add(result);
        if(!eligible(result))return output(root,result.status(),rounds,receipts,warnings);
        if(index>=request.repairs().size())return output(root,"REPAIR_LIMIT_REACHED",rounds,receipts,warnings);
        var before=capture.capture(root,deadline);RunCancellation.propagate(null);
        if(!before.complete() || !before.version().equals(result.currentVersion()))return output(root,"PATCH_CHANGED",rounds,receipts,warnings);
        SelfPatchReviewService.remaining(deadline,1);var repair=request.repairs().get(index);Receipt receipt;
        try {receipt=fixer.apply(root,repair,deadline);RunCancellation.propagate(null);}
        catch(IOException | RuntimeException error) {
          RunCancellation.propagate(error);receipts.add(new Receipt(repair.id(),"UNKNOWN",null,null));
          warnings.add("Repair outcome requires saved Change Set inspection; no automatic resend or next repair");
          return output(root,"REPAIR_FAILED",rounds,receipts,warnings);
        }
        if(receipt==null){receipts.add(new Receipt(repair.id(),"UNKNOWN",null,null));return output(root,"REPAIR_REJECTED",rounds,receipts,warnings);}
        receipts.add(receipt);
        if(!repair.id().equals(receipt.id()) || !"APPLIED".equals(receipt.status())
            || receipt.proposedSha256()==null || receipt.proposedSha256().isBlank() || !receipt.proposedSha256().equals(receipt.currentSha256()))
          return output(root,"REPAIR_REJECTED",rounds,receipts,warnings);
        SelfPatchReviewService.remaining(deadline,1);var after=capture.capture(root,deadline);RunCancellation.propagate(null);
        if(!after.complete())return output(root,"BLOCKED",rounds,receipts,warnings);
        if(before.version().equals(after.version()))return output(root,"REPAIR_NO_PROGRESS",rounds,receipts,warnings);
      }
    }catch(IOException error){RunCancellation.propagate(error);warnings.add("Shared repair cycle budget or patch inspection unavailable");return output(root,"BLOCKED",rounds,receipts,warnings);}
  }
  private static boolean eligible(SelfPatchReviewService.Result result) {
    if("FIX_REQUIRED".equals(result.status()))return result.review()!=null && result.review().complete() && !result.review().findings().isEmpty();
    var test=switch(result.status()){case "INITIAL_TEST_FAILED"->result.initialTest();case "FINAL_TEST_FAILED"->result.finalTest();default->null;};
    return test!=null && test.exitCode()!=null && test.exitCode()!=0 && !test.timedOut() && !test.logsTruncated();
  }
  private static void validate(Request request) {
    if(request==null || request.testCommand()==null || request.testCommand().isBlank() || request.testCommand().length()>4096
        || request.timeoutSeconds()!=null && (request.timeoutSeconds()<1 || request.timeoutSeconds()>60)
        || request.repairs().isEmpty() || request.repairs().size()>3)throw new IllegalArgumentException("Explicit test and 1..3 saved repairs required");
    var seen=new HashSet<String>();
    for(var repair:request.repairs())if(repair==null || repair.id()==null || repair.id().isBlank() || repair.id().length()>128
        || repair.proposalSha256()==null || repair.proposalSha256().isBlank() || repair.proposalSha256().length()>128 || !seen.add(repair.id()))
      throw new IllegalArgumentException("Unique bounded saved repair IDs and proposal hashes required");
  }
  private static Result output(Path root,String status,List<SelfPatchReviewService.Result> rounds,List<Receipt> receipts,List<String> warnings) {
    return new Result(root.toString(),status,rounds,receipts,warnings);
  }
}
