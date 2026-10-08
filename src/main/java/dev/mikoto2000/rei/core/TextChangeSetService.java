package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Single existing UTF-8 file, exact baseline, durable proposal identity and explicit one-shot Apply. */
@Service
public class TextChangeSetService {
  private TextDocumentChangeSetService documents;
  @Autowired(required=false) public void setDocuments(TextDocumentChangeSetService service){documents=service;}
  public TextDocumentChangeSetService.View proposeDocuments(dev.mikoto2000.rei.core.chat.AgentRunContext owner,TextDocumentChangeSetService.Request request)throws IOException{return documents().propose(owner,request);}
  public TextDocumentChangeSetService.View inspectDocuments(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id)throws IOException{return documents().inspect(owner,id);}
  public TextDocumentChangeSetService.View discardDocuments(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id,String sha)throws IOException{return documents().discard(owner,id,sha);}
  public TextDocumentChangeSetService.View applyDocuments(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id,String sha,java.util.function.Consumer<java.util.List<TextDocumentTransaction.Change>> committed)throws IOException{return documents().apply(owner,id,sha,committed);}
  public TextDocumentChangeSetService.View rollbackDocuments(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id,String sha,String actualRequest)throws IOException{return documents().rollback(owner,id,sha,actualRequest);}
  public TextDocumentChangeSetService.View cleanDocumentStaging(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id,String sha,String actualRequest)throws IOException{return documents().cleanup(owner,id,sha,actualRequest);}
  private TextDocumentChangeSetService documents(){if(documents==null)throw new IllegalStateException("Multi-file document service unavailable");return documents;}
  public record Edit(String oldText,String newText) {}
  public record Request(String path,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) String expectedText,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) String replacement,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) String baseVersion,
      @org.springframework.ai.tool.annotation.ToolParam(required=false) List<Edit> edits) {
    public Request(String path,String expectedText,String replacement){this(path,expectedText,replacement,null,null);}
  }
  public record Baseline(String path,String text,String sha256) {}
  public record View(String id,String projectId,String path,String status,String baselineSha256,String proposedSha256,
      String proposalSha256,String currentSha256,String diff,Instant createdAt) {}
  @FunctionalInterface public interface Writer {void write(Path path,String oldText,String newText)throws IOException;}
  private final TextChangeSetRepository repository;
  private final Clock clock;
  private final Supplier<String> ids;
  @Autowired public TextChangeSetService(TextChangeSetRepository repository){this(repository,Clock.systemUTC(),()->UUID.randomUUID().toString());}
  public TextChangeSetService(TextChangeSetRepository repository,Clock clock,Supplier<String> ids){this.repository=repository;this.clock=clock;this.ids=ids;}
  public Baseline readBase(ProjectContext project,String path)throws IOException {
    RunCancellation.propagate(null);var root=project.root().toRealPath();var file=resolve(root,path);var text=read(file);
    return new Baseline(root.relativize(file).toString().replace('\\','/'),text,hash(text));
  }
  public View propose(ProjectContext project,Request request)throws IOException {
    RunCancellation.propagate(null);
    if(request==null)throw new IllegalArgumentException("Change Set request required");
    String snapshotText=null;
    if(request.edits()!=null){
      if(request.expectedText()!=null || request.replacement()!=null)throw new IllegalArgumentException("Choose full replacement or partial edits, not both");
      if(request.baseVersion()==null)throw new IllegalArgumentException("Partial edits require baseVersion");
      var root=project.root().toRealPath();var file=resolve(root,request.path());String current=read(file);
      String version=request.baseVersion().startsWith("sha256:")?request.baseVersion().substring(7):request.baseVersion();
      if(!hash(current).equals(version))throw new IllegalArgumentException("File version mismatch; read and propose again");
      String replacement=edit(current,request.edits());
      // Reuse all existing validation, ownership, durable proposal identity, diff and explicit Apply.
      snapshotText=current;request=new Request(request.path(),current,replacement);
    }
    if(request.baseVersion()!=null)throw new IllegalArgumentException("baseVersion requires partial edits");
    validateText(request.expectedText());validateText(request.replacement());
    if(request.expectedText().isEmpty() || request.expectedText().equals(request.replacement()))throw new IllegalArgumentException("Nonempty baseline and an actual change required");
    var root=project.root().toRealPath();var file=resolve(root,request.path());var current=snapshotText==null?read(file):snapshotText;
    if(!current.equals(request.expectedText()))throw new IllegalArgumentException("Baseline differs; read the current file and propose again");
    var relative=root.relativize(file).toString().replace('\\','/');
    var base=hash(current);var proposed=hash(request.replacement());
    var proposal=hash(project.id()+"\u0000"+root+"\u0000"+relative+"\u0000"+base+"\u0000"+proposed);
    var saved=new TextChangeSetRepository.Saved(ids.get(),project.id(),root.toString(),relative,current,request.replacement(),base,proposed,proposal,"PROPOSED",clock.instant());
    RunCancellation.propagate(null);repository.save(saved);return view(saved,base);
  }
  public View inspect(ProjectContext project,String id)throws IOException {
    var saved=owned(project,id);String current=null;
    try{current=hash(read(resolve(Path.of(saved.root()),saved.path())));}catch(IOException | IllegalArgumentException unavailable){/* Missing/invalid current file is not an empty file or a successful receipt. */}
    return view(saved,current);
  }
  /** Export the persisted proposal, including stale proposals, without touching the current file. */
  public byte[] exportProposal(ProjectContext project,String id)throws IOException {
    var saved=owned(project,id);validateText(saved.proposed());
    return saved.proposed().getBytes(StandardCharsets.UTF_8);
  }
  public View discard(ProjectContext project,String id,String proposalHash)throws IOException {
    var saved=owned(project,id);
    if(proposalHash==null || !saved.proposalHash().equals(proposalHash))throw new IllegalArgumentException("Exact reviewed proposal hash required");
    if(saved.status().equals("DISCARDED"))return inspect(project,id);
    if(!saved.status().equals("PROPOSED") || !repository.transition(project.id(),id,"PROPOSED","DISCARDED"))throw new IllegalStateException("Only an unclaimed proposal may be discarded");
    return inspect(project,id);
  }
  public View apply(ProjectContext project,String id,String proposalHash,Writer writer)throws IOException {
    return apply(project,id,proposalHash,writer,null);
  }
  void protectDiagnosed(ProjectContext project,String id)throws IOException {
    var saved=owned(project,id);if(!saved.status().equals("PROPOSED"))throw new IllegalStateException("Only a pending proposal can receive a repair guard");repository.protectDiagnosed(project.id(),id);
  }
  View applyDiagnosed(ProjectContext project,String id,String proposalHash,Writer writer)throws IOException {return apply(project,id,proposalHash,writer,"DIAGNOSED_REPAIR");}
  private View apply(ProjectContext project,String id,String proposalHash,Writer writer,String trustedGuard)throws IOException {
    RunCancellation.propagate(null);var saved=owned(project,id);
    if(proposalHash==null || !saved.proposalHash().equals(proposalHash))throw new IllegalArgumentException("Exact reviewed proposal hash required");
    var root=Path.of(saved.root());var file=resolve(root,saved.path());var current=hash(read(file));
    if(saved.status().equals("APPLIED"))return view(saved,current); // Receipt read; never execute the writer twice.
    var guard=repository.applyGuard(project.id(),id);
    if(guard!=null&&!guard.equals(trustedGuard)||trustedGuard!=null&&!trustedGuard.equals(guard))throw new IllegalStateException("Diagnosed repair proposal requires its exact human approval flow");
    if(!saved.status().equals("PROPOSED"))throw new IllegalStateException("Change Set is no longer applicable; inspect current content");
    if(!saved.baselineHash().equals(current)) {
      repository.transition(project.id(),id,"PROPOSED","STALE");return inspect(project,id);
    }
    if(!repository.transition(project.id(),id,"PROPOSED","APPLYING"))throw new IllegalStateException("Change Set already claimed");
    try(var lease=repository.activate(id)) {
      RunCancellation.propagate(null);
      // Recheck after the durable claim and immediately before the existing editing boundary.
      file=resolve(root,saved.path());
      if(!saved.baselineHash().equals(hash(read(file)))) {
        repository.transition(project.id(),id,"APPLYING","STALE");return inspect(project,id);
      }
      writer.write(file,saved.baseline(),saved.proposed());RunCancellation.propagate(null);
      if(!saved.proposedHash().equals(hash(read(resolve(root,saved.path())))))throw new IOException("Applied content requires inspection");
      repository.heartbeat(id);
      if(!repository.transition(project.id(),id,"APPLYING","APPLIED"))throw new IOException("Apply receipt could not be saved");
      return inspect(project,id);
    }catch(IOException | RuntimeException error) {
      try{repository.transition(project.id(),id,"APPLYING","FAILED_UNCERTAIN");}catch(RuntimeException persistence){error.addSuppressed(persistence);}
      RunCancellation.propagate(error);throw error;
    }
  }
  public View reconcile(dev.mikoto2000.rei.core.chat.AgentRunContext owner,String id,String proposalHash,String actualRequest)throws IOException{
    RunCancellation.propagate(null);if(owner==null||owner.mode()!=dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE||owner.requestSource()!=dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL||owner.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Exclusive human Shell owner required");
    var project=new ProjectContext(owner.projectId(),"",owner.projectRoot());var saved=owned(project,id);if(!saved.proposalHash().equals(proposalHash)||!("/document reconcile-single "+id+" "+proposalHash).equals(actualRequest==null?null:actualRequest.strip()))throw new IllegalArgumentException("Exact human reconciliation ID/SHA request required");
    if(saved.status().equals("RECONCILED"))return inspect(project,id);if(!Set.of("UNKNOWN","FAILED_UNCERTAIN").contains(saved.status()))throw new IllegalStateException("Only an uncertain lost Apply can be reconciled");String current=hash(read(resolve(Path.of(saved.root()),saved.path())));if(!Set.of(saved.baselineHash(),saved.proposedHash()).contains(current))throw new IllegalStateException("Current content is neither saved version; inspect and manually repair first");
    if(!repository.transition(project.id(),id,saved.status(),"RECONCILED"))throw new IllegalStateException("Reconciliation state changed");return inspect(project,id);
  }
  private TextChangeSetRepository.Saved owned(ProjectContext project,String id)throws IOException {
    RunCancellation.propagate(null);
    if(id==null || id.isBlank())throw new IllegalArgumentException("Change Set ID required");
    var saved=repository.peek(project.id(),id);
    if(!project.root().toRealPath().toString().equals(saved.root()))throw new IllegalArgumentException("Change Set belongs to a different Project root");
    return repository.get(project.id(),id);
  }
  private static Path resolve(Path root,String name)throws IOException {
    if(name==null || name.isBlank() || name.length()>1024 || name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Project-relative text path required");
    Path relative;
    try{relative=Path.of(name);}catch(InvalidPathException error){throw new IllegalArgumentException("Invalid text path");}
    if(relative.isAbsolute() || relative.getRoot()!=null || relative.normalize().startsWith("..") || RepositoryMapService.sensitive(relative))throw new IllegalArgumentException("Excluded or outside text path");
    var file=root.resolve(relative).normalize();var cursor=root;
    for(var part:root.relativize(file)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor) || Files.exists(cursor,LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor))throw new IllegalArgumentException("Linked or aliased text paths are unsupported");}
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root))throw new IllegalArgumentException("Existing regular Project text file required");
    return file;
  }
  private static String read(Path file)throws IOException {
      if(Files.size(file)>65536)throw new IllegalArgumentException("Text exceeds 64KiB");
      var bytes=new FileSnapshots().get(file.getParent(),file).bytes();if(bytes.length>65536)throw new IllegalArgumentException("Text exceeds 64KiB");
      for(byte value:bytes)if(value==0)throw new IllegalArgumentException("Binary text is unsupported");
      try{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}
      catch(java.nio.charset.CharacterCodingException error){throw new IllegalArgumentException("UTF-8 text required");}
  }
  private static void validateText(String text) {
    if(text==null || text.indexOf(0)>=0 || text.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("UTF-8 replacement/baseline must be at most 64KiB");
    try{StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));}
    catch(java.nio.charset.CharacterCodingException error){throw new IllegalArgumentException("Valid Unicode text required");}
  }
  private static String edit(String baseline,List<Edit> edits) {
    if(edits.isEmpty() || edits.size()>64)throw new IllegalArgumentException("Partial edits require 1 to 64 hunks");
    record Hunk(int start,int end,String replacement){}
    var hunks=new ArrayList<Hunk>();int inputBytes=0;
    for(var edit:edits){
      if(edit==null)throw new IllegalArgumentException("Hunk required");validateText(edit.oldText());validateText(edit.newText());
      inputBytes+=edit.oldText().getBytes(StandardCharsets.UTF_8).length+edit.newText().getBytes(StandardCharsets.UTF_8).length;
      if(inputBytes>65536)throw new IllegalArgumentException("Hunk input exceeds 64KiB");
      if(edit.oldText().isEmpty())throw new IllegalArgumentException("Nonempty oldText required");
      int start=baseline.indexOf(edit.oldText());
      if(start<0)throw new IllegalArgumentException("Hunk oldText not found");
      if(baseline.indexOf(edit.oldText(),start+1)>=0)throw new IllegalArgumentException("Hunk oldText is ambiguous; include more context");
      hunks.add(new Hunk(start,start+edit.oldText().length(),edit.newText()));
    }
    hunks.sort(Comparator.comparingInt(Hunk::start));
    for(int i=1;i<hunks.size();i++)if(hunks.get(i).start()<hunks.get(i-1).end())throw new IllegalArgumentException("Overlapping hunks");
    var result=new StringBuilder();int cursor=0;
    for(var hunk:hunks){result.append(baseline,cursor,hunk.start()).append(hunk.replacement());cursor=hunk.end();}
    result.append(baseline,cursor,baseline.length());validateText(result.toString());return result.toString();
  }
  private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
  private static View view(TextChangeSetRepository.Saved saved,String current) {
    return new View(saved.id(),saved.projectId(),saved.path(),saved.status(),saved.baselineHash(),saved.proposedHash(),saved.proposalHash(),current,
        diff(saved.path(),saved.baseline(),saved.proposed()),saved.createdAt());
  }
  private static String diff(String path,String before,String after) {
    // Keep the existing exact replacement renderer, omitting only identical outer lines.
    // This is a review preview, not an executable patch. Export retains the complete proposal.
    var oldRows=before.split("\n",-1);var newRows=after.split("\n",-1);int prefix=0,suffix=0;
    while(prefix<oldRows.length && prefix<newRows.length && oldRows[prefix].equals(newRows[prefix]))prefix++;
    while(suffix<oldRows.length-prefix && suffix<newRows.length-prefix && oldRows[oldRows.length-suffix-1].equals(newRows[newRows.length-suffix-1]))suffix++;
    int from=Math.max(0,prefix-3);int oldTo=Math.min(oldRows.length,oldRows.length-suffix+3),newTo=Math.min(newRows.length,newRows.length-suffix+3);
    String oldPart=String.join("\n",Arrays.copyOfRange(oldRows,from,oldTo))+(oldTo<oldRows.length?"\n":"");
    String newPart=String.join("\n",Arrays.copyOfRange(newRows,from,newTo))+(newTo<newRows.length?"\n":"");
    return "--- "+path+" (baseline)\n+++ "+path+" (proposal)\n@@ -"+(from+1)+","+oldPart.lines().count()+" +"+(from+1)+","+newPart.lines().count()+" @@\n"+lines(oldPart,"-")+lines(newPart,"+");
  }
  private static String lines(String text,String prefix) {
    if(text.isEmpty())return "";
    var out=new StringBuilder();var lines=text.split("\n",-1);int count=lines.length-(text.endsWith("\n")?1:0);
    for(int i=0;i<count;i++)out.append(prefix).append(lines[i]).append('\n');
    if(!text.endsWith("\n"))out.append("\\ No newline at end of file\n");return out.toString();
  }
}
