package dev.mikoto2000.rei.vectorstore;

import java.util.*;
import java.util.function.Function;

/** Bounded vendor-independent rank arithmetic; scores from different retrievers are never added. */
public final class ReciprocalRankFusion {
  public record Rank(int stream,int position) {}
  public record Ranked<T>(T item,double score,List<Rank> ranks) {public Ranked{ranks=List.copyOf(ranks);}}
  private final int constant;
  public ReciprocalRankFusion(int constant){if(constant<1 || constant>1000)throw new IllegalArgumentException("Invalid rank constant");this.constant=constant;}
  public <T> List<Ranked<T>> fuse(List<List<T>> streams,Function<T,String> key,int limit) {
    if(limit<1 || limit>256 || streams.size()>8 || streams.stream().anyMatch(s->s.size()>256))throw new IllegalArgumentException("Invalid fusion limits");
    var items=new HashMap<String,T>();var scores=new HashMap<String,Double>();var ranks=new HashMap<String,List<Rank>>();
    for(int stream=0;stream<streams.size();stream++) {
      var seen=new HashSet<String>();int position=0;
      for(var item:streams.get(stream)) {
        String id=key.apply(item);if(id==null || id.isBlank())throw new IllegalArgumentException("Missing candidate identity");
        if(!seen.add(id))continue;
        position++;items.putIfAbsent(id,item);scores.merge(id,1.0/(constant+position),Double::sum);
        ranks.computeIfAbsent(id,k->new ArrayList<>()).add(new Rank(stream,position));
      }
    }
    double scale=(constant+1.0)/Math.max(1,streams.size());
    return scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(Function.identity())).limit(limit)
        .map(id->new Ranked<>(items.get(id),scores.get(id)*scale,ranks.get(id))).toList();
  }
}
