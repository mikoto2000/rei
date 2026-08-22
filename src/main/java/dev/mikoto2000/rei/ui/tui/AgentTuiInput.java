package dev.mikoto2000.rei.ui.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jline.reader.Buffer;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;
import org.jline.reader.impl.BufferImpl;

/** TUI editor backed by JLine's buffer, history, parser and completer without JLine rendering. */
final class AgentTuiInput {
  private final Buffer buffer;
  private final LineReader reader;
  private final Completer completer;
  private List<String> completionCandidates = List.of();

  AgentTuiInput() {
    this.buffer = new BufferImpl();
    this.reader = null;
    this.completer = null;
  }

  AgentTuiInput(LineReader reader, Completer completer) {
    this.buffer = reader.getBuffer();
    this.reader = reader;
    this.completer = completer;
  }

  String text() { return buffer.toString(); }
  String textBeforeCursor() { return buffer.upToCursor(); }
  int cursor() { return buffer.cursor(); }
  List<String> completionCandidates() { return completionCandidates; }

  void insert(int codePoint) {
    buffer.write(codePoint);
    clearCandidates();
  }

  void backspace() { buffer.backspace(); clearCandidates(); }
  void delete() { buffer.delete(); clearCandidates(); }
  void left() { buffer.move(-1); clearCandidates(); }
  void right() { buffer.move(1); clearCandidates(); }
  void home() { buffer.cursor(0); clearCandidates(); }
  void end() { buffer.cursor(buffer.length()); clearCandidates(); }

  void previousHistory() {
    History history = history();
    if (history != null && history.previous()) replaceAll(history.current());
  }

  void nextHistory() {
    History history = history();
    if (history != null && history.next()) replaceAll(history.current());
  }

  void complete() {
    if (reader == null || completer == null) return;
    try {
      ParsedLine line = reader.getParser().parse(text(), cursor(), Parser.ParseContext.COMPLETE);
      List<Candidate> candidates = new ArrayList<>();
      completer.complete(reader, line, candidates);
      List<String> values = candidates.stream().map(Candidate::value).distinct().toList();
      if (values.size() == 1) replaceWord(line, values.get(0));
      completionCandidates = values;
    } catch (SyntaxError ignored) {
      completionCandidates = List.of();
    }
  }

  Optional<String> submit() {
    String value = text();
    if (value.isBlank()) return Optional.empty();
    History history = history();
    if (history != null) {
      history.add(value);
      try { history.save(); } catch (IOException ignored) { }
    }
    buffer.clear();
    clearCandidates();
    return Optional.of(value);
  }

  private History history() { return reader == null ? null : reader.getHistory(); }

  private void replaceWord(ParsedLine line, String replacement) {
    int start = cursor() - line.wordCursor();
    int end = start + line.word().length();
    String current = text();
    replaceAll(current.substring(0, start) + replacement + current.substring(end));
    buffer.cursor(start + replacement.length());
  }

  private void replaceAll(String value) {
    buffer.clear();
    buffer.write(value);
    clearCandidates();
  }

  private void clearCandidates() { completionCandidates = List.of(); }
}
