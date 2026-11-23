package org.netbeans.lib.cvsclient.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * A view of the next {@code length} bytes of a shared stream: reads and skips stop at that boundary, and closing it
 * leaves the underlying stream open. Used for the content of one received file, so that a failure while writing the
 * file cannot read into, or skip past, the next response.
 */
public final class BoundedInputStream extends InputStream {

  private final InputStream myInputStream;
  private long myRemaining;

  public BoundedInputStream(InputStream inputStream, long length) {
    myInputStream = inputStream;
    myRemaining = length;
  }

  /** The number of bytes of this file not read or skipped yet. */
  public long remaining() {
    return myRemaining;
  }

  /** Consumes the rest of this file's bytes from the underlying stream. */
  public void drain() throws IOException {
    final byte[] buffer = new byte[8192];
    while (myRemaining > 0) {
      if (read(buffer, 0, (int)Math.min(buffer.length, myRemaining)) < 0) {
        throw new IOException("Unexpected end of stream, " + myRemaining + " bytes of the file missing");
      }
    }
  }

  @Override
  public int read() throws IOException {
    if (myRemaining <= 0) return -1;
    final int result = myInputStream.read();
    if (result >= 0) myRemaining--;
    return result;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (myRemaining <= 0) return -1;
    final int result = myInputStream.read(buffer, offset, (int)Math.min(length, myRemaining));
    if (result > 0) myRemaining -= result;
    return result;
  }

  @Override
  public long skip(long count) throws IOException {
    final long skipped = myInputStream.skip(Math.min(Math.max(count, 0), myRemaining));
    if (skipped > 0) myRemaining -= skipped;
    return skipped;
  }

  @Override
  public int available() throws IOException {
    return (int)Math.min(myInputStream.available(), myRemaining);
  }

  @Override
  public void close() {
  }
}
