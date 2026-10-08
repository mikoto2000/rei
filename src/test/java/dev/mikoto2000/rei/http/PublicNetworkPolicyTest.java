package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicNetworkPolicyTest {
  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1",
      "169.254.1.1", "100.64.0.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1",
      "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1",
      "fc00::1", "fe80::1", "fec0::1", "ff02::1", "::ffff:127.0.0.1", "2001:db8::1",
      "2001::1", "2002:7f00:1::", "3fff::1", "64:ff9b::7f00:1"})
  void rejectsNonPublicAddresses(String address) throws Exception {
    assertFalse(PublicNetworkPolicy.isPublic(InetAddress.getByName(address)), address);
  }
  @ParameterizedTest
  @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
  void acceptsPublicAddresses(String address) throws Exception {
    assertTrue(PublicNetworkPolicy.isPublic(InetAddress.getByName(address)));
  }
  @ParameterizedTest
  @ValueSource(strings = {"file:///x", "ftp://example.org/x", "http:relative", "http://user:pass@example.org",
      "https://example.org:22", "http://example.org:0", "https://example.org:8443", "http://localhost/x",
      "http://127.0.0.1", "http://[::1]", "http://[fe80::1%25eth0]", "http://192.0.2.1"})
  void rejectsUnsafeUris(String value) {
    assertThrows(HttpFetchException.class, () -> PublicNetworkPolicy.validate(URI.create(value)));
  }
  @ParameterizedTest
  @ValueSource(strings = {"https://example.org/a?v=1", "HTTPS://example.org:443/a", "http://example.org:80",
      "https://8.8.8.8", "https://[2001:4860:4860::8888]"})
  void permitsPublicHttpUrisWithoutResolvingThem(String value) {
    assertDoesNotThrow(() -> PublicNetworkPolicy.validate(URI.create(value)));
  }
}
