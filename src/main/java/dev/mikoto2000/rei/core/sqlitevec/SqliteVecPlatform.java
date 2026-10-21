package dev.mikoto2000.rei.core.sqlitevec;

/** 文書検索用の保存基盤におけるSQLiteベクトル実行環境の選択肢。 */
public enum SqliteVecPlatform {
  /** Linux向けの実行環境X8664。 */
  LINUX_X86_64("linux-x86_64", "vec0.so"),
  /** Linux向けの実行環境系CPU64。 */
  LINUX_AARCH64("linux-aarch64", "vec0.so"),
  /** macOS向けの実行環境X8664。 */
  MACOS_X86_64("macos-x86_64", "vec0.dylib"),
  /** macOS向けの実行環境系CPU64。 */
  MACOS_AARCH64("macos-aarch64", "vec0.dylib"),
  /** 対象環境または判定期間の設定X8664。 */
  WINDOWS_X86_64("windows-x86_64", "vec0.dll");

  /** 識別子。 */
  private final String id;

  /** 保管資料ファイル名。 */
  private final String libraryFileName;

  /** 指定された値や利用先を使って、SqliteVecPlatformを初期化する。 */
  SqliteVecPlatform(String id, String libraryFileName) {
    this.id = id;
    this.libraryFileName = libraryFileName;
  }

  /** 文書検索用の保存基盤で用いる識別子を返す。 */
  public String id() {
    return id;
  }

  /** 文書検索用の保存基盤で用いる保管資料ファイル名を返す。 */
  public String libraryFileName() {
    return libraryFileName;
  }
}
