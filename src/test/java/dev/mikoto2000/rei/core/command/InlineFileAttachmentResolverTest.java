package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

class InlineFileAttachmentResolverTest {

  @TempDir
  Path tempDir;

  @Test
  void resolveKeepsFileTokenAndAddsMediaAttachment() throws Exception {
    Path file = tempDir.resolve("memo.txt");
    Files.writeString(file, "hello file");

    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver(() ->
        new Media(MimeTypeUtils.TEXT_PLAIN, new org.springframework.core.io.ByteArrayResource("clip".getBytes(StandardCharsets.UTF_8))));

    String input = "please read `@file:" + file + "`";
    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve(input);

    assertTrue(resolved.prompt().contains("please read `@file:"));
    assertEquals(1, resolved.media().size());
    assertEquals("hello file", new String(resolved.media().getFirst().getDataAsByteArray(), StandardCharsets.UTF_8));
    assertTrue(resolved.warnings().isEmpty());
  }

  @Test
  void resolveClipboardAddsTextMedia() {
    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver(() ->
        new Media(MimeTypeUtils.TEXT_PLAIN, new org.springframework.core.io.ByteArrayResource("clip text".getBytes(StandardCharsets.UTF_8))));

    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve("use `@clipboard` now");

    assertTrue(resolved.prompt().contains("`@clipboard`"));
    assertEquals(1, resolved.media().size());
    assertEquals("clip text", new String(resolved.media().getFirst().getDataAsByteArray(), StandardCharsets.UTF_8));
    assertTrue(resolved.warnings().isEmpty());
  }

  @Test
  void resolveClipboardCanAddImageMedia() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    byte[] pngBytes = out.toByteArray();

    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver(() ->
        new Media(MimeTypeUtils.parseMimeType("image/png"), new org.springframework.core.io.ByteArrayResource(pngBytes)));

    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve("img `@clipboard`");

    assertEquals(1, resolved.media().size());
    assertTrue(resolved.media().getFirst().getDataAsByteArray().length > 0);
    assertEquals("image/png", resolved.media().getFirst().getMimeType().toString());
  }

  @Test
  void resolveUnescapesEscapedTokensAndDoesNotAttach() {
    InlineFileAttachmentResolver resolver = new InlineFileAttachmentResolver(() -> {
      throw new IllegalStateException("must not call");
    });

    InlineFileAttachmentResolver.ResolvedPrompt resolved = resolver.resolve("\\`@file:path/to/file.txt` and \\`@clipboard`");

    assertEquals("`@file:path/to/file.txt` and `@clipboard`", resolved.prompt());
    assertTrue(resolved.media().isEmpty());
    assertTrue(resolved.warnings().isEmpty());
  }
}
