package dev.mikoto2000.rei.artifact;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class ArtifactStoreTest {
  @Test void expiryAndExplicitDeletionRetainReceiptsAndReleaseCapacity() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("expiry.db"));
    var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-07T01:00:00Z"));
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
    var limits=new ArtifactProperties();limits.setMaxArtifacts(1);limits.setRetention(Duration.ofMinutes(1));
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),clock,limits);var owner=new AgentRunContext("run","session",root,project.id());
    var item=store.publish(owner,"one","text/plain","a.txt",new byte[0]);
    assertThatThrownBy(()->store.publish(owner,"two","text/plain","b.txt",new byte[0])).isInstanceOf(ArtifactException.class);
    now.set(now.get().plusSeconds(60));assertThat(store.get(project.id(),"session",item.artifactId()).status()).isEqualTo("EXPIRED");
    assertThatThrownBy(()->store.content(project.id(),"session",item.artifactId())).isInstanceOf(ArtifactException.class);
    assertThat(store.delete(project.id(),"session",item.artifactId()).status()).isEqualTo("DELETED");
    assertThat(store.delete(project.id(),"session",item.artifactId()).status()).isEqualTo("DELETED");
    assertThat(Files.exists(root.resolve("delivery").resolve(item.artifactId()+".bin"))).isFalse();
    assertThat(store.publish(owner,"two","text/plain","b.txt",new byte[0]).status()).isEqualTo("AVAILABLE");
  }
  @TempDir Path root;
  @Test void immutableBytesAndOwnerScopedMetadataSurviveRestartWithoutExposingPaths() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(Files.createDirectory(root.resolve("project")));
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("artifacts.db"));
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    var owner=new AgentRunContext("run","session",project.root(),project.id());
    byte[] bytes="# 結果\n完成\n".getBytes(StandardCharsets.UTF_8);
    var item=store.publish(owner,"document:one","text/markdown","結果.md",bytes);
    assertThat(item.size()).isEqualTo(bytes.length);assertThat(item.sha256()).hasSize(64);
    assertThat(item.owner()).isEqualTo("RUN");assertThat(item.storageReference()).isEqualTo("artifact:"+item.artifactId());
    assertThat(item.status()).isEqualTo("AVAILABLE");
    bytes[0]='!';assertThat(store.content(project.id(),"session",item.artifactId())).startsWith((byte)'#');
    assertThatThrownBy(()->store.get(project.id(),"other",item.artifactId())).isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(()->store.get(project.id(),null,item.artifactId())).isInstanceOf(ResourceNotFoundException.class);
    var restored=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    assertThat(restored.get(project.id(),"session",item.artifactId()).sha256()).isEqualTo(item.sha256());
    assertThat(restored.list(project.id(),"session","run",1,null).items()).extracting(Artifact::artifactId).containsExactly(item.artifactId());
    assertThat(restored.publish(owner,"document:one","text/markdown","結果.md",restored.content(project.id(),"session",item.artifactId())).artifactId()).isEqualTo(item.artifactId());
    projects.remove(project.root());assertThatThrownBy(()->restored.get(project.id(),"session",item.artifactId())).isInstanceOf(ResourceNotFoundException.class);
  }
  @Test void filenameTraversalUnsupportedMediaOversizeAndTamperingFailClosed() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("artifacts.db"));
    var limits=new ArtifactProperties();limits.setMaxBytes(8);
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),limits);
    var owner=new AgentRunContext("run","session",root,project.id());
    for(String name:new String[]{"../outside","C:\\other","a/b","a\r\nb"})assertThatThrownBy(()->store.publish(owner,"bad","text/plain",name,new byte[1])).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->store.publish(owner,"bad","text/html","a.html",new byte[1])).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->store.publish(owner,"big","text/plain","a.txt",new byte[9])).isInstanceOf(ArtifactException.class);
    var item=store.publish(owner,"good","text/plain","a.txt","safe".getBytes(StandardCharsets.UTF_8));
    Files.writeString(root.resolve("delivery").resolve(item.artifactId()+".bin"),"evil");
    assertThatThrownBy(()->store.content(project.id(),"session",item.artifactId())).isInstanceOf(ArtifactException.class);
    assertThat(store.get(project.id(),"session",item.artifactId()).status()).isEqualTo("STALE");
    assertThatThrownBy(()->store.content(project.id(),"session","../outside")).isInstanceOf(ResourceNotFoundException.class);
  }
}
