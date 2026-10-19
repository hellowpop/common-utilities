package com.inzent.apim.utilities.http.curl;

import io.netty.handler.codec.http.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Exact long-option grammar: only options compatible with a single recorded transfer. */
final class CurlArguments {
    private static final Set<String> FLAGS = Set.of("--tlsv1.2", "--tlsv1.3", "--ipv4", "--ipv6");
    private static final Set<String> VALUES = Set.of("--cacert", "--capath", "--cert", "--key", "--pass",
        "--resolve", "--connect-to", "--interface", "--limit-rate", "--proxy", "--noproxy", "--proxy-user",
        "--user", "--user-agent", "--referer");
    private static final Set<String> REPEATABLE = Set.of("--resolve", "--connect-to");
    private static final Set<String> SECRET = Set.of("--user", "--proxy-user", "--pass", "--cert");
    private CurlArguments() { }

    static void validate(ExecuteContext context) {
        URI url = context.url();
        if (url == null) throw new IllegalArgumentException("url is required");
        if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
            || url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null)
            throw new IllegalArgumentException("A single HTTP(S) URL without user-info or fragment is required");
        checkToken(url.toString());
        if (!Set.of(HttpMethod.POST,HttpMethod.PUT,HttpMethod.PATCH,HttpMethod.DELETE,HttpMethod.GET,HttpMethod.HEAD).contains(context.method()))
            throw new IllegalArgumentException("Unsupported HTTP method");
        if (context.contentLength() < 0 || context.blockSize() <= 0
            || (context.contentLength() > 0 && context.contentLength() < context.contentType().minimumLength()))
            throw new IllegalArgumentException("Invalid contentLength or blockSize");
        if (noBody(context) && (context.contentLength() != 0 || context.chunked()
                || context.contentEncoding() != com.inzent.apim.utilities.http.payload.ContentEncoding.IDENTITY))
            throw new IllegalArgumentException("GET/HEAD requires an empty, unencoded, non-chunked body");
        Set<String> seen = new HashSet<>();
        List<String> args = context.configure().additionalArguments();
        for (int i=0; i<args.size(); i++) {
            String option = args.get(i); checkToken(option);
            if (!FLAGS.contains(option) && !VALUES.contains(option))
                throw new IllegalArgumentException("Unsupported or managed curl argument: " + option);
            if (!seen.add(option) && !REPEATABLE.contains(option)) throw new IllegalArgumentException("Duplicate curl option: " + option);
            if (VALUES.contains(option)) {
                if (++i == args.size()) throw new IllegalArgumentException("Missing value for " + option);
                String value = args.get(i); checkToken(value);
                if (value.isEmpty() && !option.equals("--noproxy") && !option.equals("--pass"))
                    throw new IllegalArgumentException("Empty value for " + option);
                if ((option.equals("--user") || option.equals("--proxy-user")) && value.indexOf(':') < 0)
                    throw new IllegalArgumentException("Non-interactive user:password required for " + option);
            }
        }
        if (seen.contains("--ipv4") && seen.contains("--ipv6") || seen.contains("--tlsv1.2") && seen.contains("--tlsv1.3"))
            throw new IllegalArgumentException("Mutually exclusive curl arguments");
    }
    static void validateHeaderArguments(ExecuteContext context, HttpHeaders headers) {
        List<String> args = context.configure().additionalArguments();
        for (int i=0; i<args.size(); i++) {
            String option = args.get(i);
            if (option.equals("--user-agent") && headers.contains(HttpHeaderNames.USER_AGENT)
                || option.equals("--referer") && headers.contains(HttpHeaderNames.REFERER))
                throw new IllegalArgumentException("Duplicate header source for " + option);
            if (VALUES.contains(option)) i++;
        }
    }
    static boolean noBody(ExecuteContext context) { return context.method().equals(HttpMethod.HEAD) || context.method().equals(HttpMethod.GET); }
    static void checkToken(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid control character in argument");
    }
    static List<String> build(ExecuteContext context, HttpHeaders headers) {
        var config = context.configure();
        List<String> args = new ArrayList<>(List.of(config.curlExecutable().toString(), "--disable",
            "--proxy", "", "--noproxy", ""));
        args.addAll(config.additionalArguments());
        args.addAll(List.of("--http1.1", "--globoff", "--proto", "=http,https", "--verbose", "--silent", "--show-error", "--no-buffer",
            config.verifyCertificate() ? "--no-insecure" : "--insecure",
            "--max-time", seconds(context.responseTimeout()), "--connect-timeout",
            seconds(config.connectTimeout().compareTo(context.responseTimeout()) > 0 ? context.responseTimeout() : config.connectTimeout())));
        if (context.method().equals(HttpMethod.HEAD)) args.add("--head");
        else {
            args.addAll(List.of("--request", context.method().name()));
            if (!CurlArguments.noBody(context)) args.addAll(List.of("--upload-file", "-"));
        }
        for (var entry : headers) args.addAll(List.of("--header", entry.getValue().isEmpty()
            ? entry.getKey()+";" : entry.getKey()+": "+entry.getValue()));
        if (!CurlArguments.noBody(context)) args.addAll(List.of("--header", context.chunked() ? "Content-Length:" : "Transfer-Encoding:"));
        args.addAll(List.of("--header", "Expect:", "--url", context.url().toASCIIString()));
        return List.copyOf(args);
    }
    static String seconds(Duration duration) { return BigDecimal.valueOf(duration.toNanos(),9).stripTrailingZeros().toPlainString(); }
    static List<String> mask(List<String> args) {
        List<String> copy = new ArrayList<>(args);
        for (int i=1; i<copy.size()-1; i++) {
            String option = copy.get(i);
            if (SECRET.contains(option) || option.equals("--proxy") && copy.get(i+1).contains("@")) copy.set(++i,"***");
            else if (VALUES.contains(option) || Set.of("--header","--url","--max-time","--connect-timeout","--request","--upload-file","--proto").contains(option)) i++;
        }
        return List.copyOf(copy);
    }
}
