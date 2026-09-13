Pushiko is a JVM library for sending push notifications via Apple Push Notification service (APNs) and Firebase Cloud Messaging (FCM).

## Software bill of materials

Every published Pushiko module has a CycloneDX SBOM. Maven Central publishes it beside the runtime JAR with the
`cyclonedx` classifier, for example `pushiko-apns-{version}-cyclonedx.json`. The same document is embedded at
`META-INF/sbom/pushiko-apns.cdx.json` in the runtime JAR.

GitHub releases also include a repository-wide `pushiko-{version}.cdx.json` SBOM covering every published module.
Run `./gradlew cyclonedxBom` to generate all module SBOMs or `./gradlew repositoryCyclonedxBom` to generate the
repository-wide document.

## License

```
Copyright 2025 Bloomberg Finance L.P.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
