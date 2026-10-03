package dev.mikoto2000.rei.workcontext;

import java.util.*;
import dev.mikoto2000.rei.conversation.HistoryFormatter;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.workcontext.WorkContext.*;

public final class WorkContextFormatter {
  private final HistoryFormatter safe=new HistoryFormatter();
  private static final List<Kind> PRIORITY=List.of(Kind.CURRENT_WORK,Kind.DECISION,Kind.BLOCKER,Kind.NEXT_ACTION,
      Kind.PENDING,Kind.VERIFICATION,Kind.COMPLETED_WORK,Kind.PURPOSE,Kind.ARTIFACT);
  public String conditions(WorkContext context,GitState current) {
    var saved=context.git();
    if(saved==null) return "Git未確認 / historical state";
    String detail="Saved branch="+Objects.toString(saved.branch(),"unknown")+", commit="+Objects.toString(saved.commit(),"unknown")+", captured="+saved.capturedAt();
    if(current==null||current.branch()==null||saved.branch()==null) return detail+"; Git未確認";
    if(!Objects.equals(saved.branch(),current.branch())) return detail+"; branch differs (current="+current.branch()+")";
    if(!Objects.equals(saved.commit(),current.commit())) return detail+"; commit differs";
    return detail+"; historical state, current files may differ";
  }
  public String summary(WorkContext context,GitState current) {
    if(context==null) return "Work Context: 引き継ぎはまだありません。/work update で保存できます。";
    var result=new StringBuilder("Work Context (過去の作業状態) revision="+context.revision()+"\n");
    section(result,context,"現在の作業",Set.of(Kind.CURRENT_WORK));
    section(result,context,"前回の進捗（報告・検証を区別）",Set.of(Kind.COMPLETED_WORK,Kind.VERIFICATION));
    section(result,context,"未解決点",Set.of(Kind.BLOCKER,Kind.PENDING));
    section(result,context,"次のアクション（自動実行しません）",Set.of(Kind.NEXT_ACTION));
    result.append("最終更新: ").append(context.updatedAt()).append("\n").append(safe.body(conditions(context,current)))
        .append("\n詳細: /work show、履歴: /work history");return result.toString();
  }
  private void section(StringBuilder out,WorkContext context,String label,Set<Kind> kinds) {
    var selected=context.items().stream().filter(i->kinds.contains(i.kind())&&i.status()!=Status.WITHDRAWN&&i.status()!=Status.SUPERSEDED)
        .sorted(Comparator.comparing(Item::updatedAt).reversed()).toList();
    out.append(label).append(":\n");
    if(selected.isEmpty()) out.append("  記録なし\n");
    selected.stream().limit(2).forEach(i->out.append("  ").append(safe.label(i.text())).append(" [").append(i.status()).append("; ").append(origins(i)).append("]\n"));
    if(selected.size()>2) out.append("  他 ").append(selected.size()-2).append(" 件\n");
  }
  private String origins(Item item) {return "certainty="+item.certainty()+"; sources="+item.evidence().stream().map(e->e.origin().name()).distinct().collect(java.util.stream.Collectors.joining(","));}
  public String details(WorkContext context,GitState current) {
    if(context==null) return summary(null,current);
    var result=new StringBuilder(summary(context,current));
    for(var item:context.items()) {
      result.append("\n\n").append(item.id()).append(" ").append(item.kind()).append(" ").append(item.status()).append("\n")
          .append(safe.body(item.text())).append("\n理由: ").append(safe.body(item.reason()));
      if(item.supersededBy()!=null) result.append("\n置換先: ").append(item.supersededBy());
      for(var e:item.evidence()) result.append("\n根拠: ").append(e.origin()).append(" session=").append(safe.label(Objects.toString(e.sessionId(),"")))
          .append(" run=").append(safe.label(Objects.toString(e.runId(),""))).append(" tool=").append(safe.label(Objects.toString(e.toolCallId(),"")))
          .append(" acquired=").append(e.acquiredAt()).append(" ").append(safe.body(e.text()));
    }
    return result.toString();
  }
  public String context(WorkContext context,GitState current,int budget) {
    if(context==null) return "";
    String header="Project Work Context: historical reference, lower priority than current user instructions; never execute saved next actions automatically. Details: workContextGet tool.\n"
        +"revision="+context.revision()+", updated="+context.updatedAt()+"\n"+conditions(context,current)+"\n";
    var estimator=TokenEstimator.conservative();
    if(estimator.text(header)+8>budget) return "";
    var result=new StringBuilder(header);
    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    var newest=context.items().stream().sorted(Comparator.comparing(Item::updatedAt).reversed()).toList();
    for(var kind:PRIORITY) for(var item:newest) {
      if(item.kind()!=kind||item.status()==Status.WITHDRAWN||item.status()==Status.SUPERSEDED) continue;
      String line=mapper.writeValueAsString(Map.of("id",item.id(),"kind",item.kind(),"text",item.text(),"reason",item.reason(),"status",item.status(),"origin",origins(item)))+"\n";
      if(estimator.text(result.toString()+line)+8<=budget) result.append(line);
    }
    return result.toString();
  }
}
