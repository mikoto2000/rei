package dev.mikoto2000.rei.core.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Validate edit input shapes only; ownership, source versions and transaction outcomes stay native.
 */
public final class TextEditArguments {
  private static final Set<String> FILE_OPERATIONS =
      Set.of("createDirectories", "writeBinaryFile", "deleteFile", "copyFile", "moveFile");
  private static final Set<String> RECEIPTS =
      Set.of(
          "inspectTextChangeSet",
          "inspectTextDocumentChangeSet",
          "getTextChangeSetDiff",
          "getTextDocumentChangeSetDiff",
          "discardTextChangeSet",
          "discardTextDocumentChangeSet",
          "applyTextChangeSet",
          "applyTextDocumentChangeSet",
          "reconcileTextChangeSet",
          "rollbackTextDocumentChangeSet",
          "cleanTextDocumentStaging");

  private TextEditArguments() {}

  public static boolean supports(String tool) {
    return RECEIPTS.contains(tool)
        || FILE_OPERATIONS.contains(tool)
        || Set.of(
                "proposeTextChangeSet",
                "proposeTextDocumentChangeSet",
                "readTextChangeSetBase",
                "writeMultiFile",
                "applyTextDiff")
            .contains(tool);
  }

  public static void validate(String tool, String input) {
    if (!supports(tool)) return;
    var root = ToolSchemaArguments.input(tool, input);
    var errors = new ArrayList<String>();
    if (tool.equals("proposeTextChangeSet")) {
      var request = root.path("request");
      path(request, "path", "request.path", 1024, errors);
      edit(request, "request", true, errors);
    } else if (tool.equals("proposeTextDocumentChangeSet")) {
      var operations = root.path("request").path("operations");
      if (array(operations, "request.operations", 32, errors)) {
        int index = 0;
        for (var op : operations) {
          String at = "request.operations[" + index++ + "]";
          path(op, "path", at + ".path", 1024, errors);
          String kind = op.path("kind").asText("");
          if (!Set.of("UPDATE", "CREATE", "DELETE", "RENAME").contains(kind))
            errors.add(at + ".kind must be UPDATE/CREATE/DELETE/RENAME");
          else if (kind.equals("UPDATE")) {
            edit(op, at, false, errors);
            absent(op, "target", at, errors);
          } else {
            absent(op, "edits", at, errors);
            absent(op, "baseVersion", at, errors);
            if (kind.equals("CREATE")) {
              absent(op, "expectedText", at, errors);
              absent(op, "target", at, errors);
              text(op, "replacement", at, false, errors);
            } else {
              text(op, "expectedText", at, false, errors);
              if (kind.equals("RENAME")) {
                path(op, "target", at + ".target", 1024, errors);
                if (present(op, "replacement")) text(op, "replacement", at, false, errors);
              } else {
                absent(op, "replacement", at, errors);
                absent(op, "target", at, errors);
              }
            }
          }
        }
      }
    } else if (tool.equals("readTextChangeSetBase")) path(root, "path", "path", 1024, errors);
    else if (tool.equals("writeMultiFile")) {
      if (array(root.path("files"), "files", 20, errors)) {
        int i = 0;
        long total = 0;
        var seen = new java.util.HashSet<String>();
        for (var file : root.path("files")) {
          String at = "files[" + i++ + "]";
          path(file, "path", at + ".path", 4096, errors);
          if (file.path("path").isString() && !seen.add(file.path("path").asString()))
            errors.add(at + ".path duplicates an earlier entry");
          text(file, "content", at, false, errors);
          if (file.path("content").isString()) {
            int length = file.path("content").asString().length();
            total += length;
            if (length > 100_000) errors.add(at + ".content exceeds 100000 characters");
          }
          if (present(file, "append") && !file.path("append").isBoolean())
            errors.add(at + ".append must be boolean");
          charset(file, "charset", at, errors);
        }
        if (total > 500_000) errors.add("files content exceeds 500000 characters in total");
      }
    } else if (tool.equals("applyTextDiff")) {
      path(root, "pathStr", "pathStr", 4096, errors);
      text(root, "oldText", "arguments", true, errors);
      text(root, "newText", "arguments", false, errors);
      charset(root, "charset", "arguments", errors);
    } else if (FILE_OPERATIONS.contains(tool)) {
      if (tool.equals("copyFile") || tool.equals("moveFile")) {
        path(root, "sourcePath", "sourcePath", 4096, errors);
        path(root, "destPath", "destPath", 4096, errors);
        if (!root.path("overwrite").isBoolean()) errors.add("overwrite must be boolean");
      } else {
        path(root, "pathStr", "pathStr", 4096, errors);
        if (tool.equals("writeBinaryFile")) {
          if (!root.path("append").isBoolean()) errors.add("append must be boolean");
          var contents = root.path("contents");
          if (contents.isString()) {
            try {
              java.util.Base64.getDecoder().decode(contents.asString());
            } catch (IllegalArgumentException invalid) {
              errors.add("contents must encode valid base64 bytes");
            }
          } else if (contents.isArray()) {
            for (var value : contents)
              if (!value.isIntegralNumber()
                  || !value.canConvertToInt()
                  || value.intValue() < -128
                  || value.intValue() > 255) {
                errors.add("contents must contain byte integers");
                break;
              }
          } else errors.add("contents must be base64 text or a byte array");
        }
      }
    } else {
      path(root, "id", "id", 4096, errors);
      if (!tool.startsWith("inspect"))
        hash(root, "proposalSha256", "proposalSha256", false, errors);
      if (tool.startsWith("get")) {
        integer(root, "offset", 0, Integer.MAX_VALUE, errors);
        integer(root, "maxBytes", 256, 65536, errors);
      }
    }
    if (!errors.isEmpty())
      throw new InvalidToolArgumentsException(
          tool
              + ": "
              + String.join("; ", errors.stream().limit(8).toList())
              + ". The edit tool has not executed or saved a proposal. Correct only this call. "
              + "For proposals put path inside request (or each request.operations entry), and use either "
              + "expectedText/replacement or baseVersion/edits. Use saved IDs/hashes for receipt operations; "
              + "never invent them, repeat completed siblings, or replay an unknown Apply outcome.");
  }

