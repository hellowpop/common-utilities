package com.inzent.apim.utilities.netty.http.mockserver.examples;

import com.inzent.apim.utilities.netty.http.mockserver.server.HttpMockServer;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Starts the embedded server, sends one request and returns control to the host JVM. */
public final class EmbeddedExample {
    private EmbeddedExample() {}
    public static void main(String[] args) throws Exception {
        try (HttpMockServer server=HttpMockServer.fromArguments(args)) {
            int port=server.start();
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                    .timeout(Duration.ofSeconds(3)).header("x-response-action","ok").GET().build();
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200 || !response.body().equals("OK"))
                throw new IllegalStateException("Unexpected mock response");
            System.out.println("Embedded response: " + response.statusCode() + " " + response.body());
        }
        System.out.println("Embedded server closed; host JVM continues");
    }
}
