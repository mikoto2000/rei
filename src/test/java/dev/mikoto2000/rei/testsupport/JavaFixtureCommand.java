package dev.mikoto2000.rei.testsupport;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/** Exact dependency classpath and fixture arguments in a JVM argument file, without shell interpolation. */
public final class JavaFixtureCommand {
  private JavaFixtureCommand() {}
  public static List<String> command(Path directory, Class<?> main, String... arguments) {
    return command(directory, System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), main, arguments);
  }
  static List<String> command(Path directory, String classpath, Class<?> main, String... arguments) {
    try {
      Path file = Files.createTempFile(directory, "fixture-jvm-", ".args").toAbsolutePath();
      var contents = new StringBuilder("-cp\n").append(quoted(classpath)).append('\n')
          .append(quoted(main.getName())).append('\n');
      for (var argument : arguments) contents.append(quoted(argument)).append('\n');
      // The native launcher parses @files before Java's UTF-8 file.encoding takes effect.
      var encoding = java.nio.charset.Charset.forName(System.getProperty("native.encoding",
          java.nio.charset.Charset.defaultCharset().name()));
      Files.writeString(file, contents, encoding);
      var command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
      command.add("@" + file);
      return List.copyOf(command);
    } catch (java.io.IOException error) { throw new UncheckedIOException(error); }
  }
  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "\"";
  }
}
