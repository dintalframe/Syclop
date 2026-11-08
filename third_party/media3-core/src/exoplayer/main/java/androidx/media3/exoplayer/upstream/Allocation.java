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
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

@UnstableApi
public final class Allocation {

    @Nullable
    public final byte[] data;

    @Nullable
    public final ByteBuffer buffer;
    public final int offset;
    public long nativeHandle;

    public Allocation(@Nullable byte[] data, int offset) {
        this.data = data;
        this.buffer = null;
        this.offset = offset;
        this.nativeHandle = 0L;
    }

    public Allocation(@Nullable ByteBuffer buffer, int offset) {
        this.data = null;
        this.buffer = buffer;
        this.offset = offset;
        this.nativeHandle = 0L;
    }

    Allocation(@Nullable ByteBuffer buffer, int offset, long nativeHandle) {
        this.data = null;
        this.buffer = buffer;
        this.offset = offset;
        this.nativeHandle = nativeHandle;
    }
}
