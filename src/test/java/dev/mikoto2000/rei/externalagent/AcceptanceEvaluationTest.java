package dev.mikoto2000.rei.externalagent;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcceptanceEvaluationTest {
  ImplementationSpecification specification=new ImplementationSpecification(1,"Change greeting",List.of("Use after"),"A.txt",List.of("A.txt"),List.of(),List.of(new ImplementationSpecification.AcceptanceCriterion("greeting","Says after")),List.of(),"REPLACE_EXISTING_TEXT");
  IsolatedImplementationService.Receipt receipt=new IsolatedImplementationService.Receipt("receipt","project","session","root","tree","branch","base","READY_FOR_APPROVAL","a".repeat(64),"commit",List.of("A.txt"),Map.of(),null,"Tests passed");
  @Test void readinessAndSuccessfulTestsDoNotEstablishBusinessAcceptance() {
    var result=AcceptanceEvaluation.unverified(specification,receipt);assertEquals("NOT_VERIFIED",result.getFirst().status());assertEquals(receipt.patchHash(),result.getFirst().evaluatedPatchSha256());
  }
  @Test void unsupportedModelClaimsRemainUnverifiedButEvidenceBackedParentFailureIsDistinct() {
    var claim=new AcceptanceEvaluation("greeting","VERIFIED",List.of(),"Codex claims success","PARENT_LLM",receipt.patchHash());
    assertEquals("NOT_VERIFIED",AcceptanceEvaluation.parent(specification,receipt,List.of(claim)).getFirst().status());
    var failure=new AcceptanceEvaluation("greeting","FAILED",List.of("receipt:receipt","patch:"+receipt.patchHash()),"Diff still says before","PARENT_LLM",receipt.patchHash());
    assertEquals("FAILED",AcceptanceEvaluation.parent(specification,receipt,List.of(failure)).getFirst().status());
  }
  @Test void cannotReuseEvaluationForAnotherPatchOrPretendItIsAnObjectiveTest() {
    var wrong=new AcceptanceEvaluation("greeting","VERIFIED",List.of("receipt:receipt"),"Works","PARENT_LLM","b".repeat(64));
    assertThrows(IllegalArgumentException.class,()->AcceptanceEvaluation.parent(specification,receipt,List.of(wrong)));
    var objective=new AcceptanceEvaluation("greeting","VERIFIED",List.of("receipt:receipt","patch:"+receipt.patchHash()),"Works","ADMIN_TEST",receipt.patchHash());
    assertThrows(IllegalArgumentException.class,()->AcceptanceEvaluation.parent(specification,receipt,List.of(objective)));
  }
}
