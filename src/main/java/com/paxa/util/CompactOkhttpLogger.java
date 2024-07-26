package com.paxa.util;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Connection;
import okhttp3.Protocol;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import okio.Buffer;
import okio.BufferedSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/*
Http logger that print 1 line for request and 1 line for response, to save TPS in barito

https://github.com/square/okhttp/blob/master/okhttp-logging-interceptor/src/main/kotlin/okhttp3/logging/HttpLoggingInterceptor.kt
 */

public class CompactOkhttpLogger implements Interceptor {
    private final Logger logger;
    private boolean logHeaders = false;
    private boolean logBody = false;
    private boolean logAsDebug = false;
    private CompactOkhttpLogger.FailureFilterFn logOnlyFailuresFn;
    private final Set<String> headersToRedact = new HashSet<>();
    private final Set<String> onlyHeaders = new HashSet<>();
    private final Set<String> skipHeaders = new HashSet<>();

    public interface FailureFilterFn {
        boolean isFailure(Response response, boolean hasError);
    }

    public CompactOkhttpLogger(Logger logger, boolean logHeaders, boolean logBody) {
        if (logger == null) {
            throw new RuntimeException("Logger parameter can not be null");
        }
        this.logger = logger;
        this.logHeaders = logHeaders;
        this.logBody = logBody;
    }

    public CompactOkhttpLogger(Class<?> clazz, Boolean logHeaders, Boolean logBody) {
        this(LoggerFactory.getLogger(clazz), logHeaders, logBody);
    }

    public CompactOkhttpLogger(Class<?> clazz) {
        this(clazz, false, false);
    }

    public Boolean logHeaders() { return logHeaders; }
    public Boolean logBody() { return logBody; }
    public Logger logger() { return logger; }

    public CompactOkhttpLogger withBody() {
        this.logBody = true;
        return this;
    }

    public CompactOkhttpLogger withHeaders() {
        this.logHeaders = true;
        return this;
    }

    public CompactOkhttpLogger redactHeaders(String ...headers) {
        for (String header : headers) {
            headersToRedact.add(header.toLowerCase());
        }
        return this;
    }

    public CompactOkhttpLogger onlyHeaders(String ...headers) {
        logHeaders = true;
        for (String header : headers) {
            onlyHeaders.add(header.toLowerCase());
        }
        return this;
    }

    public CompactOkhttpLogger skipHeaders(String ...headers) {
        logHeaders = true;
        for (String header : headers) {
            skipHeaders.add(header.toLowerCase());
        }
        return this;
    }

    public CompactOkhttpLogger addHeaders(String ...headers) {
        logHeaders = true;
        for (String header : headers) {
            skipHeaders.remove(header);
        }
        return this;
    }

    public CompactOkhttpLogger skipCommonHeaders() {
        logHeaders = true;
        skipHeaders.add("server");
        skipHeaders.add("date");
        skipHeaders.add("user-agent");
        skipHeaders.add("content-type");
        skipHeaders.add("content-length");
        skipHeaders.add("connection");
        skipHeaders.add("transfer-encoding");
        skipHeaders.add("vary");
        skipHeaders.add("strict-transport-security");
        skipHeaders.add("expires");
        skipHeaders.add("x-powered-by");
        skipHeaders.add("x-frame-options");
        skipHeaders.add("x-xss-protection");
        skipHeaders.add("x-content-type-options");
        skipHeaders.add("accept");
        skipHeaders.add("cache-control");
        skipHeaders.add("pragma");
        skipHeaders.add("referrer-policy");
        skipHeaders.add("content-security-policy");
        skipHeaders.add("via");

        return this;
    }

    public CompactOkhttpLogger logAsDebug() {
        logAsDebug = true;
        return this;
    }

    public CompactOkhttpLogger logAsInfo() {
        logAsDebug = false;
        return this;
    }

    public CompactOkhttpLogger logOnlyFailures() {
        logOnlyFailuresFn = (response, hasError) -> hasError || !response.isSuccessful();
        return this;
    }

    // log failure saja
    public CompactOkhttpLogger logFailuresOnly() {
        logOnlyFailuresFn = (response, hasError) -> hasError || !response.isSuccessful();
        return this;
    }

