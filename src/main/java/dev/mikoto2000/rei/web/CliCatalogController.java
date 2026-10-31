package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.cli.CliCatalog;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 軽量CLI利用可能な項目をWeb APIの操作として受け付ける。
 *
 * <p>Read-only metadata from the fixed Root declaration. No command object is instantiated or
 * invoked.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class CliCatalogController {
  /** 状態の記録。 */
  private final CliCatalog snapshot =
      new CliCatalog(
          Arrays.stream(RootCommand.class.getAnnotation(Command.class).subcommands())
              .map(CliCatalogController::describe)
              .toList());

  /** Web APIによる操作で用いる利用可能な項目を返す。 */
  @GetMapping("/api/v1/cli/catalog")
  public CliCatalog catalog() {
    return snapshot;
  }

  /** Web APIによる操作で用いる説明を返す。 */
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

  /** Web APIによる操作に関する項目一覧を処理する。 */
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

  /** Web APIによる操作に関する参加者を処理する。 */
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

  /** Web APIによる操作で用いる参加者名前を返す。 */
  private static String memberName(AnnotatedElement element) {
    return element instanceof Field field
        ? field.getName()
        : element instanceof java.lang.reflect.Parameter parameter ? parameter.getName() : "value";
  }

  /** Web APIによる操作で用いる候補種別を返す。 */
  private static String candidateType(Class<?> candidates, Class<?> type) {
    if (candidates.getSimpleName().contains("NoCompletionCandidates"))
      return type == java.nio.file.Path.class || type == java.io.File.class ? type.getName() : "";
    return candidates.getName();
  }

  /** Web APIによる操作で用いるdeclared選択肢を返す。 */
  private static List<String> declaredChoices(Class<?> type) {
    // 固定操作名の metadata だけを生成する。コマンドや動的 Provider は生成しない。
    if (CliCatalog.FixedChoices.class.isAssignableFrom(type)) {
      try {
        var candidates = (CliCatalog.FixedChoices) type.getDeclaredConstructor().newInstance();
        var values = new ArrayList<String>();
        candidates.forEach(values::add);
        return List.copyOf(values);
      } catch (ReflectiveOperationException invalid) {
        throw new IllegalStateException(
            "Cannot read fixed completion choices: " + type.getName(), invalid);
      }
    }
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

  /** Web APIによる操作で用いる選択肢を返す。 */
  private static List<String> choices(Class<?> type, Type generic) {
    if (type.isEnum()) return Arrays.stream(type.getEnumConstants()).map(Object::toString).toList();
    if (generic instanceof ParameterizedType parameterized)
      for (var argument : parameterized.getActualTypeArguments())
        if (argument instanceof Class<?> item && item.isEnum())
          return Arrays.stream(item.getEnumConstants()).map(Object::toString).toList();
    return List.of();
  }

  /** Web APIによる操作で用いる既定値を返す。 */
  private static String defaultValue(String value) {
    return value.equals("__no_default_value__") ? null : value;
  }

  /** Web APIによる操作に関する使い方を処理する。 */
  private static void help(Command command, List<CliCatalog.Option> options) {
    if (!command.mixinStandardHelpOptions()) {
      options.add(
          new CliCatalog.Option(
              List.of("--help"), "このコマンドの役割と使い方を表示します。", null, "0", false, List.of()));
    }
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
