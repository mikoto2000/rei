package dev.mikoto2000.rei.core;

import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Same fixture/API on baseline and each phase; no live LLM or network. */
@org.junit.jupiter.api.Tag("integration")
class FileOperationsBenchmarkTest {
  @TempDir Path root;
  @Test void measureTwoPatternSearchAndRead()throws Exception {
    var source=new StringBuilder();for(int i=0;i<150;i++)source.append(i==50?"first\n":i==100?"second\n":"context\n");
    var file=root.resolve("sample.txt");Files.writeString(file,source);
    var times=new ArrayList<Long>();long reads=0,bytes=0,returned=0;
    try(var recording=new Recording()){
      recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO);recording.start();
      for(int repeat=0;repeat<10;repeat++){
        var tools=new Tools(){@Override List<String> listFile(String base,Path directory){return List.of("sample.txt");}};
        var queries=List.of(query("first"),query("second"));long before=System.nanoTime();
        var result=tools.searchAndRead(new Tools.SearchAndReadRequest(queries,5,1),root);times.add(System.nanoTime()-before);
        assertEquals(1,result.size());assertNull(result.getFirst().error());assertEquals(22,result.getFirst().totalLines());
        returned+=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(result).length;
      }
      recording.stop();var trace=root.resolve("reads.jfr");recording.dump(trace);
      for(var event:RecordingFile.readAllEvents(trace))if(event.getEventType().getName().equals("jdk.FileRead") && file.toString().equals(event.getString("path"))){reads++;bytes+=event.getLong("bytesRead");}
    }
    assertTrue(reads>0,"JFR must observe real reads; unavailable instrumentation is not zero I/O");
    Collections.sort(times);
    String report="{\"case\":\"two-pattern-search-and-read\",\"repetitions\":10,\"fileReads\":"+reads+",\"bytesRead\":"+bytes+",\"returnedJsonBytes\":"+returned+",\"medianNanos\":"+times.get(5)+"}";
    Path output=Path.of(System.getProperty("fileOperations.benchmarkOutput","target/file-operations-benchmark.json"));Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,report);System.out.println(report);
  }
  static Tools.GrepQuery query(String pattern){return new Tools.GrepQuery(pattern,".",false,true,false,false,0,0,null,true,null,null);}
}
