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
package androidx.media3.exoplayer.upstream;

import androidx.annotation.Nullable;
import dalvik.annotation.optimization.FastNative;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Preferred maintained reconstruction of the custom reference binary. */
public final class DefaultAllocatorNative {
    private static final String LIBRARY_NAME = "media3_exoplayer_jni";
    private static volatile boolean loadAttempted;
    private static volatile boolean isAvailable;
    private static final int ARENA_CHUNK_SIZE = 65536;
    private static final int ARENA_MAX_CHUNKS = 512;
    private static final int ARENA_MIN_CHUNKS = 64;
    private static final Queue<Allocation> arenaPool = new ConcurrentLinkedQueue<>();
    private static volatile long arenaBaseAddress = 0;
    private static volatile long arenaEndAddress = 0;
    private static Allocation arenaBaseAllocation = null;
    private static volatile boolean arenaInitialized = false;

    @FastNative
    private static native Allocation nativeCreateAllocation(int size);

    @FastNative
    private static native void nativeFreeAllocation(long nativeHandle);

    private static synchronized void initializeArena() {
        if (arenaInitialized) {
            return;
        }
        arenaInitialized = true;
        if (!isAvailable()) {
            return;
        }
        int chunks = ARENA_MAX_CHUNKS;
        Allocation base = null;
        while (chunks >= ARENA_MIN_CHUNKS) {
            try {
                base = nativeCreateAllocation(chunks * ARENA_CHUNK_SIZE);
            } catch (Exception | UnsatisfiedLinkError e) {
                base = null;
            }
            if (base != null && base.buffer != null) {
                break;
            }
            base = null;
            chunks >>= 1;
        }
        if (base == null) {
            return;
        }
        // The base owns the memory; pooled chunks are slices that must only be recycled.
        arenaBaseAllocation = base;
        arenaBaseAddress = base.nativeHandle;
        arenaEndAddress = arenaBaseAddress + (((long) chunks) * 65536);
        ByteBuffer baseBuffer = base.buffer;
        for (int i = 0; i < chunks; i++) {
            int offset = i * ARENA_CHUNK_SIZE;
            ByteBuffer chunkBuffer = baseBuffer.duplicate();
            chunkBuffer.position(offset);
            chunkBuffer.limit(offset + ARENA_CHUNK_SIZE);
            ByteBuffer sliced = chunkBuffer.slice();
            long chunkAddress = arenaBaseAddress + ((long) offset);
            Allocation chunkAllocation = new Allocation(sliced, 0, chunkAddress);
            arenaPool.offer(chunkAllocation);
        }
    }

    @Nullable
    private static Allocation createAllocationDirect(int size) {
        if (!isAvailable()) {
            return null;
        }
        try {
            return nativeCreateAllocation(size);
        } catch (UnsatisfiedLinkError e) {
            isAvailable = false;
            return null;
        }
    }

    @Nullable
    public static Allocation createAllocation(int size) {
        Allocation allocation;
        if (size == ARENA_CHUNK_SIZE) {
            if (!arenaInitialized) {
                initializeArena();
            }
            if (arenaBaseAddress != 0) {
                allocation = arenaPool.poll();
                if (allocation != null) {
                    return allocation;
                }
            }
        }
        return createAllocationDirect(size);
    }

    public static void freeAllocation(Allocation allocation) {
        long nativeHandle = allocation.nativeHandle;
        if (nativeHandle == 0) {
            return;
        }
        if (nativeHandle >= arenaBaseAddress && nativeHandle < arenaEndAddress) {
            allocation.nativeHandle = 0L;
            // Clear the retired wrapper before publishing a new live wrapper for the same slice.
            Allocation recAllocation = new Allocation(allocation.buffer, allocation.offset, nativeHandle);
            arenaPool.offer(recAllocation);
            return;
        }
        allocation.nativeHandle = 0L;
            try {
                nativeFreeAllocation(nativeHandle);
            } catch (UnsatisfiedLinkError e) {
                isAvailable = false;
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

    private DefaultAllocatorNative() {
    }
}