    public CompactOkhttpLogger logOnlyFailures(FailureFilterFn filterFunction) {
        logOnlyFailuresFn = filterFunction;
        return this;
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        long startNs = System.nanoTime();
        StringBuffer reqBuffer = new StringBuffer();
        try {
            Request request = chain.request();
            Connection connection = chain.connection();

            reqBuffer.append(String.format("HTTP REQ: %s %s", request.method(), request.url()));
            if (connection != null) {
                reqBuffer.append(' ').append(connection.protocol());
            }

            if (logHeaders) {
                String headersStr = printHeaders(request.headers());
                if (!"".equals(headersStr)) {
                    reqBuffer.append("\n---\n").append(headersStr);
                }
            }

            if (logBody) {
                RequestBody requestBody = request.body();
                if (requestBody != null) {
                    if (requestBody.isDuplex()) {
                        reqBuffer.append("\n---\n(duplex request body omitted)");
                    } else if (requestBody.isOneShot()) {
                        reqBuffer.append("\n---\n(one-shot body omitted)");
                    } else if (bodyHasUnknownEncoding(request.headers())) {
                        reqBuffer.append("\n---\n(encoded body omitted)");
                    } else {
                        Buffer buffer = new Buffer();
                        requestBody.writeTo(buffer);
                        Charset charset = requestBody.contentType() == null ? Charset.defaultCharset() : requestBody.contentType().charset();
                        if (charset == null) {
                            charset = Charset.defaultCharset();
                        }
                        reqBuffer.append("\n---\n").append(buffer.readString(charset));
                    }
                }
            }
            if (logOnlyFailuresFn == null) {
                logMessage(reqBuffer.toString());
            }
        } catch (Exception e) {
            logger.error("Error in http logger", e);
            if (logOnlyFailuresFn == null) {
                logMessage(reqBuffer.toString());
            }
        }

        Response response;
        boolean responseHasError = false;
        try {
            response = chain.proceed(chain.request());
        } catch (Exception e) {
            long tookMs = Math.round((System.nanoTime() - startNs) / 1_000_000.0);
            if (logOnlyFailuresFn == null) {
                logExceptionResponse(e, chain.request(), tookMs);
            } else {
                Response response1 = new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("")
                        .build();
                if (logOnlyFailuresFn.isFailure(response1, true)) {
                    logMessage(reqBuffer.toString());
                    logExceptionResponse(e, chain.request(), tookMs);
                }
            }

            throw e;
        }

        StringBuffer resBuffer = new StringBuffer();
        try {
            Request request = chain.request();
            long tookMs = Math.round((System.nanoTime() - startNs) / 1_000_000.0);

            resBuffer.append(String.format("HTTP RESP: %s %s -> %d (%d ms)",
                    request.method(), request.url(), response.code(), tookMs));

            if (logHeaders) {
                String headersStr = printHeaders(response.headers());
                if (!"".equals(headersStr)) {
                    resBuffer.append("\n---\n").append(headersStr);
                }
            }

            if (logBody) {
                ResponseBody responseBody = response.body();
                if (responseBody != null && promisesBody(response)) {
                    if (bodyIsStreaming(response)) {
                        resBuffer.append("\n---\n(streaming response body)");
                    } else if (bodyHasUnknownEncoding(response.headers())) {
                        resBuffer.append("\n---\n(encoded body omitted)");
                    } else {
                        try {
                            BufferedSource source = responseBody.source();
                            source.request(Long.MAX_VALUE); // Buffer the entire body.
                            Buffer buffer = source.buffer();
                            Charset charset = responseBody.contentType() == null ? Charset.defaultCharset() : responseBody.contentType().charset();
                            if (charset == null) {
                                charset = Charset.defaultCharset();
                            }
                            String bodyStr = buffer.clone().readString(charset);

                            resBuffer.append("\n---\n").append(bodyStr);
                        } catch (Exception e) {
                            responseHasError = true;
                            resBuffer.append("\n---\n(error reading body: ").append(e.getClass().getName()).append(' ').append(e.getMessage()).append(')');
                            throw e;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error in http logger", e);
            throw e;
        } finally {
            if (logOnlyFailuresFn == null) {
                logMessage(resBuffer.toString());
            } else {
                if (logOnlyFailuresFn.isFailure(response, responseHasError)) {
                    logMessage(reqBuffer.toString());
                    logMessage(resBuffer.toString());
                }
            }
        }

        return response;
    }

    protected Boolean bodyIsStreaming(Response response) {
        MediaType contentType = response.body().contentType();
        return contentType != null && "text".equals(contentType.type()) && "event-stream".equals(contentType.subtype());
    }

    public String printHeaders(Headers headers) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            String headerLow = headers.name(i).toLowerCase();
            if (!onlyHeaders.isEmpty() && !onlyHeaders.contains(headerLow)) {
                continue;
            }
            if (!skipHeaders.isEmpty() && skipHeaders.contains(headerLow)) {
                continue;
            }

            String value = headersToRedact.contains(headerLow) ? "██" : headers.value(i);
            result.append(headers.name(i)).append(": ").append(value).append('\n');
        }
        return result.toString().trim();
    }

    protected void logExceptionResponse(Exception e, Request request, long tookMs) {
        logMessage(String.format("HTTP RESP: %s %s -> ERROR %s %s (%d ms)",
                request.method(), request.url(), e.getClass().getName(), e.getMessage(), tookMs));
    }

    public Boolean promisesBody(Response response) {
        // HEAD requests never yield a body regardless of the response headers.
        if ("HEAD".equals(response.request().method())) {
            return false;
        }

        int responseCode = response.code();
        if ((responseCode < HTTP_CONTINUE || responseCode >= 200) &&
                responseCode != HTTP_NO_CONTENT &&
                responseCode != HTTP_NOT_MODIFIED) {
            return true;
        }

        long headersContentLength = response.header("Content-Length") == null ? -1L : Long.parseLong(response.header("Content-Length"));
        if (headersContentLength != -1L || "chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            return true;
        }

        return false;
    }

    public void logMessage(String message) {
        if (logAsDebug) {
            logger.debug(message);
        } else {
            logger.info(message);
        }
    }

    public Boolean bodyHasUnknownEncoding(Headers headers) {
        String contentEncoding = headers.get("Content-Encoding");
        if (contentEncoding == null) {
            return false;
        }
        return !"identity".equalsIgnoreCase(contentEncoding) && !"gzip".equalsIgnoreCase(contentEncoding);
    }
}
