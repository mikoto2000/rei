package dev.mikoto2000.rei.core.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.Scope;

/**
 * プロジェクトごとの作業環境に必要なプロジェクト機能の構成要素対象範囲を扱う。
 *
 * <p>Existing state services keep their API; each project gets a distinct instance.
 */
public final class ProjectBeanScope implements Scope {
  /** 識別情報。 */
  private final java.util.function.Supplier<String> identity;

  /** プロジェクトごとの作業環境を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectBeanScope() {
    this(
        () -> {
          var context = ProjectService.contextForOperation();
          return context == null ? "global" : context.id();
        });
  }

  /** 指定された値や利用先を使って、ProjectBeanScopeを初期化する。 */
  public ProjectBeanScope(java.util.function.Supplier<String> identity) {
    this.identity = identity;
  }

  /** プロジェクト一覧。 */
  private final Map<String, Map<String, Object>> projects = new HashMap<>();

  /** スコープ終了時に呼び出す解放処理。 */
  private final Map<String, Runnable> destruction = new HashMap<>();

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public synchronized Object get(String name, ObjectFactory<?> factory) {
    return projects
        .computeIfAbsent(getConversationId(), key -> new HashMap<>())
        .computeIfAbsent(name, key -> factory.getObject());
  }

  /** 指定された対象を管理から取り除く。 */
  public synchronized Object remove(String name) {
    var beans = projects.get(getConversationId());
    return beans == null ? null : beans.remove(name);
  }

  /** 後から対象を参照できるよう、Destruction完了時の処理を登録する。 */
  public synchronized void registerDestructionCallback(String name, Runnable callback) {
    destruction.put(getConversationId() + ":" + name, callback);
  }

  /** 指定や設定に応じた対象を利用するため、Contextual対象データを確定する。 */
  public Object resolveContextualObject(String key) {
    return null;
  }

  /** プロジェクトごとの作業環境で参照する会話の識別子を返す。 */
  public String getConversationId() {
    var run = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    return run != null && run.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        ? "run:" + run.runId() + ":" + identity.get()
        : identity.get();
  }

  /** 後続の処理が利用できるよう、実行を解放する。 */
  public synchronized void releaseRun(String runId) {
    String prefix = "run:" + runId + ":";
    projects.keySet().removeIf(key -> key.startsWith(prefix));
    var callbacks = new ArrayList<Runnable>();
    destruction
        .entrySet()
        .removeIf(
            entry -> {
              if (entry.getKey().startsWith(prefix)) {
                callbacks.add(entry.getValue());
                return true;
              }
              return false;
            });
    callbacks.forEach(Runnable::run);
  }

  /** プロジェクトごとの作業環境で利用した資源や登録を解放し、利用を終了する。 */
  public synchronized void close() {
    destruction.values().forEach(Runnable::run);
    destruction.clear();
    projects.clear();
  }
}
