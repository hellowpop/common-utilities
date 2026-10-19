package com.inzent.apim.utilities.http.curl;

import java.io.Closeable;
import java.io.IOException;

final class Cleanup {
    private Cleanup() { }
    static IOException merge(IOException first, IOException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    static Throwable merge(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    static Throwable attempt(Throwable first, Closeable action) {
        try { action.close(); return first; }
        catch (Throwable next) { return merge(first,next); }
    }
    static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException e) throw e;
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
        if (failure != null) throw new IOException("Resource cleanup failed",failure);
    }
}
