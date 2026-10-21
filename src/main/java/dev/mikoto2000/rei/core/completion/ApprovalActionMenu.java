package dev.mikoto2000.rei.core.completion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;

/**
 * 入力候補の提示に必要な承認操作操作の選択肢を扱う。
 *
 * <p>Arrow-key approval actions share the ordinary input reader; never read on an event thread.
 */
public final class ApprovalActionMenu implements AutoCloseable {
  /**
   * 入力候補の提示で受け渡す所有者を表す。
   *
   * @param project プロジェクト
   * @param session 会話
   */
  public record Owner(String project, String session) {}

  /**
   * 入力候補の提示で受け渡す要求を表す。
   *
   * @param owner 所有者
   * @param id 識別子
   */
  private record Request(Owner owner, String id) {}

  /** 読み取り元。 */
  private final LineReader reader;

  /** 所有者。 */
  private final Supplier<Owner> owner;

  /** 要求一覧。 */
  private final LinkedHashSet<Request> requests = new LinkedHashSet<>();

  /** 選択中。 */
  private Request selected;

  /** 操作。 */
  private int action;

  /** 追加した文章。 */
  private String inserted;

  /** 有効状態。 */
  private volatile boolean enabled;

  /** 終了済み。 */
  private volatile boolean closed;

  /** 改訂番号。 */
  private long revision;

  /** 状態の定期更新を実行する窓口。 */
  private java.util.concurrent.ScheduledExecutorService updates;

  /** 入力候補の提示に必要な待機中取得元を扱う。 */
  @FunctionalInterface
  public interface PendingSource {
    /** 後続の処理で判断できるよう、未完了の対象を返す。 */
    List<String> pending(Owner owner) throws Exception;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public void enabled(boolean value) {
    enabled = value;
    if (!value) clearDisplay();
    refreshStatus();
  }

  /** 入力候補の提示で用いる待機中件数を返す。 */
  public synchronized int pendingCount() {
    Owner current = owner.get();
    return (int) requests.stream().filter(r -> r.owner.equals(current)).count();
  }

  /** 入力候補の提示に関する再確認状態を処理する。 */
  private void refreshStatus() {
    int count = pendingCount();
    if (reader instanceof ApprovalMenuLineReader menuReader)
      menuReader.approvalStatus(
          !closed && count > 0
              ? "[APPROVAL PENDING: "
                  + count
                  + "] /approval list | "
                  + (enabled
                      ? "\u7a7a\u306e\u5165\u529b\u6b04: \u2191\u2193 \u3067\u9078\u629e / Enter \u3067\u5b9f\u884c (\u6700\u521d\u306f\u8a73\u7d30\u78ba\u8a8d)"
                      : "\u901a\u5e38\u306e\u5165\u529b\u6b04\u3067\u64cd\u4f5c\u3067\u304d\u307e\u3059")
              : "");
  }

