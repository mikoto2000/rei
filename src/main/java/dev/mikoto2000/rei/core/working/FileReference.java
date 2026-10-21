package dev.mikoto2000.rei.core.working;

import java.time.OffsetDateTime;

/**
 * Working Set に保持するファイル参照。
 *
 * @param path 正規化されたファイルパス
 * @param lastAccessedAt 最後にアクセスした日時
 * @param lastModifiedAt 最後に変更した日時（変更系操作のみ設定）
 * @param accessType 最後の操作種別（read / write / edit / created）
 */
public record FileReference(
    String path, OffsetDateTime lastAccessedAt, OffsetDateTime lastModifiedAt, String accessType) {

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public static FileReference read(String path, OffsetDateTime accessedAt) {
    return new FileReference(path, accessedAt, null, "read");
  }

  /** 指定された内容を書き込み先へ反映する。 */
  public static FileReference write(
      String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "write");
  }

  /** 作業中の情報管理で用いる編集を返す。 */
  public static FileReference edit(
      String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "edit");
  }

  /** 作業中の情報管理で用いる作成を返す。 */
  public static FileReference created(
      String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "created");
  }

  /** 指定されたAccessed日時を反映した情報を作成する。 */
  public FileReference withAccessedAt(OffsetDateTime accessedAt) {
    return new FileReference(path, accessedAt, lastModifiedAt, accessType);
  }

  /** 指定された更新日時を反映した情報を作成する。 */
  public FileReference withModifiedAt(OffsetDateTime modifiedAt) {
    return new FileReference(path, lastAccessedAt, modifiedAt, accessType);
  }
}
