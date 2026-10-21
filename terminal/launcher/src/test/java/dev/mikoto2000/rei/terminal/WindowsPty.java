package dev.mikoto2000.rei.terminal;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 検証条件を再現するための判定期間仮想端末。
 *
 * <p>Test-only real ConPTY host. Output is drained independently through final teardown.
 */
final class WindowsPty implements AutoCloseable {
  /** 検証条件を再現するためのコンソール。 */
  interface Console extends StdCallLibrary {
    /** 検証に使うAPI。 */
    Console API = Native.load("kernel32", Console.class);

    /** 検証用に、新たな対象を利用できるよう、Pseudoコンソールを作成する。 */
    int CreatePseudoConsole(
        Coord size, HANDLE input, HANDLE output, int flags, HANDLEByReference console);

    /** {@code ClosePseudoConsole} の条件を再現するため、検証用の操作や状態を扱う。 */
    void ClosePseudoConsole(HANDLE console);

    /** {@code InitializeProcThreadAttributeList} の条件を再現するための補助情報を返す。 */
    boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, Pointer size);

    /** 検証用に、軽量CLIの起動において、更新Procスレッド属性に該当するか判定する。 */
    boolean UpdateProcThreadAttribute(
        Pointer list,
        int flags,
        Pointer attribute,
        Pointer value,
        BaseTSD.SIZE_T size,
        Pointer previous,
        Pointer returned);

    /** 検証用に、対象を管理から外すため、Procスレッド属性一覧を削除する。 */
    void DeleteProcThreadAttributeList(Pointer list);

