package dev.mikoto2000.rei.activity;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 前面作業状況の入力を解釈し、機能内で扱える情報へ整える。
 *
 * <p>A single foreground inference; OS observations and monitor identity never come from the model.
 */
public final class ForegroundActivityParser {
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

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public ActivityExtractor.Result parse(String text, String monitor) {
    if (text == null || text.isBlank()) throw invalid("empty_output");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text.length() > 8192) throw invalid("size");
    try {
      var n = MAPPER.readTree(text);
      var errors = VALIDATOR.validate(n);
      if (!errors.isEmpty())
        throw new ActivityOutputParser.InvalidOutput(
            errors.stream()
                .limit(20)
                .map(
                    e ->
                        new ActivityOutputParser.ResultError(
                            e.getInstanceLocation().toString(), e.getKeyword()))
                .toList());
      var activity =
          new ActivityRecord.Activity(
              monitor,
              n.get("category").asString(),
              value(n, "application"),
              value(n, "service"),
              value(n, "contentCandidate"),
              value(n, "projectCandidate"));
      return new ActivityExtractor.Result(
          new ActivityRecord.Inference(n.get("summary").asString(), List.of(activity)),
          n.get("confidence").asDouble());
    } catch (ActivityOutputParser.InvalidOutput e) {
      throw e;
    } catch (Exception e) {
      throw invalid("invalid_json");
    }
  }

  /** 後続の処理で使う値を返す。 */
  private static String value(tools.jackson.databind.JsonNode n, String key) {
    return n.get(key).isNull() ? "" : n.get(key).asString();
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static ActivityOutputParser.InvalidOutput invalid(String code) {
    return new ActivityOutputParser.InvalidOutput(
        List.of(new ActivityOutputParser.ResultError("/", code)));
  }

  /** 作業状況の把握で用いる補助資料を返す。 */
  private static String resource() {
    try (var stream =
        ForegroundActivityParser.class.getResourceAsStream(
            "/activity/foreground-classification.schema.json")) {
      return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Foreground schema unavailable");
    }
  }
}
