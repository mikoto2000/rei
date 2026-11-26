package dev.mikoto2000.rei.runadmission;

/** Captures configuration before publishing a new run and releases only its owned reference. */
public interface RunAdmission<C> {
  void accept(C owner, String prompt);

  void release(C owner);
}
