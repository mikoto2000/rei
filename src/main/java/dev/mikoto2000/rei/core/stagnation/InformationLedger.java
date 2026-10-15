package dev.mikoto2000.rei.core.stagnation;

import java.util.*;

/** Bounded exact evidence. Retained identities are never evicted or promoted twice. */
public final class InformationLedger {
  static final int LIMIT = 1024;

  public enum Status {
    NEW,
    DUPLICATE,
    UNKNOWN
  }

  public record Observation(Status status, long newLines) {}

  public record LineRange(int startInclusive, int endExclusive) {
    public LineRange {
      if (startInclusive < 1 || endExclusive <= startInclusive)
        throw new IllegalArgumentException("Invalid range");
    }
  }

  public record FileReadProgress(String normalizedPath, String revision, List<LineRange> ranges) {
    public FileReadProgress {
      ranges = List.copyOf(ranges);
    }
  }

  public record State(
      List<FileReadProgress> files, Set<String> identities, boolean historicalUnknown) {
    public State {
      files = List.copyOf(files);
      identities = Set.copyOf(identities);
    }
  }

  private final Map<String, FileReadProgress> files = new LinkedHashMap<>();
  private final Set<String> identities = new HashSet<>();
  private int rangeCount;
  private int sourceCharacters;
  private boolean historicalUnknown;

  public synchronized Status observe(String identity) {
    if (identity == null || identity.length() > 64) return Status.UNKNOWN;
    if (identities.contains(identity)) return Status.DUPLICATE;
    if (historicalUnknown || identities.size() >= LIMIT) return Status.UNKNOWN;
    identities.add(identity);
    return Status.NEW;
  }

  public synchronized Observation observeRange(String path, String revision, int start, int end) {
    if (path == null
        || revision == null
        || path.length() > 4096
        || revision.length() > 256
        || start < 1
        || end <= start) return new Observation(Status.UNKNOWN, 0);
    String key = path + "\u0000" + revision;
    var previous = files.get(key);
    var old = previous == null ? List.<LineRange>of() : previous.ranges();
    long added = (long) end - start;
    for (var range : old)
      added -=
          Math.max(
              0L,
              (long) Math.min(end, range.endExclusive()) - Math.max(start, range.startInclusive()));
    if (added == 0) return new Observation(Status.DUPLICATE, 0);
    if (historicalUnknown) return new Observation(Status.UNKNOWN, 0);
    var sorted = new ArrayList<>(old);
    sorted.add(new LineRange(start, end));
    sorted.sort(Comparator.comparingInt(LineRange::startInclusive));
    var merged = new ArrayList<LineRange>();
    for (var range : sorted) {
      if (merged.isEmpty() || merged.getLast().endExclusive() < range.startInclusive())
        merged.add(range);
      else {
        var last = merged.removeLast();
        merged.add(
            new LineRange(
                last.startInclusive(), Math.max(last.endExclusive(), range.endExclusive())));
      }
    }
    int count = rangeCount - old.size() + merged.size();
    if (previous == null
            && (files.size() >= LIMIT
                || sourceCharacters + path.length() + revision.length() > 32768)
        || count > LIMIT) return new Observation(Status.UNKNOWN, 0);
    if (previous == null) sourceCharacters += path.length() + revision.length();
    files.put(key, new FileReadProgress(path, revision, merged));
    rangeCount = count;
    return new Observation(Status.NEW, added);
  }

  public synchronized State snapshot() {
    return new State(new ArrayList<>(files.values()), identities, historicalUnknown);
  }

  public synchronized boolean covers(String path, String revision, int start, int end) {
    var file = files.get(path + "\u0000" + revision);
    return file != null
        && file.ranges().stream()
            .anyMatch(r -> r.startInclusive() <= start && r.endExclusive() >= end);
  }

  public synchronized void restore(State state) {
    if (state == null) {
      files.clear();
      identities.clear();
      rangeCount = 0;
      sourceCharacters = 0;
      historicalUnknown = true;
      return;
    }
    if (state.files().size() > LIMIT
        || state.identities().size() > LIMIT
        || state.identities().stream().anyMatch(s -> s.length() > 64))
      throw new IllegalArgumentException("Progress state capacity exceeded");
    // Validate atomically in a separate ledger before replacing live state.
    var validated = new InformationLedger();
    for (var file : state.files())
      for (var range : file.ranges()) {
        var observed =
            validated.observeRange(
                file.normalizedPath(),
                file.revision(),
                range.startInclusive(),
                range.endExclusive());
        if (observed.status() == Status.UNKNOWN)
          throw new IllegalArgumentException("Invalid progress state");
      }
    files.clear();
    identities.clear();
    files.putAll(validated.files);
    rangeCount = validated.rangeCount;
    sourceCharacters = validated.sourceCharacters;
    identities.addAll(state.identities());
    historicalUnknown = state.historicalUnknown();
  }
}
