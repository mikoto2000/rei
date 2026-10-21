package dev.mikoto2000.rei.llm;

/**
 * 出力上限実行の使用量と上限を管理する。
 *
 * <p>Independent absolute counters for output splitting and stagnation replanning, sharing the same
 * model call and token ceilings. Meaningful progress never replenishes it. The executor reserves
 * each outer prompt/planner call; the controlled model loop reserves every additional LLM/tool
 * cycle before invoking the model.
 */
public class OutputLimitRunBudget {
  /** 既定最大引数補正一覧。 */
  public static final int DEFAULT_MAX_ARGUMENT_CORRECTIONS = 5;

  /**
   * 用途に応じたモデル利用で受け渡す状態の記録を表す。
   *
   * @param replans 計画の見直し回数
   * @param llmCalls 言語モデル呼び出し回数
   * @param totalTokens 使用トークン数の合計
   * @param usageUnknown 使用状況不明
   * @param stagnationReplans 停滞計画の見直し回数
   * @param argumentCorrections 引数補正一覧
   */
  public record Snapshot(
      int replans,
      int llmCalls,
      long totalTokens,
      boolean usageUnknown,
      int stagnationReplans,
      int argumentCorrections) {
    /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Snapshot(
        int replans, int llmCalls, long totalTokens, boolean usageUnknown, int stagnationReplans) {
      this(replans, llmCalls, totalTokens, usageUnknown, stagnationReplans, 0);
    }

    /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Snapshot(int replans, int llmCalls, long totalTokens, boolean usageUnknown) {
      this(replans, llmCalls, totalTokens, usageUnknown, 0);
    }
  }

  /** 引数補正一覧。 */
  private int argumentCorrections;

  /** 用途に応じたモデル利用で判断に用いる試行Correct引数の条件を確認する。 */
  public synchronized boolean tryCorrectArguments() {
    if (argumentCorrections >= maxArgumentCorrections) return false;
    argumentCorrections++;
    return true;
  }

  /** 用途に応じたモデル利用で用いる最大引数補正一覧を返す。 */
  public int maxArgumentCorrections() {
    return maxArgumentCorrections;
  }

