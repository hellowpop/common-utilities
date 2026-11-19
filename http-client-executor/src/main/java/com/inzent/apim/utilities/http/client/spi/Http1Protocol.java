package com.inzent.apim.utilities.http.client.spi;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.CaptureLevel;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.model.ResponseStatus;
import com.inzent.apim.utilities.http.client.model.WireMode;
import com.inzent.apim.utilities.http.client.store.BodyStore;

/**
 * HTTP/1.1 framing shared by the JDK and raw transports. No text/body normalization.
 */
public final class Http1Protocol {
	private Http1Protocol() {
	}

	public static String authority(URI uri) {
		return uri.getHost() + (uri.getPort() == -1
		                        ? ""
		                        : ":" + uri.getPort());
	}

	public static int port(URI uri) {
		return uri.getPort() == -1
		       ? ("https".equalsIgnoreCase(uri.getScheme())
		          ? 443
		          : 80)
		       : uri.getPort();
	}

	public static String target(URI uri,
	                            boolean proxy) {
		String path = uri.getRawPath()
		                 .isEmpty()
		              ? "/"
		              : uri.getRawPath();
		String resource = path + (uri.getRawQuery() == null
		                          ? ""
		                          : "?" + uri.getRawQuery());
		return proxy
		       ? uri.getScheme() + "://" + uri.getRawAuthority() + resource
		       : resource;
	}

	public static void validate(ExecutionContext context,
	                            ConnectorType type) throws IOException {
		if (context.version() == ConnectVersion.HTTP_2 && (type == ConnectorType.JAVA || !"https".equalsIgnoreCase(context.url()
		                                                                                                                  .getScheme()) || context.request()
		                                                                                                                                          .wireMode() == WireMode.RAW_HTTP1 || context.captureLevel() == CaptureLevel.HTTP1_PLAINTEXT_BYTES || context.shutdownOutput())) {
			throw new IllegalArgumentException("HTTP/2 requires HT5/NETTY, HTTPS, standard framing and logical capture");
		}
		HttpHeaders headers = context.request()
		                             .headers();
		if (headers.contains("Host") && !authority(context.url()).equalsIgnoreCase(headers.first("Host"))) {
			throw new IllegalArgumentException("Host conflicts with URL");
		}
		if (headers.values("Host")
		           .size() > 1 || headers.values("Content-Length")
		                                 .size() > 1 || headers.values("Transfer-Encoding")
		                                                       .size() > 1) {
			throw new IllegalArgumentException("Duplicate request framing headers");
		}
		if (headers.contains("Content-Length") && headers.contains("Transfer-Encoding")) {
			throw new IllegalArgumentException("Conflicting framing headers");
		}
		if (headers.contains("Transfer-Encoding") && !"chunked".equalsIgnoreCase(headers.first("Transfer-Encoding"))) {
			throw new IllegalArgumentException("Only chunked transfer encoding supported");
		}
		if (context.version() == ConnectVersion.HTTP_2 && headers.contains("Transfer-Encoding")) {
			throw new IllegalArgumentException("HTTP/2 does not allow Transfer-Encoding");
		}
		if (headers.contains("Expect") || headers.contains("Upgrade") || headers.contains("Proxy-Authorization")) {
			throw new IllegalArgumentException("Expect, Upgrade and explicit Proxy-Authorization are unsupported");
		}
		if (headers.contains("Content-Length")) {
			long length = length(headers);
			if (context.request()
			           .wireMode() == WireMode.STANDARD && length != context.request()
			                                                                .body()
			                                                                .size()) {
				throw new IllegalArgumentException("Content-Length does not match body; select raw HTTP/1.1");
			}
		}
		HttpHeaders.Builder effective = headers.toBuilder();
		if (!headers.contains("Host")) {
			effective.add("Host",
			              authority(context.url()));
		}
		if (!headers.contains("Content-Length") && !headers.contains("Transfer-Encoding") && context.request()
		                                                                                            .body()
		                                                                                            .size() > 0) {
			effective.add("Content-Length",
			              Long.toString(context.request()
			                                   .body()
			                                   .size()));
		}
		if (context.request()
		           .wireMode() == WireMode.RAW_HTTP1) {
			effective.set("Connection",
			              "close");
		}
		context.requestHeaders(effective.build());
	}

