package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileOperationsOptimizationTest {
  @TempDir Path root;
  Tools tools() { return new Tools(); }
  @Test void queriesAndSectionsReadEachFileOnce() throws Exception {
    Files.writeString(root.resolve("a.txt"),"first\nsecond\n");
    var reads=new java.util.concurrent.atomic.AtomicInteger();
    var tools=new Tools(){
      @Override List<String> listFile(String base,Path directory){return List.of("a.txt");}
      @Override FileSnapshots newSnapshots(){return new FileSnapshots(path->{reads.incrementAndGet();return Files.readAllBytes(path);});}
    };
    var result=tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("first"),query("second")),2,1),root);
    assertEquals(1,reads.get());assertEquals(2,result.getFirst().totalLines());assertNotNull(result.getFirst().version());
  }
  @Test void enormousUtf8LineHasSafeContinuationAndRejectsChangedVersion()throws Exception {
    String value="日本語😀".repeat(20000);Files.writeString(root.resolve("a.txt"),value);
    var tools=tools();var result=tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",null,null)),root).getFirst();
    assertTrue(result.truncated());assertTrue(result.returnedBytes()<=TextReadBudget.MAX_BYTES);
    var body=new StringBuilder(String.join("",result.content()));
    while(result.nextRead()!=null){result=tools.readMultiFile(List.of(result.nextRead()),root).getFirst();assertNull(result.error());body.append(String.join("",result.content()));}
    assertEquals(value,body.toString());
    var first=tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",null,null)),root).getFirst();
    Files.writeString(root.resolve("a.txt"),"changed");
    assertTrue(tools.readMultiFile(List.of(first.nextRead()),root).getFirst().error().contains("version mismatch"));
  }
  @Test void snapshotsRejectMutationCapacityDeletionBinaryAndTraversal()throws Exception {
    var file=root.resolve("a.txt");Files.writeString(file,"before");
    var racing=new FileSnapshots(path->{var bytes=Files.readAllBytes(path);Files.writeString(path,"different size");return bytes;});
    assertThrows(java.io.IOException.class,()->racing.get(root,file));
    Files.writeString(file,"x".repeat(FileSnapshots.MAX_FILE_BYTES+1));assertThrows(java.io.IOException.class,()->new FileSnapshots().get(root,file));
    Files.delete(file);assertThrows(NoSuchFileException.class,()->new FileSnapshots().get(root,file));
    assertThrows(java.io.IOException.class,()->new FileSnapshots().get(root,root.resolve("../outside")));
    Files.write(file,new byte[]{0,1});assertNotNull(tools().readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",null,null)),root).getFirst().error());
  }
  @Test void repeatedRangesShareSnapshotAndCancellationPropagates()throws Exception {
    Files.writeString(root.resolve("a.txt"),"a\r\nb\r\n");var reads=new java.util.concurrent.atomic.AtomicInteger();
    var tools=new Tools(){@Override FileSnapshots newSnapshots(){return new FileSnapshots(path->{reads.incrementAndGet();return Files.readAllBytes(path);});}};
    var results=tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",1,1),new Tools.ReadFileRequest("a.txt",2,2)),root);
    assertEquals(1,reads.get());assertEquals(List.of("b"),results.getLast().content());
    Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->tools.readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",1,1)),root));}finally{Thread.interrupted();}
  }
  @Test void cacheCapacityIsBoundedAndParallelReadsAreCoalesced()throws Exception {
    var reads=new java.util.concurrent.atomic.AtomicInteger();var snapshots=new FileSnapshots(path->{reads.incrementAndGet();return Files.readAllBytes(path);});
    Files.writeString(root.resolve("same.txt"),"same");
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)){
      var futures=new ArrayList<java.util.concurrent.Future<String>>();for(int i=0;i<20;i++)futures.add(executor.submit(()->snapshots.get(root,root.resolve("same.txt")).version()));
      for(var future:futures)assertEquals(futures.getFirst().get(),future.get());
    }
    assertEquals(1,reads.get());
    byte[] large=new byte[FileSnapshots.MAX_FILE_BYTES];
    for(int i=0;i<8;i++){var path=root.resolve("large"+i);Files.write(path,large);if(i<7)snapshots.get(root,path);else assertThrows(java.io.IOException.class,()->snapshots.get(root,path));}
    assertEquals(8,reads.get());
  }
  @Test void searchGiantLineAndExplicitCharsetStayBounded()throws Exception {
    Files.writeString(root.resolve("a.txt"),"hit"+"日本語".repeat(20000));
    var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return List.of("a.txt");}};
    var grep=tools.grepMultiQuery(List.of(query("hit")),root).getFirst();assertTrue(grep.truncated());assertNotNull(grep.nextRead());
    assertTrue(grep.matches().getFirst().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<TextReadBudget.MAX_BYTES);
    var result=tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")),0,1),root).getFirst();assertTrue(result.truncated());assertNotNull(result.nextRead());
    Files.write(root.resolve("a.txt"),"\ufeff日本語\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
    var q=new Tools.GrepQuery("日本語",".",false,true,false,false,0,0,10,true,null,null,"UTF-16LE");
    assertEquals("日本語",tools.grepMultiQuery(List.of(q),root).getFirst().matches().getFirst().content());
  }
  @Test void largeResultsAndFileErrorsStayWithinWireLimitAndExposeOmissions()throws Exception {
    Files.writeString(root.resolve("a.txt"),"hit\t\"\\\n".repeat(1000));
    var paths=new ArrayList<String>();paths.add("a.txt");for(int i=0;i<1000;i++)paths.add("missing"+i);
    var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return paths;}};
    var queries=new ArrayList<Tools.GrepQuery>();for(int i=0;i<20;i++)queries.add(query("hit"));
    var results=tools.grepMultiQuery(queries,root);
    assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(results).length<=1024*1024);
    assertTrue(results.stream().anyMatch(Tools.GrepQueryResult::truncated));
    // Separate missing-file request reaches the error reporting boundary rather than a hit cap.
    var errors=tools.grepMultiQuery(List.of(query("no match")),root).getFirst();
    assertEquals(32,errors.fileErrors().size());assertEquals(968,errors.omittedErrors());assertTrue(errors.truncated());
  }
  @Test void tooManyCandidatesFailExplicitlyAndEmptyFileStillHasVersion()throws Exception {
    Files.writeString(root.resolve("a.txt"),"");
    assertNotNull(tools().readMultiFile(List.of(new Tools.ReadFileRequest("a.txt",null,null)),root).getFirst().version());
    var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return java.util.stream.IntStream.range(0,1025).mapToObj(i->"file"+i).toList();}};
    assertThrows(java.io.IOException.class,()->tools.grepMultiQuery(List.of(query("x")),root));
  }
  @Test void directoryLinksCannotAliasTheReadingBoundary()throws Exception {
    var directory=Files.createDirectory(root.resolve("real"));Files.writeString(directory.resolve("a.txt"),"secret");var link=root.resolve("alias");
    if(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")){
      var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),directory.toString()).redirectErrorStream(true).start();
      assertTrue(process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
    } else Files.createSymbolicLink(link,directory);
    try{assertNotNull(tools().readMultiFile(List.of(new Tools.ReadFileRequest("alias/a.txt",null,null)),root).getFirst().error());}
    finally{Files.delete(link);}
  }
  @Test void totalSearchLineLimitCutsActualBody() throws Exception {
    var paths = new ArrayList<String>();
    for (int i=0;i<8;i++) {String name="f"+i+".txt"; Files.writeString(root.resolve(name),"context\n".repeat(499)+"hit\n"+"context\n".repeat(500));paths.add(name);}
    var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return paths;}};
    var result=tools.searchAndRead(new Tools.SearchAndReadRequest(List.of(query("hit")),1000,8),root);
    assertTrue(result.stream().mapToInt(Tools.SearchAndReadResult::totalLines).sum()<=5000);
    assertTrue(result.stream().anyMatch(Tools.SearchAndReadResult::truncated));
  }
  @Test void externalSameLengthChangeCannotReuseStaleSearch() throws Exception {
    var path=root.resolve("a.txt");Files.writeString(path,"hit\n");var stamp=Files.getLastModifiedTime(path);
    var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return List.of("a.txt");}};
    assertEquals(1,tools.grepMultiQuery(List.of(query("hit")),root).getFirst().matches().size());
    Files.writeString(path,"new\n");Files.setLastModifiedTime(path,stamp);
    assertTrue(tools.grepMultiQuery(List.of(query("hit")),root).getFirst().matches().isEmpty());
  }
  static Tools.GrepQuery query(String pattern){return new Tools.GrepQuery(pattern,".",false,true,false,false,0,0,null,true,null,null);}
}
