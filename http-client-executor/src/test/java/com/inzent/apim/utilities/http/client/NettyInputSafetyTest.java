package com.inzent.apim.utilities.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.connector.netty.NettyConnection;
import com.inzent.apim.utilities.http.client.execution.ExecutionContext;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class NettyInputSafetyTest {
	@Test
	void timeoutDoesNotLeaveNullBuffer() throws Exception {
		this.withInput((input, socket) -> {
			Assertions.assertThrows(SocketTimeoutException.class,
			                        input::read);
			Assertions.assertThrows(SocketTimeoutException.class,
			                        input::read);
		});
	}

	@Test
	void eofRemainsEofOnSubsequentRead() throws Exception {
		this.withInput((input, socket) -> {
			socket.shutdownOutput();
			Assertions.assertEquals(-1,
			                        input.read());
			Assertions.assertEquals(-1,
			                        input.read());
		});
	}

	private void withInput(InputCheck check) throws Exception {
		ConnectionConfigure configure = ConnectionConfigure.builder()
		                                                   .responseIdleTimeout(Duration.ofMillis(100))
		                                                   .build();
		EventLoopGroup group = new MultiThreadIoEventLoopGroup(1,
		                                                       NioIoHandler.newFactory());
		ExecutorService readers = Executors.newSingleThreadExecutor();
		try (ServerSocket server = new ServerSocket(0);
		     HttpClientManager manager = HttpClientManager.create(configure);
		     HttpClientExecutor executor = manager.executorBuilder(ConnectorType.NETTY)
		                                          .build();
		     ExecutionContext context = ExecutionContext.builder(executor)
		                                                .url(URI.create("http://127.0.0.1:" + server.getLocalPort()))
		                                                .build()) {
			context.begin();
			try (NettyConnection connection = new NettyConnection(configure,
			                                                      context,
			                                                      group,
			                                                      readers);
			     Socket socket = server.accept()) {
				Field field = NettyConnection.class.getDeclaredField("input");
				field.setAccessible(true);
				Object inbound = field.get(connection);
				Method factory = inbound.getClass()
				                        .getDeclaredMethod("stream",
				                                           ExecutionContext.class);
				factory.setAccessible(true);
				InputStream input = (InputStream) factory.invoke(inbound,
				                                                 context);
				check.run(input,
				          socket);
			}
			finally {
				context.abortWith(null);
				context.finish(null);
			}
		}
		finally {
			readers.shutdownNow();
			group.shutdownGracefully(0,
			                         1,
			                         TimeUnit.SECONDS)
			     .awaitUninterruptibly();
		}
	}

	@FunctionalInterface
	private interface InputCheck {
		void run(InputStream input,
		         Socket socket) throws IOException;
	}
}
