package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/** A trusted destination grant never disables TLS certificate verification. */
class HttpApiTlsTest {
  @Test
  void untrustedCertificateIsRejectedEvenForAllowedLocalOrigin() throws Exception {
    var store = KeyStore.getInstance("PKCS12");
    try (var input = getClass().getResourceAsStream("/httpapi/untrusted-fixture.p12")) {
      store.load(input, "fixture-password".toCharArray());
    }
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "fixture-password".toCharArray());
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(keys.getKeyManagers(), null, null);
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ssl));
    server.start();
    try {
      URI origin = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
      var service =
          new HttpApiService(
              new SafeHttpFetcher(),
              new HttpApiProperties(List.of(origin), null, null, null, 5000));
      assertEquals(
          "NETWORK_ERROR",
          service
              .read(new HttpApiRequest(origin + "/api", null, null, null, null, null, null, null))
              .error());
    } finally {
      server.stop(0);
    }
  }
}
