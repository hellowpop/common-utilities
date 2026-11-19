package com.inzent.apim.utilities.http.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.inzent.apim.utilities.http.client.config.ConnectionConfigure;
import com.inzent.apim.utilities.http.client.config.Ht5Options;
import com.inzent.apim.utilities.http.client.config.JavaOptions;
import com.inzent.apim.utilities.http.client.config.NettyOptions;
import com.inzent.apim.utilities.http.client.model.ConnectorType;

class ConnectorOptionsTest {
	@Test
	void onlyMatchingConnectorOptionsAreAccepted() throws Exception {
		try (HttpClientManager manager = HttpClientManager.create(ConnectionConfigure.defaults());
		     HttpClientExecutor apache = manager.executorBuilder(ConnectorType.HT5)
		                                        .ht5Options(new Ht5Options(1))
		                                        .build();
		     HttpClientExecutor netty = manager.executorBuilder(ConnectorType.NETTY)
		                                       .nettyOptions(new NettyOptions(3))
		                                       .build();
		     HttpClientExecutor java = manager.executorBuilder(ConnectorType.JAVA)
		                                      .javaOptions(new JavaOptions(4096))
		                                      .build()) {
			Assertions.assertEquals(1,
			                        apache.configure()
			                              .workerThreads());
			Assertions.assertEquals(3,
			                        netty.configure()
			                             .workerThreads());
			Assertions.assertEquals(4096,
			                        java.configure()
			                            .readBufferSize());
			Assertions.assertThrows(IllegalArgumentException.class,
			                        () -> manager.executorBuilder(ConnectorType.JAVA)
			                                     .nettyOptions(new NettyOptions(1)));
		}
	}
}
