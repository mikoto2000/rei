package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** MCPIngress絞り込み条件の結果と状態の契約を検証するテスト。 */
class McpIngressFilterTest {
  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void tunnelClientCertificateIsDiscardedWithoutEstablishingIdentity() throws Exception {
    var request = localRequest();
    request.addHeader("x-forwarded-client-cert", "untrusted certificate metadata");
    var response = new MockHttpServletResponse();
    boolean[] reached = {false};
    new McpIngressFilter(8091)
        .doFilter(
            request,
            response,
            (a, b) -> {
              reached[0] = true;
              var http = (jakarta.servlet.http.HttpServletRequest) a;
              assertNull(http.getHeader("X-Forwarded-Client-Cert"));
              assertFalse(http.getHeaders("x-forwarded-client-cert").hasMoreElements());
              assertFalse(
                  java.util.Collections.list(http.getHeaderNames()).stream()
                      .anyMatch(n -> n.equalsIgnoreCase("X-Forwarded-Client-Cert")));
            });
    assertTrue(reached[0]);
    assertEquals(200, response.getStatus());
  }

  /** MCPによる機能の提供で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void routingHeadersRemainRejectedEvenWithTunnelCertificate() throws Exception {
    for (String name :
        java.util.List.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            "X-Forwarded-Anything")) {
      var request = localRequest();
      request.addHeader("X-Forwarded-Client-Cert", "metadata");
      request.addHeader(name, "127.0.0.1");
      var response = new MockHttpServletResponse();
      new McpIngressFilter(8091)
          .doFilter(request, response, (a, b) -> fail("Routing header reached SDK: " + name));
      assertEquals(403, response.getStatus());
    }
  }

  /** {@code localRequest} の条件を再現するための補助情報を返す。 */
  private MockHttpServletRequest localRequest() {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.setLocalAddr("127.0.0.1");
    request.setRemoteAddr("127.0.0.1");
    request.setLocalPort(8091);
    request.addHeader("Host", "127.0.0.1:8091");
    return request;
  }

  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void tunnelCertificateCannotBypassSocketHostOrOriginChecks() throws Exception {
    for (String invalid : java.util.List.of("remote", "local", "port", "host", "origin")) {
      var request = localRequest();
      request.addHeader("X-Forwarded-Client-Cert", "metadata");
      switch (invalid) {
        case "remote" -> request.setRemoteAddr("192.168.1.1");
        case "local" -> request.setLocalAddr("192.168.1.2");
        case "port" -> request.setLocalPort(8080);
        case "host" -> {
          request.removeHeader("Host");
          request.addHeader("Host", "evil.test");
        }
        case "origin" -> request.addHeader("Origin", "https://evil.test");
      }
      var response = new MockHttpServletResponse();
      new McpIngressFilter(8091)
          .doFilter(request, response, (a, b) -> fail("Certificate bypassed " + invalid));
      assertEquals(403, response.getStatus());
    }
  }

  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void primaryConnectorCannotReachMcpEvenWithForwardedLoopbackHeaders() throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.setLocalAddr("192.168.1.2");
    request.setLocalPort(8080);
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("Host", "127.0.0.1:8091");
    request.addHeader("X-Forwarded-For", "127.0.0.1");
    var response = new MockHttpServletResponse();
    new McpIngressFilter(8091).doFilter(request, response, (a, b) -> fail("Reached MCP"));
    assertEquals(403, response.getStatus());
  }

  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void dedicatedConnectorCannotExposeWebApi() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/projects");
    request.setLocalPort(8091);
    var response = new MockHttpServletResponse();
    new McpIngressFilter(8091).doFilter(request, response, (a, b) -> fail("Reached web API"));
    assertEquals(404, response.getStatus());
  }

  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void servletDecodedPathCannotBypassPrimaryConnectorBoundary() throws Exception {
    var request = new MockHttpServletRequest("POST", "/%6dcp");
    request.setServletPath("/mcp");
    request.setLocalPort(8080);
    var response = new MockHttpServletResponse();
    new McpIngressFilter(8091)
        .doFilter(request, response, (a, b) -> fail("Decoded MCP path escaped boundary"));
    assertEquals(403, response.getStatus());
  }

  /** MCPによる機能の提供で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oversizedBodyIsRejectedBeforeSdkDispatch() throws Exception {
    var request = new MockHttpServletRequest("POST", "/mcp");
    request.setLocalAddr("127.0.0.1");
    request.setRemoteAddr("127.0.0.1");
    request.setLocalPort(8091);
    request.addHeader("Host", "127.0.0.1:8091");
    request.setContent(new byte[65537]);
    var response = new MockHttpServletResponse();
    new McpIngressFilter(8091)
        .doFilter(request, response, (a, b) -> fail("Oversized body reached SDK"));
    assertEquals(413, response.getStatus());
  }
}
