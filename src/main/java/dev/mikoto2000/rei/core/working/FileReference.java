package dev.mikoto2000.rei.core.working;

import java.time.OffsetDateTime;

/**
 * Working Set に保持するファイル参照。
 *
 * @param path           正規化されたファイルパス
 * @param lastAccessedAt 最後にアクセスした日時
 * @param lastModifiedAt 最後に変更した日時（変更系操作のみ設定）
 * @param accessType     最後の操作種別（read / write / edit / created）
 */
public record FileReference(
    String path,
    OffsetDateTime lastAccessedAt,
    OffsetDateTime lastModifiedAt,
    String accessType) {

  public static FileReference read(String path, OffsetDateTime accessedAt) {
    return new FileReference(path, accessedAt, null, "read");
  }

  public static FileReference write(String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "write");
  }

  public static FileReference edit(String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "edit");
  }

  public static FileReference created(String path, OffsetDateTime accessedAt, OffsetDateTime modifiedAt) {
    return new FileReference(path, accessedAt, modifiedAt, "created");
  }

  public FileReference withAccessedAt(OffsetDateTime accessedAt) {
    return new FileReference(path, accessedAt, lastModifiedAt, accessType);
  }

  public FileReference withModifiedAt(OffsetDateTime modifiedAt) {
    return new FileReference(path, lastAccessedAt, modifiedAt, accessType);
  }
}
