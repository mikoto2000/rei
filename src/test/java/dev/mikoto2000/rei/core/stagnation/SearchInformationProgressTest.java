package dev.mikoto2000.rei.core.stagnation;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
class SearchInformationProgressTest {
 @TempDir Path root;
 @Test void sameQueryCanDiscoverNewWebResultButMetadataDoesNotCreateFacts() {
  var e=new ProgressEvaluator(root);var before=e.beforeTool("webSearch","{}");
  String result="[{\"url\":\"https://example.com/one\",\"snippet\":\"fact\",\"retrievedAt\":\"one\"}]";
  assertThat(e.afterTool("webSearch","{}",result,before)).extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.NEW_INFORMATION);
  assertThat(e.afterTool("webSearch","{}",result.replace("\"one\"","\"two\""),before)).isEmpty();
  assertThat(e.afterTool("webSearch","{}",result.replace("/one","/two"),before)).hasSize(1);
 }
 @Test void grepMatchesDeduplicateRegardlessOfQueryOrRanking() {
  var e=new ProgressEvaluator(root);var before=e.beforeTool("grepMultiQuery","{}");
  String result="[{\"queryIndex\":0,\"pattern\":\"foo\",\"matches\":[{\"path\":\"A\",\"line\":3,\"content\":\"foo bar\"}]}]";
  assertThat(e.afterTool("grepMultiQuery","{}",result,before)).hasSize(1);
  assertThat(e.afterTool("grepMultiQuery","{}",result.replace("\"foo\"","\"bar\""),before)).isEmpty();
  assertThat(e.afterTool("grepMultiQuery","{}",result.replace("foo bar","foo baz"),before)).hasSize(1);
 }
}
