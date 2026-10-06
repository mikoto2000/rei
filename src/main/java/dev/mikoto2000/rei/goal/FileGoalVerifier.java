package dev.mikoto2000.rei.goal;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Independent read-only predicate, never a model's completion claim. */
@Component
public class FileGoalVerifier {
  public record Verification(boolean satisfied,String reason) {}
  public Verification verify(GoalRepository.Goal goal) {
    if(goal.criteria().isEmpty()||goal.criteria().size()>16)return new Verification(false,"verification_unavailable");
    for(var criterion:goal.criteria()) {
      if(criterion.jsonCriterion()) {
        if(goal.projectRoot()==null)return new Verification(false,"verification_unavailable");
        try{var result=verifyJson(Path.of(goal.projectRoot()),criterion);if(!result.satisfied())return result;}
        catch(IllegalArgumentException invalid){return new Verification(false,"verification_unavailable");}
        continue;
      }
      if(criterion.sha256()==null||!criterion.sha256().matches("[a-f0-9]{64}")||goal.projectRoot()==null)
        return new Verification(false,"verification_unavailable");
      try {var result=verify(Path.of(goal.projectRoot()),criterion);if(!result.satisfied())return result;}
      catch(IllegalArgumentException error){return new Verification(false,"verification_unavailable");}
    }
    return new Verification(true,goal.criteria().stream().anyMatch(GoalRepository.FileCriterion::jsonCriterion)?"criteria_verified":"file_digest_verified");
  }
  private Verification verifyJson(Path root,GoalRepository.FileCriterion criterion) {
    if(Thread.currentThread().isInterrupted())return new Verification(false,"verification_cancelled");
    var condition=JsonFileGoalCondition.parse(criterion.jsonPointer(),criterion.expectedJson());
    if(criterion.sha256()!=null&&!criterion.sha256().isEmpty())return new Verification(false,"verification_unavailable");
    var safe=fingerprint(root,criterion.relativeFile(),false);
    if(!safe.available())return new Verification(false,safe.reason());
    Path file=root.resolve(criterion.relativeFile());
    try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
      var buffer=ByteBuffer.allocate(65537);
      while(channel.read(buffer)>=0) {
        if(Thread.currentThread().isInterrupted())return new Verification(false,"verification_cancelled");
        if(!buffer.hasRemaining())return new Verification(false,"json_file_too_large");
      }
      var bytes=java.util.Arrays.copyOf(buffer.array(),buffer.position());
      boolean matches=condition.matches(bytes);
      return new Verification(matches,matches?"json_value_verified":"json_value_mismatch");
    }catch(java.io.IOException invalid){return new Verification(false,Thread.currentThread().isInterrupted()?"verification_cancelled":"json_file_invalid");}
  }
  public record Fingerprint(boolean available,String sha256,String reason) {}
  public Fingerprint fingerprint(Path root,String relativeFile){return fingerprint(root,relativeFile,true);}
  public Verification verify(Path root,GoalRepository.FileCriterion criterion) {
    if(criterion.jsonCriterion())return verifyJson(root,criterion);
    var observed=fingerprint(root,criterion.relativeFile(),criterion.sha256()!=null);
    if(!observed.available())return new Verification(false,observed.reason());
    if(criterion.sha256()==null)return new Verification(true,"file_exists");
    boolean matches=criterion.sha256().equals(observed.sha256());
    return new Verification(matches,matches?"file_digest_verified":"digest_mismatch");
  }
  private Fingerprint fingerprint(Path root,String relativeFile,boolean digestRequired) {
    try {
      GoalRepository.validateFile(relativeFile);
      if(!Files.isDirectory(root)||!root.toRealPath().equals(root))return new Fingerprint(false,null,"project_path_changed");
      Path current=root;
      for(var part:Path.of(relativeFile)) {
        current=current.resolve(part);
        if(Files.isSymbolicLink(current))return new Fingerprint(false,null,"symbolic_link_rejected");
      }
      if(!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS))return new Fingerprint(false,null,"file_missing_or_not_regular");
      if(!current.toRealPath().startsWith(root))return new Fingerprint(false,null,"outside_project");
      if(Files.size(current)>1_048_576)return new Fingerprint(false,null,"file_too_large");
      if(!digestRequired)return new Fingerprint(true,null,"file_exists");
      var digest=MessageDigest.getInstance("SHA-256");long count=0;
      try(var channel=FileChannel.open(current,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
        var buffer=ByteBuffer.allocate(8192);
        while(channel.read(buffer)>=0) {
          if(Thread.currentThread().isInterrupted())return new Fingerprint(false,null,"verification_cancelled");
          buffer.flip();count+=buffer.remaining();if(count>1_048_576)return new Fingerprint(false,null,"file_too_large");
          digest.update(buffer);buffer.clear();
        }
      }
      return new Fingerprint(true,HexFormat.of().formatHex(digest.digest()),"file_digest_observed");
    } catch(java.io.IOException|IllegalArgumentException error) {return new Fingerprint(false,null,"verification_unavailable");}
    catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
