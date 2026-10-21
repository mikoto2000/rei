package dev.mikoto2000.rei.terminal;

import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinBase.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 軽量CLIの起動に必要な独立起動バックエンドを扱う。
 *
 * <p>Explicit OS detachment. Never falls back to a child tied to the launcher's console/job.
 */
public final class DetachedBackend {
  /** ランチャーの終了に巻き込まれない独立したバックエンドを起動する。 */
  public static long start(List<String> command, Path directory, Path log) throws IOException {
    Files.createDirectories(log.getParent());
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      var startup = new STARTUPINFO();
      startup.cb = new WinDef.DWORD(startup.size());
      var process = new PROCESS_INFORMATION();
      String arguments =
          command.stream()
              .map(DetachedBackend::quote)
              .collect(java.util.stream.Collectors.joining(" "));
      // バックエンドを端末や親のジョブから独立させ、ランチャーを閉じても継続できるようにする。
      boolean created =
          Kernel32.INSTANCE.CreateProcess(
              command.getFirst(),
              arguments,
              null,
              null,
              false,
              new WinDef.DWORD(0x01000000 | 0x00000008 | 0x00000200),
              null,
              directory.toString(),
              startup,
              process);
      if (!created)
        throw new IOException(
            "Windows detached process creation failed (code "
                + Kernel32.INSTANCE.GetLastError()
                + "). The job must allow breakaway.");
      try {
        return Integer.toUnsignedLong(process.dwProcessId.intValue());
      } finally {
        Kernel32.INSTANCE.CloseHandle(process.hThread);
        Kernel32.INSTANCE.CloseHandle(process.hProcess);
      }
    }
    Path setsid = Path.of("/usr/bin/setsid");
    // 独立起動の条件を満たせない環境では、親と一緒に終了する起動方法へ黙って切り替えない。
    if (!Files.isExecutable(setsid))
      throw new IOException(
          "Detached backend requires /usr/bin/setsid on this platform; use an OS service");
    var detached = new ArrayList<String>();
    detached.add(setsid.toString());
    detached.addAll(command);
    return new ProcessBuilder(detached)
        .directory(directory.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .redirectErrorStream(true)
        .start()
        .pid();
  }

  /** Windowsのプロセス起動時に引数の内容が変わらないよう、引用符と末尾の区切りを保護する。 */
  static String quote(String value) {
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid process argument");
    StringBuilder result = new StringBuilder("\"");
    int slashes = 0;
    for (char c : value.toCharArray()) {
      if (c == '\\') {
        slashes++;
        continue;
      }
      result.append("\\".repeat(c == '\"' ? slashes * 2 + 1 : slashes));
      slashes = 0;
      result.append(c);
    }
    return result.append("\\".repeat(slashes * 2)).append('"').toString();
  }
}
