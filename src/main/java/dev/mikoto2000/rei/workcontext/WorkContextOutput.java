package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.subagent.SubAgentResultParser;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;
import dev.mikoto2000.rei.subagent.SubAgentValidationException;
import dev.mikoto2000.rei.workcontext.WorkContext.Kind;
import dev.mikoto2000.rei.workcontext.WorkContext.Origin;
import dev.mikoto2000.rei.workcontext.WorkContext.Status;
import java.util.ArrayList;
import java.util.List;

public final class WorkContextOutput {
  private static final SubAgentResultSchema SCHEMA =
      SubAgentResultSchema.load(
          java.nio.file.Path.of("."), "classpath:/subagents/schemas/work-context.schema.json");

  public String schema() {
    return SCHEMA.json();
  }

  public List<WorkContextCandidate> parse(String text) {
    if (text == null || text.isBlank())
      throw new WorkContextExtractionException(WorkContextExtractionException.Reason.EMPTY_OUTPUT);
    tools.jackson.databind.JsonNode root;
    try {
      root = new SubAgentResultParser().parse(text);
    } catch (SubAgentValidationException error) {
      throw new WorkContextExtractionException(
          WorkContextExtractionException.Reason.INVALID_JSON, error);
    }
    if (!SCHEMA.validate(root).isEmpty())
      throw new WorkContextExtractionException(
          WorkContextExtractionException.Reason.INVALID_SCHEMA);
    var changes = new ArrayList<WorkContextCandidate>();
    for (var row : root.get("changes")) {
      var ids = new ArrayList<String>();
      for (var id : row.get("sourceIds")) ids.add(id.asString());
      changes.add(
          new WorkContextCandidate(
              row.get("action").asString(),
              row.get("targetId").isNull() ? null : row.get("targetId").asString(),
              Kind.valueOf(row.get("kind").asString()),
              row.get("text").asString(),
              row.get("reason").asString(),
              Status.valueOf(row.get("status").asString()),
              ids,
              Origin.valueOf(row.get("certainty").asString())));
    }
    return List.copyOf(changes);
  }
}
