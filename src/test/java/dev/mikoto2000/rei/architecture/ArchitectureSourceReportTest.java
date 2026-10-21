package dev.mikoto2000.rei.architecture;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Opt-in javac AST inventory, independent of architectural pass/fail budgets. */
class ArchitectureSourceReportTest {
  @Test
  void sourceInventory() throws Exception {
    if (!Boolean.getBoolean("architecture.report")) return;
    Path output = Path.of("target/architecture");
    Files.createDirectories(output);
    List<String> inventory = new ArrayList<>(List.of("path\tpackage\ttypes\tlines\tkind"));
    List<String> tools = new ArrayList<>(List.of("name\tsignature\tannotation\tline\tbody"));
    Map<String, org.springframework.ai.tool.annotation.Tool> toolAnnotations = new HashMap<>();
    for (var method : dev.mikoto2000.rei.core.Tools.class.getDeclaredMethods()) {
      var annotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
      if (annotation != null) toolAnnotations.put(method.getName(), annotation);
    }
    List<String> fields = new ArrayList<>(List.of("name\ttype\tline"));
    List<String> sizes = new ArrayList<>(List.of("path\ttype\tstartLine\tendLine\tlines"));
    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
      for (String root :
          List.of(
              "src/main/java",
              "src/test/java",
              "target/generated-sources",
              "target/generated-test-sources",
              "terminal")) {
        Path directory = Path.of(root);
        if (!Files.exists(directory)) continue;
        List<Path> files;
        try (var paths = Files.walk(directory)) {
          files =
              paths
                  .filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> !root.equals("terminal") || !p.toString().contains("target"))
                  .sorted()
                  .toList();
        }
        if (files.isEmpty()) continue;
        var task =
            (JavacTask)
                compiler.getTask(
                    null,
                    manager,
                    null,
                    List.of("-proc:none"),
                    null,
                    manager.getJavaFileObjectsFromPaths(files));
        var positions = com.sun.source.util.Trees.instance(task).getSourcePositions();
        for (var unit : task.parse()) {
          Path path = Path.of(unit.getSourceFile().toUri());
          String source = Files.readString(path);
          String relative =
              Path.of("").toAbsolutePath().relativize(path).toString().replace('\\', '/');
          int[] count = {0};
          new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree type, Void unused) {
              count[0]++;
              long first = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, type));
              long last = unit.getLineMap().getLineNumber(positions.getEndPosition(unit, type) - 1);
              var names = new ArrayDeque<String>();
              for (var cursor = getCurrentPath(); cursor != null; cursor = cursor.getParentPath()) {
                if (cursor.getLeaf() instanceof ClassTree enclosing) {
                  String name = enclosing.getSimpleName().toString();
                  names.addFirst(name.isEmpty() ? "anonymous@" + first : name);
                }
              }
              sizes.add(
                  relative
                      + "\t"
                      + unit.getPackageName()
                      + "."
                      + String.join("$", names)
                      + "\t"
                      + first
                      + "\t"
                      + last
                      + "\t"
                      + (last - first + 1));
              if (relative.endsWith("/core/Tools.java")
                  && type.getSimpleName().contentEquals("Tools")) {
                for (var member : type.getMembers()) {
                  long offset = positions.getStartPosition(unit, member);
                  long line = unit.getLineMap().getLineNumber(offset);
                  if (member instanceof VariableTree field)
                    fields.add(field.getName() + "\t" + field.getType() + "\t" + line);
                  if (member instanceof MethodTree method) {
                    for (var annotation : method.getModifiers().getAnnotations()) {
                      if (annotation.getAnnotationType().toString().equals("Tool")) {
                        var tool = toolAnnotations.get(method.getName().toString());
                        String name =
                            tool.name().isEmpty() ? method.getName().toString() : tool.name();
                        tools.add(
                            name
                                + "\t"
                                + clean(
                                    method.getReturnType()
                                        + " "
                                        + method.getName()
                                        + "("
                                        + method.getParameters()
                                        + ")")
                                + "\t"
                                + clean(tool.description())
                                + "\t"
                                + line
                                + "\t"
                                + clean(method.getBody().toString()));
                      }
                    }
                  }
                }
              }
              return super.visitClass(type, unused);
            }
          }.scan(unit, null);
          inventory.add(
              relative
                  + "\t"
                  + unit.getPackageName()
                  + "\t"
                  + count[0]
                  + "\t"
                  + source.lines().count()
                  + "\t"
                  + (root.contains("generated")
                      ? "generated"
                      : relative.contains("/test/") ? "test" : "production"));
        }
      }
    }
    Files.write(output.resolve("sources.tsv"), inventory, StandardCharsets.UTF_8);
    Files.write(output.resolve("tools.tsv"), tools, StandardCharsets.UTF_8);
    Files.write(output.resolve("tools-fields.tsv"), fields, StandardCharsets.UTF_8);
    Files.write(output.resolve("type-sizes.tsv"), sizes, StandardCharsets.UTF_8);
  }

  private static String clean(String value) {
    return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
  }
}
