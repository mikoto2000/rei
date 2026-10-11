package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
@Tag("integration")
class EpisodeDenseIndexTest {
 @TempDir Path dir;
 @Test void anOlderWorkerCannotLeaveANewerRevisionMarkedIndexedAfterOverwritingItsVector() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));
  repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v1")));
  var vectors=mock(LazySqliteVectorStore.class);
  doAnswer(i->{var newer=EpisodeRevisionPagingTest.episode("v2");repo.saveBatch("s",1,2,List.of(newer));repo.markDense(newer);return null;})
    .when(vectors).replaceBySource(anyString(),anyString(),anyString(),anyList());
  new EpisodeDenseIndex(repo,vectors).indexPending("s",null);
  assertEquals(List.of("v2"),repo.pendingDense("s",50).stream().map(Episode::revision).toList());
 }
 @Test void embeddingInputIsBounded() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));
  var claims=java.util.stream.IntStream.range(0,4).mapToObj(i->new Episode.Claim("reason","discussion ".repeat(170),Episode.Evidence.MODEL_PROPOSAL,"r","assistant")).toList();
  repo.saveBatch("s",0,1,List.of(new Episode("e","p","s","v","2026-10-11T00:00:00Z",Episode.Status.UNVERIFIED,"design","summary",claims,.5)));
  var vectors=mock(LazySqliteVectorStore.class);
  doAnswer(i->{List<Document> docs=i.getArgument(3);assertTrue(docs.getFirst().getText().length()<=4000);return null;}).when(vectors).replaceBySource(anyString(),anyString(),anyString(),anyList());
  new EpisodeDenseIndex(repo,vectors).indexPending("s",null);
 }
 @Test void rebuildingTheDerivedDatabaseRequeuesPreviouslyIndexedEpisodes() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));
  var e=EpisodeRevisionPagingTest.episode("v1");repo.saveBatch("s",0,1,List.of(e));repo.markDense(e);
  assertTrue(repo.pendingDense("s",50).isEmpty());
  assertEquals(1,repo.pendingDense("s",50,"rebuilt-database").size());
  var vectors=mock(LazySqliteVectorStore.class);new EpisodeDenseIndex(repo,vectors,"rebuilt-database").indexPending("s",null);
  assertTrue(repo.pendingDense("s",50,"rebuilt-database").isEmpty());
 }
 @Test void failedIndexingRemainsPendingAndRestartRetriesOnlyTheLatestRevision() {
  var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));var repo=new EpisodeRepository(ds);
  repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v1")));
  var vectors=mock(LazySqliteVectorStore.class);var index=new EpisodeDenseIndex(repo,vectors);
  doThrow(new IllegalStateException("provider unavailable")).when(vectors).replaceBySource(anyString(),anyString(),anyString(),anyList());
  assertThrows(IllegalStateException.class,()->index.indexPending("s",null));
  assertEquals(1,repo.pendingDense("s",50).size());
  repo.saveBatch("s",1,2,List.of(EpisodeRevisionPagingTest.episode("v2")));
  reset(vectors);var restarted=new EpisodeDenseIndex(new EpisodeRepository(ds),vectors);
  restarted.indexPending("s",null);restarted.indexPending("s",null);
  verify(vectors,times(1)).replaceBySource(eq("p"),eq("e"),anyString(),argThat(d->d.getFirst().getMetadata().get("revision").equals("v2")));
  assertTrue(repo.pendingDense("s",50).isEmpty());assertEquals(2,repo.checkpoint("s"));
 }
 @Test void denseSearchRejectsStaleRevisionsAndForeignProjectsBeforeReturningStoredText() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));
  repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v2")));
  repo.markDense(EpisodeRevisionPagingTest.episode("v2"));
  var vectors=mock(LazySqliteVectorStore.class);var index=new EpisodeDenseIndex(repo,vectors);
  when(vectors.denseSearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().id("chunk").text("untrusted stale text").metadata(Map.of("episodeId","e","revision","v1")).build()));
  assertTrue(index.search("synonym","p",8,EpisodeTimeRange.of(null,null)).isEmpty());
  when(vectors.denseSearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().id("chunk").text("do not use vector text").metadata(Map.of("episodeId","e","revision","v2")).build()));
  assertEquals("summary",index.search("synonym","p",8,EpisodeTimeRange.of(null,null)).getFirst().summary());
  assertTrue(index.search("synonym","foreign",8,EpisodeTimeRange.of(null,null)).isEmpty());
  assertTrue(index.search("synonym","p",8,EpisodeTimeRange.of("2027-01-01",null)).isEmpty());
 }
}
