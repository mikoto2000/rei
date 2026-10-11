package dev.mikoto2000.rei.cli;
import dev.mikoto2000.rei.application.cli.CliCatalog;
import java.util.*;

/** Pure completion/help over a captured grammar snapshot. No HTTP or service dependencies. */
final class CachedCatalog {
  private final CliCatalog catalog;
  CachedCatalog(CliCatalog catalog){this.catalog=catalog;}
  List<String> complete(List<String> words,int wordIndex,String prefix) {
    var values=new LinkedHashSet<String>();
    if(wordIndex==0){for(var command:catalog.commands()){values.add("/"+command.name());for(String alias:command.aliases())values.add("/"+alias);}return filtered(values,prefix);}
    if(words.isEmpty())return List.of();var selected=find(catalog.commands(),words.getFirst().replaceFirst("^/",""));if(selected==null)return List.of();
    int consumed=1;
    while(consumed<wordIndex&&consumed<words.size()){var child=find(selected.commands(),words.get(consumed));if(child==null)break;selected=child;consumed++;}
    if(wordIndex>consumed&&wordIndex<=words.size()) {
      String previous=words.get(wordIndex-1);
      for(var option:selected.options())if(option.names().contains(previous)&&!option.arity().equals("0"))return filtered(new LinkedHashSet<>(option.choices()),prefix);
    }
    if(prefix.startsWith("-")) {
      int equals=prefix.indexOf('=');
      if(equals>0){String name=prefix.substring(0,equals);for(var option:selected.options())if(option.names().contains(name)){for(String choice:option.choices())values.add(name+"="+choice);return filtered(values,prefix);}}
      for(var option:selected.options())values.addAll(option.names());return filtered(values,prefix);
    }
    for(var child:selected.commands()){values.add(child.name());values.addAll(child.aliases());}
    int position=0;
    for(int index=consumed;index<wordIndex&&index<words.size();index++) {
      String word=words.get(index);if(word.startsWith("-")) {
        var name=word.split("=",2)[0];var option=selected.options().stream().filter(item->item.names().contains(name)).findFirst().orElse(null);
        if(option!=null&&!option.arity().equals("0")&&!word.contains("="))index++;
      } else position++;
    }
    for(var parameter:selected.parameters())if(matches(parameter.index(),position))values.addAll(parameter.choices());
    return filtered(values,prefix);
  }
  String help(List<String> path) {
    List<CliCatalog.Command> commands=catalog.commands();CliCatalog.Command selected=null;
    for(String name:path){selected=find(commands,name.replaceFirst("^/",""));if(selected==null)return "";commands=selected.commands();}
    var text=new StringBuilder();
    if(selected==null){for(var command:commands)text.append('/').append(command.name()).append("  ").append(command.description()).append('\n');return text.toString();}
    text.append('/').append(String.join(" ",path)).append("  ").append(selected.description()).append('\n');
    for(var parameter:selected.parameters())text.append("  ").append(parameter.label()).append("  ").append(parameter.description()).append('\n');
    for(var option:selected.options()){text.append("  ").append(String.join(", ",option.names())).append("  ").append(option.description());if(option.defaultValue()!=null)text.append(" (default: ").append(option.defaultValue()).append(')');if(!option.choices().isEmpty())text.append(" ").append(option.choices());text.append('\n');}
    for(var command:commands)text.append("  ").append(command.name()).append("  ").append(command.description()).append('\n');
    if(path.getFirst().replaceFirst("^/","").equals("voice"))text.append("Voice is unavailable in this CLI. Use the existing Shell.\n");
    return text.toString();
  }
  private static boolean matches(String index,int position){try{String[] bounds=index.split("\\.\\.");int first=Integer.parseInt(bounds[0]);return position>=first&&(bounds.length==1?position==first:bounds[1].equals("*")||position<=Integer.parseInt(bounds[1]));}catch(NumberFormatException malformed){return position==0;}}
  private static CliCatalog.Command find(List<CliCatalog.Command> commands,String name){return commands.stream().filter(command->command.name().equals(name)||command.aliases().contains(name)).findFirst().orElse(null);}
  private static List<String> filtered(Set<String> values,String prefix){return values.stream().filter(value->value.startsWith(prefix)).toList();}
}
