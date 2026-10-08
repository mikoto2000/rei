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
  @Test void measureSmallEditThroughExistingTools()throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    boolean partial=Arrays.stream(TextChangeSetService.Request.class.getRecordComponents()).anyMatch(component->component.getName().equals("edits"));
    var source=new StringBuilder();for(int i=0;i<500;i++)source.append(i==350?"int timeout = 30;\n":"context line "+i+"\n");
    var paths=new HashSet<String>();for(int i=0;i<3;i++){var path=root.resolve("edit"+i+".txt");Files.writeString(path,source);paths.add(path.toString());}
    var service=new TextChangeSetService(new TextChangeSetRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("benchmark.db"))));
    var tools=new Tools();tools.setTextChangeSets(service);var times=new ArrayList<Long>();long input=0,output=0,reads=0,readBytes=0,writes=0,writeBytes=0;
    try(var owner=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("benchmark","session",root,UUID.randomUUID().toString()));var recording=new Recording()){
      recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO);recording.enable("jdk.FileWrite").withThreshold(java.time.Duration.ZERO);recording.start();
      for(int i=0;i<3;i++){
        String name="edit"+i+".txt";long start=System.nanoTime();Map<String,Object> request;
        if(partial){
          var read=tools.readMultiFile(List.of(new Tools.ReadFileRequest(name,351,351)));assertNull(read.getFirst().error());
          input+=json.writeValueAsBytes(Map.of("files",List.of(Map.of("path",name,"startLine",351,"endLine",351)))).length;output+=json.writeValueAsBytes(read).length;
          request=Map.of("path",name,"baseVersion",json.valueToTree(read.getFirst()).get("version").asText(),"edits",List.of(Map.of("oldText","int timeout = 30;","newText","int timeout = 60;")));
        } else {
          var read=tools.readTextChangeSetBase(name);input+=json.writeValueAsBytes(Map.of("path",name)).length;output+=json.writeValueAsBytes(read).length;
          request=Map.of("path",name,"expectedText",read.text(),"replacement",read.text().replace("int timeout = 30;","int timeout = 60;"));
        }
        input+=json.writeValueAsBytes(Map.of("request",request)).length;
        var proposal=tools.proposeTextChangeSet(json.readValue(json.writeValueAsBytes(request),TextChangeSetService.Request.class));output+=json.writeValueAsBytes(proposal).length;
        input+=json.writeValueAsBytes(Map.of("id",proposal.id(),"proposalSha256",proposal.proposalSha256())).length;
        var applied=tools.applyTextChangeSet(proposal.id(),proposal.proposalSha256());output+=json.writeValueAsBytes(applied).length;
        assertEquals("APPLIED",applied.status());times.add(System.nanoTime()-start);
      }
      recording.stop();var trace=root.resolve("edit-reads.jfr");recording.dump(trace);
      for(var event:RecordingFile.readAllEvents(trace)){
        String type=event.getEventType().getName();if(!Set.of("jdk.FileRead","jdk.FileWrite").contains(type))continue;
        String path=event.getString("path");if(!paths.contains(path) && !path.contains(".rei-document-"))continue;
        if(type.equals("jdk.FileRead")){reads++;readBytes+=event.getLong("bytesRead");}else{writes++;writeBytes+=event.getLong("bytesWritten");}
      }
    }
    for(String path:paths)assertEquals(source.toString().replace("int timeout = 30;","int timeout = 60;"),Files.readString(Path.of(path)));
    Collections.sort(times);String report="{\"case\":\"small-edit-tools\",\"mode\":\""+(partial?"hunks":"full")+"\",\"repetitions\":3,\"toolCalls\":9,\"inputJsonBytes\":"+input+",\"outputJsonBytes\":"+output+",\"fileReads\":"+reads+",\"bytesRead\":"+readBytes+",\"fileWrites\":"+writes+",\"bytesWritten\":"+writeBytes+",\"medianNanos\":"+times.get(1)+"}";
    var target=Path.of(System.getProperty("fileOperations.editBenchmarkOutput","target/file-operations-edit-benchmark.json"));Files.createDirectories(target.toAbsolutePath().getParent());Files.writeString(target,report);System.out.println(report);
  }
}
