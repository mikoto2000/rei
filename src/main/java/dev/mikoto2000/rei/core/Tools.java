package dev.mikoto2000.rei.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class Tools {
  @Tool(name = "executeExternalProgram", description = "外部プログラムを実行します")
  String executeExternalProgram(String command, String args) throws IOException, InterruptedException {
    IO.println(String.format("%s コマンドを引数 %s で実行するよ", command, args));

    ProcessBuilder processBuilder = new ProcessBuilder();
    processBuilder.command(command + " " + args);
    Process process = processBuilder.start();
    int exitCode = process.waitFor();

    IO.println(String.format("%s コマンドは終了コード %d で終了したよ", command, exitCode));

    try (
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    ) {
      return reader.lines().collect(Collectors.joining(System.lineSeparator()));
    }
  }

  @Tool(name = "rollDice", description = "x 面サイコロをひとつ振る")
  int rollDice(int x) {
    IO.println(String.format("%d 面サイコロをひとつ振るよ", x));
    return (int) (Math.random() * x) + 1;
  }

  @Tool(name = "listFile", description = "ファイル一覧を取得します")
  List<String> listFile(String baseDir) throws IOException {
    IO.println(String.format("%s 以下のファイルを一覧にするよ", baseDir));
    return Files.walk(Paths.get(baseDir), 20)
      .map(p -> p.toFile().getAbsolutePath())
      .toList();
  }

  @Tool(name = "findFile", description = "ファイルを検索します")
  List<String> findFile(String fileName) throws IOException {
    IO.println(String.format("%s のファイルを探すよ", fileName));
    return Files.find(Paths.get("."), 20, (path, basicFileAttribute) -> path.toFile().getAbsolutePath().endsWith(fileName))
      .map(p -> p.toFile().getAbsolutePath())
      .toList();
  }

  @Tool(name = "readTextFile", description = "テキストファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  List<String> readTextFile(String pathStr) throws IOException {
    IO.println(String.format("%s のテキストファイルを読むよ", pathStr));
    return Files.readAllLines(Paths.get(pathStr));
  }

  @Tool(name = "writeTextFile", description = "テキストファイルに書き込みます。ファイルが存在しない場合は作成します。")
  void writeTextFile(String pathStr, List<String> contents, boolean append) throws IOException {
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

    Files.write(Paths.get(pathStr), contents, options);
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
}

