package dev.mikoto2000.rei.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

@Component
public class Tools {
  /**
   * 外部プログラムを実行します。コマンドと引数を指定して実行します。
   *
   * @param command 実行するコマンド
   * @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
   * @return コマンドの標準出力の内容
   */
  @Tool(name = "executeExternalProgram",
  description = """
  外部プログラムを実行します。コマンドと引数を指定して実行します。
  @param command 実行するコマンド
  @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
  @return コマンドの標準出力の内容
  """)
    String executeExternalProgram(String command, List<String> args) throws IOException, InterruptedException {
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("command は空にできません");
      }
      if (command.isBlank() || command.contains(" ")) {
        throw new IllegalArgumentException(
            "外部コマンドの指定が不正です。command には curl のような実行ファイル名だけを指定し、引数は args に分けて指定してください。");
      }
      List<String> safeArgs = args == null ? List.of() : args;
      IO.println(String.format("%s コマンドを引数 %s で実行するよ", command, safeArgs));

      List<String> commandLine = new ArrayList<>();
      commandLine.add(command);
      commandLine.addAll(safeArgs);

      ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
      processBuilder.redirectErrorStream(true);
      Process process = processBuilder.start();

      String output;
      try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

      int exitCode = process.waitFor();
      IO.println(String.format("%s コマンドは終了コード %d で終了したよ", command, exitCode));

      return output;
    }

  @Tool(name = "rollDice", description = "x 面サイコロをひとつ振る")
  int rollDice(int x) {
    IO.println(String.format("%d 面サイコロをひとつ振るよ", x));
    return (int) (Math.random() * x) + 1;
  }

  @Tool(name = "today", description = "今日の日付を yyyy-MM-dd 形式で取得します")
  String today() {
    IO.println("今日の日付を取得するよ");
    return LocalDate.now().toString();
  }

  @Tool(name = "now", description = "現在時刻を ISO-8601 形式で取得します")
  String now() {
    IO.println("現在時刻を取得するよ");
    return OffsetDateTime.now().toString();
  }

  @Tool(name = "findFile", description = "ファイルを検索します（.gitignore を尊重）")
  List<String> findFile(String fileName) throws IOException, InterruptedException {
    return findFile(fileName, Paths.get("."));
  }

  List<String> findFile(String fileName, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    IO.println(String.format("%s のファイルを探すよ（.gitignore を尊重）", fileName));

    List<String> gitListedFiles = gitLsFiles(List.of(), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      return Files.find(workingDirectory, 20, (path, basicFileAttribute) ->
          path.toFile().getAbsolutePath().endsWith(fileName))
        .map(p -> p.toFile().getAbsolutePath())
        .toList();
    }

    return gitListedFiles.stream()
      .filter(s -> s.endsWith(fileName))
      .collect(Collectors.toList());
  }

  @Tool(name = "listFile", description = "ファイル一覧を取得します（.gitignore を尊重）")
  List<String> listFile(String baseDir) throws IOException, InterruptedException {
    return listFile(baseDir, Paths.get("."));
  }

  List<String> listFile(String baseDir, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    IO.println(String.format("%s 以下のファイルを一覧にするよ（.gitignore を尊重）", baseDir));

    List<String> gitListedFiles = gitLsFiles(List.of(baseDir), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      return Files.walk(workingDirectory.resolve(baseDir), 20)
        .map(p -> p.toFile().getAbsolutePath())
        .toList();
    }

    return gitListedFiles.stream()
      .filter(s -> s.startsWith(baseDir))
      .collect(Collectors.toList());
  }

  List<String> gitLsFiles(List<String> pathSpecs, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    List<String> commandLine = new ArrayList<>(List.of(
        "git",
        "ls-files",
        "-z",
        "--cached",
        "--others",
        "--exclude-standard",
        "--full-name"));
    if (pathSpecs != null && !pathSpecs.isEmpty()) {
      commandLine.add("--");
      commandLine.addAll(pathSpecs);
    }

    ProcessBuilder pb = new ProcessBuilder(commandLine);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    Process process = pb.start();
    IO.println("git ls-files コマンドを実行したよ");

    String output;
    try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
          }
    int exitCode = process.waitFor();

    if (exitCode != 0) {
      return null;
    }

    return Arrays.stream(output.split("\0"))
      .filter(s -> !s.isEmpty())
      .toList();
  }

  @Tool(name = "readTextFile", description = "テキストファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  List<String> readTextFile(String pathStr) throws IOException {
    IO.println(String.format("%s のテキストファイルを読むよ", pathStr));
    return Files.readAllLines(Paths.get(pathStr));
  }

  @Tool(name = "readPdfFile", description = "PDF ファイルから本文テキストを抽出して読み込む。")
  String readPdfFile(String pathStr) throws IOException {
    IO.println(String.format("%s の PDF ファイルを読むよ", pathStr));
    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(pathStr));
    return documentReader.get().stream()
      .map(Document::getText)
      .filter(text -> text != null && !text.isBlank())
      .collect(Collectors.joining(System.lineSeparator()));
  }

  /**
   * テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
   *
   * @param pathStr ファイルのパス
   * @param contents 書き込む内容
   * @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
   */
  @Tool(name = "writeTextFile", description =
  """
  テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
  @param pathStr ファイルのパス
  @param contents 書き込む内容
  @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
  """)
    void writeTextFile(String pathStr, String contents, boolean append) throws IOException {
      IO.println(String.format("%s のテキストファイルに %s を書き込むよ", pathStr, contents));

      OpenOption[] options = null;
      if (append) {
        options = new OpenOption[] {
          StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        };
      } else {
        options = new OpenOption[] {
          StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        };
      }

      Files.writeString(Paths.get(pathStr), contents, options);
    }

  @Tool(name = "readBinaryFile", description = "バイナリファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  byte[] readBinaryFile(String pathStr) throws IOException {
    IO.println(String.format("%s のバイナリファイルを読むよ", pathStr));
    return Files.readAllBytes(Paths.get(pathStr));
  }

  @Tool(name = "writeBinaryFile", description = "バイナリファイルに書き込みます。ファイルが存在しない場合は作成します。")
  void writeBinaryFile(String pathStr, byte[] contents, boolean append) throws IOException {
    IO.println(String.format("%s のバイナリファイルに %s を書き込むよ", pathStr, contents));

    OpenOption[] options = null;
    if (append) {
      options = new OpenOption[] {
        StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
      };
    } else {
      options = new OpenOption[] {
        StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
      };
    }

    Files.write(Paths.get(pathStr), contents, options);
  }

  @Tool(name = "deleteFile", description = "ファイルを削除します。ファイルが存在しない場合はエラーになります。")
  void deleteFile(String pathStr) throws IOException {
    IO.println(String.format("%s を削除するよ", pathStr));
    Files.delete(Paths.get(pathStr));
  }

  @Tool(name = "copyFile", description = "ファイルをコピーします。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void copyFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s にコピーするよ。上書き：%s", sourcePath, destPath, overwrite));

    boolean exists = Files.exists(Paths.get(destPath));
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.copy(Paths.get(sourcePath), Paths.get(destPath), overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.COPY_ATTRIBUTES);
  }

  @Tool(name = "moveFile", description = "ファイルを移動します。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void moveFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s に移動するよ。上書き：%s", sourcePath, destPath, overwrite));

    boolean exists = Files.exists(Paths.get(destPath));
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.move(Paths.get(sourcePath), Paths.get(destPath), overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.ATOMIC_MOVE);
  }
}
