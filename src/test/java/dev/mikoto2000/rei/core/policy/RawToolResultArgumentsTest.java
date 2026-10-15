package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultTools;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RawToolResultArgumentsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"rawResultRef\":\"reference\",\"offset\":0,\"limit\":1}",
        "{\"rawResultRef\":\"reference\",\"offset\":3433,\"limit\":16000}",
        "{\"rawResultRef\":\"reference\",\"offset\":2147483647,\"limit\":16000}"
      })
  void acceptsRequiredIntegerArgumentsWithoutReadingStorage(String input) {
    assertDoesNotThrow(() -> RawToolResultArguments.validate("readRawToolResult", input));
  }

  @Test
  void mistakenNextOffsetAndAllMissingOrInvalidFieldsAreReportedTogether() {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                RawToolResultArguments.validate(
                    "readRawToolResult",
                    "{\"rawResultRef\":false,\"nextOffset\":\"3433\",\"limit\":\"16000\"}"));
    for (String name : List.of("rawResultRef:", "offset:", "limit:", "nextOffset:"))
      assertTrue(error.getMessage().contains(name));
    assertTrue(error.getMessage().contains("引用符で囲まない"));
    assertTrue(error.getMessage().contains("offset: 0"));
    assertTrue(error.getMessage().contains("実行していません"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "-1",
        "2147483648",
        "4294967296",
        "0.5",
        "1e1",
        "\"3433\"",
        "true",
        "[]",
        "{}"
      })
  void invalidOffsetsAreModelCorrectableInsteadOfBindingFailures(String value) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                RawToolResultArguments.validate(
                    "readRawToolResult",
                    "{\"rawResultRef\":\"private-ref\",\"offset\":" + value + ",\"limit\":16000}"));
    assertTrue(error.getMessage().contains("offset:"));
    assertFalse(error.getMessage().contains("private-ref"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"null", "0", "-1", "16001", "4294967297", "1.5", "\"16000\"", "false", "[]", "{}"})
  void invalidLimitsAreNotClampedOrCoerced(String value) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                RawToolResultArguments.validate(
                    "readRawToolResult",
                    "{\"rawResultRef\":\"reference\",\"offset\":0,\"limit\":" + value + "}"));
    assertTrue(error.getMessage().contains("limit: required integer from 1 to 16000"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"rawResultRef\":null}",
        "{\"rawResultRef\":\" \"}",
        "{\"rawResultRef\":\"reference\",\"limit\":16000}",
        "{\"rawResultRef\":\"reference\",\"offset\":0}",
        "{\"rawResultRef\":\"reference\",\"offset\":0,\"limit\":16000,\"nextOffset\":1}"
      })
  void omittedRequiredArgumentsAndOutputOnlyCursorAreRejected(String input) {
    assertThrows(
        InvalidToolArgumentsException.class,
        () -> RawToolResultArguments.validate("readRawToolResult", input));
  }

  @Test
  void malformedAndBoundedJsonErrorsAreSafeToReturnToTheModel() {
    for (String input :
        new String[] {
          null,
          "",
          "not json",
          "[]",
          "{} {}",
          "{\"offset\":0,\"offset\":1}",
          " ".repeat(1_048_577),
          "[".repeat(40) + "0" + "]".repeat(40),
          "{\"offset\":" + "9".repeat(1001) + "}"
        }) {
      var error =
          assertThrows(
              InvalidToolArgumentsException.class,
              () -> RawToolResultArguments.validate("readRawToolResult", input));
      assertTrue(error.getMessage().contains("単一JSONオブジェクト"));
      assertTrue(error.getMessage().length() < 1200);
    }
    assertDoesNotThrow(() -> RawToolResultArguments.validate("readMultiFile", "not json"));
  }

  @Test
  void schemaRequiresOffsetAndExplainsMappingFromTheOutputCursor() {
    var store = mock(RawToolResultStore.class);
    var callback =
        DeliveryToolRegistration.provider(new RawToolResultTools(store)).getToolCallbacks()[0];
    var definition = callback.getToolDefinition();
    var schema = JsonMapper.builder().build().readTree(definition.inputSchema());
    for (String name : List.of("rawResultRef", "offset", "limit"))
      assertTrue(schema.path("required").toString().contains('"' + name + '"'));
    assertFalse(schema.path("properties").has("nextOffset"));
    assertTrue(
        schema
            .path("properties")
            .path("offset")
            .path("description")
            .asString()
            .contains("nextOffset"));
    assertTrue(definition.description().contains("output field, not an input argument"));
    verifyNoInteractions(store);
  }
}
