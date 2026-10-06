package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class PersistentRepositoryMapTest {
  @TempDir Path root;
  DriverManagerDataSource data(){return new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("index.db"));}
  RepositoryMapService service(JavaCompiler compiler){var result=new RepositoryMapService(path->List.of("App.java"),compiler);result.setPersistentIndex(new SqliteRepositoryMapIndex(data()));return result;}
  @Test void restartReusesMetadataOnlyAfterCheckingCurrentSourceDigest() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App { String secret=\"PRIVATE_SOURCE_BODY\"; }");
    var first=service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);
    var compiler=mock(JavaCompiler.class,org.mockito.AdditionalAnswers.delegatesTo(ToolProvider.getSystemJavaCompiler()));
    var second=service(compiler).map(root,"",10);assertEquals(first.items(),second.items());
    verify(compiler,never()).getTask(any(),any(),any(),any(),any(),any());
    Files.writeString(root.resolve("App.java"),"class Changed {}");var third=service(compiler).map(root,"",10);
    assertNotEquals(first.version(),third.version());assertTrue(third.items().getFirst().symbols().stream().anyMatch(s->s.name().equals("Changed")));
    verify(compiler,times(1)).getTask(any(),any(),any(),any(),any(),any());
    try(var connection=data().getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT payload FROM repository_map_index")){assertTrue(rows.next());assertFalse(rows.getString(1).contains("PRIVATE_SOURCE_BODY"));}
  }
  @Test void corruptedMetadataFallsBackToFreshAst() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);
    try(var connection=data().getConnection();var statement=connection.createStatement()){statement.executeUpdate("UPDATE repository_map_index SET payload=x'7b7d'");}
    var compiler=mock(JavaCompiler.class,org.mockito.AdditionalAnswers.delegatesTo(ToolProvider.getSystemJavaCompiler()));
    assertEquals("App",service(compiler).map(root,"",10).items().getFirst().symbols().getFirst().name());verify(compiler,times(1)).getTask(any(),any(),any(),any(),any(),any());
  }
  @Test void rootProfileAndDeletionCannotReuseStaleMetadata() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);
    var index=new SqliteRepositoryMapIndex(data());var other=Files.createDirectory(root.resolve("other"));assertTrue(index.load(other).isEmpty());
    try(var connection=data().getConnection();var statement=connection.createStatement()){statement.executeUpdate("UPDATE repository_map_index SET profile='other-parser'");}
    var compiler=mock(JavaCompiler.class,org.mockito.AdditionalAnswers.delegatesTo(ToolProvider.getSystemJavaCompiler()));service(compiler).map(root,"",10);verify(compiler,times(1)).getTask(any(),any(),any(),any(),any(),any());
    Files.delete(root.resolve("App.java"));var empty=new RepositoryMapService(path->List.of());empty.setPersistentIndex(index);assertTrue(empty.map(root,"",10).items().isEmpty());assertTrue(index.load(root).isEmpty());
  }
  @Test void unavailableDatabaseDoesNotReplaceLiveFactsWithAnEmptyMap() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");var data=mock(javax.sql.DataSource.class);when(data.getConnection()).thenThrow(new java.sql.SQLException("private"));
    var service=new RepositoryMapService(path->List.of("App.java"));service.setPersistentIndex(new SqliteRepositoryMapIndex(data));var result=service.map(root,"",10);assertFalse(result.partial());assertEquals("App",result.items().getFirst().symbols().getFirst().name());
  }
  @Test void failedReplacementRollsBackAndRejectsOversizedInputBeforeDeletingSnapshot() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);var index=new SqliteRepositoryMapIndex(data());var saved=index.load(root);
    var oversize=new SqliteRepositoryMapIndex.Entry("App.java","a".repeat(64),"",List.of(),Collections.nCopies(128,"x".repeat(2048)));
    assertThrows(IllegalArgumentException.class,()->index.replace(root,List.of(oversize)));assertEquals(saved,index.load(root));
    try(var connection=data().getConnection();var statement=connection.createStatement()){statement.executeUpdate("CREATE TRIGGER reject_index BEFORE INSERT ON repository_map_index BEGIN SELECT RAISE(ABORT,'fixture'); END");}
    assertThrows(IllegalStateException.class,()->index.replace(root,saved.values()));assertEquals(saved,index.load(root));
  }
  @Test void boundedCorruptRowsAndInterruptedSaveCannotBecomeReusableMetadata() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);var index=new SqliteRepositoryMapIndex(data());var saved=index.load(root);
    Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->index.replace(root,List.of()));}finally{Thread.interrupted();}assertEquals(saved,index.load(root));
    try(var connection=data().getConnection();var statement=connection.createStatement()){statement.executeUpdate("UPDATE repository_map_index SET payload=zeroblob(65537)");}assertTrue(index.load(root).isEmpty());
    assertThrows(IllegalArgumentException.class,()->index.replace(root,List.of(new SqliteRepositoryMapIndex.Entry("../App.java","a".repeat(64),"",List.of(),List.of()))));
  }
  @Test void defaultConfigurationDoesNotCreateOrReadIndexTables() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");var service=new RepositoryMapService(path->List.of("App.java"));var data=mock(javax.sql.DataSource.class);service.configurePersistentIndex(data,false);assertEquals(1,service.map(root,"",10).items().size());verifyNoInteractions(data);
    var index=new SqliteRepositoryMapIndex(data);verifyNoInteractions(data);
    var generated=Files.readString(Path.of("src/main/resources/application.yaml"));assertTrue(generated.contains("${REI_REPOSITORY_MAP_PERSISTENT_INDEX_ENABLED:false}"));
  }
  @Test void incompleteInventoryDoesNotEraseTheLastCompleteSnapshot() throws Exception {
    Files.writeString(root.resolve("App.java"),"class App {}");service(ToolProvider.getSystemJavaCompiler()).map(root,"",10);var index=new SqliteRepositoryMapIndex(data());var saved=index.load(root);
    Files.writeString(root.resolve("Broken.java"),"class Broken { ???");var service=new RepositoryMapService(path->List.of("App.java","Broken.java"));service.setPersistentIndex(index);assertTrue(service.map(root,"",10).partial());assertEquals(saved,index.load(root));
  }
}
