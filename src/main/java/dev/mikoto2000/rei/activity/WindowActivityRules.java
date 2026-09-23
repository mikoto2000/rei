package dev.mikoto2000.rei.activity;

import java.util.*;
import java.util.regex.Pattern;

/** Small independent rules. Browser services require both a browser process and a known title suffix. */
final class WindowActivityRules {
  record Match(ActivityRecord.Activity activity,double confidence,String rule) {}
  private record ServiceRule(Pattern pattern,String category,String service) {}
  private static final List<ServiceRule> SERVICES=List.of(
      new ServiceRule(Pattern.compile("(?iu)(?: / X| - X)$"),"social","X"),
      new ServiceRule(Pattern.compile("(?iu) - YouTube$"),"media","YouTube"),
      new ServiceRule(Pattern.compile("(?iu)(?: - | · | / )GitHub$"),"development","GitHub"),
      new ServiceRule(Pattern.compile("(?iu)(?: - | / )Bluesky$"),"social","Bluesky"));
  Match classify(ForegroundWindow window,String monitor) {
    String process=ActivityRolePolicy.application(window.processName());String title=window.windowTitle().strip();
    if(process.equals("vscode")) {
      var parts=title.split(" - ");String project="";
      if(parts.length>=3 && parts[parts.length-1].equalsIgnoreCase("Visual Studio Code")) project=parts[parts.length-2];
      return match(monitor,"development","Visual Studio Code","",title,project,project.isBlank()?.7:.95,"editor_title");
    }
    if(Set.of("firefox","chrome","edge").contains(process)) {
      String page=title.replaceFirst("(?iu)\\s+[—–-]\\s+(?:Mozilla Firefox|Google Chrome|Microsoft Edge)$","");
      for(var rule:SERVICES) {
        var matcher=rule.pattern().matcher(page);
        if(matcher.find()) return match(monitor,rule.category(),window.processName(),rule.service(),page.substring(0,matcher.start()).strip(),"",.95,"browser_title");
      }
      return match(monitor,"unknown",window.processName(),"","","",.4,"generic_browser");
    }
    if(process.equals("terminal")) return match(monitor,"unknown",window.processName(),"","","",.4,"generic_terminal");
    return match(monitor,"unknown",window.processName(),"","","",.2,"unrecognized_process");
  }
  private Match match(String monitor,String type,String application,String service,String content,String project,double confidence,String reason) {
    return new Match(new ActivityRecord.Activity(monitor,type,application,service,content,project),confidence,reason);
  }
}
