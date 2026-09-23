package dev.mikoto2000.rei.activity;

import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** Ordered, anchored service rules. Called only after the OS process has been identified as a browser. */
final class BrowserTitleRules {
  private record Decision(String category,String service,String content,String project,double categoryConfidence,double serviceConfidence,double projectConfidence) {}
  private record Rule(String id,int priority,Pattern pattern,Function<Matcher,Decision> result) {}
  private static Rule rule(String id,int priority,String regex,Function<Matcher,Decision> result) {
    return new Rule(id,priority,Pattern.compile(regex,Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),result);
  }
  private static String group(Matcher m,int group){return Objects.requireNonNullElse(m.group(group),"").strip();}
  private static Decision service(String category,String service,String content){return new Decision(category,service,content,"",.95,.98,0);}
  private static final Pattern REPOSITORY=Pattern.compile("(?:^|\\s)([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?=$|\\s|:)");
  private static Decision github(String prefix,boolean explicit) {
    var repo=REPOSITORY.matcher(prefix);String project=repo.find()?repo.group(2):"";
    boolean change=Pattern.compile("(?iu)\\b(pull requests?|commit|issues?)\\b").matcher(prefix).find();
    return new Decision(explicit && (!project.isEmpty() || change)?"development":"research","GitHub",prefix,project,
        explicit?(change?.85:project.isEmpty()?.65:.75):.55,explicit?.95:.65,project.isEmpty()?0:explicit?.8:.6);
  }
  private static final List<Rule> RULES=java.util.stream.Stream.of(
      rule("X_BROWSER_TITLE",100,"^(?:(.*?) (?:/|-) )?(?:X|Twitter|X \\(Twitter\\))$",m->service("social","X",group(m,1))),
      rule("YOUTUBE_MUSIC_TITLE",100,"^(?:(.*?) - )?YouTube Music$",m->service("media","YouTube Music",group(m,1))),
      rule("YOUTUBE_TITLE",100,"^(?:(.*?) - )?YouTube$",m->service("media","YouTube",group(m,1))),
      rule("AMAZON_TITLE",100,"^(?:Amazon(?:\\.co\\.jp|\\.com)?(?::.*|：.*)?|(.+?) - Amazon(?:\\.co\\.jp|\\.com)?)$",m->service("shopping","Amazon",group(m,1))),
      rule("BLUESKY_TITLE",100,"^(?:(.*?) (?:/|[-—–]) )?Bluesky$",m->service("social","Bluesky",group(m,1))),
      rule("GOOGLE_NEWS_TITLE",100,"^Google (?:ニュース|News)(?: [-—–] .+)?$",m->new Decision("research","Google News","","",.85,.95,0)),
      rule("GITHUB_TITLE",100,"^(?:(.*?) (?:-|·|/) )?GitHub$",m->github(group(m,1),true)),
      rule("ASSISTANT_SERVICE_TITLE",100,"^(?:(.*?) (?:-|—|\\|) )?(ChatGPT|OpenAI)$",m->new Decision("unknown",group(m,2).equalsIgnoreCase("ChatGPT")?"ChatGPT":"OpenAI",group(m,1),"",0,.95,0)),
      rule("SEARCH_RESULTS_TITLE",100,"^(.+?) - (Google 検索|Google Search|Bing|DuckDuckGo)$",m->new Decision("research",group(m,2).startsWith("Google")?"Google":group(m,2),group(m,1),"",.85,.95,0)),
      rule("SEARCH_HOME_TITLE",90,"^(Google|Bing|DuckDuckGo)$",m->new Decision("research",group(m,1),"","",.55,.95,0)),
      rule("REPOSITORY_TITLE_CANDIDATE",50,"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$",m->github(m.group(),false))
    ).sorted(Comparator.comparingInt(Rule::priority).reversed()).toList();
  WindowActivityRules.Match classify(ForegroundWindow window,String monitor) {
    String title=window.windowTitle().strip().replaceFirst("(?iu)\\s+[—–-]\\s+(?:Mozilla Firefox|Google Chrome|Microsoft Edge)$","");
    for(var rule:RULES) {
      var matcher=rule.pattern().matcher(title);if(!matcher.matches())continue;
      var d=rule.result().apply(matcher);
      var activity=new ActivityRecord.Activity(monitor,d.category(),window.processName(),d.service(),d.content(),d.project());
      return new WindowActivityRules.Match(activity,new ActivityFieldConfidence(d.categoryConfidence(),1,d.serviceConfidence(),d.projectConfidence(),d.content().isBlank()?0:.85),rule.id());
    }
    return WindowActivityRules.match(monitor,"unknown",window.processName(),"","","",0,"GENERIC_BROWSER");
  }
}
