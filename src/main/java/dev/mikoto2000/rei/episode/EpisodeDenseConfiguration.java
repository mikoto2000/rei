package dev.mikoto2000.rei.episode;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.ai.embedding.EmbeddingModel;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.sqlitevec.*;
import dev.mikoto2000.rei.vectorstore.LazySqliteVectorStore;
import tools.jackson.databind.json.JsonMapper;
@Configuration
@ConditionalOnExpression("${rei.memory.episodes.dense-enabled:false} && ${rei.embedding.enabled:true}")
public class EpisodeDenseConfiguration {
 @Bean public EpisodeDenseIndex episodeDenseIndex(EpisodeRepository repository,EmbeddingModel model,SqliteVecExtensionLoader loader,JsonMapper json,dev.mikoto2000.rei.conversation.ConversationTurnStore turns,dev.mikoto2000.rei.event.ProjectAgentEventStore events) throws Exception {
   var path=ReiPaths.vectorStoreDbPath().resolveSibling("episode-vectors.db");ReiPaths.ensureParentDirectoryExists(path);
   var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+path+"?enable_load_extension=true");
   var db=org.springframework.jdbc.core.simple.JdbcClient.create(source);
   db.sql("CREATE TABLE IF NOT EXISTS episode_dense_identity(singleton INTEGER PRIMARY KEY CHECK(singleton=1),generation TEXT NOT NULL)").update();
   db.sql("INSERT OR IGNORE INTO episode_dense_identity VALUES(1,?)").param(java.util.UUID.randomUUID().toString()).update();
   String generation=db.sql("SELECT generation FROM episode_dense_identity WHERE singleton=1").query(String.class).single();
   var index=new EpisodeDenseIndex(repository,new LazySqliteVectorStore(new SqliteVecDataSource(source,loader),model,json),generation);
   index.setSourceAvailable(e->e.claims().stream().allMatch(c->turns.findRun(e.sessionId(),c.runId()).isPresent()&&(!c.speaker().equals("tool")||events.findEvent(e.projectId(),c.sourceId()).filter(event->e.sessionId().equals(event.sessionId())&&c.runId().equals(event.runId())).isPresent())));
   return index;
 }
}
