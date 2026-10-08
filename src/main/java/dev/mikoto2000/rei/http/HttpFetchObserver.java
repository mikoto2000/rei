package dev.mikoto2000.rei.http;

public interface HttpFetchObserver {
  HttpFetchObserver NONE = new HttpFetchObserver() {};
  default void request(boolean redirect) {}
  default void status(int status) {}
  default void bytes(int count) {}
  default void failure(HttpFetchException.Code code) {}
  default void cancellation() {}
}
