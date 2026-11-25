package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Test-only global quota shared by independent accepted Runs, including child processes. */
final class ResponsesAcceptanceBudget {
  record Limits(
      int calls,
      long input,
      long output,
      Duration time,
      long microUsd,
      long inputMicroUsd,
      long outputMicroUsd) {}

  record Slot(int id, long input, long output) {}

  record Snapshot(int calls, int completed, int unknown, long input, long output, long microUsd) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path journal;
  private final String binding;
  private final Limits limits;
  private final Clock clock;

  ResponsesAcceptanceBudget(Path journal, String binding, Limits limits, Clock clock) {
    if (binding == null
        || !binding.matches("[A-Za-z0-9_-]+")
        || limits.calls() < 1
        || limits.input() < 1
        || limits.output() < 1
        || limits.time().isNegative()
        || limits.time().isZero()
        || limits.microUsd() < 0
        || limits.inputMicroUsd() < 0
        || limits.outputMicroUsd() < 0) throw rejected();
    this.journal = journal;
    this.binding = binding + ":" + limits;
    this.limits = limits;
    this.clock = clock;
  }

  private static final class State {
    long started;
    int calls;
    boolean violated;
    final Map<Integer, Slot> slots = new HashMap<>();
    final Map<Integer, ModelTokenUsage> known = new HashMap<>();
    final java.util.Set<Integer> unknown = new java.util.HashSet<>();
  }

  synchronized Slot reserve(long input, long output) {
    if (input < 1 || output < 1 || input > limits.input() || output > limits.output())
      throw rejected();
    return transaction((channel, state) -> {
      long now = clock.millis();
      if (state.started == 0) {
        state.started = now;
        append(channel, Map.of("type", "H", "binding", binding, "started", now));
      }
      long elapsed = now - state.started;
      var snapshot = snapshot(state);
      if (state.violated
          || elapsed < 0
          || elapsed >= limits.time().toMillis()
          || state.calls >= limits.calls()
          || snapshot.input() > limits.input() - input
          || snapshot.output() > limits.output() - output
          || cost(snapshot.input() + input, snapshot.output() + output) > limits.microUsd())
        throw rejected();
      var slot = new Slot(state.calls + 1, input, output);
      append(channel, Map.of("type", "R", "id", slot.id(), "input", input, "output", output));
      return slot;
    });
  }

  synchronized void settle(Slot slot, ModelTokenUsage usage) {
    boolean violated =
        transaction((channel, state) -> {
          requirePending(state, slot);
          if (usage.input() == null || usage.output() == null || usage.total() == null)
            throw rejected();
          boolean over = usage.input() > slot.input() || usage.output() > slot.output();
          var entry = new java.util.LinkedHashMap<String, Object>();
          entry.put("type", over ? "V" : "C");
          entry.put("id", slot.id());
          entry.put("usage", usage);
          append(channel, entry);
          return over;
        });
    if (violated) throw rejected();
  }

  synchronized void unknown(Slot slot) {
    transaction((channel, state) -> {
      requirePending(state, slot);
      append(channel, Map.of("type", "F", "id", slot.id()));
      return null;
    });
  }

  synchronized Snapshot snapshot() {
    return transaction((channel, state) -> snapshot(state));
  }

  synchronized long remainingMillis() {
    return transaction((channel, state) -> {
      if (state.started == 0) return limits.time().toMillis();
      long elapsed = clock.millis() - state.started;
      if (elapsed < 0 || elapsed >= limits.time().toMillis() || state.violated) throw rejected();
      return limits.time().toMillis() - elapsed;
    });
  }

  private void requirePending(State state, Slot slot) {
    if (!slot.equals(state.slots.get(slot.id()))
        || state.known.containsKey(slot.id())
        || state.unknown.contains(slot.id())
        || state.violated) throw rejected();
  }

  private Snapshot snapshot(State state) {
    long input = 0;
    long output = 0;
    for (var slot : state.slots.values()) {
      var usage = state.known.get(slot.id());
      input = Math.addExact(input, usage == null ? slot.input() : usage.input());
      output = Math.addExact(output, usage == null ? slot.output() : usage.output());
    }
    return new Snapshot(
        state.calls, state.known.size(), state.unknown.size(), input, output, cost(input, output));
  }

  private long cost(long input, long output) {
    return Math.addExact(
        Math.multiplyExact(input, limits.inputMicroUsd()),
        Math.multiplyExact(output, limits.outputMicroUsd()));
  }

  private interface Operation<T> {
    T apply(FileChannel channel, State state) throws java.io.IOException;
  }

  private <T> T transaction(Operation<T> operation) {
    try (var channel =
            FileChannel.open(
                journal,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      if (!lock.isValid() || channel.size() > 4 * 1024 * 1024) throw rejected();
      var buffer = ByteBuffer.allocate((int) channel.size());
      while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw rejected();
      String raw = new String(buffer.array(), StandardCharsets.UTF_8);
      if (!raw.isEmpty() && !raw.endsWith("\n")) throw rejected();
      var state = new State();
      for (String line : raw.split("\n")) {
        if (line.isEmpty()) continue;
        var row = JSON.readTree(line);
        String type = row.path("type").asText();
        if (type.equals("H")) {
          if (state.started != 0 || !binding.equals(row.path("binding").asText())) throw rejected();
          state.started = row.path("started").asLong();
          if (state.started <= 0) throw rejected();
        } else if (type.equals("R")) {
          int id = row.path("id").asInt();
          long input = row.path("input").asLong();
          long output = row.path("output").asLong();
          if (state.started == 0
              || id != state.calls + 1
              || id > limits.calls()
              || input < 1
              || input > limits.input()
              || output < 1
              || output > limits.output()) throw rejected();
          state.slots.put(id, new Slot(id, input, output));
          state.calls = id;
        } else if (type.equals("C") || type.equals("V") || type.equals("F")) {
          int id = row.path("id").asInt();
          var slot = state.slots.get(id);
          if (slot == null) throw rejected();
          requirePending(state, slot);
          if (type.equals("F")) state.unknown.add(id);
          else {
            var usage = JSON.treeToValue(row.path("usage"), ModelTokenUsage.class);
            if (usage.input() == null || usage.output() == null || usage.total() == null)
              throw rejected();
            boolean over = usage.input() > slot.input() || usage.output() > slot.output();
            if (over != type.equals("V")) throw rejected();
            state.known.put(id, usage);
            state.violated = over;
          }
        } else throw rejected();
      }
      channel.position(channel.size());
      return operation.apply(channel, state);
    } catch (java.io.IOException
        | ArithmeticException
        | java.nio.channels.OverlappingFileLockException invalid) {
      throw rejected();
    }
  }

  private static void append(FileChannel channel, Map<String, ?> entry) throws java.io.IOException {
    var bytes =
        ByteBuffer.wrap((JSON.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
    while (bytes.hasRemaining()) channel.write(bytes);
    channel.force(true);
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Approved extended Responses budget rejected");
  }
}
