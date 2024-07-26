## About `SmallHttpLogger` library


HTTP logger that print 1 line for request and 1 line for response, for better readability.


### Usage

in gradle:
```groovy
implementation group: 'com.paxa', name: 'compact_okhttp_logger', version: '0.1.0'
```

in code:
```java
// import com.paxa.util.CompactOkhttpLogger;

CompactOkhttpLogger httpLogger = new CompactOkhttpLogger(logger, true, true);
OkHttpClient httoClient = new OkHttpClient.Builder()
  .addInterceptor(httpLogger).build();
```

### Output example:

```

HTTP REQ: POST http://example.com/user/v1/profile
---
System-Timestamp: 2023-05-18T10:49:45
---
{"meta_data":{"event_id":"916b9200-d927-4630-a560-d782286c0e34","job_id":"user-186987387-903905-INTERNAL"}}"}


HTTP RESP: POST http://example.com/user/v1/profile -> 400 (275 ms)
---
{"success":false,"result":"INQUIRY_FAILED","inquiry_rc":"SYSTEM_BS_ERROR_SOMETHING"}

```

### Other Features

```java
// LOG LEVEL
httpLogger.logAsDebug(); // will call logger.debug()
httpLogger.logAsInfo(); // will call logger.info()


// LOG ONLY FAILURES

// will print req and resp only when response has exception or failed
// default filter  is (hasError || !response.isSuccessful())

httpLogger.logOnlyFailures();

// with custom filter
httpLogger.logOnlyFailures((response, hasError) -> {
    return hasError || (response.code() > 400 && response.code() != 404));
});

// SKIP HEADERS

httpLogger.skipCommonHeaders()
httpLogger.skipHeaders("x-envoy-upstream-service-time", "x-correlation-id") // case insensitive
httpLogger.redactHeaders("authentication", "pin")
```
