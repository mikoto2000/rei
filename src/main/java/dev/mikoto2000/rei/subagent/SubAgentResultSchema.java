package dev.mikoto2000.rei.subagent;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/**
 * 補助エージェントへの委譲に必要な補助エージェント結果データ形式を扱う。
 *
 * <p>Definition-owned compiled schema. Reload replaces it; executions never read schema files.
 */
public final class SubAgentResultSchema {
  /** 条件式の表記方式。 */
  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  /** 最大データ形式バイト数。 */
  private static final int MAX_SCHEMA_BYTES = 262144;

  /** 付随情報登録台帳。 */
  private static final SchemaRegistry META_REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  /** 付随情報データ形式。 */
  private static final Schema META_SCHEMA = META_REGISTRY.getSchema(SchemaLocation.of(DIALECT));

  /** 登録台帳。 */
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder
                  .schemaCacheEnabled(false)
                  .schemaRegistryConfig(
                      SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
                  .schemaLoader(loader -> loader.fetchRemoteResources(false).block(iri -> true)));

  /** 解釈済みの条件。 */
  private final Schema compiled;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final String json;

  /** 補助エージェントへの委譲に必要なCommonを扱う。 */
  private static final class Common {
    /** 起動単位の識別子。 */
    private static final SubAgentResultSchema INSTANCE =
        load(Path.of("."), "classpath:/subagents/schemas/envelope.schema.json");
  }

  /** 補助エージェントへの委譲で用いる共通の通知属性を返す。 */
  static SubAgentResultSchema envelope() {
    return Common.INSTANCE;
  }

  /** 指定された値や利用先を使って、SubAgentResultSchemaを初期化する。 */
  private SubAgentResultSchema(JsonNode node) {
    if ((!node.isObject() && !node.isBoolean()) || !META_SCHEMA.validate(node).isEmpty())
      throw invalid();
    if (node.has("$schema") && !DIALECT.equals(node.get("$schema").asString())) throw invalid();
    this.compiled = REGISTRY.getSchema(node);
    compiled.initializeValidators();
    this.json = node.toString();
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  public static SubAgentResultSchema load(Path definition, String location) {
    try (InputStream stream = open(definition, location)) {
      if (stream == null) throw invalid();
      byte[] bytes = stream.readNBytes(MAX_SCHEMA_BYTES + 1);
      if (bytes.length > MAX_SCHEMA_BYTES) throw invalid();
      return new SubAgentResultSchema(
          new SubAgentResultParser().parse(new String(bytes, StandardCharsets.UTF_8)));
    } catch (Exception error) {
      // Library errors can contain arbitrary schema values or reference locations.
      throw invalid();
    }
  }

  /** 必要な対象を利用できるよう、補助エージェント結果データ形式の利用を開始する。 */
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

  /** 補助エージェントへの委譲において、安全性相対に該当するか判定する。 */
  private static boolean safeRelative(String path) {
    return path.matches("[A-Za-z0-9_-][A-Za-z0-9_./-]*\\.json")
        && java.util.Arrays.stream(path.split("/", -1))
            .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public java.util.List<com.networknt.schema.Error> validate(JsonNode node) {
    return compiled.validate(node);
  }

  /** 補助エージェントへの委譲で用いる保存・受け渡しに使うJSON変換を返す。 */
  public String json() {
    return json;
  }

  /**
   * 利用条件に合わない指定を受け付けないよう、条件一覧を検証する。
   *
   * <p>Conditions constrain a subset of root result fields; each specified value must satisfy its
   * schema.
   */
  public java.util.List<com.networknt.schema.Error> validateConditions(JsonNode conditions) {
    var schema = new SubAgentResultParser().parse(json).deepCopy();
    if (schema.isObject()) ((tools.jackson.databind.node.ObjectNode) schema).remove("required");
    return new SubAgentResultSchema(schema).validate(conditions);
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "resultSchema: missing, unsafe, oversized or invalid Draft 2020-12 schema (external references are disabled)");
  }
}
