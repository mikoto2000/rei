package dev.mikoto2000.rei.skills;

import java.util.*;
import java.util.function.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.vectorstore.ReciprocalRankFusion;
import dev.mikoto2000.rei.vectordocument.CandidateReranker;

/** Live-catalog metadata search with an optional persisted vector cache; never caches queries or instructions. */
public final class SemanticSkillSearch {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SemanticSkillSearch.class);
  private final SemanticSkillProperties settings;
  private final Supplier<SkillMetadataEmbedding> embedding;
  private final Supplier<CandidateReranker> reranker;
  private final LongSupplier nanos;
  private final SkillEmbeddingIndex index;
  private volatile Set<String> indexedProfiles;
  private volatile boolean bypassPersistent;
  private volatile Map<String,float[]> cache=Map.of();
  private volatile Long failureAt;
  public SemanticSkillSearch(SemanticSkillProperties settings,Supplier<SkillMetadataEmbedding> embedding,Supplier<CandidateReranker> reranker,LongSupplier nanos) {
    this(settings,embedding,reranker,nanos,null);
  }
  public SemanticSkillSearch(SemanticSkillProperties settings,Supplier<SkillMetadataEmbedding> embedding,Supplier<CandidateReranker> reranker,LongSupplier nanos,SkillEmbeddingIndex index) {
    this.settings=settings;this.embedding=embedding;this.reranker=reranker;this.nanos=nanos;
    this.index=index;
  }
  public boolean enabled(){return settings.enabled();}
  public List<SkillCandidate> select(String request,List<AgentSkill> skills,List<SkillCandidate> lexical,int limit) {
    RunCancellation.propagate(null);
    if(!enabled() || request.length()>8192 || skills.size()>settings.maxSkills() || limit>256)return lexical.stream().limit(limit).toList();
    Long failed=failureAt;if(failed!=null && nanos.getAsLong()-failed<settings.failureBackoffSeconds()*1_000_000_000L)return lexical.stream().limit(limit).toList();
    List<SkillCandidate> dense;
    try {
      var eligible=skills.stream().filter(AgentSkill::enabled).filter(s->profile(s).length()<=2048).toList();
      if(eligible.isEmpty()) {
        cache=Map.of();
        if(persistent()&&!Objects.equals(indexedProfiles,Set.of()))try {
          index.replace(settings.indexNamespace(),Map.of());RunCancellation.propagate(null);indexedProfiles=Set.of();
        }catch(RuntimeException error){RunCancellation.propagate(error);log.warn("Skill index cleanup unavailable ({})",error.getClass().getSimpleName());}
        return lexical.stream().limit(limit).toList();
      }
      var model=embedding.get();if(model==null)return lexical.stream().limit(limit).toList();
      var profiles=eligible.stream().map(SemanticSkillSearch::profile).distinct().toList();
      var old=cache;var vectors=new LinkedHashMap<String,float[]>();
      for(var profile:profiles)if(old.containsKey(profile))vectors.put(profile,old.get(profile));
      if(persistent()&&!bypassPersistent) {
        var toLoad=profiles.stream().filter(p->!vectors.containsKey(p)).toList();
        if(!toLoad.isEmpty())try {
          var saved=index.load(settings.indexNamespace(),toLoad);RunCancellation.propagate(null);
          for(var profile:toLoad)if(saved.containsKey(profile))vectors.put(profile,normalized(saved.get(profile)));
        }catch(RuntimeException error){RunCancellation.propagate(error);log.warn("Skill index read unavailable; using live metadata ({})",error.getClass().getSimpleName());}
      }
      var missing=profiles.stream().filter(p->!vectors.containsKey(p)).toList();
      if(!missing.isEmpty()) {
        var batch=model.embed(missing);RunCancellation.propagate(null);
        if(batch==null || batch.size()!=missing.size())throw new IllegalStateException("Invalid metadata embedding count");
        for(int i=0;i<missing.size();i++)vectors.put(missing.get(i),normalized(batch.get(i)));
      }
      var queryBatch=model.embed(List.of(request));RunCancellation.propagate(null);
      if(queryBatch==null || queryBatch.size()!=1)throw new IllegalStateException("Invalid query embedding count");
      var query=normalized(queryBatch.getFirst());
      for(var vector:vectors.values())if(vector.length!=query.length) {
        bypassPersistent=true;
        if(persistent())try{index.invalidate(settings.indexNamespace());}catch(RuntimeException error){RunCancellation.propagate(error);log.warn("Skill index invalidation unavailable ({})",error.getClass().getSimpleName());}
        indexedProfiles=null;throw new IllegalStateException("Embedding dimensions changed");
      }
      cache=Map.copyOf(vectors);failureAt=null;
      if(persistent()&&(!Objects.equals(indexedProfiles,vectors.keySet())||!missing.isEmpty()))try {
        index.replace(settings.indexNamespace(),vectors);RunCancellation.propagate(null);indexedProfiles=Set.copyOf(vectors.keySet());bypassPersistent=false;
      }catch(RuntimeException error){RunCancellation.propagate(error);log.warn("Skill index write unavailable; keeping live candidates ({})",error.getClass().getSimpleName());}
      if(zero(query))return lexical.stream().limit(limit).toList();
      dense=eligible.stream().map(s->new Dense(s,cosine(query,vectors.get(profile(s)))))
          .filter(s->s.score()>=settings.minimumSimilarity()).sorted(Comparator.comparingDouble(Dense::score).reversed().thenComparing(s->identity(s.skill())))
          .map(s->new SkillCandidate(s.skill(),0,List.of("semantic"),List.of())).toList();
    }catch(RuntimeException error) {
      RunCancellation.propagate(error);cache=Map.of();failureAt=nanos.getAsLong();
      log.warn("Semantic skill retrieval unavailable; using keyword candidates ({})",error.getClass().getSimpleName());
      return lexical.stream().limit(limit).toList();
    }
    var lexicalById=new HashMap<String,SkillCandidate>();for(var candidate:lexical)lexicalById.putIfAbsent(identity(candidate.skill()),candidate);
    int pool=Math.min(256,Math.max(10,limit));
    var fused=new ReciprocalRankFusion(60).fuse(List.of(dense,lexical),c->identity(c.skill()),pool).stream().map(rank->{
      var candidate=rank.item();var keyword=lexicalById.get(identity(candidate.skill()));var fields=new LinkedHashSet<>(candidate.matchedFields());
      if(keyword!=null)fields.addAll(keyword.matchedFields());fields.add("rrf");
      return new SkillCandidate(candidate.skill(),Math.max(1,(int)Math.round(rank.score()*1000)),List.copyOf(fields),keyword==null?List.of():keyword.matchedKeywords());
    }).toList();
    RunCancellation.propagate(null);
    var ordered=fused;
    try {
      var ranker=reranker.get();
      if(ranker!=null && !fused.isEmpty()) {
        var result=ranker.rerank(request,fused,c->profile(c.skill()));RunCancellation.propagate(null);
        var originals=new HashMap<String,SkillCandidate>();for(var c:fused)originals.put(identity(c.skill()),c);
        var ids=result.stream().map(c->identity(c.skill())).toList();
        if(result.size()!=fused.size() || new HashSet<>(ids).size()!=ids.size() || !originals.keySet().equals(new HashSet<>(ids)))throw new IllegalStateException("Invalid skill rerank identities");
        ordered=ids.stream().map(originals::get).toList();
      }
    }catch(RuntimeException error){RunCancellation.propagate(error);log.warn("Skill rerank unavailable; using fusion order ({})",error.getClass().getSimpleName());}
    return ordered.stream().limit(limit).toList();
  }
  static String profile(AgentSkill skill){return "name: "+SkillCandidateSelector.normalize(skill.name())+"\ndescription: "+SkillCandidateSelector.normalize(skill.description())+"\nkeywords: "+String.join(", ",skill.keywords().stream().map(SkillCandidateSelector::normalize).toList());}
  private boolean persistent(){return settings.persistentIndexEnabled()&&index!=null;}
  static String identity(AgentSkill skill){return (skill.skillFile()==null?"":skill.skillFile().toAbsolutePath().normalize().toString())+"#"+SkillCandidateSelector.normalize(skill.name());}
  private record Dense(AgentSkill skill,double score) {}
  private static float[] normalized(float[] vector) {
    if(vector==null || vector.length<1 || vector.length>8192)throw new IllegalStateException("Invalid embedding size");
    double norm=0;for(float value:vector){if(!Float.isFinite(value))throw new IllegalStateException("Invalid embedding value");norm+=(double)value*value;}
    var result=vector.clone();if(norm>0){double length=Math.sqrt(norm);for(int i=0;i<result.length;i++)result[i]=(float)(result[i]/length);}return result;
  }
  private static double cosine(float[] a,float[] b){double score=0;for(int i=0;i<a.length;i++)score+=(double)a[i]*b[i];return Math.max(-1,Math.min(1,score));}
  private static boolean zero(float[] vector){for(float value:vector)if(value!=0)return false;return true;}
}
