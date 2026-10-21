package dev.mikoto2000.rei.ui.shell.sound;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 音通知通知の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.sound-notification")
public class SoundNotificationProperties {

  /** 音声通知の有効/無効。デフォルト: false */
  private boolean enabled = false;

  /** 実行するコマンドとその引数のリスト。デフォルト: 空リスト */
  private List<String> command = new ArrayList<>();
}