  /**
   * 入力候補の提示に関する追加入力を処理する。
   *
   * <p>Read-only reconciliation; failure retains the last known state, never a grant.
   */
  public synchronized void follow(PendingSource source) {
    if (updates != null) throw new IllegalStateException("Already following approvals");
    updates =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              var thread = new Thread(r, "approval-display");
              thread.setDaemon(true);
              return thread;
            });
    updates.scheduleWithFixedDelay(
        () -> {
          if (closed) return;
          try {
            Owner captured = owner.get();
            long version;
            synchronized (this) {
              version = revision;
            }
            var ids = source.pending(captured);
            reconcile(captured, ids, version);
          } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
          }
        },
        0,
        1,
        java.util.concurrent.TimeUnit.SECONDS);
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  public void reconcile(Owner captured, List<String> ids) {
    reconcile(captured, ids, -1);
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  private void reconcile(Owner captured, List<String> ids, long version) {
    boolean removedSelection;
    synchronized (this) {
      if (!captured.equals(owner.get()) || version >= 0 && version != revision) return;
      var replacements = new LinkedHashSet<Request>();
      for (String id : ids) {
        if (replacements.size() >= 256) break;
        try {
          if (UUID.fromString(id).toString().equalsIgnoreCase(id))
            replacements.add(new Request(captured, id));
        } catch (RuntimeException invalid) {
        }
      }
      requests.clear();
      requests.addAll(replacements);
      revision++;
      removedSelection = selected != null && !requests.contains(selected);
    }
    if (removedSelection) clearDisplay();
    refreshStatus();
  }

  /** 入力候補の提示で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    closed = true;
    if (updates != null) updates.shutdownNow();
    enabled(false);
  }

  /** 表示名一覧。 */
  private static final List<String> LABELS = List.of("詳細確認", "承認して続行", "拒否");

  /** 指定された値や利用先を使って、ApprovalActionMenuを初期化する。 */
  public ApprovalActionMenu(LineReader reader, Supplier<Owner> owner) {
    this.reader = reader;
    this.owner = owner;
    reader.getWidgets().put("rei-approval-up", () -> move(-1));
    reader.getWidgets().put("rei-approval-down", () -> move(1));
    reader.getWidgets().put("rei-approval-enter", this::enter);
    for (String name : List.of(LineReader.EMACS, LineReader.VIINS)) {
      var keys = reader.getKeyMaps().get(name);
      if (keys == null) continue;
      keys.bind(new Reference("rei-approval-up"), "\033[A", "\033OA");
      keys.bind(new Reference("rei-approval-down"), "\033[B", "\033OB");
      keys.bind(new Reference("rei-approval-enter"), "\r");
    }
  }

  /** 入力候補の提示に関する受付を処理する。 */
  public void offer(String project, String session, String id) {
    try {
      if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) return;
    } catch (RuntimeException invalid) {
      return;
    }
    var request = new Request(new Owner(project, session), id);
    boolean added;
    synchronized (this) {
      added = requests.size() < 256 && requests.add(request);
      if (added) revision++;
    }
    refreshStatus();
    if (added) reader.printAbove("承認メニュー: 入力欄が空のとき ↑↓ で選択、Enter で実行（最初は詳細確認）。");
  }

  /** 入力候補の提示において、moveに該当するか判定する。 */
  synchronized boolean move(int direction) {
    if (!enabled) return history(direction);
    String buffer = reader.getBuffer().toString();
    if (!buffer.isEmpty() && !Objects.equals(buffer, inserted)) return history(direction);
    Owner current = owner.get();
    var visible = requests.stream().filter(r -> r.owner.equals(current)).toList();
    if (visible.isEmpty()) {
      if (Objects.equals(buffer, inserted)) reader.getBuffer().clear();
      selected = null;
      inserted = null;
      return history(direction);
    }
    int index = selected == null ? -1 : visible.indexOf(selected);
    if (index < 0 || !Objects.equals(buffer, inserted)) {
      selected = visible.getFirst();
      action = 0;
    } else {
      int next =
          Math.floorMod(index * LABELS.size() + action + direction, visible.size() * LABELS.size());
      selected = visible.get(next / LABELS.size());
      action = next % LABELS.size();
    }
    inserted = command(selected.id, action);
    reader.getBuffer().clear();
    reader.getBuffer().write(inserted);
    var display = new StringBuilder("承認 [" + selected.id + "]　↑↓: 選択 / Enter: 実行\n");
    for (int i = 0; i < LABELS.size(); i++)
      display.append(i == action ? "▶ " : "  ").append(LABELS.get(i)).append('\n');
    if (reader instanceof ApprovalMenuLineReader menuReader)
      menuReader.approvalMenu(display.toString().stripTrailing());
    return true;
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  private boolean history(int direction) {
    clearDisplay();
    reader.callWidget(
        direction < 0 ? LineReader.UP_LINE_OR_HISTORY : LineReader.DOWN_LINE_OR_HISTORY);
    return true;
  }

  /** 入力候補の提示において、利用開始に該当するか判定する。 */
  synchronized boolean enter() {
    clearDisplay();
    if (enabled && selected != null && Objects.equals(reader.getBuffer().toString(), inserted)) {
      if (!selected.owner.equals(owner.get()) || !requests.contains(selected)) {
        reader.getBuffer().clear();
        selected = null;
        inserted = null;
        reader.printAbove("Project/Session が変わったため、承認の選択を取り消しました。");
        return true;
      }
      selected = null;
      inserted = null;
    }
    reader.callWidget(LineReader.ACCEPT_LINE);
    return true;
  }

  /** 保持している情報を初期状態へ戻すため、表示を消去する。 */
  private void clearDisplay() {
    if (reader instanceof ApprovalMenuLineReader menuReader) menuReader.clearApprovalMenu();
  }

  /** 入力候補の提示で用いるコマンドを返す。 */
  private static String command(String id, int action) {
    return switch (action) {
      case 0 -> "/approval show " + id;
      case 1 -> "/approval approve " + id + " --continue";
      default -> "/approval deny " + id;
    };
  }
}
