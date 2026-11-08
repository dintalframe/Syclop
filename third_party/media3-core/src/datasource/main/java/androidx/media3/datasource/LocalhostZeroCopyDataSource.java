// Syclop maintained reconstruction of custom Media3 transport changes; not historical private source.
package androidx.media3.datasource;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.ByteBufferDataReader;
import androidx.media3.common.util.Util;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class LocalhostZeroCopyDataSource extends BaseDataSource implements ByteBufferDataReader {

    @Nullable
    private SocketChannel socketChannel;

    @Nullable
    private Uri uri;

    @Nullable
    private DataSpec dataSpec;
    private long bytesRemaining;
    private boolean opened;

    @Nullable
    private ByteBuffer excessBuffer;

    public LocalhostZeroCopyDataSource() {
        super(true);
        this.bytesRemaining = -1L;
    }

    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        this.dataSpec = dataSpec;
        this.uri = dataSpec.uri;
        transferInitializing(dataSpec);
        String host = this.uri.getHost();
        if (host == null) {
            host = "127.0.0.1";
        }
        int port = this.uri.getPort();
        if (port == -1) {
            port = 8090;
        }
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.connect(new InetSocketAddress(host, port));
            this.socketChannel = channel;
            String path = this.uri.getPath();
            if (this.uri.getQuery() != null) {
                path = path + "?" + this.uri.getQuery();
            }
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String rangeHeader = "";
            if (dataSpec.position != 0) {
                rangeHeader = "Range: bytes=" + dataSpec.position + "-\r\n";
            }
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n" + rangeHeader + "Connection: close\r\n\r\n";
            ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII));
            while (requestBuffer.hasRemaining()) {
                channel.write(requestBuffer);
            }
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            int headerEndIndex = -1;
            while (true) {
                int read = channel.read(buffer);
                if (read <= 0) {
                    break;
                }
                byte[] array = buffer.array();
                int limit = buffer.position();
                for (int i = 0; i <= limit - 4; i++) {
                    if (array[i] == 13 && array[i + 1] == 10 && array[i + 2] == 13 && array[i + 3] == 10) {
                        headerEndIndex = i + 4;
                        break;
                    }
                }
                if (headerEndIndex != -1) {
                    break;
                }
                if (buffer.position() == buffer.capacity()) {
                    ByteBuffer newBuffer = ByteBuffer.allocate(buffer.capacity() * 2);
                    buffer.flip();
                    newBuffer.put(buffer);
                    buffer = newBuffer;
                }
            }
            if (headerEndIndex == -1) {
                throw new HttpDataSource.HttpDataSourceException("Malformed HTTP response: headers separator not found", dataSpec, 2007, 1);
            }
            int totalRead = buffer.position();
            int excessBytes = totalRead - headerEndIndex;
            if (excessBytes > 0) {
                this.excessBuffer = ByteBuffer.allocate(excessBytes);
                this.excessBuffer.put(buffer.array(), headerEndIndex, excessBytes);
                this.excessBuffer.flip();
            } else {
                this.excessBuffer = null;
            }
            byte[] headerBytes = buffer.array();
            int firstLineEnd = -1;
            for (int i2 = 0; i2 < headerEndIndex; i2++) {
                if (headerBytes[i2] == 13) {
                    firstLineEnd = i2;
                    break;
                }
            }
            if (firstLineEnd == -1) {
                firstLineEnd = headerEndIndex;
            }
            boolean isSuccess = false;
            for (int i3 = 0; i3 <= firstLineEnd - 5; i3++) {
                if (headerBytes[i3] == 32 && headerBytes[i3 + 4] == 32 && ((headerBytes[i3 + 1] == 50 && headerBytes[i3 + 2] == 48 && headerBytes[i3 + 3] == 48) || (headerBytes[i3 + 1] == 50 && headerBytes[i3 + 2] == 48 && headerBytes[i3 + 3] == 54))) {
                    isSuccess = true;
                    break;
                }
            }
            if (!isSuccess) {
                String firstLine = new String(headerBytes, 0, firstLineEnd, StandardCharsets.US_ASCII);
                throw new HttpDataSource.InvalidResponseCodeException(firstLine.contains(" 416 ") ? 416 : 400, firstLine, null, Collections.emptyMap(), dataSpec, Util.EMPTY_BYTE_ARRAY);
            }
            if (dataSpec.length != -1) {
                this.bytesRemaining = dataSpec.length;
            } else {
                long contentLength = -1;
                byte[] targetLower = {99, 111, 110, 116, 101, 110, 116, 45, 108, 101, 110, 103, 116, 104, 58};
                for (int i4 = 0; i4 <= headerEndIndex - targetLower.length; i4++) {
                    boolean match = true;
                    for (int j = 0; j < targetLower.length; j++) {
                        byte b = headerBytes[i4 + j];
                        int c = (b >= 65 && b <= 90) ? b + 32 : b;
                        if (c != targetLower[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        int start = i4 + targetLower.length;
                        while (start < headerEndIndex && (headerBytes[start] == 32 || headerBytes[start] == 9)) {
                            start++;
                        }
                        long num = 0;
                        boolean hasDigits = false;
                        while (start < headerEndIndex && headerBytes[start] >= 48 && headerBytes[start] <= 57) {
                            num = (num * 10) + ((long) (headerBytes[start] - 48));
                            hasDigits = true;
                            start++;
                        }
                        if (hasDigits) {
                            contentLength = num;
                        }
                        break;
                    }
                }
                this.bytesRemaining = contentLength >= 0 ? contentLength : -1L;
            }
            this.opened = true;
            transferStarted(dataSpec);
            return this.bytesRemaining;
        } catch (HttpDataSource.HttpDataSourceException e) {
            close();
            throw e;
        } catch (IOException e2) {
            close();
            throw new HttpDataSource.HttpDataSourceException(e2, dataSpec, 2001, 1);
        }
    }

    public boolean supportsByteBufferRead() {
        return true;
    }

    public int read(ByteBuffer buffer, int length) throws HttpDataSource.HttpDataSourceException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return -1;
        }
        SocketChannel channel = socketChannel;
        if (channel == null) {
            return -1;
        }
        int readLength = length;
        if (bytesRemaining != -1) {
            readLength = (int) Math.min(bytesRemaining, length);
        }
        int excessBytesRead = 0;
        if (excessBuffer != null && excessBuffer.hasRemaining()) {
            excessBytesRead = Math.min(excessBuffer.remaining(), readLength);
            int originalLimit = excessBuffer.limit();
            excessBuffer.limit(excessBuffer.position() + excessBytesRead);
            buffer.put(excessBuffer);
            excessBuffer.limit(originalLimit);
            if (!excessBuffer.hasRemaining()) {
                excessBuffer = null;
            }
            readLength -= excessBytesRead;
            if (bytesRemaining != -1) {
                bytesRemaining -= excessBytesRead;
            }
            bytesTransferred(excessBytesRead);
            if (readLength == 0) {
                return excessBytesRead;
            }
        }
        if (readLength > 0) {
            int originalLimit = buffer.limit();
            buffer.limit(buffer.position() + readLength);
            int bytesRead;
            try {
                bytesRead = channel.read(buffer);
            } catch (IOException e) {
                throw new HttpDataSource.HttpDataSourceException(e, Util.castNonNull(dataSpec), 2001, 2);
            } finally {
                buffer.limit(originalLimit);
            }
            if (bytesRead < 0) {
                return excessBytesRead > 0 ? excessBytesRead : -1;
            }
            if (bytesRemaining != -1) {
                bytesRemaining -= bytesRead;
            }
            bytesTransferred(bytesRead);
            return excessBytesRead + bytesRead;
        }
        return excessBytesRead;
    }

    public int read(byte[] buffer, int offset, int length) throws HttpDataSource.HttpDataSourceException {
        ByteBuffer wrap = ByteBuffer.wrap(buffer, offset, length);
        return read(wrap, length);
    }

    @Nullable
    public Uri getUri() {
        return this.uri;
    }

    public void close() {
        this.dataSpec = null;
        this.uri = null;
        this.excessBuffer = null;
        try {
            if (this.socketChannel != null) {
                this.socketChannel.close();
            }
        } catch (IOException e) {
        } finally {
            this.socketChannel = null;
            if (this.opened) {
                this.opened = false;
                transferEnded();
            }
        }
    }
}
