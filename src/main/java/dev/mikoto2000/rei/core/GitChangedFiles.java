package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.externalagent.*;

/** Bounded, read-only path inventory. Never reads diff bodies or invokes diff helpers. */
final class GitChangedFiles {
  record Observation(List<String> paths,boolean excluded) {}
  private final ExternalAgentProcessRunner processes;
  GitChangedFiles(ExternalAgentProcessRunner processes){this.processes=processes;}
  Observation collect(Path root)throws IOException {
    long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
    var identity=git(root,deadline,4096,"rev-parse","--show-toplevel","HEAD").lines().toList();
    if(identity.size()!=2||!Path.of(identity.getFirst()).toRealPath().equals(root)||!identity.getLast().matches("[0-9a-f]{40,64}"))
      throw new IOException("A Git Project root with a HEAD commit is required");
    var paths=new TreeSet<String>();
    add(paths,git(root,deadline,1048576,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-only","-z","--cached","HEAD","--"));
    add(paths,git(root,deadline,1048576,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-only","-z","--"));
    add(paths,git(root,deadline,1048576,"ls-files","--others","--exclude-standard","-z"));
    if(paths.size()>64)throw new IOException("Git changes exceed 64 paths; supply an explicit bounded selection");
    boolean excluded=paths.removeIf(name->RepositoryMapService.sensitive(Path.of(name)));
    return new Observation(List.copyOf(paths),excluded);
  }
  private static void add(Set<String> paths,String output)throws IOException {
    if(!output.isEmpty()&&!output.endsWith("\u0000"))throw new IOException("Incomplete Git path inventory");
    for(String name:output.split("\u0000")) {
      if(name.isEmpty())continue;
      try {
        Path path=Path.of(name);
        if(name.length()>1024||name.codePoints().anyMatch(Character::isISOControl)||path.isAbsolute()||path.normalize().startsWith(".."))
          throw new IOException("Unsupported Git change path; manual selection required");
      }catch(InvalidPathException error){throw new IOException("Unsupported Git change path",error);}
      paths.add(name);
    }
  }
  private String git(Path root,long deadline,int maxBytes,String... args)throws IOException {
    RunCancellation.propagate(null);
    long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("Git change inventory deadline exceeded");
    Duration timeout=Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(5).toNanos()));
    var command=new ArrayList<String>(List.of("git","--no-pager","--no-optional-locks","-c","core.fsmonitor=false"));
    command.addAll(List.of(args));
    var output=processes.run(command,root,"",timeout,timeout,maxBytes,()->Thread.currentThread().isInterrupted());
    if(output.status()==ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException("Git change inventory cancelled");
    RunCancellation.propagate(null);
    if(output.status()!=ExternalAgentResult.Status.SUCCESS||output.truncated())throw new IOException("Git change inventory unavailable or exceeds byte/time limit");
    return output.stdout();
  }
}
