package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class DocumentDraftAgentTest {
  @TempDir Path root;
  SubAgentDefinition definition() {
    return new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile","grepMultiQuery","searchAndRead","readPdfFile")),m->false)
        .load(Path.of("config/subagents/document-editor.yaml"));
  }
  tools.jackson.databind.node.ObjectNode envelope(String kind,String before,String after) {
    var json=tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
    json.put("status","SUCCESS").put("summary","Draft only; review required");json.putArray("warnings");
    var result=json.putObject("result");result.putArray("unverified").add("Rendering and factual correctness require independent checks");
    var proposal=result.putObject("proposal");proposal.put("path","document.txt").put("format",kind).put("expectedText",before).put("replacement",after).put("reason","Requested revision");
    result.putArray("sources");return json;
  }
  @Test void actualSampleIsReadOnlyBoundedAndValidatesSingleFileProposals() {
    var agent=definition();assertEquals("document-editor",agent.id());assertTrue(agent.maxSteps()<=10);assertTrue(agent.timeout().compareTo(java.time.Duration.ofSeconds(120))<=0);
    assertFalse(agent.requestedTools().contains("applyTextChangeSet"));assertFalse(agent.requestedTools().contains("runCommand"));
    var validator=new SubAgentResultValidator();
    for(String format:List.of("TEXT","MARKDOWN","MERMAID","PLANTUML"))assertTrue(validator.validate(agent,envelope(format,"before\r\n","after\r\n")).valid());
    assertFalse(validator.validate(agent,envelope("DOCX","before","after")).valid());
    assertFalse(validator.validate(agent,envelope("TEXT","","after")).valid());
    var unknown=envelope("TEXT","before","after");((tools.jackson.databind.node.ObjectNode)unknown.at("/result/proposal")).put("command","overwrite all files");
    assertFalse(validator.validate(agent,unknown).valid());
    var partial=envelope("TEXT","before","after");partial.put("status","PARTIAL");((tools.jackson.databind.node.ObjectNode)partial.get("result")).putNull("proposal");
    assertTrue(validator.validate(agent,partial).valid());
  }
  @Test void validatedDraftPersistsWithoutWritingThenAppliesExactlyReviewedFileAndRejectsStaleSource() throws Exception {
    var source=new org.sqlite.SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("drafts.db"));
    var repository=new dev.mikoto2000.rei.core.TextChangeSetRepository(source);
    var changes=new dev.mikoto2000.rei.core.TextChangeSetService(repository);
    var project=new dev.mikoto2000.rei.core.project.ProjectContext(UUID.randomUUID().toString(),"Project",root);
    String before="graph TD\r\n  A-->B\r\n",after="graph TD\r\n  A-->B\r\n  B-->C\r\n";
    var file=Files.writeString(root.resolve("document.txt"),before);
    var base=changes.readBase(project,"document.txt");var draft=envelope("MERMAID",base.text(),after);
    assertTrue(new SubAgentResultValidator().validate(definition(),draft).valid());var proposal=draft.at("/result/proposal");
    var saved=changes.propose(project,new dev.mikoto2000.rei.core.TextChangeSetService.Request(proposal.path("path").asString(),proposal.path("expectedText").asString(),proposal.path("replacement").asString()));
    assertEquals(before,Files.readString(file));assertEquals("PROPOSED",saved.status());assertTrue(saved.diff().contains("B-->C"));
    var restarted=new dev.mikoto2000.rei.core.TextChangeSetService(new dev.mikoto2000.rei.core.TextChangeSetRepository(source));
    var applied=restarted.apply(project,saved.id(),saved.proposalSha256(),(path,oldText,newText)->Files.writeString(path,newText));
    assertEquals("APPLIED",applied.status());assertEquals(after,Files.readString(file));
    var stale=restarted.propose(project,new dev.mikoto2000.rei.core.TextChangeSetService.Request("document.txt",after,before));
    Files.writeString(file,"concurrent user edit");
    assertEquals("STALE",restarted.apply(project,stale.id(),stale.proposalSha256(),(p,o,n)->fail("Must not overwrite changed source")).status());
    assertEquals("concurrent user edit",Files.readString(file));
    assertThrows(IllegalArgumentException.class,()->restarted.propose(project,new dev.mikoto2000.rei.core.TextChangeSetService.Request("../outside",before,after)));
  }
}
