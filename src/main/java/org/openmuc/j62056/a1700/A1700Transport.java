package org.openmuc.j62056.a1700;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/** One reader per port; deadline uses System.nanoTime(). The caller owns/ closes the port. */
public interface A1700Transport {
    void send(byte[] bytes) throws IOException;
    int readByte(long deadlineNanos) throws IOException;
    String name();

    final class Streams implements A1700Transport {
        private final InputStream input;
        private final OutputStream output;
        private final String name;
        private final boolean flush;
        public Streams(InputStream input, OutputStream output, String name, boolean flush) {
            this.input = input; this.output = output; this.name = name; this.flush = flush;
        }
        @Override public String name() { return name; }
        @Override public void send(byte[] bytes) throws IOException {
            output.write(bytes);
            // Disabled for the installed RXTX nativeDrain implementation; direct device streams can flush.
            if (flush) output.flush();
        }
        @Override public int readByte(long deadline) throws IOException {
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Serial read interrupted");
                if (input.available() > 0) {
                    int value = input.read();
                    if (value < 0) throw new IOException("Serial device disconnected / EOF");
                    return value;
                }
                try { Thread.sleep(2); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new InterruptedIOException("Serial read interrupted");
                }
            }
            throw new SocketTimeoutException("Serial response deadline exceeded");
        }
    }
}
