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
      var result=verifyFile(goal,criterion);if(!result.satisfied())return result;
    }
    return new Verification(true,"file_digest_verified");
  }
  private Verification verifyFile(GoalRepository.Goal goal,GoalRepository.FileCriterion criterion) {
    try {
      GoalRepository.validateFile(criterion.relativeFile());var root=Path.of(goal.projectRoot());
      if(!Files.isDirectory(root)||!root.toRealPath().equals(root))return new Verification(false,"project_path_changed");
      Path current=root;
      for(var part:Path.of(criterion.relativeFile())) {
        current=current.resolve(part);
        if(Files.isSymbolicLink(current))return new Verification(false,"symbolic_link_rejected");
      }
      if(!Files.isRegularFile(current,LinkOption.NOFOLLOW_LINKS))return new Verification(false,"file_missing_or_not_regular");
      if(!current.toRealPath().startsWith(root))return new Verification(false,"outside_project");
      if(Files.size(current)>1_048_576)return new Verification(false,"file_too_large");
      var digest=MessageDigest.getInstance("SHA-256");long count=0;
      try(var channel=FileChannel.open(current,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
        var buffer=ByteBuffer.allocate(8192);
        while(channel.read(buffer)>=0) {
          if(Thread.currentThread().isInterrupted())return new Verification(false,"verification_cancelled");
          buffer.flip();count+=buffer.remaining();if(count>1_048_576)return new Verification(false,"file_too_large");
          digest.update(buffer);buffer.clear();
        }
      }
      boolean match=HexFormat.of().formatHex(digest.digest()).equals(criterion.sha256());
      return new Verification(match,match?"file_digest_verified":"digest_mismatch");
    } catch(java.io.IOException|IllegalArgumentException error) {return new Verification(false,"verification_unavailable");}
    catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
