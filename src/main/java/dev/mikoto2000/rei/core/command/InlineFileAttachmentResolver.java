package dev.mikoto2000.rei.core.command;

import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Inlineファイル添付ファイルの指定を解決し、利用する対象を確定する。
 *
 * <p>Resolves inline attachment markers in chat prompts.
 */
public class InlineFileAttachmentResolver {

  /** ファイルトークン照合規則。 */
  private static final Pattern FILE_TOKEN_PATTERN = Pattern.compile("(?<!\\\\)`@file:([^`]+)`");

  /** クリップボードトークン照合規則。 */
  private static final Pattern CLIPBOARD_TOKEN_PATTERN = Pattern.compile("(?<!\\\\)`@clipboard`");

  /** 特殊文字を保護したトークン照合規則。 */
  private static final Pattern ESCAPED_TOKEN_PATTERN =
      Pattern.compile("\\\\(`@file:[^`]+`|`@clipboard`)");

  /** 画像PNG画像。 */
  private static final MimeType IMAGE_PNG = MimeTypeUtils.parseMimeType("image/png");

  /** クリップボードメディア提供元。 */
  private final ClipboardMediaProvider clipboardMediaProvider;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public InlineFileAttachmentResolver() {
    this(new AwtClipboardMediaProvider());
  }

  /** 指定された値や利用先を使って、InlineFileAttachmentResolverを初期化する。 */
  InlineFileAttachmentResolver(ClipboardMediaProvider clipboardMediaProvider) {
    this.clipboardMediaProvider = clipboardMediaProvider;
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public ResolvedPrompt resolve(String input) {
    if (input == null || input.isEmpty()) {
      return new ResolvedPrompt("", List.of(), List.of());
    }

    List<String> warnings = new ArrayList<>();
    List<Media> media = new ArrayList<>();

    Matcher fileMatcher = FILE_TOKEN_PATTERN.matcher(input);
    while (fileMatcher.find()) {
      String pathText = fileMatcher.group(1);
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
        warnings.add("[warn] 添付ファイルを読み取れませんでした: " + pathText + " (" + buildErrorMessage(e) + ")");
      }
    }

    Matcher clipboardMatcher = CLIPBOARD_TOKEN_PATTERN.matcher(input);
    while (clipboardMatcher.find()) {
      try {
        media.add(clipboardMediaProvider.getClipboardMedia());
      } catch (Exception e) {
        warnings.add("[warn] クリップボード内容を添付できませんでした: " + buildErrorMessage(e));
      }
    }

    String normalizedInput = ESCAPED_TOKEN_PATTERN.matcher(input).replaceAll("$1");
    return new ResolvedPrompt(normalizedInput, media, warnings);
  }

  /** 共通の実行基盤で用いる検出データの種類種別を返す。 */
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

  /** 利用先に渡せるよう、エラーメッセージを組み立てる。 */
  private String buildErrorMessage(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }

  /** クリップボードメディアを用途や設定に応じて提供する窓口。 */
  interface ClipboardMediaProvider {
    /** 共通の実行基盤で参照するクリップボードメディアを返す。 */
    Media getClipboardMedia() throws Exception;
  }

  /** Awtクリップボードメディアを用途や設定に応じて提供する窓口。 */
  static class AwtClipboardMediaProvider implements ClipboardMediaProvider {
    /** 共通の実行基盤で参照するクリップボードメディアを返す。 */
    @Override
    public Media getClipboardMedia() throws Exception {
      try {
        Transferable transferable =
            Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        if (transferable == null) {
          throw new IllegalStateException("clipboard is empty");
        }

        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
          String text = (String) transferable.getTransferData(DataFlavor.stringFlavor);
          if (text == null) {
            throw new IllegalStateException("clipboard text is null");
          }
          return new Media(
              MimeTypeUtils.TEXT_PLAIN,
              new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        }

        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
          Image image = (Image) transferable.getTransferData(DataFlavor.imageFlavor);
          if (image == null) {
            throw new IllegalStateException("clipboard image is null");
          }
          BufferedImage buffered = toBufferedImage(image);
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          ImageIO.write(buffered, "png", baos);
          return new Media(IMAGE_PNG, new ByteArrayResource(baos.toByteArray()));
        }
      } catch (HeadlessException | IllegalStateException e) {
        Media fallback = tryReadFromWindowsClipboard();
        if (fallback != null) {
          return fallback;
        }
        throw e;
      }

      Media fallback = tryReadFromWindowsClipboard();
      if (fallback != null) {
        return fallback;
      }
      throw new IllegalStateException("unsupported clipboard content type");
    }

    /** 共通の実行基盤で用いる試行読み取り起点Windowsクリップボードを返す。 */
    private Media tryReadFromWindowsClipboard() {
      String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (!osName.contains("windows")) {
        return null;
      }

      String imageBase64 =
          runPowerShell(
              "$img = Get-Clipboard -Format Image -ErrorAction SilentlyContinue; "
                  + "if ($null -ne $img) { "
                  + "  $ms = New-Object System.IO.MemoryStream; "
                  + "  $img.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); "
                  + "  [Convert]::ToBase64String($ms.ToArray()) "
                  + "}");
      if (imageBase64 != null && !imageBase64.isBlank()) {
        byte[] bytes = Base64.getDecoder().decode(imageBase64.trim());
        return new Media(IMAGE_PNG, new ByteArrayResource(bytes));
      }

      String text =
          runPowerShell(
              "Get-Clipboard -Raw -TextFormatType UnicodeText -ErrorAction SilentlyContinue");
      if (text != null && !text.isBlank()) {
        return new Media(
            MimeTypeUtils.TEXT_PLAIN, new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
      }
      return null;
    }

    /** 依頼された操作を進めるため、Powerシェルを実行する。 */
    private String runPowerShell(String script) {
      ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script);
      try {
        Process process = pb.start();
        try (InputStream in = process.getInputStream()) {
          byte[] output = in.readAllBytes();
          int exit = process.waitFor();
          if (exit != 0) {
            return null;
          }
          return new String(output, StandardCharsets.UTF_8).trim();
        }
      } catch (Exception e) {
        return null;
      }
    }

    /** 利用先へ渡せるよう、一時保持中画像の形式に変換する。 */
    private BufferedImage toBufferedImage(Image image) {
      if (image instanceof BufferedImage bufferedImage) {
        return bufferedImage;
      }
      BufferedImage converted =
          new BufferedImage(
              image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
      converted.getGraphics().drawImage(image, 0, 0, null);
      return converted;
    }
  }

  /**
   * 共通の実行基盤で受け渡す確定したプロンプトを表す。
   *
   * @param prompt プロンプト
   * @param media メディア
   * @param warnings 注意事項
   */
  public record ResolvedPrompt(String prompt, List<Media> media, List<String> warnings) {}
}
