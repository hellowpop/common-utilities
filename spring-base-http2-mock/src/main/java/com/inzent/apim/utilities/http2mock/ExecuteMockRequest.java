package com.inzent.apim.utilities.http2mock;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;
import com.inzent.apim.utilities.http.payload.ContentEncoding;
import com.inzent.apim.utilities.http.payload.ContentType;

public record ExecuteMockRequest(Integer threadCount, Long loopCount, List<Boolean> chunked, List<ContentType> contentType,
        List<ContentEncoding> contentEncoding, List<Long> contentLength, List<Long> blockSize, String endpointUrl, Map<String,String> headers) {
    public ExecuteMockRequest {
        chunked = copy(chunked); contentType = copy(contentType); contentEncoding = copy(contentEncoding);
        contentLength = copy(contentLength); blockSize = copy(blockSize);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
    public record Payload(boolean chunked, ContentType contentType, ContentEncoding contentEncoding,
                          long contentLength, long blockSize) { }
    long combinationCount() {
        long count = 1;
        for (var values : Arrays.asList(chunked, contentType, contentEncoding, contentLength, blockSize)) {
            if (values == null || values.isEmpty() || values.contains(null))
                throw new IllegalArgumentException("All payload fields must be non-empty arrays without null elements");
            try { count = Math.multiplyExact(count, values.size()); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("Payload combination count overflow"); }
        }
        return count;
    }
    // Mixed-radix indexing avoids allocating the Cartesian product. blockSize varies fastest.
    Payload payloadAt(long index) {
        long block = blockSize.get((int)(index % blockSize.size())); index /= blockSize.size();
        long length = contentLength.get((int)(index % contentLength.size())); index /= contentLength.size();
        var encoding = contentEncoding.get((int)(index % contentEncoding.size())); index /= contentEncoding.size();
        var type = contentType.get((int)(index % contentType.size())); index /= contentType.size();
        return new Payload(chunked.get(Math.toIntExact(index)), type, encoding, length, block);
    }
    long validate(MockExecutionProperties limits) {
        if (threadCount == null || threadCount < 1 || threadCount > limits.maxThreadCount())
            throw new IllegalArgumentException("threadCount must be 1.." + limits.maxThreadCount());
        if (loopCount == null || loopCount < 1) throw new IllegalArgumentException("loopCount must be positive");
        long total;
        try { total = Math.multiplyExact(Math.multiplyExact(threadCount.longValue(), loopCount), combinationCount()); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Total request count overflow"); }
        if (total > limits.maxTotalRequests()) throw new IllegalArgumentException("Total requests exceed " + limits.maxTotalRequests());
        int minimum = contentType.stream().mapToInt(ContentType::minimumLength).max().orElseThrow();
        if (contentLength.stream().anyMatch(length -> length < 0 || length > limits.maxContentLength()
                || (length != 0 && length < minimum)) || blockSize.stream().anyMatch(block -> block < 1))
            throw new IllegalArgumentException("Invalid contentLength or blockSize for selected ContentType");
        URI endpoint;
        try { endpoint = URI.create(Objects.requireNonNull(endpointUrl)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("A valid endpointUrl is required"); }
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535)
            throw new IllegalArgumentException("endpointUrl must be an absolute HTTP/HTTPS URL without user info or fragment");
        var request = HttpRequest.newBuilder(endpoint);
        if (headers.size() > 32) throw new IllegalArgumentException("At most 32 additional request headers are supported");
        var reserved = Set.of("content-type", "content-encoding", "content-length", "transfer-encoding", "host", "connection", "expect", "upgrade");
        headers.forEach((name, value) -> {
            if (reserved.contains(name.toLowerCase(Locale.ROOT)) || name.length() > 256 || value.length() > 8192)
                throw new IllegalArgumentException("Reserved or oversized request header: " + name);
            request.header(name, value);
        });
        return total;
    }
}
