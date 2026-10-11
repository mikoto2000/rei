package dev.mikoto2000.rei.slack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SlackStoreTest {
  @TempDir Path root;
  SlackMessage message(String id,String type,String team,String channel,String ts,String thread) {
    return new SlackMessage(id,team,channel,ts,thread,"U",null,null,type,"im","hello");
  }
  @Test void deduplicatesEventAndMessageAcrossRestart() {
    var s=new SlackStore(root);var m=message("E","message","T","D","1",null);
    assertTrue(s.accept(m));assertFalse(s.accept(m));
    assertFalse(new SlackStore(root).accept(message("E2","app_mention","T","D","1",null)));
    assertEquals(1,new SlackStore(root).pending().size());
  }
  @Test void threadMappingsAreScopedAndPersistent() {
    var s=new SlackStore(root);var m=message("E","message","T","D","1",null);
    s.bind(m,"P","S");assertEquals("S",new SlackStore(root).thread(m).orElseThrow().session());
    assertTrue(s.thread(message("E2","message","T2","D","1",null)).isEmpty());
    assertTrue(s.thread(message("E3","message","T","C","1",null)).isEmpty());
    assertTrue(s.thread(message("E4","message","T","D","2",null)).isEmpty());
    assertEquals("S",s.thread(message("E5","message","T","D","3","1")).orElseThrow().session());
  }
  @Test void outboxRetriesOnlyDefiniteFailuresAndPreservesUncertainSends() {
    var s=new SlackStore(root);var m=message("E","message","T","D","1",null);
    s.reply("R",m,"hello");assertEquals(1,s.outbox(0).size());
    var row=s.outbox(0).getFirst();assertTrue(s.claimSend(row.id()));assertFalse(s.claimSend(row.id()));
    assertEquals(0,new SlackStore(root).outbox(Long.MAX_VALUE).size());
    s.sent(row.id(),"9");assertEquals(0,s.outbox(Long.MAX_VALUE).size());
    s.reply("R",m,"hello");assertEquals(0,s.outbox(Long.MAX_VALUE).size());
    s.reply("R2",m,"retry");row=s.outbox(0).getFirst();s.claimSend(row.id());s.retry(row.id(),1000);
    assertEquals(0,s.outbox(999).size());assertEquals(1,s.outbox(1000).size());
  }
  @Test void rateLimitedChannelCannotBeBypassedByLaterReplies() {
    var s=new SlackStore(root);var m=message("E","message","T","D","1",null);
    s.reply("one",m,"one");s.reply("two",m,"two");s.claimSend("one");s.retry("one",1000);
    assertEquals(0,s.outbox(999).size());assertEquals("one",s.outbox(1000).getFirst().id());
  }
}
