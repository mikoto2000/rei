package dev.mikoto2000.rei.secretary;

import java.util.List;

/** Registered identities only; resolving the catalog must not change the selected project. */
@FunctionalInterface
public interface SecretaryProjects {
  List<String> registeredProjectIds();
}
