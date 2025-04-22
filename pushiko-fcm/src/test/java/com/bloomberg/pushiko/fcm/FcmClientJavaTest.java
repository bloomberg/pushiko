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

package com.bloomberg.pushiko.fcm;

import com.bloomberg.pushiko.http.HttpClient;
import com.bloomberg.pushiko.http.HttpResponse;
import kotlinx.coroutines.ExecutorsKt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static com.bloomberg.pushiko.fcm.FcmClients.FcmClient;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class FcmClientJavaTest {
    private static final String PROJECT_ID = "foo";

    @Test
    void createClientDefault() {
        assertDoesNotThrow(() -> {
            final FcmClient client = FcmClient(it -> it.executor(ForkJoinPool.commonPool())
                .connectionAcquisitionTimeout(Duration.ofMinutes(1L))
                .maximumConnections(2)
                .minimumConnections(0)
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
        @SuppressWarnings("KotlinInternalInJava")
        final com.bloomberg.pushiko.fcm.oauth.Session session = mock(com.bloomberg.pushiko.fcm.oauth.Session.class);
        when(session.getSendPath()).thenReturn("/v1/projects/bar/messages:send");
        final HttpClient httpClient = mock(HttpClient.class);
        final FcmRequest request = FcmRequests.FcmRequest(it -> it.message(message -> message.token("abc123")));
        @SuppressWarnings({"OptionalGetWithoutIsPresent", "unchecked"})
        final Constructor<FcmClient> constructor = (Constructor<FcmClient>) Arrays.stream(
            FcmClient.class.getDeclaredConstructors()).filter(it -> it.getParameterCount() == 3).findFirst().get();
        constructor.setAccessible(true);
        final FcmClient client = constructor.newInstance(Map.of(PROJECT_ID, session), httpClient,
            ExecutorsKt.from(ForkJoinPool.commonPool()));
        try {
            client.joinStartFuture().join();
            client.sendFuture(PROJECT_ID, request).get();
        } catch (final ExecutionException e) {
            // Unable to mock suspend method.
            assertTrue(e.getCause() instanceof NullPointerException);
        } finally {
            client.closeFuture().join();
        }
    }
}
