package dev.mikoto2000.rei.core.sqlitevec;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 文書検索用の保存基盤に必要なSQLiteベクトル拡張子読み込みの窓口を扱う。 */
@Component
public class SqliteVecExtensionLoader {

  /** この機能の動作設定。 */
  private final SqliteVecProperties properties;

  /** 拡張子パス対象の解決。 */
  private final ExtensionPathResolver extensionPathResolver;

  /** 文書検索用の保存基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public SqliteVecExtensionLoader(SqliteVecProperties properties, SqliteVecInstaller installer) {
    this(properties, installer::resolveExtensionPath);
  }

  /** 指定された値や利用先を使って、SqliteVecExtensionLoaderを初期化する。 */
  SqliteVecExtensionLoader(
      SqliteVecProperties properties, ExtensionPathResolver extensionPathResolver) {
    this.properties = properties;
    this.extensionPathResolver = extensionPathResolver;
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  public void load(Connection connection) {
    Path extensionPath = extensionPathResolver.resolve();
    try (var statement = connection.prepareStatement("SELECT load_extension(?)")) {
      statement.setString(1, extensionPath.toAbsolutePath().toString());
      statement.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("sqlite-vec extension のロードに失敗しました: " + extensionPath, e);
    }
  }

  /** 拡張子パスの指定を解決し、利用する対象を確定する。 */
  @FunctionalInterface
  interface ExtensionPathResolver {
    /** 指定や設定を照合し、実際に利用する対象を確定する。 */
    Path resolve();
  }
}
