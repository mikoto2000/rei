package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SqliteVecAssetResolverTest {

  private final SqliteVecAssetResolver resolver = new SqliteVecAssetResolver();

  @Test
  void resolvesLinuxLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.LINUX_X86_64);

    assertEquals("sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz", artifact.assetName());
    assertEquals("vec0.so", artifact.libraryFileName());
  }

  @Test
  void resolvesMacosLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.MACOS_AARCH64);

    assertEquals("sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz", artifact.assetName());
    assertEquals("vec0.dylib", artifact.libraryFileName());
  }

  @Test
  void resolvesWindowsLoadableAsset() {
    SqliteVecArtifact artifact = resolver.resolve("0.1.9", SqliteVecPlatform.WINDOWS_X86_64);

    assertEquals("sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz", artifact.assetName());
    assertEquals("vec0.dll", artifact.libraryFileName());
  }
}
