package dev.mikoto2000.rei.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * データ保存の共通処理に必要な保存データベースを扱う。
 *
 * <p>Independent commits through one serialized application writer per data directory.
 */
public final class StorageDatabase {
  /** ページを小さくしても自動checkpointの目安は従来の約4 MiBを維持する。 */
  static final int WAL_TARGET_BYTES = 4096 * 1000;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  public static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  /** データ保存の共通処理に必要な書き込み先状態を扱う。 */
  private static final class WriterState {
    /** 処理中に再利用するデータベース接続。 */
    Connection retained;

    /** アプリケーションが接続を保持しているか。 */
    volatile boolean keepOpen;

    /** 書き込みトランザクションへの再入を拒否するための状態。 */
    boolean transaction;

    /** 読み取り接続の貸し借りだけを保護し、SQL実行中は保持しない。 */
    final Object readerLock = new Object();

    /** 待機中の読み取り接続は一つだけ保持する。 */
    Connection reader;

    /** 再起動をまたいで貸出中の接続を再利用しないための所有者。 */
    Object readerOwner;
  }

  /** 書き込み先一覧。 */
  private static final ConcurrentHashMap<Path, WriterState> WRITERS = new ConcurrentHashMap<>();

  /** ファイル。 */
  private final Path file;

  /** 状態。 */
  private final WriterState state;

  /** 書き込み先。 */
  public final Object writer;

  /** データ保存の共通処理に必要な作業を扱う。 */
  @FunctionalInterface
  public interface Work<T> {
    /** データ保存の共通処理の操作を実行する。 */
    T run(Connection connection) throws Exception;
  }

  /** 指定された値や利用先を使って、StorageDatabaseを初期化する。 */
  public StorageDatabase(Path root) {
    file = root.toAbsolutePath().normalize().resolve("storage.db");
    state = WRITERS.computeIfAbsent(file, p -> new WriterState());
    writer = state;
    read(connection -> null);
  }

