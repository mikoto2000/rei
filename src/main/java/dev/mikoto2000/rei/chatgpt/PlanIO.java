package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.*;

/** Covers body reads too: HttpRequest.timeout alone does not bound an InputStream body. */
final class PlanIO {
  static <T> T read(
      InputStream input, Duration timeout, BooleanSupplier cancelled, Supplier<T> parse) {
    var task = new FutureTask<T>(parse::get);
    Thread.ofVirtual().name("chatgpt-plan-body").start(task);
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (true) {
        if (cancelled.getAsBoolean()) throw ChatGptException.of(CANCELLED);
        if (System.nanoTime() >= deadline) throw ChatGptException.of(TIMEOUT);
        try {
          return task.get(25, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
        }
      }
    } catch (ChatGptException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ChatGptException.of(CANCELLED);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ChatGptException safe) throw safe;
      throw ChatGptException.of(PROTOCOL);
    } finally {
      task.cancel(true);
      try {
        input.close();
      } catch (IOException ignored) {
      }
    }
  }

  static byte[] bytes(InputStream input, int limit) {
    try {
      var bytes = input.readNBytes(limit + 1);
      if (bytes.length > limit) throw ChatGptException.of(RECEIVE_LIMIT);
      return bytes;
    } catch (ChatGptException e) {
      throw e;
    } catch (Exception e) {
      throw ChatGptException.of(TRANSPORT);
    }
  }
}
