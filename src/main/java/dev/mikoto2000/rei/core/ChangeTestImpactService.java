package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import org.springframework.stereotype.Component;

/** Conservative structural candidates, never a coverage guarantee or permission to skip tests. */
@Component
public final class ChangeTestImpactService {
  public record Candidate(String path,boolean testCandidate,int distance,String reason,String via) {}
  public record Result(String root,String version,Instant scannedAt,boolean partial,List<String> warnings,
      List<String> changedFiles,List<String> unindexedChanges,List<Candidate> candidates) {}
  private final RepositoryMapService maps;
  public ChangeTestImpactService(RepositoryMapService maps){this.maps=maps;}
  public Result analyze(Path root,List<String> changedFiles,int limit)throws IOException {
    RunCancellation.propagate(null);
    if(changedFiles==null || changedFiles.isEmpty() || changedFiles.size()>64 || limit<1 || limit>100)
      throw new IllegalArgumentException("Require 1 to 64 changed paths and limit 1 to 100");
    var changes=new LinkedHashSet<String>();
    for(String name:changedFiles){
      if(name==null || name.isBlank() || name.length()>1024)throw new IllegalArgumentException("Invalid changed path");
      Path path=Path.of(name.replace('\\','/')).normalize();
      if(path.isAbsolute() || path.startsWith("..") || path.toString().isEmpty())throw new IllegalArgumentException("Changed paths must be project relative");
      changes.add(path.toString().replace('\\','/'));
    }
    var snapshot=maps.snapshot(root);var warnings=new ArrayList<>(snapshot.warnings());
    Set<String> indexed=new HashSet<>();snapshot.items().forEach(f->indexed.add(f.path()));
    var missing=changes.stream().filter(p->!indexed.contains(p)).toList();
    if(!missing.isEmpty())warnings.add("Unindexed changes: deleted, excluded, unavailable or outside the index budget; broader regression required");
    var reverse=new HashMap<String,List<RepositoryMapService.Relation>>();
    snapshot.relations().forEach(r->reverse.computeIfAbsent(r.target(),p->new ArrayList<>()).add(r));
    var found=new LinkedHashMap<String,Candidate>();var queue=new ArrayDeque<Candidate>();
    for(String change:changes)if(indexed.contains(change)){var seed=new Candidate(change,isTest(change),0,"CHANGED",change);found.put(change,seed);queue.add(seed);}
    while(!queue.isEmpty()) {
      RunCancellation.propagate(null);var current=queue.removeFirst();
      for(var edge:reverse.getOrDefault(current.path(),List.of()))if(!found.containsKey(edge.source())) {
        var candidate=new Candidate(edge.source(),isTest(edge.source()),current.distance()+1,edge.kind(),current.path());
        found.put(candidate.path(),candidate);queue.addLast(candidate);
      }
    }
    if(found.size()>limit)warnings.add("Candidate output limited; refine changed paths or run broader regression");
    warnings.add("Structural candidates only: same-package references, wildcard imports, reflection, resources and build configuration may affect additional tests");
    boolean partial=snapshot.partial() || !missing.isEmpty() || found.size()>limit;
    return new Result(snapshot.root(),snapshot.version(),snapshot.scannedAt(),partial,List.copyOf(warnings),List.copyOf(changes),missing,
        found.values().stream().sorted(Comparator.comparing(Candidate::testCandidate).reversed().thenComparingInt(Candidate::distance).thenComparing(Candidate::path)).limit(limit).toList());
  }
  private static boolean isTest(String path){return path.contains("/test/") || path.startsWith("test/");}
}
