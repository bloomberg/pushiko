## Integration

First add Pushiko as a dependency.

### Gradle

=== "Kotlin"
    ```kotlin
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation("com.bloomberg:pushiko-apns:{pushikoVersion}")
    }
    ```

=== "Groovy"
    ```groovy
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation 'com.bloomberg:pushiko-apns:{pushikoVersion}'
    }
    ```

### Maven

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.bloomberg</groupId>
    <artifactId>pushiko-apns</artifactId>
    <version>{pushikoVersion}</version>
</dependency>
```

## Sending notifications

### Certificate authentication

=== "Kotlin"
    ```kotlin
    val client = ApnsClient {
        clientCredentials(
            File("my_key.p12"),
            "protect_me".toCharArray()
        )
        environment(ApnsEnvironment.SANDBOX)
        maxConnections(1)
        minConnections(0)
    }
    try {
        client.joinStart()
        val request = ApnsRequest {
            apnsPriority(ApnsPriority.IMMEDIATE)
            apnsTopic("com.my.app")
            deviceToken("abc123")
            aps {
                alert {
                    title("Hello")
                    body("World!")
                }
            }
        }
        val response = runCatching {
            client.send(request)
        }.getOrElse {
            when (it) {
                is ClientClosedException -> TODO("Don't retry!")
                is IOException -> TODO("Retry?")
                else -> TODO("File issue?")
            }
        }
        println(response)
    } finally {
        client.close()
    }
    ```

=== "Java"
    ```java
    final ApnsClient client = ApnsClient(it -> it.clientCredentials(
            new File("my_key.p12"), "protect_me".toCharArray())
        .environment(ApnsEnvironment.SANDBOX)
        .maxConnections(1)
        .minConnections(0)
    try {
        client.joinStartFuture().join();
        final ApnsRequest request = ApnsRequest(it -> it.apnsPriority(
                ApnsPriority.IMMEDIATE)
            .apnsTopic("com.my.app")
            .deviceToken("abc123")
            .aps(aps -> aps.alert(alert -> alert.title("Hello").body("World!"))));
        // TODO Change to whenComplete!
        final ApnsResponse response = client.sendFuture(request).get();
        System.out.println(response.toString());
    } finally {
        client.closeFuture().join();
    }
    ```
