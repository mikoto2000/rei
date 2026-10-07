package dev.mikoto2000.rei.subagent;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentDagSpecTest {
  SubAgentDagSpec.Node node(String id,String... dependencies){return new SubAgentDagSpec.Node(id,"reviewer","inspect "+id,null,List.of(dependencies));}
  @Test void fanOutAndFanInProduceOnlyDependencyReadyFrontiers() {
    var spec=new SubAgentDagSpec(List.of(node("a"),node("b"),node("compare","a","b"),node("review","compare")),SubAgentDagSpec.FailurePolicy.FAIL_FAST,2);
    assertEquals(List.of("a","b"),spec.ready(Set.of(),Set.of()));
    assertEquals(List.of("b"),spec.ready(Set.of("a"),Set.of()));
    assertEquals(List.of("compare"),spec.ready(Set.of("a","b"),Set.of()));
    assertEquals(List.of("review"),spec.ready(Set.of("a","b","compare"),Set.of()));
    assertEquals(List.of(),spec.ready(Set.of("a","b","compare","review"),Set.of()));
  }
  @Test void cyclesUnknownDependenciesAndUnboundedInputsAreRejectedBeforeExecution() {
    assertThrows(IllegalArgumentException.class,()->new SubAgentDagSpec(List.of(node("a","b"),node("b","a")),SubAgentDagSpec.FailurePolicy.FAIL_FAST,2));
    assertThrows(IllegalArgumentException.class,()->new SubAgentDagSpec(List.of(node("a","missing")),SubAgentDagSpec.FailurePolicy.FAIL_FAST,2));
    assertThrows(IllegalArgumentException.class,()->new SubAgentDagSpec(List.of(node("a"),node("a")),SubAgentDagSpec.FailurePolicy.FAIL_FAST,2));
    assertThrows(IllegalArgumentException.class,()->new SubAgentDagSpec(List.of(node("a")),SubAgentDagSpec.FailurePolicy.FAIL_FAST,3));
    assertThrows(IllegalArgumentException.class,()->new SubAgentDagSpec(java.util.stream.IntStream.range(0,17).mapToObj(i->node("n"+i)).toList(),SubAgentDagSpec.FailurePolicy.FAIL_FAST,2));
  }
  @Test void failFastStopsWhilePartialPolicyContinuesOnlyIndependentBranches() {
    var nodes=List.of(node("a"),node("b"),node("compare","a","b"));
    assertEquals(List.of(),new SubAgentDagSpec(nodes,SubAgentDagSpec.FailurePolicy.FAIL_FAST,2).ready(Set.of(),Set.of("a")));
    var partial=new SubAgentDagSpec(nodes,SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT,2);
    assertEquals(List.of("b"),partial.ready(Set.of(),Set.of("a")));
    assertEquals(List.of(),partial.ready(Set.of("b"),Set.of("a")));
  }
}
