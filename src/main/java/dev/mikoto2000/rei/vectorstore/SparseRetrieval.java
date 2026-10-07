package dev.mikoto2000.rei.vectorstore;
import java.util.*;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Explicitly constructed optional encoder/index adapter. Absence leaves existing BM25 retrieval intact. */
public final class SparseRetrieval {
  public interface Index{
    String modelId();int dimensions();
    default boolean supportsFilters(){return false;}
    /** Implementations must apply the request filter and threshold before topK. */
    List<Document> search(SearchRequest request,SparseEncoder.Vector vector);
    default List<Document> search(SearchRequest request,SparseEncoder.Vector vector,Runnable active){active.run();var result=search(request,vector);active.run();return result;}
  }
  public record Result(String mode,String reason,String modelId,List<Document> documents,boolean truthVerified){}
  private final RetrievalCandidates baseline;private final SparseEncoder encoder;private final Index index;private final LongSupplier nanos;private final long timeout;
  public SparseRetrieval(RetrievalCandidates baseline,SparseEncoder encoder,Index index){this(baseline,encoder,index,System::nanoTime,Duration.ofSeconds(5));}
  public SparseRetrieval(RetrievalCandidates baseline,SparseEncoder encoder,Index index,LongSupplier nanos,Duration timeout){this.baseline=Objects.requireNonNull(baseline);this.encoder=encoder;this.index=index;this.nanos=Objects.requireNonNull(nanos);if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Sparse deadline <=30s");this.timeout=timeout.toNanos();}
  public Result search(SearchRequest request){
    RunCancellation.propagate(null);if(request==null||request.getQuery()==null||request.getQuery().length()>8192||request.getTopK()<1||request.getTopK()>256)throw new IllegalArgumentException("Bounded sparse query/topK required");
    if(encoder==null||index==null)return fallback(request,"SPARSE_NOT_CONFIGURED");if(request.hasFilterExpression()&&!index.supportsFilters())return fallback(request,"SPARSE_FILTER_UNSUPPORTED");
    long started=nanos.getAsLong();Runnable active=()->{RunCancellation.propagate(null);if(nanos.getAsLong()-started>=timeout)throw new Deadline();};
    try{
      active.run();if(!Objects.equals(encoder.modelId(),index.modelId())||index.dimensions()<1||index.dimensions()>1048576)throw new Fallback("SPARSE_MODEL_MISMATCH");
      var vector=encoder.encode(request.getQuery(),active);active.run();if(vector==null||!Objects.equals(vector.modelId(),encoder.modelId())||vector.dimensions()!=index.dimensions())throw new Fallback("SPARSE_MODEL_MISMATCH");if(vector.weights().isEmpty())throw new Fallback("SPARSE_EMPTY_VECTOR");
      var documents=index.search(request,vector,active);active.run();return new Result("LEARNED_SPARSE","SPARSE_PROVIDER_USED",vector.modelId(),bounded(documents,request),false);
    }catch(RuntimeException failure){RunCancellation.propagate(failure);if(failure instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException)throw failure;return fallback(request,failure instanceof Fallback selected?selected.reason:failure instanceof Deadline?"SPARSE_TIMEOUT":"SPARSE_UNAVAILABLE");}
  }
  private Result fallback(SearchRequest request,String reason){RunCancellation.propagate(null);return new Result("BM25",reason,null,bounded(baseline.bm25Search(request),request),false);}
  private static List<Document> bounded(List<Document> documents,SearchRequest request){if(documents==null||documents.size()>request.getTopK()||documents.stream().anyMatch(doc->doc==null||doc.getId()==null||doc.getId().isBlank())||documents.stream().map(Document::getId).distinct().count()!=documents.size())throw new IllegalStateException("Invalid bounded sparse/BM25 candidates");return List.copyOf(documents);}
  private static final class Fallback extends RuntimeException{private final String reason;Fallback(String reason){this.reason=reason;}}
  private static final class Deadline extends RuntimeException{}
}
