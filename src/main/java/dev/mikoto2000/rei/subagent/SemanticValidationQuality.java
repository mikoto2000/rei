package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 補助エージェントへの委譲に必要な意味による検証品質を扱う。
 *
 * <p>Explicit evaluation only. Fixture labels and judge agreement are not correctness proofs.
 */
public final class SemanticValidationQuality {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SemanticValidationQuality() {}

  /** 補助エージェントへの委譲における判断結果の選択肢。 */
  public enum Decision {
    /** 受付。 */
    ACCEPT,
    /** 拒否。 */
    REJECT,
    /** 判断を保留する選択。 */
    ABSTAIN
  }

  /**
   * 補助エージェントへの委譲で受け渡す観測内容を表す。
   *
   * @param tool ツール
   * @param arguments 引数
   * @param output 出力
   */
  public record Observation(String tool, String arguments, String output) {
    /** 受け取った情報からObservationを作成する。 */
    public Observation {
      bounded(tool, 100);
      bounded(arguments, 16384);
      bounded(output, 16384);
    }
  }

  /**
   * 補助エージェントへの委譲で受け渡す事例を表す。
   *
   * @param id 識別子
   * @param category 分類
   * @param task タスク
   * @param answer 回答
   * @param observations 観測内容一覧
   * @param expected 期待する内容
   */
  public record Case(
      String id,
      String category,
      String task,
      String answer,
      List<Observation> observations,
      Decision expected) {
    /** 受け取った情報からCaseを作成する。 */
    public Case {
      bounded(id, 128);
      bounded(category, 128);
      bounded(task, 8192);
      bounded(answer, 32768);
      observations = List.copyOf(observations);
      if (observations.size() > 16 || expected == null)
        throw new IllegalArgumentException("Bounded labelled evidence required");
    }
  }

  /** 補助エージェントへの委譲に必要な評価の判断を扱う。 */
  @FunctionalInterface
  public interface Judge {
    /** 補助エージェントへの委譲で用いる評価の判断を返す。 */
    Decision judge(Case fixture);
  }

  /**
   * 補助エージェントへの委譲で受け渡す行を表す。
   *
   * @param id 識別子
   * @param category 分類
   * @param expected 期待する内容
   * @param primary 主要な
   * @param secondary 補助的な
   */
  public record Row(
      String id, String category, Decision expected, Decision primary, Decision secondary) {}

  /**
   * 補助エージェントへの委譲で受け渡す計測値を表す。
   *
   * @param truePositive 有効正の
   * @param falsePositive 無効正の
   * @param falseNegative 無効負の
   * @param trueNegative 有効負の
   * @param abstentions 判断を保留した内容
   * @param uncertainDecisions 不確実な判断結果一覧
   * @param coverage 対象範囲
   */
  public record Metrics(
      int truePositive,
      int falsePositive,
      int falseNegative,
      int trueNegative,
      int abstentions,
      int uncertainDecisions,
      double coverage) {}

  /**
   * 補助エージェントへの委譲で受け渡すレポートを表す。
   *
   * @param primaryProvider 主要な提供元
   * @param secondaryProvider 補助的な提供元
   * @param rows 行一覧
   * @param primary 主要な
   * @param secondary 補助的な
   * @param agreement 合意の程度
   * @param truthVerified 確定情報確認済み
   */
  public record Report(
      String primaryProvider,
      String secondaryProvider,
      List<Row> rows,
      Metrics primary,
      Metrics secondary,
      Double agreement,
      boolean truthVerified) {}

