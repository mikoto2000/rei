package dev.mikoto2000.rei.voice;
import java.nio.file.*;import java.nio.charset.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class WhisperByteVocabularyTest {
 @TempDir Path temp;
 @Test void preservesSplitUtf8AndTokenIdsAndDeletesOnlyOwnedFile() throws Exception {
  byte[] raw="短く挨拶😀".getBytes(StandardCharsets.UTF_8);var lines=new ArrayList<String>();
  for(int i=0;i<raw.length;i++)lines.add(Base64.getEncoder().encodeToString(new byte[]{raw[i]})+" "+i);
  lines.add("= "+raw.length);Path source=temp.resolve("tokens.txt");Files.write(source,lines);
  Path derived;try(var vocab=WhisperByteVocabulary.create(source)) {
   derived=vocab.path();var encoded=Files.readAllLines(derived);var joined=new StringBuilder();
   for(int i=0;i<raw.length;i++){String[] parts=encoded.get(i).split(" ");assertThat(parts[1]).isEqualTo(""+i);joined.append(new String(Base64.getDecoder().decode(parts[0]),StandardCharsets.UTF_8));}
   assertThat(WhisperByteVocabulary.decode(joined.toString())).isEqualTo("短く挨拶😀");
   assertThat(encoded.getLast()).isEqualTo("= "+raw.length);
  }assertThat(derived).doesNotExist();assertThat(source).exists();
 }
 @Test void preservesEveryByteAndRejectsInvalidOutput() throws Exception {
  String text="Latin 日本語 😀\n";assertThat(WhisperByteVocabulary.decode(WhisperByteVocabulary.encode(text.getBytes(StandardCharsets.UTF_8)))).isEqualTo(text);
  byte[] all=new byte[256];for(int i=0;i<256;i++)all[i]=(byte)i;String encoded=WhisperByteVocabulary.encode(all);
  for(int i=0;i<256;i++)assertThat((int)encoded.charAt(i)).isEqualTo(0xe000+i);
  assertThatThrownBy(()->WhisperByteVocabulary.decode("plain")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->WhisperByteVocabulary.decode("\ue0ff")).isInstanceOf(CharacterCodingException.class);
 }
 @Test void rejectsDuplicateIdsAndMalformedVocabulary() throws Exception {
  Path source=temp.resolve("bad.txt");for(String input:new String[]{"YQ== 0\nYg== 0\n","? 0\n","YQ== -1\n"}){
   Files.writeString(source,input);assertThatThrownBy(()->WhisperByteVocabulary.create(source)).isInstanceOf(IllegalArgumentException.class);
  }
 }
}
