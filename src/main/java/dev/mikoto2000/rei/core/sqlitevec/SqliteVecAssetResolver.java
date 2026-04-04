package dev.mikoto2000.rei.core.sqlitevec;

import org.springframework.stereotype.Component;

@Component
public class SqliteVecAssetResolver {

  public SqliteVecArtifact resolve(String version, SqliteVecPlatform platform) {
    return new SqliteVecArtifact(
        "sqlite-vec-%s-loadable-%s.tar.gz".formatted(version, platform.id()),
        platform.libraryFileName());
  }
}
