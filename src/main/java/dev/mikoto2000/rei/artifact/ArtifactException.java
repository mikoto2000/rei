package dev.mikoto2000.rei.artifact;

/** 成果物の管理を継続できない成果物の状況を伝える例外。 */
public final class ArtifactException extends RuntimeException {
  /** 成果物の管理におけるコードの選択肢。 */
  public enum Code {
    /** 許容件数。 */
    CAPACITY,
    /** 利用不可。 */
    UNAVAILABLE,
    /** 未指定。 */
    MISSING,
    /** 古い状態。 */
    STALE,
    /** 期限切れ。 */
    EXPIRED,
    /** 削除済み。 */
    DELETED,
    /** 安全でない保存。 */
    UNSAFE_STORAGE,
    /** 保存失敗情報。 */
    STORAGE_FAILURE
  }

  /** コード。 */
  private final Code code;

  /** 指定された値や利用先を使って、ArtifactExceptionを初期化する。 */
  public ArtifactException(Code code) {
    super("Artifact: " + code);
    this.code = code;
  }

  /** 成果物の管理で用いるコードを返す。 */
  public Code code() {
    return code;
  }
}
