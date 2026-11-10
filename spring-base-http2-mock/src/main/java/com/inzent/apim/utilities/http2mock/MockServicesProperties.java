package com.inzent.apim.utilities.http2mock;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import com.inzent.apim.utilities.http.mockserver.MockOptions;
import com.inzent.apim.utilities.http.mockserver.MockResponse;
import com.inzent.apim.utilities.http.mockserver.HttpProtocolLimits;

@ConfigurationProperties(prefix = "mock.services", ignoreUnknownFields = false)
public record MockServicesProperties(@DefaultValue Netty netty, @DefaultValue Response response) {
    public record Netty(@DefaultValue("false") boolean enabled,
                        @DefaultValue("classpath:netty-mock.yml") String config, List<String> arguments) {
        public Netty { arguments = arguments == null ? List.of() : List.copyOf(arguments); }
    }
    public record Response(@DefaultValue("false") boolean enabled, @DefaultValue("64") int maxClients,
                           @DefaultValue("5s") Duration shutdownTimeout, List<Binding> bindings) {
        public Response { bindings = bindings == null ? List.of() : List.copyOf(bindings); }
    }
    public enum Scenario { REPLY, WAIT_FOR_CLIENT_DISCONNECT, SLOW_READ, CLOSE }
    public record Binding(String name, @DefaultValue("REPLY") Scenario scenario,
                          @DefaultValue("127.0.0.1") String host, int port,
                          @DefaultValue("0ms") Duration delay, @DefaultValue Options options, @DefaultValue Reply reply) { }

    public record Options(@DefaultValue("true") boolean reuseAddress, @DefaultValue("50") int backlog,
                          Integer serverReceiveBufferSize, Integer receiveBufferSize, Integer sendBufferSize,
                          @DefaultValue("-1") int lingerSeconds, @DefaultValue("false") boolean keepAlive,
                          @DefaultValue("true") boolean tcpNoDelay, @DefaultValue("0") int readTimeoutMillis,
                          @DefaultValue("1024") int readChunkSize, @DefaultValue("100ms") Duration readInterval,
                          @DefaultValue("10s") Duration responseTimeout) {
        MockOptions toOptions() {
            return new MockOptions(reuseAddress, backlog, serverReceiveBufferSize, receiveBufferSize, sendBufferSize,
                    lingerSeconds, keepAlive, tcpNoDelay, readTimeoutMillis, readChunkSize, readInterval, responseTimeout);
        }
    }
    public record Reply(@DefaultValue("200") int status, @DefaultValue("text/plain; charset=UTF-8") String contentType,
                        @DefaultValue("OK") String body, @DefaultValue("8192") int maxLineBytes,
                        @DefaultValue("65536") int maxHeaderBytes, @DefaultValue("16777216") int maxContentBytes) {
        MockResponse toResponse() { return MockResponse.utf8(status, contentType, body); }
        HttpProtocolLimits limits() { return new HttpProtocolLimits(maxLineBytes, maxHeaderBytes, maxContentBytes); }
    }
}
