package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class ImplementationRequestRepositoryTest {
  @TempDir Path root;
  @Test void terminalReceiptReconciliationIsIdempotentAndCannotDowngradeResult() {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("requests.db"));var first=new ImplementationRequestRepository(source,Clock.systemUTC());var second=new ImplementationRequestRepository(source,Clock.systemUTC());
    first.save(new ImplementationRequestRepository.Request("id",1,"hash","project",root.toString(),"session","run","run:human","NATURAL_LANGUAGE","codex","base",null,"canonical","{}","AUTO_APPROVE",null,"AUTHORIZED",null,null,Instant.now(),Instant.now()));
    assertTrue(first.claim("id","one"));second.finish("id","RESULT_AVAILABLE","id","same-result");
    assertDoesNotThrow(()->first.finish("id","RESULT_AVAILABLE","id","same-result"));assertThrows(IllegalStateException.class,()->first.finish("id","UNKNOWN","id",null));assertEquals("RESULT_AVAILABLE",second.get("id").executionStatus());
  }
  @Test void atomicClaimSurvivesAnotherRepositoryAndOnlyOneConcurrentCallerWins() throws Exception {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("requests.db"));
    var first=new ImplementationRequestRepository(source,Clock.systemUTC());var second=new ImplementationRequestRepository(source,Clock.systemUTC());
    var record=new ImplementationRequestRepository.Request("id",1,"hash","project",root.toString(),"session","run","run:human","NATURAL_LANGUAGE","codex","base",null,"canonical","{}","AUTO_APPROVE",null,"AUTHORIZED",null,null,Instant.now(),Instant.now());
    first.save(record);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var start=new CountDownLatch(1);
      var a=pool.submit(()->{start.await();return first.claim("id","one");});var b=pool.submit(()->{start.await();return second.claim("id","two");});start.countDown();
      assertNotEquals(a.get(),b.get());
    }
    assertEquals("EXECUTING",second.get("id").executionStatus());assertFalse(second.claim("id","restart"));
    second.finish("id","UNKNOWN",null,null);assertFalse(first.claim("id","retry"));
  }
}
