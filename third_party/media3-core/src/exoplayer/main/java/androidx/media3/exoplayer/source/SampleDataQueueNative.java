/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source;

import dalvik.annotation.optimization.FastNative;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/** Preferred maintained reconstruction of the custom reference binary. */
public final class SampleDataQueueNative {
    private static final String LIBRARY_NAME = "media3_exoplayer_jni";
    private static volatile boolean loadAttempted;
    private static volatile boolean isAvailable;
    private static final ThreadLocal<BufferAddressCache> targetCache = new ThreadLocal<BufferAddressCache>() { 
        @Override
        protected BufferAddressCache initialValue() {
            return new BufferAddressCache();
        }
    };

    @FastNative
    private static native boolean nativeCopyFromArray(byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

    @FastNative
    private static native boolean nativeCopyToArray(ByteBuffer source, int sourceOffset, byte[] target, int targetOffset, int length);

    @FastNative
    private static native boolean nativeCopyBetweenDirectBuffers(ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length);

    @FastNative
    public static native void nativeCopyAddresses(long sourceAddress, int sourceOffset, long targetAddress, int targetOffset, int length);

    @FastNative
    private static native long nativeGetBufferAddress(ByteBuffer buffer);

    public static boolean copyFromArray(byte[] source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
        if (length == 0) {
            return true;
        }
        if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.length) {
            return false;
        }
        if (targetOffset < 0 || targetOffset + length > target.capacity()) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            return nativeCopyFromArray(source, sourceOffset, target, targetOffset, length);
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return false;
        }
    }

    public static boolean copyToArray(ByteBuffer source, int sourceOffset, byte[] target, int targetOffset, int length) {
        if (length == 0) {
            return true;
        }
        if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.capacity()) {
            return false;
        }
        if (targetOffset < 0 || targetOffset + length > target.length) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            return nativeCopyToArray(source, sourceOffset, target, targetOffset, length);
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return false;
        }
    }

    public static boolean copyBetweenDirectBuffers(ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
        if (length == 0) {
            return true;
        }
        if (sourceOffset < 0 || length < 0 || sourceOffset + length > source.capacity()) {
            return false;
        }
        if (targetOffset < 0 || targetOffset + length > target.capacity()) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            return nativeCopyBetweenDirectBuffers(source, sourceOffset, target, targetOffset, length);
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return false;
        }
    }

    private static boolean isAvailable() {
        if (loadAttempted) {
            return isAvailable;
        }
        return loadLibrary();
    }

    private static synchronized boolean loadLibrary() {
        if (loadAttempted) {
            return isAvailable;
        }
        loadAttempted = true;
        try {
            System.loadLibrary(LIBRARY_NAME);
            isAvailable = true;
        } catch (SecurityException | UnsatisfiedLinkError e) {
            isAvailable = false;
        }
        return isAvailable;
    }

    public static long getDirectBufferAddress(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || !isAvailable()) {
            return 0L;
        }
        try {
            return nativeGetBufferAddress(buffer);
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return 0L;
        }
    }

    // An address cache does not retain the buffer or take ownership of its native memory.
    private static final class BufferAddressCache {
        private WeakReference<ByteBuffer> lastBufferRef;
        long lastAddress;

        private BufferAddressCache() {
            this.lastBufferRef = new WeakReference<>(null);
        }
    }

    public static long getDirectBufferAddressCached(ByteBuffer buffer) {
        if (buffer == null) {
            return 0L;
        }
        BufferAddressCache cache = targetCache.get();
        ByteBuffer lastBuffer = (ByteBuffer) cache.lastBufferRef.get();
        if (lastBuffer == buffer) {
            return cache.lastAddress;
        }
        long address = getDirectBufferAddress(buffer);
        cache.lastBufferRef = new WeakReference<>(buffer);
        cache.lastAddress = address;
        return address;
    }

    public static boolean copyBetweenAddressesDirect(long sourceAddr, int sourceOffset, long targetAddr, int targetOffset, int length) {
        if (sourceAddr == 0 || targetAddr == 0) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            nativeCopyAddresses(sourceAddr, sourceOffset, targetAddr, targetOffset, length);
            return true;
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return false;
        }
    }

    public static boolean copyBetweenAddresses(ByteBuffer source, int sourceOffset, ByteBuffer target, int targetOffset, int length) {
        long sourceAddr = getDirectBufferAddressCached(source);
        long targetAddr = getDirectBufferAddressCached(target);
        if (sourceAddr == 0 || targetAddr == 0) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            nativeCopyAddresses(sourceAddr, sourceOffset, targetAddr, targetOffset, length);
            return true;
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return false;
        }
    }

    private SampleDataQueueNative() {
    }
}
