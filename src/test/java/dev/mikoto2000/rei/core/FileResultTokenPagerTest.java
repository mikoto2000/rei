package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

class FileResultTokenPagerTest {
  @Test void longLineKeepsNativeReadCursorAndOriginalVersionWithoutSplittingSurrogates() throws Exception {
    String source="教材🙂\\\"".repeat(1000);
    var request=new Tools.ReadFileRequest("lesson.md",1,1,"sha",0,"UTF-8");
    var original=new Tools.ReadFileResult("lesson.md",1,1,List.of(source),false,null,"sha",null,source.length());
    var result=new FileResultTokenPager(TokenEstimator.conservative()).page(List.of(original),List.of(request),512).getFirst();
    assertThat(result.truncated()).isTrue();
    assertThat(result.nextRead().expectedVersion()).isEqualTo("sha");
    assertThat(result.nextRead().startLine()).isEqualTo(1);
    assertThat(result.content().getFirst()).isEqualTo(source.substring(0,result.nextRead().offset()));
    assertThat(Character.isLowSurrogate(source.charAt(result.nextRead().offset()))).isFalse();
    assertThat(TokenEstimator.conservative().text(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(result)))).isLessThanOrEqualTo(512);
  }
  @Test void continuationStartsAtActualRequestedOffsetAndPreservesCharset() throws Exception {
    var request=new Tools.ReadFileRequest("lesson.md",3,3,"sha",17,"Shift_JIS");
    var original=new Tools.ReadFileResult("lesson.md",3,3,List.of("本文".repeat(1000)),false,null,"sha",null,6000);
    var result=new FileResultTokenPager(TokenEstimator.conservative()).page(List.of(original),List.of(request),512).getFirst();
    assertThat(result.nextRead().offset()).isEqualTo(17+result.content().getFirst().length());
    assertThat(result.nextRead().charset()).isEqualTo("Shift_JIS");
  }
}
