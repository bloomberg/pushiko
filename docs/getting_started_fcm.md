## Integration

First add Pushiko as a dependency.

### Gradle

=== "Kotlin"
    ```kotlin
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation("com.bloomberg.pushiko:pushiko-fcm:{pushikoVersion}")
    }
    ```

=== "Groovy"
    ```groovy
    repositories {
        mavenCentral()
    }

    dependencies {
        implementation 'com.bloomberg.pushiko:pushiko-fcm:{pushikoVersion}'
    }
    ```

### Maven

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.bloomberg.pushiko</groupId>
    <artifactId>pushiko-fcm</artifactId>
    <version>{pushikoVersion}</version>
</dependency>
```

## Sending notifications

=== "Kotlin"
    ```kotlin
    val client = FcmClient {
        // TODO Replace with the path to your FCM metadata json file.
        metadata(File("firebase_metadata.json"))
        maxConnections(1)
        minConnections(0)
    }
    try {
        client.joinStart()
        val request = FcmRequest {
            validateOnly(true)
            message {
                token("abc123")
                notification {
                    title("Hello")
                    body("World!")
                }
                android {
                    // TODO Change to reflect the priority of your notification.
                    priority(AndroidMessagePriority.HIGH)
                }
                data {
                    stringValue("Stay", "Safe!")
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
    final FcmClient client = FcmClient(
        it -> it.metadata(new File("firebase_metadata.json").toURI())
            .maxConnections(1)
            .minConnections(0)
    try {
        client.joinStartFuture().join();
        final FcmRequest request = FcmRequest(it -> it.validateOnly(true)
            .message(message -> message.token("abc123")
                .notification(notification -> notification.title("Hello")
                    .body("World!"))
                .android(android -> android.priority(AndroidMessagePriority.NORMAL))
            .data(data -> data.stringValue("Stay", "Safe!"))));
        // TODO Change to whenComplete!
        final FcmResponse response = client.sendFuture(request).get();
        System.out.println(response.toString());
    } finally {
        client.close().join();
    }
    ```
