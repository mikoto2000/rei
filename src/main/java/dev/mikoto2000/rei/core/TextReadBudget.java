package dev.mikoto2000.rei.core;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounds source payload bytes, including UTF-8 multibyte code points and enormous lines. */
final class TextReadBudget {
  static final int MAX_BYTES=64*1024;
  record Slice(List<String> lines,int nextLine,int nextOffset,boolean truncated,int bytes) {}
  private int remaining;
  TextReadBudget(){this(MAX_BYTES);}
  TextReadBudget(int bytes){remaining=Math.max(0,Math.min(MAX_BYTES,bytes));}
  int remaining(){return remaining;}
  boolean reserve(int bytes){if(bytes<0 || bytes>remaining)return false;remaining-=bytes;return true;}
  Slice take(List<String> source,int start,int end,int offset,int maxLines){
    var out=new ArrayList<String>();int used=0;int line=start;int nextOffset=offset;
    while(line<end && out.size()<maxLines && remaining>0){
      String value=source.get(line);if(nextOffset<0 || nextOffset>value.length() || nextOffset>0 && nextOffset<value.length() && Character.isLowSurrogate(value.charAt(nextOffset)))throw new IllegalArgumentException("Invalid continuation offset");
      int index=nextOffset;int cost=0;
      while(index<value.length()) {int cp=value.codePointAt(index);int size=cp<128?1:cp<2048?2:cp<65536?3:4;if(cost+size+1>remaining)break;cost+=size;index+=Character.charCount(cp);}
      if(index==nextOffset && index<value.length())break;
      out.add(value.substring(nextOffset,index));remaining-=cost+1;used+=cost+1;
      if(index<value.length()){nextOffset=index;break;}
      line++;nextOffset=0;
    }
    return new Slice(List.copyOf(out),line+1,nextOffset,line<end,used);
  }
}
