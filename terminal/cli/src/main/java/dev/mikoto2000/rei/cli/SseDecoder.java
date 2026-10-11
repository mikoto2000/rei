package dev.mikoto2000.rei.cli;

import java.io.*;
import java.util.function.Consumer;

/** Incremental, bounded SSE decoder. Sequence is advanced only after successful delivery. */
public final class SseDecoder {
  public record Event(long sequence,String type,String data) {}
  private long sequence;
  public SseDecoder(long sequence){this.sequence=sequence;}
  public long sequence(){return sequence;}
  public void read(Reader input,Consumer<Event> deliver)throws IOException {
    StringBuilder line=new StringBuilder(),data=new StringBuilder();String type="message";long id=-1;int size=0;
    int character;
    while((character=input.read())!=-1) {
      if(character=='\r')continue;
      if(++size>1048576)throw new IOException("SSE event capacity exceeded");
      if(character!='\n'){line.append((char)character);continue;}
      String value=line.toString();line.setLength(0);
      if(value.isEmpty()) {
        if(id>sequence&&!data.isEmpty()&&!type.equals("heartbeat")) {
          deliver.accept(new Event(id,type,data.substring(0,data.length()-1)));sequence=id;
        }
        data.setLength(0);type="message";id=-1;size=0;continue;
      }
      if(value.startsWith(":"))continue;
      int separator=value.indexOf(':');String field=separator<0?value:value.substring(0,separator);
      String content=separator<0?"":value.substring(separator+1);if(content.startsWith(" "))content=content.substring(1);
      switch(field) {
        case "event"->type=content;
        case "data"->data.append(content).append('\n');
        case "id"->{try{id=Long.parseLong(content);}catch(NumberFormatException invalid){throw new IOException("Invalid SSE sequence");}}
        default->{}
      }
    }
  }
}
