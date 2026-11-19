package com.inzent.apim.utilities.http.client.spi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.connector.ht5.Ht5Connection;
import com.inzent.apim.utilities.http.client.connector.ht5.Ht5Http2Connection;
import com.inzent.apim.utilities.http.client.connector.java.JavaConnection;
import com.inzent.apim.utilities.http.client.connector.netty.NettyConnection;
import com.inzent.apim.utilities.http.client.connector.netty.NettyHttp2Connection;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectVersion;
import com.inzent.apim.utilities.http.client.model.ConnectorType;
import com.inzent.apim.utilities.http.client.model.WireMode;

/**
 * Strict physical budgets with independent HTTP/2 stream reservations.
 */
public final class ConnectionPool
		implements AutoCloseable {
	public static final class Lease {
		private final Entry entry;
		private final boolean reused;
		private boolean released;

		private Lease(Entry entry,
		              boolean reused) {
			this.entry = entry;
			this.reused = reused;
		}

		public ClientConnection connection() {
			return this.entry.connection;
		}

		public boolean reused() {
			return this.reused;
		}
	}

	private static final class Entry {
		private final ClientConnection connection;
		private final String route;
		private final long created = System.nanoTime();
		private long idleSince = this.created;
		private int active;
		private boolean used;
		private boolean draining;

		private Entry(ClientConnection connection,
		              String route) {
			this.connection = connection;
			this.route = route;
		}
	}

	private final ConnectorType type;
	private final ConnectionConfigure configure;
	private final List<Entry> entries = new ArrayList<>();
	private final Map<String, Integer> routes = new HashMap<>();
	private final ExecutorService readers = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task,
		                           "http-client-response");
		thread.setDaemon(true);
		return thread;
	});
	private EventLoopGroup group;
	private int allocated;
	private boolean closed;

	public ConnectionPool(ConnectorType type,
	                      ConnectionConfigure configure) {
		this.type = type;
		this.configure = configure;
	}

	public Lease acquire(ExecutionContext context) throws Exception {
		String route = context.url()
		                      .getScheme()
		                      .toLowerCase(Locale.ROOT) + "://" + context.url()
		                                                                 .getHost()
		                                                                 .toLowerCase(Locale.ROOT) + ":" + Http1Protocol.port(context.url()) + "/" + context.version();
		long end = System.nanoTime() + this.configure.poolAcquireTimeout()
		                                             .toNanos();
		while (true) {
			synchronized (this) {
				context.checkCancelled();
				if (this.closed) {
					throw new IllegalStateException("Pool closed");
				}
				this.evict();
				if (context.request()
				           .wireMode() != WireMode.RAW_HTTP1) {
					for (int i = 0; i < this.entries.size(); i++) {
						int index = this.configure.leaseOrder() == ConnectionConfigure.LeaseOrder.FIFO
						            ? i
						            : this.entries.size() - 1 - i;
						Entry entry = this.entries.get(index);
						int capacity = entry.connection.multiplexed()
						               ? Math.min(this.configure.maxConcurrentStreamsPerConnection(),
						                          entry.connection.streamCapacity())
						               : 1;
						if (entry.route.equals(route) && !entry.draining && entry.active < capacity) {
							return this.reserve(entry,
							                    context);
						}
					}
				}
				if (this.allocated < this.configure.maxConnTotal() && this.routes.getOrDefault(route,
				                                                                               0) < this.configure.maxConnPerRoute()) {
					this.allocated++;
					this.routes.merge(route,
					                  1,
					                  Integer::sum);
				} else {
					Entry idle = this.entries.stream()
					                         .filter(entry -> entry.active == 0 && (this.allocated == this.configure.maxConnTotal() || entry.route.equals(route)))
					                         .findFirst()
					                         .orElse(null);
					if (idle != null && (context.request()
					                            .wireMode() == WireMode.RAW_HTTP1 || !idle.route.equals(route))) {
						this.discard(idle);
						continue;
					}
					long remaining = Math.min(end - System.nanoTime(),
					                          TimeUnit.MILLISECONDS.toNanos(context.remainingMillis()));
					if (remaining <= 0) {
						throw new java.net.SocketTimeoutException("Pool/stream acquire timeout");
					}
					try {
						TimeUnit.NANOSECONDS.timedWait(this,
						                               Math.min(remaining,
						                                        TimeUnit.MILLISECONDS.toNanos(20)));
					}
					catch (InterruptedException failure) {
						Thread.currentThread()
						      .interrupt();
						context.cancel();
						throw new IOException("Interrupted acquiring connection",
						                      failure);
					}
					continue;
				}
			}
			ClientConnection connection = null;
			try {
				connection = this.open(context);
				if (connection.multiplexed()) {
					context.abortWith(() -> {
					});
				}
				context.checkCancelled();
				synchronized (this) {
					if (this.closed) {
						throw new IOException("Pool closed during connect");
					}
					Entry entry = new Entry(connection,
					                        route);
					this.entries.add(entry);
					this.notifyAll();
					if (!connection.multiplexed() || connection.streamCapacity() > 0) {
						return this.reserve(entry,
						                    context);
					}
				}
			}
			catch (Throwable failure) {
				if (connection != null) {
					closeQuietly(connection);
				}
				synchronized (this) {
					this.allocated--;
					this.decrementRoute(route);
					this.notifyAll();
				}
				throw failure;
			}
		}
	}

	private Lease reserve(Entry entry,
	                      ExecutionContext context) {
		Lease lease = new Lease(entry,
		                        entry.used);
		entry.active++;
		entry.used = true;
		// Shared transports are cancelled by their exchange's stream future, never by a physical lease callback.
		if (entry.connection.multiplexed()) {
			context.abortWith(() -> {
			});
		} else {
			context.abortWith(() -> closeQuietly(entry.connection));
		}
		return lease;
	}

	public synchronized void release(Lease lease,
	                                 boolean successful) {
		if (lease.released) {
			throw new IllegalStateException("Lease already released");
		}
		lease.released = true;
		Entry entry = lease.entry;
		entry.active--;
		if (!successful && !entry.connection.multiplexed()) {
			entry.draining = true;
		}
		if (!entry.connection.reusable()) {
			entry.draining = true;
		}
		if (this.closed) {
			return;
		}
		if (entry.active == 0) {
			if (entry.draining || System.nanoTime() - entry.created >= this.configure.timeToLive()
			                                                                         .toNanos()) {
				this.discard(entry);
			} else {
				entry.idleSince = System.nanoTime();
				this.entries.remove(entry);
				this.entries.add(entry);
			}
		}
		this.notifyAll();
	}

	private void evict() {
		long now = System.nanoTime();
		for (Entry entry : List.copyOf(this.entries)) {
			if (now - entry.created >= this.configure.timeToLive()
			                                         .toNanos() || (entry.used && !entry.connection.reusable() && (entry.active == 0 || entry.connection.multiplexed()))) {
				entry.draining = true;
			}
			if (entry.active == 0 && (entry.draining || now - entry.idleSince >= this.configure.idleTimeout()
			                                                                                   .toNanos())) {
				this.discard(entry);
			}
		}
	}

	private void discard(Entry entry) {
		if (this.entries.remove(entry)) {
			closeQuietly(entry.connection);
			this.allocated--;
			this.decrementRoute(entry.route);
		}
	}

	private void decrementRoute(String route) {
		int value = this.routes.getOrDefault(route,
		                                     1) - 1;
		if (value == 0) {
			this.routes.remove(route);
		} else {
			this.routes.put(route,
			                value);
		}
	}

	private ClientConnection open(ExecutionContext context) throws Exception {
		if (context.version() == ConnectVersion.HTTP_2) {
			return switch (this.type) {
				case HT5 -> new Ht5Http2Connection(this.configure,
				                                   context);
				case NETTY -> new NettyHttp2Connection(this.configure,
				                                       context,
				                                       this.group());
				case JAVA -> throw new IllegalArgumentException("JAVA does not support HTTP/2");
			};
		}
		return switch (this.type) {
			case JAVA -> new JavaConnection(this.configure,
			                                context,
			                                this.readers);
			case HT5 -> new Ht5Connection(this.configure,
			                              context,
			                              this.readers);
			case NETTY -> new NettyConnection(this.configure,
			                                  context,
			                                  this.group(),
			                                  this.readers);
		};
	}

	private synchronized EventLoopGroup group() {
		if (this.closed) {
			throw new IllegalStateException("Pool closed");
		}
		if (this.group == null) {
			this.group = new MultiThreadIoEventLoopGroup(this.configure.workerThreads(),
			                                             NioIoHandler.newFactory());
		}
		return this.group;
	}

	private static void closeQuietly(ClientConnection connection) {
		try {
			connection.close();
		}
		catch (IOException ignored) {
		}
	}

	@Override
	public void close() {
		EventLoopGroup threads;
		List<Entry> snapshot;
		synchronized (this) {
			if (this.closed) {
				return;
			}
			this.closed = true;
			snapshot = List.copyOf(this.entries);
			this.entries.clear();
			this.routes.clear();
			this.allocated = 0;
			threads = this.group;
			this.notifyAll();
		}
		snapshot.forEach(entry -> closeQuietly(entry.connection));
		if (threads != null) {
			threads.shutdownGracefully(0,
			                           this.configure.shutdownTimeout()
			                                         .toMillis(),
			                           TimeUnit.MILLISECONDS)
			       .awaitUninterruptibly();
		}
		this.readers.shutdownNow();
		try {
			this.readers.awaitTermination(this.configure.shutdownTimeout()
			                                            .toMillis(),
			                              TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException failure) {
			Thread.currentThread()
			      .interrupt();
		}
	}
}
