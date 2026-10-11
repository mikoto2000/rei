package dev.mikoto2000.rei.slack;

import java.util.*;
import dev.mikoto2000.rei.event.CredentialRedactor;

public final class SlackReplies {
  private SlackReplies() {}
  public static String safe(String text) {
    String result=CredentialRedactor.redact(text).replaceAll("\\bx(?:ox[baprs]|app)-[A-Za-z0-9-]+","[REDACTED]");
    for(String name:List.of("SLACK_BOT_TOKEN","SLACK_APP_TOKEN")) {
      String secret=System.getenv(name);if(secret!=null&&!secret.isBlank())result=result.replace(secret,"[REDACTED]");
    }
    return result;
  }
  public static List<String> parts(String text) {
    String safe=safe(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replaceAll("\\*\\*([^*]+)\\*\\*","*$1*").replaceAll("~~([^~]+)~~","~$1~");
    var result=new ArrayList<String>();
    for(int start=0;start<safe.length();) {
      int end=Math.min(start+3000,safe.length());
      if(end<safe.length()&&Character.isHighSurrogate(safe.charAt(end-1)))end--;
      result.add(safe.substring(start,end));start=end;
    }
    return List.copyOf(result);
  }
}
