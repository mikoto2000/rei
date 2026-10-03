package dev.mikoto2000.rei.workcontext;
import java.util.List;
import dev.mikoto2000.rei.workcontext.WorkContext.*;

public record WorkContextCandidate(String action,String targetId,Kind kind,String text,String reason,Status status,List<String> sourceIds,Origin certainty) {
  public WorkContextCandidate(String action,String targetId,Kind kind,String text,String reason,Status status,List<String> sourceIds) {
    this(action,targetId,kind,text,reason,status,sourceIds,null);
  }
  public WorkContextCandidate {
    if(!List.of("ADD","CORRECT","STATUS","SUPERSEDE","CONFLICT").contains(action)
        ||kind==null||status==null||text==null||text.isBlank()||text.length()>2000||reason==null||reason.length()>2000
        ||sourceIds==null||sourceIds.isEmpty()||sourceIds.size()>20) throw new IllegalArgumentException("Invalid Work Context candidate");
    sourceIds=List.copyOf(sourceIds);
  }
}
