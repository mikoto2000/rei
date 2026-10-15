package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class ForegroundConsoleWindowsTest {
  @TempDir Path root;

  @Test
  void ctrlDAndCtrlCCloseServerAndReleaseStorageInRealWindowsConsole() throws Exception {
    for (String key : List.of("\u0004", "\u0003")) {
      var directory =
          Files.createDirectory(root.resolve(key.equals("\u0004") ? "ctrl-d" : "ctrl-c"));
      var argumentFile = directory.resolve("java.args");
      var arguments =
          List.of(
              "--enable-native-access=ALL-UNNAMED",
              "--add-opens=java.base/java.io=ALL-UNNAMED",
              "-cp",
              System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
              Server.class.getName(),
              directory.toString());
      Files.writeString(
          argumentFile,
          arguments.stream()
              .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .collect(java.util.stream.Collectors.joining("\n")));
      var argv =
          List.of(
              Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
              "@" + argumentFile);
      try (var pty = new WindowsPty(argv, directory)) {
        pty.await("Ctrl+D / Ctrl+C to stop.");
        int port = Integer.parseInt(Files.readString(directory.resolve("port.txt")));
        try (var socket = new Socket("127.0.0.1", port)) {
          assertTrue(socket.isConnected());
        }
        assertTrue(dev.mikoto2000.rei.launcher.BackendOwnership.isOwned(directory));
        pty.send(key);
        assertEquals(0, pty.exitCode(), pty.output());
        assertFalse(dev.mikoto2000.rei.launcher.BackendOwnership.isOwned(directory));
        assertThrows(
            IOException.class,
            () -> {
              try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
              }
            });
      }
    }
  }

  public static final class Server {
    public static void main(String[] args) throws Exception {
      var kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE;
      var input = kernel.CreateFile("CONIN$", 0x80000000 | 0x40000000, 3, null, 3, 0, null);
      var output = kernel.CreateFile("CONOUT$", 0x80000000 | 0x40000000, 3, null, 3, 0, null);
      if (!kernel.SetStdHandle(-10, input)
          || !kernel.SetStdHandle(-11, output)
          || !kernel.SetStdHandle(-12, output))
        throw new IOException("Cannot attach console handles");
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
      Path directory = Path.of(args[0]);
      try (var context = new org.springframework.context.support.GenericApplicationContext()) {
        context.registerBean(
            "lease",
            dev.mikoto2000.rei.storage.StorageMigrationCoordinator.class,
            () -> {
              try {
                return new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(directory);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            },
            definition -> definition.setDestroyMethodName("close"));
        context.registerBean(
            "listener",
            ServerSocket.class,
            () -> {
              try {
                return new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            },
            definition -> definition.setDestroyMethodName("close"));
        context.refresh();
        Files.writeString(
            directory.resolve("port.txt"),
            Integer.toString(context.getBean(ServerSocket.class).getLocalPort()));
        ForegroundConsole.run(context);
      }
      System.exit(0);
    }
  }
}
