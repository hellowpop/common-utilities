package com.inzent.apim.utilities.netty.http.mockserver.annotation;

import java.lang.annotation.*;

/** The exact request header value handled by the annotated processor. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpResponseType {
    String value();
}