	public static void write(ExecutionContext context,
	                         OutputStream output,
	                         boolean proxy) throws IOException {
		StringBuilder head = new StringBuilder(context.method()
		                                              .token()).append(' ')
		                                                       .append(target(context.url(),
		                                                                      proxy))
		                                                       .append(" HTTP/1.1\r\n");
		for (HttpHeaders.Entry entry : context.request()
		                                      .headers()
		                                      .entries()) {
			head.append(entry.name())
			    .append(": ")
			    .append(entry.value())
			    .append("\r\n");
		}
		output.write(head.append("\r\n")
		                 .toString()
		                 .getBytes(StandardCharsets.ISO_8859_1));
		boolean chunked = context.request()
		                         .headers()
		                         .contains("Transfer-Encoding");
		try (InputStream body = context.request()
		                               .body()
		                               .openStream()) {
			byte[] buffer = new byte[8192];
			for (int count; (count = body.read(buffer)) != -1; ) {
				context.checkCancelled();
				if (chunked) {
					output.write((Integer.toHexString(count) + "\r\n").getBytes(StandardCharsets.US_ASCII));
				}
				output.write(buffer,
				             0,
				             count);
				if (chunked) {
					output.write(new byte[]{13,
					                        10});
				}
			}
		}
		if (chunked) {
			output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		}
		output.flush();
	}

	public static HttpResponsePayload read(ExecutionContext context,
	                                       InputStream input) throws IOException {
		List<ResponseStatus> interim = new ArrayList<>();
		ResponseStatus status;
		HttpHeaders headers;
		do {
			String line = line(input,
			                   8192);
			String[] parts = line.split(" ",
			                            3);
			if (parts.length < 2 || !parts[0].equals("HTTP/1.1") || !parts[1].matches("[0-9]{3}")) {
				throw new IOException("Invalid HTTP/1.1 response status");
			}
			status = new ResponseStatus(Integer.parseInt(parts[1]),
			                            parts.length == 3
			                            ? parts[2]
			                            : "");
			headers = headers(input);
			if (status.code() == 101) {
				throw new IOException("Protocol upgrade is unsupported");
			}
			if (status.code() < 200) {
				if (interim.size() == 16) {
					throw new IOException("Too many interim responses");
				}
				interim.add(status);
			}
		} while (status.code() < 200);
		BodyStore body = context.newStore();
		HttpHeaders trailers = HttpHeaders.builder()
		                                  .build();
		context.response(new HttpResponsePayload(status,
		                                         ConnectVersion.HTTP_1_1,
		                                         headers,
		                                         trailers,
		                                         body,
		                                         interim,
		                                         false));
		if (headers.contains("Content-Length") && headers.contains("Transfer-Encoding")) {
			throw new IOException("Conflicting response framing");
		}
		if (headers.contains("Content-Length")) {
			length(headers);
		}
		boolean noBody = context.method()
		                        .token()
		                        .equals("HEAD") || status.code() == 204 || status.code() == 304;
		if (!noBody) {
			if (headers.contains("Transfer-Encoding")) {
				if (headers.values("Transfer-Encoding")
				           .size() != 1 || !"chunked".equalsIgnoreCase(headers.first("Transfer-Encoding"))) {
					throw new IOException("Unsupported response transfer encoding");
				}
				while (true) {
					String size = line(input,
					                   8192).split(";",
					                               2)[0];
					if (!size.matches("[0-9A-Fa-f]+")) {
						throw new IOException("Invalid chunk length");
					}
					long count;
					try {
						count = Long.parseLong(size,
						                       16);
					}
					catch (NumberFormatException exception) {
						throw new IOException("Chunk too large",
						                      exception);
					}
					if (count == 0) {
						trailers = headers(input);
						break;
					}
					copy(input,
					     body,
					     count,
					     context);
					if (!line(input,
					          2).isEmpty()) {
						throw new IOException("Missing chunk CRLF");
					}
				}
			} else if (headers.contains("Content-Length")) {
				copy(input,
				     body,
				     length(headers),
				     context);
			} else {
				byte[] buffer = new byte[8192];
				for (int count; (count = input.read(buffer)) != -1; ) {
					context.checkCancelled();
					body.write(buffer,
					           0,
					           count);
				}
			}
		}
		body.seal();
		return new HttpResponsePayload(status,
		                               ConnectVersion.HTTP_1_1,
		                               headers,
		                               trailers,
		                               body,
		                               interim,
		                               true);
	}

