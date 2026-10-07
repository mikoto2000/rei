package dev.mikoto2000.rei.github;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Only fixed facts from authenticated payloads. No title, body, command or URL is retained. */
public record GitHubFact(String type,String event,String action,String repository,String branch,Integer pullRequest,
    String headSha,String conclusion,Long workflowRunId,Instant occurredAt) {
  public String sourceId(){return sourceId(repository,pullRequest,branch);}
  public static String sourceId(String repository,Integer pullRequest,String branch) {
    String key=repository.toLowerCase(Locale.ROOT)+"\0"+(pullRequest==null?"branch:"+branch:"pr:"+pullRequest);
    try{return "github:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}
    catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
}
