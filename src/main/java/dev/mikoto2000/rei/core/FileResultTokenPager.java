package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

/** Keeps the existing result schema; every omitted source suffix has a version-bound native cursor. */
final class FileResultTokenPager {
  private final TokenEstimator estimator;
  private final ObjectMapper mapper = new ObjectMapper();
  FileResultTokenPager(TokenEstimator estimator) { this.estimator = estimator; }
  List<Tools.ReadFileResult> page(List<Tools.ReadFileResult> results, List<Tools.ReadFileRequest> requests, int tokens) throws IOException {
    if (results.size() != requests.size()) throw new IOException("Invalid file result association");
    if (estimate(results) <= tokens) return results;
    int total = 0;
    var boundaries = new ArrayList<Integer>();
    for (var result : results) {
      for (var line : result.content()) total += line.codePointCount(0,line.length()) + 1;
      boundaries.add(total);
    }
    int low = 0, high = total;
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      if (estimate(prefix(results,requests,middle)) <= tokens) low = middle; else high = middle - 1;
    }
    // A completed file removes its nextRead envelope; inspect those non-monotonic boundaries too.
    for (int boundary : boundaries) if (boundary > low && estimate(prefix(results,requests,boundary)) <= tokens) low = boundary;
    var page = prefix(results,requests,low);
    if (estimate(page) > tokens || total > 0 && low == 0) throw new IOException("CONTEXT_BUDGET_INSUFFICIENT; narrow the file batch or metadata");
    return page;
  }
  private int estimate(Object value) throws IOException { return estimator.text(mapper.writeValueAsString(value)); }
  private List<Tools.ReadFileResult> prefix(List<Tools.ReadFileResult> results, List<Tools.ReadFileRequest> requests, int units) {
    var output = new ArrayList<Tools.ReadFileResult>();
    for (int index=0;index<results.size();index++) {
      var result=results.get(index);var request=requests.get(index);
      if (result.error()!=null || result.content().isEmpty() || request.symbol()!=null) { output.add(result); continue; }
      var content=new ArrayList<String>();int partialOffset=-1;
      for(var line:result.content()) {
        int count=line.codePointCount(0,line.length());
        if(units<=0)break;
        if(units>=count+1) { content.add(line);units-=count+1; }
        else {
          int end=line.offsetByCodePoints(0,Math.min(units,count)); content.add(line.substring(0,end)); units=0;
          if(end<line.length())partialOffset=end;
          break;
        }
      }
      if(content.size()==result.content().size() && partialOffset<0) { output.add(result);continue; }
      int nextLine=result.startLine()+content.size();int nextOffset=0;
      if(partialOffset>=0) { nextLine--;nextOffset=partialOffset+(content.size()==1&&request.offset()!=null?request.offset():0); }
      else if(content.isEmpty())nextOffset=request.offset()==null?0:request.offset();
      var next=new Tools.ReadFileRequest(result.path(),nextLine,request.endLine(),result.version(),nextOffset,request.charset());
      int bytes=content.stream().mapToInt(line->line.getBytes(StandardCharsets.UTF_8).length+1).sum();
      output.add(new Tools.ReadFileResult(result.path(),result.startLine(),content.isEmpty()?null:result.startLine()+content.size()-1,
          List.copyOf(content),true,result.error(),result.version(),next,bytes,result.symbol(),result.candidates(),result.ownerOverview()));
    }
    return List.copyOf(output);
  }
}
