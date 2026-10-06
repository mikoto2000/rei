package dev.mikoto2000.rei.core.dependency;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.workcontext.WorkContextGit;

@Component
public class DependencySourceProbe implements DependencyProbe {
  private final FileGoalVerifier files;private final WorkContextGit git;private final BackgroundProcessManager processes;
  private final DependencyHttpProbe http;private final Clock clock;
  public DependencySourceProbe(FileGoalVerifier files,WorkContextGit git,BackgroundProcessManager processes,DependencyHttpProbe http,Clock clock){this.files=files;this.git=git;this.processes=processes;this.http=http;this.clock=clock;}
  public String fileBaseline(Path root,String file) {
    var snapshot=files.fingerprint(root,file);
    if(snapshot.available())return snapshot.sha256();
    if(snapshot.reason().equals("file_missing_or_not_regular"))return "missing";
    if(snapshot.reason().equals("verification_cancelled"))throw new java.util.concurrent.CancellationException();
    throw new IllegalStateException("File fingerprint unavailable");
  }
  public String gitBaseline(Path root) {
    var captured=git.capture(root,clock.instant());
    if(captured.branch()==null||captured.commit()==null)throw new IllegalStateException("Git baseline unavailable");
    return captured.branch()+"\n"+captured.commit();
  }
  @Override public DependencyObservation probe(PersistentDependencyRepository.Entry entry) {
    var spec=entry.spec();var root=Path.of(entry.projectRoot());
    return switch(spec.kind()) {
      case FILE_EXISTS,FILE_SHA256 -> {
        var result=files.verify(root,new GoalRepository.FileCriterion(spec.target(),spec.expected()));
        if(result.reason().equals("verification_cancelled"))throw new java.util.concurrent.CancellationException();
        var state=result.satisfied()?DependencyState.COMPLETED:java.util.Set.of("file_missing_or_not_regular","digest_mismatch").contains(result.reason())?DependencyState.WAITING:DependencyState.BLOCKED;
        yield new DependencyObservation(entry.id(),state,result.reason());
      }
      case FILE_CHANGED -> {
        try {boolean changed=!fileBaseline(root,spec.target()).equals(spec.expected());yield new DependencyObservation(entry.id(),changed?DependencyState.COMPLETED:DependencyState.WAITING,changed?"file_changed":"file_unchanged");}
        catch(IllegalStateException unavailable){yield new DependencyObservation(entry.id(),DependencyState.BLOCKED,"file_snapshot_unavailable");}
      }
      case GIT_STATE_CHANGED -> {
        try {boolean changed=!gitBaseline(root).equals(spec.expected());yield new DependencyObservation(entry.id(),changed?DependencyState.COMPLETED:DependencyState.WAITING,changed?"git_state_changed":"git_state_unchanged");}
        catch(IllegalStateException unavailable){yield new DependencyObservation(entry.id(),DependencyState.BLOCKED,"git_unavailable");}
      }
      case PROCESS_EXIT -> {
        var process=processes.statusOwned(spec.target(),entry.projectId(),entry.sessionId(),root);
        var state=!process.found()?DependencyState.BLOCKED:switch(process.status()) {
          case STARTING,RUNNING -> DependencyState.RUNNING;
          case EXITED -> process.exitCode()!=null&&process.exitCode()==Integer.parseInt(spec.expected())?DependencyState.COMPLETED:DependencyState.FAILED;
          case FAILED -> DependencyState.FAILED;case KILLED -> DependencyState.CANCELLED;
        };
        yield new DependencyObservation(entry.id(),state,!process.found()?"process_unavailable":"process_"+state.name().toLowerCase(java.util.Locale.ROOT));
      }
      case HTTP_STATUS -> http.probe(entry.id(),spec.target(),Integer.parseInt(spec.expected()));
      case HTTP_BODY_SHA256 -> http.probeBody(entry.id(),spec.target(),Integer.parseInt(spec.expected().substring(0,3)),spec.expected().substring(4));
      case HTTP_JSON_VALUE -> http.probeJson(entry.id(),spec.target(),spec.expected());
      case USER_ANSWER -> new DependencyObservation(entry.id(),entry.answer()==null?DependencyState.WAITING:DependencyState.COMPLETED,entry.answer()==null?"user_answer_waiting":"user_answer_received");
    };
  }
}
