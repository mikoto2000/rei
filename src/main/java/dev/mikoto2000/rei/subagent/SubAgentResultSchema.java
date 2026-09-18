package dev.mikoto2000.rei.subagent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import com.networknt.schema.*;
import com.networknt.schema.path.PathType;
import tools.jackson.databind.JsonNode;

/** Definition-owned compiled schema. Reload replaces it; executions never read schema files. */
public final class SubAgentResultSchema {
  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
  private static final int MAX_SCHEMA_BYTES = 262144;
  private static final SchemaRegistry META_REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final Schema META_SCHEMA = META_REGISTRY.getSchema(SchemaLocation.of(DIALECT));
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
      builder -> builder.schemaCacheEnabled(false)
          .schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
          .schemaLoader(loader -> loader.fetchRemoteResources(false).block(iri -> true)));
  private final Schema compiled;
  private final String json;
  private static final class Common {
    private static final SubAgentResultSchema INSTANCE = load(Path.of("."), "classpath:/subagents/schemas/envelope.schema.json");
  }
  static SubAgentResultSchema envelope() { return Common.INSTANCE; }

  private SubAgentResultSchema(JsonNode node) {
    if ((!node.isObject() && !node.isBoolean()) || !META_SCHEMA.validate(node).isEmpty()) throw invalid();
    if (node.has("$schema") && !DIALECT.equals(node.get("$schema").asString())) throw invalid();
    this.compiled = REGISTRY.getSchema(node);
    compiled.initializeValidators();
    this.json = node.toString();
  }

  public static SubAgentResultSchema load(Path definition, String location) {
    try (InputStream stream = open(definition, location)) {
      if (stream == null) throw invalid();
      byte[] bytes = stream.readNBytes(MAX_SCHEMA_BYTES + 1);
      if (bytes.length > MAX_SCHEMA_BYTES) throw invalid();
      return new SubAgentResultSchema(new SubAgentResultParser().parse(new String(bytes, StandardCharsets.UTF_8)));
    } catch (Exception error) {
      // Library errors can contain arbitrary schema values or reference locations.
      throw invalid();
    }
  }
  private static InputStream open(Path definition, String location) throws IOException {
    if (location.startsWith("classpath:/subagents/schemas/")) {
      String resource = location.substring("classpath:".length());
      if (!safeRelative(resource.substring(1))) throw invalid();
      return SubAgentResultSchema.class.getResourceAsStream(resource);
    }
    if (!safeRelative(location)) throw invalid();
    Path root = definition.toAbsolutePath().normalize().getParent().toRealPath();
    Path target = root.resolve(location).toRealPath();
    if (!target.startsWith(root) || !Files.isRegularFile(target)) throw invalid();
    return Files.newInputStream(target);
  }
  private static boolean safeRelative(String path) {
    return path.matches("[A-Za-z0-9_-][A-Za-z0-9_./-]*\\.json")
        && java.util.Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
  }
  java.util.List<com.networknt.schema.Error> validate(JsonNode node) { return compiled.validate(node); }
  public String json() { return json; }
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("resultSchema: missing, unsafe, oversized or invalid Draft 2020-12 schema (external references are disabled)");
  }
}
