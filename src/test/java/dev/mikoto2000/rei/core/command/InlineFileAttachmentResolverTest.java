package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InlineFileAttachmentResolverTest {

  @TempDir
  Path tempDir;

  @Test
  void resolveKeepsTokenAndAddsMediaAttachment() throws Exception {
    Path file = tempDir.resolve("memo.txt");
    Files.writeString(file, "hello file");

    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver();
    String input = "please read `@file:" + file.toString() + "`";
    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve(input);

    assertTrue(resolved.prompt().contains("please read `@file:"));
    assertEquals(1, resolved.media().size());
    assertEquals("hello file", new String(resolved.media().getFirst().getDataAsByteArray()));
    assertTrue(resolved.warnings().isEmpty());
  }

  @Test
  void resolveUnescapesEscapedTokenAndDoesNotAttach() {
    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver();
    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve("\\`@file:path/to/file.txt`");

    assertEquals("`@file:path/to/file.txt`", resolved.prompt());
    assertTrue(resolved.media().isEmpty());
    assertTrue(resolved.warnings().isEmpty());
  }
}
