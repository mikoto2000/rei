package dev.mikoto2000.rei.core.service;

import java.util.Set;
import org.springframework.stereotype.Component;

/** コマンド入力補完通知の判断基準をまとめる。 */
@Component
public class CommandCompletionNotificationPolicy {

  /** 無効状態作業ルートコマンド一覧。 */
  private static final Set<String> DISABLED_ROOT_COMMANDS =
      Set.of("model", "models", "project", "sh");

  /** 対話から使う共通操作において、通知の実施条件を判定する。 */
  public boolean shouldNotify(String... args) {
    if (args == null || args.length == 0 || args[0] == null) {
      return true;
    }
    return !DISABLED_ROOT_COMMANDS.contains(args[0]);
  }
}
