package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Selection owned by one client/session. Retain this object across that client's requests. */
public final class ProjectClient {
  final ProjectService service;
  final AtomicReference<Path> selection;

  ProjectClient(ProjectService service, Path startup) {
    this.service = service;
    this.selection = new AtomicReference<>(startup);
  }

  public ProjectClientScope open() { return ProjectClientScope.open(this); }
}
