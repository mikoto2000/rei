package dev.mikoto2000.rei.paper;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class PaperProviderWorkflowTest {
  @TempDir Path root;
  String openAlex(String title,Integer year,Boolean open,String doi) {
    return "{\"title\":\""+title+"\",\"doi\":\""+doi+"\",\"publication_year\":"+year+",\"open_access\":{\"is_oa\":"+open+"},\"abstract_inverted_index\":{\"source\":[0]}}";
  }
  PaperSearchQuery query(int limit,boolean open) {
    return new PaperSearchQuery("GUI agent & RAG",2025,2025,List.of(),List.of(),open,PaperSearchQuery.Sort.RELEVANCE,limit);
  }
  @Test void providerFiltersUntrustedResponseBeforePersistenceThenLibraryAndSummarySurviveRestart() {
    var config=new PaperProperties();var op=PaperOperation.local("session");var calls=new ArrayList<java.net.URI>();
    String response="{\"results\":["+String.join(",",openAlex("Old",2024,true,"10.1/old"),openAlex("Closed",2025,false,"10.1/closed"),
        openAlex("Unknown",null,true,"10.1/unknown"),openAlex("Selected",2025,true,"https://doi.org/10.1/selected"),openAlex("Excess",2025,true,"10.1/excess"))+ "]}";
    PaperHttpClient http=(uri,type,max,operation)->{operation.check();calls.add(uri);assertEquals("api.openalex.org",uri.getHost());
      assertTrue(uri.getRawQuery().contains("search=GUI+agent+%26+RAG"));return response.getBytes(StandardCharsets.UTF_8);};
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("papers.db"));
    var repo=new SqlitePaperRepository(source);var refs=new PaperSessionReferences();
    var result=new PaperSearchService(new OpenAlexSearchProvider(http,config),new CrossrefSearchProvider(http,config),repo,refs,config).search(query(1,true),op);
    assertEquals(List.of("Selected"),result.papers().stream().map(Paper::title).toList());assertTrue(result.warnings().isEmpty());assertEquals(1,calls.size());
    assertEquals(1,repo.search("",20).size());
    var store=new PaperArtifactStore(root.resolve("artifacts"),config);var library=new PaperLibraryService(repo,store,refs,config);
    var selected=library.get("1",op);assertEquals("10.1/selected",selected.doi());
    var content=new PaperContentService(store,(u,t,m,o)->{fail("Abstract-only fixture must not fetch PDFs");return null;},new PaperExtractionService(config),config,repo);
    var modelCalls=new java.util.concurrent.atomic.AtomicInteger();
    var model=new PaperLanguageModel(){public String model(){return "fixture-model";}public String generate(String system,String input,PaperOperation operation){operation.check();modelCalls.incrementAndGet();assertTrue(input.contains("source"));
      return "{\"content\":{\"problem\":\"unknown\",\"background\":\"unknown\",\"contributions\":[],\"method\":\"unknown\",\"datasets\":[],\"evaluation\":\"unknown\",\"results\":[\"source\"],\"limitations\":[\"abstract only\"],\"futureWork\":[]},\"evidence\":[{\"claim\":\"source\",\"section\":\"Abstract\",\"page\":0,\"text\":\"source\"}]}";}};
    var summary=new PaperSummaryService(repo,content,model,new PaperSummaryValidator(),config).summarize(selected,PaperSummary.Mode.STANDARD,false,op);
    assertEquals(StructuredPaper.Availability.ABSTRACT_ONLY,summary.availability());assertEquals("source",summary.evidence().getFirst().text());
    var restarted=new SqlitePaperRepository(source);var restored=new PaperLibraryService(restarted,store,new PaperSessionReferences(),config).get(selected.id(),PaperOperation.local("new-session"));
    assertEquals(selected.id(),restored.id());
    assertEquals(summary,new PaperSummaryService(restarted,content,model,new PaperSummaryValidator(),config).summarize(restored,PaperSummary.Mode.STANDARD,false,op));assertEquals(1,modelCalls.get());
    assertThrows(PaperException.class,()->new PaperLibraryService(restarted,store,new PaperSessionReferences(),config).get("1",PaperOperation.local("other")));
  }
  @Test void fallbackEnforcesYearAndCountAndDoesNotInventOpenAccess() {
    var config=new PaperProperties();var calls=new ArrayList<String>();
    PaperHttpClient http=(uri,type,max,op)->{op.check();calls.add(uri.getHost());
      if(uri.getHost().equals("api.openalex.org"))return "{}".getBytes(StandardCharsets.UTF_8);
      return "{\"message\":{\"items\":[{\"title\":[\"Old\"],\"DOI\":\"10.1/old\",\"published\":{\"date-parts\":[[2024]]}},{\"title\":[\"Selected\"],\"DOI\":\"10.1/selected\",\"published\":{\"date-parts\":[[2025]]}},{\"title\":[\"Excess\"],\"DOI\":\"10.1/excess\",\"published\":{\"date-parts\":[[2025]]}}]}}".getBytes(StandardCharsets.UTF_8);};
    var repo=new SqlitePaperRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("fallback.db")));
    var service=new PaperSearchService(new OpenAlexSearchProvider(http,config),new CrossrefSearchProvider(http,config),repo,new PaperSessionReferences(),config);
    var result=service.search(query(1,false),PaperOperation.local("session"));
    assertEquals(List.of("Selected"),result.papers().stream().map(Paper::title).toList());assertEquals(1,result.warnings().size());
    assertEquals(List.of("api.openalex.org","api.crossref.org"),calls);
    assertTrue(new CrossrefSearchProvider(http,config).search(query(1,true),PaperOperation.local("session")).isEmpty());
  }
}
