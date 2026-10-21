package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 検索パスの照合規則の結果と状態の契約を検証するテスト。 */
class SearchGlobTest {
  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @CsvSource({
    "*.java, A.java, true",
    "*.java, foo/deep/A.java, true",
    "*.java, foo/A.xml, false",
    "*.java, foo/A.JAVA, false",
    "**/*.java, A.java, true",
    "**/*.java, foo/deep/A.java, true",
    "foo/*.java, foo/A.java, true",
    "foo/*.java, foo/deep/A.java, false",
    "foo/*.java, other/foo/A.java, false",
    "foo/**/*.java, foo/A.java, true",
    "foo/**/*.java, foo/deep/A.java, true",
    "**/foo/**/A.java, foo/A.java, true",
    "**/foo/**/A.java, x/foo/y/z/A.java, true",
    "'**/*.{java,xml}', A.java, true",
    "'**/*.{java,xml}', foo/pom.xml, true",
    "'**/*.{java,xml}', foo/A.txt, false",
    "'**/*.java,**/*.xml', foo/A.java, false",
    "foo\\**\\*.java, foo/A.java, true",
    "foo/**/*.java, foo\\deep\\A.java, true",
    "A?.java, foo/A1.java, true",
    "A?.java, foo/A12.java, false",
    "[AB]*.java, foo/A.java, true",
    "[a-z]*.java, foo/apple.java, true",
    "[!AB]*.java, foo/C.java, true",
    "[!AB]*.java, foo/A.java, false",
    "foo/[!x]/*.java, foo/a/A.java, true",
    "foo/[!x]/*.java, foo/a/b/A.java, false"
  })
  void matchesConsistently(String glob, String path, boolean expected) {
    assertEquals(expected, SearchGlob.compile(glob).matches(path));
  }

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"[abc", "{java,xml", "a}", "{{a,b},c}", "[]"})
  void rejectsMalformedGlobs(String glob) {
    assertThrows(IllegalArgumentException.class, () -> SearchGlob.compile(glob));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankGlobDoesNotFilter(String glob) {
    assertNull(SearchGlob.compile(glob));
    assertNull(SearchGlob.compile(null));
  }
}
