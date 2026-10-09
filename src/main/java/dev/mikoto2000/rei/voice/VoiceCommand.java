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
    mixinStandardHelpOptions=true,subcommands={VoiceCommand.Device.class,VoiceCommand.Pending.class,VoiceCommand.Models.class})
public class VoiceCommand {
  private final VoiceInputCoordinator voice;
  private final AudioDeviceService devices;
  private final VoiceProperties properties;
  private final ShellConversationService shell;
  private final VoiceModelManager models;
  private final VoiceDeliveryService delivery;
  private final WindowsMicrophoneMonitor monitor;
  private ConversationTarget target;
  @Spec private CommandSpec spec;
  /** Metadata-only construction for help/completion without Spring or native initialization. */
  public VoiceCommand() { this(null,null,null,null); }
  public VoiceCommand(VoiceInputCoordinator voice,AudioDeviceService devices,VoiceProperties properties,ShellConversationService shell) {
    this(voice,devices,properties,shell,null);
  }
  public VoiceCommand(VoiceInputCoordinator voice,AudioDeviceService devices,VoiceProperties properties,ShellConversationService shell,VoiceModelManager models) {
    this(voice,devices,properties,shell,models,null,null);
  }
  public VoiceCommand(VoiceInputCoordinator voice,AudioDeviceService devices,VoiceProperties properties,ShellConversationService shell,VoiceModelManager models,VoiceDeliveryService delivery) {
    this(voice,devices,properties,shell,models,delivery,null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public VoiceCommand(VoiceInputCoordinator voice,AudioDeviceService devices,VoiceProperties properties,ShellConversationService shell,VoiceModelManager models,VoiceDeliveryService delivery,WindowsMicrophoneMonitor monitor) {
    this.voice=voice;this.devices=devices;this.properties=properties;this.shell=shell;this.models=models;this.delivery=delivery;this.monitor=monitor;
  }
  private void bindDelivery() {
    if(delivery!=null){delivery.setConfirmation(properties.isConfirmation());delivery.bind(shell.captureClient(),target);}
  }
  private VoiceDeliveryService recognitionReviews() {
    if(delivery==null)throw new IllegalStateException("Recognition review service is unavailable");return delivery;
  }
  @Command(name="confirm",description="認識文を確認して送信。ツール実行の承認とは別です",mixinStandardHelpOptions=true)
  int confirm(@Parameters(paramLabel="ID") UUID id,@Option(names="--text",paramLabel="CORRECTION") String correction) {
    return attempt(()->{recognitionReviews().confirm(id,correction);spec.commandLine().getOut().println("confirmed: "+id);return 0;});
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
    requireOff(); requireModelsReady(); var selected=devices.selected(); target=shell.captureTarget();
    bindDelivery();voice.start(target,selected,properties.settings());
    if(voice.awaitStartup(VoiceRuntimeLimits.COMMAND_STARTUP)!=VoiceInputCoordinator.State.LISTENING)
      throw new IllegalStateException("音声入力を開始できませんでした。/voice status を確認してください");
    spec.commandLine().getOut().println("LISTENING: 受付中です");return 0;
  }); }
  @Command(name="off",description="録音を停止し、未確定・未認識発話を破棄",mixinStandardHelpOptions=true)
  int off() { return attempt(()-> {voice.off(); if(delivery!=null)delivery.clear(); spec.commandLine().getOut().println("voice: "+voice.state()); return 0;}); }
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
    requireOff(); requireModelsReady(); var selected=devices.selected();target=shell.captureTarget();
    bindDelivery();voice.startDiagnostic(target,selected,properties.settings());
    if(voice.awaitStartup(VoiceRuntimeLimits.COMMAND_STARTUP)!=VoiceInputCoordinator.State.LISTENING)
      throw new IllegalStateException("音声診断を開始できませんでした");
    spec.commandLine().getOut().println("LISTENING: 20秒間の診断を開始しました。Agent送信・録音ファイル保存はありません。");return 0;
  }); }
  @Command(name="config",description="設定表示・変更。ミリ秒指定、変更はOFF時のみ",mixinStandardHelpOptions=true)
  int config(@Option(names="--threshold") Float threshold,
      @Option(names="--pre-roll-ms") Integer preRoll,@Option(names="--min-speech-ms") Integer minSpeech,
      @Option(names="--silence-ms") Integer silence,@Option(names="--max-speech-ms") Integer maxSpeech,
      @Option(names="--tail-ms") Integer tail,@Option(names="--confirmation",arity="0..1",fallbackValue="true") Boolean confirmation) {
    return attempt(()-> {
      var s=properties.settings();
      if(threshold!=null||preRoll!=null||minSpeech!=null||silence!=null||maxSpeech!=null||tail!=null||confirmation!=null) {
        requireOff();
        s=new VoiceSettings(threshold==null?s.threshold():threshold,preRoll==null?s.preRollMs():preRoll,
          minSpeech==null?s.minSpeechMs():minSpeech,silence==null?s.silenceMs():silence,
          maxSpeech==null?s.maxSpeechMs():maxSpeech,tail==null?s.tailMs():tail);
        properties.setSettings(s);
        if(confirmation!=null){properties.setConfirmation(confirmation);if(delivery!=null)delivery.setConfirmation(confirmation);}
      }
      spec.commandLine().getOut().println(s);spec.commandLine().getOut().println("recognition confirmation: "+properties.isConfirmation());return 0;
    });
  }
  private VoiceModelManager modelManager() {
    if(models==null)throw new IllegalStateException("Voice model manager is unavailable");return models;
  }
  private void printModels() {
    var manifest=modelManager().manifest();var out=spec.commandLine().getOut();
    out.println("Whisper large-v3-turbo multilingual FP32 / Silero VAD / sherpa-onnx Windows x64 CPU");
    out.println("manifest: "+manifest.id()+"; total: "+manifest.totalBytes()+" bytes");
    for(var asset:manifest.assets()) {
      out.println(asset.path()+" / "+asset.bytes()+" bytes / "+asset.license());
      out.println("  "+asset.url());out.println("  SHA-256: "+asset.sha256());
    }
    out.println("明示承認: /voice models install --approve "+manifest.id());
    out.println("取得後に /voice on を実行してください。取得中はマイクを開きません。");
  }
  private void requireModelsReady() {
    if(models==null)return; // compatibility for metadata-only and legacy injected tests
    if(models.busy())throw new IllegalStateException("モデル取得中です。/voice models status または cancel を使ってください");
    try {models.readyDirectory();}
    catch(java.io.IOException missing) {printModels();throw new IllegalStateException("モデル一式が未配置または破損しています。承認後に取得するか、固定一式を手動配置してください");}
  }
  @Component @Command(name="models",description="固定モデル一式の案内・明示承認付き取得",mixinStandardHelpOptions=true)
  public static class Models implements Runnable {
    @ParentCommand VoiceCommand parent;
    public void run(){parent.attempt(()->{parent.printModels();return 0;});}
    @Command(name="info",description="配布元・ライセンス・サイズ・固定SHAを表示",mixinStandardHelpOptions=true)
    int info(){return parent.attempt(()->{parent.printModels();return 0;});}
    @Command(name="status",description="非同期取得の状態と進捗",mixinStandardHelpOptions=true)
    int status(){return parent.attempt(()->{
      var manager=parent.modelManager();
      if(!manager.busy())try {parent.spec.commandLine().getOut().println("verified ready: "+manager.readyDirectory());}
        catch(java.io.IOException unavailable){parent.spec.commandLine().getOut().println("bundle: missing or corrupt");}
      parent.spec.commandLine().getOut().println(manager.status());
      return 0;
    });}
    @Command(name="verify",description="ローカル固定一式のサイズ・SHAを検証。取得やマイク起動は行いません",mixinStandardHelpOptions=true)
    int verify(){return parent.attempt(()->{
      var manager=parent.modelManager();if(manager.busy())throw new IllegalStateException("モデル取得中です。完了後に検証してください");
      try{parent.spec.commandLine().getOut().println("verified ready: "+manager.readyDirectory());return 0;}
      catch(java.io.IOException invalid){throw new IllegalStateException("モデル一式が未配置または破損しています。models info を確認してください");}
    });}
    @Command(name="install",description="表示したmanifest IDを承認して非同期取得。失敗後は同じコマンドで再試行",mixinStandardHelpOptions=true)
    int install(@Option(names="--approve",paramLabel="MANIFEST_ID") String approval){return parent.attempt(()->{
      parent.requireOff();boolean started=parent.modelManager().install(approval);
      parent.spec.commandLine().getOut().println(started?"モデル取得開始。/voice models status または cancel":"検証済み一式を再利用します。ネットワーク取得はありません");return 0;
    });}
    @Command(name="cancel",description="取得を取り消し、不完全な一式を破棄",mixinStandardHelpOptions=true)
    int cancel(){return parent.attempt(()->{
      parent.spec.commandLine().getOut().println(parent.modelManager().cancel()?"取消を受け付けました。終了は models status で確認してください":"取得は実行中ではありません");return 0;
    });}
  }
  @Component @Command(name="device",description="入力デバイスの明示選択",mixinStandardHelpOptions=true)
  public static class Device {
    @ParentCommand VoiceCommand parent;
    @Command(name="set",description="一覧のIDを選択（OFF時のみ）",mixinStandardHelpOptions=true)
    int set(@Parameters(paramLabel="ID") String id) {
      return parent.attempt(()-> {parent.requireOff();parent.devices.select(id);parent.properties.setDeviceId(id);if(parent.monitor!=null)parent.monitor.reselect(id);
        parent.spec.commandLine().getOut().println("selected: "+parent.devices.selected().name());return 0;});
    }
  }
  @Component @Command(name="pending",description="固定した送信先の待機中VOICE入力",mixinStandardHelpOptions=true)
  public static class Pending implements Runnable {
    @ParentCommand VoiceCommand parent;
    public void run() {
      if(parent.delivery!=null)for(var input:parent.delivery.pending())parent.spec.commandLine().getOut().println("review: "+input.inputId()+" "+input.text());
      if(parent.target==null){parent.spec.commandLine().getOut().println("音声入力の送信先は未設定です");return;}
      for(var input:parent.shell.pending(parent.target))parent.spec.commandLine().getOut().println(input.inputId()+" "+input.text());
    }
    @Command(name="cancel",description="未実行のVOICE入力をキャンセル",mixinStandardHelpOptions=true)
    int cancel(@Parameters(paramLabel="ID") UUID id) {
      return parent.attempt(()-> {
        if(!(parent.delivery!=null&&parent.delivery.cancel(id))&&(parent.target==null||!parent.shell.cancelPending(parent.target,id))) {
          parent.spec.commandLine().getErr().println("待機中の入力が見つかりません");return 2;
        }
        parent.spec.commandLine().getOut().println("cancelled: "+id);return 0;
      });
    }
  }
}