  /** 対象を判断基準に照らして評価する。 */
  public static Report evaluate(
      List<Case> fixtures,
      String primaryProvider,
      Judge primary,
      String secondaryProvider,
      Judge secondary) {
    bounded(primaryProvider, 128);
    Objects.requireNonNull(primary);
    fixtures = List.copyOf(fixtures);
    if (fixtures.isEmpty()
        || fixtures.size() > 128
        || fixtures.stream().map(Case::id).distinct().count() != fixtures.size())
      throw new IllegalArgumentException("Unique bounded fixture set required");
    if ((secondaryProvider == null) != (secondary == null))
      throw new IllegalArgumentException("Secondary identity and judge required together");
    if (secondaryProvider != null) bounded(secondaryProvider, 128);
    long started = System.nanoTime();
    var rows = new ArrayList<Row>();
    int agreed = 0;
    for (var fixture : fixtures) {
      active(started);
      var a = Objects.requireNonNull(primary.judge(fixture));
      active(started);
      var b = secondary == null ? null : Objects.requireNonNull(secondary.judge(fixture));
      active(started);
      if (a == b) agreed++;
      rows.add(new Row(fixture.id(), fixture.category(), fixture.expected(), a, b));
    }
    var saved = List.copyOf(rows);
    return new Report(
        primaryProvider,
        secondaryProvider,
        saved,
        metrics(saved, false),
        secondary == null ? null : metrics(saved, true),
        secondary == null ? null : (double) agreed / rows.size(),
        false);
  }

  /** 補助エージェントへの委譲で用いる計測値を返す。 */
  private static Metrics metrics(List<Row> rows, boolean secondary) {
    int tp = 0, fp = 0, fn = 0, tn = 0, abstain = 0, uncertain = 0;
    for (var row : rows) {
      var actual = secondary ? row.secondary() : row.primary();
      if (actual == Decision.ABSTAIN) {
        abstain++;
        continue;
      }
      if (row.expected() == Decision.ABSTAIN) {
        uncertain++;
        continue;
      }
      if (actual == Decision.REJECT) {
        if (row.expected() == Decision.REJECT) tp++;
        else fp++;
      } else if (row.expected() == Decision.REJECT) fn++;
      else tn++;
    }
    return new Metrics(
        tp, fp, fn, tn, abstain, uncertain, (double) (rows.size() - abstain) / rows.size());
  }

  /**
   * 補助エージェントへの委譲で用いるモデルを返す。
   *
   * <p>Uses the production validator with one bounded, tool-free call and an explicit shared
   * reservation.
   */
  public static Judge model(
      ChatModel model,
      ToolCallingChatOptions options,
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      Runnable check) {
    Objects.requireNonNull(model);
    Objects.requireNonNull(options);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(check);
    return fixture -> {
      check.run();
      var evidence = new SubAgentEvidence();
      for (var observation : fixture.observations())
        evidence.capture(observation.tool(), observation.arguments(), observation.output());
      var definition =
          new SubAgentDefinition(
              "evaluation",
              "Evaluation",
              "Semantic evaluation",
              "Evaluate original task",
              List.of(),
              null,
              1,
              Duration.ofSeconds(10),
              owner.projectRoot());
      try {
        new SubAgentSemanticValidator()
            .validate(
                model,
                new Prompt(fixture.task(), options),
                definition,
                fixture.answer(),
                evidence,
                new AtomicInteger(1),
                owner,
                check,
                reservation)
            .block(Duration.ofSeconds(10));
        check.run();
        return Decision.ACCEPT;
      } catch (SubAgentValidationException invalid) {
        return invalid.errors().stream()
                .anyMatch(error -> error.message().startsWith("Semantic validation: "))
            ? Decision.REJECT
            : Decision.ABSTAIN;
      }
    };
  }

  /** 補助エージェントへの委譲に関する活動中を処理する。 */
  private static void active(long started) {
    RunCancellation.propagate(null);
    if (System.nanoTime() - started > Duration.ofSeconds(30).toNanos())
      throw new IllegalStateException("Evaluation deadline exceeded");
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static void bounded(String value, int limit) {
    if (value == null || value.isBlank() || value.length() > limit)
      throw new IllegalArgumentException("Bounded nonblank evaluation text required");
  }
}
