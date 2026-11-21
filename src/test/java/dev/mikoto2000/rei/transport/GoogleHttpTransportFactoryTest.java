package dev.mikoto2000.rei.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.mtls.MtlsProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks certificate-source and client-certificate choices without making network requests. */
class GoogleHttpTransportFactoryTest {
  @TempDir Path directory;

  @Test
  void jdkCertificateStoreIsPreferredAndMissingJdkUsesTheExistingBundledFallback()
      throws Exception {
    KeyStore empty = KeyStore.getInstance(KeyStore.getDefaultType());
    empty.load(null, null);
    Path cacerts = directory.resolve("lib/security/cacerts");
    Files.createDirectories(cacerts.getParent());
    try (var output = Files.newOutputStream(cacerts)) {
      empty.store(output, "changeit".toCharArray());
    }
    assertThat(GoogleHttpTransportFactory.loadTrustStore(directory).size()).isZero();
    assertThat(GoogleHttpTransportFactory.loadTrustStore(directory.resolve("missing")).size())
        .isPositive();
  }

  @Test
  void clientCertificateIsRequestedOnlyWhenTheExistingMtlsProviderEnablesIt() throws Exception {
    var provider = mock(MtlsProvider.class);
    var transport = GoogleHttpTransportFactory.newTrustedTransport(provider);
    try {
      assertThat(transport).isNotNull();
    } finally {
      transport.shutdown();
    }
    verify(provider, org.mockito.Mockito.never()).getKeyStore();
    when(provider.useMtlsClientCertificate()).thenReturn(true);
    when(provider.getKeyStore()).thenReturn(null);
    transport = GoogleHttpTransportFactory.newTrustedTransport(provider);
    try {
      assertThat(transport).isNotNull();
    } finally {
      transport.shutdown();
    }
    verify(provider).getKeyStore();
    verify(provider).getKeyStorePassword();
  }
}
