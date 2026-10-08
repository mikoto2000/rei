package dev.mikoto2000.rei.testsupport;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class JavaFixtureCommandTest {
  @TempDir Path root;
  @Test void preservesUnicodeWithWesternNativeEncoding() throws Exception {
    String previous = System.getProperty("native.encoding");
    try {
      System.setProperty("native.encoding", "windows-1252");
      launchesLongClasspathWithoutChangingLiteralArguments();
    } finally {
      if (previous == null) System.clearProperty("native.encoding");
      else System.setProperty("native.encoding", previous);
    }
  }
  @Test void launchesLongClasspathWithoutChangingLiteralArguments() throws Exception {
    String classpath = String.join(java.io.File.pathSeparator, Collections.nCopies(1200, root.toString()))
        + java.io.File.pathSeparator + System.getProperty("java.class.path");
    assertThat(classpath.length()).isGreaterThan(32767);
    String argument = "quotes \" with spaces and \\backslashes 日本語";
    var command = JavaFixtureCommand.command(root, classpath, Echo.class, argument);
    assertThat(String.join(" ", command).length()).isLessThan(4096);
    var output = root.resolve("output.log");
    var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(Files.readString(output).trim()).isEqualTo(Base64.getEncoder()
          .encodeToString(argument.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } finally { if(process.isAlive())process.destroyForcibly(); }
  }
  public static class Echo {
    public static void main(String[] args) {
      System.out.println(Base64.getEncoder().encodeToString(args[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
  }
}
