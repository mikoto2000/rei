package dev.mikoto2000.rei;


import java.io.IOException;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import dev.mikoto2000.rei.command.RootCommand;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

@RequiredArgsConstructor
@SpringBootApplication
public class ReiApplication {

  private final RootCommand rootCommand;
  private final CommandLine.IFactory factory;

  public  static void main(String[] args) throws IOException {
    var context = SpringApplication.run(ReiApplication.class, args);
    var app = context.getBean(ReiApplication.class);
    app.run(args);
    context.close();
  }

  private void run(String[] args) throws IOException {
    var cmd = new picocli.CommandLine(rootCommand, factory);

    var terminal = TerminalBuilder.builder()
      .system(true)
      .build();

    LineReader reader = LineReaderBuilder.builder()
      .terminal(terminal)
      .build();

    System.out.println("AI Shell");
    System.out.println("通常入力は chat として扱います。/exit で終了します。");

    while (true) {
      try {
        String line = reader.readLine("> ");
        if (line == null) {
          break;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }

        if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
          break;
        }

        if (trimmed.startsWith("/")) {
          String commandText = trimmed.substring(1).trim();
          if (commandText.isEmpty()) {
            continue;
          }
          cmd.execute(splitCommandLine(commandText));
        } else {
          cmd.execute(trimmed);
        }

      } catch (UserInterruptException e) {
        // Ctrl-C でその行だけキャンセル
      } catch (EndOfFileException e) {
        break;
      }
    }
  }

  private String[] splitCommandLine(String line) {
    return line.split("\\s+");
  }
}
