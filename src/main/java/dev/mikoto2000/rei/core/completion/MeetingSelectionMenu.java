package dev.mikoto2000.rei.core.completion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;

/** Shared terminal presentation of the existing meeting selection contract. */
public final class MeetingSelectionMenu {
  private MeetingSelectionMenu() {}

  /** A selectable work item; the original ID is never derived from its display label. */
  public record Candidate(String id, String label) {}

  /** Offer selection only on the Shell terminal, preserving cancellation without saving. */
  public static Optional<List<String>> choose(
      LineReader reader, JsonNode report, java.io.PrintWriter output) {
    if (reader == null || !ApprovalDisplayState.interactive(reader.getTerminal()))
      return Optional.empty();
    var candidates = candidates(report);
    if (candidates.isEmpty()) return Optional.empty();
    output.flush();
    var choice = choose(reader, candidates);
    if (choice.isEmpty()) output.println("保存せずにミーティングを閉じました。");
    return choice;
  }

  /** Only current, unconfirmed meetings offer an interactive choice. */
  public static List<Candidate> candidates(JsonNode report) {
    var meeting = report.path("meeting");
    if (meeting.path("confirmed").asBoolean() || !meeting.path("question").isTextual())
      return List.of();
    var result = new LinkedHashMap<String, Candidate>();
    String section = report.path("review").asBoolean() ? "carryover" : "priorities";
    for (var work : meeting.path("sections").path(section)) {
      String project = work.path("projectId").asText();
      String id = work.path("id").asText();
      if (project.isBlank() || id.isBlank()) continue;
      String selection = project + "::" + id;
      result.putIfAbsent(
          selection,
          new Candidate(
              selection,
              "["
                  + project
                  + "] "
                  + work.path("title").asText()
                  + " ["
                  + work.path("state").asText()
                  + "]"
                  + (work.path("verified").asBoolean() ? "" : " 状態未検証")));
    }
    return List.copyOf(result.values());
  }

  /** Empty optional means cancel; an empty list means explicitly confirm no selection. */
  public static Optional<List<String>> choose(LineReader reader, List<Candidate> candidates) {
    return choose(reader, candidates, false);
  }

  /** Adapt primitive menu data supplied by the checkpoint command. */
  public static Optional<String> chooseOne(LineReader reader, List<String> ids, List<String> labels) {
    if (ids.size() != labels.size()) throw new IllegalArgumentException("Menu IDs and labels differ");
    var candidates = new ArrayList<Candidate>();
    for (int index = 0; index < ids.size(); index++)
      candidates.add(new Candidate(ids.get(index), labels.get(index)));
    return chooseOne(reader, candidates);
  }

  /** Enter selects the cursor's item immediately; cancellation never selects a default. */
  public static Optional<String> chooseOne(LineReader reader, List<Candidate> candidates) {
    return choose(reader, candidates, true).map(selection -> selection.getFirst());
  }

  private static Optional<List<String>> choose(
      LineReader reader, List<Candidate> candidates, boolean single) {
    if (!(reader instanceof LineReaderImpl input) || candidates.isEmpty()) return Optional.empty();
    var terminal = reader.getTerminal();
    var keys = new KeyMap<Binding>();
    keys.setNomatch(new Reference("ignore"));
    keys.setUnicode(new Reference("ignore"));
    keys.setAmbiguousTimeout(100);
    keys.bind(new Reference("up"), "\033[A", "\033OA");
    keys.bind(new Reference("down"), "\033[B", "\033OB");
    keys.bind(new Reference("toggle"), " ");
    keys.bind(new Reference("confirm"), "\r", "\n");
    keys.bind(new Reference("cancel"), "\033", KeyMap.ctrl('C'), KeyMap.ctrl('D'));
    var selected = new LinkedHashSet<String>();
    int cursor = 0;
    var display = new Display(terminal, false);
    var original = terminal.enterRawMode();
    try {
      while (true) {
        int columns = Math.max(1, terminal.getWidth());
        int rows = Math.max(3, terminal.getHeight());
        display.resize(rows, columns);
        var lines = new ArrayList<AttributedString>();
        lines.add(
            new AttributedString(
                single
                    ? "↑↓ 移動 / Enter 選択して再開 / Esc 取消"
                    : "↑↓ 移動 / Space 選択・解除 / Enter 保存 / Esc 取消 (" + selected.size() + "/3)"));
        // Keep the cursor visible even in a short terminal; truncate labels without ANSI execution.
        int count = Math.min(candidates.size(), Math.max(1, rows - 2));
        int first = Math.max(0, Math.min(cursor, candidates.size() - count));
        for (int index = first; index < first + count; index++) {
          var candidate = candidates.get(index);
          String label = candidate.label().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
          var line =
              new AttributedString(
                  (index == cursor ? "> " : "  ")
                      + (single ? "" : selected.contains(candidate.id()) ? "[x] " : "[ ] ")
                      + label);
          lines.add(line.columnSubSequence(0, Math.max(1, columns - 1)));
        }
        lines.set(0, lines.getFirst().columnSubSequence(0, Math.max(1, columns - 1)));
        display.update(lines, 0);
        terminal.flush();
        var binding = input.readBinding(keys);
        if (!(binding instanceof Reference action)) return Optional.empty();
        switch (action.name()) {
          case "up" -> cursor = Math.floorMod(cursor - 1, candidates.size());
          case "down" -> cursor = (cursor + 1) % candidates.size();
          case "toggle" -> {
            if (single) break;
            String id = candidates.get(cursor).id();
            if (!selected.remove(id)) {
              if (selected.size() < 3) selected.add(id);
              else terminal.puts(org.jline.utils.InfoCmp.Capability.bell);
            }
          }
          case "confirm" -> {
            return Optional.of(
                single ? List.of(candidates.get(cursor).id()) : List.copyOf(selected));
          }
          case "cancel" -> {
            return Optional.empty();
          }
          default -> {}
        }
      }
    } catch (EndOfFileException | UserInterruptException cancelled) {
      return Optional.empty();
    } finally {
      try {
        display.update(List.of(), 0);
        terminal.flush();
      } finally {
        terminal.setAttributes(original);
      }
    }
  }
}
