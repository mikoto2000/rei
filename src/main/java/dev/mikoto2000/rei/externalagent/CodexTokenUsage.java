package dev.mikoto2000.rei.externalagent;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

/** Only a complete, successful single-turn JSONL stream is sufficient usage evidence. */
final class CodexTokenUsage {
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxNumberLength(32).maxStringLength(1048576).build())
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  static Integer total(ExternalAgentProcessRunner.Output output) {
    if(output.status()!=ExternalAgentResult.Status.SUCCESS||output.truncated()||output.stdout().length()>4194304)return null;
    boolean started=false,completed=false;Integer total=null;
    try {
      for(String line:output.stdout().split("\\R")) {
        if(line.isBlank())continue;
        if(completed)return null;
        var event=JSON.readTree(line);
        if(event==null||!event.isObject()||!event.path("type").isTextual())return null;
        String type=event.path("type").asText();
        if(type.equals("turn.started")) {if(started)return null;started=true;}
        if(type.equals("turn.failed")||type.equals("error"))return null;
        if(type.equals("turn.completed")) {
          if(!started)return null;
          var usage=event.path("usage");var input=usage.path("input_tokens");var result=usage.path("output_tokens");
          if(!input.isIntegralNumber()||!result.isIntegralNumber()||!input.canConvertToInt()||!result.canConvertToInt()
              ||input.intValue()<0||result.intValue()<0)return null;
          long sum=(long)input.intValue()+result.intValue();if(sum<=0||sum>Integer.MAX_VALUE)return null;
          total=(int)sum;completed=true;
        }
      }
      return completed?total:null;
    } catch(Exception invalid) {return null;}
  }
}
