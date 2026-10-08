## Version 2.2.1

* Netty 4.1.139.Final.

## Version 2.2.0

### Fixes

* Remove cancelled pool acquisitions from the pending queue.
* Recycle dead poolables when removed.
* Prevent reaping poolables with active permits.
* Grow after selecting an unhealthy poolable.
* Reject newest acquisition when queue is full.
* Fix lost pool availability notifications.
* Fix caller cancellation of pending pool acquisitions.
* Drain active leases before shutdown.
* Validate pool capacity invariants.
* Await all pool creation attempts.
* Gracefully drain pooled channels after GOAWAY.
* Isolate external callbacks from pool control.
* Preserve pool permits when telemetry fails.
* Propagate pool creation failures to pending acquisitions.
* Bound pool shutdown and propagate cleanup failures.
* Defer dead poolable recycling until permit release.
* Prevent cancellation from orphaning factory resources.
* Handle HTTP/2 GOAWAY errors by stream boundary.
* Detach poolable listeners before disposal.
* Replace draining poolables before active leases finish.
* Reject unavailable factory results before pooling.
* Release recycle budget regardless of recycle outcome.
* Retire poolables discovered unavailable during their own selection read.
* Refuse permits on poolables that are no longer alive.
* Notify the pool when a channel starts closing or becomes inactive.

## Version 2.1.1

### Fixes

* Encode opaque APNs device tokens in request paths.

### Dependencies

* Google Auth 1.52.0

## Version 2.1.0

### Features

* Support APNs JWT provider authentication.

### Fixes

* Validate FCM OAuth token endpoint in credentials.
* Address stale FCM OAuth token usage.

### Dependencies

* Netty 4.1.138.Final

## Version 2.0.2

### Fixes

* Remove timing race from OAuth backoff test.
* Fix HTTP client test cleanup.
* Add targeted Jazzer fuzzing.
* Make buffered HTTP/2 requests cancellable.

## Version 2.0.1

### Fixes

* Bound HTTP/2 SETTINGS wait after TLS handshake.
* Always redact sensitive headers from trace logs.
* Encode and bound peer-provided HTTP/2 GOAWAY debug data in logs.
* Neutralize GOAWAY debug data in logs.
* Prevent quadratic response body consolidation.
* Bound automatic FCM retry delays derived from `Retry-After` responses.

## Version 2.0.0

### Breaking

* Move pushiko-api types under com.bloomberg.pushiko.api.
* Move pushiko-netty-ktx types under com.bloomberg.pushiko.netty.ktx.
* Move pushiko-pools types under com.bloomberg.pushiko.pools.
* Set Automatic-Module-Name on published module jars.

### Fixes

* Select from CommonMuxPool according to poolable health.
* Honour mid-connection SETTINGS_MAX_CONCURRENT_STREAMS.
* Guard against NPE cancelling absent response timeout in ConnectionHandler.
* Close FCM server-error response body reader.
* Cap accumulated HTTP/2 response body size.
* Reject control and whitespace characters in APNs request fields.
* Enforce semantic versioning at publication with japicmp.
* Enable explicit API.

### Dependencies

* Gradle 8.14.5
* Netty 4.1.136.Final

## Version 1.0.9

### Dependencies

* Netty 4.1.135.Final

## Version 1.0.8

### Fixes

* Replace token iterator with independent refreshOnce calls.
* Fix potential double-resume in HttpRequestSender.send.
* Use KeyManagerFactory for client TLS credential setup.

## Version 1.0.7

### Fixes

* Make FCM credentials manager responsive to closure during initialization.
* Fix setting the status code for an FcmSuccessResponse.
* Replace use of ambiguous coroutineContext.

## Version 1.0.6

### Fixes

* Limit FCM credential refresh retry attempts on init.

## Version 1.0.5

### Fixes

* Check if an OAuth exception for FCM credential is retryable, fail fast in init.
* Make FCM keep-alive cancellation-aware.

### Dependencies

* Google OAuth 1.43.0
* Netty 4.1.132.Final

## Version 1.0.4

### Dependencies

* Gradle 8.14.3
* Netty 4.1.127.Final
* Nexus Publish Plugin 2.0.0

## Version 1.0.3

### Dependencies

* Gradle 8.14.2
* Netty 4.1.122.Final

## Version 1.0.2

### Fixes

* Publish to the subgroup "com.bloomberg.pushiko" on Maven Central.

### Dependencies

* Gradle 8.14

## Version 1.0.1

### Dependencies

* Netty 4.1.121.Final.

## Version 1.0.0

Initial publication.
