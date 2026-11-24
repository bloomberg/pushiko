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

package com.bloomberg.pushiko.json;

import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Source;
import okio.Timeout;
import okio.Utf8;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@SuppressWarnings("deprecation")
final class SizeLimitedBufferedSink implements BufferedSink {
    private static final long READ_CHUNK_SIZE = 8_192L;

    private final Buffer delegate;
    private final long maximumByteCount;
    private final String description;

    SizeLimitedBufferedSink(
        final Buffer delegate,
        final long maximumByteCount,
        final String description
    ) {
        if (maximumByteCount < 0L) {
            throw new IllegalArgumentException("maximumByteCount must not be negative");
        }
        this.delegate = delegate;
        this.maximumByteCount = maximumByteCount;
        this.description = description;
    }

    private void requireCapacity(final long byteCount) {
        if (byteCount > maximumByteCount - delegate.size()) {
            throw new IllegalArgumentException(description + " must not exceed " + maximumByteCount + " bytes");
        }
    }

    @Override
    public Buffer buffer() {
        return delegate;
    }

    @Override
    public Buffer getBuffer() {
        return delegate;
    }

    @Override
    public BufferedSink write(final ByteString byteString) {
        requireCapacity(byteString.size());
        delegate.write(byteString);
        return this;
    }

    @Override
    public BufferedSink write(final ByteString byteString, final int offset, final int byteCount) {
        requireCapacity(byteCount);
        delegate.write(byteString, offset, byteCount);
        return this;
    }

    @Override
    public BufferedSink write(final byte[] source) {
        requireCapacity(source.length);
        delegate.write(source);
        return this;
    }

    @Override
    public BufferedSink write(final byte[] source, final int offset, final int byteCount) {
        requireCapacity(byteCount);
        delegate.write(source, offset, byteCount);
        return this;
    }

    @Override
    public int write(final ByteBuffer source) throws IOException {
        requireCapacity(source.remaining());
        return delegate.write(source);
    }

    @Override
    public long writeAll(final Source source) throws IOException {
        long totalBytesRead = 0L;
        final Buffer pending = new Buffer();
        while (true) {
            final long remainingCapacity = maximumByteCount - delegate.size();
            final long byteCount = remainingCapacity == Long.MAX_VALUE ?
                READ_CHUNK_SIZE : Math.min(READ_CHUNK_SIZE, remainingCapacity + 1L);
            final long bytesRead = source.read(pending, byteCount);
            if (bytesRead == -1L) {
                return totalBytesRead;
            }
            requireCapacity(bytesRead);
            delegate.write(pending, bytesRead);
            totalBytesRead += bytesRead;
        }
    }

    @Override
    public BufferedSink write(final Source source, final long byteCount) throws IOException {
        requireCapacity(byteCount);
        delegate.write(source, byteCount);
        return this;
    }

    @Override
    public BufferedSink writeUtf8(final String string) {
        requireCapacity(Utf8.size(string));
        delegate.writeUtf8(string);
        return this;
    }

    @Override
    public BufferedSink writeUtf8(final String string, final int beginIndex, final int endIndex) {
        requireCapacity(Utf8.size(string, beginIndex, endIndex));
        delegate.writeUtf8(string, beginIndex, endIndex);
        return this;
    }

    @Override
    public BufferedSink writeUtf8CodePoint(final int codePoint) {
        final long byteCount;
        if (codePoint < 0x80 || codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            byteCount = 1L;
        } else if (codePoint < 0x800) {
            byteCount = 2L;
        } else if (codePoint < 0x10000) {
            byteCount = 3L;
        } else if (codePoint <= 0x10FFFF) {
            byteCount = 4L;
        } else {
            throw new IllegalArgumentException("Unexpected code point: " + Integer.toHexString(codePoint));
        }
        requireCapacity(byteCount);
        delegate.writeUtf8CodePoint(codePoint);
        return this;
    }

    @Override
    public BufferedSink writeString(final String string, final Charset charset) {
        return writeString(string, 0, string.length(), charset);
    }

    @Override
    public BufferedSink writeString(
        final String string,
        final int beginIndex,
        final int endIndex,
        final Charset charset
    ) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return writeUtf8(string, beginIndex, endIndex);
        }
        return write(string.substring(beginIndex, endIndex).getBytes(charset));
    }

    @Override
    public BufferedSink writeByte(final int value) {
        requireCapacity(1L);
        delegate.writeByte(value);
        return this;
    }

    @Override
    public BufferedSink writeShort(final int value) {
        requireCapacity(2L);
        delegate.writeShort(value);
        return this;
    }

    @Override
    public BufferedSink writeShortLe(final int value) {
        requireCapacity(2L);
        delegate.writeShortLe(value);
        return this;
    }

    @Override
    public BufferedSink writeInt(final int value) {
        requireCapacity(4L);
        delegate.writeInt(value);
        return this;
    }

    @Override
    public BufferedSink writeIntLe(final int value) {
        requireCapacity(4L);
        delegate.writeIntLe(value);
        return this;
    }

    @Override
    public BufferedSink writeLong(final long value) {
        requireCapacity(8L);
        delegate.writeLong(value);
        return this;
    }

    @Override
    public BufferedSink writeLongLe(final long value) {
        requireCapacity(8L);
        delegate.writeLongLe(value);
        return this;
    }

    @Override
    public BufferedSink writeDecimalLong(final long value) {
        final String encoded = Long.toString(value);
        requireCapacity(encoded.length());
        delegate.writeUtf8(encoded);
        return this;
    }

    @Override
    public BufferedSink writeHexadecimalUnsignedLong(final long value) {
        final String encoded = Long.toHexString(value);
        requireCapacity(encoded.length());
        delegate.writeUtf8(encoded);
        return this;
    }

    @Override
    public void write(final Buffer source, final long byteCount) {
        requireCapacity(byteCount);
        delegate.write(source, byteCount);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public BufferedSink emit() {
        delegate.emit();
        return this;
    }

    @Override
    public BufferedSink emitCompleteSegments() {
        delegate.emitCompleteSegments();
        return this;
    }

    @Override
    public OutputStream outputStream() {
        return new OutputStream() {
            @Override
            public void write(final int value) {
                writeByte(value);
            }

            @Override
            public void write(final byte[] source, final int offset, final int byteCount) {
                SizeLimitedBufferedSink.this.write(source, offset, byteCount);
            }

            @Override
            public void flush() {
                SizeLimitedBufferedSink.this.flush();
            }

            @Override
            public void close() {
                SizeLimitedBufferedSink.this.close();
            }
        };
    }

    @Override
    public Timeout timeout() {
        return delegate.timeout();
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
