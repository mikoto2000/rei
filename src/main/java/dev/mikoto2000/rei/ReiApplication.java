

package dev.mikoto2000.rei;


import java.io.IOException;
import java.nio.file.Path;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

@EnableScheduling
@RequiredArgsConstructor
@SpringBootApplication
public class ReiApplication {

  private final RootCommand rootCommand;
  private final CommandLine.IFactory factory;
  private final ModelHolderService currentModelHolder;

  private final Path HISTORY_FILE = ReiPaths.historyFilePath();

  public  static void main(String[] args) throws IOException {
    var context = SpringApplication.run(ReiApplication.class, args);
    var app = context.getBean(ReiApplication.class);
    app.run(args);
    context.close();
  }

  private void run(String[] args) throws IOException {
    var cmd = new picocli.CommandLine(rootCommand, factory);
    try {
      ReiPaths.ensureParentDirectoryExists(HISTORY_FILE);
    } catch (Exception e) {
      throw new IOException("履歴ファイル用ディレクトリの作成に失敗しました: " + HISTORY_FILE, e);
    }

    var terminal = TerminalBuilder.builder()
      .system(true)
      .build();

    LineReader reader = LineReaderBuilder.builder()
      .terminal(terminal)
      .variable(LineReader.HISTORY_FILE, HISTORY_FILE)
      .variable(LineReader.HISTORY_SIZE, 1000)
      .variable(LineReader.HISTORY_FILE_SIZE, 1000)
      .build();

    System.out.println("AI Shell");
    System.out.println("通常入力は chat として扱います。/exit で終了します。");

    while (true) {
      try {
        String line = reader.readLine(currentModelHolder.get() + "> ");
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

        if (trimmed.equals("/help")) {
          cmd.execute("--help");
          continue;
        }

        if (trimmed.equals("/version")) {
          cmd.execute("--version");
          continue;
        }

        if (trimmed.startsWith("/")) {
          String commandText = trimmed.substring(1).trim();
          if (commandText.isEmpty()) {
            continue;
          }
          cmd.execute(splitCommandLine(commandText));
        } else {
          cmd.execute("chat", trimmed);
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
