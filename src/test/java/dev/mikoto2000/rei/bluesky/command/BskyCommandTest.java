package dev.mikoto2000.rei.bluesky.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.bluesky.BlueskyPostResult;
import dev.mikoto2000.rei.bluesky.BlueskyPostService;
import picocli.CommandLine;

class BskyCommandTest {

  @Test
  void replyCommandDelegatesToService() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.reply("at://did:plc:x/app.bsky.feed.post/abc", "hello world"))
        .thenReturn(new BlueskyPostResult(true, "Bluesky reply created", "at://did:plc:me/app.bsky.feed.post/r1",
            "https://bsky.app/profile/did:plc:me/post/r1"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("reply", "at://did:plc:x/app.bsky.feed.post/abc", "hello", "world");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(service).reply("at://did:plc:x/app.bsky.feed.post/abc", "hello world");
    String output = out.toString();
    assertTrue(output.contains("Bluesky reply created"));
    assertTrue(output.contains("postUri: at://did:plc:me/app.bsky.feed.post/r1"));
  }

  @Test
  void replyCommandUsesAutoGenerationWhenTextOmitted() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.reply("at://did:plc:x/app.bsky.feed.post/abc"))
        .thenReturn(new BlueskyPostResult(true, "Bluesky reply created", "at://did:plc:me/app.bsky.feed.post/r2",
            "https://bsky.app/profile/did:plc:me/post/r2"));

    int exitCode = newCommand(service).execute("reply", "at://did:plc:x/app.bsky.feed.post/abc");

    assertEquals(0, exitCode);
    verify(service).reply("at://did:plc:x/app.bsky.feed.post/abc");
  }

  private CommandLine newCommand(BlueskyPostService service) {
    return new CommandLine(new BskyCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == BskyCommand.ReplyCommand.class) {
          return cls.cast(new BskyCommand.ReplyCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
