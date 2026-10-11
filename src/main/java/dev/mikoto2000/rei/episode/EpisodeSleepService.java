package dev.mikoto2000.rei.episode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.llm.ModelCallBudget;
@Service
@EnableConfigurationProperties(EpisodeProperties.class)
public class EpisodeSleepService {
  private final EpisodeRepository repository;private final ConversationTurnStore turns;
  private final EpisodeProcessor processor;private final EpisodeProperties properties;private final MemoryProperties memory;
  private EpisodeDenseIndex dense;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setDense(EpisodeDenseIndex dense){this.dense=dense;}
  private org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextService> work;
  @org.springframework.beans.factory.annotation.Autowired
  public void setWorkContext(org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextService> work){this.work=work;}
  public EpisodeSleepService(EpisodeRepository repository,ConversationTurnStore turns,EpisodeProcessor processor,EpisodeProperties properties,MemoryProperties memory) {
    this.repository=repository;this.turns=turns;this.processor=processor;this.properties=properties;this.memory=memory;
  }
  public void process(String session,String project,ModelCallBudget budget,java.util.function.BooleanSupplier cancelled) {
    if(!properties.enabled()||!memory.enabled())return;
    String worker=UUID.randomUUID().toString();
    if(!repository.acquire(session,worker,(long)memory.sleep().timeoutSeconds()+60))throw new IllegalStateException("Episode processing already running");
    try {
    long from=repository.checkpoint(session),to=from;int tokens=0;
    var batch=new ArrayList<ConversationTurnStore.Turn>();
    for(var turn:turns.readRange(session,from,Math.min(50,memory.sleep().maxTurns()))) {
      if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
      if(turn.status()==ConversationTurnStore.Status.RUNNING)break;
      int size=dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(Objects.toString(turn.request(),"")+Objects.toString(turn.assistantMessage(),""))+64;
      if(tokens+size>memory.sleep().maxInputTokens())break;
      to++;tokens+=size;batch.add(turn);
    }
    if(to>from)processor.process(session,project,from,to,List.copyOf(batch),budget);
    if(dense!=null)dense.indexPending(session,budget);
    if(work!=null&&work.getIfAvailable()!=null)work.getObject().current(project).ifPresent(context->repository.linkWorkContext(context,session));
    } finally {repository.release(session,worker);}
  }
  public void linkMemory(String project,String session,java.util.List<String> runs,String memoryId) {
    if(properties.enabled())repository.linkMemory(project,session,runs,memoryId);
  }
  public long pending(String session){return properties.enabled()?Math.max(Math.max(0,turns.turnCount(session)-repository.checkpoint(session)),dense==null?0:dense.pending(session)):0;}
}
