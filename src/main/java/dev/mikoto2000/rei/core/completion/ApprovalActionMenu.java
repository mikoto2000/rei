package dev.mikoto2000.rei.core.completion;

import java.util.*;
import java.util.function.Supplier;
import org.jline.reader.*;

/** Arrow-key approval actions share the ordinary input reader; never read on an event thread. */
public final class ApprovalActionMenu {
  public record Owner(String project, String session) {}
  private record Request(Owner owner, String id) {}
  private final LineReader reader;
  private final Supplier<Owner> owner;
  private final LinkedHashSet<Request> requests = new LinkedHashSet<>();
  private Request selected;
  private int action;
  private String inserted;
  private volatile boolean enabled;
  public void enabled(boolean value) { enabled = value; }
  private static final List<String> LABELS = List.of("詳細確認", "承認して続行", "拒否");

  public ApprovalActionMenu(LineReader reader, Supplier<Owner> owner) {
    this.reader = reader; this.owner = owner;
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

  public void offer(String project, String session, String id) {
    try { if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) return; }
    catch (RuntimeException invalid) { return; }
    var request = new Request(new Owner(project, session), id);
    boolean added;
    synchronized (this) { added = requests.size() < 256 && requests.add(request); }
    if (added) reader.printAbove("承認メニュー: 入力欄が空のとき ↑↓ で選択、Enter で実行（最初は詳細確認）。");
  }

  synchronized boolean move(int direction) {
    if (!enabled) return history(direction);
    String buffer = reader.getBuffer().toString();
    if (!buffer.isEmpty() && !Objects.equals(buffer, inserted)) return history(direction);
    Owner current = owner.get();
    var visible = requests.stream().filter(r -> r.owner.equals(current)).toList();
    if (visible.isEmpty()) {
      if (Objects.equals(buffer, inserted)) reader.getBuffer().clear();
      selected = null; inserted = null;
      return history(direction);
    }
    int index = selected == null ? -1 : visible.indexOf(selected);
    if (index < 0 || !Objects.equals(buffer, inserted)) { selected = visible.getFirst(); action = 0; }
    else {
      int next = Math.floorMod(index * LABELS.size() + action + direction, visible.size() * LABELS.size());
      selected = visible.get(next / LABELS.size()); action = next % LABELS.size();
    }
    inserted = command(selected.id, action);
    reader.getBuffer().clear(); reader.getBuffer().write(inserted);
    var display = new StringBuilder("承認 [" + selected.id + "]　↑↓: 選択 / Enter: 実行\n");
    for (int i = 0; i < LABELS.size(); i++) display.append(i == action ? "▶ " : "  ").append(LABELS.get(i)).append('\n');
    reader.printAbove(display.toString().stripTrailing());
    return true;
  }

  private boolean history(int direction) {
    reader.callWidget(direction < 0 ? LineReader.UP_LINE_OR_HISTORY : LineReader.DOWN_LINE_OR_HISTORY);
    return true;
  }

  synchronized boolean enter() {
    if (enabled && selected != null && Objects.equals(reader.getBuffer().toString(), inserted)) {
      if (!selected.owner.equals(owner.get())) {
        reader.getBuffer().clear(); selected = null; inserted = null;
        reader.printAbove("Project/Session が変わったため、承認の選択を取り消しました。");
        return true;
      }
      if (action != 0) requests.remove(selected);
      selected = null; inserted = null;
    }
    reader.callWidget(LineReader.ACCEPT_LINE);
    return true;
  }

  private static String command(String id, int action) {
    return switch (action) {
      case 0 -> "/approval show " + id;
      case 1 -> "/approval approve " + id + " --continue";
      default -> "/approval deny " + id;
    };
  }
}
