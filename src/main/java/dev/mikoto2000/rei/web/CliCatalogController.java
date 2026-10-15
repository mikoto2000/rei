package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import picocli.CommandLine.*;

/**
 * Read-only metadata from the fixed Root declaration. No command object is instantiated or invoked.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class CliCatalogController {
  private final CliCatalog snapshot =
      new CliCatalog(
          Arrays.stream(RootCommand.class.getAnnotation(Command.class).subcommands())
              .map(CliCatalogController::describe)
              .toList());

  @GetMapping("/api/v1/cli/catalog")
  public CliCatalog catalog() {
    return snapshot;
  }

  private static CliCatalog.Command describe(Class<?> type) {
    Command command = type.getAnnotation(Command.class);
    var options = new ArrayList<CliCatalog.Option>();
    var parameters = new ArrayList<CliCatalog.Parameter>();
    var children = new ArrayList<CliCatalog.Command>();
    var groups = new ArrayList<CliCatalog.Group>();
    fields(type, options, parameters, groups);
    if (command.name().equals("paper")) {
      fields(dev.mikoto2000.rei.paper.PaperCommandRequest.class, options, parameters, groups);
      parameters.set(
          0,
          new CliCatalog.Parameter(
              "0",
              "ACTION",
              "1",
              "",
              List.of("search", "show", "summarize", "translate", "library", "import"),
              ""));
    }
    if (command.name().equals("material-review")) {
      for (var nested :
          dev.mikoto2000.rei.externalagent.BeginnerReviewRequest.class.getDeclaredClasses())
        if (nested.getSimpleName().equals("Options")) fields(nested, options, parameters, groups);
    }
    if (command.name().equals("doctor"))
      for (var nested : dev.mikoto2000.rei.doctor.DoctorRequest.class.getDeclaredClasses())
        if (nested.getSimpleName().equals("Options")) fields(nested, options, parameters, groups);
    for (var nested : command.subcommands()) children.add(describe(nested));
    for (var method :
        Arrays.stream(type.getDeclaredMethods())
            .sorted(Comparator.comparing(Method::getName))
            .toList()) {
      var annotation = method.getAnnotation(Command.class);
      if (annotation == null) continue;
      var methodOptions = new ArrayList<CliCatalog.Option>();
      var methodParameters = new ArrayList<CliCatalog.Parameter>();
      for (var parameter : method.getParameters())
        member(
            parameter,
            parameter.getType(),
            parameter.getParameterizedType(),
            methodOptions,
            methodParameters);
      help(annotation, methodOptions);
      children.add(
          new CliCatalog.Command(
              annotation.name(),
              List.of(annotation.aliases()),
              String.join("\n", annotation.description()),
              methodOptions,
              methodParameters,
              List.of()));
    }
    help(command, options);
    return new CliCatalog.Command(
        command.name(),
        List.of(command.aliases()),
        String.join("\n", command.description()),
        options,
        parameters,
        children,
        Runnable.class.isAssignableFrom(type)
            || java.util.concurrent.Callable.class.isAssignableFrom(type),
        groups);
  }

  private static void fields(
      Class<?> type,
      List<CliCatalog.Option> options,
      List<CliCatalog.Parameter> parameters,
      List<CliCatalog.Group> groups) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass())
      for (var field : current.getDeclaredFields()) {
        var annotation = field.getAnnotation(ArgGroup.class);
        if (annotation == null)
          member(field, field.getType(), field.getGenericType(), options, parameters);
        else {
          var groupedOptions = new ArrayList<CliCatalog.Option>();
          var nested = new ArrayList<CliCatalog.Group>();
          fields(field.getType(), groupedOptions, parameters, nested);
          groups.add(
              new CliCatalog.Group(
                  annotation.exclusive(), annotation.multiplicity(), groupedOptions, nested));
          options.addAll(groupedOptions);
        }
      }
  }

  private static void member(
      AnnotatedElement element,
      Class<?> type,
      Type generic,
      List<CliCatalog.Option> options,
      List<CliCatalog.Parameter> parameters) {
    var choices = choices(type, generic);
    var option = element.getAnnotation(Option.class);
    if (option != null)
      options.add(
          new CliCatalog.Option(
              List.of(option.names()),
              String.join("\n", option.description()),
              defaultValue(option.defaultValue()),
              option.arity().isEmpty()
                  ? (type == boolean.class || type == Boolean.class ? "0" : "1")
                  : option.arity(),
              option.required(),
              choices,
              candidateType(option.completionCandidates(), type),
              option.paramLabel().isEmpty() ? "<" + memberName(element) + ">" : option.paramLabel(),
              type.isArray() || Collection.class.isAssignableFrom(type),
              option.split()));
    var positional = element.getAnnotation(Parameters.class);
    if (positional != null) {
      String label =
          positional.paramLabel().isEmpty()
              ? "<" + memberName(element) + ">"
              : positional.paramLabel();
      var declared = declaredChoices(positional.completionCandidates());
      if (!declared.isEmpty()) choices = declared;
      if (choices.isEmpty()
          && positional.index().equals("0")
          && label.contains("|")
          && label.matches("[a-z-]+(?:\\|[a-z-]+)+")) choices = List.of(label.split("\\|"));
      if (element instanceof Field field
          && field.getDeclaringClass()
              == dev.mikoto2000.rei.llm.capture.LlmCaptureCommand.Capture.class)
        choices =
            List.of("next", "status", "off", "list", "show", "raw", "export", "delete", "clear");
      parameters.add(
          new CliCatalog.Parameter(
              positional.index(),
              label,
              positional.arity(),
              String.join("\n", positional.description()),
              choices,
              candidateType(positional.completionCandidates(), type),
              type.isArray() || Collection.class.isAssignableFrom(type)));
    }
  }

  private static String memberName(AnnotatedElement element) {
    return element instanceof Field field
        ? field.getName()
        : element instanceof java.lang.reflect.Parameter parameter ? parameter.getName() : "value";
  }

  private static String candidateType(Class<?> candidates, Class<?> type) {
    if (candidates.getSimpleName().contains("NoCompletionCandidates"))
      return type == java.nio.file.Path.class || type == java.io.File.class ? type.getName() : "";
    return candidates.getName();
  }

  private static List<String> declaredChoices(Class<?> type) {
    if (type == dev.mikoto2000.rei.activity.ActivityCommand.ActionCandidates.class)
      return List.of("today", "yesterday", "pause", "resume");
    if (type == dev.mikoto2000.rei.activity.ActivityCommand.SummaryCommand.DateCandidates.class)
      return List.of("today", "yesterday");
    if (type
        == dev.mikoto2000.rei.activity.ActivityCommand.CoachingCommand.CoachingCandidates.class)
      return List.of("status", "configure", "on", "off", "weekly", "monthly");
    if (type
        == dev.mikoto2000.rei.activity.ActivityCommand.BehaviorCommand.BehaviorCandidates.class)
      return List.of("on", "off", "status", "evaluate", "uncertain", "suggest-rules");
    if (type
        == dev.mikoto2000.rei.activity.ActivityCommand.ClassificationCommand
            .ClassificationCandidates.class)
      return List.of("status", "reload", "unknowns", "rules", "suggest-rules");
    return List.of();
  }

  private static List<String> choices(Class<?> type, Type generic) {
    if (type.isEnum()) return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    if (generic instanceof ParameterizedType parameterized)
      for (var argument : parameterized.getActualTypeArguments())
        if (argument instanceof Class<?> item && item.isEnum())
          return Arrays.stream(item.getEnumConstants()).map(Object::toString).toList();
    return List.of();
  }

  private static String defaultValue(String value) {
    return value.equals("__no_default_value__") ? null : value;
  }

  private static void help(Command command, List<CliCatalog.Option> options) {
    if (command.mixinStandardHelpOptions()) {
      options.add(
          new CliCatalog.Option(
              List.of("-h", "--help"),
              "Show this help message and exit.",
              null,
              "0",
              false,
              List.of()));
      options.add(
          new CliCatalog.Option(
              List.of("-V", "--version"),
              "Print version information and exit.",
              null,
              "0",
              false,
              List.of()));
    }
  }
}
