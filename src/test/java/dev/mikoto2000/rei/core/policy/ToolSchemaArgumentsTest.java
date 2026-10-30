package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolSchemaArgumentsTest {
  private static final String SCHEMA =
      """
      {"type":"object","required":["request"],"properties":{"request":{"type":"object",
      "required":["path"],"properties":{"path":{"type":"string"},"optional":{"type":"integer"}}}}}
      """;

  @Test
  void requiredNestedFieldsAreCorrectableWithoutEchoingSecretArguments() {
    assertThat(ToolSchemaArguments.supports(SCHEMA)).isTrue();
    for (String input :
        new String[] {
          "{}",
          "{\"request\":null}",
          "{\"request\":{\"path\":42}}",
          "{\"request\":{\"secret\":\"PRIVATE_SECRET\"}}"
        }) {
      assertThatThrownBy(() -> ToolSchemaArguments.validate("otherTool", SCHEMA, input))
          .isInstanceOf(InvalidToolArgumentsException.class)
          .hasMessageContaining("not executed")
          .hasMessageNotContaining("PRIVATE_SECRET");
    }
  }

  @Test
  void defaultsAndOpaqueContractsRemainWithOwningTools() {
    assertThatCode(
            () ->
                ToolSchemaArguments.validate(
                    "otherTool", SCHEMA, "{\"request\":{\"path\":\"a\",\"optional\":null}}"))
        .doesNotThrowAnyException();
    assertThat(ToolSchemaArguments.supports("{}")).isFalse();
    assertThat(ToolSchemaArguments.supports("broken")).isFalse();
    assertThatCode(() -> ToolSchemaArguments.validate("opaque", "{}", "legacy input"))
        .doesNotThrowAnyException();
  }

  @Test
  void objectArrayItemsAndLocalReferencesAreCheckedWithoutFetchingRemoteSchemas() {
    String schema =
        """
        {"type":"object","required":["entries"],"properties":{"entries":{"type":"array",
        "items":{"$ref":"#/$defs/entry"}}},"$defs":{"entry":{"type":"object",
        "required":["id"],"properties":{"id":{"type":"string"}}}}}
        """;
    assertThatThrownBy(
            () -> ToolSchemaArguments.validate("arrayTool", schema, "{\"entries\":[{}]}"))
        .isInstanceOf(InvalidToolArgumentsException.class)
        .hasMessageContaining("entries[0].id");
    assertThatCode(
            () ->
                ToolSchemaArguments.validate(
                    "arrayTool", schema, "{\"entries\":[{\"id\":\"known\"}]}"))
        .doesNotThrowAnyException();
  }
}
