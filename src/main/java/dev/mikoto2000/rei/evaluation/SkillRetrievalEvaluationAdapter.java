package dev.mikoto2000.rei.evaluation;
import java.util.*;
import dev.mikoto2000.rei.skills.*;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Uses live-catalog keyword search and existing SemanticSkillSearch with controlled rank streams. */
public final class SkillRetrievalEvaluationAdapter implements RetrievalQualityEvaluation.Retriever {
  private final List<AgentSkill> skills;private final SemanticSkillProperties settings;private final SkillMetadataEmbedding embedding;private final CandidateReranker reranker;
  public SkillRetrievalEvaluationAdapter(List<AgentSkill> skills,SemanticSkillProperties settings,SkillMetadataEmbedding embedding,CandidateReranker reranker){this.skills=List.copyOf(skills);this.settings=Objects.requireNonNull(settings);this.embedding=embedding;this.reranker=reranker;if(skills.stream().map(AgentSkill::name).distinct().count()!=skills.size())throw new IllegalArgumentException("Unique fixture skill names required");}
  public List<String> retrieve(RetrievalQualityEvaluation.Case fixture,RetrievalQualityEvaluation.Mode mode,boolean rerank){
    RunCancellation.propagate(null);int pool=Math.min(256,Math.max(10,fixture.topK()));var lexical=new SkillCandidateSelector().selectCandidates(fixture.query(),skills,pool);List<SkillCandidate> result;
    if(mode==RetrievalQualityEvaluation.Mode.LEXICAL){result=lexical;if(rerank){if(reranker==null)throw new IllegalStateException("Rerank evaluation provider unavailable");var ranked=reranker.rerankForEvaluation(fixture.query(),result,c->c.skill().name()+"\n"+c.skill().description());RunCancellation.propagate(null);if(ranked==null||ranked.size()!=result.size()||!new HashSet<>(result.stream().map(c->c.skill().name()).toList()).equals(new HashSet<>(ranked.stream().map(c->c.skill().name()).toList())))throw new IllegalStateException("Reranker changed skill candidates");result=ranked;}}
    else{if(!settings.enabled()||embedding==null||rerank&&reranker==null)throw new IllegalStateException("Explicit evaluation embedding/rerank provider required");
      // A single dense rank stream preserves dense ordering through existing RRF; no keyword fallback can disguise an unavailable model.
      var failure=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();var dimensions=new java.util.concurrent.atomic.AtomicInteger();
      SkillMetadataEmbedding strict=input->{try{var vectors=embedding.embed(input);if(vectors==null||vectors.size()!=input.size()||vectors.stream().anyMatch(vector->vector==null||vector.length==0||!finite(vector)))throw new IllegalStateException("Invalid evaluation embeddings");for(var vector:vectors){dimensions.compareAndSet(0,vector.length);if(vector.length!=dimensions.get())throw new IllegalStateException("Evaluation embedding dimensions changed");}return vectors;}catch(RuntimeException error){RunCancellation.propagate(error);failure.set(error);throw error;}};
      result=new SemanticSkillSearch(settings,()->strict,()->rerank?new StrictReranker(reranker,failure):null,System::nanoTime).select(fixture.query(),skills,mode==RetrievalQualityEvaluation.Mode.DENSE?List.of():lexical,pool);
      if(failure.get()!=null||result.stream().anyMatch(c->!c.matchedFields().contains("rrf")))throw new IllegalStateException("Semantic evaluation fell back; no dense/RRF quality result",failure.get());
    }
    return result.stream().limit(fixture.topK()).map(c->c.skill().name()).toList();
  }
  private static boolean finite(float[] vector){for(float value:vector)if(!Float.isFinite(value))return false;return true;}
  private static final class StrictReranker implements CandidateReranker{
    private final CandidateReranker delegate;private final java.util.concurrent.atomic.AtomicReference<RuntimeException> failure;
    StrictReranker(CandidateReranker delegate,java.util.concurrent.atomic.AtomicReference<RuntimeException> failure){this.delegate=delegate;this.failure=failure;}
    public <T>List<T> rerank(String query,List<T> candidates,java.util.function.Function<T,String> text){try{var result=delegate.rerankForEvaluation(query,candidates,text);if(result==null||result.size()!=candidates.size()||new HashSet<>(result).size()!=result.size()||!new HashSet<>(result).equals(new HashSet<>(candidates)))throw new IllegalStateException("Evaluation reranker changed candidates");return result;}catch(RuntimeException error){RunCancellation.propagate(error);failure.set(error);throw error;}}
  }
}
