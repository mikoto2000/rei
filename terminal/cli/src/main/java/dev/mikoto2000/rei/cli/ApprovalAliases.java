package dev.mikoto2000.rei.cli;

import java.util.*;

/** Numbers are never reused and are bound to this terminal's displayed Project/Session selection. */
final class ApprovalAliases {
  private record Entry(String project,String session,String id) {}
  private final Map<String,Entry> rows=new LinkedHashMap<>();
  String bind(String project,String session,String id) {
    var entry=new Entry(project,session,id);
    for(var row:rows.entrySet())if(row.getValue().equals(entry))return row.getKey();
    if(rows.size()>=1024)return id;
    String number=Integer.toString(rows.size()+1);rows.put(number,entry);return number;
  }
  String resolve(String project,String session,String value) {
    if(!value.matches("[0-9]+"))return value;
    var entry=rows.get(value);
    if(entry==null||!Objects.equals(project,entry.project())||!Objects.equals(session,entry.session()))
      throw new IllegalArgumentException("現在のProject/Sessionで /approval list を実行してください。表示した番号だけが利用できます。");
    return entry.id();
  }
}
