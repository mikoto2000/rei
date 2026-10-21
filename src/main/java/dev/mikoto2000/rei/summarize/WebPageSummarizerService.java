package dev.mikoto2000.rei.summarize;

import java.net.URI;

/** WebページSummarizerのユースケースを実現する。 */
public interface WebPageSummarizerService {

  /** 対象の内容を、要点が分かる形にまとめる。 */
  SummaryResult summarize(URI uri);
}
