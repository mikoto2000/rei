package dev.mikoto2000.rei.subagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 補助エージェントへの委譲に必要な計画レビュー比較結果を扱う。
 *
 * <p>Compares actual worker results, never caller-supplied verdicts or votes.
 */
public final class PlanReviewComparison {
  /**
   * 補助エージェントへの委譲で受け渡す比較結果を表す。
   *
   * @param truthVerified 確定情報確認済み
   * @param conclusions 結論一覧
   * @param commonFindings common指摘一覧
   * @param independentFindings independent指摘一覧
   * @param evidence 根拠
   * @param unresolved 未確定
   */
  public record Comparison(
      boolean truthVerified,
      List<String> conclusions,
      List<String> commonFindings,
      Map<String, List<String>> independentFindings,
      Map<String, List<String>> evidence,
      List<String> unresolved) {}

  /** 補助エージェントへの委譲で用いる比較を返す。 */
  public static Comparison compare(List<ParallelSubAgentDelegator.Item> items) {
    var conclusions = new LinkedHashSet<String>();
    var findings = new LinkedHashMap<String, List<String>>();
    var evidence = new LinkedHashMap<String, List<String>>();
    var unresolved = new ArrayList<String>();
    var counts = new HashMap<String, Integer>();
    for (var item : items) {
      var values = new LinkedHashSet<String>();
      var references = new ArrayList<String>();
      if (item.status() != ParallelSubAgentDelegator.Status.COMPLETED)
        unresolved.add(
            item.id() + ": " + (item.receipt() == null ? item.status() : item.receipt().state()));
      if (item.receipt() != null && !item.receipt().groups().isEmpty()) {
        unresolved.add(
            item.id()
                + ": groups reviewed separately; cross-group relationships require parent verification");
        for (var group : item.receipt().groups()) {
          var answer = group.result();
          if (answer.structuredOutput() == null) {
            unresolved.add(item.id() + " group " + group.number() + ": " + group.state());
            continue;
          }
          conclusions.add(answer.structuredOutput().summary());
          for (var finding : answer.structuredOutput().result().path("findings"))
            values.add(finding.toString());
          for (var value : answer.structuredOutput().result().path("unverified"))
            unresolved.add(item.id() + " group " + group.number() + ": " + value.asString());
          if (group.state() == ChatGptSubAgentBackend.State.COMPLETED) {
            var wrapper = new SubAgentResultParser().parse(answer.output());
            for (var reference : wrapper.path("references")) references.add(reference.toString());
          } else unresolved.add(item.id() + " group " + group.number() + ": " + group.state());
          if (group.semantic() != null
              && group.semantic().support() != PlanReviewSemanticValidator.Support.SUPPORTED)
            unresolved.add(
                item.id()
                    + " group "
                    + group.number()
                    + ": semantic support "
                    + group.semantic().support());
        }
      } else if (item.result() != null && item.result().structuredOutput() != null) {
        conclusions.add(item.result().structuredOutput().summary());
        var result = item.result().structuredOutput().result();
        for (var finding : result.path("findings")) values.add(finding.toString());
        for (var value : result.path("unverified"))
          unresolved.add(item.id() + ": " + value.asString());
        if (item.receipt() != null
            && item.receipt().state() == ChatGptSubAgentBackend.State.COMPLETED) {
          var wrapper = new SubAgentResultParser().parse(item.result().output());
          for (var reference : wrapper.path("references")) references.add(reference.toString());
          if (item.receipt().semantic() != null
              && item.receipt().semantic().support()
                  != PlanReviewSemanticValidator.Support.SUPPORTED)
            unresolved.add(item.id() + ": semantic support " + item.receipt().semantic().support());
        } else
          unresolved.add(item.id() + ": snapshot citation comparison unavailable for local result");
      } else unresolved.add(item.id() + ": no validated structured answer");
      findings.put(item.id(), List.copyOf(values));
      evidence.put(item.id(), List.copyOf(references));
      for (var value : values) counts.merge(value, 1, Integer::sum);
    }
    var common =
        counts.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    var independent = new LinkedHashMap<String, List<String>>();
    findings.forEach(
        (id, values) ->
            independent.put(id, values.stream().filter(v -> counts.get(v) == 1).toList()));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (conclusions.size() > 1)
      unresolved.add(
          "Different conclusions require independent parent assessment; no majority verdict");
    return new Comparison(
        false,
        List.copyOf(conclusions),
        common,
        Map.copyOf(independent),
        Map.copyOf(evidence),
        List.copyOf(unresolved));
  }
}
