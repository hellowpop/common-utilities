package com.inzent.apim.utilities.http.curl;

import java.io.IOException;

/** Distinguishes a server ending an upload from a failure reading the prepared body. */
final class UploadPipeException extends IOException {
    UploadPipeException(IOException cause) { super("Curl upload pipe closed",cause); }
}
