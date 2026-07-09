package net.seninp.jmotif.sax.parallel;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import net.seninp.jmotif.sax.NumerosityReductionStrategy;
import net.seninp.jmotif.sax.SAXException;
import net.seninp.jmotif.sax.TSProcessor;
import net.seninp.jmotif.sax.datastructure.SAXRecords;

public class TestParallelInterruption {

  private static double[] ts;
  private static double[] largeTs;

  private ParallelSAXImplementation ps;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    InputStream fileStream = new FileInputStream("src/resources/dataset/300_signal1.txt.gz");
    InputStream gzipStream = new GZIPInputStream(fileStream);
    Reader decoder = new InputStreamReader(gzipStream, "US-ASCII");
    BufferedReader buffered = new BufferedReader(decoder);
    ts = TSProcessor.readTS(buffered, 0, 10000);

    fileStream = new FileInputStream("src/resources/dataset/300_signal1.txt.gz");
    gzipStream = new GZIPInputStream(fileStream);
    decoder = new InputStreamReader(gzipStream, "US-ASCII");
    buffered = new BufferedReader(decoder);
    largeTs = TSProcessor.readTS(buffered, 0, 0);
  }

  @After
  public void tearDown() {
    if (ps != null) {
      ps.shutdown();
      ps = null;
    }
  }

  @Test
  public void testCancelNoOpAfterCompletion() {
    ps = new ParallelSAXImplementation();
    try {
      @SuppressWarnings("unused")
      SAXRecords res = ps.process(ts, 2, 50, 5, 5, NumerosityReductionStrategy.NONE, 0.005);
      Thread.sleep(1);
      ps.cancel();
    }
    catch (Exception e) {
      fail("Shouldn't throw an exception: " + e);
    }
  }

  @Test
  public void testCancelDuringRunThrowsSAXException() throws Exception {
    ps = new ParallelSAXImplementation();
    AtomicReference<Exception> caught = new AtomicReference<>();

    Thread worker = new Thread(() -> {
      try {
        ps.process(largeTs, 8, 200, 11, 7, NumerosityReductionStrategy.NONE, 0.005);
      }
      catch (Exception e) {
        caught.set(e);
      }
    });

    worker.start();

    while (worker.isAlive()) {
      ps.cancel();
      Thread.sleep(5);
    }
    worker.join(120_000);

    Exception failure = caught.get();
    assertNotNull("expected parallel SAX to fail when cancelled during a run", failure);
    assertTrue("expected SAXException but got " + failure,
        failure instanceof SAXException);
    assertTrue(failure.getMessage().contains("interrupted")
        || failure.getMessage().contains("Interrupted"));
  }
}
