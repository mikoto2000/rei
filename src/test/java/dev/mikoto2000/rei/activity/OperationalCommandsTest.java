package dev.mikoto2000.rei.activity;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class OperationalCommandsTest {
  @TempDir Path dir;
  @Test void allOperationsAndCompletionAreAvailableWithoutLlm() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+dir.resolve("commands.db"));
    var toolkit=new ClassificationToolkit(new ActivityProperties(),new OperationalRules(dir.resolve("rules.yaml")),new ClassificationTelemetryRepository(ds),Clock.systemUTC());
    var root=new ActivityCommand();root.operationalToolkit(toolkit,new ClassificationRuleSuggestions(toolkit,(input,schema)->{throw new IllegalStateException();}));
    var cli=new picocli.CommandLine(root);var output=new java.io.StringWriter();cli.setOut(new java.io.PrintWriter(output));
    for(String action:new String[]{"status","reload","unknowns","rules","suggest-rules"})assertEquals(0,cli.execute("classification",action));
    for(String action:new String[]{"uncertain","suggest-rules"})assertEquals(0,cli.execute("behavior",action));
    assertEquals(0,cli.execute("classification","unknowns","--top","1"));
    assertTrue(output.toString().contains("Classification"));
    assertTrue(java.util.stream.StreamSupport.stream(new ActivityCommand.ClassificationCommand.ClassificationCandidates().spliterator(),false).toList().contains("suggest-rules"));
    assertTrue(java.util.stream.StreamSupport.stream(new ActivityCommand.BehaviorCommand.BehaviorCandidates().spliterator(),false).toList().contains("uncertain"));
  }
}
