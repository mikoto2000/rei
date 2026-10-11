package dev.mikoto2000.rei.cli;

import java.util.*;

/** Explicit chat options; an unknown option must not become model input. */
record ChatInput(String runId,String mode,String message) {
  static ChatInput parse(List<String> words,String defaultMode) {
    String run=null,mode=defaultMode;var seen=new HashSet<String>();var prompt=new ArrayList<String>();boolean options=true;
    for(int index=1;index<words.size();index++) {
      String token=words.get(index);if(options&&token.equals("--")){options=false;continue;}
      if(!options||!token.startsWith("-")){prompt.add(token);continue;}
      int equals=token.indexOf('=');String option=equals<0?token:token.substring(0,equals);
      if(!Set.of("--run","--mode").contains(option)||!seen.add(option))throw invalid();
      if(equals<0&&index+1>=words.size())throw invalid();String value=equals<0?words.get(++index):token.substring(equals+1);
      if(option.equals("--run")){if(value.isBlank()||value.length()>256)throw invalid();run=value;}
      else mode=value.toUpperCase(Locale.ROOT).replace('-','_');
    }
    if(!Set.of("EXCLUSIVE","READ_ONLY","CONVERSATION").contains(mode)||run!=null&&!mode.equals("EXCLUSIVE")||prompt.isEmpty())throw invalid();
    String message=String.join(" ",prompt);if(message.isBlank()||message.length()>16384)throw invalid();
    return new ChatInput(run,mode,message);
  }
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Usage: /chat [--mode exclusive|read-only|conversation] [--run RUN_ID] [--] MESSAGE; Run guidance requires exclusive access");}
}
