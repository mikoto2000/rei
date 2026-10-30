package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TextEditArgumentsTest {
  private static final String HASH = "a".repeat(64);

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of("proposeTextChangeSet", "{}"),
        Arguments.of("proposeTextChangeSet", "{'request':null}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'','expectedText':'old','replacement':'new'}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'  ','expectedText':'old','replacement':'new'}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':2,'expectedText':'old','replacement':'new'}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'a\\nb','expectedText':'old','replacement':'new'}}"),
        Arguments.of("proposeTextChangeSet", "{'request':{'path':'note.md','expectedText':'old'}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','expectedText':'same','replacement':'same'}}"),
        Arguments.of("proposeTextChangeSet", "{'request':{'path':'note.md','edits':[]}}"),
        Arguments.of("proposeTextChangeSet", "{'request':{'path':'note.md','edits':[null]}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','baseVersion':'bad','edits':[{'oldText':'x','newText':'y'}]}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','baseVersion':'"
                + HASH
                + "','edits':[{'oldText':'','newText':'x'}]}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','baseVersion':'"
                + HASH
                + "','edits':[{'oldText':'x','newText':null}]}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','expectedText':'x','replacement':'y','baseVersion':'"
                + HASH
                + "','edits':[{'oldText':'x','newText':'y'}]}}"),
        Arguments.of("proposeTextDocumentChangeSet", "{'request':{'operations':[]}}"),
        Arguments.of("proposeTextDocumentChangeSet", "{'request':{'operations':[null]}}"),
        Arguments.of(
            "proposeTextDocumentChangeSet",
            "{'request':{'operations':[{'kind':'update','path':'note.md'}]}}"),
        Arguments.of(
            "proposeTextDocumentChangeSet",
            "{'request':{'operations':[{'kind':'CREATE','replacement':'new'}]}}"),
        Arguments.of(
            "proposeTextDocumentChangeSet",
            "{'request':{'operations':[{'kind':'RENAME','path':'note.md','expectedText':'x'}]}}"),
        Arguments.of(
            "proposeTextDocumentChangeSet",
            "{'request':{'operations':[{'kind':'DELETE','path':'note.md','expectedText':'x','replacement':'y'}]}}"),
        Arguments.of("createDirectories", "{}"),
        Arguments.of("deleteFile", "{'pathStr':' '}"),
        Arguments.of("copyFile", "{'sourcePath':'a','overwrite':true}"),
        Arguments.of("moveFile", "{'sourcePath':'a','destPath':'b','overwrite':'yes'}"),
        Arguments.of("writeBinaryFile", "{'pathStr':'a','contents':'???','append':false}"),
        Arguments.of("writeBinaryFile", "{'pathStr':'a','contents':[256],'append':false}"),
        Arguments.of("readTextChangeSetBase", "{'path':null}"),
        Arguments.of("inspectTextChangeSet", "{'id':' '}"),
        Arguments.of("applyTextChangeSet", "{'id':'known'}"),
        Arguments.of("discardTextDocumentChangeSet", "{'id':'known','proposalSha256':3}"),
        Arguments.of(
            "getTextChangeSetDiff", "{'id':'known','proposalSha256':'" + HASH + "','offset':'3'}"),
        Arguments.of(
            "getTextDocumentChangeSetDiff",
            "{'id':'known','proposalSha256':'" + HASH + "','maxBytes':0}"),
        Arguments.of("writeMultiFile", "{'files':[]}"),
        Arguments.of("writeMultiFile", "{'files':[{'path':'a','content':'x'},null]}"),
        Arguments.of("writeMultiFile", "{'files':[{'path':'a','content':null}]}"),
        Arguments.of("writeMultiFile", "{'files':[{'path':'a','content':'x','append':'true'}]}"),
        Arguments.of(
            "writeMultiFile", "{'files':[{'path':'a','content':'x','charset':'not-a-charset'}]}"),
        Arguments.of(
            "writeMultiFile", "{'files':[{'path':'a','content':'x'},{'path':'a','content':'y'}]}"),
        Arguments.of("applyTextDiff", "{'pathStr':'note.md','oldText':'','newText':'x'}"),
        Arguments.of("applyTextDiff", "{'pathStr':'note.md','oldText':'x','newText':null}"),
        Arguments.of("proposeTextChangeSet", "{'request':{},'request':{}}"),
        Arguments.of("proposeTextChangeSet", "{} {}"));
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void rejectsBeforeToolExecutionWithoutEchoingText(String tool, String input) {
    org.assertj.core.api.Assertions.assertThat(TextEditArguments.supports(tool)).isTrue();
    assertThatThrownBy(() -> TextEditArguments.validate(tool, input.replace('\'', '"')))
        .isInstanceOf(InvalidToolArgumentsException.class)
        .hasMessageContaining(tool);
  }

  static Stream<Arguments> valid() {
    return Stream.of(
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'日本語.md','expectedText':'x','replacement':''}}"),
        Arguments.of(
            "proposeTextChangeSet",
            "{'request':{'path':'note.md','baseVersion':'sha256:"
                + HASH
                + "','edits':[{'oldText':' ','newText':''}]}}"),
        Arguments.of(
            "proposeTextDocumentChangeSet",
            "{'request':{'operations':[{'kind':'CREATE','path':'new.md','replacement':''},{'kind':'DELETE','path':'old.md','expectedText':''},{'kind':'RENAME','path':'a.md','target':'b.md','expectedText':'a'},{'kind':'UPDATE','path':'c.md','expectedText':'','replacement':'x'}]}}"),
        Arguments.of("createDirectories", "{'pathStr':'a/b'}"),
        Arguments.of("copyFile", "{'sourcePath':'a','destPath':'b','overwrite':false}"),
        Arguments.of("writeBinaryFile", "{'pathStr':'a','contents':'AAE=','append':false}"),
        Arguments.of("writeBinaryFile", "{'pathStr':'a','contents':[-128,0,255],'append':true}"),
        Arguments.of("readTextChangeSetBase", "{'path':'note.md'}"),
        Arguments.of("inspectTextDocumentChangeSet", "{'id':'known'}"),
        Arguments.of(
            "applyTextDocumentChangeSet", "{'id':'known','proposalSha256':'" + HASH + "'}"),
        Arguments.of(
            "getTextDocumentChangeSetDiff",
            "{'id':'known','proposalSha256':'" + HASH + "','offset':null,'maxBytes':null}"),
        Arguments.of("writeMultiFile", "{'files':[{'path':'a','content':''}]}"),
        Arguments.of("applyTextDiff", "{'pathStr':'note.md','oldText':'x','newText':''}"));
  }

  @ParameterizedTest
  @MethodSource("valid")
  void preservesEmptyReplacementsOptionalDefaultsAndEditModes(String tool, String input) {
    assertThatCode(() -> TextEditArguments.validate(tool, input.replace('\'', '"')))
        .doesNotThrowAnyException();
  }
}