	public static long length(HttpHeaders headers) throws IOException {
		Long result = null;
		for (String value : headers.values("Content-Length")) {
			for (String item : value.split(",",
			                               -1)) {
				String token = item.trim();
				if (!token.matches("[0-9]+")) {
					throw new IOException("Invalid Content-Length");
				}
				long parsed;
				try {
					parsed = Long.parseLong(token);
				}
				catch (NumberFormatException exception) {
					throw new IOException("Content-Length overflow",
					                      exception);
				}
				if (result != null && result.longValue() != parsed) {
					throw new IOException("Conflicting Content-Length");
				}
				result = parsed;
			}
		}
		return result == null
		       ? 0
		       : result;
	}

	public static HttpHeaders headers(InputStream input) throws IOException {
		HttpHeaders.Builder builder = HttpHeaders.builder();
		int total = 0;
		for (String line; !(line = line(input,
		                                8192)).isEmpty(); ) {
			total += line.length() + 2;
			if (total > 65536) {
				throw new IOException("Headers exceed 64 KiB");
			}
			int colon = line.indexOf(':');
			if (colon < 1) {
				throw new IOException("Malformed header");
			}
			try {
				builder.add(line.substring(0,
				                           colon),
				            line.substring(colon + 1)
				                .trim());
			}
			catch (IllegalArgumentException exception) {
				throw new IOException("Invalid response header",
				                      exception);
			}
		}
		return builder.build();
	}

	public static String line(InputStream input,
	                          int limit) throws IOException {
		StringBuilder result = new StringBuilder();
		while (true) {
			int value = input.read();
			if (value == -1) {
				throw new EOFException("Incomplete HTTP line");
			}
			if (value == '\r') {
				if (input.read() != '\n') {
					throw new IOException("Expected CRLF");
				}
				return result.toString();
			}
			if (value == '\n' || result.length() >= limit) {
				throw new IOException("Invalid or excessive HTTP line");
			}
			result.append((char) value);
		}
	}

	private static void copy(InputStream input,
	                         OutputStream output,
	                         long length,
	                         ExecutionContext context) throws IOException {
		byte[] buffer = new byte[8192];
		long remaining = length;
		while (remaining > 0) {
			context.checkCancelled();
			int count = input.read(buffer,
			                       0,
			                       (int) Math.min(buffer.length,
			                                      remaining));
			if (count < 0) {
				throw new EOFException("Incomplete HTTP body");
			}
			output.write(buffer,
			             0,
			             count);
			remaining -= count;
		}
	}

	public static boolean reusable(HttpResponsePayload response,
	                               ExecutionContext context) {
		if (!context.requestSent()) {
			return false;
		}
		if (context.request()
		           .wireMode() == WireMode.RAW_HTTP1 || context.shutdownOutput() || hasToken(context.request()
		                                                                                            .headers(),
		                                                                                     "Connection",
		                                                                                     "close") || hasToken(response.headers(),
		                                                                                                          "Connection",
		                                                                                                          "close")) {
			return false;
		}
		return response.headers()
		               .contains("Content-Length") || response.headers()
		                                                      .contains("Transfer-Encoding") || context.method()
		                                                                                               .token()
		                                                                                               .equals("HEAD") || response.status()
		                                                                                                                          .code() == 204 || response.status()
		                                                                                                                                                    .code() == 304;
	}

	public static boolean hasToken(HttpHeaders headers,
	                               String name,
	                               String token) {
		return headers.values(name)
		              .stream()
		              .flatMap(value -> java.util.Arrays.stream(value.split(",")))
		              .anyMatch(value -> value.trim()
		                                      .equalsIgnoreCase(token));
	}
}
