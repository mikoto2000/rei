package dev.mikoto2000.rei.application.session;

import java.io.*;
import java.time.Instant;
import java.util.Base64;

/** Versioned opaque transport token, bound to the requested resource/filter, never a file path. */
public final class CursorCodec {
  public String encode(String scope, CursorKey key) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeByte(1); out.writeUTF(scope); out.writeLong(key.time().getEpochSecond());
      out.writeInt(key.time().getNano()); out.writeUTF(key.id());
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException error) { throw new IllegalArgumentException("Invalid cursor", error); }
  }
  public CursorKey decode(String scope, String token) {
    if (token == null) return null;
    try {
      if (token.isEmpty() || token.length() > 4096) throw new IllegalArgumentException();
      var bytes = Base64.getUrlDecoder().decode(token);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw new IllegalArgumentException();
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readUnsignedByte() != 1 || !in.readUTF().equals(scope)) throw new IllegalArgumentException();
      long seconds = in.readLong(); int nanos = in.readInt();
      if (nanos < 0 || nanos > 999_999_999) throw new IllegalArgumentException();
      var key = new CursorKey(Instant.ofEpochSecond(seconds, nanos), in.readUTF());
      if (key.id().isEmpty() || in.available() != 0) throw new IllegalArgumentException();
      return key;
    } catch (IOException | RuntimeException error) { throw new IllegalArgumentException("Invalid cursor", error); }
  }
}
