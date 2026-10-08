package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SearchRankingBudgetTest {
  @TempDir Path root;
  Tools tools(List<String> paths){return new Tools(){@Override List<String> listFile(String base,Path directory){return paths;}};}
  Tools.GrepQuery query(String text){return new Tools.GrepQuery(text,".",false,true,false,false,0,0,null,true,null,null);}
  void write(String path,String text)throws Exception{Files.writeString(root.resolve(path),text);}
  @Test void exactFilenameAndAstDeclarationBeatBodyReferences()throws Exception {
    write("a.txt","UserService reference");write("UserService.java","class UserService {}");
    var result=tools(List.of("a.txt","UserService.java")).searchAndRead(new Tools.SearchAndReadRequest(List.of(query("UserService")),0,2),root);
    assertEquals("UserService.java",result.getFirst().path());assertTrue(result.getFirst().matchedBy().contains("symbol-exact"));assertTrue(result.getFirst().score()>result.getLast().score());
  }
  @Test void methodDeclarationsAndExplicitPathsAreRankedWithoutLlmCalls()throws Exception {
    write("A.java","class A { void reference(){ z.execute(); } }");write("Z.java","class Z { void execute() {} }");
    var tools=tools(List.of("A.java","Z.java"));
    var method=tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("execute")),0,2),root);
    assertEquals("Z.java",method.getFirst().path());assertTrue(method.getFirst().matchedBy().contains("symbol-exact"));
    var explicit=tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("execute")),0,2,8192,2000,500,List.of("A.java")),root);
    assertEquals("A.java",explicit.getFirst().path());assertTrue(explicit.getFirst().matchedBy().contains("explicit-path"));
  }
  @Test void aHugeEarlyHitFileCannotHideTheLaterExactTargetOrConsumeItsBudget()throws Exception {
    write("a.txt","Target reference\n".repeat(2000));write("Target.java","class Target { void work(){} }");
    var result=tools(List.of("a.txt","Target.java")).searchAndRead(new Tools.SearchAndReadRequest(List.of(query("Target")),25,2,4096,1200,500,List.of()),root);
    assertEquals(2,result.size());assertEquals("Target.java",result.getFirst().path());assertFalse(result.getFirst().sections().isEmpty());assertFalse(result.getLast().sections().isEmpty());
    var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);assertTrue(dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(json)<=1200);
  }
  @Test void japaneseTokenLimitsAndContinuationAreAppliedToTheWholeJsonResult()throws Exception {
    write("a.txt","ヒット"+"日本語".repeat(3000));write("b.txt","ヒット"+"日本語".repeat(3000));
    var result=tools(List.of("a.txt","b.txt")).searchAndRead(new Tools.SearchAndReadRequest(List.of(query("ヒット")),0,2,8192,1200,100,List.of()),root);
    assertEquals(2,result.size());assertTrue(result.stream().allMatch(Tools.SearchAndReadResult::truncated));assertTrue(result.stream().allMatch(item->item.nextRead()!=null));
    var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);assertTrue(dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(json)<=1200);
  }
  @Test void tiedResultsAreStableAndWorkingFilesHaveAnExplicitReason()throws Exception {
    write("a.txt","hit");write("z.txt","hit");var tools=tools(List.of("z.txt","a.txt"));
    var request=new Tools.SearchAndReadRequest(List.of(query("hit")),0,2);
    assertEquals(List.of("a.txt","z.txt"),tools.searchAndRead(request,root).stream().map(Tools.SearchAndReadResult::path).toList());
    assertEquals(List.of("a.txt","z.txt"),tools.searchAndRead(request,root).stream().map(Tools.SearchAndReadResult::path).toList());
    var active=tools(List.of("a.txt","z.txt"));active.workingSet().recordRead(root.resolve("z.txt"));
    assertEquals("z.txt",active.searchAndRead(request,root).getFirst().path());
  }
  @Test void inventoryCannotEnumerateOutsideTheProject()throws Exception {
    assertThrows(java.io.IOException.class,()->new Tools().listFile("..",root));
    assertThrows(java.io.IOException.class,()->new Tools().listFile(".git",root));
  }
  @Test void noMatchesAreNormalButInvalidBudgetsAreExplicitErrors()throws Exception {
    write("a.txt","nothing");var tools=tools(List.of("a.txt"));assertTrue(tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")),0,2),root).isEmpty());
    assertThrows(IllegalArgumentException.class,()->tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")),0,2,0,1000,500,List.of()),root));
  }
}
