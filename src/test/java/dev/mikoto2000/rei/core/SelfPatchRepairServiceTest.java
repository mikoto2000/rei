package dev.mikoto2000.rei.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelfPatchRepairServiceTest {
  @TempDir Path root;
  SelfPatchReviewService.Result round(String status,String version) {
    return new SelfPatchReviewService.Result(root.toString(),status,version,version,List.of("A.java"),
        new SelfPatchReviewService.TestObservation(status.equals("INITIAL_TEST_FAILED")?"failed":"completed",status.equals("INITIAL_TEST_FAILED")?1:0,false,false,null),
        status.equals("FIX_REQUIRED")?new SelfPatchReviewService.Review(true,List.of(new SelfPatchReviewService.Finding("A.java",1,"WHITESPACE")),List.of()):null,null,List.of(),List.of());
  }
  SelfPatchReviewService.Snapshot snapshot(String version){return new SelfPatchReviewService.Snapshot(version,List.of("A.java"),List.of(),true,List.of());}
  SelfPatchRepairService.Request request(){return new SelfPatchRepairService.Request("fixture",10,List.of(new SelfPatchRepairService.Repair("fix","hash")));}
  @Test void fixesAReportedFailureThenRepeatsEntireReviewAndPreservesOriginalFailure() throws Exception {
    var steps=new ArrayList<String>();var version=new AtomicInteger();var deadline=new java.util.concurrent.atomic.AtomicLong();
    var service=new SelfPatchRepairService((r,q,d)->{if(deadline.get()==0)deadline.set(d);else assertEquals(deadline.get(),d);steps.add("cycle");return round(version.get()==0?"INITIAL_TEST_FAILED":"VERIFIED_CHECKS","v"+version.get());},
        (r,d)->{assertEquals(deadline.get(),d);steps.add("snapshot");return snapshot("v"+version.get());},
        (r,fix,d)->{assertEquals(deadline.get(),d);steps.add("fix");version.incrementAndGet();return new SelfPatchRepairService.Receipt(fix.id(),"APPLIED","new","new");});
    var result=service.verify(root,request());assertEquals("VERIFIED_CHECKS",result.status());
    assertEquals(List.of("cycle","snapshot","fix","snapshot","cycle"),steps);
    assertEquals(2,result.rounds().size());assertEquals(1,result.rounds().getFirst().initialTest().exitCode());assertEquals(1,result.repairs().size());
  }
  @Test void changedPatchIncompleteReviewOrUncertainRepairNeverStartsAnotherFix() throws Exception {
    for(var status:List.of("PATCH_CHANGED","BLOCKED","REVIEW_INCOMPLETE","NO_PATCH")) {
      var service=new SelfPatchRepairService((r,q,d)->round(status,"v1"),(r,d)->{fail("no capture");return snapshot("v1");},
          (r,f,d)->{fail("no fix");return null;});
      assertEquals(status,service.verify(root,request()).status());
    }
    var changed=new SelfPatchRepairService((r,q,d)->round("FIX_REQUIRED","v1"),(r,d)->snapshot("other"),(r,f,d)->{fail("no changed patch write");return null;});
    assertEquals("PATCH_CHANGED",changed.verify(root,request()).status());
    var uncertain=new SelfPatchRepairService((r,q,d)->round("FIX_REQUIRED","v1"),(r,d)->snapshot("v1"),(r,f,d)->new SelfPatchRepairService.Receipt(f.id(),"FAILED_UNCERTAIN","new","partial"));
    assertEquals("REPAIR_REJECTED",uncertain.verify(root,request()).status());
  }
  @Test void noProgressAndRepairLimitStopRatherThanLoopOrClaimSuccess() throws Exception {
    var count=new AtomicInteger();
    var unchanged=new SelfPatchRepairService((r,q,d)->round("FIX_REQUIRED","v1"),(r,d)->snapshot("v1"),
        (r,f,d)->new SelfPatchRepairService.Receipt(f.id(),"APPLIED","new","new"));
    assertEquals("REPAIR_NO_PROGRESS",unchanged.verify(root,request()).status());
    var limited=new SelfPatchRepairService((r,q,d)->round("FIX_REQUIRED","v"+count.get()),(r,d)->snapshot("v"+count.get()),
        (r,f,d)->{count.incrementAndGet();return new SelfPatchRepairService.Receipt(f.id(),"APPLIED","new","new");});
    assertEquals("REPAIR_LIMIT_REACHED",limited.verify(root,request()).status());assertEquals(1,count.get());
  }
  @Test void invalidPlanAndCancellationStopBeforeAnotherStage() throws Exception {
    var service=new SelfPatchRepairService((r,q,d)->{throw new java.util.concurrent.CancellationException();},(r,d)->snapshot("v1"),(r,f,d)->null);
    assertThrows(IllegalArgumentException.class,()->service.verify(root,new SelfPatchRepairService.Request("fixture",10,List.of())));
    assertThrows(IllegalArgumentException.class,()->service.verify(root,new SelfPatchRepairService.Request("fixture",10,List.of(new SelfPatchRepairService.Repair("same","hash"),new SelfPatchRepairService.Repair("same","hash")))));
    assertThrows(java.util.concurrent.CancellationException.class,()->service.verify(root,request()));
  }
  @Test void maximumThreeRepairsAndFourWholeCyclesRetainTheSameBoundedPlan() throws Exception {
    var fixes=new AtomicInteger();var cycles=new AtomicInteger();
    var service=new SelfPatchRepairService((r,q,d)->{cycles.incrementAndGet();return round("FIX_REQUIRED","v"+fixes.get());},
        (r,d)->snapshot("v"+fixes.get()),(r,f,d)->{fixes.incrementAndGet();return new SelfPatchRepairService.Receipt(f.id(),"APPLIED","new","new");});
    var plan=new ArrayList<SelfPatchRepairService.Repair>();for(int i=0;i<3;i++)plan.add(new SelfPatchRepairService.Repair("fix-"+i,"hash"));
    assertEquals("REPAIR_LIMIT_REACHED",service.verify(root,new SelfPatchRepairService.Request("fixture",10,plan)).status());
    assertEquals(3,fixes.get());assertEquals(4,cycles.get());
    plan.add(new SelfPatchRepairService.Repair("fourth","hash"));
    assertThrows(IllegalArgumentException.class,()->service.verify(root,new SelfPatchRepairService.Request("fixture",10,plan)));
    assertEquals(3,fixes.get());assertEquals(4,cycles.get());
  }
  @Test void opaqueFailedTestsAndUnknownApplyReceiptsDoNotContinue() throws Exception {
    for(boolean timeout:List.of(true,false)) {
      var failed=new SelfPatchReviewService.Result(root.toString(),"INITIAL_TEST_FAILED","v1","v1",List.of("A.java"),
          new SelfPatchReviewService.TestObservation("failed",1,timeout,!timeout,null),null,null,List.of(),List.of());
      var service=new SelfPatchRepairService((r,q,d)->failed,(r,d)->{fail("no capture for incomplete test");return snapshot("v1");},(r,f,d)->{fail("no opaque fix");return null;});
      assertEquals("INITIAL_TEST_FAILED",service.verify(root,request()).status());
    }
    for(boolean throwsError:List.of(true,false)) {
      var service=new SelfPatchRepairService((r,q,d)->round("FIX_REQUIRED","v1"),(r,d)->snapshot("v1"),
          (r,f,d)->{if(throwsError)throw new java.io.IOException("uncertain write");return null;});
      var result=service.verify(root,request());assertEquals(throwsError?"REPAIR_FAILED":"REPAIR_REJECTED",result.status());
      assertEquals(1,result.rounds().size());assertEquals("UNKNOWN",result.repairs().getFirst().status());
    }
  }
}
