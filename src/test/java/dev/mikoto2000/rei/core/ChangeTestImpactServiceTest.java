package dev.mikoto2000.rei.core;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ChangeTestImpactServiceTest {
  @TempDir Path root;
  @Test void followsReverseImportsAndTestCandidatesAndExplainsMissingFiles() throws Exception {
    var paths=List.of("src/main/java/p/A.java","src/main/java/p/B.java","src/test/java/p/BTest.java");
    for(String path:paths){Files.createDirectories(root.resolve(path).getParent());}
    Files.writeString(root.resolve(paths.get(0)),"package p; class A {}");
    Files.writeString(root.resolve(paths.get(1)),"package p; import p.A; class B {}");
    Files.writeString(root.resolve(paths.get(2)),"package p; class BTest {}");
    var service=new ChangeTestImpactService(new RepositoryMapService(p->paths));
    var result=service.analyze(root,List.of(paths.get(0),"Deleted.java"),20);
    assertTrue(result.candidates().stream().anyMatch(c->c.path().equals(paths.get(2)) && c.testCandidate() && c.distance()==2));
    assertTrue(result.partial());assertTrue(result.unindexedChanges().contains("Deleted.java"));
    assertThrows(IllegalArgumentException.class,()->service.analyze(root,List.of("../outside"),20));
  }
  @Test void limitsResultsAndRefreshesWithoutInventingRelationships() throws Exception {
    Files.writeString(root.resolve("A.java"),"class A {}");
    var service=new ChangeTestImpactService(new RepositoryMapService(p->List.of("A.java")));
    assertEquals(1,service.analyze(root,List.of("A.java"),1).candidates().size());
    Files.delete(root.resolve("A.java"));assertTrue(service.analyze(root,List.of("A.java"),1).candidates().isEmpty());
    assertThrows(IllegalArgumentException.class,()->service.analyze(root,List.of(),20));
  }
  @Test void assessesCrossModuleImpactAndIncludesIntegrationCandidatesOutsideImportGraph() throws Exception {
    var paths=List.of("lib/src/main/java/p/A.java","app/src/main/java/q/B.java","app/src/test/java/q/AppIntegrationTest.java","other/src/test/java/x/OtherIT.java");
    var sources=List.of("package p; public class A {}","package q; import p.A; class B {}","package q; class AppIntegrationTest {}","package x; class OtherIT {}");
    for(int index=0;index<paths.size();index++){Files.createDirectories(root.resolve(paths.get(index)).getParent());Files.writeString(root.resolve(paths.get(index)),sources.get(index));}
    var result=new ChangeTestImpactService(new RepositoryMapService(path->paths)).analyze(root,List.of(paths.getFirst()),1);
    var assessment=result.regressionAssessment();
    assertEquals(List.of("app","lib"),assessment.affectedModules());
    assertTrue(assessment.reasons().contains("CROSS_MODULE_REFERENCES"));
    assertEquals("BROAD_REGRESSION_REQUIRED",assessment.scope());
    assertEquals(List.of(paths.get(2)),assessment.integrationCandidates().stream().map(candidate->candidate.path()).toList());
    assertEquals("AFFECTED_MODULE_NAME_CONVENTION",assessment.integrationCandidates().getFirst().reason());
    assertEquals(1,result.candidates().size());
  }
  @Test void buildChangesRequireBroadRegressionAndDoNotClaimCoverage() throws Exception {
    String path="module/src/it/java/SmokeIT.java";Files.createDirectories(root.resolve(path).getParent());Files.writeString(root.resolve(path),"class SmokeIT {}");
    var service=new ChangeTestImpactService(new RepositoryMapService(directory->List.of(path)));
    var result=service.analyze(root,List.of("pom.xml"),5);
    assertEquals("BROAD_REGRESSION_REQUIRED",result.regressionAssessment().scope());
    assertTrue(result.regressionAssessment().reasons().contains("BUILD_CONFIGURATION_CHANGED"));
    assertTrue(result.regressionAssessment().reasons().contains("UNINDEXED_CHANGES"));
    assertFalse(result.regressionAssessment().reasons().contains("CROSS_MODULE_REFERENCES"));
    assertEquals(path,result.regressionAssessment().integrationCandidates().getFirst().path());
    assertTrue(service.analyze(root,List.of(path),5).candidates().getFirst().testCandidate());
  }
  @Test void integrationAssessmentHasIndependentBoundsAndExplicitPartialOutput() throws Exception {
    var paths=new ArrayList<String>();
    for(int index=0;index<101;index++) {
      String path="src/test/java/Test"+index+"IT.java";Files.createDirectories(root.resolve(path).getParent());
      Files.writeString(root.resolve(path),"class Test"+index+"IT {}");paths.add(path);
    }
    var result=new ChangeTestImpactService(new RepositoryMapService(directory->paths)).analyze(root,List.of(paths.getFirst()),1);
    assertEquals(100,result.regressionAssessment().integrationCandidates().size());
    assertTrue(result.regressionAssessment().partial());assertTrue(result.partial());
    assertTrue(result.regressionAssessment().reasons().contains("ASSESSMENT_OUTPUT_LIMITED"));
  }
  @Test void internalSnapshotIsNotRestrictedByMapDisplayLimitAndCyclesTerminate() throws Exception {
    var paths=new ArrayList<String>();
    for(int i=0;i<110;i++){String name="F"+i+".java";paths.add(name);Files.writeString(root.resolve(name),"class F"+i+" {}");}
    paths.add("ZA.java");paths.add("ZB.java");
    Files.writeString(root.resolve("ZA.java"),"package p; import p.ZB; class ZA {}");
    Files.writeString(root.resolve("ZB.java"),"package p; import p.ZA; class ZB {}");
    var result=new ChangeTestImpactService(new RepositoryMapService(p->paths)).analyze(root,List.of("ZA.java"),1);
    assertTrue(result.unindexedChanges().isEmpty());assertTrue(result.partial());
    assertEquals(1,result.candidates().size());
    var all=new ChangeTestImpactService(new RepositoryMapService(p->paths)).analyze(root,List.of("ZA.java"),10);
    assertEquals(2,all.candidates().size());
  }
}
