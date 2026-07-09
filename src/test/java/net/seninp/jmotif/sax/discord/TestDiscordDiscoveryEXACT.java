package net.seninp.jmotif.sax.discord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import net.seninp.jmotif.sax.NumerosityReductionStrategy;
import net.seninp.jmotif.sax.TSProcessor;
import net.seninp.util.StackTrace;

public class TestDiscordDiscoveryEXACT {

  private static final String TEST_DATA_FNAME = "src/resources/test-data/ecg0606_1.csv";

  private static final int WIN_SIZE = 100;
  private static final int PAA_SIZE = 3;
  private static final int ALPHABET_SIZE = 3;

  private static final double NORM_THRESHOLD = 0.01;

  private static final int DISCORDS_TO_TEST = 5;

  private static final NumerosityReductionStrategy STRATEGY = NumerosityReductionStrategy.EXACT;

  // logging stuff
  //
  private static final Logger LOGGER;
  private static final Level LOGGING_LEVEL = Level.INFO;

  static {
    LOGGER = (Logger) LoggerFactory.getLogger(TestDiscordDiscoveryEXACT.class);
    LOGGER.setLevel(LOGGING_LEVEL);
  }

  private double[] series;

  @Before
  public void setUp() throws Exception {
    series = TSProcessor.readFileColumn(TEST_DATA_FNAME, 0, 0);
  }

  @Test
  public void test() {

    DiscordRecords discordsHash = null;

    try {

      discordsHash = HOTSAXImplementation.series2Discords(series, DISCORDS_TO_TEST, WIN_SIZE,
          PAA_SIZE, ALPHABET_SIZE, STRATEGY, NORM_THRESHOLD);
      for (DiscordRecord d : discordsHash) {
        LOGGER.debug("hotsax hash discord " + d.toString());
      }

    }
    catch (Exception e) {
      fail("shouldn't throw an exception, exception thrown: \n" + StackTrace.toString(e));
      e.printStackTrace();
    }

    // Golden anchor for the top discord (z-normed /n, full series): without an
    // absolute value, a uniform shift would pass unnoticed. (Secondary discords
    // legitimately differ from the brute force under EXACT numerosity reduction,
    // so only the top discord is pinned.)
    assertEquals(430, discordsHash.get(0).getPosition());
    assertEquals(5.279080, discordsHash.get(0).getNNDistance(), 1e-6);
  }
}
