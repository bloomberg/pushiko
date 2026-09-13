## Integration

First add Pushiko as a dependency.

### Gradle

=== "Kotlin"
    ```kotlin
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation("com.bloomberg.pushiko:pushiko-apns:{pushikoVersion}")
    }
    ```

=== "Groovy"
    ```groovy
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation 'com.bloomberg.pushiko:pushiko-apns:{pushikoVersion}'
    }
    ```

### Maven

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.bloomberg.pushiko</groupId>
    <artifactId>pushiko-apns</artifactId>
    <version>{pushikoVersion}</version>
</dependency>
```

## Authentication

Choose either certificate authentication or token authentication for each client. They cannot be configured together.

### Certificate authentication

=== "Kotlin"
    ```kotlin
    val client = ApnsClient {
        clientCredentials(
            File("my_key.p12"),
            "protect_me".toCharArray()
        )
        environment(ApnsEnvironment.DEVELOPMENT)
        maximumConnections(1)
        minimumConnections(0)
    }
    ```

=== "Java"
    ```java
    final ApnsClient client = ApnsClient(it -> it.clientCredentials(
            new File("my_key.p12"), "protect_me".toCharArray())
        .environment(ApnsEnvironment.DEVELOPMENT)
        .maximumConnections(1)
        .minimumConnections(0));
    ```

### Token authentication

Create an APNs signing key in the Apple Developer portal and download its unencrypted PKCS#8 `.p8` file. Provider
tokens are signed with ES256, shared across the client's connections, and replaced after 50 minutes. Keep the signing
key file secret and keep the host clock synchronized.

=== "Kotlin"
    ```kotlin
    val client = ApnsClient {
        signingKey(
            ApnsSigningKey.loadFromPkcs8File(
                File("AuthKey_KEYID12345.p8"),
                keyId = "KEYID12345",
                teamId = "TEAMID1234"
            )
        )
        environment(ApnsEnvironment.DEVELOPMENT)
        maximumConnections(1)
        minimumConnections(0)
    }
    ```

=== "Java"
    ```java
    final ApnsClient client = ApnsClient(it -> it.signingKey(
            ApnsSigningKey.loadFromPkcs8File(
                new File("AuthKey_KEYID12345.p8"),
                "KEYID12345",
                "TEAMID1234"))
        .environment(ApnsEnvironment.DEVELOPMENT)
        .maximumConnections(1)
        .minimumConnections(0));
    ```

## Sending notifications

=== "Kotlin"
    ```kotlin
    try {
        client.joinStart()
        val request = ApnsRequest {
            priority(Priority.IMMEDIATE)
            topic("com.my.app")
            deviceToken("abc123")
            aps {
                alert {
                    title("Hello")
                    body("World!")
                }
            }
        }
        println(client.send(request))
    } finally {
        client.close()
    }
    ```

=== "Java"
    ```java
    try {
        client.joinStartFuture().join();
        final ApnsRequest request = ApnsRequest(it -> it
            .priority(Priority.IMMEDIATE)
            .topic("com.my.app")
            .deviceToken("abc123")
            .aps(aps -> aps.alert(alert -> alert.title("Hello").body("World!"))));
        final ApnsResponse response = client.sendFuture(request).get();
        System.out.println(response);
    } finally {
        client.closeFuture().join();
    }
    ```
