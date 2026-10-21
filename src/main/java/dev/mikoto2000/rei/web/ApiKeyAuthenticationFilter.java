package dev.mikoto2000.rei.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Web APIによる操作に必要なAPIキー認証絞り込み条件を扱う。 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  /** 期待する内容。 */
  private final byte[] expected;

  /** GitHubの利用設定または連携情報の保存先。 */
  private final boolean github;

  /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
    this(properties, false);
  }

  /** 指定された値や利用先を使って、ApiKeyAuthenticationFilterを初期化する。 */
  ApiKeyAuthenticationFilter(ApiKeyProperties properties, boolean github) {
    expected = properties.getApiKey().getBytes(StandardCharsets.UTF_8);
    this.github = github;
  }

  /** Web APIによる操作に関するdo絞り込み条件内部を処理する。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (github && "POST".equals(request.getMethod()) && "/api/v1/github/events".equals(path)) {
      chain.doFilter(request, response);
      return;
    }
    if ("GET".equals(request.getMethod()) && "/actuator/health".equals(path)) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader("Authorization");
    if (expected.length == 0
        || header == null
        || !header.startsWith("Bearer ")
        || !MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken("api-client", null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** Web APIによる操作において、否定絞り込み条件非同期実行先への引き渡しの実施条件を判定する。 */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }
}
