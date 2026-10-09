package dev.mikoto2000.rei.voice;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.application.input.ConversationTarget;

@Component
@Command(name="voice",description="音声入力（初期OFF・選択したマイクのみ）",
    mixinStandardHelpOptions=true,subcommands={VoiceCommand.Device.class,VoiceCommand.Pending.class})
public class VoiceCommand {
  private final VoiceInputCoordinator voice;
  private final AudioDeviceService devices;
  private final VoiceProperties properties;
  private final ShellConversationService shell;
  private ConversationTarget target;
  @Spec private CommandSpec spec;
  /** Metadata-only construction for help/completion without Spring or native initialization. */
  public VoiceCommand() { this(null,null,null,null); }
  @org.springframework.beans.factory.annotation.Autowired
  public VoiceCommand(VoiceInputCoordinator voice,AudioDeviceService devices,VoiceProperties properties,ShellConversationService shell) {
    this.voice=voice;this.devices=devices;this.properties=properties;this.shell=shell;
  }
  private int attempt(IntSupplier action) {
    try {
      if(voice==null||devices==null||properties==null||shell==null)throw new IllegalStateException("Voice service is unavailable");
      return action.getAsInt();
    }
    catch (IllegalArgumentException | IllegalStateException e) { spec.commandLine().getErr().println(e.getMessage()); return 2; }
  }
  private void requireOff() {
    if (voice.state()!=VoiceInputCoordinator.State.OFF && voice.state()!=VoiceInputCoordinator.State.FAILED)
      throw new IllegalStateException("先に /voice off を実行し、OFFになるまで待ってください");
  }
  @Command(name="on",description="選択マイクで開始。確定した発話は会話入力として送信",mixinStandardHelpOptions=true)
  int on() { return attempt(()-> {
    var selected=devices.selected(); requireOff(); target=shell.captureTarget();
    voice.start(target,selected,properties.settings());
    if(voice.awaitStartup(java.time.Duration.ofSeconds(30))!=VoiceInputCoordinator.State.LISTENING)
      throw new IllegalStateException("音声入力を開始できませんでした。/voice status を確認してください");
    spec.commandLine().getOut().println("LISTENING: 受付中です");return 0;
  }); }
  @Command(name="off",description="録音を停止し、未確定・未認識発話を破棄",mixinStandardHelpOptions=true)
  int off() { return attempt(()-> {voice.off(); spec.commandLine().getOut().println("voice: "+voice.state()); return 0;}); }
  @Command(name="status",description="音声入力と固定した送信先の状態",mixinStandardHelpOptions=true)
  int status() { return attempt(()-> {
    spec.commandLine().getOut().println("voice: "+voice.state()+", ASR queue="+voice.queuedSegments()+"/2");
    if(target!=null)spec.commandLine().getOut().println("target: "+target.project().id()+" / "+target.sessionId());
    spec.commandLine().getOut().println("selected device: "+properties.getDeviceId());return 0;
  }); }
  @Command(name="devices",description="入力デバイスと明示選択用IDを表示",mixinStandardHelpOptions=true)
  int devices() { return attempt(()-> {
    for(var device:devices.devices()) spec.commandLine().getOut().println(device.id()+"  "+device.name()+" / "+device.description());
    return 0;
  }); }
  @Command(name="test",description="20秒間のマイク認識診断。Agentへは送信しません",mixinStandardHelpOptions=true)
  int test() { return attempt(()-> {
    var selected=devices.selected();requireOff();target=shell.captureTarget();
    voice.startDiagnostic(target,selected,properties.settings());
    if(voice.awaitStartup(java.time.Duration.ofSeconds(30))!=VoiceInputCoordinator.State.LISTENING)
      throw new IllegalStateException("音声診断を開始できませんでした");
    spec.commandLine().getOut().println("LISTENING: 20秒間の診断を開始しました。Agent送信・録音ファイル保存はありません。");return 0;
  }); }
  @Command(name="config",description="設定表示・変更。ミリ秒指定、変更はOFF時のみ",mixinStandardHelpOptions=true)
  int config(@Option(names="--threshold") Float threshold,
      @Option(names="--pre-roll-ms") Integer preRoll,@Option(names="--min-speech-ms") Integer minSpeech,
      @Option(names="--silence-ms") Integer silence,@Option(names="--max-speech-ms") Integer maxSpeech,
      @Option(names="--tail-ms") Integer tail) {
    return attempt(()-> {
      var s=properties.settings();
      if(threshold!=null||preRoll!=null||minSpeech!=null||silence!=null||maxSpeech!=null||tail!=null) {
        requireOff();
        s=new VoiceSettings(threshold==null?s.threshold():threshold,preRoll==null?s.preRollMs():preRoll,
          minSpeech==null?s.minSpeechMs():minSpeech,silence==null?s.silenceMs():silence,
          maxSpeech==null?s.maxSpeechMs():maxSpeech,tail==null?s.tailMs():tail);
        properties.setSettings(s);
      }
      spec.commandLine().getOut().println(s);return 0;
    });
  }
  @Component @Command(name="device",description="入力デバイスの明示選択",mixinStandardHelpOptions=true)
  public static class Device {
    @ParentCommand VoiceCommand parent;
    @Command(name="set",description="一覧のIDを選択（OFF時のみ）",mixinStandardHelpOptions=true)
    int set(@Parameters(paramLabel="ID") String id) {
      return parent.attempt(()-> {parent.requireOff();parent.devices.select(id);parent.properties.setDeviceId(id);
        parent.spec.commandLine().getOut().println("selected: "+parent.devices.selected().name());return 0;});
    }
  }
  @Component @Command(name="pending",description="固定した送信先の待機中VOICE入力",mixinStandardHelpOptions=true)
  public static class Pending implements Runnable {
    @ParentCommand VoiceCommand parent;
    public void run() {
      if(parent.target==null){parent.spec.commandLine().getOut().println("音声入力の送信先は未設定です");return;}
      for(var input:parent.shell.pending(parent.target))parent.spec.commandLine().getOut().println(input.inputId()+" "+input.text());
    }
    @Command(name="cancel",description="未実行のVOICE入力をキャンセル",mixinStandardHelpOptions=true)
    int cancel(@Parameters(paramLabel="ID") UUID id) {
      return parent.attempt(()-> {
        if(parent.target==null||!parent.shell.cancelPending(parent.target,id)) {
          parent.spec.commandLine().getErr().println("待機中の入力が見つかりません");return 2;
        }
        parent.spec.commandLine().getOut().println("cancelled: "+id);return 0;
      });
    }
  }
}