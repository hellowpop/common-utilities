package com.inzent.apim.utilities.http2mock;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ProtocolFilter
		extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(HttpServletRequest request,
	                                HttpServletResponse response,
	                                FilterChain chain) throws ServletException, IOException {
		// Use the container's decoded, mapped path, including its matrix-parameter normalization.
		String path = request.getServletPath();
		if (path.startsWith("/http2/") && !request.isSecure()) {
			reject(response, 403, "HTTPS required for /http2/*");
		} else if (path.startsWith("/api/") && !"HTTP/1.1".equals(request.getProtocol())) {
			reject(response,
			       505,
			       "HTTP/1.1 required");
		} else if (path.startsWith("/http2/") && !request.getProtocol()
		                                                 .startsWith("HTTP/2")) {
			reject(response,
			       505,
			       "HTTP/2 over TLS required; negotiate h2 using ALPN");
		} else {
			chain.doFilter(request,
			               response);
		}
	}

	private void reject(HttpServletResponse response,
	                    int status,
	                    String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.getWriter()
		        .write("{\"error\":\"" + message + "\"}");
	}
}
