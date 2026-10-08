package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.util.*;

/** Fair per-file allocations; every omitted source prefix has a version-bound continuation. */
final class SearchResultBudget {
  static Tools.SearchAndReadResult fit(Tools.SearchAndReadResult result,int bytes,int tokens,String charset)throws IOException {
    return FileResultBudget.fit(result,bytes,tokens,value->smaller(value,charset));
  }
  private static Tools.SearchAndReadResult copy(Tools.SearchAndReadResult value,List<Tools.SearchMatch> matches,List<Tools.ReadSection> sections,Tools.ReadFileRequest next,int omitted) {
    return new Tools.SearchAndReadResult(value.path(),matches,sections,value.error(),true,value.filesTruncated(),value.version(),next,value.score(),value.matchedBy(),omitted);
  }
  private static Tools.ReadFileRequest cursor(Tools.SearchAndReadResult value,int line,int offset,String charset){
    var old=value.nextRead();if(old!=null && (old.startLine()<line || old.startLine()==line && (old.offset()==null?0:old.offset())<=offset))return old;
    return new Tools.ReadFileRequest(value.path(),line,null,value.version(),offset,charset);
  }
  private static Tools.SearchAndReadResult smaller(Tools.SearchAndReadResult value,String charset){
    var matches=value.matches();var sections=value.sections();var next=value.nextRead();int omitted=value.omittedMatches();
    if(matches.size()>1){int keep=(matches.size()+1)/2;return copy(value,List.copyOf(matches.subList(0,keep)),sections,next,omitted+matches.size()-keep);}
    if(!matches.isEmpty() && matches.getFirst().content().length()>64){
      var match=matches.getFirst();String text=FileResultBudget.half(match.content());
      return copy(value,List.of(new Tools.SearchMatch(match.queryIndex(),match.line(),text)),sections,cursor(value,match.line(),text.length(),charset),omitted);
    }
    if(sections.size()>1){int keep=(sections.size()+1)/2;return copy(value,matches,List.copyOf(sections.subList(0,keep)),cursor(value,sections.get(keep).startLine(),0,charset),omitted);}
    if(!sections.isEmpty()){
      var section=sections.getFirst();var lines=section.content();
      if(lines.size()>1){int keep=(lines.size()+1)/2;return copy(value,matches,List.of(new Tools.ReadSection(section.startLine(),section.startLine()+keep-1,List.copyOf(lines.subList(0,keep)),true)),cursor(value,section.startLine()+keep,0,charset),omitted);}
      if(!lines.isEmpty() && !lines.getFirst().isEmpty()){
        String text=FileResultBudget.half(lines.getFirst());var part=text.isEmpty()?List.<Tools.ReadSection>of():List.of(new Tools.ReadSection(section.startLine(),section.startLine(),List.of(text),true));
        return copy(value,matches,part,cursor(value,section.startLine(),text.length(),charset),omitted);
      }
      return copy(value,matches,List.of(),cursor(value,section.startLine(),0,charset),omitted);
    }
    if(!matches.isEmpty()){
      var match=matches.getFirst();return copy(value,List.of(),sections,cursor(value,match.line(),0,charset),omitted+1);
    }
    return value;
  }
}
