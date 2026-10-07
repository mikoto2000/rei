package dev.mikoto2000.rei.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.externalagent.*;
import dev.mikoto2000.rei.core.SelfPatchReviewService.*;

/** Git worktree facts only. Does not alter the index, invoke diff helpers, or retain source bodies. */
public final class GitPatchInspector {
  public record Material(String diff,Map<String,String> sources){public Material{sources=Map.copyOf(sources);}}
  public Material material(Path root,Snapshot snapshot,long deadline)throws IOException {
    if(!snapshot.complete()||snapshot.changedFiles().size()>32)throw new IOException("Complete patch of at most 32 files required");
    var sources=new TreeMap<String,String>();int total=0;
    for(String name:snapshot.changedFiles()){
      SelfPatchReviewService.remaining(deadline,1);if(!Files.exists(root.resolve(name),LinkOption.NOFOLLOW_LINKS))continue;
      byte[] bytes=read(root,name);if(bytes.length>65536||(total+=bytes.length)>65536||!text(bytes))throw new IOException("Review sources exceed 64KiB or are nontext");sources.put(name,new String(bytes,StandardCharsets.UTF_8));
    }
    String staged=git(root,deadline,32768,"diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","--cached","HEAD","--").stdout();
    String working=git(root,deadline,32768,"diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","HEAD","--").stdout();
    if(staged.getBytes(StandardCharsets.UTF_8).length+working.getBytes(StandardCharsets.UTF_8).length>32768)throw new IOException("Review diff exceeds 32KiB");
    var diff=new StringBuilder(staged).append('\n').append(working);
    for(String name:snapshot.untracked())if(sources.containsKey(name)){diff.append("\ndiff --git a/").append(name).append(" b/").append(name).append("\n+++ b/").append(name).append("\n@@ -0,0 +1 @@\n");for(String line:sources.get(name).split("\n",-1))diff.append('+').append(line).append('\n');}
    if(diff.toString().getBytes(StandardCharsets.UTF_8).length>32768)throw new IOException("Review diff exceeds 32KiB");return new Material(diff.toString(),sources);
  }
  private final ExternalAgentProcessRunner processes;
  private final java.util.function.BooleanSupplier cancelled;
  private static final Pattern CHECK=Pattern.compile("^(.*):(\\d+): (.*)$");
  public GitPatchInspector(ExternalAgentProcessRunner processes){this(processes,()->false);}
  public GitPatchInspector(ExternalAgentProcessRunner processes,java.util.function.BooleanSupplier cancelled){this.processes=processes;this.cancelled=cancelled;}
  public Snapshot capture(Path root,long deadline)throws IOException {
    var identity=git(root,deadline,4096,"rev-parse","--show-toplevel","HEAD").stdout().lines().toList();
    if(identity.size()!=2 || !Path.of(identity.getFirst()).toRealPath().equals(root) || !identity.getLast().matches("[0-9a-f]{40,64}"))
      throw new IOException("A Git repository root with a HEAD commit is required");
    var tracked=names(git(root,deadline,1048576,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-only","-z","HEAD","--").stdout());
    var untracked=names(git(root,deadline,1048576,"ls-files","--others","--exclude-standard","-z").stdout());
    var changes=new TreeSet<String>(tracked);changes.addAll(untracked);
    if(changes.size()>128)return new Snapshot("",changes.stream().limit(128).toList(),List.of(),false,List.of("Patch file count exceeds 128"));
    for(String name:changes)if(!safe(root,name))return new Snapshot("",List.copyOf(changes),untracked,false,List.of("Some patch paths require manual review: excluded, symlink, nonregular or outside root"));
    var digest=digest();update(digest,identity.getLast().getBytes(StandardCharsets.UTF_8));
    String staged=git(root,deadline,1048576,"diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","--full-index","--binary","--cached","HEAD","--").stdout();
    String working=git(root,deadline,1048576,"diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","--full-index","--binary","--").stdout();
    if(staged.contains("\nGIT binary patch\n") || working.contains("\nGIT binary patch\n"))
      return new Snapshot("",List.copyOf(changes),untracked,false,List.of("Binary patch requires manual review"));
    update(digest,staged.getBytes(StandardCharsets.UTF_8));update(digest,working.getBytes(StandardCharsets.UTF_8));
    int bytes=0;
    for(String name:changes) {
      SelfPatchReviewService.remaining(deadline,1);update(digest,name.getBytes(StandardCharsets.UTF_8));Path path=root.resolve(name);
      if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){update(digest,"DELETED".getBytes(StandardCharsets.UTF_8));continue;}
      byte[] content=read(root,name);bytes+=content.length;
      if(bytes>2097152)return new Snapshot("",List.copyOf(changes),untracked,false,List.of("Patch source byte budget exceeds 2MiB"));
      if(!text(content))return new Snapshot("",List.copyOf(changes),untracked,false,List.of("Binary or non-UTF8 patch requires manual review"));
      update(digest,content);
    }
    return new Snapshot(HexFormat.of().formatHex(digest.digest()),List.copyOf(changes),untracked,true,List.of());
  }
  public Review review(Path root,Snapshot snapshot,long deadline)throws IOException {
    var output=runGit(root,deadline,65536,"diff","--no-ext-diff","--no-textconv","--no-renames","--no-color","--check","HEAD","--");
    var findings=new LinkedHashSet<Finding>();var warnings=new LinkedHashSet<String>();boolean complete=true;
    if(output.truncated() || output.status()!=ExternalAgentResult.Status.SUCCESS && output.status()!=ExternalAgentResult.Status.FAILED)
      return new Review(false,List.of(),List.of("Git diff check did not produce a complete result"));
    if(output.status()==ExternalAgentResult.Status.FAILED) {
      boolean recognized=false;
      for(String line:output.stdout().lines().toList()) {
        var match=CHECK.matcher(line);
        if(!match.matches())continue;
        if(!snapshot.changedFiles().contains(match.group(1))){complete=false;continue;}
        String kind=kind(match.group(3));if(kind==null){complete=false;continue;}
        recognized=true;findings.add(new Finding(match.group(1),Long.parseLong(match.group(2)),kind));
        if(findings.size()>24){complete=false;break;}
      }
      if(!recognized || !output.stderr().isBlank())complete=false;
    }
    int bytes=0;
    for(String name:snapshot.untracked()) {
      SelfPatchReviewService.remaining(deadline,1);byte[] content=read(root,name);bytes+=content.length;
      if(bytes>2097152 || !text(content)){complete=false;break;}
      long number=0;
      for(String line:new String(content,StandardCharsets.UTF_8).lines().toList()) {
        number++;String kind=null;
        if(line.matches("(?:<{7}(?: .*|)|={7}|>{7}(?: .*|))"))kind="CONFLICT_MARKER";
        else if(line.endsWith(" ") || line.endsWith("\t"))kind="WHITESPACE";
        if(kind!=null)findings.add(new Finding(name,number,kind));
        if(findings.size()>24){complete=false;break;}
      }
      if(findings.size()>24)break;
    }
    if(!complete)warnings.add("Patch checks were incomplete or exceeded the 24 finding limit");
    return new Review(complete,findings.stream().limit(24).toList(),List.copyOf(warnings));
  }
  private static String kind(String diagnostic) {
    if(diagnostic.contains("leftover conflict marker"))return "CONFLICT_MARKER";
    if(diagnostic.contains("whitespace") || diagnostic.contains("space before tab") || diagnostic.contains("new blank line at EOF"))return "WHITESPACE";
    return null;
  }
  private static boolean safe(Path root,String name)throws IOException {
    Path path=Path.of(name);if(name.length()>1024 || name.codePoints().anyMatch(Character::isISOControl) || path.isAbsolute()
        || path.normalize().startsWith("..") || RepositoryMapService.sensitive(path))return false;
    Path target=root.resolve(path).normalize();
    if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return true;
    return Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) && target.toRealPath().startsWith(root);
  }
  private static byte[] read(Path root,String name)throws IOException {
    if(!safe(root,name))throw new IOException("Patch path unavailable");
    try(var stream=Files.newInputStream(root.resolve(name),LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes(262145);if(bytes.length>262144)throw new IOException("Patch file exceeds 256KiB");return bytes;}
  }
  private static boolean text(byte[] bytes) {
    for(byte value:bytes)if(value==0)return false;
    try{StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));return true;}catch(java.nio.charset.CharacterCodingException error){return false;}
  }
  private static List<String> names(String output){return Arrays.stream(output.split(String.valueOf((char)0))).filter(s->!s.isEmpty()).distinct().sorted().toList();}
  private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
  private static void update(MessageDigest digest,byte[] bytes){digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
  private ExternalAgentProcessRunner.Output git(Path root,long deadline,int bytes,String... args)throws IOException {
    var output=runGit(root,deadline,bytes,args);if(output.status()!=ExternalAgentResult.Status.SUCCESS || output.truncated())throw new IOException("Git patch inventory unavailable or exceeds its byte limit");return output;
  }
  private ExternalAgentProcessRunner.Output runGit(Path root,long deadline,int bytes,String... args)throws IOException {
    var command=new ArrayList<String>(List.of("git","--no-pager","--no-optional-locks","-c","core.quotepath=false","-c","core.fsmonitor=false"));command.addAll(List.of(args));
    var timeout=SelfPatchReviewService.remaining(deadline,5);
    var output=processes.run(command,root,"",timeout,timeout,bytes,()->cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
    if(output.status()==ExternalAgentResult.Status.CANCELLED)throw new java.util.concurrent.CancellationException("self-review cancelled");
    RunCancellation.propagate(null);return output;
  }
}
