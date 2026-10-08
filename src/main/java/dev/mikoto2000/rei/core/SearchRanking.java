package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import dev.mikoto2000.rei.core.working.WorkingSet;

/** Fixed, explainable rules. AST enrichment is a deterministic top-32 candidate refinement. */
final class SearchRanking {
  record Ranked(String path,List<Tools.SearchMatch> matches,int score,List<String> matchedBy){}
  static List<Ranked> rank(Map<String,List<Tools.SearchMatch>> files,List<Tools.GrepQuery> queries,List<String> preferred,Path root,FileSnapshots snapshots,RepositoryMapService maps,WorkingSet working)throws IOException {
    var words=queries.stream().map(Tools.GrepQuery::pattern).filter(word->word!=null && !word.isBlank()).map(word->word.toLowerCase(Locale.ROOT)).distinct().toList();
    var preliminary=files.entrySet().stream().map(entry->score(entry.getKey(),entry.getValue(),words,preferred,working.contains(root.resolve(entry.getKey())),null,false)).sorted(order()).toList();
    Set<String> enriched=new HashSet<>();for(var candidate:preliminary.stream().filter(item->item.path().endsWith(".java")).limit(32).toList())enriched.add(candidate.path());
    var result=new ArrayList<Ranked>();
    for(var candidate:preliminary){
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);RepositoryMapService.File metadata=null;
      if(enriched.contains(candidate.path()))metadata=maps.describeSnapshot(root,snapshots.get(root,root.resolve(candidate.path())));
      result.add(score(candidate.path(),candidate.matches(),words,preferred,working.contains(root.resolve(candidate.path())),metadata,candidate.path().endsWith(".java") && !enriched.contains(candidate.path())));
    }
    result.sort(order());return List.copyOf(result);
  }
  private static Comparator<Ranked> order(){return Comparator.comparingInt(Ranked::score).reversed().thenComparing(Ranked::path);}
  private static Ranked score(String path,List<Tools.SearchMatch> matches,List<String> words,List<String> preferred,boolean working,RepositoryMapService.File metadata,boolean limited){
    String lower=path.replace('\\','/').toLowerCase(Locale.ROOT),name=Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');String stem=dot<0?name:name.substring(0,dot);
    int points=0;var reasons=new ArrayList<String>();
    if(preferred!=null && preferred.stream().map(value->value.replace('\\','/').toLowerCase(Locale.ROOT)).anyMatch(lower::equals)){points+=10000;reasons.add("explicit-path");}
    if(words.stream().anyMatch(stem::equals)){points+=450;reasons.add("filename-exact");}else if(words.stream().anyMatch(stem::contains)){points+=150;reasons.add("filename-partial");}
    if(words.stream().anyMatch(lower::contains)){points+=70;reasons.add("path");}
    if(metadata!=null && metadata.symbols().stream().anyMatch(symbol->{String qualified=symbol.name().toLowerCase(Locale.ROOT),simple=qualified.substring(qualified.lastIndexOf('.')+1);return words.stream().anyMatch(word->word.equals(qualified)||word.equals(simple));})){points+=800;reasons.add("symbol-exact");}
    if(matches.stream().anyMatch(match->words.contains(match.content().strip().toLowerCase(Locale.ROOT)))){points+=40;reasons.add("body-exact");}else{points+=10;reasons.add("body");}
    if(matches.size()>1){points+=Math.min(16,matches.size())*3;reasons.add("multiple-hits");}
    if(working){points+=30;reasons.add("working-file");}if(limited)reasons.add("symbol-ranking-limited");
    if(metadata!=null && !metadata.status().equals("PARSED"))reasons.add("symbol-unavailable");
    return new Ranked(path,List.copyOf(matches),points,List.copyOf(reasons));
  }
}
