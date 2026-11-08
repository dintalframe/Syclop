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
import java.nio.ByteBuffer;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Tees data into a {@link DataSink} as the data is read. */
@UnstableApi
public final class TeeDataSource implements DataSource, ByteBufferDataReader {
  private byte[] byteBufferScratch;


  private final DataSource upstream;
  private final DataSink dataSink;

  private boolean dataSinkNeedsClosing;
  private long bytesRemaining;

  /**
   * @param upstream The upstream {@link DataSource}.
   * @param dataSink The {@link DataSink} into which data is written.
   */
  public TeeDataSource(DataSource upstream, DataSink dataSink) {
    this.upstream = Assertions.checkNotNull(upstream);
    this.dataSink = Assertions.checkNotNull(dataSink);
  }

  @Override
  public void addTransferListener(TransferListener transferListener) {
    Assertions.checkNotNull(transferListener);
    upstream.addTransferListener(transferListener);
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    bytesRemaining = upstream.open(dataSpec);
    if (bytesRemaining == 0) {
      return 0;
    }
    if (dataSpec.length == C.LENGTH_UNSET && bytesRemaining != C.LENGTH_UNSET) {
      // Reconstruct dataSpec in order to provide the resolved length to the sink.
      dataSpec = dataSpec.subrange(0, bytesRemaining);
    }
    dataSinkNeedsClosing = true;
    dataSink.open(dataSpec);
    return bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int bytesRead = upstream.read(buffer, offset, length);
    if (bytesRead > 0) {
      // TODO: Consider continuing even if writes to the sink fail.
      dataSink.write(buffer, offset, bytesRead);
      if (bytesRemaining != C.LENGTH_UNSET) {
        bytesRemaining -= bytesRead;
      }
    }
    return bytesRead;
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
    try {
      upstream.close();
    } finally {
      if (dataSinkNeedsClosing) {
        dataSinkNeedsClosing = false;
        dataSink.close();
      }
    }
  }


    public boolean supportsByteBufferRead() {
        return (this.upstream instanceof ByteBufferDataReader) && ((ByteBufferDataReader) this.upstream).supportsByteBufferRead();
    }

    public int read(ByteBuffer buffer, int length) throws IOException {
        if (!supportsByteBufferRead()) {
            throw new UnsupportedOperationException();
        }
        if (this.bytesRemaining == 0) {
            return -1;
        }
        int startPosition = buffer.position();
        int bytesRead = ((ByteBufferDataReader) this.upstream).read(buffer, length);
        if (bytesRead > 0) {
            writeToSink(buffer, startPosition, bytesRead);
            if (this.bytesRemaining != -1) {
                this.bytesRemaining -= (long) bytesRead;
            }
        }
        return bytesRead;
    }

    private void writeToSink(ByteBuffer buffer, int offset, int length) throws IOException {
        byte[] scratch = getByteBufferScratch(length);
        ByteBuffer duplicate = buffer.duplicate();
        duplicate.position(offset);
        int remaining = length;
        while (remaining > 0) {
            int bytesToWrite = Math.min(remaining, scratch.length);
            duplicate.get(scratch, 0, bytesToWrite);
            dataSink.write(scratch, 0, bytesToWrite);
            remaining -= bytesToWrite;
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
