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
import androidx.media3.common.NuvioEngineConfig;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.util.Arrays;

@UnstableApi
public final class DefaultAllocator implements Allocator {
    private static final int AVAILABLE_EXTRA_CAPACITY = 100;
    private final boolean trimOnReset;
    private final int individualAllocationSize;
    private final boolean forceNativeAllocation;

    @Nullable
    private final byte[] initialAllocationBlock;
    private final @NullableType Allocation[] initialAllocations;
    private int targetBufferSize;
    private int allocatedCount;
    private int availableCount;
    private @NullableType Allocation[] availableAllocations;

    public DefaultAllocator(boolean trimOnReset, int individualAllocationSize) {
        this(trimOnReset, individualAllocationSize, 0, false);
    }

    public DefaultAllocator(boolean trimOnReset, int individualAllocationSize, int initialAllocationCount) {
        this(trimOnReset, individualAllocationSize, initialAllocationCount, false);
    }

    public DefaultAllocator(boolean trimOnReset, int individualAllocationSize, int initialAllocationCount, boolean forceNativeAllocation) {
        Assertions.checkArgument(individualAllocationSize > 0);
        Assertions.checkArgument(initialAllocationCount >= 0);
        this.trimOnReset = trimOnReset;
        this.individualAllocationSize = individualAllocationSize;
        this.forceNativeAllocation = forceNativeAllocation;
        this.availableCount = initialAllocationCount;
        this.availableAllocations = new Allocation[initialAllocationCount + AVAILABLE_EXTRA_CAPACITY];
        boolean useNativeAllocation = forceNativeAllocation || NuvioEngineConfig.get().isNativeAllocationEnabled();
        if (initialAllocationCount > 0 && !useNativeAllocation) {
            this.initialAllocationBlock = new byte[initialAllocationCount * individualAllocationSize];
            this.initialAllocations = null;
            for (int i = 0; i < initialAllocationCount; i++) {
                this.availableAllocations[i] = new Allocation(this.initialAllocationBlock, i * individualAllocationSize);
            }
        } else if (initialAllocationCount > 0) {
            this.initialAllocationBlock = null;
            this.initialAllocations = new Allocation[initialAllocationCount];
            for (int i = 0; i < initialAllocationCount; i++) {
                Allocation allocation = createAllocation(individualAllocationSize);
                this.availableAllocations[i] = allocation;
                this.initialAllocations[i] = allocation;
            }
        } else {
            this.initialAllocationBlock = null;
            this.initialAllocations = null;
        }
    }

    public synchronized void reset() {
        if (this.trimOnReset) {
            setTargetBufferSize(0);
        }
    }

    public synchronized void setTargetBufferSize(int targetBufferSize) {
        boolean targetBufferSizeReduced = targetBufferSize < this.targetBufferSize;
        this.targetBufferSize = targetBufferSize;
        if (targetBufferSizeReduced) {
            trim();
        }
    }

    public synchronized Allocation allocate() {
    allocatedCount++;
    Allocation allocation;
    if (availableCount > 0) {
      allocation = Assertions.checkNotNull(availableAllocations[--availableCount]);
      availableAllocations[availableCount] = null;
    } else {
      allocation = createAllocation(individualAllocationSize);
      if (allocatedCount > availableAllocations.length) {
        // Make availableAllocations be large enough to contain all allocations made by this
        // allocator so that release() does not need to grow the availableAllocations array. See
        // [Internal ref: b/209801945].
        availableAllocations = Arrays.copyOf(availableAllocations, availableAllocations.length * 2);
      }
    }
    return allocation;
  }

    public synchronized void release(Allocation allocation) {
    availableAllocations[availableCount++] = allocation;
    allocatedCount--;
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

    public synchronized void release(@Nullable AllocationNode allocationNode) {
    while (allocationNode != null) {
      availableAllocations[availableCount++] = allocationNode.getAllocation();
      allocatedCount--;
      allocationNode = allocationNode.next();
    }
    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

    public synchronized void trim() {
        int targetAllocationCount = Util.ceilDivide(this.targetBufferSize, this.individualAllocationSize);
        int targetAvailableCount = Math.max(0, targetAllocationCount - this.allocatedCount);
        if (targetAvailableCount >= this.availableCount) {
            return;
        }
        if (this.initialAllocationBlock != null || this.initialAllocations != null) {
            int lowIndex = 0;
            int highIndex = this.availableCount - 1;
            while (lowIndex <= highIndex) {
                Allocation lowAllocation = (Allocation) Assertions.checkNotNull(this.availableAllocations[lowIndex]);
                if (isInitialAllocation(lowAllocation)) {
                    lowIndex++;
                } else {
                    Allocation highAllocation = (Allocation) Assertions.checkNotNull(this.availableAllocations[highIndex]);
                    if (!isInitialAllocation(highAllocation)) {
                        highIndex--;
                    } else {
                        this.availableAllocations[lowIndex++] = highAllocation;
                        this.availableAllocations[highIndex--] = lowAllocation;
                    }
                }
            }
            targetAvailableCount = Math.max(targetAvailableCount, lowIndex);
            if (targetAvailableCount >= this.availableCount) {
                return;
            }
        }
        for (int i = targetAvailableCount; i < this.availableCount; i++) {
            Allocation allocation = this.availableAllocations[i];
            if (allocation != null && allocation.buffer != null) {
                freeAllocation(allocation);
            }
        }
        Arrays.fill(this.availableAllocations, targetAvailableCount, this.availableCount, (Object) null);
        this.availableCount = targetAvailableCount;
    }

    public synchronized int getTotalBytesAllocated() {
        return this.allocatedCount * this.individualAllocationSize;
    }

    public synchronized int getAvailableBytes() {
        return this.availableCount * this.individualAllocationSize;
    }

    public synchronized int getMemoryFootprint() {
        return (this.allocatedCount + this.availableCount) * this.individualAllocationSize;
    }

    public int getIndividualAllocationLength() {
        return this.individualAllocationSize;
    }

    // Direct fallback is intentional: native mode keeps direct storage when JNI is unavailable.
    private Allocation createAllocation(int size) {
        if (!this.forceNativeAllocation && !NuvioEngineConfig.get().isNativeAllocationEnabled()) {
            return new Allocation(new byte[size], 0);
        }
        Allocation allocation = DefaultAllocatorNative.createAllocation(size);
        return allocation != null
            ? allocation : new Allocation(ByteBuffer.allocateDirect(size), 0);
    }

    private boolean isInitialAllocation(Allocation allocation) {
        if (this.initialAllocationBlock != null && allocation.data == this.initialAllocationBlock) {
            return true;
        }
        if (this.initialAllocations != null) {
            for (Allocation initialAllocation : this.initialAllocations) {
                if (allocation == initialAllocation) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void freeAllocation(Allocation allocation) {
        if (allocation.nativeHandle != 0) {
            DefaultAllocatorNative.freeAllocation(allocation);
        }
    }
}
