package com.inzent.apim.utilities.http.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TunnelProxy
		implements AutoCloseable {
	record Seen(String headers,
	            String remoteHost) {
	}

	final AtomicInteger connections = new AtomicInteger();
	final LinkedBlockingQueue<Seen> requests = new LinkedBlockingQueue<>();
	private final ServerSocket listener = new ServerSocket(0);
	private final List<Socket> sockets = new CopyOnWriteArrayList<>();
	private final ExecutorService workers = Executors.newCachedThreadPool();
	private final int targetPort;
	private final String requiredAuthorization;
	private volatile boolean closed;

	TunnelProxy(int targetPort,
	            String requiredAuthorization) throws IOException {
		this.targetPort = targetPort;
		this.requiredAuthorization = requiredAuthorization;
		this.workers.submit(() -> {
			while (!this.closed) {
				try {
					Socket socket = this.listener.accept();
					this.sockets.add(socket);
					this.connections.incrementAndGet();
					this.workers.submit(() -> this.serve(socket));
				}
				catch (IOException failure) {
					if (!this.closed) {
						throw new java.io.UncheckedIOException(failure);
					}
				}
			}
		});
	}

	int port() {
		return this.listener.getLocalPort();
	}

	private void serve(Socket incoming) {
		try (incoming) {
			incoming.setSoTimeout(5000);
			String headers = new String(Http1IntegrationTest.readHeaders(incoming.getInputStream()),
			                            StandardCharsets.ISO_8859_1);
			this.requests.add(new Seen(headers,
			                           incoming.getInetAddress()
			                                   .getHostAddress()));
			if (this.requiredAuthorization != null && !headers.toLowerCase(java.util.Locale.ROOT)
			                                                  .contains(("Proxy-Authorization: " + this.requiredAuthorization + "\r\n").toLowerCase(java.util.Locale.ROOT))) {
				incoming.getOutputStream()
				        .write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"fixture\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				return;
			}
			try (Socket outgoing = new Socket("127.0.0.1",
			                                  this.targetPort)) {
				this.sockets.add(outgoing);
				outgoing.setSoTimeout(5000);
				incoming.getOutputStream()
				        .write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				this.workers.submit(() -> {
					try {
						incoming.getInputStream()
						        .transferTo(outgoing.getOutputStream());
						outgoing.shutdownOutput();
					}
					catch (IOException ignored) {
					}
				});
				outgoing.getInputStream()
				        .transferTo(incoming.getOutputStream());
			}
		}
		catch (Exception ignored) { /* Connection teardown is expected when a client cancels or closes. */ }
	}

	@Override
	public void close() throws IOException {
		this.closed = true;
		this.listener.close();
		for (Socket socket : this.sockets) {
			try {
				socket.close();
			}
			catch (IOException ignored) {
			}
		}
		this.workers.shutdownNow();
		try {
			this.workers.awaitTermination(3,
			                              TimeUnit.SECONDS);
		}
		catch (InterruptedException failure) {
			Thread.currentThread()
			      .interrupt();
		}
	}
}
