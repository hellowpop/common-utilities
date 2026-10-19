package com.inzent.apim.utilities.http.curl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** Internal file-output boundary for spilling an encoded entity. */
@FunctionalInterface
interface BodyFileFactory {
    OutputStream open(Path path) throws IOException;
}
