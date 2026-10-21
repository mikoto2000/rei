package dev.mikoto2000.rei.externalagent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 外部エージェントへの委譲に必要な外部エージェント取得元状態の記録を扱う。
 *
 * <p>Provider-independent bounded source data and exact hash evidence.
 */
final class ExternalAgentSourceSnapshot {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalAgentSourceSnapshot() {}

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  private static void check(BooleanSupplier cancelled, long deadline) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
    if (System.nanoTime() >= deadline) throw new IOException("Snapshot deadline reached");
  }

  /** 外部エージェントへの委譲において、対象外に該当するか判定する。 */
  static boolean excluded(Path relative) {
    for (var part : relative) {
      String name = part.toString().toLowerCase(Locale.ROOT);
      if (Set.of(
                  ".rei",
                  ".storage",
                  ".git",
                  ".claude",
                  ".codex",
                  ".agents",
                  ".aws",
                  ".m2",
                  "target",
                  "build",
                  "node_modules")
              .contains(name)
          || name.matches(
              "\\.env(?:\\..*)?|credentials(?:[._].*)?|secrets(?:[._].*)?|.*\\.(key|pem)"))
        return true;
    }
    return false;
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  static byte[] read(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536)
      throw new IOException();
    try (var stream = Files.newInputStream(file)) {
      var bytes = stream.readNBytes(65537);
      if (bytes.length > 65536) throw new IOException();
      return bytes;
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  static List<Map<String, Object>> snapshot(
      Path root, Path target, BooleanSupplier cancelled, long deadline) throws IOException {
    var files = new ArrayList<Map<String, Object>>();
    var visited = new int[] {0};
    var total = new int[] {0};
    Path selected = target == null ? root : target;
    if (excluded(root.relativize(selected))) throw new IOException();
    Files.walkFileTree(
        selected,
        EnumSet.noneOf(FileVisitOption.class),
        16,
        new SimpleFileVisitor<>() {
          /** 外部エージェントへの委譲に関する対象の確認を処理する。 */
          private void visit(Path path) throws IOException {
            check(cancelled, deadline);
            if (++visited[0] > 512 || !path.toRealPath().startsWith(root)) throw new IOException();
          }

          /** 外部エージェントへの委譲で用いるpre対象の確認ディレクトリを返す。 */
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
              throws IOException {
            visit(directory);
            return excluded(root.relativize(directory))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          /** 外部エージェントへの委譲で用いる対象の確認ファイルを返す。 */
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            visit(file);
            if (excluded(root.relativize(file))) return FileVisitResult.CONTINUE;
            if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException();
            var bytes = read(file);
            total[0] += bytes.length;
            if (files.size() >= 32 || total[0] > 262144) throw new IOException();
            String text;
            try {
              text =
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .onMalformedInput(CodingErrorAction.REPORT)
                      .onUnmappableCharacter(CodingErrorAction.REPORT)
                      .decode(ByteBuffer.wrap(bytes))
                      .toString();
            } catch (CharacterCodingException invalid) {
              throw new IOException();
            }
            if (text.indexOf('\0') >= 0) throw new IOException();
            int line = 1, start = 0;
            for (int i = 0; i < text.length(); i++)
              if (text.charAt(i) == '\n') {
                line++;
                start = i + 1;
              }
            files.add(
                Map.of(
                    "path",
                    root.relativize(file).toString(),
                    "sha256",
                    hash(bytes),
                    "text",
                    dev.mikoto2000.rei.event.CredentialRedactor.redact(text),
                    "sourceLength",
                    text.length(),
                    "sourceEndLine",
                    line,
                    "sourceEndOffset",
                    text.length() - start,
                    "sourceLineCount",
                    line));
            return FileVisitResult.CONTINUE;
          }
        });
    if (files.isEmpty()) throw new IOException();
    files.sort(Comparator.comparing(file -> (String) file.get("path")));
    return List.copyOf(files);
  }
}
