package dev.mikoto2000.rei.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.artifact.*;

/** One bounded PlantUML PNG rendering, separate from text Apply and delivered through ArtifactStore. */
@Service
public final class DocumentRendererService {
  public record Request(String path,String sourceSha256) {}
  public record Rendered(int exitCode,boolean parseError,byte[] png,String rendererIdentity) {}
  @FunctionalInterface public interface Renderer{Rendered render(String source)throws Exception;}
  public record Receipt(String id,String status,String sourceSha256,String rendererIdentity,Integer exitCode,
      boolean parseError,long size,String outputSha256,Artifact artifact,Instant createdAt,List<String> warnings) {}
  private record Row(String id,String status,String sourceSha,String payload,long pid,String processStart) {}
  private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
  private final JdbcClient db;private final Clock clock;private final boolean enabled;private final Renderer renderer;private final ArtifactStore artifacts;
  private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
  @org.springframework.beans.factory.annotation.Autowired
  public DocumentRendererService(@org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock,
      @org.springframework.beans.factory.annotation.Value("${rei.document-renderer.enabled:false}") boolean enabled,
      @org.springframework.beans.factory.annotation.Value("${rei.document-renderer.java:}") String java,
      @org.springframework.beans.factory.annotation.Value("${rei.document-renderer.plantuml-jar:}") String jar,
      org.springframework.beans.factory.ObjectProvider<ArtifactStore> artifacts){this(source,clock,enabled,new PlantUmlRenderer(java,jar,Duration.ofSeconds(20)),artifacts.getIfAvailable());}
  public DocumentRendererService(DataSource source,Clock clock,boolean enabled,Renderer renderer,ArtifactStore artifacts){
    db=JdbcClient.create(source);this.clock=clock;this.enabled=enabled;this.renderer=renderer;this.artifacts=artifacts;
    db.sql("CREATE TABLE IF NOT EXISTS document_renderer_receipts(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,request_key TEXT NOT NULL,source_sha TEXT NOT NULL,status TEXT NOT NULL,payload TEXT,pid INTEGER NOT NULL,process_start TEXT NOT NULL,created TEXT NOT NULL,UNIQUE(project,root,session,run,request_key))").update();
  }
  public Receipt validate(AgentRunContext owner,Request request)throws IOException{
    owner(owner,true);if(!enabled)throw new IllegalStateException("Document renderer disabled");
    if(request==null||request.path()==null||!request.path().matches("(?i).+\\.(puml|plantuml)")||request.sourceSha256()==null||!request.sourceSha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Selected PlantUML path and exact source SHA required");
    String text=TextDocumentTransaction.read(owner.projectRoot(),request.path());if(text==null||!request.sourceSha256().equals(TextDocumentTransaction.hash(text)))throw new IllegalArgumentException("Selected source is stale");safeInput(text);
    String key=TextDocumentTransaction.hash(request.path()+"\n"+request.sourceSha256());var previous=find(owner,key);if(previous.isPresent())return inspect(owner,previous.get().id());
    String id=UUID.randomUUID().toString();ACTIVE.add(id);
    try{
      int inserted=db.sql("INSERT OR IGNORE INTO document_renderer_receipts SELECT ?,?,?,?,?,?,?,'STARTED',NULL,?,?,? WHERE (SELECT count(*) FROM document_renderer_receipts WHERE project=?)<128 AND (SELECT count(*) FROM document_renderer_receipts)<1024")
          .params(id,owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),owner.runId(),key,request.sourceSha256(),ProcessHandle.current().pid(),start(),clock.instant().toString(),owner.projectId()).update();
      if(inserted!=1){var repeated=find(owner,key);if(repeated.isPresent())return inspect(owner,repeated.get().id());throw new IllegalStateException("Renderer history capacity reached");}
      try{
        RunCancellation.propagate(null);Rendered output=renderer.render(text);RunCancellation.propagate(null);
        var current=TextDocumentTransaction.fingerprint(owner.projectRoot(),request.path());if(!current.available()||!current.exists()||!request.sourceSha256().equals(current.sha256()))return save(new Receipt(id,"STALE",request.sourceSha256(),null,null,false,0,null,null,clock.instant(),List.of("SOURCE_CHANGED_DURING_RENDER")));
        if(output==null||output.rendererIdentity()==null||output.rendererIdentity().length()>256)throw new IOException("Invalid renderer identity");
        boolean valid=output.exitCode()==0&&!output.parseError()&&validPng(output.png());Artifact artifact=null;var warnings=new ArrayList<String>();String sha=valid?hash(output.png()):null;
        if(valid){if(artifacts==null)warnings.add("ARTIFACT_DELIVERY_DISABLED");else artifact=artifacts.publish(owner,"renderer:"+id,"image/png","diagram.png",output.png());}
        return save(new Receipt(id,valid?"RENDERED":"INVALID",request.sourceSha256(),output.rendererIdentity(),output.exitCode(),output.parseError(),valid?output.png().length:0,sha,artifact,clock.instant(),List.copyOf(warnings)));
      }catch(TimeoutException timeout){return save(new Receipt(id,"TIMEOUT",request.sourceSha256(),null,null,false,0,null,null,clock.instant(),List.of("RENDERER_DEADLINE_EXCEEDED")));}
      catch(Exception failure){save(new Receipt(id,RunCancellation.isCancellation(failure)||Thread.currentThread().isInterrupted()?"UNKNOWN":"UNAVAILABLE",request.sourceSha256(),null,null,false,0,null,null,clock.instant(),List.of("RENDERER_OR_ARTIFACT_UNAVAILABLE")));RunCancellation.propagate(failure);return inspect(owner,id);}
    }finally{ACTIVE.remove(id);}
  }
  public Receipt inspect(AgentRunContext owner,String id)throws IOException{
    owner(owner,false);if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Renderer ID required");
    var row=db.sql("SELECT * FROM document_renderer_receipts WHERE id=? AND project=? AND root=? AND session=?").params(id,owner.projectId(),owner.projectRoot().toString(),owner.conversationId()).query(DocumentRendererService::row).optional().orElseThrow(()->new IllegalArgumentException("Renderer receipt not found in captured Project/root/session"));
    if(row.status().equals("STARTED")&&!(row.pid()==ProcessHandle.current().pid()&&row.processStart().equals(start())&&ACTIVE.contains(id))&&!alive(row)){
      return save(new Receipt(id,"UNKNOWN",row.sourceSha(),null,null,false,0,null,null,clock.instant(),List.of("LOST_RENDER_OPERATION_NO_REPLAY")));
    }
    if(row.payload()==null)return new Receipt(id,"STARTED",row.sourceSha(),null,null,false,0,null,null,clock.instant(),List.of("RENDER_IN_PROGRESS"));
    return json.readValue(row.payload(),Receipt.class);
  }
  private Optional<Row> find(AgentRunContext owner,String key){return db.sql("SELECT * FROM document_renderer_receipts WHERE project=? AND root=? AND session=? AND run=? AND request_key=?").params(owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),owner.runId(),key).query(DocumentRendererService::row).optional();}
  private static Row row(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new Row(rs.getString("id"),rs.getString("status"),rs.getString("source_sha"),rs.getString("payload"),rs.getLong("pid"),rs.getString("process_start"));}
  private Receipt save(Receipt receipt)throws IOException{String payload=json.writeValueAsString(receipt);if(payload.length()>16384||db.sql("UPDATE document_renderer_receipts SET status=?,payload=? WHERE id=? AND status='STARTED'").params(receipt.status(),payload,receipt.id()).update()!=1)throw new IOException("Renderer receipt claim changed");return receipt;}
  private static String start(){return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");}
  private static boolean alive(Row row){if(row.pid()==ProcessHandle.current().pid())return ACTIVE.contains(row.id());return ProcessHandle.of(row.pid()).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(i->i.toString().equals(row.processStart())).orElse(false);}
  private static void owner(AgentRunContext owner,boolean write)throws IOException{RunCancellation.propagate(null);if(owner==null||owner.projectId()==null||owner.conversationId().startsWith("subagent:")||write&&owner.mode()!=AgentRunContext.Mode.EXCLUSIVE||!owner.projectRoot().toRealPath().equals(owner.projectRoot()))throw new IllegalArgumentException("Captured canonical human Project and exclusive rendering required");}
  static void safeInput(String text){String normalized=text.replace("\r\n","\n").strip();if(!normalized.startsWith("@startuml\n")||!normalized.endsWith("\n@enduml")||normalized.indexOf('@',1)!=normalized.lastIndexOf('@')||normalized.contains("!")||normalized.contains("%")||normalized.contains("[")||normalized.contains("]")||java.util.regex.Pattern.compile("<(?![-.=])").matcher(normalized).find())throw new IllegalArgumentException("One standalone PlantUML diagram without directives, functions or resource links required");TextDocumentTransaction.text(text);}
  static boolean validPng(byte[] bytes){if(bytes==null||bytes.length<24||bytes.length>4194304||!Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return false;try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())return false;var reader=readers.next();try{reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>4096||height>4096||(long)width*height>16777216)return false;return reader.read(0)!=null;}finally{reader.dispose();}}catch(IOException|RuntimeException invalid){return false;}}
  private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}

  public static final class PlantUmlRenderer implements Renderer {
    private final String java,jar;private final Duration timeout;
    public PlantUmlRenderer(String java,String jar,Duration timeout){this.java=java;this.jar=jar;this.timeout=timeout;if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Renderer timeout 1..30000ms");}
    public Rendered render(String source)throws Exception{
      safeInput(source);RunCancellation.propagate(null);Path executable=java==null||java.isBlank()?Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java"):Path.of(java);Path library=jar==null||jar.isBlank()?null:Path.of(jar);
      if(library==null||!library.isAbsolute()||!Files.isRegularFile(library,LinkOption.NOFOLLOW_LINKS)||Files.size(library)>134217728||!library.toRealPath().equals(library)||!executable.isAbsolute()||!Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS)||!executable.toRealPath().equals(executable))throw new IOException("Administrator renderer paths unavailable");String identity="plantuml-jar-sha256:"+hash(Files.readAllBytes(library));
      Path temporary=Files.createTempDirectory("rei-renderer-").toRealPath();try{var builder=new ProcessBuilder(executable.toString(),"-Djava.io.tmpdir="+temporary,"-Djava.awt.headless=true","-DPLANTUML_SECURITY_PROFILE=SANDBOX","-DPLANTUML_LIMIT_SIZE=4096","-jar",library.toString(),"-pipe","-tpng","-failfast2","-stdrpt:2","-nometadata");String systemRoot=builder.environment().get("SystemRoot");builder.environment().clear();if(systemRoot!=null)builder.environment().put("SystemRoot",systemRoot);builder.environment().put("TEMP",temporary.toString());builder.environment().put("TMP",temporary.toString());builder.directory(temporary.toFile());
      Process process=builder.start();
      try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
        var png=workers.submit(()->read(process.getInputStream(),4194304,process));var errors=workers.submit(()->read(process.getErrorStream(),8192,process));var writing=workers.submit(()->{try(var out=process.getOutputStream()){out.write(source.getBytes(StandardCharsets.UTF_8));}return true;});
        try{if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))throw new TimeoutException();writing.get(1,TimeUnit.SECONDS);byte[] bytes=png.get(1,TimeUnit.SECONDS);String stderr=new String(errors.get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);return new Rendered(process.exitValue(),stderr.contains("error")||stderr.contains("syntax"),bytes,identity);}
        finally{stop(process);writing.cancel(true);png.cancel(true);errors.cancel(true);}
      }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
      finally{stop(process);}}finally{cleanupTemporary(temporary);}
    }
    private static void cleanupTemporary(Path directory)throws IOException{if(!directory.isAbsolute()||!directory.getFileName().toString().startsWith("rei-renderer-")||!directory.toRealPath().equals(directory))throw new IOException("Renderer temporary boundary changed");try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(!path.toAbsolutePath().normalize().startsWith(directory))throw new IOException("Renderer cleanup boundary changed");Files.deleteIfExists(path);}}}
    private static byte[] read(InputStream input,int limit,Process process)throws IOException{try(input){byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit){stop(process);throw new IOException("Renderer output capacity exceeded");}return bytes;}}
    private static void stop(Process process){process.descendants().forEach(child->{try{child.destroyForcibly();}catch(RuntimeException ignored){}});if(process.isAlive())process.destroyForcibly();try{process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}catch(IOException ignored){}}
  }
}
