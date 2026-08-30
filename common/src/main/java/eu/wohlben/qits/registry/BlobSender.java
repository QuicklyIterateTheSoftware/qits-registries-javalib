package eu.wohlben.qits.registry;

import eu.wohlben.qits.blobstore.control.BlobStore;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.WriteStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes a stored blob to a Vert.x response, under the client's backpressure.
 *
 * <p>The serving half of {@link OciRequestBody}, and what replaced {@code sendFile} when blobs
 * stopped being files. {@code sendFile} handed a file region to Netty and returned, so the transfer
 * cost no thread and no heap; a blob in PostgreSQL has neither a path nor a region, and the bytes
 * have to be pulled chunk by chunk by somebody. This class is that somebody, and it is deliberately
 * the <b>calling worker thread</b>: every route that serves a blob is already a {@code
 * blockingHandler}, so the thread is there to be used and the store's reads are blocking JDBC.
 *
 * <p><b>The one thing that must not be got wrong is backpressure.</b> The database answers far
 * faster than a client on a slow link reads, so a loop that wrote without looking would queue the
 * whole blob in Netty's buffers — a gigabyte layer, in heap, per slow puller. So: one {@link
 * WriteStream#writeQueueFull()} check after every write, and the thread parks on {@link
 * WriteStream#drainHandler} until the socket has moved the bytes. The blob's peak cost is one read
 * buffer plus whatever Netty holds below its own watermark, whatever the blob's size.
 *
 * <p>Parking is what makes a dead connection matter: nothing will ever drain, so the wait is bounded
 * by {@code qits.artifacts.blob-send-drain-timeout} and a failed write wakes it at once. Either way
 * the response is closed rather than ended — the same shape {@code sendFile}'s failure handler had,
 * because the headers are long gone by then and there is no status left to send.
 *
 * <p><b>The caller owns the head.</b> Content-Type, Content-Length, ETag and cache headers are the
 * format's business, and a {@code HEAD} must end itself before calling — this class writes a body
 * unconditionally. Content-Length in particular is not optional: Vert.x refuses to write a body
 * without it unless the response was set chunked.
 */
@ApplicationScoped
public class BlobSender {

  private static final Logger LOG = Logger.getLogger(BlobSender.class);

  /**
   * How much is pulled from the store per write. Below the store's 1 MiB chunk so a write never
   * waits on two chunk queries, and far above the socket's watermark so the parking below is what
   * paces the transfer rather than the read size.
   */
  static final int READ_SIZE = 64 * 1024;

  @Inject BlobStore blobStore;

  /**
   * How long to wait for a client to read before giving up on it.
   *
   * <p>This is the ceiling on how long one slow — or vanished — puller can hold a worker thread. It
   * is a wait for the <b>next</b> drain, not for the whole transfer, so a client reading steadily
   * at any speed never meets it.
   */
  @ConfigProperty(name = "qits.artifacts.blob-send-drain-timeout", defaultValue = "PT1M")
  Duration drainTimeout;

  /**
   * Streams the blob's bytes into {@code response} and ends it.
   *
   * <p>Returns only when the client has taken every byte, or when the send was abandoned — an
   * abandoned send is logged at debug and closes the connection, because a client that went away
   * mid-pull is ordinary traffic, not an incident.
   *
   * @param what how this blob is named in the debug log, in the caller's own terms ({@code "npm
   *     tarball left-pad@1.3.0"}), never a bare digest a reader would have to trace back
   * @throws eu.wohlben.qits.blobstore.error.NotFoundException there is no such blob — thrown before
   *     anything is written, so the caller's error handler can still answer it
   */
  public void send(HttpServerResponse response, String blobId, String what) {
    InputStream bytes = blobStore.open(blobId);
    try (bytes) {
      pump(response, bytes, drainTimeout);
      response.end();
    } catch (IOException | RuntimeException aborted) {
      LOG.debugf(aborted, "%s: send aborted after %d bytes", what, response.bytesWritten());
      if (!response.ended()) {
        response.close();
      }
    }
  }

  /**
   * Writes {@code in} to {@code out}, parking this thread whenever the write queue fills.
   *
   * <p>Package-private and on {@link WriteStream} rather than on {@code HttpServerResponse}: the
   * response is one, and a stream a test can hold full at will is the only way to prove that a fast
   * source and a stopped reader do not meet in memory.
   *
   * @return how many bytes were handed to {@code out}
   * @throws SendAborted the stream failed, or the reader stopped for longer than {@code
   *     drainTimeout}
   */
  static long pump(WriteStream<Buffer> out, InputStream in, Duration drainTimeout)
      throws IOException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<CountDownLatch> parked = new AtomicReference<>();
    Handler<Throwable> died =
        thrown -> {
          failure.compareAndSet(null, thrown);
          CountDownLatch waiting = parked.get();
          if (waiting != null) {
            // A write that failed while this thread was parked: nothing will drain now, so end the
            // wait here instead of at the timeout.
            waiting.countDown();
          }
        };

    long sent = 0;
    byte[] chunk;
    // readNBytes returns an exactly-sized fresh array, which Buffer.buffer wraps without copying —
    // so a reused scratch array would be handed to Netty and then overwritten under it.
    while ((chunk = in.readNBytes(READ_SIZE)).length > 0) {
      raise(failure);
      out.write(Buffer.buffer(chunk)).onFailure(died);
      sent += chunk.length;
      if (out.writeQueueFull()) {
        awaitDrain(out, drainTimeout, failure, parked);
      }
    }
    raise(failure);
    return sent;
  }

  /** Parks until the write queue has room again. */
  private static void awaitDrain(
      WriteStream<Buffer> out,
      Duration drainTimeout,
      AtomicReference<Throwable> failure,
      AtomicReference<CountDownLatch> parked) {
    CountDownLatch drained = new CountDownLatch(1);
    parked.set(drained);
    out.drainHandler(ignored -> drained.countDown());
    try {
      // Vert.x calls a drain handler on the transition, not on the state — so a queue that emptied
      // between the caller's check and the line above would never call this one. Re-reading the
      // state now that the handler is installed is what closes that window.
      if (out.writeQueueFull() && failure.get() == null && !await(drained, drainTimeout)) {
        throw new SendAborted("the client read nothing for " + drainTimeout, null);
      }
    } finally {
      parked.set(null);
      out.drainHandler(null);
    }
    raise(failure);
  }

  private static boolean await(CountDownLatch drained, Duration drainTimeout) {
    try {
      return drained.await(drainTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SendAborted("interrupted waiting for the client to read", interrupted);
    }
  }

  private static void raise(AtomicReference<Throwable> failure) {
    Throwable thrown = failure.get();
    if (thrown != null) {
      throw new SendAborted("the connection failed mid-send", thrown);
    }
  }

  /** A send that cannot finish: the connection is gone, or the client stopped reading. */
  static final class SendAborted extends RuntimeException {

    SendAborted(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