  private static void edit(JsonNode node, String at, boolean single, List<String> errors) {
    if (present(node, "edits")) {
      absent(node, "expectedText", at, errors);
      absent(node, "replacement", at, errors);
      hash(node, "baseVersion", at + ".baseVersion", true, errors);
      if (array(node.path("edits"), at + ".edits", 64, errors)) {
        int i = 0;
        for (var edit : node.path("edits")) {
          String where = at + ".edits[" + i++ + "]";
          text(edit, "oldText", where, true, errors);
          text(edit, "newText", where, false, errors);
        }
      }
    } else {
      absent(node, "baseVersion", at, errors);
      text(node, "expectedText", at, single, errors);
      text(node, "replacement", at, false, errors);
      if (node.path("expectedText").isString()
          && node.path("expectedText").equals(node.path("replacement")))
        errors.add(at + " must describe an actual change");
    }
  }

  private static boolean present(JsonNode node, String field) {
    return node.has(field) && !node.path(field).isNull();
  }

  private static void charset(JsonNode node, String field, String at, List<String> errors) {
    if (!present(node, field)) return;
    if (!node.path(field).isString()) errors.add(at + "." + field + " must be string");
    else if (!node.path(field).asString().isBlank()) {
      try {
        java.nio.charset.Charset.forName(node.path(field).asString());
      } catch (IllegalArgumentException invalid) {
        errors.add(at + "." + field + " must name a supported charset, such as UTF-8");
      }
    }
  }

  private static void absent(JsonNode node, String field, String at, List<String> errors) {
    if (present(node, field))
      errors.add(at + "." + field + " is incompatible with the selected edit mode");
  }

  private static void path(JsonNode node, String field, String at, int limit, List<String> errors) {
    var value = node.path(field);
    if (!value.isString()
        || value.asString().isBlank()
        || value.asString().length() > limit
        || value.asString().codePoints().anyMatch(Character::isISOControl))
      errors.add(
          at
              + " must be a nonblank path/identifier without control characters (max "
              + limit
              + ")");
  }

  private static void text(
      JsonNode node, String field, String at, boolean nonempty, List<String> errors) {
    var value = node.path(field);
    if (!value.isString() || nonempty && value.asString().isEmpty())
      errors.add(
          at
              + "."
              + field
              + " must be "
              + (nonempty ? "nonempty " : "")
              + "string (empty replacement is allowed)");
  }

  private static boolean array(JsonNode value, String at, int maximum, List<String> errors) {
    boolean valid = value.isArray() && !value.isEmpty() && value.size() <= maximum;
    if (!valid) errors.add(at + " must be an array with 1.." + maximum + " entries");
    return valid;
  }

  private static void hash(
      JsonNode node, String field, String at, boolean prefix, List<String> errors) {
    var value = node.path(field);
    if (!value.isString()
        || !value.asString().matches((prefix ? "(sha256:)?" : "") + "[a-f0-9]{64}"))
      errors.add(at + " must be the saved SHA-256" + (prefix ? " (optional sha256: prefix)" : ""));
  }

  private static void integer(
      JsonNode node, String field, int minimum, int maximum, List<String> errors) {
    var value = node.path(field);
    if (present(node, field)
        && (!value.isIntegralNumber()
            || !value.canConvertToInt()
            || value.intValue() < minimum
            || value.intValue() > maximum))
      errors.add(field + " must be an integer in " + minimum + ".." + maximum);
  }
}
