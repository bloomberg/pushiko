## Version 2.0.0

### Breaking Changes

* Move `ClientClosedException` to `com.bloomberg.pushiko.api.exceptions`, and the metrics interfaces `Gauges`, `Metrics` and `MetricsComponent` to `com.bloomberg.pushiko.api.metrics`. This also removes a split package shared with `pushiko-metrics`.
* Move `pushiko-netty-ktx` types from `com.bloomberg.netty.ktx` to `com.bloomberg.pushiko.netty.ktx`.
* Move `pushiko-pools` types from `com.bloomberg.pushiko.pool` to `com.bloomberg.pushiko.pools`.

### Fixes

* Set a stable `Automatic-Module-Name` on each published jar so consumers can require these artifacts on the JPMS module path.

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
