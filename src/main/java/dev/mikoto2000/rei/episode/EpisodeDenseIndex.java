package dev.mikoto2000.rei.episode;

import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import dev.mikoto2000.rei.llm.*;

/** Separate derived store: vector text is never trusted as an authoritative episode. */
public class EpisodeDenseIndex {
  private final EpisodeRepository repository;
  private final LazySqliteVectorStore vectors;
  private final String generation;
  private java.util.function.Predicate<Episode> sourceAvailable=e->true;
  public EpisodeDenseIndex(EpisodeRepository repository,LazySqliteVectorStore vectors){this(repository,vectors,"default");}
  public EpisodeDenseIndex(EpisodeRepository repository,LazySqliteVectorStore vectors,String generation){this.repository=repository;this.vectors=vectors;this.generation=generation;}
  public void setSourceAvailable(java.util.function.Predicate<Episode> check){sourceAvailable=check;}
  public int pending(String session){return repository.pendingDense(session,1,generation).size();}
  public void indexPending(String session,ModelCallBudget budget) {
    var pending=repository.pendingDense(session,50,generation);if(pending.isEmpty())return;
    String worker=UUID.randomUUID().toString();
    if(!repository.acquire("__episode_dense_index",worker,600))return;
    try(var scope=ModelCallBudgetScope.open(budget)) {
      for(var e:repository.pendingDense(session,50,generation)) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        if(!sourceAvailable.test(e))continue;
        String content=e.title()+" "+e.summary()+" "+e.claims().stream().map(c->c.kind()+" "+c.evidence()+" "+c.text()).collect(java.util.stream.Collectors.joining(" "));
        var document=Document.builder().id(e.id()).text(content.substring(0,Math.min(4000,content.length())))
            .metadata(Map.of("docId",e.projectId(),"source",e.id(),"chunkIndex",0,"ingestedAt",e.occurredAt(),"episodeId",e.id(),"revision",e.revision())).build();
        vectors.replaceBySource(e.projectId(),e.id(),e.occurredAt(),List.of(document));
        if(!repository.markDense(e,generation))repository.invalidateDense(e.id());
      }
    } finally {repository.release("__episode_dense_index",worker);}
  }
  public List<Episode> search(String query,String project,int limit,EpisodeTimeRange range) {
    if(limit<1||limit>20||query==null||query.length()>2000)throw new IllegalArgumentException("Invalid dense query");
    if(!repository.hasDense(project,generation))return List.of();
    var request=SearchRequest.builder().query(query).topK(limit).similarityThreshold(0)
        .filterExpression(new FilterExpressionBuilder().eq("docId",project).build()).build();
    var result=new LinkedHashMap<String,Episode>();
    for(var document:vectors.denseSearch(request)) {
      var id=document.getMetadata().get("episodeId");var revision=document.getMetadata().get("revision");
      if(id instanceof String key)repository.find(key,project).filter(e->e.revision().equals(revision)&&range.includes(java.time.Instant.parse(e.occurredAt())))
          .ifPresent(e->result.putIfAbsent(e.id(),e));
    }
    return List.copyOf(result.values());
  }
}
