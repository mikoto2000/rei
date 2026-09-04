package dev.mikoto2000.rei.summarize;

import java.net.URI;

public interface WebPageSummarizerService {

  SummaryResult summarize(URI uri);
}