  /** 必要な対象を利用できるよう、保存データベースの利用を開始する。 */
  private Connection open(boolean readOnly) throws Exception {
    StorageBackup.requireSafePath(file);
    Connection connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + file.toUri().toASCIIString() + (readOnly ? "?mode=ro" : "?mode=rw"));
    try (var query = connection.createStatement()) {
      query.execute("PRAGMA busy_timeout=5000");
      if (!readOnly) {
        query.execute("PRAGMA synchronous=FULL");
        int pageSize;
        try (var rows = query.executeQuery("PRAGMA page_size")) {
          pageSize = rows.getInt(1);
        }
        query.execute("PRAGMA wal_autocheckpoint=" + Math.max(1, WAL_TARGET_BYTES / pageSize));
      }
      requireCurrentSchema(connection);
      return connection;
    } catch (Exception error) {
      connection.close();
      throw error;
    }
  }

  /**
   * データ保存の共通処理で用いるkeepWal開始を返す。
   *
   * <p>Reuses the writer for the owning application's lifetime without retaining a read snapshot or
   * changing checkpoint/durability settings. Closing the last connection after every event
   * otherwise checkpoints and deletes the WAL. The startup gate must close this handle before
   * releasing its process lease.
   */
  AutoCloseable keepWalOpen() throws Exception {
    synchronized (writer) {
      if (state.keepOpen)
        throw new IllegalStateException("Storage writer already has a lifetime owner");
      state.retained = open(false);
      synchronized (state.readerLock) {
        state.readerOwner = new Object();
        state.keepOpen = true;
      }
      return new AutoCloseable() {
        /** 終了済み。 */
        private boolean closed;

        /** データ保存の共通処理で利用した資源や登録を解放し、利用を終了する。 */
        @Override
        public void close() throws Exception {
          synchronized (writer) {
            if (closed) return;
            closed = true;
            state.keepOpen = false;
            var connection = state.retained;
            state.retained = null;
            try {
              synchronized (state.readerLock) {
                state.readerOwner = null;
                var reader = state.reader;
                state.reader = null;
                if (reader != null) reader.close();
              }
            } finally {
              if (connection != null) connection.close();
            }
          }
        }
      };
    }
  }

  /** 条件を満たさない操作を拒否するため、現在データ形式の前提条件を確認する。 */
  private static void requireCurrentSchema(Connection connection) throws SQLException {
    try (var query = connection.createStatement();
        var rows = query.executeQuery("PRAGMA user_version")) {
      if (!rows.next() || rows.getInt(1) != StorageMigrationCoordinator.SCHEMA_VERSION)
        throw new SQLException("Storage startup gate has not prepared the supported schema");
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public <T> T read(Work<T> work) {
    try (var reader = borrowReader()) {
      // 接続を再利用しても、パスと形式の検証は省略しない。
      if (reader.reused) {
        StorageBackup.requireSafePath(file);
        requireCurrentSchema(reader.connection);
      }
      T result = work.run(reader.connection);
      // 明示的なtransactionや失敗した接続を次の読み取りへ渡さない。
      reader.reusable = reader.connection.getAutoCommit();
      return result;
    } catch (Exception error) {
      throw new IllegalStateException("Cannot read SQLite storage", error);
    }
  }

  /** 同時読み取りや再入時は接続の空きを待たず、従来どおり別接続を使う。 */
  private ReaderLease borrowReader() throws Exception {
    Connection connection = null;
    Object owner;
    synchronized (state.readerLock) {
      owner = state.readerOwner;
      if (state.keepOpen) {
        connection = state.reader;
        state.reader = null;
      }
    }
    return new ReaderLease(connection == null ? open(true) : connection, connection != null, owner);
  }

  /** SQL実行中に共有ロックを取らず、正常なautocommit接続だけを返却する。 */
  private final class ReaderLease implements AutoCloseable {
    private final Connection connection;
    private final boolean reused;
    private final Object owner;
    private boolean reusable;

    private ReaderLease(Connection connection, boolean reused, Object owner) {
      this.connection = connection;
      this.reused = reused;
      this.owner = owner;
    }

    @Override
    public void close() throws SQLException {
      synchronized (state.readerLock) {
        if (reusable && state.keepOpen && owner == state.readerOwner && state.reader == null) {
          state.reader = connection;
          return;
        }
      }
      connection.close();
    }
  }

  /** データ保存の共通処理で用いる一連の更新を返す。 */
  public <T> T transaction(Work<T> work) {
    synchronized (writer) {
      if (state.transaction)
        throw new IllegalStateException("Nested storage transactions are not supported");
      state.transaction = true;
      try {
        StorageBackup.requireSafePath(file);
        boolean retained = state.keepOpen;
        if (retained && state.retained == null) state.retained = open(false);
        var connection = retained ? state.retained : open(false);
        try (var owned = retained ? null : connection) {
          try {
            connection.setAutoCommit(false);
            if (retained) requireCurrentSchema(connection);
            T result = work.run(connection);
            connection.commit();
            return result;
          } catch (Exception | Error error) {
            try {
              connection.rollback();
            } catch (SQLException rollback) {
              error.addSuppressed(rollback);
              if (retained) {
                state.retained = null;
                try {
                  connection.close();
                } catch (SQLException close) {
                  error.addSuppressed(close);
                }
              }
            }
            throw error;
          }
        }
      } catch (RuntimeException error) {
        throw error;
      } catch (Exception error) {
        throw new IllegalStateException("Cannot persist SQLite storage", error);
      } finally {
        state.transaction = false;
      }
    }
  }

  /**
   * データ保存の共通処理で用いる保守を返す。
   *
   * <p>Explicit maintenance statements such as VACUUM require autocommit.
   */
  <T> T maintenance(Work<T> work) {
    synchronized (writer) {
      try (var connection = open(false)) {
        return work.run(connection);
      } catch (RuntimeException error) {
        throw error;
      } catch (Exception error) {
        throw new IllegalStateException(
            "SQLite maintenance failed; retry in another window", error);
      }
    }
  }

  /**
   * データ保存の共通処理で用いる順序キーを返す。
   *
   * <p>SQLite BINARY compares UTF-8; this key preserves String.compareTo's UTF-16 ordering.
   */
  public static byte[] orderKey(String value) {
    byte[] result = new byte[Math.multiplyExact(value.length(), 2)];
    for (int i = 0; i < value.length(); i++) {
      result[2 * i] = (byte) (value.charAt(i) >>> 8);
      result[2 * i + 1] = (byte) value.charAt(i);
    }
    return result;
  }
}
