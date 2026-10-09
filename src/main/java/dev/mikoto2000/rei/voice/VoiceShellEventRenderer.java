package dev.mikoto2000.rei.voice;
import java.util.function.Consumer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
public final class VoiceShellEventRenderer implements Consumer<VoiceEventPublisher.Event> {
  private final ShellEventOutput output;
  public VoiceShellEventRenderer(ShellEventOutput output) { this.output=output; }
  public void accept(VoiceEventPublisher.Event event) {
    String text=switch(event.type()) {
      case STATE_CHANGED -> event.detail().equals("LISTENING") ? "LISTENING: 受付中です" : event.detail();
      case SEGMENT_QUEUE_FULL -> "認識待ちキューが満杯のため発話を破棄しました";
      case SHORT_DROPPED -> "短すぎる発話を破棄しました";
      case MAX_DROPPED -> "25秒の上限に達した未完了発話を破棄しました";
      case RESULT_REJECTED -> "空・無効・コマンド形式の認識結果を破棄しました";
      case INPUT_REJECTED -> "会話入力を受け付けられませんでした。/voice pending と送信先Sessionを確認してください";
      case BACKEND_FAILED -> "音声モデル/JNIの初期化に失敗しました。モデル一式と --enable-native-access=ALL-UNNAMED を確認してください";
      case CAPTURE_FAILED -> "選択マイクの録音/VADが停止しました。別マイクへは切り替えません";
      case RECOGNITION_FAILED -> "音声認識に失敗して停止しました";
      case RELEASE_FAILED -> "音声資源の解放に失敗しました";
      case DIAGNOSTIC_RESULT -> "診断結果（Agent送信なし）: "+event.detail();
    };
    output.println("[voice] "+text);output.flush();
  }
}