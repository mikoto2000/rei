package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.artifact.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;

@Tag("integration")
class DocumentRendererProcessTest {
  @TempDir Path root;
  public static class SleepingRenderer{public static void main(String[] args)throws Exception{Thread.sleep(60000);}}
  Path sleeper()throws Exception{
    Path jar=root.resolve("sleeper.jar");var manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,SleepingRenderer.class.getName());String entry=SleepingRenderer.class.getName().replace('.','/')+".class";
    try(var output=new JarOutputStream(Files.newOutputStream(jar),manifest);var bytes=SleepingRenderer.class.getClassLoader().getResourceAsStream(entry)){assertNotNull(bytes);output.putNextEntry(new JarEntry(entry));bytes.transferTo(output);output.closeEntry();}return jar;
  }
  @Test void actualChildJvmDeadlineKillsProcessAndReturnsWithinBound()throws Exception{
    var renderer=new DocumentRendererService.PlantUmlRenderer("",sleeper().toString(),Duration.ofMillis(400));long start=System.nanoTime();assertThrows(TimeoutException.class,()->renderer.render("@startuml\nAlice -> Bob\n@enduml"));assertTrue(System.nanoTime()-start<Duration.ofSeconds(6).toNanos());
  }
  @Test void cancellingActualChildJvmFinishesOwnedWorker()throws Exception{
    var renderer=new DocumentRendererService.PlantUmlRenderer("",sleeper().toString(),Duration.ofSeconds(20));var worker=Executors.newSingleThreadExecutor();try{var task=worker.submit(()->{try{return renderer.render("@startuml\nAlice -> Bob\n@enduml");}catch(Exception failure){throw new RuntimeException(failure);}});Thread.sleep(300);assertTrue(task.cancel(true));}finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
  }
  @Test void realPlantUmlErrorAndPngAreVerifiedAndDeliveredThroughExistingStore()throws Exception{
    String jar=System.getProperty("rei.test.plantuml.jar","");Assumptions.assumeTrue(!jar.isBlank()&&Files.isRegularFile(Path.of(jar)),"PlantUML jar not configured; use -Drei.test.plantuml.jar=/absolute/plantuml.jar");
    var renderer=new DocumentRendererService.PlantUmlRenderer("",jar,Duration.ofSeconds(20));var invalid=renderer.render("@startuml\nthis is invalid nonsense ??\n@enduml\n");assertNotEquals(0,invalid.exitCode());
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("renderer.db"));var store=new ArtifactStore(source,projects,root.resolve("artifacts"),Clock.systemUTC(),new ArtifactProperties());var owner=new AgentRunContext("actual","session",root,project.id());
    String text="@startuml\nAlice -> Bob : hello\n@enduml\n";Files.writeString(root.resolve("diagram.puml"),text);var service=new DocumentRendererService(source,Clock.systemUTC(),true,renderer,store);var result=service.validate(owner,new DocumentRendererService.Request("diagram.puml",TextDocumentTransaction.hash(text)));
    assertEquals("RENDERED",result.status(),result.toString());assertEquals(0,result.exitCode());assertFalse(result.parseError());assertTrue(result.size()>24);assertNotNull(result.artifact());assertEquals("AVAILABLE",result.artifact().status());assertEquals(result.outputSha256(),result.artifact().sha256());assertEquals(result.size(),store.content(project.id(),"session",result.artifact().artifactId()).length);assertEquals(text,Files.readString(root.resolve("diagram.puml")));
    assertEquals(result,new DocumentRendererService(source,Clock.systemUTC(),true,input->{throw new AssertionError("No replay");},store).inspect(owner,result.id()));
  }
}
