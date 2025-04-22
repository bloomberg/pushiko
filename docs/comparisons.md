In the following "FCM" and "APNs" are respectively the common abbreviations for Firebase Cloud Messaging and Apple Push Notification service.

## FCM and APNs documented comparisons

* FCM responds with a unique notification identifier in the response body, APNs responds with this identifier in the 
  headers.
* Trusted FCM connections are always established using a JSON web token; APNs additionally offers certificate-based
  server authentication, and each app bundle identifier and APNs environment pair has its own certificate. As such,
  with FCM we can always maintain a single shared connection pool; certificate authentication with APNs requires
  maintaining one connection pool per certificate and environment pair.

## FCM and APNs empirical comparisons

These observations were made and recorded during development. They're not substantiated by any official documentation
and may be subject to change.

* FCM requires Application-Layer Protocol Negotiation (ALPN), APNs does not. (APNs is h2-only.) Without ALPN the
  client sends a `GOAWAY` error 1 to FCM and closes the connection when negotiating HTTP.
* FCM requires the `:authority` pseudo-header in every h2 request, even if this repeats the host in the URL. Without
  this FCM responds with a 400 and closes the stream. APNs is happy if this is omitted.
* FCM allows up to 100 concurrent streams per h2 connection; APNs allows 1000.
* FCM frequently (though not always) replies with two `DATA` frames upon a successful notification request: the
  first containing the response, the second an empty frame that also closes the stream. APNs responds with one
  `DATA` frame that also closes the stream.
* FCM sends a `GOAWAY` frame (for error `0xB` and `"too_many_pings"`) when it receives three consecutive pings
  without a request. It then closes the connection, regardless of the ping interval. APNs is apparently more relaxed,
  it has yet to exhibit this behaviour instead keeping connections open.
* FCM sends a `GOAWAY` frame (for `"session_timeout"`) after four minutes of idling, and closes the connection; even
  after twenty minutes of idling, APNs still keeps connections open.
* FCM sends a `GOAWAY` frame (for `"max_age"`) when a connection has been active for one hour and closes the
  connection; APNs keeps connections open.
* FCM sends a ping immediately after closing the stream upon responding to a notification request. APNs does not
  send this ping.
* FCM has an internal timeout of 5 seconds and responds with a 500 internal server error status code when this is
  encountered.
