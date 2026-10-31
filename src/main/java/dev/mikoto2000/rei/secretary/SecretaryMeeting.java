package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared read-only meeting projection. IDs and authoritative state win over narrative recency. */
public final class SecretaryMeeting {
  private SecretaryMeeting() {}

  public record Work(
      String id,
      String projectId,
      String title,
      String state,
      Instant updated,
      LocalDate due,
      int priority,
      String nextAction,
      boolean verified,
      SecretaryContext.Reliability reliability,
      List<SecretaryContext.Item> evidence) {
    public Work {
      evidence = List.copyOf(evidence);
    }
  }

  public record View(
      Map<String, List<Work>> sections,
      String scheduleStatus,
      String question,
      boolean confirmed,
      String text) {
    public View {
      var copy = new LinkedHashMap<String, List<Work>>();
      sections.forEach((k, v) -> copy.put(k, List.copyOf(v)));
      sections = Collections.unmodifiableMap(copy);
    }
  }

  public record Decision(
      String id,
      String projectId,
      LocalDate date,
      boolean review,
      String key,
      Instant saved,
      List<String> selected,
      List<String> projects) {
    public Decision(
        String id,
        String projectId,
        LocalDate date,
        boolean review,
        String key,
        Instant saved,
        List<String> selected) {
      this(id, projectId, date, review, key, saved, selected, List.of(projectId));
    }

    public Decision {
      selected = List.copyOf(selected);
      projects = projects == null ? List.of(projectId) : List.copyOf(projects);
    }
  }

