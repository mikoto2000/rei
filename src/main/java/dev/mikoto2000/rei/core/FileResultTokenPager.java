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
    var capacities = new ArrayList<Integer>();
    for (int index=0;index<results.size();index++) {
      var result = results.get(index);
      int capacity = 0;
      if (result.error()==null && requests.get(index).symbol()==null)
        for (var line : result.content()) capacity += line.codePointCount(0,line.length()) + 1;
      capacities.add(capacity);
      total += capacity;
    }
    int low = 0, high = total;
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      if (estimate(prefix(results,requests,capacities,middle)) <= tokens) low = middle; else high = middle - 1;
    }
    // A completed file removes its nextRead envelope; inspect those non-monotonic boundaries too.
    for (int capacity : capacities) {
      int boundary = capacities.stream().mapToInt(value -> Math.min(value,capacity)).sum();
      if (boundary > low && estimate(prefix(results,requests,capacities,boundary)) <= tokens) low = boundary;
    }
    var page = prefix(results,requests,capacities,low);
    long readable = capacities.stream().filter(capacity -> capacity > 0).count();
    if (estimate(page) > tokens || low < readable)
      throw new IOException("CONTEXT_BUDGET_INSUFFICIENT; result metadata and a prefix of every file exceed the token budget; narrow the file batch or metadata");
    return page;
  }
  private int estimate(Object value) throws IOException { return estimator.text(mapper.writeValueAsString(value)); }
  private List<Tools.ReadFileResult> prefix(List<Tools.ReadFileResult> results, List<Tools.ReadFileRequest> requests,
      List<Integer> capacities, int units) {
    // Equal prefix quotas, redistributing unused quotas from short files. Never spend the whole page on file 1.
    int low=0,high=capacities.stream().mapToInt(Integer::intValue).max().orElse(0);
    while(low<high) {
      int middle=low+(high-low+1)/2;
      int needed=capacities.stream().mapToInt(value->Math.min(value,middle)).sum();
      if(needed<=units)low=middle;else high=middle-1;
    }
    int share=low;
    int remainder=units-capacities.stream().mapToInt(value->Math.min(value,share)).sum();
    var output = new ArrayList<Tools.ReadFileResult>();
    for (int index=0;index<results.size();index++) {
      var result=results.get(index);var request=requests.get(index);
      if (result.error()!=null || result.content().isEmpty() || request.symbol()!=null) { output.add(result); continue; }
      int quota=Math.min(capacities.get(index),share);
      if(remainder>0 && quota<capacities.get(index)) {quota++;remainder--;}
      var content=new ArrayList<String>();int partialOffset=-1;
      for(var line:result.content()) {
        int count=line.codePointCount(0,line.length());
        if(quota<=0)break;
        if(quota>=count+1) { content.add(line);quota-=count+1; }
        else {
          int end=line.offsetByCodePoints(0,Math.min(quota,count)); content.add(line.substring(0,end)); quota=0;
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
          List.copyOf(content),true,result.error(),result.version(),next,bytes,result.symbol(),result.candidates(),result.ownerOverview(),"RESULT_TOKEN_BUDGET"));
    }
    return List.copyOf(output);
  }
}
