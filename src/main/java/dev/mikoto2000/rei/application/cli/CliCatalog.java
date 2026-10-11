package dev.mikoto2000.rei.application.cli;
import java.util.List;
/** Immutable grammar data; it contains no executor, service, or filesystem capability. */
public record CliCatalog(List<Command> commands) {
  public CliCatalog {commands=List.copyOf(commands);}
  public record Command(String name,List<String> aliases,String description,List<Option> options,List<Parameter> parameters,List<Command> commands) {
    public Command {aliases=List.copyOf(aliases);options=List.copyOf(options);parameters=List.copyOf(parameters);commands=List.copyOf(commands);}
  }
  public record Option(List<String> names,String description,String defaultValue,String arity,boolean required,List<String> choices) {
    public Option {names=List.copyOf(names);choices=List.copyOf(choices);}
  }
  public record Parameter(String index,String label,String arity,String description,List<String> choices,String candidateType) {
    public Parameter {choices=List.copyOf(choices);}
  }
}
