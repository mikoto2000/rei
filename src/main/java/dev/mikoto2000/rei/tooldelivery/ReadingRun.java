package dev.mikoto2000.rei.tooldelivery;

import java.nio.file.Path;

/** Captured read authority; this contract does not depend on the execution engine. */
public interface ReadingRun {
  String runId();

  String conversationId();

  Path projectRoot();

  String projectId();

  String sourceName();

  String modeName();
}
