package dev.mikoto2000.rei.mcp;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class McpIngressFilterTest {
  @Test void primaryConnectorCannotReachMcpEvenWithForwardedLoopbackHeaders() throws Exception {
    var request=new MockHttpServletRequest("POST","/mcp");
    request.setLocalAddr("192.168.1.2");request.setLocalPort(8080);request.setRemoteAddr("127.0.0.1");
    request.addHeader("Host","127.0.0.1:8091");request.addHeader("X-Forwarded-For","127.0.0.1");
    var response=new MockHttpServletResponse();
    new McpIngressFilter(8091).doFilter(request,response,(a,b)->fail("Reached MCP"));
    assertEquals(403,response.getStatus());
  }
  @Test void dedicatedConnectorCannotExposeWebApi() throws Exception {
    var request=new MockHttpServletRequest("GET","/api/v1/projects");request.setLocalPort(8091);
    var response=new MockHttpServletResponse();
    new McpIngressFilter(8091).doFilter(request,response,(a,b)->fail("Reached web API"));
    assertEquals(404,response.getStatus());
  }
  @Test void servletDecodedPathCannotBypassPrimaryConnectorBoundary() throws Exception {
    var request=new MockHttpServletRequest("POST","/%6dcp");request.setServletPath("/mcp");request.setLocalPort(8080);
    var response=new MockHttpServletResponse();new McpIngressFilter(8091).doFilter(request,response,(a,b)->fail("Decoded MCP path escaped boundary"));
    assertEquals(403,response.getStatus());
  }
  @Test void oversizedBodyIsRejectedBeforeSdkDispatch() throws Exception {
    var request=new MockHttpServletRequest("POST","/mcp");request.setLocalAddr("127.0.0.1");request.setRemoteAddr("127.0.0.1");request.setLocalPort(8091);
    request.addHeader("Host","127.0.0.1:8091");request.setContent(new byte[65537]);var response=new MockHttpServletResponse();
    new McpIngressFilter(8091).doFilter(request,response,(a,b)->fail("Oversized body reached SDK"));assertEquals(413,response.getStatus());
  }}
