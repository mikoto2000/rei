package dev.mikoto2000.rei.dot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 利用者の状態表示で受け渡す状態表示コマンド要求を表す。
 *
 * <p>Pure shared grammar; never executes commands or accepts arbitrary routes.
 *
 * @param action 操作
 * @param requestId 要求の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param payload 通知内容
 */
public record DotCommandRequest(
    String action, String requestId, String projectId, Map<String, Object> payload) {
  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static DotCommandRequest parse(List<String> words) {
    if (words == null || words.isEmpty())
      return new DotCommandRequest("requests", null, null, Map.of());
    String action = words.getFirst();
    if (!Set.of("request", "requests", "status", "result", "cancel", "verify", "resume")
        .contains(action))
      throw new IllegalArgumentException(
          "Use /dot request|requests|status|result|cancel|verify|resume");
    var values = new LinkedHashMap<String, Object>();
    var arguments = new ArrayList<String>();
    var constraints = new ArrayList<String>();
    var criteria = new ArrayList<String>();
    String project = null;
    var seen = new HashSet<String>();
    for (int i = 1; i < words.size(); i++) {
      String word = words.get(i);
      if (!word.startsWith("--")) {
        arguments.add(word);
        continue;
      }
      if (word.equals("--yes")) {
        if (!seen.add(word)) throw new IllegalArgumentException("Repeated option");
        values.put("confirmed", true);
        continue;
      }
      if (!Set.of(
                  "--project-id",
                  "--category",
                  "--request-id",
                  "--context",
                  "--expected-output",
                  "--constraint",
                  "--acceptance-criterion",
                  "--timeout",
                  "--status",
                  "--note",
                  "--satisfied")
              .contains(word)
          || ++i >= words.size())
        throw new IllegalArgumentException("Unknown or incomplete Dot option");
      String value = words.get(i);
      if (!Set.of("--constraint", "--acceptance-criterion").contains(word) && !seen.add(word))
        throw new IllegalArgumentException("Repeated option");
      switch (word) {
        case "--project-id" -> project = value;
        case "--constraint" -> constraints.add(value);
        case "--acceptance-criterion" -> criteria.add(value);
        case "--timeout" -> {
          int timeout = Integer.parseInt(value);
          if (timeout < 1 || timeout > 86400)
            throw new IllegalArgumentException("Timeout is 1..86400 seconds");
          values.put("timeout", timeout);
        }
        default ->
            values.put(
                switch (word) {
                  case "--context" -> "context_summary";
                  case "--expected-output" -> "expected_output";
                  case "--request-id" -> "request_id";
                  default -> word.substring(2);
                },
                value);
      }
    }
    if (action.equals("request")) {
      if (arguments.isEmpty()
          || !Set.of("category", "context_summary", "expected_output", "timeout", "request_id")
              .containsAll(values.keySet()))
        throw new IllegalArgumentException("Request instruction and request options required");
      values.put("instruction", String.join(" ", arguments));
      values.put("constraints", constraints);
      values.put("acceptance_criteria", criteria);
    } else {
      if (!constraints.isEmpty() || !criteria.isEmpty())
        throw new IllegalArgumentException("Constraints are request-only");
      int count = action.equals("requests") ? 0 : 1;
      if (arguments.size() != count)
        throw new IllegalArgumentException("Exactly one request ID required");
      if (!action.equals("verify") && !action.equals("resume") && !values.isEmpty())
        throw new IllegalArgumentException("Unexpected Dot options");
      if (action.equals("resume") && !Set.of("confirmed").containsAll(values.keySet()))
        throw new IllegalArgumentException("Resume accepts --yes only");
      if (action.equals("verify")
          && !Set.of("confirmed", "status", "note", "satisfied").containsAll(values.keySet()))
        throw new IllegalArgumentException("Unexpected verification option");
    }
    return new DotCommandRequest(
        action,
        action.equals("request") || action.equals("requests") ? null : arguments.getFirst(),
        project,
        Collections.unmodifiableMap(values));
  }
}
