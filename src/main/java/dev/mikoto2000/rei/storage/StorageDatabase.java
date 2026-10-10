package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Independent commits through one serialized application writer per data directory. */
public final class StorageDatabase {
  public static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final class WriterState {
    Connection retained;
    boolean keepOpen,transaction;
  }
  private static final ConcurrentHashMap<Path,WriterState> WRITERS=new ConcurrentHashMap<>();
  private final Path file;
  private final WriterState state;
  public final Object writer;
  @FunctionalInterface public interface Work<T>{T run(Connection connection)throws Exception;}
  public StorageDatabase(Path root) {
    file=root.toAbsolutePath().normalize().resolve("storage.db");state=WRITERS.computeIfAbsent(file,p->new WriterState());writer=state;
    read(connection->null);
  }
  private Connection open(boolean readOnly)throws Exception {
    StorageBackup.requireSafePath(file);
    Connection connection=DriverManager.getConnection("jdbc:sqlite:"+file.toUri().toASCIIString()+(readOnly?"?mode=ro":"?mode=rw"));
    try(var query=connection.createStatement()) {
      query.execute("PRAGMA busy_timeout=5000");
      if(!readOnly)query.execute("PRAGMA synchronous=FULL");
      requireCurrentSchema(connection);
      return connection;
    }catch(Exception error){connection.close();throw error;}
  }
  /**
   * Reuses the writer for the owning application's lifetime without retaining a
   * read snapshot or changing checkpoint/durability settings. Closing the last
   * connection after every event otherwise checkpoints and deletes the WAL.
   * The startup gate must close this handle before releasing its process lease.
   */
  AutoCloseable keepWalOpen() throws Exception {
    synchronized(writer) {
      if(state.keepOpen)throw new IllegalStateException("Storage writer already has a lifetime owner");
      state.retained=open(false);state.keepOpen=true;
      return new AutoCloseable() {
        private boolean closed;
        @Override public void close() throws Exception {
          synchronized(writer) {
            if(closed)return;closed=true;state.keepOpen=false;
            var connection=state.retained;state.retained=null;
            if(connection!=null)connection.close();
          }
        }
      };
    }
  }
  private static void requireCurrentSchema(Connection connection)throws SQLException {
    try(var query=connection.createStatement();var rows=query.executeQuery("PRAGMA user_version")) {
      if(!rows.next()||rows.getInt(1)!=StorageMigrationCoordinator.SCHEMA_VERSION)throw new SQLException("Storage startup gate has not prepared the supported schema");
    }
  }
  public <T>T read(Work<T> work) {
    try(var connection=open(true)){return work.run(connection);}
    catch(Exception error){throw new IllegalStateException("Cannot read SQLite storage",error);}
  }
  public <T>T transaction(Work<T> work) {
    synchronized(writer) {
      if(state.transaction)throw new IllegalStateException("Nested storage transactions are not supported");
      state.transaction=true;
      try {
        StorageBackup.requireSafePath(file);
        boolean retained=state.keepOpen;
        if(retained&&state.retained==null)state.retained=open(false);
        var connection=retained?state.retained:open(false);
        try(var owned=retained?null:connection) {
          try {
            connection.setAutoCommit(false);
            if(retained)requireCurrentSchema(connection);
            T result=work.run(connection);connection.commit();return result;
          }catch(Exception|Error error) {
            try{connection.rollback();}catch(SQLException rollback) {
              error.addSuppressed(rollback);
              if(retained) {
                state.retained=null;
                try{connection.close();}catch(SQLException close){error.addSuppressed(close);}
              }
            }
            throw error;
          }
        }
      }catch(RuntimeException error){throw error;}
      catch(Exception error){throw new IllegalStateException("Cannot persist SQLite storage",error);}
      finally{state.transaction=false;}
    }
  }
  /** Explicit maintenance statements such as VACUUM require autocommit. */
  <T>T maintenance(Work<T> work){synchronized(writer){try(var connection=open(false)){return work.run(connection);}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("SQLite maintenance failed; retry in another window",error);}}}
  /** SQLite BINARY compares UTF-8; this key preserves String.compareTo's UTF-16 ordering. */
  public static byte[] orderKey(String value) {
    byte[] result=new byte[Math.multiplyExact(value.length(),2)];
    for(int i=0;i<value.length();i++){result[2*i]=(byte)(value.charAt(i)>>>8);result[2*i+1]=(byte)value.charAt(i);}
    return result;
  }
}
