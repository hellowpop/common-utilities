package com.inzent.apim.utilities.http.caller.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Session token and origin validation for state-changing APIs on the local operator console. */
@Component
public final class ApiSecurityFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","no-referrer");response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
        if(request.getRequestURI().startsWith("/api/")){
            response.setHeader("Cache-Control","no-store");
            if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
                var session=request.getSession(false);String expected=session==null?null:(String)session.getAttribute("callerCsrf");String token=request.getHeader("X-CSRF-Token");
                boolean valid=expected!=null&&token!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));
                String origin=request.getHeader("Origin");if(origin!=null){try{URI o=URI.create(origin);int port=o.getPort()<0?("https".equals(o.getScheme())?443:80):o.getPort();valid&=o.getScheme().equals(request.getScheme())&&o.getHost().equalsIgnoreCase(request.getServerName())&&port==request.getServerPort();}catch(Exception e){valid=false;}}
                if(!valid){response.setStatus(403);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":403,\"title\":\"CSRF validation failed\",\"detail\":\"Fetch /api/csrf with a session cookie and send X-CSRF-Token\"}");return;}
            }
        }
        chain.doFilter(request,response);
    }
}
