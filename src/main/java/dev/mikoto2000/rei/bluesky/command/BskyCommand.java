package dev.mikoto2000.rei.bluesky.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import lombok.RequiredArgsConstructor;
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

  @Component
  @RequiredArgsConstructor
  @Command(name = "reply", description = "指定した投稿にリプライします")
  public static class ReplyCommand implements Runnable {

    private final BlueskyPostService blueskyPostService;

    @Parameters(index = "0", paramLabel = "TARGET_POST", description = "リプライ対象投稿の at:// URI または bsky.app URL")
    String targetPost;

    @Parameters(index = "1..*", arity = "0..*", paramLabel = "TEXT", description = "リプライ本文（省略時はAI生成）")
    String[] textParts;

    @Override
    public void run() {
      BlueskyPostResult result = textParts == null || textParts.length == 0
          ? blueskyPostService.reply(targetPost)
          : blueskyPostService.reply(targetPost, String.join(" ", textParts));
      if (!result.success()) {
        System.out.println(result.message());
        return;
      }
      System.out.println(result.message());
      System.out.println("postUri: " + result.postUri());
      if (result.postUrl() != null) {
        System.out.println("postUrl: " + result.postUrl());
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "history-users", description = "会話履歴があるユーザー一覧を表示します")
  public static class HistoryUsersCommand implements Runnable {

    private final BlueskyReplyConversationRepository conversationRepository;

    @Override
    public void run() {
      var handles = conversationRepository.listHandles();
      if (handles.isEmpty()) {
        System.out.println("会話履歴のあるユーザーはありません");
        return;
      }
      handles.forEach(System.out::println);
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "history", description = "指定ユーザーとの会話履歴を表示します")
  public static class HistoryCommand implements Runnable {

    private final BlueskyReplyConversationRepository conversationRepository;

    @Parameters(index = "0", paramLabel = "HANDLE", description = "対象ユーザーの handle")
    String handle;

    @Option(names = "--limit", defaultValue = "20", description = "表示件数（既定: ${DEFAULT-VALUE}）")
    int limit;

    @Override
    public void run() {
      var messages = conversationRepository.findRecent(handle, limit);
      if (messages.isEmpty()) {
        System.out.println("対象ユーザーの会話履歴はありません: " + handle);
        return;
      }
      messages.forEach(message -> System.out.println(
          message.createdAt() + " | " + message.role() + " | " + message.content()));
    }
  }
}
