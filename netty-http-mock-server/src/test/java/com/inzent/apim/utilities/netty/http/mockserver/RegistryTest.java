package com.inzent.apim.utilities.netty.http.mockserver;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.processor.*;
import com.inzent.apim.utilities.netty.http.mockserver.registry.ActionRegistry;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class RegistryTest {
    static final String PROCESSORS="com.inzent.apim.utilities.netty.http.mockserver.processor";
    public static class ProbeProcessor extends AnnotationProcessor {
        public ActionResult process(Channel c,FullHttpRequest r) {
            return new ActionResult.SendResponse(Responses.text(200,"probe"));
        }
    }
    @HttpResponseType("renamed") public static class DirectProcessor extends ProbeProcessor {}
    public static class UnannotatedChild extends DirectProcessor {}
    @HttpResponseType("renamed") public static class DuplicateProcessor extends ProbeProcessor {}
    @HttpResponseType("bad value") public static class BadValueProcessor extends ProbeProcessor {}
    @HttpResponseType("wrong") public static class NotAProcessor {}
    @HttpResponseType("abstract") public abstract static class AbstractProcessor extends ProbeProcessor {}
    @HttpResponseType("no-ctor") public static class NoConstructor extends ProbeProcessor { public NoConstructor(int ignored) {} }
    @HttpResponseType("throws") public static class FailingConstructor extends ProbeProcessor {
        public FailingConstructor() { throw new IllegalStateException("Cannot initialize"); }
    }
    @Test void discoversAllBuiltinProcessorTypesFromDirectory() {
        var registry=ActionRegistry.discover(List.of(PROCESSORS));
        assertEquals(8,registry.names().size());
        assertTrue(registry.names().containsAll(List.of("default","ok","copy-and-send","error-500","close","fake-close","delay-close","status")));
        assertFalse(registry.names().contains("copy"));
        assertThrows(IllegalArgumentException.class,() -> ActionRegistry.discover(List.of("")));
    }
    @Test void bindsOnlyDirectHeaderMetadataWithoutStereotypes() {
        var registry=ActionRegistry.ofClasses(List.of(DirectProcessor.class,ProbeProcessor.class,UnannotatedChild.class));
        assertEquals(java.util.Set.of("renamed"),registry.names());
        assertEquals("probe",executeText(registry,"renamed"));
    }
    @Test void rejectsDuplicateAndMalformedHeaderValues() {
        assertThrows(IllegalArgumentException.class,() -> ActionRegistry.ofClasses(List.of(DirectProcessor.class,DuplicateProcessor.class)));
        assertThrows(IllegalArgumentException.class,() -> ActionRegistry.ofClasses(List.of(BadValueProcessor.class)));
    }
    @Test void rejectsInvalidProcessorsAtStartup() {
        for (Class<?> invalid:List.of(NotAProcessor.class,AbstractProcessor.class,NoConstructor.class,FailingConstructor.class)) {
            assertThrows(IllegalArgumentException.class,() -> ActionRegistry.ofClasses(List.of(invalid)),invalid.getName());
        }
    }
    @Test void supportsDirectBuiltinProcessorRegistration() {
        var registry=ActionRegistry.ofClasses(List.of(ResponseOkActionProcessor.class,ResponseCopyAndSendActionProcessor.class));
        assertEquals(java.util.Set.of("ok","copy-and-send"),registry.names());
        assertEquals("OK",executeText(registry,"ok"));
    }
    private static String executeText(ActionRegistry registry,String action) {
        var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
        var channel=new EmbeddedChannel();
        try {
            var response=((ActionResult.SendResponse)registry.execute(action,channel,request)).response();
            try { return response.content().toString(java.nio.charset.StandardCharsets.UTF_8); }
            finally { response.release(); }
        } finally { request.release(); channel.finishAndReleaseAll(); }
    }
    @Test void copyPreservesBytesWithIndependentOwnership() {
        var registry=ActionRegistry.discover(List.of(PROCESSORS));
        var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/",Unpooled.wrappedBuffer(new byte[]{0,1,(byte)255}));
        var channel=new EmbeddedChannel();
        var result=(ActionResult.SendResponse)registry.execute("copy-and-send",channel,request);
        request.release();
        try {
            assertArrayEquals(new byte[]{0,1,(byte)255},io.netty.buffer.ByteBufUtil.getBytes(result.response().content()));
            assertEquals("application/octet-stream",result.response().headers().get(HttpHeaderNames.CONTENT_TYPE));
        } finally { result.response().release(); channel.finishAndReleaseAll(); }
    }
    @Test void delayValidationAndOutcome() {
        var registry=ActionRegistry.discover(List.of(PROCESSORS)); var channel=new EmbeddedChannel();
        var request=new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
        try {
            request.headers().set("x-delay-amount","1500");
            assertEquals(1500,((ActionResult.DelayCloseConnection)registry.execute("delay-close",channel,request)).delayMillis());
            for (String value:List.of("","-1","+1","1.5","1ms","1,2","2147483648","999999999999999999999999")) {
                request.headers().set("x-delay-amount",value);
                assertThrows(IllegalArgumentException.class,() -> registry.execute("delay-close",channel,request),value);
            }
            request.headers().remove("x-delay-amount");
            assertThrows(IllegalArgumentException.class,() -> registry.execute("delay-close",channel,request));
            request.headers().add("x-delay-amount","1").add("x-delay-amount","2");
            assertThrows(IllegalArgumentException.class,() -> registry.execute("delay-close",channel,request));
        } finally { request.release(); channel.finishAndReleaseAll(); }
    }
}
