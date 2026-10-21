package dev.mikoto2000.rei.externalagent;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 外部エージェントへの委譲に必要な実装仕様検証の窓口を扱う。
 *
 * <p>Canonical, bounded requirements over existing real Project paths. No silent truncation.
 */
public final class ImplementationSpecificationValidator {
  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  /**
   * 外部エージェントへの委譲で受け渡す検証済みを表す。
   *
   * @param specification 仕様
   * @param canonical 正規
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param task タスク
   */
  public record Validated(
      ImplementationSpecification specification, String canonical, String sha256, String task) {}

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ImplementationSpecificationValidator() {}

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public static Validated validate(Path directory, ImplementationSpecification input) {
    if (input == null)
      throw new IllegalArgumentException(
          "objective, instructions, target, allowedPaths and acceptanceCriteria required");
    if (input.schemaVersion() != 1 || !"REPLACE_EXISTING_TEXT".equals(input.changeMode()))
      throw new IllegalArgumentException("Only schema 1 / REPLACE_EXISTING_TEXT is supported");
    String objective = text(input.objective(), 500, "objective");
    var instructions = texts(input.instructions(), 1, 16, 1000, "instructions");
    var constraints =
        texts(
            input.constraints() == null ? List.of() : input.constraints(),
            0,
            16,
            500,
            "constraints");
    String targetText = text(input.target(), 1024, "target");
    var paths = texts(input.allowedPaths(), 1, 32, 1024, "allowedPaths");
    if (input.acceptanceCriteria() == null
        || input.acceptanceCriteria().isEmpty()
        || input.acceptanceCriteria().size() > 16)
      throw new IllegalArgumentException("1 to 16 acceptanceCriteria required");
    var ids = new HashSet<String>();
    var criteria = new ArrayList<ImplementationSpecification.AcceptanceCriterion>();
    for (var criterion : input.acceptanceCriteria()) {
      if (criterion == null) throw new IllegalArgumentException("Missing acceptance criterion");
      String id = text(criterion.id(), 64, "criterion ID");
      if (!id.matches("[A-Za-z0-9_-]+") || !ids.add(id))
        throw new IllegalArgumentException("Unique criterion IDs required");
      criteria.add(
          new ImplementationSpecification.AcceptanceCriterion(
              id, text(criterion.description(), 500, "acceptance criterion")));
    }
    var references = new ArrayList<ImplementationSpecification.RequirementReference>();
    if (input.references() != null) {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (input.references().size() > 16)
        throw new IllegalArgumentException("At most 16 references");
      for (var reference : input.references()) {
        if (reference == null
            || !Set.of("USER_EXPLICIT", "USER_CONFIRMED", "CODE_FACT", "DESIGN_DOCUMENT")
                .contains(reference.sourceType()))
          throw new IllegalArgumentException(
              "Known reference source required; references never authorize execution");
        references.add(
            new ImplementationSpecification.RequirementReference(
                reference.sourceType(),
                text(reference.locator(), 1024, "reference locator"),
                text(reference.excerpt(), 500, "reference excerpt")));
      }
    }
    try {
      Path root = directory.toRealPath(),
          target = ExternalAgentRequest.resolveTarget(root, targetText);
      var normalized = new TreeSet<String>();
      for (String name : paths) {
        Path allowed = ExternalAgentRequest.resolveTarget(root, name);
        if (!allowed.startsWith(target) || !Files.isDirectory(target) && !allowed.equals(target))
          throw new IllegalArgumentException("allowedPaths must be inside target");
        if (ExternalAgentSourceSnapshot.excluded(root.relativize(allowed)))
          throw new IllegalArgumentException("Excluded allowed path");
        normalized.add(relative(root, allowed));
      }
      // Reuse all source limits, UTF-8 checks and link/junction escape checks before authorization.
      ExternalAgentSourceSnapshot.snapshot(
          root,
          target,
          () -> false,
          System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos());
      var specification =
          new ImplementationSpecification(
              1,
              objective,
              instructions,
              relative(root, target),
              List.copyOf(normalized),
              constraints,
              List.copyOf(criteria),
              List.copyOf(references),
              "REPLACE_EXISTING_TEXT");
      String task =
          "Approved implementation objective:\n"
              + objective
              + "\nInstructions:\n"
              + String.join("\n", instructions)
              + "\nConstraints:\n"
              + String.join("\n", constraints);
      if (task.length() > 4000)
        throw new IllegalArgumentException(
            "Required task exceeds existing 4000 character limit; clarify a smaller request");
      String canonical = JSON.writeValueAsString(specification);
      if (canonical.getBytes(StandardCharsets.UTF_8).length > 32768)
        throw new IllegalArgumentException("Specification exceeds 32 KiB");
      return new Validated(
          specification,
          canonical,
          ImplementationProposal.sha256(canonical.getBytes(StandardCharsets.UTF_8)),
          task);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException(
          "Target exceeds source limits or cannot be safely read", error);
    }
  }

  /** 外部エージェントへの委譲で用いる保存形式への変換を返す。 */
  static String serialize(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalArgumentException("Cannot serialize implementation request", error);
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  static String hash(Object value) {
    return ImplementationProposal.sha256(serialize(value).getBytes(StandardCharsets.UTF_8));
  }

  /** 外部エージェントへの委譲で用いる相対を返す。 */
  private static String relative(Path root, Path path) {
    String result = root.relativize(path).toString().replace('\\', '/');
    return result.isEmpty() ? "." : result;
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static String text(String value, int max, String label) {
    if (value == null || value.isBlank() || value.length() > max || value.indexOf(0) >= 0)
      throw new IllegalArgumentException(
          label + " is required and must be at most " + max + " characters");
    return value.strip();
  }

  /** 外部エージェントへの委譲で用いるtextsを返す。 */
  private static List<String> texts(
      List<String> values, int min, int max, int length, String label) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (values == null || values.size() < min || values.size() > max)
      throw new IllegalArgumentException(label + " requires " + min + " to " + max + " entries");
    return values.stream().map(value -> text(value, length, label)).toList();
  }
}
