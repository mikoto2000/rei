package dev.mikoto2000.rei.core.command;

import java.io.File;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.FileSystemResource;

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

  private static final File STORE_FILE = new File("target/vector-store.json");

  private final SimpleVectorStore vectorStore;

  @Parameters(arity = "1..*", paramLabel = "DOCUMENTS...", description = "ドキュメントのパス")
  private String[] documents;

  @Override
  public void run() {
    for (String document : documents) {
      TikaDocumentReader documentReader;
      documentReader = new TikaDocumentReader(new FileSystemResource(document));
      TextSplitter textSplitter = TokenTextSplitter.builder()
          .withChunkSize(500)
          .build();
      vectorStore.add(textSplitter.apply(documentReader.get()));
    }
    vectorStore.save(STORE_FILE);
  }
}


