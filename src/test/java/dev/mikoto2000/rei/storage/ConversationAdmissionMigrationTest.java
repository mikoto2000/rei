package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class ConversationAdmissionMigrationTest {
  @Test void leaseVerificationRequiresUniqueProjectSessionColumnsAndActivePredicate() throws Exception {
    for (String index : new String[]{
        "CREATE INDEX conversation_session_owner ON conversation_admissions(project_id,session_id) WHERE active=1",
        "CREATE UNIQUE INDEX conversation_session_owner ON conversation_admissions(run_id) WHERE active=1",
        "CREATE UNIQUE INDEX conversation_session_owner ON conversation_admissions(project_id,session_id) WHERE active=1 OR active=0"}) {
      try (var db=DriverManager.getConnection("jdbc:sqlite::memory:");var sql=db.createStatement()) {
        ConversationAdmissionMigration.apply(db);
        ConversationAdmissionMigration.verify(db);
        sql.execute("DROP INDEX conversation_session_owner");sql.execute(index);
        assertThrows(SQLException.class,()->ConversationAdmissionMigration.verify(db),index);
      }
    }
  }
  @Test void validLeaseRejectsSecondActiveRunButAllowsOtherSessionsAndInactiveRuns() throws Exception {
    try (var db=DriverManager.getConnection("jdbc:sqlite::memory:");var sql=db.createStatement()) {
      ConversationAdmissionMigration.apply(db);ConversationAdmissionMigration.verify(db);
      String insert="INSERT INTO conversation_admissions(run_id,project_id,session_id,context,status,active,owner_instance,owner_pid,accepted) VALUES ";
      sql.execute(insert+"('one','project','session','{}','RUNNING',1,'owner',1,'now')");
      assertThrows(SQLException.class,()->sql.execute(insert+"('two','project','session','{}','RUNNING',1,'owner',1,'now')"));
      sql.execute(insert+"('other','project','other','{}','RUNNING',1,'owner',1,'now')");
      sql.execute(insert+"('queued','project','session','{}','QUEUED',0,'owner',1,'now')");
    }
  }
}
