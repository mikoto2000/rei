package dev.mikoto2000.rei.computeruse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 画面操作の支援で受け渡す画面操作利用設定を表す。
 *
 * @param enabled 有効状態
 * @param maxSteps 最大手順一覧
 * @param historyLimit 履歴上限
 * @param stabilizationMillis stabilizationミリ秒数
 * @param repairs 修復一覧
 * @param clipboardMillis クリップボードミリ秒数
 */
@ConfigurationProperties("rei.computer-use")
public record ComputerUseProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("20") int maxSteps,
    @DefaultValue("5") int historyLimit,
    @DefaultValue("500") long stabilizationMillis,
    @DefaultValue("1") int repairs,
    @DefaultValue("150") long clipboardMillis) {
  /** 受け取った情報からComputerUsePropertiesを作成する。 */
  public ComputerUseProperties {
    if (maxSteps < 1
        || maxSteps > 200
        || historyLimit < 1
        || historyLimit > 20
        || stabilizationMillis < 1
        || stabilizationMillis > 10000
        || repairs < 0
        || repairs > 2
        || clipboardMillis < 1
        || clipboardMillis > 10000)
      throw new IllegalArgumentException("Invalid Computer Use limits");
  }
}