    /** 検証用に、軽量CLIの起動において、作成プロセスWに該当するか判定する。 */
    boolean CreateProcessW(
        WString application,
        char[] command,
        Pointer processSecurity,
        Pointer threadSecurity,
        boolean inherit,
        int flags,
        Pointer environment,
        WString directory,
        Startup startup,
        WinBase.PROCESS_INFORMATION process);
  }

  /** 検証条件を再現するためのCoord。 */
  @Structure.FieldOrder({"x", "y"})
  public static class Coord extends Structure implements Structure.ByValue {
    /** 検証に使う横方向の位置、縦方向の位置。 */
    public short x = 120, y = 40;
  }

  /** 検証条件を再現するための起動時。 */
  @Structure.FieldOrder({"base", "attributes"})
  public static class Startup extends Structure {
    /** 検証に使う基準。 */
    public WinBase.STARTUPINFO base = new WinBase.STARTUPINFO();

    /** 検証に使う属性一覧。 */
    public Pointer attributes;
  }

  /** 検証に使う入力、出力、コンソール、プロセス。 */
  private HANDLE input, output, console, process;

  /** 検証に使う属性一覧。 */
  private Memory attributes;

  /** 検証に使う文字起こし結果。 */
  private final ByteArrayOutputStreamBuffer transcript = new ByteArrayOutputStreamBuffer();

  /** 検証に使う保留中の作業の処理。 */
  private Thread drain;

  /** 検証に使う終了済み。 */
  private boolean closed;

  /** 検証条件に必要な値を受け取り、WindowsPtyを用意する。 */
  WindowsPty(List<String> argv, Path directory) throws IOException {
    HANDLE inputRead = null, outputWrite = null;
    try {
      var read = new HANDLEByReference();
      var write = new HANDLEByReference();
      require(Kernel32.INSTANCE.CreatePipe(read, write, null, 0));
      inputRead = read.getValue();
      input = write.getValue();
      require(Kernel32.INSTANCE.CreatePipe(read, write, null, 0));
      output = read.getValue();
      outputWrite = write.getValue();
      var reference = new HANDLEByReference();
      int status =
          Console.API.CreatePseudoConsole(new Coord(), inputRead, outputWrite, 0, reference);
      if (status != 0)
        throw new IOException("CreatePseudoConsole HRESULT " + Integer.toHexString(status));
      console = reference.getValue();
      var size = new Memory(Native.SIZE_T_SIZE);
      Console.API.InitializeProcThreadAttributeList(null, 1, 0, size);
      long required =
          Native.SIZE_T_SIZE == 8 ? size.getLong(0) : Integer.toUnsignedLong(size.getInt(0));
      attributes = new Memory(required);
      require(Console.API.InitializeProcThreadAttributeList(attributes, 1, 0, size));
      require(
          Console.API.UpdateProcThreadAttribute(
              attributes,
              0,
              Pointer.createConstant(0x20016),
              console.getPointer(),
              new BaseTSD.SIZE_T(Native.POINTER_SIZE),
              null,
              null));
      var startup = new Startup();
      startup.base.cb = new WinDef.DWORD(startup.size());
      startup.attributes = attributes;
      var created = new WinBase.PROCESS_INFORMATION();
      String command =
          argv.stream()
              .map(DetachedBackend::quote)
              .collect(java.util.stream.Collectors.joining(" "));
      var environment = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
      environment.putAll(System.getenv());
      environment.put("SHELL", "powershell");
      environment.put("TERM", "xterm-256color");
      String block =
          environment.entrySet().stream()
                  .map(e -> e.getKey() + "=" + e.getValue() + "\0")
                  .collect(java.util.stream.Collectors.joining())
              + "\0";
      byte[] encoded = block.getBytes(StandardCharsets.UTF_16LE);
      var nativeEnvironment = new Memory(encoded.length);
      nativeEnvironment.write(0, encoded, 0, encoded.length);
      require(
          Console.API.CreateProcessW(
              null,
              (command + '\0').toCharArray(),
              null,
              null,
              false,
              0x80000 | 0x400,
              nativeEnvironment,
              new WString(directory.toString()),
              startup,
              created));
      process = created.hProcess;
      Kernel32.INSTANCE.CloseHandle(created.hThread);
      drain =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    byte[] bytes = new byte[4096];
                    var count = new IntByReference();
                    while (Kernel32.INSTANCE.ReadFile(output, bytes, bytes.length, count, null))
                      if (count.getValue() > 0) transcript.append(bytes, count.getValue());
                  });
    } catch (IOException | RuntimeException error) {
      close();
      throw error;
    } finally {
      if (inputRead != null) Kernel32.INSTANCE.CloseHandle(inputRead);
      if (outputWrite != null) Kernel32.INSTANCE.CloseHandle(outputWrite);
    }
  }

  /** 検証用に、条件を満たさない操作を拒否するため、判定期間仮想端末の前提条件を確認する。 */
  private static void require(boolean result) throws IOException {
    if (!result) throw new IOException("Windows PTY error " + Kernel32.INSTANCE.GetLastError());
  }

  /** {@code send} の条件を再現するため、検証用の操作や状態を扱う。 */
  void send(String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    var written = new IntByReference();
    require(Kernel32.INSTANCE.WriteFile(input, bytes, bytes.length, written, null));
    if (written.getValue() != bytes.length) throw new IOException("Partial PTY input");
  }

  /** {@code output} の条件を再現するための補助情報を返す。 */
  String output() {
    return transcript
        .text()
        .replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "")
        .replaceAll("\u001b\\][^\u0007]*\u0007", "");
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、判定期間仮想端末を待つ。 */
  void await(String expected) throws Exception {
    awaitAfter(expected, 0);
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、変更後を待つ。 */
  void awaitAfter(String expected, int offset) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      String text = output();
      if (text.length() >= offset && text.substring(offset).contains(expected)) return;
      if (Kernel32.INSTANCE.WaitForSingleObject(process, 0) == 0)
        throw new AssertionError(
            "PTY client exited " + exitCode() + " before " + expected + "; output: " + text);
      Thread.sleep(50);
    }
    throw new AssertionError("PTY missing " + expected + "; output: " + output());
  }

  /** {@code exitCode} の条件を再現するための補助情報を返す。 */
  int exitCode() throws IOException {
    if (Kernel32.INSTANCE.WaitForSingleObject(process, 20000) != 0)
      throw new IOException("PTY client did not exit: " + output());
    var code = new IntByReference();
    require(Kernel32.INSTANCE.GetExitCodeProcess(process, code));
    return code.getValue();
  }

  /** 検証で利用した対象の終了を扱う。 */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (console != null) Console.API.ClosePseudoConsole(console);
    if (process != null) {
      Kernel32.INSTANCE.WaitForSingleObject(process, 5000);
      Kernel32.INSTANCE.CloseHandle(process);
    }
    if (input != null) Kernel32.INSTANCE.CloseHandle(input);
    if (drain != null)
      try {
        drain.join(5000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    if (output != null) Kernel32.INSTANCE.CloseHandle(output);
    if (attributes != null) {
      Console.API.DeleteProcThreadAttributeList(attributes);
      attributes.close();
    }
  }

  /** 検証条件を再現するためのバイトArray出力逐次データ一時保持領域。 */
  private static final class ByteArrayOutputStreamBuffer {
    /** 検証に使うバイト数。 */
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

    /** {@code append} の条件を再現するため、検証用の操作や状態を扱う。 */
    synchronized void append(byte[] value, int count) {
      bytes.write(value, 0, count);
    }

    /** {@code text} の条件を再現するための補助情報を返す。 */
    synchronized String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
