package dev.mikoto2000.rei.core.contextbudget;
import static org.assertj.core.api.Assertions.*;import java.nio.file.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
class RawPagingScaleTest {
 @TempDir Path dir;
 @Test void hundredsOfPagesReadAndWriteOriginalBodyOnceAndNeverReuseAcrossScopes() {
  var store=new RawToolResultStore(dir);String source="原文🙂\r\n".repeat(8000);String ref=store.save("chat","run","reader","call",source);
  var owner=new AgentRunContext("run","chat",dir,"project");int calls=0,offset=0;var text=new StringBuilder();
  try(var scope=AgentRunScope.open(owner)) {
   var pages=new RawToolResultTools(store,TokenEstimator.conservative(),256);
   while(offset<source.length()) {String wire=pages.readRawToolResult(ref,offset,16000);String[] split=wire.split("\n",8);int next=Integer.parseInt(split[5].substring(12));assertThat(next).isGreaterThan(offset);text.append(split[7]);offset=next;calls++;}
   assertThat(store.bodyReadCount()).isEqualTo(1);assertThat(store.bodyWriteCount()).isEqualTo(1);
   assertThatThrownBy(()->store.read("other",ref)).hasMessageContaining("RAW_RESULT_ACCESS_DENIED");
  }
  assertThat(calls).isGreaterThan(100);assertThat(text.toString()).isEqualTo(source);store.clearRun("run");
  try(var scope=AgentRunScope.open(owner)){store.read("chat",ref);assertThat(store.bodyReadCount()).isEqualTo(2);}
 }
}
