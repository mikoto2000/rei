package dev.mikoto2000.rei.activity;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 作業状況出力の入力を解釈し、機能内で扱える情報へ整える。
 *
 * <p>Same offline JSON Schema validator stack as SubAgent output validation, with sanitized errors.
 */
public final class ActivityOutputParser {
  /** データの変換。 */
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  /** データ形式。 */
  public static final String SCHEMA = resource();

  /** 検証の窓口。 */
  private static final Schema VALIDATOR =
      SchemaRegistry.withDefaultDialect(
              SpecificationVersion.DRAFT_2020_12,
              b ->
                  b.schemaRegistryConfig(
                          SchemaRegistryConfig.builder()
                              .pathType(com.networknt.schema.path.PathType.JSON_POINTER)
                              .build())
                      .schemaLoader(l -> l.fetchRemoteResources(false).block(iri -> true)))
          .getSchema(MAPPER.readTree(SCHEMA));

  /**
   * 作業状況の把握で用いるデータ形式対象監視の窓口一覧を返す。
   *
   * <p>Constrain provider generation to the actual display IDs in this capture.
   */
  public static String schemaForMonitors(List<String> monitors) {
    if (monitors.isEmpty()) throw new IllegalArgumentException("Missing monitors");
    var schema = MAPPER.readTree(SCHEMA);
    var monitor =
        (tools.jackson.databind.node.ObjectNode)
            schema.at("/properties/activities/items/properties/monitor");
    var allowed = monitor.putArray("enum");
    monitors.forEach(allowed::add);
    return MAPPER.writeValueAsString(schema);
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public ActivityExtractor.Result parse(String text, List<String> monitors) {
    if (text == null || text.isBlank())
      throw new InvalidOutput(List.of(new ResultError("/", "empty_output")));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text.length() > 65536) throw new InvalidOutput(List.of(new ResultError("/", "size")));
    try {
      var node = MAPPER.readTree(text);
      var errors = VALIDATOR.validate(node);
      if (!errors.isEmpty())
        throw new InvalidOutput(
            errors.stream()
                .limit(20)
                .map(e -> new ResultError(e.getInstanceLocation().toString(), e.getKeyword()))
                .toList());
      var activities = new ArrayList<ActivityRecord.Activity>();
      for (var a : node.get("activities")) {
        if (!monitors.contains(a.get("monitor").asString()))
          throw new InvalidOutput(
              List.of(
                  new ResultError(
                      "/activities/" + activities.size() + "/monitor", "unknown_monitor")));
        activities.add(
            new ActivityRecord.Activity(
                a.get("monitor").asString(),
                a.get("type").asString(),
                a.get("application").asString(),
                a.get("service").asString(),
                a.get("contentTitle").asString(),
                a.get("projectCandidate").asString()));
      }
      return new ActivityExtractor.Result(
          new ActivityRecord.Inference(node.get("summary").asString(), activities),
          node.get("confidence").asDouble());
    } catch (InvalidOutput e) {
      throw e;
    } catch (Exception e) {
      throw new InvalidOutput(List.of(new ResultError("/", "invalid_json")));
    }
  }

  /** 作業状況の把握で用いる補助資料を返す。 */
  private static String resource() {
    try (var stream =
        ActivityOutputParser.class.getResourceAsStream("/activity/extraction.schema.json")) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Activity schema unavailable");
    }
  }

  /**
   * 作業状況の把握で受け渡す結果エラーを表す。
   *
   * @param path パス
   * @param code コード
   */
  public record ResultError(String path, String code) {}

  /** 作業状況の把握に必要な不正出力を扱う。 */
  public static final class InvalidOutput extends IllegalArgumentException {
    /** 安全性コード一覧。 */
    private static final Set<String> SAFE_CODES =
        Set.of(
            "size",
            "empty_output",
            "invalid_json",
            "unknown_monitor",
            "invalid_response",
            "empty_response",
            "multiple_results",
            "unexpected_tool_calls",
            "output_limit",
            "type",
            "enum",
            "required",
            "additionalProperties",
            "minimum",
            "maximum",
            "minLength",
            "maxLength",
            "maxItems");

    /** エラー一覧。 */
    private final List<ResultError> errors;

    /** 指定された値や利用先を使って、InvalidOutputを初期化する。 */
    public InvalidOutput(List<ResultError> errors) {
      super("Invalid activity structured output");
      this.errors = List.copyOf(errors);
    }

    /** 作業状況の把握で用いるエラー一覧を返す。 */
    public List<ResultError> errors() {
      return errors;
    }

    /**
     * 作業状況の把握で用いる診断を返す。
     *
     * <p>Log only schema-owned paths and known codes, never provider text or unknown property
     * names.
     */
    public String diagnostic() {
      return errors.stream()
          .limit(5)
          .map(
              e ->
                  safePath(e.path())
                      + ":"
                      + (e.code() != null && SAFE_CODES.contains(e.code())
                          ? e.code()
                          : "validation_error"))
          .distinct()
          .collect(java.util.stream.Collectors.joining(", "));
    }

    /** 作業状況の把握で用いる安全性パスを返す。 */
    private static String safePath(String path) {
      return path != null
              && path.matches(
                  "/(category|application|service|projectCandidate|contentCandidate|summary|confidence|activities(?:/[0-9]{1,4}(?:/(?:monitor|type|application|service|contentTitle|projectCandidate))?)?)")
          ? path
          : "/";
    }
  }
}
