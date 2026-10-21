package dev.mikoto2000.rei.computeruse;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 自動実行の指定で利用する選択可能な設定。
 *
 * <p>Explicit startup opt-in; intentionally not bound to YAML or environment variables.
 */
@Component
public final class FullAutoOptions implements ApplicationRunner {
  /** 有効状態。 */
  private final boolean enabled;

  /** 指定された値や利用先を使って、FullAutoOptionsを初期化する。 */
  public FullAutoOptions(ApplicationArguments args) {
    var values = args.getOptionValues("fullauto");
    if (!args.containsOption("fullauto")) enabled = false;
    else if (values == null || values.isEmpty()) enabled = true;
    else if (values.size() == 1
        && ("true".equals(values.getFirst()) || "false".equals(values.getFirst())))
      enabled = Boolean.parseBoolean(values.getFirst());
    else throw new IllegalArgumentException("Use --fullauto, --fullauto=true, or --fullauto=false");
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public boolean enabled() {
    return enabled;
  }

  /** 画面操作の支援で用いる起動時メッセージを返す。 */
  public String startupMessage() {
    return enabled
        ? "[tool_permission] --fullauto enabled: tool approval requests (including voice input) are automatically approved; DENY and execution-mode restrictions remain enforced. [computer_use] CONFIRM_REQUIRED operations (投稿・送信・削除・購入など) are allowed; PROHIBITED remains blocked."
        : "[tool_permission] --fullauto disabled: configured tool approvals apply. [computer_use] only LOW operations are allowed.";
  }

  /** 画面操作の支援の操作を実行する。 */
  @Override
  public void run(ApplicationArguments args) {
    System.out.println(startupMessage());
  }
}
