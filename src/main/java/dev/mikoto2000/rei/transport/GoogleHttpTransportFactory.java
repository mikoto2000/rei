/*
 * Copyright 2013 Google Inc.
 * Adapted for Rei from google-api-client 2.9.2 GoogleNetHttpTransport / GoogleUtils.
 * Licensed under the Apache License, Version 2.0. See
 * https://www.apache.org/licenses/LICENSE-2.0 and META-INF/LICENSE-spring-ai-memory.txt.
 */
package dev.mikoto2000.rei.transport;

import com.google.api.client.googleapis.GoogleUtils;
import com.google.api.client.googleapis.mtls.MtlsProvider;
import com.google.api.client.googleapis.mtls.MtlsUtils;
import com.google.api.client.http.javanet.NetHttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Uses the maintained transport builder while preserving the existing trust store and mTLS
 * selection.
 */
public final class GoogleHttpTransportFactory {
  private static KeyStore certificates;

  private GoogleHttpTransportFactory() {}

  /** Creates the same trusted transport used by the Calendar, Tasks and OAuth clients. */
  public static NetHttpTransport newTrustedTransport()
      throws GeneralSecurityException, IOException {
    return newTrustedTransport(MtlsUtils.getDefaultMtlsProvider());
  }

  static NetHttpTransport newTrustedTransport(MtlsProvider provider)
      throws GeneralSecurityException, IOException {
    KeyStore clientCertificates = null;
    String password = null;
    if (provider.useMtlsClientCertificate()) {
      clientCertificates = provider.getKeyStore();
      password = provider.getKeyStorePassword();
    }
    var builder = new NetHttpTransport.Builder();
    if (clientCertificates != null && password != null)
      return builder.trustCertificates(trustStore(), clientCertificates, password).build();
    return builder.trustCertificates(trustStore()).build();
  }

  private static synchronized KeyStore trustStore() throws GeneralSecurityException, IOException {
    if (certificates == null)
      certificates = loadTrustStore(Path.of(System.getProperty("java.home")));
    return certificates;
  }

  static KeyStore loadTrustStore(Path javaHome) throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    for (String relative : new String[] {"lib/security/cacerts", "jre/lib/security/cacerts"}) {
      try (InputStream stream = Files.newInputStream(javaHome.resolve(relative))) {
        store.load(stream, "changeit".toCharArray());
        return store;
      } catch (IOException unavailable) {
        // The original Google helper also tried the next location before its bundled fallback.
      }
    }
    KeyStore bundled = KeyStore.getInstance("PKCS12");
    try (InputStream stream = GoogleUtils.class.getResourceAsStream("google.p12")) {
      if (stream == null) throw new IOException("Google trust store resource is missing");
      bundled.load(stream, "notasecret".toCharArray());
    }
    return bundled;
  }
}
