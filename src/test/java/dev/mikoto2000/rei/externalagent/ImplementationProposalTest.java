package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ImplementationProposalTest {
  @TempDir Path root;
  @Test void validatesAllBaselinesBeforeWritingAnyFile() throws Exception {
    Files.writeString(root.resolve("A.txt"),"before\n");Files.writeString(root.resolve("B.txt"),"before\n");
    String hash=ImplementationProposal.sha256(Files.readAllBytes(root.resolve("A.txt")));
    var proposal=new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",hash,"after\n"),new ImplementationProposal.Edit("B.txt",hash,"next\n")));
    var manifest=Map.of("A.txt",hash,"B.txt",hash);
    Files.writeString(root.resolve("B.txt"),"changed independently\n");
    assertThrows(IllegalArgumentException.class,()->proposal.apply(root,manifest));
    assertEquals("before\n",Files.readString(root.resolve("A.txt")));
    Files.writeString(root.resolve("B.txt"),"before\n");proposal.apply(root,manifest);
    assertEquals("after\n",Files.readString(root.resolve("A.txt")));assertEquals("next\n",Files.readString(root.resolve("B.txt")));
  }
  @Test void excludesUnexpectedTraversalSecretDuplicateBinaryAndOversizedChanges() throws Exception {
    Files.writeString(root.resolve("A.txt"),"before");String hash=ImplementationProposal.sha256("before".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for(String path:List.of("../A.txt",".env","A.txt/../A.txt","unknown.txt","/A.txt")) {
      var p=new ImplementationProposal(List.of(new ImplementationProposal.Edit(path,hash,"after")));
      assertThrows(IllegalArgumentException.class,()->p.apply(root,Map.of("A.txt",hash)),path);
    }
    for(String replacement:List.of("bad\u0000binary","x".repeat(65537))) {
      var p=new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",hash,replacement)));
      assertThrows(IllegalArgumentException.class,()->p.apply(root,Map.of("A.txt",hash)));
    }
    var duplicate=new ImplementationProposal(List.of(new ImplementationProposal.Edit("A.txt",hash,"one"),new ImplementationProposal.Edit("A.txt",hash,"two")));
    assertThrows(IllegalArgumentException.class,()->duplicate.apply(root,Map.of("A.txt",hash)));
    assertEquals("before",Files.readString(root.resolve("A.txt")));
  }
  @Test void rejectsIncompleteOrExtraStructuredFields() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    String hash="a".repeat(64);
    assertEquals(1,ImplementationProposal.parse(mapper.readTree("{\"edits\":[{\"path\":\"A.txt\",\"expectedSha256\":\""+hash+"\",\"replacement\":\"after\"}]}")).edits().size());
    for(String json:List.of("{}","{\"edits\":[]}","{\"edits\":[],\"command\":\"evil\"}","{\"edits\":[{\"path\":\"A.txt\"}]}"))assertThrows(IllegalArgumentException.class,()->ImplementationProposal.parse(mapper.readTree(json)));
  }
}
