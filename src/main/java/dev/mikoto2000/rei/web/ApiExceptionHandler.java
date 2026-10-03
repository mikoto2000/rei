package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "dev.mikoto2000.rei.web")
public class ApiExceptionHandler {
  @ExceptionHandler(java.util.ConcurrentModificationException.class)
  public ResponseEntity<Error> workContextConflict() {return ResponseEntity.status(409).body(new Error("Work Context revision changed"));}
  @ExceptionHandler(dev.mikoto2000.rei.application.state.OperationConflictException.class)
  public ResponseEntity<Error> operationConflict() {
    return ResponseEntity.status(409).body(new Error("Resource conflict"));
  }
  @ExceptionHandler(java.time.format.DateTimeParseException.class)
  public ResponseEntity<Error> invalidDate() { return invalidRequest(); }
  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.beans.TypeMismatchException.class})
  public ResponseEntity<Error> malformedRequest() {
    return ResponseEntity.badRequest().body(new Error("Invalid request"));
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Error> unexpected(Exception exception) {
    if (exception instanceof org.springframework.web.ErrorResponse response)
      return ResponseEntity.status(response.getStatusCode()).body(new Error("Request rejected"));
    return ResponseEntity.status(500).body(new Error("Internal server error"));
  }
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
