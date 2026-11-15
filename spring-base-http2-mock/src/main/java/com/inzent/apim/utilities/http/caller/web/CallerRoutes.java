package com.inzent.apim.utilities.http.caller.web;

import java.util.Set;

/** Route ownership shared by the caller security filter and embedding applications. */
public final class CallerRoutes {
    private CallerRoutes() {}

    public static boolean isApi(String path) {
        return Set.of("/api/csrf", "/api/example", "/api/status", "/api/scenarios", "/api/runs").contains(path)
                || path.startsWith("/api/scenarios/") || path.startsWith("/api/runs/");
    }
}
