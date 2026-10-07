package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.CredentialRedactor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Multi-file extension of the same text proposal repository and exclusive Project editing boundary. */
@Service
public final class TextDocumentChangeSetService {
  public record Operation(String kind,String path,String target,String expectedText,String replacement) {}
  public record Request(List<Operation> operations){public Request{operations=operations==null?List.of():List.copyOf(operations);}}
  public record FileView(String path,String baselineSha256,String proposedSha256,String currentSha256,boolean baselineExists,boolean proposedExists,boolean currentAvailable,boolean currentExists) {}
  public record StagingView(String target,String temporary,String expectedSha256,String currentSha256,boolean available,boolean exists) {}
  public record View(String id,String status,String proposalSha256,List<FileView> files,String diff,Instant createdAt,List<String> warnings,String phase,int stagingFiles,List<StagingView> staging,boolean currentMatches) {}
  private record Saved(String id,String project,String root,String session,Instant createdAt,List<Operation> operations,List<TextDocumentTransaction.Change> changes) {}
  private record Row(Saved saved,String sha,String status,String phase,List<TextDocumentTransaction.Stage> stages,List<String> warnings,long pid,String processStart) {}
  private static final Set<String> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final JdbcClient db;private final Clock clock;private final TextDocumentTransaction.Move move;
  private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper(
      com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(1048576).maxNumberLength(64).build()).build())
      .findAndRegisterModules().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  @org.springframework.beans.factory.annotation.Autowired
  public TextDocumentChangeSetService(TextChangeSetRepository repository,Clock clock){this(repository,clock,TextDocumentTransaction::replace);}
  public TextDocumentChangeSetService(TextChangeSetRepository repository,Clock clock,TextDocumentTransaction.Move move){db=repository.documentsDb();this.clock=clock;this.move=move;}
  public View propose(AgentRunContext owner,Request request)throws IOException{
    Path root=owner(owner,true);if(request==null||request.operations().isEmpty()||request.operations().size()>32)throw new IllegalArgumentException("1..32 text operations required");
    var changes=new ArrayList<TextDocumentTransaction.Change>();var paths=new HashSet<String>();int bytes=0;
    for(var op:request.operations()){
      if(op==null||op.kind()==null||!Set.of("UPDATE","CREATE","DELETE","RENAME").contains(op.kind()))throw new IllegalArgumentException("UPDATE/CREATE/DELETE/RENAME required");
      selected(root,op.path(),paths);String current=TextDocumentTransaction.read(root,op.path());
      if(op.kind().equals("CREATE")){
        if(current!=null||op.expectedText()!=null||op.target()!=null)throw new IllegalArgumentException("Create needs an absent target and no baseline");TextDocumentTransaction.text(op.replacement());changes.add(new TextDocumentTransaction.Change(op.path(),null,op.replacement()));
      }else{
        if(current==null||op.expectedText()==null||!current.equals(op.expectedText()))throw new IllegalArgumentException("Exact existing baseline required");TextDocumentTransaction.text(op.expectedText());
        switch(op.kind()){
          case "UPDATE"->{if(op.target()!=null||Objects.equals(current,op.replacement()))throw new IllegalArgumentException("Actual update required");TextDocumentTransaction.text(op.replacement());changes.add(new TextDocumentTransaction.Change(op.path(),current,op.replacement()));}
          case "DELETE"->{if(op.target()!=null||op.replacement()!=null)throw new IllegalArgumentException("Delete has no target or replacement");changes.add(new TextDocumentTransaction.Change(op.path(),current,null));}
          case "RENAME"->{selected(root,op.target(),paths);if(TextDocumentTransaction.read(root,op.target())!=null)throw new IllegalArgumentException("Rename destination must be absent");String replacement=op.replacement()==null?current:op.replacement();TextDocumentTransaction.text(replacement);changes.add(new TextDocumentTransaction.Change(op.path(),current,null));changes.add(new TextDocumentTransaction.Change(op.target(),null,replacement));}
          default->throw new IllegalArgumentException("Invalid operation");
        }
      }
    }
    for(var change:changes)for(String value:new String[]{change.before(),change.after()})if(value!=null)bytes+=value.getBytes(StandardCharsets.UTF_8).length;if(bytes>262144)throw new IllegalArgumentException("Combined baseline/proposal text exceeds 256KiB");
    String id=UUID.randomUUID().toString();TextDocumentTransaction.preflight(root,id,changes);var saved=new Saved(id,owner.projectId(),root.toString(),owner.conversationId(),clock.instant(),request.operations(),List.copyOf(changes));String payload=encode(saved),sha=TextDocumentTransaction.hash(payload);
    db.sql("DELETE FROM text_document_change_sets WHERE project=? AND status IN ('DISCARDED','APPLIED','ROLLED_BACK','STALE') AND stages='[]' AND id NOT IN (SELECT id FROM text_document_change_sets WHERE project=? AND status IN ('DISCARDED','APPLIED','ROLLED_BACK','STALE') AND stages='[]' ORDER BY rowid DESC LIMIT 100)").params(owner.projectId(),owner.projectId()).update();
    if(db.sql("INSERT INTO text_document_change_sets(id,project,root,session,payload,sha,status) SELECT ?,?,?,?,?,?,'PROPOSED' WHERE (SELECT count(*) FROM text_document_change_sets WHERE project=?)<128 AND (SELECT count(*) FROM text_document_change_sets)<1024")
        .params(id,owner.projectId(),root.toString(),owner.conversationId(),payload,sha,owner.projectId()).update()!=1)throw new IllegalStateException("Document proposal history capacity reached");return inspect(owner,id);
  }
  public View inspect(AgentRunContext owner,String id)throws IOException{var row=owned(owner,id);reconcile(row);return view(owned(owner,id));}
  public View discard(AgentRunContext owner,String id,String sha)throws IOException{
    owner(owner,true);var row=owned(owner,id);exact(row,sha);if(row.status().equals("DISCARDED"))return view(row);if(!row.status().equals("PROPOSED"))throw new IllegalStateException("Only unclaimed proposals can be discarded");finish(row,"PROPOSED","DISCARDED",List.of());return inspect(owner,id);
  }
  public View apply(AgentRunContext owner,String id,String sha)throws IOException{
    return apply(owner,id,sha,changes->{});
  }
  public View apply(AgentRunContext owner,String id,String sha,java.util.function.Consumer<List<TextDocumentTransaction.Change>> committed)throws IOException{
    owner(owner,true);var row=owned(owner,id);exact(row,sha);if(row.status().equals("APPLIED"))return view(row);if(!row.status().equals("PROPOSED"))throw new IllegalStateException("Consumed or uncertain document set; inspect without replay");
    Path root=Path.of(row.saved().root());if(!TextDocumentTransaction.matches(root,row.saved().changes(),false)){finish(row,"PROPOSED","STALE",List.of("Baseline changed; no writes"));return inspect(owner,id);}
    if(!ACTIVE.add(id))throw new IllegalStateException("Document apply already active");
    try{
      int claim=db.sql("UPDATE text_document_change_sets SET status='APPLYING',phase='CLAIMED',pid=?,process_start=?,heartbeat=? WHERE id=? AND status='PROPOSED' AND NOT EXISTS (SELECT 1 FROM text_change_sets s WHERE s.root=text_document_change_sets.root AND s.status IN ('APPLYING','FAILED_UNCERTAIN'))")
          .params(ProcessHandle.current().pid(),processStart(),clock.instant().toString(),id).update();if(claim!=1)throw new IllegalStateException("Document or single-file apply requires inspection");
      var outcome=TextDocumentTransaction.apply(root,id,row.saved().changes(),(phase,stages)->journal(id,phase,stages),move);
      finish(row,"APPLYING",outcome.status(),outcome.warnings());if(outcome.status().equals("APPLIED")){cleanupTerminal(row);committed.accept(row.saved().changes());}return inspect(owner,id);
    }catch(IOException|RuntimeException failure){
      try{var current=owned(owner,id);if(current.status().equals("APPLYING")){boolean restored=current.phase().equals("ROLLED_BACK")&&TextDocumentTransaction.matches(root,row.saved().changes(),false);finish(current,"APPLYING",restored?"ROLLED_BACK":"UNKNOWN",List.of("Apply interrupted; inspect saved journal and current hashes"));}}
      catch(IOException|RuntimeException persistence){failure.addSuppressed(persistence);}RunCancellation.propagate(failure);throw failure;
    }finally{ACTIVE.remove(id);}
  }
  public View rollback(AgentRunContext owner,String id,String sha,String actualUserRequest)throws IOException{
    owner(owner,true);var row=owned(owner,id);exact(row,sha);if(!("/document rollback "+id+" "+sha).equals(actualUserRequest==null?null:actualUserRequest.strip()))throw new IllegalArgumentException("Exact human /document rollback ID SHA required");reconcile(row);row=owned(owner,id);
    if(row.status().equals("ROLLED_BACK"))return view(row);if(!row.status().equals("UNKNOWN"))throw new IllegalStateException("Only inspected UNKNOWN operations can be recovered");if(!ACTIVE.add(id))throw new IllegalStateException("Recovery already active");
    try{
      if(db.sql("UPDATE text_document_change_sets SET status='ROLLING_BACK',pid=?,process_start=?,heartbeat=?,recoveries=recoveries+1 WHERE id=? AND status='UNKNOWN' AND recoveries<3")
          .params(ProcessHandle.current().pid(),processStart(),clock.instant().toString(),id).update()!=1)throw new IllegalStateException("Recovery changed or limit reached");
      var outcome=TextDocumentTransaction.rollback(Path.of(row.saved().root()),id,row.saved().changes(),row.stages(),(phase,stages)->journal(id,phase,stages));finish(row,"ROLLING_BACK",outcome.status(),outcome.warnings());return inspect(owner,id);
    }catch(IOException|RuntimeException failure){db.sql("UPDATE text_document_change_sets SET status='UNKNOWN' WHERE id=? AND status='ROLLING_BACK'").param(id).update();RunCancellation.propagate(failure);throw failure;}finally{ACTIVE.remove(id);}
  }
  public View cleanup(AgentRunContext owner,String id,String sha,String actualUserRequest)throws IOException{
    owner(owner,true);var row=owned(owner,id);exact(row,sha);if(!("/document clean "+id+" "+sha).equals(actualUserRequest==null?null:actualUserRequest.strip())||!Set.of("APPLIED","ROLLED_BACK","STALE").contains(row.status()))throw new IllegalArgumentException("Exact human terminal staging cleanup required");cleanupTerminal(row);return inspect(owner,id);
  }
  private void cleanupTerminal(Row row)throws IOException{
    var stages=ownedRow(row.saved().project(),row.saved().root(),row.saved().session(),row.saved().id()).stages();
    try{TextDocumentTransaction.cleanupOwned(Path.of(row.saved().root()),row.saved().id(),row.saved().changes(),stages);journal(row.saved().id(),"CLEANED",List.of());}
    catch(IOException|RuntimeException unavailable){db.sql("UPDATE text_document_change_sets SET warnings=? WHERE id=?").params(encode(List.of("Target outcome is saved; owned staging files require explicit cleanup")),row.saved().id()).update();}
  }
  private void reconcile(Row row){if(row.pid()==ProcessHandle.current().pid()&&Objects.equals(row.processStart(),processStart())&&ACTIVE.contains(row.saved().id()))return;if(Set.of("APPLYING","ROLLING_BACK").contains(row.status())&&(!alive(row.pid(),row.processStart())||row.pid()==ProcessHandle.current().pid()&&!ACTIVE.contains(row.saved().id())))db.sql("UPDATE text_document_change_sets SET status='UNKNOWN',warnings=? WHERE id=? AND status=? AND pid=? AND process_start=?").params(encode(List.of("Process/operation no longer active; no automatic replay or rollback")),row.saved().id(),row.status(),row.pid(),row.processStart()).update();}
  private static boolean alive(long pid,String start){try{return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(handle->handle.info().startInstant()).map(instant->instant.toString().equals(start)).orElse(false);}catch(RuntimeException unavailable){return false;}}
  private static String processStart(){return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");}
  private void journal(String id,String phase,List<TextDocumentTransaction.Stage> stages)throws IOException{if(db.sql("UPDATE text_document_change_sets SET phase=?,stages=?,heartbeat=? WHERE id=? AND status IN ('APPLYING','ROLLING_BACK','APPLIED','ROLLED_BACK','STALE')").params(phase,encode(stages),clock.instant().toString(),id).update()!=1)throw new IOException("Document journal unavailable");}
  private void finish(Row row,String expected,String status,List<String> warnings)throws IOException{if(db.sql("UPDATE text_document_change_sets SET status=?,phase=?,warnings=?,heartbeat=? WHERE id=? AND status=?").params(status,status,encode(warnings),clock.instant().toString(),row.saved().id(),expected).update()!=1)throw new IOException("Document terminal receipt unavailable");}
  private Row owned(AgentRunContext owner,String id)throws IOException{Path root=owner(owner,false);if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Exact document ID required");return ownedRow(owner.projectId(),root.toString(),owner.conversationId(),id);}
  private Row ownedRow(String project,String root,String session,String id){return db.sql("SELECT * FROM text_document_change_sets WHERE id=? AND project=? AND root=? AND session=?").params(id,project,root,session).query((rs,n)->{
      String payload=rs.getString("payload"),sha=rs.getString("sha");if(!Objects.equals(TextDocumentTransaction.hash(payload),sha))throw new IllegalStateException("Document proposal payload changed");var saved=decode(payload,Saved.class);
      if(!saved.id().equals(id)||!saved.project().equals(project)||!saved.root().equals(root)||!saved.session().equals(session))throw new IllegalStateException("Document payload ownership changed");
      return new Row(saved,sha,rs.getString("status"),rs.getString("phase"),List.of(decode(rs.getString("stages"),TextDocumentTransaction.Stage[].class)),List.of(decode(rs.getString("warnings"),String[].class)),rs.getLong("pid"),rs.getString("process_start"));
    }).optional().orElseThrow(()->new IllegalArgumentException("Document set not found in this Project/root/session"));}
  private View view(Row row)throws IOException{
    var files=new ArrayList<FileView>();var diff=new StringBuilder();Path root=Path.of(row.saved().root());
    for(var change:row.saved().changes()){var current=TextDocumentTransaction.fingerprint(root,change.path());
      files.add(new FileView(change.path(),TextDocumentTransaction.hash(change.before()),TextDocumentTransaction.hash(change.after()),current.sha256(),change.before()!=null,change.after()!=null,current.available(),current.exists()));diff.append("--- ").append(change.path()).append(" (baseline)\n+++ ").append(change.path()).append(" (proposal)\n");append(diff,change.before(),'-');append(diff,change.after(),'+');}
    var staging=new ArrayList<StagingView>();var seen=new HashSet<String>();for(var stage:row.stages())if(seen.add(stage.temporary())){var current=TextDocumentTransaction.fingerprint(root,stage.temporary());staging.add(new StagingView(stage.path(),stage.temporary(),stage.sha256(),current.sha256(),current.available(),current.exists()));}
    boolean matching=files.stream().allMatch(file->file.currentAvailable()&&file.proposedExists()==file.currentExists()&&Objects.equals(file.proposedSha256(),file.currentSha256()));
    return new View(row.saved().id(),row.status(),row.sha(),List.copyOf(files),CredentialRedactor.redact(diff.toString()),row.saved().createdAt(),row.warnings(),row.phase(),(int)staging.stream().filter(StagingView::exists).count(),List.copyOf(staging),matching);
  }
  private static void append(StringBuilder out,String text,char prefix){if(text==null)return;for(String line:text.split("\n",-1))out.append(prefix).append(line).append('\n');if(!text.endsWith("\n"))out.append("\\ No newline at end of file\n");}
  private static void exact(Row row,String sha){if(sha==null||!sha.equals(row.sha()))throw new IllegalArgumentException("Exact reviewed whole Change Set SHA required");}
  private static void selected(Path root,String name,Set<String> paths)throws IOException{TextDocumentTransaction.path(root,name);if(name.substring(name.lastIndexOf('/')+1).startsWith(".rei-document-")||!paths.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Unique nonreserved target paths required");}
  private static Path owner(AgentRunContext owner,boolean write)throws IOException{RunCancellation.propagate(null);if(owner==null||owner.projectId()==null||owner.conversationId().startsWith("subagent:")||write&&owner.mode()!=AgentRunContext.Mode.EXCLUSIVE)throw new IllegalArgumentException("Captured human Project/session and exclusive writes required");Path root=owner.projectRoot().toRealPath();if(!root.equals(owner.projectRoot()))throw new IllegalArgumentException("Canonical captured Project root required");return root;}
  private String encode(Object value){try{String payload=json.writeValueAsString(value);if(payload.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("Document payload exceeds 1MiB");return payload;}catch(IOException invalid){throw new IllegalStateException("Document serialization unavailable",invalid);}}
  private <T>T decode(String value,Class<T> type){try{if(value==null||value.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalStateException("Document payload exceeds bounds");return json.readValue(value,type);}catch(IOException invalid){throw new IllegalStateException("Document payload unavailable",invalid);}}
}
