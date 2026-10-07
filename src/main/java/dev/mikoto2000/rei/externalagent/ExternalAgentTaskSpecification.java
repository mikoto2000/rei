package dev.mikoto2000.rei.externalagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned task resources, independent of command grammar and provider startup. */
record ExternalAgentTaskSpecification(String promptResource, String schemaResource) {
  static ExternalAgentTaskSpecification forAction(ExternalAgentRequest.Action action) {
    return switch (action) {
      case REVIEW -> new ExternalAgentTaskSpecification("/external-agent/review.txt", "/external-agent/schema.json");
      case MATERIAL_REVIEW -> new ExternalAgentTaskSpecification("/external-agent/material-review.md", "/subagents/schemas/material-review.schema.json");
      case PROPOSE_FIX -> new ExternalAgentTaskSpecification("/external-agent/fix-proposal.txt", "/external-agent/fix-proposal-schema.json");
      case IMPLEMENT -> new ExternalAgentTaskSpecification("/external-agent/implementation.txt", "/external-agent/implementation-schema.json");
    };
  }
  String prompt() throws IOException { return read(promptResource); }
  String schema() throws IOException { return read(schemaResource); }
  private String read(String resource) throws IOException {
    try (var stream = ExternalAgentTaskSpecification.class.getResourceAsStream(resource)) {
      if (stream == null) throw new IOException("Missing external task resource");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
