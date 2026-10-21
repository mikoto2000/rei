package dev.mikoto2000.rei.subagent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 補助エージェントへの委譲に必要な補助エージェント根拠を扱う。
 *
 * <p>Per-run observations captured by the runner, never accepted from model claims.
 */
final class SubAgentEvidence {
  /**
   * 補助エージェントへの委譲で受け渡す観測内容を表す。
   *
   * @param tool ツール
   * @param hash ハッシュ値
   * @param output 出力
   * @param arguments 引数
   * @param truncated 省略の有無
   */
  private record Observation(
      String tool, String hash, String output, JsonNode arguments, boolean truncated) {}

  /** 観測内容一覧。 */
  private final Map<String, Observation> observations = new LinkedHashMap<>();

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper mapper = JsonMapper.builder().build();

  /** 補助エージェントへの委譲で用いる意味による状態の記録を返す。 */
  synchronized String semanticSnapshot() {
    String snapshot = mapper.writeValueAsString(observations);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (snapshot.length() > 65536)
      throw new SubAgentValidationException(
          List.of(
              new ValidationError(
                  "/result/evidence", "Semantic evidence exceeds bounded input limit")));
    return snapshot;
  }

  /** 後から状況を確認できるよう、補助エージェント根拠を記録する。 */
  synchronized String capture(String tool, String input, String output) {
    if (observations.size() >= 64 || output == null)
      throw new IllegalStateException("Evidence capture limit or invalid tool output");
    String id = UUID.randomUUID().toString();
    String retained = output.substring(0, Math.min(16384, output.length()));
    String hash = hash(output);
    JsonNode arguments = null;
    if (input != null && input.length() <= 16384)
      try {
        arguments = new SubAgentResultParser().parse(input);
      } catch (RuntimeException invalid) {
        /* Unparseable input cannot satisfy an exact call contract. */
      }
    observations.put(
        id, new Observation(tool, hash, retained, arguments, retained.length() != output.length()));
    return mapper.writeValueAsString(
        Map.of(
            "evidenceId",
            id,
            "tool",
            tool,
            "inputSha256",
            hash(input),
            "outputSha256",
            hash,
            "output",
            retained,
            "truncated",
            retained.length() != output.length()));
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  synchronized ValidationResult validate(List<String> requiredTools, JsonNode json) {
    return validate(requiredTools, List.of(), json);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  synchronized ValidationResult validate(
      List<String> requiredTools, List<SubAgentRequiredCall> requiredCalls, JsonNode json) {
    var errors = new ArrayList<ValidationError>();
    JsonNode evidence = json.path("result").path("evidence");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (!evidence.isArray() || evidence.size() > 64) {
      return ValidationResult.of(
          List.of(new ValidationError("/result/evidence", "Expected bounded evidence array")));
    }
    var seen = new HashSet<String>();
    var supported = new HashSet<String>();
    var cited = new ArrayList<Observation>();
    for (int i = 0; i < evidence.size(); i++) {
      JsonNode claim = evidence.get(i);
      String path = "/result/evidence/" + i;
      if (!claim.isObject()
          || claim.size() != 4
          || !text(claim, "evidenceId", 64)
          || !text(claim, "tool", 100)
          || !text(claim, "outputSha256", 64)
          || !text(claim, "quote", 2048)) {
        errors.add(
            new ValidationError(
                path, "Expected evidenceId, tool, outputSha256 and bounded nonblank quote"));
        continue;
      }
      String id = claim.get("evidenceId").asString();
      Observation actual = observations.get(id);
      if (!seen.add(id)
          || actual == null
          || !actual.tool().equals(claim.get("tool").asString())
          || !actual.hash().equals(claim.get("outputSha256").asString())
          || !actual.output().contains(claim.get("quote").asString())) {
        errors.add(
            new ValidationError(
                path, "Evidence does not match a unique observation from this run"));
        continue;
      }
      supported.add(actual.tool());
      cited.add(actual);
    }
    if ("SUCCESS".equals(json.path("status").asString()))
      for (int index = 0; index < requiredCalls.size(); index++) {
        var required = requiredCalls.get(index);
        if (cited.stream()
            .noneMatch(
                actual ->
                    required.matches(actual.tool(), actual.arguments())
                        && required.matchesOutput(actual.output(), actual.truncated())))
          errors.add(
              new ValidationError(
                  "/status",
                  "SUCCESS requires cited observation for requiredToolCalls[" + index + "]"));
      }
    if ("SUCCESS".equals(json.path("status").asString()) && !supported.containsAll(requiredTools)) {
      errors.add(
          new ValidationError(
              "/status", "SUCCESS requires cited observations for all required evidence tools"));
    }
    return ValidationResult.of(errors);
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static boolean text(JsonNode node, String key, int max) {
    var value = node.get(key);
    return value != null
        && value.isString()
        && !value.asString().isBlank()
        && value.asString().length() <= max;
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
