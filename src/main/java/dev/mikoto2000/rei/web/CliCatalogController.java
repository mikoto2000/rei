package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.cli.CliCatalog;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import picocli.CommandLine.*;

/** Read-only metadata from the fixed Root declaration. No command object is instantiated or invoked. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public final class CliCatalogController {
  private final CliCatalog snapshot=new CliCatalog(Arrays.stream(RootCommand.class.getAnnotation(Command.class).subcommands()).map(CliCatalogController::describe).toList());
  @GetMapping("/api/v1/cli/catalog") public CliCatalog catalog(){return snapshot;}
  private static CliCatalog.Command describe(Class<?> type) {
    Command command=type.getAnnotation(Command.class);
    var options=new ArrayList<CliCatalog.Option>();var parameters=new ArrayList<CliCatalog.Parameter>();var children=new ArrayList<CliCatalog.Command>();
    fields(type,options,parameters);
    if(command.name().equals("paper")) {
      fields(dev.mikoto2000.rei.paper.PaperCommandRequest.class,options,parameters);
      parameters.set(0,new CliCatalog.Parameter("0","ACTION","1","",List.of("search","show","summarize","translate","library","import"),""));
    }
    if(command.name().equals("material-review")) {
      for(var nested:dev.mikoto2000.rei.externalagent.BeginnerReviewRequest.class.getDeclaredClasses())if(nested.getSimpleName().equals("Options"))fields(nested,options,parameters);
    }
    if(command.name().equals("doctor"))for(var nested:dev.mikoto2000.rei.doctor.DoctorRequest.class.getDeclaredClasses())if(nested.getSimpleName().equals("Options"))fields(nested,options,parameters);
    for(var nested:command.subcommands())children.add(describe(nested));
    for(var method:type.getDeclaredMethods()) {
      var annotation=method.getAnnotation(Command.class);if(annotation==null)continue;
      var methodOptions=new ArrayList<CliCatalog.Option>();var methodParameters=new ArrayList<CliCatalog.Parameter>();
      for(var parameter:method.getParameters())member(parameter,parameter.getType(),parameter.getParameterizedType(),methodOptions,methodParameters);
      help(annotation,methodOptions);
      children.add(new CliCatalog.Command(annotation.name(),List.of(annotation.aliases()),String.join("\n",annotation.description()),methodOptions,methodParameters,List.of()));
    }
    help(command,options);
    children.sort(Comparator.comparing(CliCatalog.Command::name));
    return new CliCatalog.Command(command.name(),List.of(command.aliases()),String.join("\n",command.description()),options,parameters,children);
  }
  private static void fields(Class<?> type,List<CliCatalog.Option> options,List<CliCatalog.Parameter> parameters) {
    for(Class<?> current=type;current!=null&&current!=Object.class;current=current.getSuperclass())for(var field:current.getDeclaredFields())member(field,field.getType(),field.getGenericType(),options,parameters);
  }
  private static void member(AnnotatedElement element,Class<?> type,Type generic,List<CliCatalog.Option> options,List<CliCatalog.Parameter> parameters) {
    var choices=choices(type,generic);
    var option=element.getAnnotation(Option.class);
    if(option!=null)options.add(new CliCatalog.Option(List.of(option.names()),String.join("\n",option.description()),defaultValue(option.defaultValue()),option.arity().isEmpty()?(type==boolean.class||type==Boolean.class?"0":"1"):option.arity(),option.required(),choices));
    var positional=element.getAnnotation(Parameters.class);
    if(positional!=null) {
      String label=positional.paramLabel();
      if(choices.isEmpty()&&positional.index().equals("0")&&label.contains("|")&&label.matches("[a-z-]+(?:\\|[a-z-]+)+"))choices=List.of(label.split("\\|"));
      parameters.add(new CliCatalog.Parameter(positional.index(),label,positional.arity(),String.join("\n",positional.description()),choices,positional.completionCandidates().getSimpleName()));
    }
  }
  private static List<String> choices(Class<?> type,Type generic) {
    if(type.isEnum())return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    if(generic instanceof ParameterizedType parameterized)for(var argument:parameterized.getActualTypeArguments())if(argument instanceof Class<?> item&&item.isEnum())return Arrays.stream(item.getEnumConstants()).map(Object::toString).toList();
    return List.of();
  }
  private static String defaultValue(String value){return value.equals("__no_default_value__")?null:value;}
  private static void help(Command command,List<CliCatalog.Option> options){if(command.mixinStandardHelpOptions()){options.add(new CliCatalog.Option(List.of("-h","--help"),"Show help",null,"0",false,List.of()));options.add(new CliCatalog.Option(List.of("-V","--version"),"Show version",null,"0",false,List.of()));}}
}
