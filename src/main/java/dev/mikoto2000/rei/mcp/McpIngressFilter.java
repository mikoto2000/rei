package dev.mikoto2000.rei.mcp;

import java.io.*;
import java.util.Collections;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dedicated connector boundary, applied before authentication and MCP dispatch. */
public final class McpIngressFilter extends OncePerRequestFilter {
  private final McpIngressPolicy policy;
  public McpIngressFilter(int port) { policy=new McpIngressPolicy(port); }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    boolean mcp=request.getRequestURI().startsWith("/mcp") || request.getServletPath().startsWith("/mcp");
    if(!mcp) {
      if(request.getLocalPort()==policy.port())response.setStatus(404);
      else chain.doFilter(request,response);
      return;
    }
    boolean forwarded=Collections.list(request.getHeaderNames()).stream()
        .anyMatch(name->name.equalsIgnoreCase("Forwarded")||name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-"));
    if(forwarded || Collections.list(request.getHeaders("Host")).size()!=1
        || Collections.list(request.getHeaders("Origin")).size()>1
        || !policy.allows(request.getRemoteAddr(),request.getLocalAddr(),request.getLocalPort(),
            request.getHeader("Host"),request.getHeader("Origin"))) { response.setStatus(403);return; }
    if(request.getContentLengthLong()>65536) { response.setStatus(413);return; }
    byte[] body=request.getInputStream().readNBytes(65537);
    if(body.length>65536) { response.setStatus(413);return; }
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override public ServletInputStream getInputStream() {
        var input=new ByteArrayInputStream(body);
        return new ServletInputStream() {
          public int read(){return input.read();}
          public boolean isFinished(){return input.available()==0;}
          public boolean isReady(){return true;}
          public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
        };
      }
      @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    },response);
  }
}
