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

package com.bloomberg.pushiko.proxies

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

fun systemHttpsProxyAddress(host: String) = URI("https", host, null, null).httpProxyAddress()

private fun URI.httpProxyAddress() = ProxySelector.getDefault().select(this).firstOrNull {
    Proxy.Type.HTTP === it.type() && it.address().run { this is InetSocketAddress && isUnresolved }
}?.let {
    it.address() as InetSocketAddress
}
