package dev.mikoto2000.rei.terminal;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.completion.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * 承認判定期間仮想端末の結果と状態の契約を検証するテスト。
 *
 * <p>Real ConPTY presentation with synthetic requests; never dispatches a command or model.
 */
@EnabledOnOs(OS.WINDOWS)
class ApprovalWindowsPtyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う識別子。 */
  private static final String ID = "ca880320-1482-4d84-8fb9-53c0b3234997";

  /** 状態およびログ一覧について、続きの取得位置を保持することを確認する。 */
  @Test
  void statusAndLogsPreserveCursorAndExistingArrowMenu() throws Exception {
    var argv =
        List.of(
            Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Fixture.class.getName());
    try (var pty = new WindowsPty(argv, directory)) {
      pty.await("[APPROVAL REQUIRED]");
      pty.await("[APPROVAL PENDING: 1]");
      pty.send("draft\033[D\033[D");
      pty.await("fixture-stream-two");
      pty.await("[APPROVAL PENDING: 2]");
      pty.send("X\r");
      pty.await("INPUT=draXft");
      pty.send("\033[B\r");
      pty.await("MENU=/approval show " + ID);
      pty.await("RESOLVED=0");
      assertEquals(0, pty.exitCode());
    }
  }

  /** 検証条件を再現するための検証条件。 */
  public static final class Fixture {
    /** 検証用に、軽量CLIの起動の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) throws Exception {
      var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
      var input = kernel.CreateFile("CONIN$", 0x80000000 | 0x40000000, 3, null, 3, 0, null);
      var output = kernel.CreateFile("CONOUT$", 0x80000000 | 0x40000000, 3, null, 3, 0, null);
      if (!kernel.SetStdHandle(-10, input)
          || !kernel.SetStdHandle(-11, output)
          || !kernel.SetStdHandle(-12, output))
        throw new IOException("Console handles unavailable");
      var handle = FileDescriptor.class.getDeclaredField("handle");
      handle.setAccessible(true);
      handle.setLong(FileDescriptor.in, com.sun.jna.Pointer.nativeValue(input.getPointer()));
      handle.setLong(FileDescriptor.out, com.sun.jna.Pointer.nativeValue(output.getPointer()));
      handle.setLong(FileDescriptor.err, com.sun.jna.Pointer.nativeValue(output.getPointer()));
      System.setIn(new FileInputStream("CONIN$"));
      System.setOut(
          new PrintStream(
              new FileOutputStream("CONOUT$"), true, java.nio.charset.StandardCharsets.UTF_8));
      System.setErr(System.out);
      try (var terminal = org.jline.terminal.TerminalBuilder.builder().system(true).build()) {
        if (!ApprovalDisplayState.interactive(terminal))
          throw new IOException("Native interactive terminal required");
        var reader = new ApprovalMenuLineReader(terminal);
        var owner = new ApprovalActionMenu.Owner("fixture", "session");
        try (var menu = new ApprovalActionMenu(reader, () -> owner)) {
          menu.enabled(true);
          menu.offer(owner.project(), owner.session(), ID);
          reader.printAbove(
              ApprovalNotice.format(
                  "runCommand\nfixture operation\n/approval approve " + ID + " --continue"));
          reader
              .getWidgets()
              .put(
                  "fixture-stream",
                  () -> {
                    if (reader.getBuffer().toString().equals("draft")
                        && reader.getBuffer().cursor() == 3) {
                      reader.printAbove("fixture-stream-one");
                      reader.printAbove("fixture-stream-two");
                      menu.reconcile(owner, List.of(ID, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
                    }
                    return true;
                  });
          var ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
          ticker.scheduleWithFixedDelay(
              () -> {
                if (reader.isReading())
                  try {
                    reader.callWidget("fixture-stream");
                  } catch (IllegalStateException ended) {
                  }
              },
              20,
              20,
              java.util.concurrent.TimeUnit.MILLISECONDS);
          try {
            String value = reader.readLine("fixture> ");
            reader.printAbove("INPUT=" + value);
          } finally {
            ticker.shutdownNow();
          }
          reader.printAbove("MENU=" + reader.readLine("fixture-menu> "));
          menu.reconcile(owner, List.of());
          reader.printAbove("RESOLVED=" + menu.pendingCount());
        }
      }
    }
  }
}
