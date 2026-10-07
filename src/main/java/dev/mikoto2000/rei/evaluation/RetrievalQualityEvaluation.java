package dev.mikoto2000.rei.evaluation;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import dev.mikoto2000.rei.core.chat.RunCancellation;

/** Labelled, provider-independent offline evaluation; does not install or invoke a model by itself. */
public final class RetrievalQualityEvaluation {
  public enum Mode{LEXICAL,DENSE,RRF}
  public record Case(String id,String query,Map<String,Integer> relevance,List<String> hardNegatives,List<String> expectedTopK,int topK){
    public Case{relevance=Map.copyOf(relevance);hardNegatives=List.copyOf(hardNegatives);expectedTopK=List.copyOf(expectedTopK);
      if(id==null||id.isBlank()||id.length()>128||query==null||query.isBlank()||query.length()>8192||relevance.isEmpty()||relevance.size()>256||hardNegatives.size()>256||topK<1||topK>256||expectedTopK.size()>topK||relevance.values().stream().anyMatch(v->v<1||v>3)||!Collections.disjoint(relevance.keySet(),hardNegatives)||new HashSet<>(hardNegatives).size()!=hardNegatives.size()||new HashSet<>(expectedTopK).size()!=expectedTopK.size())throw new IllegalArgumentException("Invalid bounded relevance fixture");
      for(String value:java.util.stream.Stream.concat(java.util.stream.Stream.concat(relevance.keySet().stream(),hardNegatives.stream()),expectedTopK.stream()).toList())identity(value);
    }
  }
  public record Metrics(double recall,double precision,double mrr,double ndcg,boolean expectedTopKMatched) {}
  public record Run(String fixtureId,Mode mode,boolean reranked,List<String> ranking,Metrics metrics) {}
  public record Average(double recall,double precision,double mrr,double ndcg,int cases) {}
  public record Comparison(String provider,List<Run> runs,Map<String,Average> aggregate,boolean truthVerified) {}
  @FunctionalInterface public interface Retriever{List<String> retrieve(Case fixture,Mode mode,boolean rerank);}
  private final LongSupplier nanos;private final long limit;
  public RetrievalQualityEvaluation(){this(System::nanoTime,Duration.ofSeconds(30));}
  public RetrievalQualityEvaluation(LongSupplier nanos,Duration timeout){if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Evaluation deadline <=30s");this.nanos=nanos;limit=timeout.toNanos();}
  public Metrics score(Case fixture,List<String> ranking){
    RunCancellation.propagate(null);if(fixture==null||ranking==null||ranking.size()>256||new HashSet<>(ranking).size()!=ranking.size())throw new IllegalArgumentException("Bounded unique ranking required");ranking.forEach(RetrievalQualityEvaluation::identity);
    var selected=ranking.stream().limit(fixture.topK()).toList();int hits=0;double reciprocal=0,dcg=0;
    for(int i=0;i<selected.size();i++){int relevance=fixture.relevance().getOrDefault(selected.get(i),0);if(relevance>0){hits++;if(reciprocal==0)reciprocal=1.0/(i+1);dcg+=(Math.pow(2,relevance)-1)/log2(i+2);}}
    var ideal=fixture.relevance().values().stream().sorted(Comparator.reverseOrder()).limit(fixture.topK()).toList();double idcg=0;for(int i=0;i<ideal.size();i++)idcg+=(Math.pow(2,ideal.get(i))-1)/log2(i+2);
    return new Metrics(hits/(double)fixture.relevance().size(),hits/(double)fixture.topK(),reciprocal,idcg==0?0:dcg/idcg,selected.equals(fixture.expectedTopK()));
  }
  public Comparison compare(List<Case> fixtures,String provider,Retriever retriever){
    if(fixtures==null||fixtures.isEmpty()||fixtures.size()>128||fixtures.stream().anyMatch(Objects::isNull)||fixtures.stream().map(Case::id).distinct().count()!=fixtures.size()||provider==null||!provider.matches("[A-Za-z0-9._:-]{1,128}")||retriever==null)throw new IllegalArgumentException("Bounded unique fixtures and explicit provider identity required");
    long started=nanos.getAsLong();var runs=new ArrayList<Run>();var aggregate=new LinkedHashMap<String,Average>();
    for(var mode:Mode.values())for(boolean rerank:List.of(false,true)){var group=new ArrayList<Metrics>();for(var fixture:fixtures){active(started);var ranking=List.copyOf(retriever.retrieve(fixture,mode,rerank));active(started);var metrics=score(fixture,ranking);group.add(metrics);runs.add(new Run(fixture.id(),mode,rerank,ranking,metrics));}aggregate.put(mode.name()+"_"+(rerank?"RERANK":"BASE"),new Average(group.stream().mapToDouble(Metrics::recall).average().orElseThrow(),group.stream().mapToDouble(Metrics::precision).average().orElseThrow(),group.stream().mapToDouble(Metrics::mrr).average().orElseThrow(),group.stream().mapToDouble(Metrics::ndcg).average().orElseThrow(),group.size()));}
    return new Comparison(provider,List.copyOf(runs),Map.copyOf(aggregate),false);
  }
  private void active(long started){RunCancellation.propagate(null);if(nanos.getAsLong()-started>=limit)throw new IllegalStateException("Evaluation deadline exceeded; incomplete comparison is not success");}
  private static void identity(String value){if(value==null||value.isBlank()||value.length()>1024||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Bounded candidate identity required");}
  private static double log2(double value){return Math.log(value)/Math.log(2);}
}
