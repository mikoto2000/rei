package dev.mikoto2000.rei.core.completion;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 入力候補の提示で受け渡す入力補完文脈を表す。
 *
 * <p>Cursor prefix is decoded by the UI parser. argumentIndex is local to the selected command.
 *
 * @param fullLine 全体行
 * @param cursorPosition 続きの取得位置位置
 * @param tokens トークン数
 * @param wordIndex 語句位置
 * @param argumentIndex 引数位置
 * @param currentToken 現在トークン
 * @param commandPath コマンドパス
 * @param types 種別一覧
 * @param choices 選択肢
 * @param workingDirectory 作業ディレクトリ
 */
public record CompletionContext(
    String fullLine,
    int cursorPosition,
    List<String> tokens,
    int wordIndex,
    int argumentIndex,
    String currentToken,
    List<String> commandPath,
    Set<String> types,
    List<CompletionCandidate> choices,
    Path workingDirectory) {
  /** 受け取った情報からCompletionContextを作成する。 */
  public CompletionContext {
    tokens = List.copyOf(tokens);
    commandPath = List.copyOf(commandPath);
    types = Set.copyOf(types);
    choices = List.copyOf(choices);
  }

  /** 指定された種別一覧を反映した情報を作成する。 */
  public CompletionContext withTypes(Set<String> selectedTypes) {
    return new CompletionContext(
        fullLine,
        cursorPosition,
        tokens,
        wordIndex,
        argumentIndex,
        currentToken,
        commandPath,
        selectedTypes,
        choices,
        workingDirectory);
  }
}
