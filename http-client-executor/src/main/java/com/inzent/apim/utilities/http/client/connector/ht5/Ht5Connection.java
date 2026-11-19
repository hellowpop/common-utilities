package com.inzent.apim.utilities.http.client.connector.ht5;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import org.apache.hc.client5.http.impl.io.DefaultManagedHttpClientConnection;
import org.apache.hc.core5.function.Supplier;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.impl.io.SocketHolder;
import org.apache.hc.core5.http.io.SessionOutputBuffer;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.connector.java.JavaConnection;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.HttpHeaders;
import com.inzent.apim.utilities.http.client.model.WireMode;
import com.inzent.apim.utilities.http.client.spi.Http1Protocol;

/**
 * Apache HttpClient 5 managed writer; no high-level entity interceptor can repair raw headers.
 */
public final class Ht5Connection
		extends JavaConnection {
	private final ManagedConnection managed;

	public Ht5Connection(ConnectionConfigure configure,
	                     ExecutionContext context,
	                     java.util.concurrent.ExecutorService readers) throws Exception {
		super(configure,
		      context,
		      readers);
		this.managed = new ManagedConnection();
		try {
			this.managed.bind(new SocketHolder(this.socket) {
				@Override
				protected OutputStream getOutputStream(Socket socket) throws IOException {
					return Ht5Connection.this.captured(socket.getOutputStream());
				}
			});
		}
		catch (Throwable failure) {
			this.socket.close();
			throw failure;
		}
	}

	@Override
	protected void send(ExecutionContext context) throws Exception {
		boolean proxy = this.configure.proxy() != null && !"https".equalsIgnoreCase(context.url()
		                                                                                   .getScheme());
		BasicClassicHttpRequest request = new BasicClassicHttpRequest(context.method()
		                                                                     .token(),
		                                                              Http1Protocol.target(context.url(),
		                                                                                   proxy));
		request.setAbsoluteRequestUri(proxy);
		for (HttpHeaders.Entry header : context.request()
		                                       .headers()
		                                       .entries()) {
			request.addHeader(header.name(),
			                  header.value());
		}
		this.managed.raw = context.request()
		                          .wireMode() == WireMode.RAW_HTTP1 && !context.request()
		                                                                       .headers()
		                                                                       .contains("Transfer-Encoding");
		try (InputStream body = context.request()
		                               .body()
		                               .openStream()) {
			request.setEntity(new InputStreamEntity(body,
			                                        context.request()
			                                               .body()
			                                               .size(),
			                                        null));
			this.managed.sendRequestHeader(request);
			if (context.request()
			           .body()
			           .size() > 0 || context.request()
			                                 .headers()
			                                 .contains("Transfer-Encoding")) {
				this.managed.sendRequestEntity(request);
			}
			this.managed.flush();
		}
	}

	@Override
	public void close() throws IOException {
		this.managed.close();
	}

	private static final class ManagedConnection
			extends DefaultManagedHttpClientConnection {
		private boolean raw;

		private ManagedConnection() {
			super("http-client-ht5");
		}

		@Override
		protected OutputStream createContentOutputStream(long length,
		                                                 SessionOutputBuffer buffer,
		                                                 OutputStream output,
		                                                 Supplier<List<? extends Header>> trailers) {
			if (!this.raw) {
				return super.createContentOutputStream(length,
				                                       buffer,
				                                       output,
				                                       trailers);
			}
			return new OutputStream() {
				@Override
				public void write(int value) throws IOException {
					buffer.write(value,
					             output);
				}

				@Override
				public void write(byte[] bytes,
				                  int offset,
				                  int size) throws IOException {
					buffer.write(bytes,
					             offset,
					             size,
					             output);
				}

				@Override
				public void close() throws IOException {
					buffer.flush(output);
				}
			};
		}
	}
}
