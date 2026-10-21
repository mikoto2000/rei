package dev.mikoto2000.rei.core.predicate;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 条件式の評価に必要なDeclarative判定条件を扱う。
 *
 * <p>Fixed, typed observations only. Missing data remains UNKNOWN through NOT and NE.
 */
public final class DeclarativePredicate {
  /** 条件式の評価における結果の選択肢。 */
  public enum Result {
    /** 条件を満たしたかどうか。 */
    SATISFIED,
    /** 条件を満たしていない結果。 */
    UNSATISFIED,
    /** 不明。 */
    UNKNOWN
  }

  /**
   * 条件式の評価で受け渡す要素を表す。
   *
   * @param op 操作の種別
   * @param pointer 参照位置
   * @param value 値
   * @param args 引数
   * @param pattern 照合規則
   */
  private record Node(
      String op, String pointer, JsonNode value, List<Node> args, Pattern pattern) {}

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(65536)
                          .maxNumberLength(64)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  /** 作業ルート。 */
  private final Node root;

  /** 正規。 */
  private final String canonical;

  /** 指定された値や利用先を使って、DeclarativePredicateを初期化する。 */
  private DeclarativePredicate(Node root, String canonical) {
    this.root = root;
    this.canonical = canonical;
  }

  /** 条件式の評価で用いる保存・受け渡しに使うJSON変換を返す。 */
  public String json() {
    return canonical;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static DeclarativePredicate parse(String text) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (text == null || text.length() > 4096) throw invalid();
      var document = JSON.readTree(text);
      if (document == null
          || !document.isObject()
          || document.size() != 2
          || !document.path("version").isIntegralNumber()
          || !document.path("version").canConvertToInt()
          || document.path("version").intValue() != 1
          || !document.has("expression")) throw invalid();
      var root = node(document.get("expression"), 1, new int[] {0});
      return new DeclarativePredicate(root, document.toString());
    } catch (java.io.IOException | RuntimeException error) {
      throw invalid();
    }
  }

  /** 条件式の評価で用いる要素を返す。 */
  private static Node node(JsonNode json, int depth, int[] count) {
    if (depth > 8
        || ++count[0] > 32
        || json == null
        || !json.isObject()
        || !json.path("op").isTextual()) throw invalid();
    String op = json.get("op").textValue();
    if (Set.of("AND", "OR", "NOT").contains(op)) {
      var args = json.path("args");
      if (json.size() != 2
          || !args.isArray()
          || args.isEmpty()
          || args.size() > 8
          || op.equals("NOT") && args.size() != 1) throw invalid();
      var children = new ArrayList<Node>();
      for (var child : args) children.add(node(child, depth + 1, count));
      return new Node(op, null, null, List.copyOf(children), null);
    }
    if (op.equals("EXISTS")) {
      if (json.size() != 2 || !json.path("pointer").isTextual()) throw invalid();
      String pointer = json.path("pointer").textValue();
      validatePointer(pointer);
      return new Node(op, pointer, null, List.of(), null);
    }
    if (!Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "MATCH").contains(op)
        || json.size() != 3
        || !json.path("pointer").isTextual()
        || !json.has("value")) throw invalid();
    String pointer = json.path("pointer").textValue();
    var value = json.get("value");
    validatePointer(pointer);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (!value.isValueNode() || value.toString().length() > 1024) throw invalid();
    if (Set.of("GT", "GTE", "LT", "LTE").contains(op) && !value.isNumber() && !value.isTextual())
      throw invalid();
    Pattern pattern = null;
    if (op.equals("MATCH")) {
      if (!value.isTextual()
          || value.textValue().length() > 256
          || value.textValue().contains("{")
          || value.textValue().contains("}")) throw invalid();
      pattern = Pattern.compile(value.textValue());
      if (pattern.programSize() > 512) throw invalid();
    }
    return new Node(op, pointer, value, List.of(), pattern);
  }

  /** 対象を判断基準に照らして評価する。 */
  public Result evaluate(byte[] body) {
    return evaluate(body, System.nanoTime() + 250_000_000L);
  }

  /** 対象を判断基準に照らして評価する。 */
  public Result evaluate(byte[] body, long deadline) {
    if (body == null || body.length > 65536 || expired(deadline)) return Result.UNKNOWN;
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      var document = JSON.readTree(text);
      if (document == null || expired(deadline)) return Result.UNKNOWN;
      var result = evaluate(root, document, deadline);
      return expired(deadline) ? Result.UNKNOWN : result;
    } catch (java.io.IOException | RuntimeException error) {
      return Result.UNKNOWN;
    }
  }

  /** 対象を判断基準に照らして評価する。 */
  private Result evaluate(Node node, JsonNode document, long deadline) {
    if (expired(deadline)) return Result.UNKNOWN;
    if (node.op().equals("NOT")) {
      return switch (evaluate(node.args().getFirst(), document, deadline)) {
        case SATISFIED -> Result.UNSATISFIED;
        case UNSATISFIED -> Result.SATISFIED;
        case UNKNOWN -> Result.UNKNOWN;
      };
    }
    if (Set.of("AND", "OR").contains(node.op())) {
      boolean unknown = false;
      for (var child : node.args()) {
        var observed = evaluate(child, document, deadline);
        unknown |= observed == Result.UNKNOWN;
        if (node.op().equals("AND") && observed == Result.UNSATISFIED) return Result.UNSATISFIED;
        if (node.op().equals("OR") && observed == Result.SATISFIED) return Result.SATISFIED;
      }
      return unknown
          ? Result.UNKNOWN
          : node.op().equals("AND") ? Result.SATISFIED : Result.UNSATISFIED;
    }
    var actual = document.at(node.pointer());
    if (node.op().equals("EXISTS")) return truth(!actual.isMissingNode());
    var expected = node.value();
    if (actual.isMissingNode() || !actual.isValueNode()) return Result.UNKNOWN;
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (actual.isTextual() && actual.textValue().length() > 4096) return Result.UNKNOWN;
    if (node.op().equals("MATCH"))
      return actual.isTextual()
          ? truth(node.pattern().matcher(actual.textValue()).matches())
          : Result.UNKNOWN;
    int order;
    if (actual.isNumber() && expected.isNumber())
      order = actual.decimalValue().compareTo(expected.decimalValue());
    else if (actual.isTextual() && expected.isTextual())
      order = actual.textValue().compareTo(expected.textValue());
    else if ((actual.isBoolean() && expected.isBoolean() || actual.isNull() && expected.isNull())
        && Set.of("EQ", "NE").contains(node.op())) order = actual.equals(expected) ? 0 : 1;
    else return Result.UNKNOWN;
    return truth(
        switch (node.op()) {
          case "EQ" -> order == 0;
          case "NE" -> order != 0;
          case "GT" -> order > 0;
          case "GTE" -> order >= 0;
          case "LT" -> order < 0;
          case "LTE" -> order <= 0;
          default -> false;
        });
  }

  /** 条件式の評価において、期限切れに該当するか判定する。 */
  private static boolean expired(long deadline) {
    return Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0;
  }

  /** 利用条件に合わない指定を受け付けないよう、参照位置を検証する。 */
  private static void validatePointer(String pointer) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (pointer.length() > 256
        || !pointer.startsWith("/")
        || pointer.chars().filter(c -> c == '/').count() > 16
        || pointer.codePoints().anyMatch(Character::isISOControl)
        || pointer.matches(".*~(?![01]).*")) throw invalid();
    JsonPointer.compile(pointer);
  }

  /** 条件式の評価で用いる確定情報を返す。 */
  private static Result truth(boolean value) {
    return value ? Result.SATISFIED : Result.UNSATISFIED;
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid bounded version 1 declarative predicate");
  }
}
