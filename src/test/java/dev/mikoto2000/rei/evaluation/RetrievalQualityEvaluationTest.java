package dev.mikoto2000.rei.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RetrievalQualityEvaluationTest {
  RetrievalQualityEvaluation.Case fixture(){return new RetrievalQualityEvaluation.Case("graded","query",Map.of("a",3,"b",1),List.of("negative"),List.of("a","b"),2);}
  @Test void gradedMetricsUseCutoffFullRelevantSetAndIdealOrdering(){
    var evaluator=new RetrievalQualityEvaluation();var result=evaluator.score(fixture(),List.of("negative","b","a"));assertEquals(.5,result.recall());assertEquals(.5,result.precision());assertEquals(.5,result.mrr());assertTrue(result.ndcg()>0&&result.ndcg()<.1);assertFalse(result.expectedTopKMatched());
    var perfect=evaluator.score(fixture(),List.of("a","b"));assertEquals(1,perfect.recall());assertEquals(1,perfect.precision());assertEquals(1,perfect.mrr());assertEquals(1,perfect.ndcg());assertTrue(perfect.expectedTopKMatched());
  }
  @Test void shortAndEmptyListsDoNotInflatePrecisionAndDuplicatesAreInvalid(){
    var evaluator=new RetrievalQualityEvaluation();assertEquals(.5,evaluator.score(fixture(),List.of("a")).precision());assertEquals(0,evaluator.score(fixture(),List.of()).ndcg());assertThrows(IllegalArgumentException.class,()->evaluator.score(fixture(),List.of("a","a")));
    assertThrows(IllegalArgumentException.class,()->new RetrievalQualityEvaluation.Case("bad","q",Map.of("a",3),List.of("a"),List.of("a"),1));
  }
  @Test void comparingModesRecordsProviderIdentityAndDoesNotClaimLiveQuality(){
    var result=new RetrievalQualityEvaluation().compare(List.of(fixture()),"deterministic-fixture",(f,mode,rerank)->rerank?List.of("a","b"):mode==RetrievalQualityEvaluation.Mode.LEXICAL?List.of("negative","b"):List.of("b","a"));
    assertEquals(6,result.runs().size());assertEquals("deterministic-fixture",result.provider());assertFalse(result.truthVerified());assertTrue(result.runs().stream().anyMatch(r->!r.metrics().expectedTopKMatched()));assertEquals(6,result.aggregate().size());
  }
  @Test void cancellationAndProviderFailureDoNotBecomeZeroScoreSuccess(){
    var evaluator=new RetrievalQualityEvaluation();assertThrows(java.util.concurrent.CancellationException.class,()->evaluator.compare(List.of(fixture()),"fixture",(f,m,r)->{throw new java.util.concurrent.CancellationException();}));assertThrows(IllegalStateException.class,()->evaluator.compare(List.of(fixture()),"fixture",(f,m,r)->{throw new IllegalStateException("unavailable");}));
  }
  @Test void totalDeadlineStopsComparisonWithoutPublishingPartialMetrics(){
    var nanos=new java.util.concurrent.atomic.AtomicLong();var evaluator=new RetrievalQualityEvaluation(nanos::get,java.time.Duration.ofMillis(1));var calls=new java.util.concurrent.atomic.AtomicInteger();assertThrows(IllegalStateException.class,()->evaluator.compare(List.of(fixture()),"fixture",(f,m,r)->{calls.incrementAndGet();nanos.addAndGet(1000001);return List.of("a","b");}));assertEquals(1,calls.get());
  }
}
