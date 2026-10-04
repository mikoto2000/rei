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
  public record IntegrationCandidate(String path,String module,String reason) {}
  public record RegressionAssessment(String scope,List<String> reasons,List<String> affectedModules,
      List<IntegrationCandidate> integrationCandidates,boolean partial) {}
  public record Result(String root,String version,Instant scannedAt,boolean partial,List<String> warnings,
      List<String> changedFiles,List<String> unindexedChanges,List<Candidate> candidates,RegressionAssessment regressionAssessment) {}
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
    var assessment=assess(snapshot,changes,missing,found.values(),partial);
    if(assessment.partial()&&!partial)warnings.add("Regression assessment output limited; broader regression required");
    return new Result(snapshot.root(),snapshot.version(),snapshot.scannedAt(),partial||assessment.partial(),List.copyOf(warnings),List.copyOf(changes),missing,
        found.values().stream().sorted(Comparator.comparing(Candidate::testCandidate).reversed().thenComparingInt(Candidate::distance).thenComparing(Candidate::path)).limit(limit).toList(),assessment);
  }
  private static RegressionAssessment assess(RepositoryMapService.View snapshot,Set<String> changes,List<String> missing,
      Collection<Candidate> affected,boolean partial) {
    var reasons=new ArrayList<String>();var modules=new TreeSet<String>();
    var byPath=new HashMap<String,RepositoryMapService.File>();snapshot.items().forEach(file->byPath.put(file.path(),file));
    for(var candidate:affected){var file=byPath.get(candidate.path());if(file!=null)modules.add(file.module());}
    boolean crossModule=modules.size()>1;
    boolean build=changes.stream().anyMatch(ChangeTestImpactService::buildConfiguration);
    if(build) {
      reasons.add("BUILD_CONFIGURATION_CHANGED");snapshot.items().forEach(file->modules.add(file.module()));
    }
    if(!missing.isEmpty()) {
      reasons.add("UNINDEXED_CHANGES");snapshot.items().forEach(file->modules.add(file.module()));
    }
    if(snapshot.partial())reasons.add("PARTIAL_INDEX");
    if(crossModule)reasons.add("CROSS_MODULE_REFERENCES");
    if(partial)reasons.add("INCOMPLETE_CANDIDATE_OUTPUT");
    boolean broad=!reasons.isEmpty();reasons.add("STRUCTURAL_EVIDENCE_ONLY_NO_COVERAGE_GUARANTEE");
    var integrations=new ArrayList<IntegrationCandidate>();
    for(var file:snapshot.items()) {
      RunCancellation.propagate(null);
      if(!modules.contains(file.module())||!isTest(file.path())||!file.path().endsWith(".java"))continue;
      String name=Path.of(file.path()).getFileName().toString();String reason=null;
      if(file.path().contains("/it/")||file.path().startsWith("it/")||file.path().contains("/integration/"))reason="AFFECTED_MODULE_INTEGRATION_DIRECTORY";
      else if(name.endsWith("IT.java")||name.endsWith("IntegrationTest.java"))reason="AFFECTED_MODULE_NAME_CONVENTION";
      else if(file.imports().stream().anyMatch(value->value.equals("org.springframework.boot.test.context.SpringBootTest")||value.startsWith("org.testcontainers.")))reason="AFFECTED_MODULE_TEST_CONTEXT_IMPORT";
      if(reason!=null)integrations.add(new IntegrationCandidate(file.path(),file.module(),reason));
    }
    integrations.sort(Comparator.comparing(IntegrationCandidate::path));
    boolean truncated=integrations.size()>100||modules.size()>100;
    if(truncated)reasons.add("ASSESSMENT_OUTPUT_LIMITED");
    return new RegressionAssessment(broad||truncated?"BROAD_REGRESSION_REQUIRED":"STRUCTURAL_EVIDENCE_ONLY",List.copyOf(reasons),
        modules.stream().limit(100).toList(),integrations.stream().limit(100).toList(),partial||truncated);
  }
  private static boolean buildConfiguration(String path) {
    String name=Path.of(path).getFileName().toString();
    return Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts","gradle.properties","package.json","Cargo.toml","Makefile").contains(name)
        ||path.startsWith(".github/workflows/");
  }
  private static boolean isTest(String path){return path.contains("/test/")||path.startsWith("test/")||path.contains("/it/")||path.startsWith("it/")||path.contains("/integration/");}
}
