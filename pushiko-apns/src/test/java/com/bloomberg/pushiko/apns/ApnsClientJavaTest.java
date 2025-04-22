/*
 * Copyright 2025 Bloomberg Finance L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bloomberg.pushiko.apns;

import com.bloomberg.pushiko.http.HttpClient;
import com.bloomberg.pushiko.http.HttpResponse;
import kotlinx.coroutines.ExecutorsKt;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static com.bloomberg.pushiko.apns.ApnsClients.ApnsClient;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ApnsClientJavaTest {
    @Test
    void createClientDefault() {
        assertDoesNotThrow(() -> {
            final ApnsClient client = ApnsClient(it -> it.environment(ApnsEnvironment.DEVELOPMENT)
                .executor(ForkJoinPool.commonPool())
                .maximumConnections(2)
                .minimumConnections(0)
                .clientCredentials(privateKey(), "changeit".toCharArray())
                .proxy("proxy.foo.com", 80));
            try {
                client.joinStartFuture().join();
            } finally {
                client.closeFuture().join();
            }
        });
    }

    @Test
    void sendFuturePropagates() throws Exception {
        final HttpResponse httpResponse = mock(HttpResponse.class);
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.header("apns-id")).thenReturn("foo-id");
        final HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings({"OptionalGetWithoutIsPresent", "unchecked"})
        final Constructor<ApnsClient> constructor = (Constructor<ApnsClient>) Arrays.stream(
            ApnsClient.class.getDeclaredConstructors()).filter(it -> it.getParameterCount() == 2).findFirst().get();
        constructor.setAccessible(true);
        final ApnsRequest request = ApnsRequests.ApnsRequest(it -> it.topic("com.foo").deviceToken("abc123"));
        final ApnsClient client = constructor.newInstance(httpClient, ExecutorsKt.from(ForkJoinPool.commonPool()));
        try {
            client.joinStartFuture().join();
            client.sendFuture(request).get();
        } catch (final ExecutionException e) {
            // Unable to mock suspend method.
            assertTrue(e.getCause() instanceof NullPointerException);
        } finally {
            client.closeFuture().join();
        }
    }

    private static File privateKey() {
        try {
            return new File(Objects.requireNonNull(ApnsClientJavaTest.class.getClassLoader()
                .getResource("keystore.pkcs12")).toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
