package dev.mikoto2000.rei.core.sqlitevec;

import org.springframework.stereotype.Component;

/** SQLiteベクトル素材の指定を解決し、利用する対象を確定する。 */
@Component
public class SqliteVecAssetResolver {

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public SqliteVecArtifact resolve(String version, SqliteVecPlatform platform) {
    return new SqliteVecArtifact(
        "sqlite-vec-%s-loadable-%s.tar.gz".formatted(version, platform.id()),
        platform.libraryFileName());
  }
}
