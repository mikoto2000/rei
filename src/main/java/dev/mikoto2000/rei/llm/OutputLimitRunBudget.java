package dev.mikoto2000.rei.llm;

/**
 * Independent absolute counters for output splitting and stagnation replanning, sharing the same
 * model call and token ceilings. Meaningful progress never replenishes it. The executor reserves
 * each outer prompt/planner call; the controlled model loop reserves every additional LLM/tool
 * cycle before invoking the model.
 */
public class OutputLimitRunBudget {
  public static final int DEFAULT_MAX_ARGUMENT_CORRECTIONS = 5;

  public record Snapshot(
      int replans,
      int llmCalls,
      long totalTokens,
      boolean usageUnknown,
      int stagnationReplans,
      int argumentCorrections) {
    public Snapshot(
        int replans, int llmCalls, long totalTokens, boolean usageUnknown, int stagnationReplans) {
      this(replans, llmCalls, totalTokens, usageUnknown, stagnationReplans, 0);
    }

    public Snapshot(int replans, int llmCalls, long totalTokens, boolean usageUnknown) {
      this(replans, llmCalls, totalTokens, usageUnknown, 0);
    }
  }

  private int argumentCorrections;

  public synchronized boolean tryCorrectArguments() {
    if (argumentCorrections >= maxArgumentCorrections) return false;
    argumentCorrections++;
    return true;
  }

  public int maxArgumentCorrections() {
    return maxArgumentCorrections;
  }

  public synchronized int remainingArgumentCorrections() {
    return Math.max(0, maxArgumentCorrections - argumentCorrections);
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        replans, llmCalls, totalTokens, usageUnknown, stagnationReplans, argumentCorrections);
  }

  public synchronized void restore(Snapshot saved) {
    if (saved == null
        || saved.replans() < 0
        || saved.stagnationReplans() < 0
        || saved.llmCalls() < 0
        || saved.totalTokens() < 0
        || saved.argumentCorrections() < 0)
      throw new IllegalArgumentException("Invalid saved Run budget");
    argumentCorrections = Math.max(argumentCorrections, saved.argumentCorrections());
    stagnationReplans = Math.max(stagnationReplans, saved.stagnationReplans());
    replans = Math.max(replans, saved.replans());
    llmCalls = Math.max(llmCalls, saved.llmCalls());
    totalTokens = Math.max(totalTokens, saved.totalTokens());
    usageUnknown |= saved.usageUnknown();
  }

  /** Optional durable parent budget. Reservations precede every model call. */
  public interface LlmCallReservation {
    boolean tryReserve();

    int remaining();

    default void recordTotalTokens(Integer tokens) {}

    default boolean tokenLimitEnabled() {
      return false;
    }

    default boolean tokenExhausted() {
      return false;
    }

    default boolean usageUnknown() {
      return false;
    }
  }

  private final LlmCallReservation reservation;

  private final int maxReplans;
  private final int maxLlmCalls;
  private final boolean llmCallBudgetEnabled;
  private final int maxArgumentCorrections;
  private int replans;
  private int stagnationReplans;
  private int llmCalls;
  private final long maxTotalTokens;
  private long totalTokens;
  private boolean usageUnknown;

  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls) {
    this(maxReplans, maxLlmCalls, null);
  }

  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls, LlmCallReservation reservation) {
    this(maxReplans, maxLlmCalls, reservation, 0);
  }

  public OutputLimitRunBudget(
      int maxReplans, int maxLlmCalls, LlmCallReservation reservation, long maxTotalTokens) {
    this(maxReplans, maxLlmCalls, reservation, maxTotalTokens, DEFAULT_MAX_ARGUMENT_CORRECTIONS);
  }

  public OutputLimitRunBudget(
      int maxReplans,
      int maxLlmCalls,
      LlmCallReservation reservation,
      long maxTotalTokens,
      int maxArgumentCorrections) {
    this(maxReplans, maxLlmCalls, reservation, maxTotalTokens, maxArgumentCorrections, true);
  }

  public OutputLimitRunBudget(
      int maxReplans,
      int maxLlmCalls,
      LlmCallReservation reservation,
      long maxTotalTokens,
      int maxArgumentCorrections,
      boolean llmCallBudgetEnabled) {
    if (maxTotalTokens < 0) throw new IllegalArgumentException("Token limit must be nonnegative");
    if (maxArgumentCorrections < 0)
      throw new IllegalArgumentException("Argument correction limit must be nonnegative");
    this.maxArgumentCorrections = maxArgumentCorrections;
    this.maxTotalTokens = maxTotalTokens;
    this.maxReplans = Math.max(0, maxReplans);
    this.maxLlmCalls = Math.max(0, maxLlmCalls);
    this.llmCallBudgetEnabled = llmCallBudgetEnabled;
    this.reservation = reservation;
  }

  public LlmCallReservation sharedLlmReservation() {
    return reservation;
  }

  public synchronized boolean tryConsumeLlmCall() {
    if ((llmCallBudgetEnabled && llmCalls >= maxLlmCalls) || tokenExhausted()) {
      return false;
    }
    if (reservation != null && !reservation.tryReserve()) return false;
    if (llmCalls < Integer.MAX_VALUE) llmCalls++;
    return true;
  }

  public synchronized boolean tryConsumeReplan() {
    if (replans >= maxReplans || remainingLlmCalls() <= 0) {
      return false;
    }
    replans++;
    return true;
  }

  public synchronized int replanCount() {
    return replans;
  }

  public synchronized boolean tryConsumeStagnationReplan() {
    if (stagnationReplans >= maxReplans || remainingLlmCalls() <= 0) return false;
    stagnationReplans++;
    return true;
  }

  public synchronized int remainingLlmCalls() {
    if (tokenExhausted()) return 0;
    int local = llmCallBudgetEnabled ? Math.max(0, maxLlmCalls - llmCalls) : Integer.MAX_VALUE;
    return reservation == null ? local : Math.min(local, Math.max(0, reservation.remaining()));
  }

  public synchronized boolean hasRemainingLlmCalls() {
    return remainingLlmCalls() > 0;
  }

  public boolean tokenLimitEnabled() {
    return maxTotalTokens > 0 || (reservation != null && reservation.tokenLimitEnabled());
  }

  public boolean llmCallBudgetEnabled() {
    return llmCallBudgetEnabled;
  }

  public synchronized long totalTokens() {
    return totalTokens;
  }

  public synchronized boolean usageUnknown() {
    return usageUnknown || (reservation != null && reservation.usageUnknown());
  }

  public synchronized boolean tokenExceeded() {
    return maxTotalTokens > 0 && totalTokens > maxTotalTokens;
  }

  public synchronized boolean tokenExhausted() {
    return (maxTotalTokens > 0 && (usageUnknown || totalTokens >= maxTotalTokens))
        || (reservation != null && reservation.tokenExhausted());
  }

  public synchronized void recordTotalTokens(Integer tokens) {
    if (!tokenLimitEnabled()) return;
    if (tokens == null || tokens <= 0) usageUnknown = true;
    else
      totalTokens = totalTokens > Long.MAX_VALUE - tokens ? Long.MAX_VALUE : totalTokens + tokens;
    if (reservation != null && reservation.tokenLimitEnabled())
      reservation.recordTotalTokens(tokens);
  }
}
