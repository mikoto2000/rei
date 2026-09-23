package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityTuningMetricsTest {
  @Test void metricsDistinguishUnknownPartialAndOutputLimitWithoutLeakingEvidence() throws Exception {
    var p=new ActivityProperties();p.setEnabled(true);var observer=mock(DesktopActivityObserver.class);var extractor=mock(ActivityExtractor.class);
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ActivityEvidencePipeline.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","X"));
      var time=new ActivityChangeScopeTest.Time();var capture=new ActivityCapture(p,observer,extractor,mock(ActivityStore.class),mock(ScreenshotStore.class),time);
      capture.tick();time.advance(60);
      when(observer.foreground()).thenReturn(ActivityEvidenceClassifierTest.window("Firefox","PRIVATE_CONTEXT - ChatGPT"));when(observer.capture()).thenReturn(ActivityChangeScopeTest.screen(10,20));
      when(extractor.extract(any(),any())).thenThrow(new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/PRIVATE_PAYLOAD","output_limit"))));
      capture.tick();
      var logs=appender.list.stream().map(e->e.getFormattedMessage()).toList();
      assertTrue(logs.stream().anyMatch(s->s.contains("observations=2 unknown=1 partial=2 vision_output_limit=1 vision_validation=0 evidence_only_rate=0.5")));
      assertTrue(logs.stream().anyMatch(s->s.contains("reason=OUTPUT_LIMIT")));assertTrue(logs.stream().noneMatch(s->s.contains("PRIVATE_")));
    }finally{logger.detachAppender(appender);appender.stop();}
  }
}
