package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "dev.mikoto2000.rei.web")
public class ApiExceptionHandler {
  public record Error(String message) {}
  @ExceptionHandler(RunNotFoundException.class)
  public ResponseEntity<Error> notFound() {
    return ResponseEntity.status(404).body(new Error("Run not found"));
  }
}
