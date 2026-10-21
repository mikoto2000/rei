package dev.mikoto2000.rei.workcontext;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 作業文脈の引き継ぎに必要な作業文脈Gitを扱う。
 *
 * <p>Optional read-only work-time metadata. Git failure never prevents reading a handoff.
 */
@Component
public class WorkContextGit {
  /** 後から状況を確認できるよう、作業文脈Gitを記録する。 */
  public WorkContext.GitState capture(Path root, Instant now) {
    return new WorkContext.GitState(
        root.toString(), read(root, "--abbrev-ref", "HEAD"), read(root, "HEAD"), now);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private String read(Path root, String... args) {
    Process process = null;
    try {
      var command =
          new java.util.ArrayList<>(java.util.List.of("git", "-C", root.toString(), "rev-parse"));
      command.addAll(java.util.List.of(args));
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(2, TimeUnit.SECONDS)) return null;
      if (process.exitValue() != 0) return null;
      String value =
          new String(
                  process.getInputStream().readNBytes(1024),
                  java.nio.charset.StandardCharsets.UTF_8)
              .strip();
      return value.isBlank() ? null : value;
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    } catch (java.io.IOException error) {
      return null;
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }
}
