package net.seninp.jmotif.sax.parallel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.seninp.jmotif.sax.NumerosityReductionStrategy;
import net.seninp.jmotif.sax.SAXException;
import net.seninp.jmotif.sax.SAXProcessor;
import net.seninp.jmotif.sax.alphabet.NormalAlphabet;
import net.seninp.jmotif.sax.datastructure.SAXRecord;
import net.seninp.jmotif.sax.datastructure.SAXRecords;

/**
 * Implements a parallel SAX factory class.
 *
 * @author psenin
 */
public class ParallelSAXImplementation {

  private static final Logger LOGGER = LoggerFactory.getLogger(ParallelSAXImplementation.class);

  /** Set only while a parallel {@link #process} run is in flight; cleared on exit. */
  private volatile ExecutorService executorService;

  /**
   * Constructor.
   */
  public ParallelSAXImplementation() {
    super();
  }

  /**
   * Discretizes a time series using N threads. Throws {@link SAXException} if a worker is
   * interrupted, a worker fails, or not all chunk results are collected.
   *
   * @param timeseries the input time series.
   * @param threadsNum the number of threads to allocate for conversion.
   * @param slidingWindowSize the SAX sliding window size.
   * @param paaSize the SAX PAA size.
   * @param alphabetSize the SAX alphabet size.
   * @param numRedStrategy the SAX numerosity reduction strategy.
   * @param normalizationThreshold the normalization threshold.
   * @return a SAX representation of the input time series.
   * @throws SAXException if error occurs.
   */
  public SAXRecords process(double[] timeseries, int threadsNum, int slidingWindowSize, int paaSize,
      int alphabetSize, NumerosityReductionStrategy numRedStrategy, double normalizationThreshold)
      throws SAXException {

    LOGGER.debug("Starting the parallel SAX");

    NormalAlphabet na = new NormalAlphabet();
    SAXProcessor sp = new SAXProcessor();

    int evenIncrement = timeseries.length / threadsNum;
    // The chunking/merge logic below assumes at least two chunks (a distinct first and last
    // chunk). With a single thread there is exactly one chunk worth of work, so the "last chunk"
    // would be submitted as a second task writing completedChunks[1] on a size-1 array, and the
    // first chunk would over-read the series past its end. Route the single-threaded case (and
    // any chunk that is too small to yield a window start) to the sequential implementation.
    if (threadsNum <= 1 || evenIncrement <= slidingWindowSize) {
      LOGGER.warn("Unable to run with {} threads. Rolling back to single-threaded implementation.",
          threadsNum);
      return sp.ts2saxViaWindow(timeseries, slidingWindowSize, paaSize, na.getCuts(alphabetSize),
          numRedStrategy, normalizationThreshold);
    }

    //
    // Numerosity reduction (EXACT and MINDIST) is order-dependent and therefore cannot be applied
    // safely inside the parallel workers, whose results merge in nondeterministic completion order.
    // Each worker always runs with NONE so the full, contiguous window-start sequence is
    // reconstructed regardless of merge order; the requested reduction is then applied as a single
    // deterministic post-pass over the merged, index-sorted result (identical to the sequential
    // implementation in SAXProcessor.ts2saxViaWindow).
    //
    NumerosityReductionStrategy nrStrategy = NumerosityReductionStrategy.NONE;

    ExecutorService pool = Executors.newFixedThreadPool(threadsNum);
    executorService = pool;
    LOGGER.debug("Created thread pool of {} threads", threadsNum);

    try {
      SAXRecords res = new SAXRecords(0);
      ExecutorCompletionService<HashMap<Integer, char[]>> completionService = new ExecutorCompletionService<HashMap<Integer, char[]>>(
          pool);

      int totalTaskCounter = 0;
      final long tstamp = System.currentTimeMillis();

      int reminder = timeseries.length % threadsNum;
      int firstChunkSize = evenIncrement + reminder;
      LOGGER.debug("data size {}, evenIncrement {}, reminder {}, firstChunkSize {}",
          timeseries.length, evenIncrement, reminder, firstChunkSize);

      {
        int firstChunkStart = 0;
        int firstChunkEnd = (firstChunkSize - 1) + slidingWindowSize;
        final SAXWorker job0 = new SAXWorker(tstamp + totalTaskCounter, timeseries, firstChunkStart,
            firstChunkEnd, slidingWindowSize, paaSize, alphabetSize, nrStrategy,
            normalizationThreshold);
        completionService.submit(job0);
        LOGGER.debug("submitted first chunk job {}", tstamp);
        totalTaskCounter++;
      }

      while (totalTaskCounter < threadsNum - 1) {
        int intermediateChunkStart = (firstChunkSize - 1) + (totalTaskCounter - 1) * evenIncrement
            + 1;
        int intermediateChunkEnd = (firstChunkSize - 1) + (totalTaskCounter * evenIncrement)
            + slidingWindowSize;
        final SAXWorker job = new SAXWorker(tstamp + totalTaskCounter, timeseries,
            intermediateChunkStart, intermediateChunkEnd, slidingWindowSize, paaSize, alphabetSize,
            nrStrategy, normalizationThreshold);
        completionService.submit(job);
        LOGGER.debug("submitted intermediate chunk job {}", (tstamp + totalTaskCounter));
        totalTaskCounter++;
      }

      {
        int lastChunkStart = timeseries.length - evenIncrement;
        int lastChunkEnd = timeseries.length;
        final SAXWorker jobN = new SAXWorker(tstamp + totalTaskCounter, timeseries, lastChunkStart,
            lastChunkEnd, slidingWindowSize, paaSize, alphabetSize, nrStrategy,
            normalizationThreshold);
        completionService.submit(jobN);
        LOGGER.debug("submitted last chunk job {}", (tstamp + totalTaskCounter));
        totalTaskCounter++;
      }

      pool.shutdown();

      while (totalTaskCounter > 0) {

        if (Thread.currentThread().isInterrupted()) {
          LOGGER.info("Parallel SAX being interrupted");
          pool.shutdownNow();
          Thread.currentThread().interrupt();
          throw new SAXException("Parallel SAX conversion was interrupted");
        }

        Future<HashMap<Integer, char[]>> finished = completionService.poll(24, TimeUnit.HOURS);

        if (null == finished) {
          LOGGER.error("Parallel SAX timed out waiting for worker results");
          pool.shutdownNow();
          throw new SAXException(
              "Parallel SAX conversion timed out waiting for worker results");
        }

        HashMap<Integer, char[]> chunkRes = finished.get();

        if (null == chunkRes) {
          pool.shutdownNow();
          throw new SAXException("Parallel SAX worker was interrupted before completing its chunk");
        }

        LOGGER.debug("job with stamp {} has finished", chunkRes.get(-1));

        chunkRes.remove(-1);

        res.addAll(chunkRes);
        totalTaskCounter--;
      }

      if (NumerosityReductionStrategy.EXACT.equals(numRedStrategy)
          || NumerosityReductionStrategy.MINDIST.equals(numRedStrategy)) {

        SAXRecords newRes = new SAXRecords();
        ArrayList<Integer> keys = res.getAllIndices();
        char[] previousStr = null;
        for (int i : keys) {

          SAXRecord entry = res.getByIndex(i);

          if (null != previousStr) {
            if (NumerosityReductionStrategy.EXACT.equals(numRedStrategy)
                && Arrays.equals(entry.getPayload(), previousStr)) {
              continue;
            }
            else if (NumerosityReductionStrategy.MINDIST.equals(numRedStrategy)
                && sp.checkMinDistIsZero(entry.getPayload(), previousStr)) {
              continue;
            }
          }

          newRes.add(entry.getPayload(), i);
          previousStr = entry.getPayload();
        }

        res = newRes;
      }

      return res;
    }
    catch (InterruptedException e) {
      LOGGER.error("Error while waiting results.", e);
      pool.shutdownNow();
      Thread.currentThread().interrupt();
      throw new SAXException("Parallel SAX conversion was interrupted", e);
    }
    catch (ExecutionException e) {
      LOGGER.error("Parallel SAX worker failed.", e);
      pool.shutdownNow();
      throw new SAXException("Parallel SAX worker failed", e.getCause());
    }
    finally {
      awaitPoolTermination(pool);
      executorService = null;
    }
  }

  /**
   * Cancels an in-flight {@link #process} run by shutting down the active worker pool. Safe to call
   * when no run is active (no-op).
   */
  public void cancel() {
    ExecutorService pool = executorService;
    if (pool == null || pool.isShutdown()) {
      return;
    }
    LOGGER.info("Parallel SAX cancel requested");
    pool.shutdownNow();
    awaitPoolTermination(pool);
  }

  private static void awaitPoolTermination(ExecutorService pool) {
    if (pool == null) {
      return;
    }
    try {
      if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
        pool.shutdownNow();
        if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
          LOGGER.error("Parallel SAX pool did not terminate");
        }
      }
    }
    catch (InterruptedException ie) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
