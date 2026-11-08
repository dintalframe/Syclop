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
// Syclop maintained reconstruction of custom Media3 transport changes; not historical private source.
package androidx.media3.datasource;

import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.castNonNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;

/** A {@link DataSource} that decrypts the data read from an upstream source. */
@UnstableApi
public final class AesCipherDataSource implements DataSource, ByteBufferDataReader {
  private byte[] byteBufferScratch;


  private final DataSource upstream;
  private final byte[] secretKey;

  @Nullable private AesFlushingCipher cipher;

  public AesCipherDataSource(byte[] secretKey, DataSource upstream) {
    this.upstream = upstream;
    this.secretKey = secretKey;
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    checkNotNull(transferListener);
    upstream.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    long dataLength = upstream.open(dataSpec);
    cipher =
        new AesFlushingCipher(
            Cipher.DECRYPT_MODE,
            secretKey,
            dataSpec.key,
            dataSpec.uriPositionOffset + dataSpec.position);
    return dataLength;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    int read = upstream.read(buffer, offset, length);
    if (read == C.RESULT_END_OF_INPUT) {
      return C.RESULT_END_OF_INPUT;
    }
    castNonNull(cipher).updateInPlace(buffer, offset, read);
    return read;
  }

  @Override
  @Nullable
  public Uri getUri() {
    return upstream.getUri();
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    cipher = null;
    upstream.close();
  }


    public boolean supportsByteBufferRead() {
        return (this.upstream instanceof ByteBufferDataReader) && ((ByteBufferDataReader) this.upstream).supportsByteBufferRead();
    }

    public int read(ByteBuffer buffer, int length) throws IOException {
        if (!supportsByteBufferRead()) {
            throw new UnsupportedOperationException();
        }
        if (length == 0) {
            return 0;
        }
        int startPosition = buffer.position();
        int read = ((ByteBufferDataReader) this.upstream).read(buffer, length);
        if (read == -1) {
            return -1;
        }
        updateInPlace(buffer, startPosition, read);
        return read;
    }

    private void updateInPlace(ByteBuffer buffer, int offset, int length) {
        if (buffer.hasArray()) {
            ((AesFlushingCipher) Util.castNonNull(this.cipher)).updateInPlace(buffer.array(), buffer.arrayOffset() + offset, length);
            return;
        }
        byte[] scratch = getByteBufferScratch(length);
        int remaining = length;
        int scratchOffset = offset;
        while (remaining > 0) {
            int toUpdate = Math.min(remaining, scratch.length);
            ByteBuffer duplicate = buffer.duplicate();
            duplicate.position(scratchOffset);
            duplicate.get(scratch, 0, toUpdate);
            ((AesFlushingCipher) Util.castNonNull(this.cipher)).updateInPlace(scratch, 0, toUpdate);
            duplicate.position(scratchOffset);
            duplicate.put(scratch, 0, toUpdate);
            scratchOffset += toUpdate;
            remaining -= toUpdate;
        }
    }

    private byte[] getByteBufferScratch(int length) {
        int scratchLength = Math.min(length, 16384);
        if (this.byteBufferScratch == null || this.byteBufferScratch.length < scratchLength) {
            this.byteBufferScratch = new byte[scratchLength];
        }
        return this.byteBufferScratch;
    }
}
