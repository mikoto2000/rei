package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@Tag("integration")
class EpisodeRevisionPagingTest {
 @TempDir Path dir;
 @Test void pagesBeyondOneHundredWithoutSkippingWhenANewRevisionArrives() {
  var repo=new EpisodeRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db")));
  for(int i=0;i<105;i++)repo.saveBatch("s",i,i+1,List.of(episode("v"+i)));
  var first=repo.revisionPage("e","p",null,3);
  assertEquals(List.of("v104","v103","v102"),first.items().stream().map(Episode::revision).toList());
  repo.saveBatch("s",105,106,List.of(episode("new")));
  var seen=new ArrayList<>(first.items());String cursor=first.nextCursor();
  while(cursor!=null){var page=repo.revisionPage("e","p",cursor,3);seen.addAll(page.items());cursor=page.nextCursor();}
  assertEquals(105,seen.size());assertEquals(105,seen.stream().map(Episode::revision).distinct().count());
  assertEquals("v0",seen.getLast().revision());
  assertTrue(repo.revisionPage("e","other",null,3).items().isEmpty());
  assertThrows(IllegalArgumentException.class,()->repo.revisionPage("e","other","v104",3));
  assertThrows(IllegalArgumentException.class,()->repo.revisionPage("e","p",null,11));
 }
 static Episode episode(String revision){return new Episode("e","p","s",revision,"2026-10-11T00:00:00Z",Episode.Status.UNVERIFIED,"design","summary",List.of(new Episode.Claim("reason","proposal",Episode.Evidence.MODEL_PROPOSAL,"r","assistant")),.5);}
}
