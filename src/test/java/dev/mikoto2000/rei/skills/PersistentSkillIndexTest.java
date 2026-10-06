package dev.mikoto2000.rei.skills;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

class PersistentSkillIndexTest {
  @TempDir Path root;
  SQLiteDataSource data() {var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+root.resolve("skills.db"));return data;}
  SemanticSkillProperties config(String namespace) {return new SemanticSkillProperties(true,64,.55,30,true,namespace);}
  AgentSkill skill(String description) {return new AgentSkill("writer",description,List.of(),true,root,root.resolve("SKILL.md"),"private instructions");}
  @Test void restartedSearchReusesOnlyMetadataAndReturnsTheCurrentSkill()throws Exception {
    var calls=new ArrayList<List<String>>();
    SkillMetadataEmbedding model=texts->{calls.add(List.copyOf(texts));return texts.stream().map(t->new float[]{1,0}).toList();};
    var index=new SqliteSkillEmbeddingIndex(data());
    new SemanticSkillSearch(config("test-model-v1"),()->model,()->null,System::nanoTime,index).select("compose",List.of(skill("articles")),List.of(),5);
    assertEquals(2,calls.size());
    var replacement=skill("articles");
    var restarted=new SemanticSkillSearch(config("test-model-v1"),()->model,()->null,System::nanoTime,new SqliteSkillEmbeddingIndex(data()));
    assertSame(replacement,restarted.select("compose again",List.of(replacement),List.of(),5).getFirst().skill());
    assertEquals(3,calls.size());assertEquals(List.of("compose again"),calls.getLast());
    restarted.select("compose",List.of(skill("changed description")),List.of(),5);assertEquals(5,calls.size());
    try(var connection=data().getConnection();var statement=connection.createStatement();var rs=statement.executeQuery("SELECT COUNT(*) FROM skill_embedding_index")) {
      assertTrue(rs.next());assertEquals(1,rs.getInt(1));
    }
  }
  @Test void emptyCatalogRemovesPreviousPersistedMetadata()throws Exception {
    var index=new SqliteSkillEmbeddingIndex(data());
    String profile=SemanticSkillSearch.profile(skill("articles"));index.replace("test-model-v1",Map.of(profile,new float[]{1,0}));
    SkillMetadataEmbedding model=texts->{fail("Empty catalog must not call embedding");return List.of();};
    new SemanticSkillSearch(config("test-model-v1"),()->model,()->null,System::nanoTime,index).select("compose",List.of(),List.of(),5);
    assertTrue(index.load("test-model-v1",List.of(profile)).isEmpty());
  }
  @Test void namespaceRotationReembedsAndDimensionMismatchInvalidatesBeforeRetry()throws Exception {
    var index=new SqliteSkillEmbeddingIndex(data());var profile=SemanticSkillSearch.profile(skill("articles"));
    index.replace("old-model",Map.of(profile,new float[]{1,0}));
    var calls=new ArrayList<List<String>>();
    SkillMetadataEmbedding model=texts->{calls.add(List.copyOf(texts));return texts.stream().map(t->new float[]{1,0,0}).toList();};
    var clock=new java.util.concurrent.atomic.AtomicLong();
    var search=new SemanticSkillSearch(config("old-model"),()->model,()->null,clock::get,index);
    var lexical=List.of(new SkillCandidate(skill("articles"),10,List.of("name"),List.of()));
    assertEquals(lexical,search.select("compose",List.of(skill("articles")),lexical,5));
    assertTrue(index.load("old-model",List.of(profile)).isEmpty());assertEquals(1,calls.size());
    clock.set(31_000_000_000L);
    assertTrue(search.select("compose",List.of(skill("articles")),lexical,5).getFirst().matchedFields().contains("rrf"));
    assertEquals(3,calls.size());assertEquals(3,index.load("old-model",List.of(profile)).get(profile).length);
    new SemanticSkillSearch(config("new-model"),()->model,()->null,System::nanoTime,index).select("compose",List.of(skill("articles")),List.of(),5);
    assertEquals(5,calls.size());assertTrue(index.load("old-model",List.of(profile)).isEmpty());
    assertEquals(1,index.load("new-model",List.of(profile)).size());
  }
  @Test void corruptRowsAreMissesAndInvalidWritesDoNotReplaceTheValidSnapshot()throws Exception {
    var index=new SqliteSkillEmbeddingIndex(data());var profile=SemanticSkillSearch.profile(skill("articles"));
    index.replace("model",Map.of(profile,new float[]{1,0}));
    try(var connection=data().getConnection();var statement=connection.createStatement()) {
      statement.executeUpdate("UPDATE skill_embedding_index SET vector_sha='broken'");
    }
    assertTrue(index.load("model",List.of(profile)).isEmpty());
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    SkillMetadataEmbedding model=texts->{calls.incrementAndGet();return texts.stream().map(t->new float[]{1,0}).toList();};
    new SemanticSkillSearch(config("model"),()->model,()->null,System::nanoTime,index).select("compose",List.of(skill("articles")),List.of(),5);
    assertEquals(2,calls.get());assertEquals(1,index.load("model",List.of(profile)).size());
    for(var vector:List.of(new float[]{Float.NaN},new float[0],new float[]{2,0},new float[8193]))
      assertThrows(IllegalArgumentException.class,()->index.replace("model",Map.of(profile,vector)));
    var oversized=new HashMap<String,float[]>();for(int i=0;i<257;i++)oversized.put("p"+i,new float[]{1,0});
    assertThrows(IllegalArgumentException.class,()->index.replace("model",oversized));
    assertThrows(IllegalArgumentException.class,()->index.replace("model",Map.of("x".repeat(2049),new float[]{1,0})));
    assertEquals(1,index.load("model",List.of(profile)).size());
    try(var connection=data().getConnection();var statement=connection.createStatement();var rs=statement.executeQuery("SELECT profile_sha,vector_sha FROM skill_embedding_index")) {
      assertTrue(rs.next());assertTrue(rs.getString(1).matches("[a-f0-9]{64}"));assertTrue(rs.getString(2).matches("[a-f0-9]{64}"));
    }
  }
  @Test void replacementIsAtomicAndPrunesRemovedProfiles()throws Exception {
    var index=new SqliteSkillEmbeddingIndex(data());index.replace("model",Map.of("one",new float[]{1,0},"two",new float[]{0,1}));
    try(var connection=data().getConnection();var statement=connection.createStatement()) {
      statement.executeUpdate("CREATE TRIGGER fail_index BEFORE INSERT ON skill_embedding_index BEGIN SELECT RAISE(ABORT,'reject'); END");
    }
    assertThrows(IllegalStateException.class,()->index.replace("model",Map.of("new",new float[]{1,0})));
    assertEquals(2,index.load("model",List.of("one","two")).size());
    try(var connection=data().getConnection();var statement=connection.createStatement()){statement.executeUpdate("DROP TRIGGER fail_index");}
    index.replace("model",Map.of("two",new float[]{0,1}));
    assertEquals(Set.of("two"),index.load("model",List.of("one","two")).keySet());
  }
  @Test void disabledPersistenceDoesNotTouchDatabaseAndDatabaseFailuresKeepLiveResults() {
    var data=org.mockito.Mockito.mock(javax.sql.DataSource.class);
    var index=new SqliteSkillEmbeddingIndex(data);
    SkillMetadataEmbedding model=texts->texts.stream().map(t->new float[]{1,0}).toList();
    var off=new SemanticSkillProperties(true,64,.55,30);
    new SemanticSkillSearch(off,()->model,()->null,System::nanoTime,index).select("compose",List.of(skill("articles")),List.of(),5);
    org.mockito.Mockito.verifyNoInteractions(data);
    var broken=new SkillEmbeddingIndex() {
      public Map<String,float[]> load(String ns,List<String> profiles){throw new IllegalStateException("private database failure");}
      public void replace(String ns,Map<String,float[]> vectors){throw new IllegalStateException("private database failure");}
      public void invalidate(String ns){throw new IllegalStateException();}
    };
    assertTrue(new SemanticSkillSearch(config("model"),()->model,()->null,System::nanoTime,broken)
        .select("compose",List.of(skill("articles")),List.of(),5).getFirst().matchedFields().contains("rrf"));
  }
  @Test void indexCancellationPropagatesAndProviderFailureDoesNotDestroySavedMetadata() {
    var index=new SqliteSkillEmbeddingIndex(data());var profile=SemanticSkillSearch.profile(skill("articles"));
    index.replace("model",Map.of(profile,new float[]{1,0}));
    var calls=new ArrayList<List<String>>();var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
    SkillMetadataEmbedding model=texts->{calls.add(List.copyOf(texts));if(fail.getAndSet(false))throw new IllegalStateException("provider unavailable");return texts.stream().map(t->new float[]{1,0}).toList();};
    var clock=new java.util.concurrent.atomic.AtomicLong();var search=new SemanticSkillSearch(config("model"),()->model,()->null,clock::get,index);
    assertTrue(search.select("compose",List.of(skill("articles")),List.of(),5).isEmpty());assertEquals(1,index.load("model",List.of(profile)).size());
    clock.set(31_000_000_000L);assertFalse(search.select("compose again",List.of(skill("articles")),List.of(),5).isEmpty());
    assertEquals(List.of(List.of("compose"),List.of("compose again")),calls);
    for(boolean cancelRead:List.of(true,false)) {
      var cancelled=new SkillEmbeddingIndex() {
        public Map<String,float[]> load(String ns,List<String> profiles){if(cancelRead)throw new java.util.concurrent.CancellationException();return Map.of();}
        public void replace(String ns,Map<String,float[]> vectors){throw new java.util.concurrent.CancellationException();}
        public void invalidate(String ns){}
      };
      assertThrows(java.util.concurrent.CancellationException.class,()->new SemanticSkillSearch(config("model"),()->model,()->null,System::nanoTime,cancelled).select("compose",List.of(skill("articles")),List.of(),5));
    }
    Thread.currentThread().interrupt();
    try{assertThrows(java.util.concurrent.CancellationException.class,()->index.replace("model",Map.of()));}
    finally{Thread.interrupted();}
    assertEquals(1,index.load("model",List.of(profile)).size());
  }
  @Test void configurationBindsWithExplicitNamespaceAndRemainsLazyByDefault() {
    var defaults=new SemanticSkillProperties(false,0,0,0);assertFalse(defaults.persistentIndexEnabled());
    assertThrows(IllegalArgumentException.class,()->config(null));
    var bound=new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.skills.semantic.enabled","true","rei.skills.semantic.persistent-index-enabled","true","rei.skills.semantic.index-namespace","test-model-v1")))
        .bind("rei.skills.semantic",SemanticSkillProperties.class).get();
    assertTrue(bound.enabled());assertTrue(bound.persistentIndexEnabled());assertEquals("test-model-v1",bound.indexNamespace());
    var data=org.mockito.Mockito.mock(javax.sql.DataSource.class);
    new SemanticSkillConfiguration().skillEmbeddingIndex(data);org.mockito.Mockito.verifyNoInteractions(data);
  }
  @Test void staleDimensionsWithUnavailableInvalidationDoNotTrapFutureSearches() {
    var profile=SemanticSkillSearch.profile(skill("articles"));
    var stale=new SkillEmbeddingIndex() {
      public Map<String,float[]> load(String ns,List<String> profiles){return Map.of(profile,new float[]{1,0});}
      public void replace(String ns,Map<String,float[]> vectors){throw new IllegalStateException("read-only index");}
      public void invalidate(String ns){throw new IllegalStateException("read-only index");}
    };
    SkillMetadataEmbedding model=texts->texts.stream().map(t->new float[]{1,0,0}).toList();
    var clock=new java.util.concurrent.atomic.AtomicLong();var search=new SemanticSkillSearch(config("model"),()->model,()->null,clock::get,stale);
    assertTrue(search.select("compose",List.of(skill("articles")),List.of(),5).isEmpty());
    clock.set(31_000_000_000L);
    assertFalse(search.select("compose",List.of(skill("articles")),List.of(),5).isEmpty());
  }
}
