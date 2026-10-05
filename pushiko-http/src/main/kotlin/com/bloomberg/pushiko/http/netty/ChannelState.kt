/*
 * Copyright 2026 Bloomberg Finance L.P.
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

package com.bloomberg.pushiko.http.netty

import io.netty.channel.Channel
import io.netty.util.AttributeKey

internal val maxConcurrentStreamsAttributeKey = AttributeKey.valueOf<Long>(
    Channel::class.java,
    "channelMaxConcurrentStreams"
)

internal val streamCapacityChangedAttributeKey = AttributeKey.valueOf<() -> Unit>(
    Channel::class.java,
    "channelStreamCapacityChanged"
)

internal val Channel.maxConcurrentStreams: Long?
    get() = attr(maxConcurrentStreamsAttributeKey).get()

internal fun Channel.recordMaxConcurrentStreams(maxConcurrentStreams: Long) =
    attr(maxConcurrentStreamsAttributeKey).set(maxConcurrentStreams)

private val channelIsClosingAttributeKey = AttributeKey.valueOf<Boolean>("channelIsClosing")
@JvmSynthetic
internal fun Channel.isClosing() = attr(channelIsClosingAttributeKey).get() ?: false
internal fun Channel.signalIsClosing() = attr(channelIsClosingAttributeKey).getAndSet(true) != true

internal val channelIsDrainingAttributeKey = AttributeKey.valueOf<Boolean>("channelIsDraining")
internal fun Channel.isDraining() = attr(channelIsDrainingAttributeKey).get() ?: false
internal fun Channel.signalIsDraining() = attr(channelIsDrainingAttributeKey).getAndSet(true) != true

internal fun Channel.signalAvailabilityChanged() {
    attr(streamCapacityChangedAttributeKey).get()?.invoke()
}
