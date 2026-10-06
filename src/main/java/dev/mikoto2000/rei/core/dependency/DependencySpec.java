package dev.mikoto2000.rei.core.dependency;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;

public record DependencySpec(Kind kind,String target,String expected) {
  public enum Kind { FILE_EXISTS, FILE_SHA256, FILE_CHANGED, GIT_STATE_CHANGED, PROCESS_EXIT, HTTP_STATUS, HTTP_BODY_SHA256, USER_ANSWER }
  public boolean network(){return kind==Kind.HTTP_STATUS||kind==Kind.HTTP_BODY_SHA256;}
  public DependencySpec {
    if(kind==null||target==null||target.isBlank()||target.length()>2048||expected!=null&&expected.length()>4096)
      throw new IllegalArgumentException("Bounded dependency kind and target required");
    switch(kind) {
      case FILE_EXISTS,FILE_SHA256,FILE_CHANGED -> {
        var path=Path.of(target);if(target.length()>1024||path.isAbsolute()||path.getRoot()!=null||target.contains(":"))throw new IllegalArgumentException("Project-relative file required");
        for(var part:path)if(part.toString().equals(".."))throw new IllegalArgumentException("Parent traversal rejected");
        if(kind==Kind.FILE_SHA256&&(expected==null||!expected.matches("[a-fA-F0-9]{64}")))throw new IllegalArgumentException("SHA-256 required");
        if(kind==Kind.FILE_CHANGED&&(expected==null||!expected.equals("missing")&&!expected.matches("[a-fA-F0-9]{64}")))throw new IllegalArgumentException("File fingerprint baseline required");
        expected=kind==Kind.FILE_EXISTS?null:expected.toLowerCase(Locale.ROOT);
      }
      case HTTP_STATUS,HTTP_BODY_SHA256 -> {
        var uri=URI.create(target);
        if(uri.getScheme()==null||!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
          throw new IllegalArgumentException("HTTP URL without user info or fragment required");
        if(kind==Kind.HTTP_STATUS) {
          if(expected==null||!expected.matches("[1-5][0-9]{2}"))throw new IllegalArgumentException("HTTP status 100..599 required");
        }else {
          if(expected==null||!expected.matches("[1-5][0-9]{2}:[a-fA-F0-9]{64}"))throw new IllegalArgumentException("HTTP status:SHA-256 required");
          expected=expected.toLowerCase(Locale.ROOT);
        }
      }
      case PROCESS_EXIT -> {if(target.length()>128)throw new IllegalArgumentException("Process ID too long");if(expected==null)expected="0";if(!expected.matches("-?[0-9]{1,3}"))throw new IllegalArgumentException("Bounded exit code required");}
      case GIT_STATE_CHANGED -> {
        if(!target.equals("HEAD")||expected==null)throw new IllegalArgumentException("HEAD baseline required");
        var parts=expected.split("\n",-1);
        if(parts.length!=2||parts[0].isBlank()||parts[0].length()>256||parts[0].contains("\r")||!parts[1].matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Branch and commit baseline required");
      }
      case USER_ANSWER -> {if(target.length()>1024)throw new IllegalArgumentException("Question too long");expected=null;}
    }
  }
}
