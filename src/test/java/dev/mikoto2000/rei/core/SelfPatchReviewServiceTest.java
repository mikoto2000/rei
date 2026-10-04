package dev.mikoto2000.rei.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import static org.junit.jupiter.api.Assertions.*;

class SelfPatchReviewServiceTest {
  @TempDir Path root;
  SelfPatchReviewService.Snapshot snapshot(String version){return new SelfPatchReviewService.Snapshot(version,List.of("A.java"),List.of(),true,List.of());}
  SelfPatchReviewService.TestObservation test(boolean success){return new SelfPatchReviewService.TestObservation(success?"completed":"failed",success?0:1,false,false,
      BuildTestFailureDiagnosis.command(success?"completed":"failed",success?0:1,false,"","",null));}
  SelfPatchReviewService.Review review(){return new SelfPatchReviewService.Review(true,List.of(),List.of());}
  @Test void verifiesInitialTestReviewAndFinalTestAgainstTheSamePatchInOrder() throws Exception {
    var steps=new ArrayList<String>();
    var service=new SelfPatchReviewService((r,d)->{assertEquals(root.toRealPath(),r);steps.add("snapshot");return snapshot("v1");},
        (r,s,d)->{steps.add("review");return review();},(r,c,t)->{assertEquals("fixture test",c);assertTrue(t.toSeconds()<=30);steps.add("test");return test(true);});
    var result=service.verify(root,new SelfPatchReviewService.Request("fixture test",30));
    assertEquals("VERIFIED_CHECKS",result.status());
    assertEquals(List.of("snapshot","test","snapshot","review","snapshot","test","snapshot"),steps);
    assertEquals("v1",result.patchVersion());assertNotNull(result.finalTest());
  }
  @Test void failedInitialTestNeverReviewsOrRunsFinalTest() throws Exception {
    var calls=new AtomicInteger();
    var service=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->{fail("review must not run");return review();},(r,c,t)->{calls.incrementAndGet();return test(false);});
    assertEquals("INITIAL_TEST_FAILED",service.verify(root,new SelfPatchReviewService.Request("fixture",null)).status());assertEquals(1,calls.get());
  }
  @Test void reviewFindingsReturnToTheExistingFixLoopWithoutClaimingVerification() throws Exception {
    var calls=new AtomicInteger();
    var service=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->new SelfPatchReviewService.Review(true,
        List.of(new SelfPatchReviewService.Finding("A.java",3,"CONFLICT_MARKER")),List.of()),(r,c,t)->{calls.incrementAndGet();return test(true);});
    var result=service.verify(root,new SelfPatchReviewService.Request("fixture",10));
    assertEquals("FIX_REQUIRED",result.status());assertEquals(1,calls.get());assertNull(result.finalTest());
  }
  @Test void changesDuringInitialOrFinalTestInvalidateTheCycle() throws Exception {
    for(int changeAt:List.of(2,4)) {
      var snapshots=new AtomicInteger();var calls=new AtomicInteger();
      var service=new SelfPatchReviewService((r,d)->snapshot(snapshots.incrementAndGet()>=changeAt?"changed":"v1"),
          (r,s,d)->review(),(r,c,t)->{calls.incrementAndGet();return test(true);});
      assertEquals("PATCH_CHANGED",service.verify(root,new SelfPatchReviewService.Request("fixture",10)).status());
      assertEquals(changeAt==2?1:2,calls.get());
    }
  }
  @Test void incompleteReviewOrTruncatedTestLogsCannotVerify() throws Exception {
    var incomplete=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->new SelfPatchReviewService.Review(false,List.of(),List.of("incomplete")),(r,c,t)->test(true));
    assertEquals("REVIEW_INCOMPLETE",incomplete.verify(root,new SelfPatchReviewService.Request("fixture",10)).status());
    var truncated=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->review(),(r,c,t)->new SelfPatchReviewService.TestObservation("completed",0,false,true,test(true).diagnosis()));
    assertEquals("INITIAL_TEST_FAILED",truncated.verify(root,new SelfPatchReviewService.Request("fixture",10)).status());
  }
  @Test void failedFinalTestIsPreservedAndDoesNotVerify() throws Exception {
    var calls=new AtomicInteger();
    var service=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->review(),(r,c,t)->test(calls.incrementAndGet()==1));
    var result=service.verify(root,new SelfPatchReviewService.Request("fixture",10));
    assertEquals("FINAL_TEST_FAILED",result.status());assertEquals(1,result.finalTest().exitCode());
  }
  @Test void invalidArgumentsAndCancellationStopBeforeAnotherStage() throws Exception {
    var service=new SelfPatchReviewService((r,d)->snapshot("v1"),(r,s,d)->review(),(r,c,t)->{Thread.currentThread().interrupt();return test(true);});
    assertThrows(IllegalArgumentException.class,()->service.verify(root,new SelfPatchReviewService.Request("",10)));
    assertThrows(IllegalArgumentException.class,()->service.verify(root,new SelfPatchReviewService.Request("fixture",61)));
    try{assertThrows(CancellationException.class,()->service.verify(root,new SelfPatchReviewService.Request("fixture",10)));}finally{Thread.interrupted();}
  }
}
