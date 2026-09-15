package dev.mikoto2000.rei.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private final byte[] expected;
  ApiKeyAuthenticationFilter(ApiKeyProperties properties) {
    expected = properties.getApiKey().getBytes(StandardCharsets.UTF_8);
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if ("GET".equals(request.getMethod()) && "/actuator/health".equals(path)) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader("Authorization");
    if (expected.length == 0 || header == null || !header.startsWith("Bearer ")
        || !MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken("api-client", null, List.of()));
    SecurityContextHolder.setContext(context);
    try { chain.doFilter(request, response); }
    finally { SecurityContextHolder.clearContext(); }
  }
  @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
}
