package dev.mikoto2000.rei.testsupport;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/** Exact dependency classpath and Unicode fixture arguments, without shell interpolation. */
public final class JavaFixtureCommand {
  private JavaFixtureCommand() {}
  public static List<String> command(Path directory, Class<?> main, String... arguments) {
    return command(directory, System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), main, arguments);
  }
  static List<String> command(Path directory, String classpath, Class<?> main, String... arguments) {
    try {
      Path file = Files.createTempFile(directory, "fixture-jvm-", ".args").toAbsolutePath();
      var contents = new StringBuilder("-cp\n").append(quoted(classpath)).append('\n')
          .append(quoted(Bridge.class.getName())).append('\n').append(quoted(encoded(main.getName()))).append('\n')
          .append(quoted(Integer.toString(arguments.length))).append('\n');
      // The native launcher parses @files before Java's UTF-8 file.encoding takes effect.
      var encoding = java.nio.charset.Charset.forName(System.getProperty("native.encoding",
          java.nio.charset.Charset.defaultCharset().name()));
      // ProcessBuilder argv can also lose characters outside the Windows code page.
      // Only ASCII crosses the launcher boundary; the fixture receives its original strings.
      for (var argument : arguments) contents.append(quoted(encoded(argument))).append('\n');
      Files.writeString(file, contents, encoding);
      var command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin",
          System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
      command.add("@" + file);
      return List.copyOf(command);
    } catch (java.io.IOException error) { throw new UncheckedIOException(error); }
  }
  private static String encoded(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  public static final class Bridge {
    private Bridge() {}
    public static void main(String[] arguments) throws Throwable {
      int encodedCount = Integer.parseInt(arguments[1]);
      var decoded = Arrays.copyOfRange(arguments, 2, arguments.length);
      for (int i = 0; i < encodedCount; i++) decoded[i] = decoded(decoded[i]);
      var main = Class.forName(decoded(arguments[0])).getDeclaredMethod("main", String[].class);
      main.setAccessible(true);
      try { main.invoke(null, (Object) decoded); }
      catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }
    private static String decoded(String value) {
      return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }
  }
  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\f", "\\f") + "\"";
  }
}
