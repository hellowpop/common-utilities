package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.processor.AnnotationProcessor;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class ProcessorApiTest {
    @HttpResponseType("direct")
    public static class DirectProcessor extends AnnotationProcessor {
        @Override public ActionResult process(Channel channel,FullHttpRequest request) {
            return new ActionResult.SendResponse(Responses.text(200,request.uri()));
        }
    }
    @Test void processorNeedsOnlyHeaderBindingAndRequestProcessingMethod() {
        var registry=ActionRegistry.ofClasses(List.of(DirectProcessor.class));
        var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/direct-response");
        var channel=new EmbeddedChannel();
        try {
            var result=(ActionResult.SendResponse)registry.execute("direct",channel,request);
            try { assertEquals("/direct-response",result.response().content().toString(java.nio.charset.StandardCharsets.UTF_8)); }
            finally { result.response().release(); }
        } finally { request.release(); channel.finishAndReleaseAll(); }
    }
}
