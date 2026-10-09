package dev.mikoto2000.rei.externalagent;

import java.util.*;

/** Criterion evidence is bound to one receipt/specification/patch; model success is never proof. */
public record AcceptanceEvaluation(String criterionId,String status,List<String> evidence,String explanation,
    String evaluator,String evaluatedPatchSha256) {
  public AcceptanceEvaluation {
    if(!Set.of("VERIFIED","FAILED","NOT_VERIFIED").contains(status))throw new IllegalArgumentException("Invalid acceptance status");
    evidence=List.copyOf(evidence);
  }
  static List<AcceptanceEvaluation> unverified(ImplementationSpecification specification,IsolatedImplementationService.Receipt receipt) {
    return specification.acceptanceCriteria().stream().map(c->new AcceptanceEvaluation(c.id(),"NOT_VERIFIED",
        receipt.patchHash()==null?List.of():List.of("receipt:"+receipt.id(),"patch:"+receipt.patchHash()),
        "Administrator test exit and static review do not establish this business requirement; parent semantic evaluation required",
        "SERVER_EVIDENCE_LIMIT",receipt.patchHash())).toList();
  }
}
