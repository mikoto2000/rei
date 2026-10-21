package dev.mikoto2000.rei.core.service;

import java.util.Set;
import org.springframework.stereotype.Component;

/** コマンド利用者入力表示の判断基準をまとめる。 */
@Component
public class CommandUserInputDisplayPolicy {

  /** 非表示作業ルートコマンド一覧。 */
  private static final Set<String> HIDDEN_ROOT_COMMANDS = Set.of("project", "sh");

  /** 対話から使う共通操作において、表示の実施条件を判定する。 */
  public boolean shouldDisplay(String... args) {
    if (args == null || args.length == 0 || args[0] == null) {
      return true;
    }
    return !HIDDEN_ROOT_COMMANDS.contains(args[0]);
  }
}
