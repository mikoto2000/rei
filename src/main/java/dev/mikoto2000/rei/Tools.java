package dev.mikoto2000.rei;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class Tools {
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

  @Tool(name = "readBinaryFile", description = "バイナリファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  byte[] readBinaryFile(String pathStr) throws IOException {
    IO.println(String.format("%s のバイナリファイルを読むよ", pathStr));
    return Files.readAllBytes(Paths.get(pathStr));
  }
}
