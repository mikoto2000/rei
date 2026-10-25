package dev.mikoto2000.rei.secretary;

import java.sql.Connection;

/** Storage port. The secretary domain never depends on storage implementations or UI. */
public interface SecretaryDatabase {
  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws java.sql.SQLException, java.io.IOException;
  }

  <T> T read(Work<T> work);

  <T> T transaction(Work<T> work);
}
