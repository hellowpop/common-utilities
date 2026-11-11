package com.inzent.apim.utilities.http2mock;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class MockRequestExecutionController {
    private final MockRequestExecutionService executions;
    public MockRequestExecutionController(MockRequestExecutionService executions) { this.executions = executions; }

    @PostMapping(value = "/api/execute-mock-request", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(@RequestBody ExecuteMockRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Accel-Buffering", "no");
        return executions.execute(request);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException invalid) { return error(400, invalid.getMessage()); }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed() { return error(400, "JSON 필드 형식과 ContentType/ContentEncoding enum 값을 확인하세요."); }
    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<?> busy(RejectedExecutionException busy) { return error(429, "동시에 실행할 수 있는 작업 수를 초과했습니다."); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> stopped(IllegalStateException stopped) { return error(503, "백그라운드 실행기가 종료 중입니다."); }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }
}
