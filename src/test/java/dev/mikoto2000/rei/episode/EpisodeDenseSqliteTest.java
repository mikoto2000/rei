package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import tools.jackson.databind.json.JsonMapper;
import dev.mikoto2000.rei.core.sqlitevec.*;
import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import dev.mikoto2000.rei.llm.*;
@Tag("integration")
class EpisodeDenseSqliteTest {
 @TempDir Path dir;
 LazySqliteVectorStore vectors(EmbeddingModel model) {
  var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+dir.resolve("episode-vectors.db"));source.setLoadExtension(true);
  var properties=new SqliteVecProperties();properties.setExtensionPath(dev.mikoto2000.rei.testsupport.SqliteVecTestExtension.resolve().toString());
  var installer=new SqliteVecInstaller(properties,new PlatformDetector(),new SqliteVecAssetResolver(),new JsonMapper());
  return new LazySqliteVectorStore(new SqliteVecDataSource(source,new SqliteVecExtensionLoader(properties,installer)),model,new JsonMapper());
 }
 @Test void realVectorsSurviveRestartAndChargeTheSharedBudgetBeforePublication() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v1")));
  var provider=mock(EmbeddingModel.class);when(provider.getEmbeddingContent(any(Document.class))).thenAnswer(i->((Document)i.getArgument(0)).getText());
  when(provider.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0)),new EmbeddingResponseMetadata("test",new org.springframework.ai.chat.metadata.DefaultUsage(3,0))));
  when(provider.dimensions()).thenReturn(2);when(provider.embed(anyString())).thenReturn(new float[]{1,0});
  when(provider.embed(any(Document.class))).thenReturn(new float[]{1,0});
  var model=new BudgetedEmbeddingModel(provider);var store=vectors(model);var index=new EpisodeDenseIndex(repo,store);
  var budget=mock(ModelCallBudget.class);when(budget.tokenLimitEnabled()).thenReturn(true);
  index.indexPending("s",budget);verify(budget,times(2)).run();verify(budget,times(2)).recordTotalTokens(3);
  assertTrue(repo.pendingDense("s",50).isEmpty());assertEquals(1,store.list().size());
  var restarted=new EpisodeDenseIndex(repo,vectors(model));
  assertEquals("v1",restarted.search("semantic synonym","p",8,EpisodeTimeRange.of(null,null)).getFirst().revision());
  assertTrue(restarted.search("semantic synonym","foreign",8,EpisodeTimeRange.of(null,null)).isEmpty());
  restarted.indexPending("s",budget);verify(budget,times(2)).run();
 }
 @Test void unavailableSourcesNeverReachTheEmbeddingProvider() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v1")));
  var store=mock(LazySqliteVectorStore.class);var index=new EpisodeDenseIndex(repo,store);index.setSourceAvailable(e->false);
  index.indexPending("s",null);verifyNoInteractions(store);assertEquals(1,repo.pendingDense("s",50).size());
 }
 @Test void tokenBudgetFailureRollsBackVectorsAndLeavesTheEpisodePending() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));repo.saveBatch("s",0,1,List.of(EpisodeRevisionPagingTest.episode("v1")));
  var provider=mock(EmbeddingModel.class);when(provider.dimensions()).thenReturn(2);
  when(provider.getEmbeddingContent(any(Document.class))).thenAnswer(i->((Document)i.getArgument(0)).getText());
  when(provider.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0)),new EmbeddingResponseMetadata("test",new org.springframework.ai.chat.metadata.DefaultUsage(3,0))));
  var store=vectors(new BudgetedEmbeddingModel(provider));assertTrue(store.list().isEmpty());
  var budget=mock(ModelCallBudget.class);when(budget.tokenLimitEnabled()).thenReturn(true);
  doThrow(new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED)).when(budget).recordTotalTokens(3);
  assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,()->new EpisodeDenseIndex(repo,store).indexPending("s",budget));
  assertTrue(store.list().isEmpty());assertEquals(1,repo.pendingDense("s",50).size());assertEquals(1,repo.checkpoint("s"));
  verify(budget).recordTotalTokens(3);assertNull(ModelCallBudgetScope.current());
 }
}
