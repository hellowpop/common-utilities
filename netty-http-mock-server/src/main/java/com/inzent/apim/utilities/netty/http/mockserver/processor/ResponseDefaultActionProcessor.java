package com.inzent.apim.utilities.netty.http.mockserver.processor;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.response.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;

/** Provides usage guidance when the configured action header is absent. */
@HttpResponseType("default")
public final class ResponseDefaultActionProcessor extends AnnotationProcessor {
    private static final String GUIDANCE="""
            HTTP Mock Server가 실행 중입니다.
            설정된 action 헤더(기본값: x-response-action)를 지정해 모의 응답을 선택하세요.
            기본 제공 action: default, ok, copy-and-send, status, error-500, close, fake-close, delay-close.
            status: x-status 헤더에 응답 상태 코드(200~599)를 지정하세요.
            delay-close: x-delay-amount 헤더에 지연 시간(milliseconds)을 지정하세요.
            """;

    @Override public ActionResult process(Channel channel,FullHttpRequest request) {
        return new ActionResult.SendResponse(Responses.text(200,GUIDANCE));
    }
}
