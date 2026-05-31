package dev.mikoto2000.rei.bluesky.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "bsky",
    description = "Bluesky投稿コマンド",
    subcommands = {
      BskyCommand.ReplyCommand.class
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
}
