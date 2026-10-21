package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SQLiteベクトル素材対象の解決の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteVecAssetResolverTest {

  /** 検証に使う対象の解決。 */
  private final SqliteVecAssetResolver resolver = new SqliteVecAssetResolver();

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesLinuxLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.LINUX_X86_64);

    assertEquals("sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz", artifact.assetName());
    assertEquals("vec0.so", artifact.libraryFileName());
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesMacosLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.MACOS_AARCH64);

    assertEquals("sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz", artifact.assetName());
    assertEquals("vec0.dylib", artifact.libraryFileName());
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesWindowsLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.WINDOWS_X86_64);

    assertEquals("sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz", artifact.assetName());
    assertEquals("vec0.dll", artifact.libraryFileName());
  }
}
