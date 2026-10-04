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
