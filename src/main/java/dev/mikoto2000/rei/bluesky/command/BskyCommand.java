package dev.mikoto2000.rei.bluesky.command;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Bluesky投稿コマンドを行うための利用者コマンド。 */
@Component
@Command(
    name = "bsky",
    description = "Bluesky投稿コマンド",
    subcommands = {
      BskyCommand.ReplyCommand.class,
      BskyCommand.HistoryUsersCommand.class,
      BskyCommand.HistoryCommand.class
    })
public class BskyCommand {
  /** Blueskyとの連携で用いるレポート返信を返す。 */
  public static String reportReply(
      BlueskyPostService service, String target, java.util.List<String> parts) {
    var command = new ReplyCommand(service);
    command.targetPost = target;
    command.textParts = parts.toArray(String[]::new);
    return report(command);
  }

  /** Blueskyとの連携で用いるレポート履歴利用者一覧を返す。 */
  public static String reportHistoryUsers(BlueskyReplyConversationRepository repository) {
    return report(new HistoryUsersCommand(repository));
  }

  /** Blueskyとの連携で用いるレポート履歴を返す。 */
  public static String reportHistory(
      BlueskyReplyConversationRepository repository, String handle, int limit) {
    var command = new HistoryCommand(repository);
    command.handle = handle;
    command.limit = limit;
    return report(command);
  }

  /** Blueskyとの連携で用いるレポートを返す。 */
  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  /** Blueskyとの連携のうち、レポートの操作を利用者コマンドとして受け付ける。 */
  private abstract static class ReportCommand implements Runnable {
    /** 出力先。 */
    protected java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);
  }

  /** 指定した投稿にリプライしますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "reply", description = "指定した投稿にリプライします")
  public static class ReplyCommand extends ReportCommand {

    /** Bluesky投稿サービス。 */
    private final BlueskyPostService blueskyPostService;

    /** 対象投稿。 */
    @Parameters(
        index = "0",
        paramLabel = "TARGET_POST",
        description = "リプライ対象投稿の at:// URI または bsky.app URL")
    String targetPost;

    /** テキスト部分一覧。 */
    @Parameters(
        index = "1..*",
        arity = "0..*",
        paramLabel = "TEXT",
        description = "リプライ本文（省略時はAI生成）")
    String[] textParts;

    /** 指定した投稿にリプライしますを行う。 */
    @Override
    public void run() {
      BlueskyPostResult result =
          textParts == null || textParts.length == 0
              ? blueskyPostService.reply(targetPost)
              : blueskyPostService.reply(targetPost, String.join(" ", textParts));
      if (!result.success()) {
        out.println(result.message());
        return;
      }
      out.println(result.message());
      out.println("postUri: " + result.postUri());
      if (result.postUrl() != null) {
        out.println("postUrl: " + result.postUrl());
      }
    }
  }

  /** 会話履歴があるユーザー一覧を表示しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "history-users", description = "会話履歴があるユーザー一覧を表示します")
  public static class HistoryUsersCommand extends ReportCommand {

    /** 会話保存と検索の窓口。 */
    private final BlueskyReplyConversationRepository conversationRepository;

    /** 会話履歴があるユーザー一覧を表示しますを行う。 */
    @Override
    public void run() {
      var handles = conversationRepository.listHandles();
      if (handles.isEmpty()) {
        out.println("会話履歴のあるユーザーはありません");
        return;
      }
      handles.forEach(out::println);
    }
  }

  /** 指定ユーザーとの会話履歴を表示しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "history", description = "指定ユーザーとの会話履歴を表示します")
  public static class HistoryCommand extends ReportCommand {

    /** 会話保存と検索の窓口。 */
    private final BlueskyReplyConversationRepository conversationRepository;

    /** 操作対象。 */
    @Parameters(index = "0", paramLabel = "HANDLE", description = "対象ユーザーの handle")
    String handle;

    /** 上限。 */
    @Option(names = "--limit", defaultValue = "20", description = "表示件数（既定: ${DEFAULT-VALUE}）")
    int limit;

    /** 指定ユーザーとの会話履歴を表示しますを行う。 */
    @Override
    public void run() {
      var messages = conversationRepository.findRecent(handle, limit);
      if (messages.isEmpty()) {
        out.println("対象ユーザーの会話履歴はありません: " + handle);
        return;
      }
      messages.forEach(
          message ->
              out.println(
                  message.createdAt() + " | " + message.role() + " | " + message.content()));
    }
  }
}
