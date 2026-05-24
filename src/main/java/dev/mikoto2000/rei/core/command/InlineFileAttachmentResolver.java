package dev.mikoto2000.rei.core.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Resolves inline file attachment markers in chat prompts.
 */
class InlineFileAttachmentResolver {

  private static final Pattern TOKEN_PATTERN = Pattern.compile("(?<!\\\\)`@file:([^`]+)`");
  private static final Pattern ESCAPED_TOKEN_PATTERN = Pattern.compile("\\\\(`@file:[^`]+`)");

  ResolvedPrompt resolve(String input) {
    if (input == null || input.isEmpty()) {
      return new ResolvedPrompt("", List.of(), List.of());
    }

    List<String> warnings = new ArrayList<>();
    List<Media> media = new ArrayList<>();

    Matcher matcher = TOKEN_PATTERN.matcher(input);
    while (matcher.find()) {
      String pathText = matcher.group(1);
      Path path = Path.of(pathText);
      try {
        if (!Files.exists(path)) {
          warnings.add("[warn] 添付ファイルが存在しません: " + pathText);
          continue;
        }
        if (!Files.isReadable(path)) {
          warnings.add("[warn] 添付ファイルを読み取れませんでした: " + pathText + " (not readable)");
          continue;
        }
        MimeType mimeType = detectMimeType(path);
        media.add(new Media(mimeType, new FileSystemResource(path)));
      } catch (Exception e) {
        warnings.add("[warn] 添付ファイルを読み取れませんでした: " + pathText + " (" + e.getMessage() + ")");
      }
    }

    String normalizedInput = ESCAPED_TOKEN_PATTERN.matcher(input).replaceAll("$1");
    return new ResolvedPrompt(normalizedInput, media, warnings);
  }

  private MimeType detectMimeType(Path path) {
    try {
      String contentType = Files.probeContentType(path);
      if (contentType == null || contentType.isBlank()) {
        return MimeTypeUtils.APPLICATION_OCTET_STREAM;
      }
      MimeType parsed = MimeTypeUtils.parseMimeType(contentType);
      return parsed != null ? parsed : MimeTypeUtils.APPLICATION_OCTET_STREAM;
    } catch (Exception e) {
      return MimeTypeUtils.APPLICATION_OCTET_STREAM;
    }
  }

  record ResolvedPrompt(String prompt, List<Media> media, List<String> warnings) {
  }
}
