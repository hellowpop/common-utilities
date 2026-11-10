package com.inzent.apim.utilities.http2mock.invalid;

import com.inzent.apim.utilities.netty.http.mockserver.annotation.HttpResponseType;
import com.inzent.apim.utilities.netty.http.mockserver.processor.AnnotationProcessor;

@HttpResponseType("invalid-abstract")
public abstract class InvalidProcessor extends AnnotationProcessor { }
