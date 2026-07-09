package net.seninp.jmotif.sax.motif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import net.seninp.jmotif.SlowTests;
import net.seninp.jmotif.sax.TSProcessor;
import net.seninp.util.StackTrace;

public class TestMotifDiscovery {

  private static final String TEST_DATA_FNAME = "src/resources/test-data/ecg0606_1.csv";

  private static final int MOTIF_SIZE = 100;

  private static final double MOTIF_RANGE = 1.5;

  private static final double ZNORM_THRESHOLD = 0.01;

  /** Prefix length keeps brute-force motif search fast in the default test suite. */
  private static final int SERIES_PREFIX = 800;

  private double[] series;

  @Before
  public void setUp() throws Exception {
    double[] full = TSProcessor.readFileColumn(TEST_DATA_FNAME, 0, 0);
    series = java.util.Arrays.copyOf(full, Math.min(SERIES_PREFIX, full.length));
  }

  @Test
  public void testEMMA() {
    MotifRecord motifsBF;
    MotifRecord motifsEMMA;
    try {
      motifsBF = BruteForceMotifImplementation.series2BruteForceMotifs(series, MOTIF_SIZE,
          MOTIF_RANGE, ZNORM_THRESHOLD);

      motifsEMMA = EMMAImplementation.series2EMMAMotifs(series, MOTIF_SIZE, MOTIF_RANGE, 6, 4,
          ZNORM_THRESHOLD);

      assertEquals("Asserting motif frequency", motifsBF.getFrequency(), motifsEMMA.getFrequency());

      for (Integer m : motifsBF.getOccurrences()) {
        assertTrue("Asserting motif locations", motifsEMMA.getOccurrences().contains(m));
      }

    }
    catch (Exception e) {
      fail("It shouldnt fail, but failed with " + StackTrace.toString(e));
    }
  }

  /**
   * Sweep several motif-size / range / PAA / alphabet combinations and assert
   * EMMA agrees with the brute-force oracle on every one. Excluded from default
   * {@code mvn test} ({@link SlowTests}); run with {@code mvn test -Pslow-tests}.
   */
  @Test
  @Category(SlowTests.class)
  public void testEMMAvsBruteForceSweep() {
    try {
      int[] motifSizes = { 60, 100 };
      double[] ranges = { 0.8, 1.5, 2.5, 3.5 };
      int[] paaSizes = { 4, 6 };
      int[] alphabetSizes = { 4, 5 };
      for (int ms : motifSizes) {
        for (double r : ranges) {
          for (int paa : paaSizes) {
            for (int alpha : alphabetSizes) {
              MotifRecord bf = BruteForceMotifImplementation.series2BruteForceMotifs(series, ms, r,
                  ZNORM_THRESHOLD);
              MotifRecord em = EMMAImplementation.series2EMMAMotifs(series, ms, r, paa, alpha,
                  ZNORM_THRESHOLD);
              String cfg = "ms=" + ms + " r=" + r + " paa=" + paa + " alpha=" + alpha;
              assertEquals("EMMA frequency must match brute force for " + cfg, bf.getFrequency(),
                  em.getFrequency());
              assertEquals("EMMA occurrences must match brute force for " + cfg, bf.getOccurrences(),
                  em.getOccurrences());
            }
          }
        }
      }
    }
    catch (Exception e) {
      fail("It shouldnt fail, but failed with " + StackTrace.toString(e));
    }
  }
}
