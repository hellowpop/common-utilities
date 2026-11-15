package com.inzent.apim.utilities.http.caller;

import com.inzent.apim.utilities.http2mock.MockApplication;

/** Compatibility entry point; all services now belong to the Spring mock application. */
@Deprecated
public class HttpMockRequestCallerApplication {
    public static void main(String[] args){MockApplication.main(args);}
}
