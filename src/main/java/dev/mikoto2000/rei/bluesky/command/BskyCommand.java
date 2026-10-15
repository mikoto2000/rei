package dev.mikoto2000.rei.bluesky.command;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
  public static String reportReply(
      BlueskyPostService service, String target, java.util.List<String> parts) {
    var command = new ReplyCommand(service);
    command.targetPost = target;
    command.textParts = parts.toArray(String[]::new);
    return report(command);
  }

  public static String reportHistoryUsers(BlueskyReplyConversationRepository repository) {
    return report(new HistoryUsersCommand(repository));
  }

  public static String reportHistory(
      BlueskyReplyConversationRepository repository, String handle, int limit) {
    var command = new HistoryCommand(repository);
    command.handle = handle;
    command.limit = limit;
    return report(command);
  }

  private static String report(ReportCommand command) {
    var text = new java.io.StringWriter();
    command.out = new java.io.PrintWriter(text, true);
    command.run();
    return text.toString().replaceAll("[\\r\\n]+$", "");
  }

  private abstract static class ReportCommand implements Runnable {
    protected java.io.PrintWriter out = new java.io.PrintWriter(System.out, true);
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "reply", description = "指定した投稿にリプライします")
  public static class ReplyCommand extends ReportCommand {

    private final BlueskyPostService blueskyPostService;

    @Parameters(
        index = "0",
        paramLabel = "TARGET_POST",
        description = "リプライ対象投稿の at:// URI または bsky.app URL")
    String targetPost;

    @Parameters(
        index = "1..*",
        arity = "0..*",
        paramLabel = "TEXT",
        description = "リプライ本文（省略時はAI生成）")
    String[] textParts;

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

  @Component
  @RequiredArgsConstructor
  @Command(name = "history-users", description = "会話履歴があるユーザー一覧を表示します")
  public static class HistoryUsersCommand extends ReportCommand {

    private final BlueskyReplyConversationRepository conversationRepository;

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

  @Component
  @RequiredArgsConstructor
  @Command(name = "history", description = "指定ユーザーとの会話履歴を表示します")
  public static class HistoryCommand extends ReportCommand {

    private final BlueskyReplyConversationRepository conversationRepository;

    @Parameters(index = "0", paramLabel = "HANDLE", description = "対象ユーザーの handle")
    String handle;

    @Option(names = "--limit", defaultValue = "20", description = "表示件数（既定: ${DEFAULT-VALUE}）")
    int limit;

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
