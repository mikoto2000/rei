package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class SubAgentResultValidatorTest {
  @TempDir Path directory;
  final SubAgentResultParser parser = new SubAgentResultParser();
  final SubAgentResultValidator validator = new SubAgentResultValidator();
  final SubAgentDefinitionLoader loader = new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true);
  static final String REVIEW_SCHEMA = """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "required":["findings"],"additionalProperties":false,"properties":{"findings":{
       "type":"array","items":{"type":"object","required":["severity","message"],
       "additionalProperties":false,"properties":{"severity":{"type":"string","enum":["HIGH","MEDIUM","LOW"]},
       "message":{"type":"string"}}}}}}
      """;
  SubAgentDefinition definition(String schema) throws Exception {
    String yaml = SubAgentConfigurationTest.yaml("reviewer");
    if (schema != null) {
      Files.writeString(directory.resolve("result.schema.json"), schema);
      yaml += "resultSchema: result.schema.json\n";
    }
    return loader.load(Files.writeString(directory.resolve("reviewer.yaml"), yaml));
  }
  ObjectNode envelope() { return (ObjectNode) parser.parse(SubAgentResultParserTest.VALID); }

  @ParameterizedTest @ValueSource(strings = {"SUCCESS", "FAILURE", "PARTIAL"})
  void acceptsEnvelopeWithoutSpecificSchema(String status) throws Exception {
    assertThat(validator.validate(definition(null), envelope().put("status", status)).valid()).isTrue();
  }
  @ParameterizedTest @ValueSource(strings = {"status", "summary", "result", "warnings"})
  void rejectsMissingFields(String field) throws Exception {
    var json = envelope(); json.remove(field);
    assertThat(validator.validate(definition(null), json).errors()).isNotEmpty();
  }
  @ParameterizedTest @ValueSource(strings = {"[]", "null", "1", "\"text\""})
  void rejectsNonObjects(String json) throws Exception {
    assertThat(validator.validate(definition(null), parser.parse(json)).valid()).isFalse();
  }
  @ParameterizedTest @ValueSource(strings = {
      "\"status\":\"DONE\"", "\"status\":1", "\"summary\":\"\"", "\"summary\":null",
      "\"summary\":false", "\"warnings\":\"oops\"", "\"warnings\":[1]", "\"warnings\":[null]",
      "\"result\":[]", "\"result\":null", "\"foo\":\"bar\""})
  void rejectsInvalidEnvelopeField(String field) throws Exception {
    var json = envelope(); json.setAll((ObjectNode) parser.parse("{" + field + "}"));
    var result = validator.validate(definition(null), json);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).allSatisfy(e -> { assertThat(e.path()).isNotNull(); assertThat(e.message()).isNotBlank(); });
  }
  @Test void validatesAgentSpecificShapeAndKeepsResultPathWithoutValues() throws Exception {
    var definition = definition(REVIEW_SCHEMA);
    var json = envelope();
    json.set("result", parser.parse("{\"findings\":[{\"severity\":\"HIGH\",\"message\":\"problem\"}]}"));
    assertThat(validator.validate(definition, json).valid()).isTrue();
    ((ObjectNode) json.at("/result/findings/0")).put("severity", "CRITICAL-private-secret");
    var errors = validator.validate(definition, json).errors();
    assertThat(errors).anyMatch(e -> e.path().equals("/result/findings/0/severity"));
    assertThat(errors.toString()).doesNotContain("private-secret");
  }
  @ParameterizedTest @ValueSource(strings = {"{}", "{\"findings\":[{\"severity\":\"HIGH\"}]}",
      "{\"findings\":[{\"severity\":\"HIGH\",\"message\":\"ok\",\"extra\":true}]}",
      "{\"findings\":[],\"unknown\":1}", "{\"findings\":[1]}"})
  void rejectsSpecificSchemaViolations(String result) throws Exception {
    var json = envelope(); json.set("result", parser.parse(result));
    assertThat(validator.validate(definition(REVIEW_SCHEMA), json).valid()).isFalse();
  }
  @Test void compiledSchemaSurvivesFileChangesAndFailedReloadKeepsSnapshot() throws Exception {
    definition(REVIEW_SCHEMA);
    var registry = new SubAgentRegistry(directory, loader);
    assertThat(registry.reload()).isEmpty();
    var original = registry.findById("reviewer").orElseThrow();
    Files.writeString(directory.resolve("result.schema.json"), "not json");
    assertThat(registry.reload()).isNotEmpty();
    assertThat(registry.findById("reviewer")).containsSame(original);
    assertThat(validator.validate(original, envelope()).valid()).isFalse();
  }
  @ParameterizedTest @ValueSource(strings = {"not json", "[]", "{\"type\":42}", "{\"required\":\"x\"}",
      "{\"type\":\"invented\"}", "{\"$schema\":\"https://example.com/schema\"}",
      "{\"$ref\":\"https://example.com/secret\"}", "{\"$ref\":\"file:///secret\"}",
      "{\"properties\":{\"x\":{\"$ref\":\"classpath:/secret.json\"}}}",
      "{\"properties\":{\"x\":{\"pattern\":\"[\"}}}",
      "{\"$ref\":\"#/$defs/missing\"}"})
  void rejectsBadSchemaAtDefinitionLoad(String schema) {
    assertThatThrownBy(() -> definition(schema)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resultSchema").hasMessageNotContaining("secret");
  }
  @ParameterizedTest @ValueSource(strings = {"missing.json", "../secret.json", "/secret.json", "C:/secret.json",
      "https://example.com/schema.json", "classpath:/other/secret.json", "classpath:/subagents/schemas/../secret.json"})
  void rejectsMissingOrUnsafeSchemaPaths(String path) throws Exception {
    var file = Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer") + "resultSchema: " + path + "\n");
    assertThatThrownBy(() -> loader.load(file)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resultSchema");
  }
  @Test void supportsClasspathSchemaAndLocalDefinitions() throws Exception {
    var file = Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer")
        + "resultSchema: classpath:/subagents/schemas/test-result.schema.json\n");
    var json = envelope(); json.set("result", parser.parse("{\"count\":2}"));
    assertThat(validator.validate(loader.load(file), json).valid()).isTrue();
    json.set("result", parser.parse("{\"count\":\"wrong\"}"));
    assertThat(validator.validate(loader.load(file), json).valid()).isFalse();
  }
  @Test void rejectsOversizedSchema() {
    assertThatThrownBy(() -> definition("{\"description\":\"" + "x".repeat(262144) + "\"}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resultSchema");
  }
  @Test void limitsValidationDiagnosticsAndDoesNotEchoInvalidValues() throws Exception {
    var json = envelope();
    json.set("warnings", parser.parse("[" + String.join(",", Collections.nCopies(200, "123")) + "]"));
    var errors = validator.validate(definition(null), json).errors();
    assertThat(errors).hasSize(100);
    assertThat(errors).allMatch(e -> e.message().equals("Schema constraint violated: type"));
  }
}
