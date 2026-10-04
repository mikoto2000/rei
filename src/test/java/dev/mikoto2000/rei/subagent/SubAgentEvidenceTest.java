package dev.mikoto2000.rei.subagent;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SubAgentEvidenceTest {
  final JsonMapper mapper = JsonMapper.builder().build();
  String answer(String status, Object evidence) {
    return mapper.writeValueAsString(Map.of("status",status,"summary","done","result",Map.of("evidence",evidence),"warnings",List.of()));
  }
  Map<String,String> claim(String receipt, String quote) {
    var json=new SubAgentResultParser().parse(receipt);
    return Map.of("evidenceId",json.get("evidenceId").asString(),"tool",json.get("tool").asString(),"outputSha256",json.get("outputSha256").asString(),"quote",quote);
  }
  @Test void successRequiresObservedRequiredToolsAndMatchingQuotes() {
    var ledger=new SubAgentEvidence();
    var json=new SubAgentResultParser().parse(answer("SUCCESS",List.of()));
    assertFalse(ledger.validate(List.of("readMultiFile"),json).valid());
    String receipt=ledger.capture("readMultiFile","{}","actual file content");
    var valid=new SubAgentResultParser().parse(answer("SUCCESS",List.of(claim(receipt,"actual file"))));
    assertTrue(ledger.validate(List.of("readMultiFile"),valid).valid());
    assertFalse(new SubAgentEvidence().validate(List.of("readMultiFile"),valid).valid());
    assertFalse(ledger.validate(List.of("readMultiFile"),new SubAgentResultParser().parse(answer("SUCCESS",List.of(claim(receipt,"invented secret"))))).valid());
  }
  @Test void partialMayReportMissingWorkButCannotForgeEvidenceOrDuplicateReferences() {
    var ledger=new SubAgentEvidence();
    assertTrue(ledger.validate(List.of("readMultiFile"),new SubAgentResultParser().parse(answer("PARTIAL",List.of()))).valid());
    var reference=claim(ledger.capture("readMultiFile","{}","observed"),"observed");
    var forged=new HashMap<>(reference);forged.put("outputSha256","0".repeat(64));
    var failure=ledger.validate(List.of("readMultiFile"),new SubAgentResultParser().parse(answer("PARTIAL",List.of(forged))));
    assertFalse(failure.valid());assertTrue(failure.errors().stream().noneMatch(e->e.message().contains("observed")));
    assertFalse(ledger.validate(List.of("readMultiFile"),new SubAgentResultParser().parse(answer("SUCCESS",List.of(reference,reference)))).valid());
  }
  @Test void receiptOutputIsBoundedAndDisclosesTruncation() {
    var ledger=new SubAgentEvidence();
    var receipt=new SubAgentResultParser().parse(ledger.capture("readMultiFile","{}","x".repeat(20000)));
    assertTrue(receipt.get("truncated").asBoolean());assertEquals(16384,receipt.get("output").asString().length());
    assertFalse(ledger.validate(List.of("readMultiFile"),new SubAgentResultParser().parse(answer("SUCCESS",Map.of()))).valid());
  }
}
