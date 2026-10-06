package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubAgentToolOutcomeTest {
  final SubAgentEvidenceTest fixture = new SubAgentEvidenceTest();
  @Test void successNeedsMatchingArgumentsAndObservedOutputFieldsOnTheSameReceipt() {
    var ledger = new SubAgentEvidence();
    var required = List.of(new SubAgentRequiredCall("readMultiFile", "{}", "{\"status\":\"OK\"}"));
    var failed = fixture.claim(ledger.capture("readMultiFile", "{}", "{\"status\":\"FAILED\"}"), "FAILED");
    var json = new SubAgentResultParser().parse(fixture.answer("SUCCESS", List.of(failed)));
    assertFalse(ledger.validate(List.of("readMultiFile"), required, json).valid());
    assertTrue(ledger.validate(List.of("readMultiFile"), required,
        new SubAgentResultParser().parse(fixture.answer("PARTIAL", List.of(failed)))).valid());
    var wrongArguments = fixture.claim(ledger.capture("readMultiFile", "{\"path\":\"other\"}",
        "{\"status\":\"OK\"}"), "OK");
    assertFalse(ledger.validate(List.of("readMultiFile"), required,
        new SubAgentResultParser().parse(fixture.answer("SUCCESS", List.of(failed, wrongArguments)))).valid());
    var good = fixture.claim(ledger.capture("readMultiFile", "{}", "{\"status\":\"OK\",\"extra\":1}"), "OK");
    assertTrue(ledger.validate(List.of("readMultiFile"), required,
        new SubAgentResultParser().parse(fixture.answer("SUCCESS", List.of(good)))).valid());
  }

  @Test void invalidTruncatedAndTypeMismatchedOutputsCannotSatisfyContract() {
    var required = List.of(new SubAgentRequiredCall("readMultiFile", "{}", "{\"found\":true}"));
    for (String output : List.of("not JSON", "{\"found\":\"true\"}", "{\"found\":true,\"found\":false}",
        "{\"found\":true} trailing", "{\"found\":true,\"extra\":\"" + "x".repeat(17000) + "\"}")) {
      var ledger = new SubAgentEvidence();
      var claim = fixture.claim(ledger.capture("readMultiFile", "{}", output), output.substring(0, 5));
      assertFalse(ledger.validate(List.of("readMultiFile"), required,
          new SubAgentResultParser().parse(fixture.answer("SUCCESS", List.of(claim)))).valid());
    }
  }

  @Test void expectedOutputMustBeANonemptyBoundedObject() {
    for (String value : List.of("{}", "[]", "null", "{} {}", "{\"a\":1,\"a\":2}",
        "{\"a\":\"" + "x".repeat(4096) + "\"}")) {
      assertThrows(IllegalArgumentException.class, () -> new SubAgentRequiredCall("readMultiFile", "{}", value));
    }
    assertNull(new SubAgentRequiredCall("readMultiFile", "{}").expectedOutputJson());
  }
}
