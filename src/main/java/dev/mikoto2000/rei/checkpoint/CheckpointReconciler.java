package dev.mikoto2000.rei.checkpoint;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.*;

/** Read-only comparison. Process inspection never attaches, restarts or terminates saved processes. */
@Component
public class CheckpointReconciler {
  private final BackgroundProcessManager processes;
  private dev.mikoto2000.rei.event.ProjectAgentEventStore eventStore;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  void setEventStore(dev.mikoto2000.rei.event.ProjectAgentEventStore store){eventStore=store;}
  private dev.mikoto2000.rei.core.contextbudget.RawToolResultStore rawResults;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  void setRawResults(dev.mikoto2000.rei.core.contextbudget.RawToolResultStore store){rawResults=store;}
  private dev.mikoto2000.rei.conversation.ConversationTurnStore turns;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  void setTurns(dev.mikoto2000.rei.conversation.ConversationTurnStore store){turns=store;}
  public CheckpointReconciler(BackgroundProcessManager processes){this.processes=processes;}
  public record Result(String decision,List<String> usable,List<String> changed,List<String> recheck,
      List<Operation> unknownOperations,List<String> blockers,String nextAction) {}
  public Result check(PersistentCheckpoint saved,boolean leased) {
    active();var usable=new ArrayList<String>();var changed=new ArrayList<String>();var recheck=new ArrayList<String>();var blockers=new ArrayList<String>();
    Path root=Path.of(saved.projectRoot());
    if(!Files.isDirectory(root))blockers.add("Project directory missing: "+root);
    if(leased)blockers.add("Task is owned by a live execution process");
    if("ABANDONED".equals(saved.status()))blockers.add("Task abandoned; history retained");
    if("RUNNING".equals(saved.status())&&!leased)recheck.add("Execution owner lost: interrupted, awaiting reconciliation");
    var currentGit=git(root);
    if(!saved.git().equals(currentGit))changed.add("Git branch/HEAD/work tree changed");
    else usable.add("Git snapshot matches (unavailable Git is not proof of clean work tree)");
    for(var file:saved.files().entrySet()) {
      active();String current=fingerprint(Path.of(file.getKey()));
      if(!file.getValue().equals(current))changed.add("File changed/missing: "+file.getKey());
      else if(current.startsWith("sha256:"))usable.add("File matches: "+file.getKey());
      else recheck.add("File fingerprint unavailable: "+file.getKey());
    }
    for(var process:saved.processes()) {
      active();boolean same=false;
      if(processes!=null&&PersistentCheckpointRepository.OWNER.equals(process.owner())) {
        var managed=processes.status(process.processId(),1);
        same=managed.found()&&managed.pid()==process.pid()&&Objects.equals(start(process.pid()),process.startedAt());
        if(same)usable.add("Managed process: "+process.processId()+" status="+managed.status()+"; wait/query existing result, do not spawn again");
      }
      if(!same)recheck.add("Process identity/reconnection unknown: "+process.processId()+"; do not restart or terminate");
    }
    var unknown=saved.operations().stream().filter(o->o.status()==OperationStatus.STARTED||o.status()==OperationStatus.UNKNOWN).toList();
    if(saved.evidence().stream().anyMatch(e->e.origin().equals("ASSISTANT_CLAIM")))recheck.add("Assistant completion claims are unverified; validate against tool evidence and acceptance criteria");
    saved.evidence().stream().filter(e->e.origin().equals("TOOL_CONFIRMED")).limit(20).forEach(e->usable.add("Confirmed tool result: "+e.toolCallId()+" "+e.summary()));
    if(turns!=null) {
      var newer=turns.read(saved.sessionId()).stream().filter(t->t.createdAt()!=null&&t.createdAt().isAfter(saved.createdAt())).map(t->t.runId()).limit(10).toList();
      if(!newer.isEmpty())recheck.add("Newer Session turns may change instructions/acceptance criteria; inspect history: "+newer);
    }
    if(eventStore!=null) {
      var ids=new HashSet<String>();saved.evidence().stream().map(Evidence::eventId).filter(Objects::nonNull).forEach(ids::add);
      eventStore.referenceStatus(saved.projectId(),ids).forEach((id,status)->{if(!status.equals("available"))recheck.add("Result reference "+id+": "+status);});
    }
    if(!unknown.isEmpty())recheck.add("Unknown operations require item-specific confirmation before repeating side effects");
    if(rawResults!=null)for(var evidence:saved.evidence())if(evidence.origin().equals("RAW_RESULT_REFERENCE")&&!rawResults.exists(saved.sessionId(),evidence.summary()))recheck.add("Raw result missing: "+evidence.summary());
    String next=changed.isEmpty()&&recheck.isEmpty()?saved.nextAction():"差分と結果不明の操作を再確認し、未検証の工程から再計画";
    return new Result(!blockers.isEmpty()?"BLOCKED":!unknown.isEmpty()?"CONFIRMATION_REQUIRED":"CONTINUE",List.copyOf(usable),List.copyOf(changed),List.copyOf(recheck),unknown,List.copyOf(blockers),next);
  }
  static void active(){if(Thread.currentThread().isInterrupted())throw new CancellationException();}
  static String start(long pid){return ProcessHandle.of(pid).flatMap(p->p.info().startInstant()).map(Object::toString).orElse(null);}
  public static String fingerprint(Path file) {
    active();
    try {
      if(!Files.exists(file))return "missing";
      if(!Files.isRegularFile(file))return "not-regular";
      if(Files.size(file)>16*1024*1024)return "metadata:"+Files.size(file)+":"+Files.getLastModifiedTime(file);
      var digest=java.security.MessageDigest.getInstance("SHA-256");
      try(var input=Files.newInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){active();digest.update(buffer,0,n);}}
      return "sha256:"+HexFormat.of().formatHex(digest.digest());
    }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){return "unavailable";}
  }
  public static Map<String,String> git(Path root) {
    active();var result=new LinkedHashMap<String,String>();
    result.put("branch",readGit(root,"rev-parse","--abbrev-ref","HEAD"));
    result.put("head",readGit(root,"rev-parse","HEAD"));
    result.put("workTree",readGit(root,"status","--porcelain=v1","--untracked-files=normal"));return Map.copyOf(result);
  }
  private static String readGit(Path root,String... args) {
    active();Process process=null;
    try {
      var command=new ArrayList<>(List.of("git","--no-optional-locks","-c","core.fsmonitor=false","-C",root.toString()));command.addAll(List.of(args));
      process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();var running=process;
      var output=CompletableFuture.supplyAsync(()->{
        try{return running.getInputStream().readNBytes(65537);}catch(java.io.IOException e){return new byte[0];}
      });
      if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)return "unavailable";
      byte[] bytes=output.get(1,TimeUnit.SECONDS);
      if(bytes.length>65536)return "oversized:recheck";
      return new String(bytes,java.nio.charset.StandardCharsets.UTF_8).strip();
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
    catch(java.io.IOException|ExecutionException|TimeoutException e){return "unavailable";}
    finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
  }
}
