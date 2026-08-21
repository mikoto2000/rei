package dev.mikoto2000.rei.core.searchcache;

/**
 * 検索キャッシュのキー。検索ツール名と正規化済み引数から決定的に作成する。
 *
 * @param tool              検索ツール名
 * @param normalizedArguments 正規化済み引数（canonical な文字列表現）
 */
public record SearchCacheKey(String tool, String normalizedArguments) {

  /** 正規化済みキー文字列。 */
  public String canonical() {
    return tool + "|" + normalizedArguments;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SearchCacheKey that)) {
      return false;
    }
    return tool.equals(that.tool) && normalizedArguments.equals(that.normalizedArguments);
  }

  @Override
  public int hashCode() {
    return canonical().hashCode();
  }
}