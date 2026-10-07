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
  public record Request(String path,String expectedText,String replacement) {}
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
    validateText(request.expectedText());validateText(request.replacement());
    if(request.expectedText().isEmpty() || request.expectedText().equals(request.replacement()))throw new IllegalArgumentException("Nonempty baseline and an actual change required");
    var root=project.root().toRealPath();var file=resolve(root,request.path());var current=read(file);
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
    try {
      RunCancellation.propagate(null);
      // Recheck after the durable claim and immediately before the existing editing boundary.
      file=resolve(root,saved.path());
      if(!saved.baselineHash().equals(hash(read(file)))) {
        repository.transition(project.id(),id,"APPLYING","STALE");return inspect(project,id);
      }
      writer.write(file,saved.baseline(),saved.proposed());RunCancellation.propagate(null);
      if(!saved.proposedHash().equals(hash(read(resolve(root,saved.path())))))throw new IOException("Applied content requires inspection");
      if(!repository.transition(project.id(),id,"APPLYING","APPLIED"))throw new IOException("Apply receipt could not be saved");
      return inspect(project,id);
    }catch(IOException | RuntimeException error) {
      try{repository.transition(project.id(),id,"APPLYING","FAILED_UNCERTAIN");}catch(RuntimeException persistence){error.addSuppressed(persistence);}
      RunCancellation.propagate(error);throw error;
    }
  }
  private TextChangeSetRepository.Saved owned(ProjectContext project,String id)throws IOException {
    RunCancellation.propagate(null);
    if(id==null || id.isBlank())throw new IllegalArgumentException("Change Set ID required");
    var saved=repository.get(project.id(),id);
    if(!project.root().toRealPath().toString().equals(saved.root()))throw new IllegalArgumentException("Change Set belongs to a different Project root");
    return saved;
  }
  private static Path resolve(Path root,String name)throws IOException {
    if(name==null || name.isBlank() || name.length()>1024 || name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Project-relative text path required");
    Path relative;
    try{relative=Path.of(name);}catch(InvalidPathException error){throw new IllegalArgumentException("Invalid text path");}
    if(relative.isAbsolute() || relative.getRoot()!=null || relative.normalize().startsWith("..") || RepositoryMapService.sensitive(relative))throw new IllegalArgumentException("Excluded or outside text path");
    var file=root.resolve(relative).normalize();var cursor=root;
    for(var part:root.relativize(file)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IllegalArgumentException("Linked text paths are unsupported");}
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root))throw new IllegalArgumentException("Existing regular Project text file required");
    return file;
  }
  private static String read(Path file)throws IOException {
    try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
      var bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Text exceeds 64KiB");
      for(byte value:bytes)if(value==0)throw new IllegalArgumentException("Binary text is unsupported");
      try{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}
      catch(java.nio.charset.CharacterCodingException error){throw new IllegalArgumentException("UTF-8 text required");}
    }
  }
  private static void validateText(String text) {
    if(text==null || text.indexOf(0)>=0 || text.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("UTF-8 replacement/baseline must be at most 64KiB");
    try{StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));}
    catch(java.nio.charset.CharacterCodingException error){throw new IllegalArgumentException("Valid Unicode text required");}
  }
  private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
  private static View view(TextChangeSetRepository.Saved saved,String current) {
    return new View(saved.id(),saved.projectId(),saved.path(),saved.status(),saved.baselineHash(),saved.proposedHash(),saved.proposalHash(),current,
        diff(saved.path(),saved.baseline(),saved.proposed()),saved.createdAt());
  }
  private static String diff(String path,String before,String after) {
    // Complete replacement diff, including newline changes. Not a guessed minimal edit or executable patch.
    return "--- "+path+" (baseline)\n+++ "+path+" (proposal)\n"+lines(before,"-")+lines(after,"+");
  }
  private static String lines(String text,String prefix) {
    if(text.isEmpty())return "";
    var out=new StringBuilder();var lines=text.split("\n",-1);int count=lines.length-(text.endsWith("\n")?1:0);
    for(int i=0;i<count;i++)out.append(prefix).append(lines[i]).append('\n');
    if(!text.endsWith("\n"))out.append("\\ No newline at end of file\n");return out.toString();
  }
}
