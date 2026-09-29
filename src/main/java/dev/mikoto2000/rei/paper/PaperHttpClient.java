package dev.mikoto2000.rei.paper;

import java.net.URI;

public interface PaperHttpClient {
  byte[] get(URI uri, String contentType, int maxBytes, PaperOperation op);
}
