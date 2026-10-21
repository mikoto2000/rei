package dev.mikoto2000.rei.core.sqlitevec;

/**
 * 文書検索用の保存基盤で受け渡すSQLiteベクトル成果物を表す。
 *
 * @param assetName 素材名前
 * @param libraryFileName 保管資料ファイル名
 */
public record SqliteVecArtifact(String assetName, String libraryFileName) {}
