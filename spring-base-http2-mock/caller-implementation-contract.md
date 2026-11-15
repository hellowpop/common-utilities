# 이관 상태 (2026-11-15)

이 문서는 기존 caller의 요구사항·설계·개발 기록을 보존합니다. 현재 소유 프로젝트는 spring-base-http2-mock이며 별도 Boot 4 프로젝트나 library JAR은 사용하지 않습니다. 현재 실행법은 [caller 사용법](caller-README.md), 파일 배치와 변경 내역은 [프로젝트 구성](project.md), 전체 이관 기록은 [이관 기록](caller-migration.md)을 따릅니다. 아래의 과거 경로·버전·명령은 당시 기록입니다.

# Internal implementation contracts

Shared interfaces agreed before independent implementation. Java package root `com.inzent.apim.utilities.http.caller`.

## Transport/payload owner

- `payload.PayloadSpec` record `(boolean chunked, ContentType contentType, ContentEncoding contentEncoding, long contentLength, long blockSize)`.
- `transport.BodyStore implements AutoCloseable`: static `create(Path directory, long memoryThreshold, long maxBytes)`, `void write(byte[],int,int)`, `void finish()`, `InputStream openStream()`, `long size()`, `byte[] readBytes(int maxBytes)`, `Path moveTo(Path destination)`, `void close()`. After move caller owns destination; close does not delete it. Do not expose openStore to scripts. Validate limits, bounded memory/file promotion.
- `payload.PreparedPayload implements AutoCloseable`: accessors `Map<String,List<String>> headers()`, `BodyStore body()`.
- `payload.PayloadAdapter`: `PreparedPayload prepare(PayloadSpec spec, Path directory)` and `PreparedPayload override(PayloadSpec spec, byte[] rawBody, Path directory)`. Original prepare uses existing generator callback, copies inside callback. override honors type/encoding/chunked with byte[] raw override (bounded by script 1 MiB).
- `transport.TransportRequest` record `(URI uri, String method, Map<String,List<String>> headers, BodyStore body, boolean chunked)`; headers include Content-Type/Encoding from PreparedPayload, engine extras merged. Transport owns framing/Host and validates.
- `transport.TransportResponse implements AutoCloseable`: `int status()`, `Map<String,List<String>> headers()`, `BodyStore body()`, `long elapsedNanos()`, `long sentBytes()`. caller closes, body can be transferred out first.
- `transport.NettyTransport implements AutoCloseable`: constructor `(Path temporaryDirectory, long maxResponseBytes)` and synchronous `TransportResponse execute(TransportRequest request, Duration timeout) throws Exception`. No payload/file blocking on event loops. Thread interruption cancels channel, partial response may be discarded with explicit exception. TLS verifies hostname/chain. Initially connection per request permitted if safely bounded (document actual). App lifecycle closes transport after workers.
- Agent may add detailed overloads/helpers while preserving the above for integration. Netty modules pinned via POM 4.1.139.Final.

## Script/log owner

- `script.ScriptService implements AutoCloseable`: no-arg constructor, `void validate(String script)`, `void execute(String script, Map<String,Object> context, Duration timeout)`, `void close()`. Empty script no-op. Context values are safe maps/scalars or classes in script package only. Expose explicit facade types there if needed, document names. Errors throw RuntimeException. Disable loops/new/pragma/class/reflection and arbitrary methods. Tests cover modification of headers/maps/body through supported surface. Bounded executor with cooperative cancellation; degraded when slot fails to terminate.
- Prefer simple `script.ScriptMap` facade wrapping JSON-compatible Map with `get`, `put`, bean/property lookup. Root can use nested contexts `scenario`, `run`, `worker`, `path`, `exchange`, `scope` as safe maps, with `worker.vars.put(...)`, request headers map, request/response body as bounded String/byte[]. If wrapping required ScriptService wraps nested raw maps internally and copies mutations back.
- `logging.RunLogManager implements AutoCloseable`: static `open(Path directory, boolean enabled, long maxFileBytes, int maxFiles, boolean gzip)`; `void log(String stream, String message)` with stream `control` or `worker-0001` etc; `close()`. Filenames safely derived stream, UTF-8 records bounded to 16 KiB, maxFiles includes active file, rolling gzip, synchronized per stream, no disabled files. Synchronous off event-loop logging acceptable. Compression on caller worker acceptable bounded work.

## Build

POM parent Boot 4.1.2; Java 17; Netty BOM 4.1.139.Final; JEXL 3.7.1. `-Dcaller.build.directory=target-agent-name` separates test outputs. Tests can be run `mvn -f http-mock-request-caller/pom.xml -DskipTests=false -Dtest=SpecificTest -Dcaller.build.directory=target-agent-name test` after root announces dependencies available. Use JAVA_HOME D:/01.app/java/jdk-17.0.10.
