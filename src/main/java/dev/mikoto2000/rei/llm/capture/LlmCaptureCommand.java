package dev.mikoto2000.rei.llm.capture;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name="llm",description="LLM request capture",subcommands=LlmCaptureCommand.Capture.class,mixinStandardHelpOptions=true)
public final class LlmCaptureCommand implements Runnable {
  private final CaptureStore store;
  private final Supplier<Object> client;
  private final Supplier<String> conversation;
  private final Runnable ensureConversation;
  private PrintWriter shellOutput;
  private Predicate<String> confirmation=prompt->false;
  @Spec private CommandSpec spec;
  public LlmCaptureCommand(){this(null,()->null,()->null,()->{});}
  @org.springframework.beans.factory.annotation.Autowired
  public LlmCaptureCommand(CaptureStore store,dev.mikoto2000.rei.core.project.ProjectService projects,
      dev.mikoto2000.rei.application.session.ShellConversationService conversations){
    this(store,projects::currentClient,conversations::currentSessionId,()->{if(conversations.currentSessionId()==null)conversations.newConversation();});
  }
  public LlmCaptureCommand(CaptureStore store,Supplier<Object> client,Supplier<String> conversation,Runnable ensureConversation){this.store=store;this.client=client;this.conversation=conversation;this.ensureConversation=ensureConversation;}
  public void setShellOutput(PrintWriter output){shellOutput=output;}
  public void setConfirmation(Predicate<String> confirmation){this.confirmation=Objects.requireNonNull(confirmation);}
  private PrintWriter out(){return shellOutput==null?spec.commandLine().getOut():shellOutput;}
  public void run(){spec.commandLine().usage(out());}
  @Command(name="capture",description="次のCLI依頼のHTTP本文をJVMメモリへ記録",mixinStandardHelpOptions=true)
  public static final class Capture implements java.util.concurrent.Callable<Integer> {
    @ParentCommand LlmCaptureCommand parent;
    @Parameters(arity="0..*",paramLabel="ACTION [ID] [PATH]") String[] args;
    public Integer call(){return parent.execute(args==null?new String[0]:args);}
  }
  private int execute(String[] args){
    var out=out();
    try{
      if(args.length==0){out.println("/llm capture next|status|off|list [run-id]|show <attempt-id>|raw <attempt-id>|export <attempt-id> <path>|delete <run-id>|clear");return 0;}
      switch(args[0]){
        case "next" -> {arity(args,1);ensureConversation.run();store.reserve(client.get(),conversation.get());out.println("次のこのCLI会話の依頼を記録します。本文には秘密情報が含まれる可能性があります。");}
        case "status" -> {arity(args,1);out.println(store.status(client.get(),conversation.get()));}
        case "off" -> {arity(args,1);out.println(store.off(client.get(),conversation.get())?"予約を取り消しました。":"この会話の未消費予約はありません。進行中の記録は継続します。");}
        case "list" -> {if(args.length==1)store.sessions().forEach(s->out.println(s));else{arity(args,2);store.attempts(args[1]).forEach(a->out.println(a));}}
        case "show" -> {arity(args,2);var a=store.attempt(args[1]);out.println(a);out.println("マスキングは完全性を保証しません。");
          if(a.captureState().equals("COMPLETE"))out.println(CaptureDisplay.masked(store.body(a.attemptId())));else out.println("完全な原本はありません: "+a.reason());}
        case "raw" -> {arity(args,2);var a=store.attempt(args[1]);requireComplete(a);if(!confirm())return 2;
          out.println("UTF-8復号・制御文字エスケープ済み表示です。完全なバイト表現ではありません。");out.println(CaptureDisplay.escape(new String(store.body(a.attemptId()),StandardCharsets.UTF_8)));}
        case "export" -> {arity(args,3);var a=store.attempt(args[1]);requireComplete(a);if(!confirm())return 2;
          export(store.body(a.attemptId()),Path.of(args[2]));out.println("原本バイト列を保存しました。出力ファイルはcaptureの削除対象外です。");}
        case "delete" -> {arity(args,2);if(!store.delete(args[1]))throw new IllegalArgumentException("Unknown or ambiguous Run ID");out.println("Run記録を削除しました。");}
        case "clear" -> {arity(args,1);store.clear();out.println("予約と全記録を削除しました。");}
        default -> throw new IllegalArgumentException("Unknown capture action");
      }
      return 0;
    }catch(IllegalArgumentException|IllegalStateException error){out.println(CaptureDisplay.escape(error.getMessage()));return 2;}
    catch(IOException error){out.println("原本の表示・保存に失敗しました。ファイルは上書きしません。");return 2;}
    catch(RuntimeException error){out.println("Capture操作に失敗しました。");return 2;}
    finally{out.flush();}
  }
  private boolean confirm(){return confirmation.test("秘密情報を含む未加工本文を表示・保存します。端末履歴・保存後のコピーは削除対象外です。続行しますか? [y/N] ");}
  private static void requireComplete(CaptureStore.AttemptInfo attempt){if(!attempt.captureState().equals("COMPLETE"))throw new IllegalArgumentException("Complete original unavailable");}
  private static void arity(String[] args,int length){if(args.length!=length)throw new IllegalArgumentException("Invalid capture arguments");}
  static void export(byte[] bytes,Path requested) throws IOException {
    for(Path part:requested)if(part.toString().equals(".."))throw new IOException("Parent traversal rejected");
    var target=requested.toAbsolutePath().normalize();var parent=target.getParent();
    if(parent==null||!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("Parent unavailable");
    for(var current=parent;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("Symbolic parent rejected");
    if(!parent.toRealPath().equals(parent))throw new IOException("Aliased parent rejected");
    var options=Set.<OpenOption>of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    try(var channel=Files.newByteChannel(target,options)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);}
  }
}
