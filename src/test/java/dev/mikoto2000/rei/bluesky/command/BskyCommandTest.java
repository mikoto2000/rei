package dev.mikoto2000.rei.bluesky.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import dev.mikoto2000.rei.bluesky.BlueskyReplyConversationRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Bskyコマンドの結果と状態の契約を検証するテスト。 */
class BskyCommandTest {

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void replyCommandDelegatesToService() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.reply("at://did:plc:x/app.bsky.feed.post/abc", "hello world"))
        .thenReturn(
            new BlueskyPostResult(
                true,
                "Bluesky reply created",
                "at://did:plc:me/app.bsky.feed.post/r1",
                "https://bsky.app/profile/did:plc:me/post/r1"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode =
          newCommand(service)
              .execute("reply", "at://did:plc:x/app.bsky.feed.post/abc", "hello", "world");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).reply("at://did:plc:x/app.bsky.feed.post/abc", "hello world");
    String output = out.toString();
    assertTrue(output.contains("Bluesky reply created"));
    assertTrue(output.contains("postUri: at://did:plc:me/app.bsky.feed.post/r1"));
  }

  /** 返信コマンドについて、自動生成を利用することを確認する。 */
  @Test
  void replyCommandUsesAutoGenerationWhenTextOmitted() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.reply("at://did:plc:x/app.bsky.feed.post/abc"))
        .thenReturn(
            new BlueskyPostResult(
                true,
                "Bluesky reply created",
                "at://did:plc:me/app.bsky.feed.post/r2",
                "https://bsky.app/profile/did:plc:me/post/r2"));

    int exitCode = newCommand(service).execute("reply", "at://did:plc:x/app.bsky.feed.post/abc");

    assertEquals(0, exitCode);
    verify(service).reply("at://did:plc:x/app.bsky.feed.post/abc");
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void historyUsersCommandPrintsHandles() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    BlueskyReplyConversationRepository repository = mock(BlueskyReplyConversationRepository.class);
    when(repository.listHandles())
        .thenReturn(java.util.List.of("alice.bsky.social", "bob.bsky.social"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service, repository).execute("history-users");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }
    assertTrue(out.toString().contains("alice.bsky.social"));
    assertTrue(out.toString().contains("bob.bsky.social"));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void historyCommandPrintsConversation() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    BlueskyReplyConversationRepository repository = mock(BlueskyReplyConversationRepository.class);
    when(repository.findRecent("alice.bsky.social", 2))
        .thenReturn(
            java.util.List.of(
                new BlueskyReplyConversationRepository.ConversationMessage(
                    "user", "hello", java.time.OffsetDateTime.parse("2026-06-01T03:00:00+09:00")),
                new BlueskyReplyConversationRepository.ConversationMessage(
                    "assistant",
                    "hi",
                    java.time.OffsetDateTime.parse("2026-06-01T03:01:00+09:00"))));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode =
          newCommand(service, repository).execute("history", "alice.bsky.social", "--limit", "2");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }
    assertTrue(out.toString().contains("user | hello"));
    assertTrue(out.toString().contains("assistant | hi"));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(BlueskyPostService service) {
    return newCommand(service, mock(BlueskyReplyConversationRepository.class));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(
      BlueskyPostService service, BlueskyReplyConversationRepository repository) {
    return new CommandLine(
        new BskyCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls == BskyCommand.ReplyCommand.class) {
              return cls.cast(new BskyCommand.ReplyCommand(service));
            }
            if (cls == BskyCommand.HistoryUsersCommand.class) {
              return cls.cast(new BskyCommand.HistoryUsersCommand(repository));
            }
            if (cls == BskyCommand.HistoryCommand.class) {
              return cls.cast(new BskyCommand.HistoryCommand(repository));
            }
            return CommandLine.defaultFactory().create(cls);
          }
        });
  }
}
