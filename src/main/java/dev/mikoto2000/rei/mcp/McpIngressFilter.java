package dev.mikoto2000.rei.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCPによる機能の提供に必要なMCPIngress絞り込み条件を扱う。
 *
 * <p>Dedicated connector boundary, applied before authentication and MCP dispatch.
 */
public final class McpIngressFilter extends OncePerRequestFilter {
  /** TUNNEL証明書ヘッダー。 */
  private static final String TUNNEL_CERTIFICATE_HEADER = "X-Forwarded-Client-Cert";

  /** 判断基準。 */
  private final McpIngressPolicy policy;

  /** 指定された値や利用先を使って、McpIngressFilterを初期化する。 */
  public McpIngressFilter(int port) {
    policy = new McpIngressPolicy(port);
  }

  /** MCPによる機能の提供に関するdo絞り込み条件内部を処理する。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean mcp =
        request.getRequestURI().startsWith("/mcp") || request.getServletPath().startsWith("/mcp");
    if (!mcp) {
      if (request.getLocalPort() == policy.port()) response.setStatus(404);
      else chain.doFilter(request, response);
      return;
    }
    boolean forwarded =
        Collections.list(request.getHeaderNames()).stream()
            .anyMatch(
                name ->
                    !name.equalsIgnoreCase(TUNNEL_CERTIFICATE_HEADER)
                        && (name.equalsIgnoreCase("Forwarded")
                            || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-")));
    if (forwarded
        || Collections.list(request.getHeaders("Host")).size() != 1
        || Collections.list(request.getHeaders("Origin")).size() > 1
        || !policy.allows(
            request.getRemoteAddr(),
            request.getLocalAddr(),
            request.getLocalPort(),
            request.getHeader("Host"),
            request.getHeader("Origin"))) {
      response.setStatus(403);
      return;
    }
    if (request.getContentLengthLong() > 65536) {
      response.setStatus(413);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(65537);
    if (body.length > 65536) {
      response.setStatus(413);
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          // Tunnel certificate metadata is not a credential or a source of network identity.
          /** MCPによる機能の提供で参照するヘッダーを返す。 */
          @Override
          public String getHeader(String name) {
            return name.equalsIgnoreCase(TUNNEL_CERTIFICATE_HEADER) ? null : super.getHeader(name);
          }

          /** MCPによる機能の提供で参照するHTTPヘッダーを返す。 */
          @Override
          public java.util.Enumeration<String> getHeaders(String name) {
            return name.equalsIgnoreCase(TUNNEL_CERTIFICATE_HEADER)
                ? Collections.emptyEnumeration()
                : super.getHeaders(name);
          }

          /** MCPによる機能の提供で参照するヘッダー名前一覧を返す。 */
          @Override
          public java.util.Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !name.equalsIgnoreCase(TUNNEL_CERTIFICATE_HEADER))
                    .toList());
          }

          /** MCPによる機能の提供で参照する入力逐次データを返す。 */
          @Override
          public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
              /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
              public int read() {
                return input.read();
              }

              /** MCPによる機能の提供において、完了済みかどうかを判定する。 */
              public boolean isFinished() {
                return input.available() == 0;
              }

              /** MCPによる機能の提供において、準備完了かどうかを判定する。 */
              public boolean isReady() {
                return true;
              }

              /** MCPによる機能の提供で使用する読み取り通知の受け取り先を設定する。 */
              public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
              }
            };
          }

          /** MCPによる機能の提供で参照する読み取り元を返す。 */
          @Override
          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        response);
  }
}