  /** 用途に応じたモデル利用で用いる残量引数補正一覧を返す。 */
  public synchronized int remainingArgumentCorrections() {
    return Math.max(0, maxArgumentCorrections - argumentCorrections);
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  public synchronized Snapshot snapshot() {
    return new Snapshot(
        replans, llmCalls, totalTokens, usageUnknown, stagnationReplans, argumentCorrections);
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
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

  /**
   * 用途に応じたモデル利用に必要な言語モデル呼び出し予約を扱う。
   *
   * <p>Optional durable parent budget. Reservations precede every model call.
   */
  public interface LlmCallReservation {
    /** 用途に応じたモデル利用で判断に用いる試行予約の条件を確認する。 */
    boolean tryReserve();

    /** 用途に応じたモデル利用で用いる残量を返す。 */
    int remaining();

    /** 使用したトークン数を実行予算へ記録する。 */
    default void recordTotalTokens(Integer tokens) {}

    /** トークン数による実行予算の制限が有効かどうかを返す。 */
    default boolean tokenLimitEnabled() {
      return false;
    }

    /** 用途に応じたモデル利用において、トークン残量なしに該当するか判定する。 */
    default boolean tokenExhausted() {
      return false;
    }

    /** 用途に応じたモデル利用において、使用状況不明に該当するか判定する。 */
    default boolean usageUnknown() {
      return false;
    }
  }

  /** 予約。 */
  private final LlmCallReservation reservation;

  /** 最大計画の見直し回数。 */
  private final int maxReplans;

  /** 最大言語モデル呼び出し回数。 */
  private final int maxLlmCalls;

  /** 言語モデル呼び出し予算有効状態。 */
  private final boolean llmCallBudgetEnabled;

  /** 最大引数補正一覧。 */
  private final int maxArgumentCorrections;

  /** 計画の見直し回数。 */
  private int replans;

  /** 停滞計画の見直し回数。 */
  private int stagnationReplans;

  /** 言語モデル呼び出し回数。 */
  private int llmCalls;

  /** 最大使用トークン数の合計。 */
  private final long maxTotalTokens;

  /** 使用トークン数の合計。 */
  private long totalTokens;

  /** 使用状況不明。 */
  private boolean usageUnknown;

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls) {
    this(maxReplans, maxLlmCalls, null);
  }

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OutputLimitRunBudget(int maxReplans, int maxLlmCalls, LlmCallReservation reservation) {
    this(maxReplans, maxLlmCalls, reservation, 0);
  }

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OutputLimitRunBudget(
      int maxReplans, int maxLlmCalls, LlmCallReservation reservation, long maxTotalTokens) {
    this(maxReplans, maxLlmCalls, reservation, maxTotalTokens, DEFAULT_MAX_ARGUMENT_CORRECTIONS);
  }

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OutputLimitRunBudget(
      int maxReplans,
      int maxLlmCalls,
      LlmCallReservation reservation,
      long maxTotalTokens,
      int maxArgumentCorrections) {
    this(maxReplans, maxLlmCalls, reservation, maxTotalTokens, maxArgumentCorrections, true);
  }

  /** 指定された値や利用先を使って、OutputLimitRunBudgetを初期化する。 */
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

  /** 用途に応じたモデル利用で用いる共有言語モデル予約を返す。 */
  public LlmCallReservation sharedLlmReservation() {
    return reservation;
  }

  /** 用途に応じたモデル利用で判断に用いる試行Consume言語モデル呼び出しの条件を確認する。 */
  public synchronized boolean tryConsumeLlmCall() {
    if ((llmCallBudgetEnabled && llmCalls >= maxLlmCalls) || tokenExhausted()) {
      return false;
    }
    if (reservation != null && !reservation.tryReserve()) return false;
    if (llmCalls < Integer.MAX_VALUE) llmCalls++;
    return true;
  }

  /** 用途に応じたモデル利用で判断に用いる試行Consume計画の見直しの条件を確認する。 */
  public synchronized boolean tryConsumeReplan() {
    if (replans >= maxReplans || remainingLlmCalls() <= 0) {
      return false;
    }
    replans++;
    return true;
  }

  /** 用途に応じたモデル利用で用いる計画の見直し件数を返す。 */
  public synchronized int replanCount() {
    return replans;
  }

  /** 用途に応じたモデル利用で判断に用いる試行Consume停滞計画の見直しの条件を確認する。 */
  public synchronized boolean tryConsumeStagnationReplan() {
    if (stagnationReplans >= maxReplans || remainingLlmCalls() <= 0) return false;
    stagnationReplans++;
    return true;
  }

  /** 用途に応じたモデル利用で用いる残量言語モデル呼び出し回数を返す。 */
  public synchronized int remainingLlmCalls() {
    if (tokenExhausted()) return 0;
    int local = llmCallBudgetEnabled ? Math.max(0, maxLlmCalls - llmCalls) : Integer.MAX_VALUE;
    return reservation == null ? local : Math.min(local, Math.max(0, reservation.remaining()));
  }

  /** 用途に応じたモデル利用において、残量言語モデル呼び出し回数の有無を判定する。 */
  public synchronized boolean hasRemainingLlmCalls() {
    return remainingLlmCalls() > 0;
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return maxTotalTokens > 0 || (reservation != null && reservation.tokenLimitEnabled());
  }

  /** 用途に応じたモデル利用において、言語モデル呼び出し予算有効状態に該当するか判定する。 */
  public boolean llmCallBudgetEnabled() {
    return llmCallBudgetEnabled;
  }

  /** 用途に応じたモデル利用で用いる使用トークン数の合計を返す。 */
  public synchronized long totalTokens() {
    return totalTokens;
  }

  /** 用途に応じたモデル利用において、使用状況不明に該当するか判定する。 */
  public synchronized boolean usageUnknown() {
    return usageUnknown || (reservation != null && reservation.usageUnknown());
  }

  /** 用途に応じたモデル利用において、トークン上限超過に該当するか判定する。 */
  public synchronized boolean tokenExceeded() {
    return maxTotalTokens > 0 && totalTokens > maxTotalTokens;
  }

  /** 用途に応じたモデル利用において、トークン残量なしに該当するか判定する。 */
  public synchronized boolean tokenExhausted() {
    return (maxTotalTokens > 0 && (usageUnknown || totalTokens >= maxTotalTokens))
        || (reservation != null && reservation.tokenExhausted());
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public synchronized void recordTotalTokens(Integer tokens) {
    if (!tokenLimitEnabled()) return;
    if (tokens == null || tokens <= 0) usageUnknown = true;
    else
      totalTokens = totalTokens > Long.MAX_VALUE - tokens ? Long.MAX_VALUE : totalTokens + tokens;
    if (reservation != null && reservation.tokenLimitEnabled())
      reservation.recordTotalTokens(tokens);
  }
}
