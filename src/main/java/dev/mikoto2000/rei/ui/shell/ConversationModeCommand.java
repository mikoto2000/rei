package dev.mikoto2000.rei.ui.shell;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.chat.ResponseStyle;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
@Component
@Command(name="mode",description="Sessionごとの応答スタイル。実行能力・承認は維持します",mixinStandardHelpOptions=true)
public final class ConversationModeCommand implements java.util.concurrent.Callable<Integer> {
 private final ShellConversationService shell;
 @Spec private CommandSpec spec;
 public ConversationModeCommand(){this(null);}
 @org.springframework.beans.factory.annotation.Autowired
 public ConversationModeCommand(ShellConversationService shell){this.shell=shell;}
 public Integer call(){return status();}
 @Command(name="auto",description="音声入力は会話、文字入力は通常へ自動切替する",mixinStandardHelpOptions=true)
 public int auto(){return display(()->{shell.responseStyle(ResponseStyle.AUTO,false);show();});}
 @Command(name="normal",description="現在のSessionを通常の応答スタイルに戻す",mixinStandardHelpOptions=true)
 public int normal(){return display(()->{shell.responseStyle(ResponseStyle.NORMAL,false);show();});}
 @Command(name="conversation",description="現在のSessionを自然で簡潔な会話スタイルにする",mixinStandardHelpOptions=true)
 public int conversation(@Option(names="--voice-only",arity="0..1",fallbackValue="true",defaultValue="false",description="音声入力だけに会話スタイルを適用する") boolean voiceOnly) {
  return display(()->{shell.responseStyle(ResponseStyle.CONVERSATION,voiceOnly);show();});
 }
 @Command(name="status",description="現在のSessionの応答スタイルを表示する",mixinStandardHelpOptions=true)
 public int status(){return display(this::show);}
 private void show() {
  var metadata=shell.currentMetadata();
  var style=metadata==null?ResponseStyle.AUTO:metadata.responseStyle();
  spec.commandLine().getOut().println("Response mode: "+style.name().toLowerCase(java.util.Locale.ROOT)
      +(style==ResponseStyle.AUTO?" (text: normal, voice: conversation)":metadata!=null&&metadata.voiceOnly()?" (voice only)":" (text and voice)")
      +(metadata==null?"; no active Session":""));
 }
 private int display(Runnable action) {
  try{action.run();return 0;}
  catch(RuntimeException failed){spec.commandLine().getErr().println("Cannot update or read response mode.");return 1;}
  finally{spec.commandLine().getOut().flush();spec.commandLine().getErr().flush();}
 }
}
