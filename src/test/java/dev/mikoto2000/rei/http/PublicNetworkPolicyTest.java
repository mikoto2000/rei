package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 公開ネットワーク判断基準の結果と状態の契約を検証するテスト。 */
class PublicNetworkPolicyTest {
  /** 以外公開接続先一覧を受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "0.0.0.0",
        "127.0.0.1",
        "10.0.0.1",
        "172.16.0.1",
        "192.168.0.1",
        "169.254.1.1",
        "100.64.0.1",
        "192.0.0.1",
        "192.0.2.1",
        "192.88.99.1",
        "198.18.0.1",
        "198.51.100.1",
        "203.0.113.1",
        "224.0.0.1",
        "255.255.255.255",
        "::",
        "::1",
        "fc00::1",
        "fe80::1",
        "fec0::1",
        "ff02::1",
        "::ffff:127.0.0.1",
        "2001:db8::1",
        "2001::1",
        "2002:7f00:1::",
        "3fff::1",
        "64:ff9b::7f00:1"
      })
  void rejectsNonPublicAddresses(String address) throws Exception {
    assertFalse(PublicNetworkPolicy.isPublic(InetAddress.getByName(address)), address);
  }

  /** 公開接続先一覧を受け付けられることを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
  void acceptsPublicAddresses(String address) throws Exception {
    assertTrue(PublicNetworkPolicy.isPublic(InetAddress.getByName(address)));
  }

  /** 許可できないURI一覧を受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:///x",
        "ftp://example.org/x",
        "http:relative",
        "http://user:pass@example.org",
        "https://example.org:22",
        "http://example.org:0",
        "https://example.org:8443",
        "http://localhost/x",
        "http://127.0.0.1",
        "http://[::1]",
        "http://[fe80::1%25eth0]",
        "http://192.0.2.1"
      })
  void rejectsUnsafeUris(String value) {
    assertThrows(HttpFetchException.class, () -> PublicNetworkPolicy.validate(URI.create(value)));
  }

  /** 外部情報の取得で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.org/a?v=1",
        "HTTPS://example.org:443/a",
        "http://example.org:80",
        "https://8.8.8.8",
        "https://[2001:4860:4860::8888]"
      })
  void permitsPublicHttpUrisWithoutResolvingThem(String value) {
    assertDoesNotThrow(() -> PublicNetworkPolicy.validate(URI.create(value)));
  }

  /** 数値ホストの解釈差やprivate表記をDNSへ渡す前に拒否する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://2130706433/a",
        "http://0177.0.0.1/a",
        "http://0x7f000001/a",
        "http://0X7F000001/a",
        "http://127.1/a",
        "http://127.0.1/a",
        "http://127.000.0.1/a",
        "http://0x7f.0.0.1/a",
        "http://127.0.0.1./a",
        "http://8.8.8.8./a",
        "http://008.008.008.008/a",
        "http://134744072/a",
        "http://0x08080808/a",
        "http://4294967296/a",
        "http://999.999.999.999/a",
        "http://1.2.3.4.5/a",
        "http://0x7f000001./a",
        "http://LOCALHOST./a",
        "http://nested.LOCALHOST./a",
        "http://[0:0:0:0:0:0:0:1]/a",
        "http://[::ffff:127.0.0.1]/a",
        "http://[::ffff:7f00:1]/a"
      })
  void rejectsAmbiguousOrPrivateHostSpellingsBeforeResolution(String value) {
    var failure =
        assertThrows(
            HttpFetchException.class, () -> PublicNetworkPolicy.validate(URI.create(value)));
    org.junit.jupiter.api.Assertions.assertEquals(
        HttpFetchException.Code.URL_NOT_ALLOWED, failure.code());
  }

  /** 正規の公開IPと通常のDNS名は、大文字・末尾ドットを含めて維持する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://ExAmPlE.OrG./a?x=1", "https://0xface.example/a",
        "https://assets123.example/a", "https://8.8.8.8:443/a",
        "https://[2001:4860:4860:0:0:0:0:8888]/a", "https://[::ffff:8.8.8.8]/a"
      })
  void preservesCanonicalPublicAndDnsHostnames(String value) {
    assertDoesNotThrow(() -> PublicNetworkPolicy.validate(URI.create(value)));
  }
}
