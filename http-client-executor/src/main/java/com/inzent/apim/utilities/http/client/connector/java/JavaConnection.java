package com.inzent.apim.utilities.http.client.connector.java;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.ProxyConfigure;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.HttpResponsePayload;
import com.inzent.apim.utilities.http.client.spi.ClientConnection;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

/**
 * JDK-only HTTP/1.1 connector. Also supplies socket setup for Apache's managed connection.
 */
public class JavaConnection
		implements ClientConnection {
	protected final ConnectionConfigure configure;
	protected final Socket socket;
	protected final InputStream input;
	protected final OutputStream output;
	protected ExecutionContext current;
	private final boolean forwardProxy;
	private boolean reusable;
	protected final java.util.concurrent.ExecutorService readers;

	public JavaConnection(ConnectionConfigure configure,
	                      ExecutionContext context,
	                      java.util.concurrent.ExecutorService readers) throws Exception {
		this.readers = readers;
		this.configure = configure;
		this.socket = connect(configure,
		                      context);
		this.input = new java.io.BufferedInputStream(this.socket.getInputStream(),
		                                             configure.readBufferSize());
		this.output = this.socket.getOutputStream();
		this.forwardProxy = configure.proxy() != null && !"https".equalsIgnoreCase(context.url()
		                                                                                  .getScheme());
	}

	public static Socket connect(ConnectionConfigure configure,
	                             ExecutionContext context) throws Exception {
		URI uri = context.url();
		ProxyConfigure proxy = configure.proxy();
		Socket socket = new Socket();
		context.abortWith(() -> closeQuietly(socket));
		boolean success = false;
		try {
			socket.setTcpNoDelay(configure.tcpNoDelay());
			socket.setKeepAlive(configure.keepAlive());
			if (configure.sendBufferSize() > 0) {
				socket.setSendBufferSize(configure.sendBufferSize());
			}
			if (configure.receiveBufferSize() > 0) {
				socket.setReceiveBufferSize(configure.receiveBufferSize());
			}
			if (configure.localAddress() != null) {
				socket.bind(new InetSocketAddress(configure.localAddress(),
				                                  0));
			}
			socket.connect(new InetSocketAddress(proxy == null
			                                     ? uri.getHost()
			                                     : proxy.host(),
			                                     proxy == null
			                                     ? Http1Protocol.port(uri)
			                                     : proxy.port()),
			               (int) Math.min(Integer.MAX_VALUE,
			                              Math.min(context.remainingMillis(),
			                                       configure.connectTimeout()
			                                                .toMillis())));
			socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
			                                   Math.min(context.remainingMillis(),
			                                            configure.responseIdleTimeout()
			                                                     .toMillis())));
			if (proxy != null && "https".equalsIgnoreCase(uri.getScheme())) {
				String authority = uri.getHost() + ":" + Http1Protocol.port(uri);
				String request = "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n" + (proxy.authorization() == null
				                                                                                        ? ""
				                                                                                        : "Proxy-Authorization: " + proxy.authorization() + "\r\n") + "\r\n";
				socket.getOutputStream()
				      .write(request.getBytes(StandardCharsets.ISO_8859_1));
				String status = Http1Protocol.line(socket.getInputStream(),
				                                   8192);
				Http1Protocol.headers(socket.getInputStream());
				if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) {
					throw new IOException("Proxy CONNECT failed");
				}
			}
			Socket result = socket;
			if ("https".equalsIgnoreCase(uri.getScheme())) {
				SSLContext sslContext = configure.sslContext() == null
				                        ? SSLContext.getDefault()
				                        : configure.sslContext();
				SSLSocket secure = (SSLSocket) sslContext.getSocketFactory()
				                                         .createSocket(socket,
				                                                       uri.getHost(),
				                                                       Http1Protocol.port(uri),
				                                                       true);
				context.abortWith(() -> closeQuietly(secure));
				SSLParameters parameters = secure.getSSLParameters();
				parameters.setEndpointIdentificationAlgorithm("HTTPS");
				parameters.setProtocols(configure.tlsProtocols()
				                                 .toArray(String[]::new));
				secure.setSSLParameters(parameters);
				secure.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
				                                   Math.min(context.remainingMillis(),
				                                            configure.tlsHandshakeTimeout()
				                                                     .toMillis())));
				secure.startHandshake();
				result = secure;
			}
			success = true;
			return result;
		}
		finally {
			if (!success) {
				closeQuietly(socket);
			}
		}
	}

	protected static void closeQuietly(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException ignored) {
		}
	}

	protected OutputStream captured(OutputStream delegate) {
		return new FilterOutputStream(delegate) {
			@Override
			public void write(int value) throws IOException {
				this.write(new byte[]{(byte) value});
			}

			@Override
			public void write(byte[] bytes,
			                  int offset,
			                  int length) throws IOException {
				this.out.write(bytes,
				               offset,
				               length);
				if (JavaConnection.this.current != null && JavaConnection.this.current.requestCapture() != null) {
					JavaConnection.this.current.requestCapture()
					                           .write(bytes,
					                                  offset,
					                                  length);
				}
			}
		};
	}

	protected void send(ExecutionContext context) throws Exception {
		Http1Protocol.write(context,
		                    this.captured(this.output),
		                    this.forwardProxy);
	}

	@Override
	public HttpResponsePayload exchange(ExecutionContext context) throws Exception {
		this.current = context;
		try {
			context.abortWith(() -> closeQuietly(this.socket));
			this.socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
			                                        Math.min(context.remainingMillis(),
			                                                 this.configure.responseIdleTimeout()
			                                                               .toMillis())));
			if (this.forwardProxy && this.configure.proxy()
			                                       .authorization() != null) {
				context.requestHeaders(context.request()
				                              .headers()
				                              .toBuilder()
				                              .set("Proxy-Authorization",
				                                   this.configure.proxy()
				                                                 .authorization())
				                              .build());
			}
			context.transition(ExecutionContext.State.SENDING,
			                   "SEND",
			                   "START");
			HttpResponsePayload response = com.inzent.apim.utilities.http.client.spi.DuplexExchange.run(context,
			                                                                                            this.readers,
			                                                                                            () -> {
				                                                                                            this.send(context);
				                                                                                            if (context.shutdownOutput()) {
					                                                                                            this.socket.shutdownOutput();
				                                                                                            }
			                                                                                            },
			                                                                                            () -> Http1Protocol.read(context,
			                                                                                                                     this.input),
			                                                                                            () -> closeQuietly(this.socket));
			this.reusable = Http1Protocol.reusable(response,
			                                       context);
			return response;
		}
		finally {
			this.current = null;
		}
	}

	@Override
	public boolean reusable() {
		return this.reusable && !this.socket.isClosed();
	}

	@Override
	public void close() throws IOException {
		this.socket.close();
	}
}
