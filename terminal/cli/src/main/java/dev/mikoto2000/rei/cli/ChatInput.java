package dev.mikoto2000.rei.cli;

import java.util.*;

/** Explicit chat options; an unknown option must not become model input. */
record ChatInput(String runId,String mode,String message) {
  static ChatInput parse(List<String> words,String defaultMode) {
    String run=null,mode=defaultMode;int index=1;var seen=new HashSet<String>();
    while(index<words.size()&&words.get(index).startsWith("--")) {
      String option=words.get(index++);if(option.equals("--"))break;
      if(!Set.of("--run","--mode").contains(option)||!seen.add(option)||index>=words.size())throw invalid();
      String value=words.get(index++);
      if(option.equals("--run")){if(value.isBlank()||value.length()>256)throw invalid();run=value;}
      else mode=value.toUpperCase(Locale.ROOT).replace('-','_');
    }
    if(!Set.of("EXCLUSIVE","READ_ONLY","CONVERSATION").contains(mode)||run!=null&&!mode.equals("EXCLUSIVE")||index>=words.size())throw invalid();
    String message=String.join(" ",words.subList(index,words.size()));if(message.isBlank()||message.length()>16384)throw invalid();
    return new ChatInput(run,mode,message);
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Usage: /chat [--mode exclusive|read-only|conversation] [--run RUN_ID] [--] MESSAGE; Run guidance requires exclusive access");}
}
