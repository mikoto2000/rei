package dev.mikoto2000.rei.launcher;

/** Transitional Backend modes, parsed without Spring, persistence or scheduling. */
public enum BackendMode {
  SERVER, LEGACY_SHELL;
  public static BackendMode parse(String[] args) {
    String mode = null;
    for (int i=0;i<args.length;i++) {
      String value;
      if (args[i].equals("--mode")) {
        if (++i == args.length) throw new IllegalArgumentException("--mode requires a value");
        value = args[i];
      } else if (args[i].startsWith("--mode=")) value = args[i].substring(7);
      else continue;
      if (mode != null) throw new IllegalArgumentException("Specify --mode only once");
      mode = value;
    }
    if (mode == null || mode.equals("legacy-shell")) return LEGACY_SHELL;
    if (mode.equals("server")) return SERVER;
    throw new IllegalArgumentException("Available modes: server, legacy-shell; lightweight CLI migration is not yet complete");
  }
}
