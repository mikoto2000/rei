package dev.mikoto2000.rei.workcontext;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.ProjectContext;

/** Seen state belongs to each UI client, not a global project or the persisted data. */
@Component
public class WorkContextPresenter {
  private final WorkContextService service;private final WorkContextProperties properties;private final WorkContextGit git;
  public WorkContextPresenter(WorkContextService service,WorkContextProperties properties,WorkContextGit git) {this.service=service;this.properties=properties;this.git=git;}
  public Optional<String> present(ProjectContext project,String session,Set<String> seen) {
    if(!properties.autoPresent()) return Optional.empty();
    String key=project.id()+":"+Objects.toString(session,"unselected");
    synchronized(seen) {
      if(seen.contains(key)) return Optional.empty();
      var saved=service.current(project.id()).orElse(null);
      var current=saved==null?null:git.capture(project.root(),Instant.now());
      String text=new WorkContextFormatter().summary(saved,current);seen.add(key);return Optional.of(text);
    }
  }
}
