package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationSpecificationTest {
  @TempDir Path root;
  ImplementationSpecification spec(String target,List<String> paths,List<ImplementationSpecification.AcceptanceCriterion> criteria) {
    return new ImplementationSpecification(1,"Improve greeting",List.of("Replace before with after"),target,paths,List.of("No new files"),criteria,List.of(),"REPLACE_EXISTING_TEXT");
  }
  @Test void normalizesPathsAndHashesDeterministicallyWithoutLosingRequirements() throws Exception {
    Files.writeString(root.resolve("A.txt"),"before");
    var first=ImplementationSpecificationValidator.validate(root,spec("./A.txt",List.of("./A.txt"),List.of(new ImplementationSpecification.AcceptanceCriterion("greeting","Greeting says after"))));
    var second=ImplementationSpecificationValidator.validate(root,spec("A.txt",List.of("A.txt"),List.of(new ImplementationSpecification.AcceptanceCriterion("greeting","Greeting says after"))));
    assertEquals(first.sha256(),second.sha256());assertEquals("A.txt",first.specification().target());
    assertTrue(first.task().contains("Replace before with after"));assertTrue(first.task().contains("No new files"));
  }
  @Test void rejectsMissingDuplicateUnsupportedAndOversizedRequirements() throws Exception {
    Files.writeString(root.resolve("A.txt"),"before");
    var criterion=new ImplementationSpecification.AcceptanceCriterion("one","Works");
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,spec("A.txt",List.of(),List.of(criterion))));
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,spec("A.txt",List.of("A.txt"),List.of(criterion,criterion))));
    var invalid=new ImplementationSpecification(1,"x",List.of("y"),"A.txt",List.of("A.txt"),List.of(),List.of(criterion),List.of(),"CREATE");
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,invalid));
    var huge=new ImplementationSpecification(1,"x",Collections.nCopies(5,"a".repeat(1000)),"A.txt",List.of("A.txt"),List.of(),List.of(criterion),List.of(),"REPLACE_EXISTING_TEXT");
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,huge));
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,null));
  }
  @Test void rejectsOutsideMissingAndAllowedPathsOutsideTarget() throws Exception {
    Files.writeString(root.resolve("A.txt"),"before");Files.writeString(root.resolve("B.txt"),"before");
    var criteria=List.of(new ImplementationSpecification.AcceptanceCriterion("one","Works"));
    for(String target:List.of("../outside","missing"))assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,spec(target,List.of("A.txt"),criteria)));
    assertThrows(IllegalArgumentException.class,()->ImplementationSpecificationValidator.validate(root,spec("A.txt",List.of("B.txt"),criteria)));
  }
}
