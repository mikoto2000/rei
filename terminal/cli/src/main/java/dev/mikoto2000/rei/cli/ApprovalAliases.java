package dev.mikoto2000.rei.cli;

import java.util.*;

/**
 * 表示済みの承認番号をプロジェクトと会話に結び付け、別の作業での誤承認を防ぐ。
 *
 * <p>Numbers are never reused and are bound to this terminal's displayed Project/Session selection.
 */
final class ApprovalAliases {
  /**
   * 軽量CLIでの対話で受け渡す項目を表す。
   *
   * @param project プロジェクト
   * @param session 会話
   * @param id 識別子
   */
  private record Entry(String project, String session, String id) {}

  /** この端末で表示した番号と、その番号が指すプロジェクト・会話・承認の対応。 */
  private final Map<String, Entry> rows = new LinkedHashMap<>();

  /** 表示用の承認番号を発行する。同じ対象には同じ番号を返し、発行済みの番号を再利用しない。 */
  String bind(String project, String session, String id) {
    var entry = new Entry(project, session, id);
    for (var row : rows.entrySet()) if (row.getValue().equals(entry)) return row.getKey();
    // 表示番号の管理上限に達した後は、既存番号を使い回さず元の識別子を案内する。
    if (rows.size() >= 1024) return id;
    String number = Integer.toString(rows.size() + 1);
    rows.put(number, entry);
    return number;
  }

  /** 現在のプロジェクトと会話で表示した承認番号を、元の承認識別子へ解決する。 */
  String resolve(String project, String session, String value) {
    if (!value.matches("[0-9]+")) return value;
    var entry = rows.get(value);
    // 別の会話へ切り替えた後に、以前表示した番号で承認してしまうことを防ぐ。
    if (entry == null
        || !Objects.equals(project, entry.project())
        || !Objects.equals(session, entry.session()))
      throw new IllegalArgumentException(
          "現在のProject/Sessionで /approval list を実行してください。表示した番号だけが利用できます。");
    return entry.id();
  }
}
