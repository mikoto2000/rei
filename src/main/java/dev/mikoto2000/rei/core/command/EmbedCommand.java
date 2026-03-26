package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.FileSystemResource;

import dev.mikoto2000.rei.core.configuration.VectorStorePaths;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * EmbedCommand
 */
@Command(
name = "embed",
description = "ドキュメントをベクトルストアに埋め込みます")
@RequiredArgsConstructor
public class EmbedCommand implements Runnable {

  private static final Path STORE_FILE = VectorStorePaths.storeFile();

  private final SimpleVectorStore vectorStore;

  @Parameters(arity = "1..*", paramLabel = "DOCUMENTS...", description = "ドキュメントのパス")
  private String[] documents;

  @Override
  public void run() {
    try {
      for (String document : documents) {
        TikaDocumentReader documentReader;
        documentReader = new TikaDocumentReader(new FileSystemResource(document));
        TextSplitter textSplitter = TokenTextSplitter.builder()
            .withChunkSize(500)
            .build();
        vectorStore.add(textSplitter.apply(documentReader.get()));
      }
      VectorStorePaths.createParentDirectories();
      vectorStore.save(STORE_FILE.toFile());
    } catch (IOException e) {
      throw new RuntimeException("ベクトルストアの保存に失敗しました", e);
    }
  }
}
