package com.paxa.util;

import org.slf4j.Logger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class CompactOkhttpLoggerTest {
    private MockWebServer mockServer = new MockWebServer();

    @Mock
    private Logger logger;

    @AfterEach
    public void stopMock() throws IOException {
        if (mockServer != null) {
            mockServer.shutdown();
        }
    }

    void enableMockServer(int responseCode, String responseBody, int delayMs) throws IOException {
        this.mockServer = new MockWebServer();

        MockResponse mockResponse = new MockResponse()
            .setResponseCode(responseCode)
            .setBody(responseBody);

        if (delayMs != 0) {
            mockResponse.setBodyDelay(Long.valueOf(delayMs), TimeUnit.MILLISECONDS);
        }

        mockServer.enqueue(mockResponse);
        mockServer.start();
    }

    @Test
    public void shouldPrintSuccessRequest() throws IOException {
        enableMockServer(201, "it's ok", 0);

        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            logLines.add(i.getArgument(0));
            return null;
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true);
        OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(httpLogger).build();

        Request request = new Request.Builder()
            .url(mockServer.url("/foo"))
            .header("test_key", "test_value")
            .get().build();
        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
            "HTTP REQ: GET " + mockServer.url("/foo") + "\n" +
            "---\n" +
            "test_key: test_value"
        );
        assertEquals(
            logLines.get(1).replaceAll("\\(\\d+ ms\\)", "(X ms)"),
            "HTTP RESP: GET " + mockServer.url("/foo") + " -> 201 (X ms)\n" +
            "---\n" +
            "Content-Length: 7\n" + 
            "---\n" +
            "it's ok"
        );
    }

    @Test
    public void shouldPrintTimeoutError() throws IOException {
        enableMockServer(201, "it's ok", 100);

        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true);
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .readTimeout(1, TimeUnit.MILLISECONDS)
            .build();

        Request request = new Request.Builder()
            .url(mockServer.url("/foo"))
            .header("test_key", "test_value")
            .get().build();

        Exception e = assertThrows(SocketTimeoutException.class, () -> { httpClient.newCall(request).execute(); });

        // problem with mock server, sometimes it fail with connection error, sometimes it gives haaders

        if (e.getMessage().equals("timeout")) {
            assertEquals(e.getMessage(), "timeout");
        } else {
            assertEquals(e.getMessage(), "Read timed out");
        }
        assertEquals(e.getClass(), SocketTimeoutException.class);
        assertEquals(logLines.size(), 2);

        if (logLines.get(1).contains("/foo -> ERROR")) {
            assertEquals(
                logLines.get(1).replaceAll("\\(\\d+ ms\\)", "(X ms)"),
                "HTTP RESP: GET " + mockServer.url("/foo") +
                " -> ERROR java.net.SocketTimeoutException " + e.getMessage() + " (X ms)"
            );
        } else {
            assertEquals(
                logLines.get(1).replaceAll("\\(\\d+ ms\\)", "(X ms)"),
                "HTTP RESP: GET " + mockServer.url("/foo") + " -> 201 (X ms)\n" +
                "---\n" +
                "Content-Length: 7\n" + 
                "---\n" +
                "(error reading body: java.net.SocketTimeoutException " + e.getMessage() + ")"
            );
        }
    }

    @Test
    public void shouldSkipAndMaskHeaders() throws IOException {
        enableMockServer(201, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true)
            .skipCommonHeaders()
            .redactHeaders("pin");
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .build();

        Request request = new Request.Builder()
            .url(mockServer.url("/foo"))
            .header("date", "super")
            .header("pin", "12345")
            .get().build();

        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
            "HTTP REQ: GET " + mockServer.url("/foo") + "\n" +
            "---\n" +
            "pin: ██"
        );
    }

    @Test
    public void shouldSkipAndMaskHeadersAndAddCustomHeader() throws IOException {
        enableMockServer(201, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true)
                .skipCommonHeaders()
                .addHeaders("date")
                .redactHeaders("pin");
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(httpLogger)
                .build();

        Request request = new Request.Builder()
                .url(mockServer.url("/foo"))
                .header("date", "super")
                .header("content-type", "application/json")
                .header("pin", "12345")
                .get().build();

        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
                "HTTP REQ: GET " + mockServer.url("/foo") + "\n" +
                        "---\n" +
                        "date: super\n" +
                        "pin: ██"
        );
    }

    @Test
    public void shouldPrintPostRequest() throws IOException {
        enableMockServer(201, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true);
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .build();

        Request request = new Request.Builder()
            .url(mockServer.url("/foo"))
            .header("date", "super")
            .post(RequestBody.create("this is good bye", MediaType.parse("text/plain")))
            .build();

        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
            "HTTP REQ: POST " + mockServer.url("/foo") + "\n" +
            "---\n" +
            "date: super\n" +
            "---\n" +
            "this is good bye"
        );
    }

    @Test
    public void shouldLogInDebug() throws IOException {
        enableMockServer(201, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).debug(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true).logAsDebug();
        OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(httpLogger)
            .build();

        Request request = new Request.Builder()
            .url(mockServer.url("/foo"))
            .header("date", "super")
            .post(RequestBody.create("this is good bye", MediaType.parse("text/plain")))
            .build();

        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
            "HTTP REQ: POST " + mockServer.url("/foo") + "\n" +
            "---\n" +
            "date: super\n" +
            "---\n" +
            "this is good bye"
        );
    }


    @Test
    public void shouldFailureOnly() throws IOException {
        enableMockServer(400, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true)
                .logFailuresOnly();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(httpLogger)
                .build();

        Request request = new Request.Builder()
                .url(mockServer.url("/foo"))
                .header("date", "super")
                .get()
                .build();

        httpClient.newCall(request).execute();

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
                "HTTP REQ: GET " + mockServer.url("/foo") + "\n" +
                        "---\n" +
                        "date: super"
        );
    }

    @Test
    public void shouldFailureOnlyWithCustomFilter() throws IOException {
        enableMockServer(400, "it's ok", 0);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true)
                .logOnlyFailures((response, hasError) -> hasError || response.code() > 404);
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(httpLogger)
                .build();

        Request request = new Request.Builder()
                .url(mockServer.url("/foo"))
                .header("date", "super")
                .get()
                .build();

        httpClient.newCall(request).execute();

        mockServer.shutdown();
        enableMockServer(406, "it's ok", 0);

        request = new Request.Builder()
                .url(mockServer.url("/foo"))
                .header("date", "super")
                .get()
                .build();

        httpClient.newCall(request).execute();

        // first request considered success, only second request logged
        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
                "HTTP REQ: GET " + mockServer.url("/foo") + "\n" +
                        "---\n" +
                        "date: super"
        );
        assertEquals(
                logLines.get(1).replaceAll("\\(\\d+ ms\\)", "(X ms)"),
                "HTTP RESP: GET " + mockServer.url("/foo") + " -> 406 (X ms)\n" +
                        "---\n" +
                        "Content-Length: 7\n" +
                        "---\n" +
                        "it's ok"
        );
    }

    @Test
    public void shouldFailureOnlyOnTimeout() throws IOException {
        enableMockServer(400, "it's ok", 100);
        List<String> logLines = new ArrayList<String>();
        doAnswer(i -> {
            return logLines.add(i.getArgument(0));
        }).when(logger).info(any());

        CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true)
                .logOnlyFailures();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(httpLogger)
                .readTimeout(1, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url(mockServer.url("/foo"))
                .header("date", "super")
                .post(RequestBody.create("this is good bye", MediaType.parse("text/plain")))
                .build();

        assertThrows(SocketTimeoutException.class, () -> { httpClient.newCall(request).execute(); });

        assertEquals(logLines.size(), 2);
        assertEquals(logLines.get(0),
                "HTTP REQ: POST " + mockServer.url("/foo") + "\n" +
                        "---\n" +
                        "date: super\n" +
                        "---\n" +
                        "this is good bye"
        );
    }
}
