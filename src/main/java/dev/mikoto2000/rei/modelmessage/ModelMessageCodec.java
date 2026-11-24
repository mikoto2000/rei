package dev.mikoto2000.rei.modelmessage;

import org.springframework.ai.chat.messages.Message;

/** Versioned model-message serialization, independent of transport, storage and execution. */
public final class ModelMessageCodec {
  public byte[] encode(Message message) {
    throw new UnsupportedOperationException("Model message encoding is not implemented");
  }

  public Message decode(byte[] bytes) {
    throw new UnsupportedOperationException("Model message decoding is not implemented");
  }
}