  static Comparator<Work> order() {
    return Comparator.comparing(Work::due, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparingInt(Work::priority)
        .thenComparing(Work::updated, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(Work::projectId)
        .thenComparing(Work::id);
  }

  static boolean notice(Work w) {
    return w.evidence().stream()
        .anyMatch(i -> i.observation() != null && "ATTENTION".equals(i.observation().kind()));
  }

  static boolean scheduled(Work w) {
    return w.evidence().stream()
        .anyMatch(
            i ->
                "scheduler".equals(i.source())
                    || i.observation() != null
                        && Set.of("SCHEDULE", "CALENDAR").contains(i.observation().kind()));
  }

  static boolean selectable(Work w) {
    return ongoing(w) && !scheduled(w);
  }

  static boolean ongoing(Work w) {
    return !Set.of("COMPLETED", "CANCELLED").contains(w.state());
  }

  static boolean on(Work w, LocalDate date, ZoneId zone) {
    return w.updated() != null && w.updated().atZone(zone).toLocalDate().equals(date);
  }

  static boolean actionable(SecretaryContext.Item item) {
    String kind = item.observation() == null ? item.status() : item.observation().kind();
    return Set.of(
            "ATTENTION",
            "CURRENT_WORK",
            "PENDING",
            "BLOCKER",
            "NEXT_ACTION",
            "COMPLETED_WORK",
            "COMPLETED",
            "GOAL",
            "TASK",
            "SCHEDULE",
            "CALENDAR")
        .contains(kind);
  }

  public static View project(
      String project,
      LocalDate date,
      ZoneId zone,
      boolean review,
      Map<String, List<SecretaryTask>> sections,
      List<SecretaryContext.Item> context,
      boolean partial,
      List<String> carried,
      List<String> confirmed) {
    var work = new LinkedHashMap<String, Work>();
    // The ledger is current authoritative intention state, independent of tool executions.
    sections.values().stream()
        .flatMap(List::stream)
        .forEach(
            t -> {
              String state =
                  switch (t.status()) {
                    case DONE -> "COMPLETED";
                    case CANCELLED -> "CANCELLED";
                    case WAITING -> "WAITING";
                    case OPEN -> "PENDING";
                    default -> "UNKNOWN";
                  };
              work.put(
                  "secretary-task:" + t.id(),
                  new Work(
                      "secretary-task:" + t.id(),
                      project,
                      t.title(),
                      state,
                      t.updated(),
                      t.due(),
                      t.priority(),
                      null,
                      t.status() != SecretaryTask.Status.CANDIDATE,
                      t.status() == SecretaryTask.Status.CANDIDATE
                          ? t.origin().equals("EXTERNAL")
                              ? SecretaryContext.Reliability.UNKNOWN
                              : SecretaryContext.Reliability.INFERENCE
                          : SecretaryContext.Reliability.USER_REGISTERED,
                      List.of()));
            });
    var aliases = new HashMap<String, String>();
    var ambiguous = new HashSet<String>();
    for (var item : context)
      if (item.observation() != null && item.observation().verified()) {
        for (var ref : item.references())
          if (ref.startsWith("run:")) {
            var previous = aliases.putIfAbsent(ref, item.observation().workId());
            if (previous != null && !previous.equals(item.observation().workId()))
              ambiguous.add(ref);
          }
      }
    ambiguous.forEach(aliases::remove);
    var grouped = new LinkedHashMap<String, List<SecretaryContext.Item>>();
    for (var item : context) {
      var o = item.observation();
      if (o != null
          && o.updated() != null
          && !o.verified()
          && !o.updated().isBefore(date.plusDays(1).atStartOfDay(zone).toInstant())) continue;
      String id = o != null && o.workId() != null ? o.workId() : item.source() + ":" + item.id();
      id = aliases.getOrDefault(id, id);
      grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
    }
    for (var group : grouped.entrySet()) {
      var items = group.getValue();
      if (items.stream().noneMatch(SecretaryMeeting::actionable)) continue;
      var canonical = work.get(group.getKey());
      // Only providers reading current canonical stores may assert verified state.
      var current =
          items.stream()
              .filter(i -> i.observation() != null && i.observation().verified())
              .max(
                  Comparator.comparing(
                      i -> i.observation().updated(),
                      Comparator.nullsFirst(Comparator.naturalOrder())))
              .orElse(null);
      var latest =
          items.stream()
              .filter(SecretaryMeeting::actionable)
              .max(
                  Comparator.comparing(
                      i -> i.observation() == null ? null : i.observation().updated(),
                      Comparator.nullsFirst(Comparator.naturalOrder())))
              .orElseThrow();
      var source = current == null ? latest : current;
      var o = source.observation();
      String state = current == null ? "UNKNOWN" : o.state();
      if (!Set.of(
              "COMPLETED", "IN_PROGRESS", "PENDING", "WAITING", "BLOCKED", "UNKNOWN", "CANCELLED")
          .contains(state)) state = "UNKNOWN";
      String next =
          items.stream()
              .filter(i -> i.observation() != null && i.observation().nextAction() != null)
              .max(
                  Comparator.comparing(
                      i -> i.observation().updated(),
                      Comparator.nullsFirst(Comparator.naturalOrder())))
              .map(i -> i.observation().nextAction())
              .orElse(null);
      if (canonical != null) {
        var override =
            items.stream()
                .filter(i -> i.source().equals("secretary-current-state"))
                .findFirst()
                .orElse(null);
        work.put(
            group.getKey(),
            new Work(
                canonical.id(),
                project,
                canonical.title(),
                override == null ? canonical.state() : override.observation().state(),
                override == null ? canonical.updated() : override.observation().updated(),
                canonical.due(),
                canonical.priority(),
                next,
                override == null ? canonical.verified() : override.observation().verified(),
                canonical.reliability(),
                items));
      } else
        work.put(
            group.getKey(),
            new Work(
                group.getKey(),
                project,
                source.title(),
                state,
                o == null ? null : o.updated(),
                o == null ? null : o.due(),
                3,
                next,
                current != null,
                source.reliability(),
                items));
    }
    var all = work.values().stream().sorted(order()).toList();
    var active =
        all.stream()
            .filter(SecretaryMeeting::ongoing)
            .filter(w -> !scheduled(w))
            .filter(
                w ->
                    w.evidence().isEmpty()
                        || w.evidence().stream()
                            .anyMatch(
                                i -> {
                                  String k =
                                      i.observation() == null ? i.status() : i.observation().kind();
                                  return !Set.of("COMPLETED", "COMPLETED_WORK").contains(k)
                                      && actionable(i);
                                })
                        || w.verified())
            .toList();
    var result = new LinkedHashMap<String, List<Work>>();
    result.put(
        "schedule",
        all.stream()
            .filter(SecretaryMeeting::scheduled)
            .filter(SecretaryMeeting::ongoing)
            .filter(
                w ->
                    w.evidence().stream()
                        .noneMatch(
                            i ->
                                Set.of("CANCELLED", "CANCELED")
                                    .contains(
                                        String.valueOf(i.status())
                                            .toUpperCase(java.util.Locale.ROOT))))
            .limit(3)
            .toList());
    result.put("today", active.stream().filter(w -> date.equals(w.due())).limit(3).toList());
    result.put("handoff", active.stream().filter(w -> carried.contains(w.id())).limit(3).toList());
    result.put(
        "completed",
        all.stream()
            .filter(w -> w.verified() && w.state().equals("COMPLETED") && on(w, date, zone))
            .limit(3)
            .toList());
    result.put(
        "progress",
        all.stream()
            .filter(w -> !scheduled(w))
            .filter(w -> !notice(w))
            .filter(w -> on(w, date, zone) && !Set.of("COMPLETED", "CANCELLED").contains(w.state()))
            .limit(3)
            .toList());
    result.put("unfinished", active.stream().filter(w -> !notice(w)).limit(3).toList());
    result.put(
        "waiting",
        active.stream()
            .filter(w -> Set.of("WAITING", "BLOCKED", "UNKNOWN").contains(w.state()))
            .limit(3)
            .toList());
    var ranked =
        active.stream()
            .filter(w -> !notice(w))
            .sorted(
                Comparator.<Work>comparingInt(
                        w ->
                            w.due() != null && !w.due().isAfter(date)
                                ? 0
                                : carried.contains(w.id()) ? 1 : 2)
                    .thenComparing(order()))
            .toList();
    result.put("priorities", ranked.stream().limit(3).toList());
    result.put("carryover", ranked.stream().limit(3).toList());
    result.put(
        "decided", active.stream().filter(w -> confirmed.contains(w.id())).limit(3).toList());
    boolean scheduleKnown =
        context.stream()
            .anyMatch(
                i -> i.source().equals("calendar-coverage") && i.status().equals("AVAILABLE"));
    return view(
        date,
        zone.getId(),
        review,
        result,
        partial ? "PARTIAL" : scheduleKnown ? "AVAILABLE" : "UNKNOWN",
        !confirmed.isEmpty());
  }

  public static View aggregate(
      LocalDate date, String zone, boolean review, List<View> views, boolean partial) {
    var sections = new LinkedHashMap<String, List<Work>>();
    for (var view : views)
      view.sections()
          .forEach((k, v) -> sections.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
    var handoffs = sections.getOrDefault("handoff", List.of());
    sections.replaceAll(
        (k, v) ->
            v.stream()
                .distinct()
                .sorted(
                    Set.of("priorities", "carryover").contains(k)
                        ? Comparator.<Work>comparingInt(
                                w ->
                                    w.due() != null && !w.due().isAfter(date)
                                        ? 0
                                        : handoffs.contains(w) ? 1 : 2)
                            .thenComparing(order())
                        : order())
                .limit(3)
                .toList());
    String schedule =
        partial
            ? "PARTIAL"
            : !views.isEmpty()
                    && views.stream().allMatch(v -> v.scheduleStatus().equals("AVAILABLE"))
                ? "AVAILABLE"
                : "UNKNOWN";
    return view(
        date,
        zone,
        review,
        sections,
        schedule,
        !views.isEmpty() && views.stream().allMatch(View::confirmed));
  }

  static View view(
      LocalDate date,
      String zone,
      boolean review,
      Map<String, List<Work>> sections,
      String schedule,
      boolean confirmed) {
    var keys =
        review
            ? List.of("completed", "progress", "unfinished", "waiting", "carryover")
            : List.of("schedule", "today", "handoff", "priorities", "waiting");
    var labels =
        Map.of(
            "completed",
            "今日完了した作業",
            "progress",
            "今日記録された進展・更新",
            "unfinished",
            "未完了・中断",
            "waiting",
            "待機・状態確認",
            "carryover",
            "明日への引き継ぎ候補",
            "schedule",
            "今日の予定",
            "today",
            "今日の期限",
            "handoff",
            "昨日からの引き継ぎ",
            "priorities",
            "今日の優先候補");
    var text = new StringBuilder((review ? "夕" : "朝") + "ミーティング " + date + " (" + zone + ")\n");
    text.append(
        schedule.equals("AVAILABLE")
            ? sections.getOrDefault("schedule", List.of()).isEmpty()
                ? "登録済みの私用予定はありません。\n"
                : "登録済みの私用予定を確認しました。\n"
            : "予定情報は未取得または一部不明です。\n");
    var seen = new HashSet<String>();
    for (String key : keys) {
      var items =
          sections.getOrDefault(key, List.of()).stream()
              .filter(w -> seen.add(w.projectId() + "\0" + w.id()))
              .toList();
      if (items.isEmpty()) continue;
      text.append(labels.get(key)).append(":\n");
      for (var w : items) {
        text.append("- [")
            .append(w.projectId())
            .append("] ")
            .append(shortText(w.title()))
            .append(" [")
            .append(w.state())
            .append("]");
        if (!w.verified()) text.append(" 状態未検証/").append(w.reliability());
        if (sections.getOrDefault("handoff", List.of()).contains(w)) text.append(" [前日引き継ぎ]");
        if (sections.getOrDefault(review ? "carryover" : "priorities", List.of()).contains(w))
          text.append(review ? " [引き継ぎ候補]" : " [優先候補]");
        if (w.nextAction() != null) text.append(" → ").append(shortText(w.nextAction()));
        text.append(selectable(w) ? " {選択ID: " : " {参照ID: ")
            .append(shortText(w.projectId() + "::" + w.id()))
            .append("}\n");
      }
    }
    if (review
        && sections.getOrDefault("completed", List.of()).isEmpty()
        && sections.getOrDefault("progress", List.of()).isEmpty())
      text.append("今日、確認できた進展はありません。\n");
    if (confirmed) text.append("方針は保存済みです。ミーティングを終了します。\n");
    String question =
        !confirmed
                && !sections.getOrDefault(review ? "carryover" : "priorities", List.of()).isEmpty()
            ? review
                ? "明日へ引き継ぐ作業を選択して確定してください（最大3件・選択なしでも終了できます）。"
                : "今日優先する作業を選択して確定してください（最大3件・選択なしでも終了できます）。"
            : null;
    if (question != null) text.append(question).append("\n");
    else if (!confirmed) text.append("確認事項はありません。ミーティングを終了します。\n");
    return new View(sections, schedule, question, confirmed, text.toString());
  }

  static String shortText(String text) {
    String safe = text.replaceAll("[\\p{Cntrl}]", " ");
    return safe.length() > 180 ? safe.substring(0, 180) + "…" : safe;
  }
}
