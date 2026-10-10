package dev.mikoto2000.rei.doctor;

/** A diagnostic returns one scoped observation; callers isolate failures between checks. */
@FunctionalInterface
public interface DoctorDiagnostic {
  DoctorResult inspect() throws Exception;
}
