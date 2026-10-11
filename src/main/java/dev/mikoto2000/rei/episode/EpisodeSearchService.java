package dev.mikoto2000.rei.episode;
import java.util.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.workcontext.WorkContextService;
import dev.mikoto2000.rei.vectorstore.ReciprocalRankFusion;

@Service
public class EpisodeSearchService {
  public record Hit(String kind,String id,String projectId,String summary,String evidence,double score) {}
  private final EpisodeRepository episodes;private final MemoryRepository memories;
  private final ConversationHistorySearchService history;private final ConversationTurnStore turns;private final WorkContextService work;
  private dev.mikoto2000.rei.core.project.ProjectService projects;
  private int maxTokens=1500;
  private boolean memoryEnabled=true;
  private EpisodeDenseIndex dense;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setDense(EpisodeDenseIndex dense){this.dense=dense;}
  private dev.mikoto2000.rei.event.ProjectAgentEventStore events;
  @org.springframework.beans.factory.annotation.Autowired
  public void setEvents(dev.mikoto2000.rei.event.ProjectAgentEventStore events){this.events=events;}
  @org.springframework.beans.factory.annotation.Autowired
  public void setProjects(dev.mikoto2000.rei.core.project.ProjectService projects){this.projects=projects;}
  @org.springframework.beans.factory.annotation.Autowired
  public void setMemoryProperties(dev.mikoto2000.rei.memory.configuration.MemoryProperties properties){maxTokens=properties.retrieval().maxTokens();memoryEnabled=properties.enabled();}
  public EpisodeSearchService(EpisodeRepository episodes,MemoryRepository memories,ConversationHistorySearchService history,ConversationTurnStore turns,WorkContextService work) {
    this.episodes=episodes;this.memories=memories;this.history=history;this.turns=turns;this.work=work;
  }
  public List<Hit> search(HistorySearchRequest request,String context) {
    var range=EpisodeTimeRange.of(request.since(),request.until());
    String query=request.query();if(context!=null&&!context.isBlank())query=query+" "+clip(context,200);
    if(memoryEnabled&&(context==null||context.isBlank())) {
      var run=dev.mikoto2000.rei.core.chat.AgentRunScope.current();
      if(run!=null&&Objects.equals(run.projectId(),request.preferredProjectId())) {
        long count=turns.turnCount(run.conversationId());
        String recent=turns.readRange(run.conversationId(),Math.max(0,count-3),3).stream()
            .filter(t->t.status()!=ConversationTurnStore.Status.RUNNING&&!t.runId().equals(run.runId()))
            .limit(2).map(t->clip(t.request(),100)).collect(java.util.stream.Collectors.joining(" "));
        if(!recent.isBlank())query=query+" "+recent;
      }
    }
    if((context==null||context.isBlank())&&request.preferredProjectId()!=null) {
      var state=work.current(request.preferredProjectId());
      if(state.isPresent()) {
        String topics=state.get().items().stream().filter(i->i.status()==dev.mikoto2000.rei.workcontext.WorkContext.Status.OPEN
            &&(i.kind()==dev.mikoto2000.rei.workcontext.WorkContext.Kind.CURRENT_WORK||i.kind()==dev.mikoto2000.rei.workcontext.WorkContext.Kind.PURPOSE)).limit(2).map(i->clip(i.text(),100)).collect(java.util.stream.Collectors.joining(" "));
        if(!topics.isBlank())query=query+" "+topics;
      }
    }
    var histories=history.search(new HistorySearchRequest(query,request.preferredProjectId(),request.referencedProject(),request.retrievalScope(),request.conversationScope(),request.speaker(),request.since(),request.until(),8));
    var projects=new LinkedHashSet<String>();if(request.preferredProjectId()!=null)projects.add(request.preferredProjectId());
    histories.stream().map(ConversationSearchResult::sourceProjectId).filter(Objects::nonNull).forEach(projects::add);
    if(this.projects!=null&&request.retrievalScope()!=HistorySearchScope.CURRENT_PROJECT_ONLY)
      this.projects.registeredProjects().stream().limit(256).map(dev.mikoto2000.rei.core.project.ProjectContext::id).forEach(projects::add);
    var episodeHits=new ArrayList<Hit>();
    int foreign=0;
    for(String project:projects) {
      if(!memoryEnabled)break;
      boolean local=project.equals(request.preferredProjectId());
      if(!local&&(foreign>=HistoryRetrievalPolicy.CROSS_PROJECT_MAX_RESULTS||request.retrievalScope()==HistorySearchScope.CURRENT_PROJECT_PREFERRED&&episodeHits.size()>=3))continue;
      var lexical=episodes.search(query,project,local?8:3,range);
      List<Episode> denseCandidates=List.of();
      if(dense!=null)try{denseCandidates=dense.search(query,project,local?8:3,range);}catch(RuntimeException error){
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped)throw stopped;
        org.slf4j.LoggerFactory.getLogger(EpisodeSearchService.class).warn("Episode dense retrieval unavailable: {}",error.getClass().getSimpleName());
      }
      var ranked=new ReciprocalRankFusion(60).fuse(List.of(denseCandidates,lexical),Episode::id,local?8:3);
      for(var rank:ranked){var e=rank.item();if(available(e)) {
        if(!local&&foreign++>=3)break;
        episodeHits.add(new Hit("episode",e.id(),project,clip(e.title()+": "+e.summary(),500),e.occurredAt()+" "+e.status()+" "+e.claims().stream().map(c->c.evidence().name()).distinct().toList()+" "+(local?HistoryRetrievalPolicy.LOCAL_BOUNDARY:HistoryRetrievalPolicy.FOREIGN_BOUNDARY),0));
      }}
    }
    List<Hit> memoryHits=memoryEnabled?memories.search(query,request.preferredProjectId(),8).stream().filter(m->range.includes(m.updatedAt().toInstant())).map(m->new Hit("memory",m.id(),Objects.toString(m.projectId(),"GLOBAL"),clip(m.summary(),500),m.status().name(),0)).toList():List.of();
    var historyHits=histories.stream().map(h->new Hit("conversation",h.conversationId()+"/"+h.timestamp()+"/"+h.speaker(),h.sourceProjectId(),clip(h.content(),500),h.speaker()+" "+Objects.toString(h.contextBoundary(),HistoryRetrievalPolicy.LOCAL_BOUNDARY),0)).toList();
    var workHits=new ArrayList<Hit>();
    if(request.preferredProjectId()!=null)work.current(request.preferredProjectId()).ifPresent(w->w.items().stream().filter(i->range.includes(i.updatedAt())&&queryMatches(request.query(),i.text())).limit(8).forEach(i->workHits.add(new Hit("workContext",i.id(),w.projectId(),clip(i.text(),500),i.certainty().name(),0))));
    var fused=fuse(List.of(episodeHits.stream().limit(24).toList(),memoryHits,historyHits,workHits),Math.min(20,request.limit()==null?8:Math.max(1,request.limit())),4000);
    int tokens=0,other=0;var bounded=new ArrayList<Hit>();for(var hit:fused) {
      if(hit.projectId()!=null&&!hit.projectId().equals(request.preferredProjectId())&&!hit.projectId().equals("GLOBAL")&&other++>=3)continue;
      tokens+=dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(hit.toString())+32;
      if(tokens>maxTokens)break;bounded.add(hit);
    }return List.copyOf(bounded);
  }
  private static boolean queryMatches(String query,String text){return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).anyMatch(text.toLowerCase(Locale.ROOT)::contains);}
  public String registeredProject(String project) {
    if(projects==null||projects.registeredProjects().stream().noneMatch(p->p.id().equals(project)))throw new IllegalArgumentException("Unknown registered project");return project;
  }
  public boolean available(Episode e) {return e.claims().stream().map(Episode.Claim::runId).distinct().allMatch(run->runSource(e.sessionId(),run).isPresent())
      &&e.claims().stream().filter(c->c.speaker().equals("tool")).allMatch(c->toolSource(e,c).isPresent());}
  private Optional<ConversationTurnStore.Turn> runSource(String session,String run){return accessible(()->turns.findRun(session,run));}
  private static <T> Optional<T> accessible(java.util.function.Supplier<Optional<T>> source) {
    try{return source.get();}catch(RuntimeException error){dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);return Optional.empty();}
  }
  private Optional<dev.mikoto2000.rei.event.ToolCompletedPayload> toolSource(Episode e,Episode.Claim c) {
    return events==null?Optional.empty():accessible(()->events.findEvent(e.projectId(),c.sourceId())).filter(event->e.sessionId().equals(event.sessionId())&&c.runId().equals(event.runId()))
        .map(dev.mikoto2000.rei.event.AgentEvent::payload).filter(p->p instanceof dev.mikoto2000.rei.event.ToolCompletedPayload).map(p->(dev.mikoto2000.rei.event.ToolCompletedPayload)p);
  }
  public Map<String,Object> detail(String id,String project) {
    if(!memoryEnabled)throw new IllegalStateException("Memory is disabled");
    var revisions=episodes.revisions(id,project);if(revisions.isEmpty())throw new IllegalArgumentException("Unknown episode");
    boolean foreign=foreign(project);int start=Math.max(0,revisions.size()-(foreign?1:3));
    return Map.of("id",id,"projectId",project,"contextBoundary",foreign?HistoryRetrievalPolicy.FOREIGN_BOUNDARY:HistoryRetrievalPolicy.LOCAL_BOUNDARY,"relations",Objects.requireNonNullElse(episodes.relations(id,project),List.of()),"revisions",revisions.subList(start,revisions.size()).stream().map(e->available(e)?Map.of("revision",e.revision(),"status",e.status(),"title",clip(e.title(),120),"summary",clip(e.summary(),foreign?200:500),"claims",e.claims().stream().limit(foreign?3:8).map(c->Map.of("kind",c.kind(),"text",clip(c.text(),200),"evidence",c.evidence(),"runId",c.runId())).toList(),"sourceStatus","AVAILABLE"):Map.of("revision",e.revision(),"sourceStatus","UNAVAILABLE_DELETED_OR_EXPIRED")).toList());
  }
  public List<Map<String,String>> sources(String id,String project) {
    if(!memoryEnabled)throw new IllegalStateException("Memory is disabled");
    var result=new ArrayList<Map<String,String>>();
    var revisions=new ArrayList<>(episodes.revisions(id,project));Collections.reverse(revisions);
    for(var e:revisions)for(var c:e.claims()) {
      var source=runSource(e.sessionId(),c.runId());
      var text=c.speaker().equals("tool")?source.flatMap(t->toolSource(e,c)).map(t->clip(t.resultSummary(),500)):source.map(t->clip(c.speaker().equals("user")?t.request():t.assistantMessage(),500));
      result.add(Map.of("sessionId",e.sessionId(),"runId",c.runId(),"sourceId",Objects.toString(c.sourceId(),""),"speaker",c.speaker(),"evidence",c.evidence().name(),"status",text.isPresent()?"AVAILABLE":"UNAVAILABLE_DELETED_OR_EXPIRED","text",text.orElse("")));
      if(result.size()>=(foreign(project)?3:8))return List.copyOf(result);
    }return List.copyOf(result);
  }
  public Map<String,Object> detailPage(String id,String project,String beforeRevision) {
    if(!memoryEnabled)throw new IllegalStateException("Memory is disabled");
    boolean foreign=foreign(project);var page=episodes.revisionPage(id,project,beforeRevision,foreign?1:3);
    if(page.items().isEmpty()&&episodes.find(id,project).isEmpty())throw new IllegalArgumentException("Unknown episode");
    var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("projectId",project);
    result.put("contextBoundary",foreign?HistoryRetrievalPolicy.FOREIGN_BOUNDARY:HistoryRetrievalPolicy.LOCAL_BOUNDARY);
    result.put("nextCursor",page.nextCursor());result.put("relations",Objects.requireNonNullElse(episodes.relations(id,project),List.of()));
    result.put("revisions",page.items().stream().map(e->available(e)?Map.of("revision",e.revision(),"status",e.status(),"title",clip(e.title(),120),"summary",clip(e.summary(),foreign?200:500),"claims",e.claims().stream().limit(foreign?3:8).map(c->Map.of("kind",c.kind(),"text",clip(c.text(),200),"evidence",c.evidence(),"runId",c.runId())).toList(),"sourceStatus","AVAILABLE"):Map.of("revision",e.revision(),"sourceStatus","UNAVAILABLE_DELETED_OR_EXPIRED")).toList());return result;
  }
  public Map<String,Object> sourcesPage(String id,String project,String revision,int offset) {
    if(!memoryEnabled)throw new IllegalStateException("Memory is disabled");
    if(offset<0||offset>32)throw new IllegalArgumentException("Invalid source offset");
    var e=(revision==null?episodes.find(id,project):episodes.revision(id,project,revision)).orElseThrow(()->new IllegalArgumentException("Unknown episode revision"));
    if(offset>e.claims().size())throw new IllegalArgumentException("Invalid source offset");
    int end=Math.min(e.claims().size(),offset+(foreign(project)?3:8));var excerpts=new ArrayList<Map<String,String>>();
    for(var c:e.claims().subList(offset,end)) {
      var source=runSource(e.sessionId(),c.runId());
      var text=c.speaker().equals("tool")?source.flatMap(t->toolSource(e,c)).map(t->clip(t.resultSummary(),500)):source.map(t->clip(c.speaker().equals("user")?t.request():t.assistantMessage(),500));
      excerpts.add(Map.of("sessionId",e.sessionId(),"runId",c.runId(),"sourceId",Objects.toString(c.sourceId(),""),"speaker",c.speaker(),"evidence",c.evidence().name(),"status",text.isPresent()?"AVAILABLE":"UNAVAILABLE_DELETED_OR_EXPIRED","text",text.orElse("")));
    }
    var result=new LinkedHashMap<String,Object>();result.put("projectId",project);result.put("contextBoundary",foreign(project)?HistoryRetrievalPolicy.FOREIGN_BOUNDARY:HistoryRetrievalPolicy.LOCAL_BOUNDARY);result.put("revision",e.revision());result.put("sources",List.copyOf(excerpts));result.put("nextOffset",end<e.claims().size()?end:null);return result;
  }
  public static List<Hit> fuse(List<List<Hit>> streams,int limit,int maxChars) {
    int chars=0;var result=new ArrayList<Hit>();
    for(var rank:new ReciprocalRankFusion(60).fuse(streams,h->h.kind()+":"+h.projectId()+":"+h.id(),limit)) {
      var h=rank.item();chars+=h.summary().length();if(chars>maxChars)break;
      result.add(new Hit(h.kind(),h.id(),h.projectId(),h.summary(),h.evidence(),rank.score()));
    }return List.copyOf(result);
  }
  private static String clip(String text,int size){String safe=dev.mikoto2000.rei.event.CredentialRedactor.redact(Objects.toString(text,""));return safe.substring(0,Math.min(size,safe.length()));}
  private static boolean foreign(String project){var current=dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();return current!=null&&!current.id().equals(project);}
}
