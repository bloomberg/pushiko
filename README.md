# Pushiko

[![Apache 2.0](https://img.shields.io/badge/license-Apache%202-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.bloomberg/pushiko-apns.svg?labelColor=007AFF)](https://search.maven.org/artifact/com.bloomberg/pushiko-apns)
[![Maven Central](https://img.shields.io/maven-central/v/com.bloomberg/pushiko-fcm.svg?labelColor=A4C639)](https://search.maven.org/artifact/com.bloomberg/pushiko-fcm)

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/bloomberg/pushiko/badge)](https://securityscorecards.dev/viewer/?uri=github.com/bloomberg/pushiko)

## Introduction

Pushiko is a JVM library for sending push notifications via Apple Push Notification service (APNs) and Firebase Cloud Messaging (FCM).

## Features

* Support for Apple Push Notification service, covering iOS, macOS, and Safari.
* Support for Firebase Cloud Messaging, covering Android and web browsers.
* Potential to support other services like Amazon Simple Notification Service (Amazon SNS) and Windows Push Notification Services (WNS).

## Rationale

When Apple announced it would be retiring its binary Apple Push Notifications service ("APNs") protocol in 2021 in favor of a new HTTP/2 provider interface, we set about planning for the migration of our own iOS push notifications service. The Java ecosystem is rich in mature HTTP libraries and frameworks that we could use to build our solution. Given our familiarity with JVM-based technologies from our experience developing for Android, we naturally started here. The performance of and support for Jon Chambers' "[Pushy](https://pushy-apns.org/)" library made it a good candidate with which to begin evaluating our options.

Meanwhile, our existing Android notifications service, which also targeted the JVM, made use of the batched send endpoint[^1] offered by Firebase Cloud Messaging ("FCM"). Each request went over HTTP/1.1 and carried up to 500 notifications, and the aggregation and processing of these was more complicated than just exploiting the multiplexing offered by HTTP/2. We saw value in migrating this service to HTTP/2 as well, both to simplify its implementation and to ultimately achieve greater consistency with the eventual iOS notifications service.

Such an approach would also make it possible to only have to reason about a single HTTP-stack. For instance, when triaging, we'd like to be able to assert with some initial confidence that, if both the Android and iOS notifications services are reporting an issue, the root cause is likely to be internal, while if one is healthy and the other is not, that the root cause is likely to be external.

In the absence of a community solution for the JVM – and drawing on inspiration from Jon Chambers' work and from data gathered during our APNs pilot – we set about creating a new HTTP/2 client for FCM built on top of [Netty](https://netty.io/) that simultaneously offers high throughput and furthermore addresses our production requirements. For example (in no particular order):

* the client must maintain a minimum number of channels without ever relying on request traffic to do so: The channel pool must not be allowed to shrink below a minimum size between requests;
* requests must never be held pending the creation of a channel if there is some capacity available;
* the same IP address for an internet proxy must not continue to be used indefinitely;
* notifications are often used to wake the world: The client must be ready for spikes in traffic, creating extra, short-lived channels if need be, and only when permitted to do so;
* the client must make every reasonable effort to anticipate having to create extra channels, such as by using a high-low watermark heuristic similar to Netty's own;
* the client must make reasonable efforts to retry requests while avoiding contributing to the duplication of notifications, and to abort sending notification requests that have been cancelled. Requests meeting with a REFUSED_STREAM error code from the peer should be retried but over another channel;
* the client must regularly check the health of each channel – helped in part by the judicious use of the HTTP/2 ping frame – and replace channels that are unhealthy;
* the client must not drop notifications: The client must expose some indication of its own health so that consumption of requests upstream may be paused and resumed.

Extending our client to support the new APNs provider was a matter of adding a simple API. We achieved all of the consistency we had asked for between our two notifications services – and the resulting client is called "Pushiko."

We believe Pushiko is both mature and generic enough to represent a useful contribution back to the open source community.

[^1]: [Deprecated by Google](https://firebase.google.com/support/faq#fcm-23-deprecation) on 20 June 2023, and failing from 21 June 2024.

## Quick Start

Please refer to the main documentation: For APNs see [Getting started > APNs](https://bloomberg.github.io/pushiko/getting_started_apns/) and for FCM see [Getting started > FCM](https://bloomberg.github.io/pushiko/getting_started_fcm/).

## Acknowledgements

The original implementation of the Netty channel pool in Pushiko was heavily influenced by Jon Chambers' own APNs Java channel pool in Pushy :bow:. Taking this as our template for how to begin designing our pool, any mistakes introduced in subsequent work are entirely our own.

## Contributions

We :heart: contributions.

Have you had a good experience with this project? Why not share some love and contribute code, or just let us know about any issues you had with it?

We welcome issue reports [here](../../issues); be sure to choose the proper issue template for your issue, so that we can be sure you're providing the necessary information.

## Licenses

Please read the [LICENSE](LICENSE) file.

## Code of Conduct

This project has adopted a [Code of Conduct](https://github.com/bloomberg/.github/blob/master/CODE_OF_CONDUCT.md).

If you have any concerns about the Code, or behavior which you have experienced in the project, please contact us at opensource@bloomberg.net.

## Security Vulnerability Reporting

If you believe you have identified a security vulnerability in this project, please send an email to the project team at opensource@bloomberg.net, detailing the suspected issue and any methods you've found to reproduce it.

Please do NOT open an issue in the GitHub repository, as we'd prefer to keep vulnerability reports private until we've had an opportunity to review and address them.
