package com.inzent.apim.utilities.http2mock;

import java.net.URI;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MockServicesControlController {
    private final MockServicesLifecycle services;
    public MockServicesControlController(MockServicesLifecycle services) { this.services = services; }

    @GetMapping("/test/service-engines")
    public ResponseEntity<List<MockServicesLifecycle.EngineState>> states() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(services.engineStates());
    }

    @PostMapping(value = "/test/services/{engine}/{action}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> control(@PathVariable String engine, @PathVariable String action, HttpServletRequest request) {
        if (!sameOrigin(request)) return error(403, "다른 출처에서는 Mock 서비스를 제어할 수 없습니다.");
        try {
            services.control(engine, action);
            return states();
        } catch (IllegalArgumentException invalid) {
            return error(400, "지원하지 않는 Mock 엔진 또는 작업입니다.");
        } catch (IllegalStateException failure) {
            return error(409, "작업을 완료하지 못했습니다. 엔진 상태, 포트와 서버 설정을 확인하세요.");
        }
    }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
    private boolean sameOrigin(HttpServletRequest request) {
        String header = request.getHeader("Origin");
        if (header == null) return !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
        try {
            URI origin = URI.create(header);
            URI target = URI.create(request.getRequestURL().toString());
            int port = origin.getPort() == -1 ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80) : origin.getPort();
            return origin.getHost() != null && origin.getScheme() != null && origin.getUserInfo() == null
                    && origin.getRawQuery() == null && origin.getRawFragment() == null && origin.getRawPath().isEmpty()
                    && origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost().equalsIgnoreCase(target.getHost()) && port == request.getServerPort();
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
