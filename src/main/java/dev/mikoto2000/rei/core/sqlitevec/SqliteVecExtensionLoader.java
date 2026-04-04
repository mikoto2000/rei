package dev.mikoto2000.rei.core.sqlitevec;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;

@Component
public class SqliteVecExtensionLoader {

  private final SqliteVecProperties properties;
  private final ExtensionPathResolver extensionPathResolver;

  @Autowired
  public SqliteVecExtensionLoader(SqliteVecProperties properties, SqliteVecInstaller installer) {
    this(properties, installer::resolveExtensionPath);
  }

  SqliteVecExtensionLoader(SqliteVecProperties properties, ExtensionPathResolver extensionPathResolver) {
    this.properties = properties;
    this.extensionPathResolver = extensionPathResolver;
  }

  public void load(Connection connection) {
    if (!properties.isEnabled()) {
      return;
    }
    Path extensionPath = extensionPathResolver.resolve();
    try (var statement = connection.prepareStatement("SELECT load_extension(?)")) {
      statement.setString(1, extensionPath.toAbsolutePath().toString());
      statement.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("sqlite-vec extension のロードに失敗しました: " + extensionPath, e);
    }
  }

  @FunctionalInterface
  interface ExtensionPathResolver {
    Path resolve();
  }
}
