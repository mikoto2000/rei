package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.toolcontract.ArgumentIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Validate file/query batch sizes before approval, binding or tool execution. */
public final class FileBatchArguments {
  public static final int MAX_READ_FILES = 30;
  public static final int MAX_QUERIES = 20;
  public static final int MAX_PATHS = 64;
  public static final int MAX_PREFERRED_PATHS = 20;

  private FileBatchArguments() {}

  public static boolean supports(String tool) {
    return Set.of(
            "readMultiFile",
            "readSkillResource",
            "countFileLines",
            "grepMultiQuery",
            "searchAndRead",
            "beginReview")
        .contains(tool);
  }

  public static void validate(String tool, String input) {
    if (!supports(tool)) return;
    var root = ToolSchemaArguments.input(tool, input);
    var issues = new ArrayList<ArgumentIssue>();
    switch (tool) {
      case "readMultiFile", "readSkillResource" ->
          batch(root.path("files"), "files", 1, MAX_READ_FILES, issues);
      case "countFileLines", "beginReview" ->
          batch(root.path("paths"), "paths", 1, MAX_PATHS, issues);
      case "grepMultiQuery" -> batch(root.path("queries"), "queries", 1, MAX_QUERIES, issues);
      case "searchAndRead" -> {
        var request = root.path("request");
        batch(request.path("queries"), "request.queries", 1, MAX_QUERIES, issues);
        var preferred = request.path("preferredPaths");
        if (!preferred.isMissingNode() && !preferred.isNull())
          batch(preferred, "request.preferredPaths", 0, MAX_PREFERRED_PATHS, issues);
      }
      default -> {}
    }
    if (!issues.isEmpty())
      throw new InvalidToolArgumentsException(
          tool
              + ": "
              + ArgumentIssue.describe(issues, "; ")
              + ". Split larger batches into separate calls within these limits. "
              + "The tool has not executed; preserve successful sibling results.",
          issues);
  }

  private static void batch(
      JsonNode value, String path, int minimum, int maximum, List<ArgumentIssue> issues) {
    if (!value.isArray() || value.size() < minimum || value.size() > maximum)
      issues.add(
          new ArgumentIssue(
              path, "must be a JSON array with " + minimum + " to " + maximum + " entries"));
  }
}
