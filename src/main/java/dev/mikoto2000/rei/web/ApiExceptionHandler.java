package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "dev.mikoto2000.rei.web")
public class ApiExceptionHandler {
  @ExceptionHandler(dev.mikoto2000.rei.event.ReplayGapException.class)
  public ResponseEntity<Error> replayGap() {
    return ResponseEntity.status(409).body(new Error("Required run event history is no longer retained"));
  }
  @ExceptionHandler(dev.mikoto2000.rei.application.run.ResourceNotFoundException.class)
  public ResponseEntity<Error> resourceNotFound() {
    return ResponseEntity.status(404).body(new Error("Resource not found"));
  }
  @ExceptionHandler(dev.mikoto2000.rei.application.run.SessionConflictException.class)
  public ResponseEntity<Error> sessionConflict() {
    return ResponseEntity.status(409).body(new Error("Session belongs to another project"));
  }
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Error> invalidRequest() {
    return ResponseEntity.badRequest().body(new Error("Invalid request"));
  }
  public record Error(String message) {}
  @ExceptionHandler(RunNotFoundException.class)
  public ResponseEntity<Error> notFound() {
    return ResponseEntity.status(404).body(new Error("Run not found"));
  }
}
