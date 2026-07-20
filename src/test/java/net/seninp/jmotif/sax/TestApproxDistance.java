package net.seninp.jmotif.sax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Test;

public class TestApproxDistance {

  private static final double[] series = { 1., 2., 3., 4., 5., 6., 7., 8., 9., 10., 11., 12., 13.,
      14., 15. };
  private static final double[] series2 = { 1., 2., 3., 4., 5., 6., 7., 8., 9., 10., 11., 12., 13.,
      14., 15., 16. };

  private SAXProcessor sp;
  private TSProcessor tsp;

  @Before
  public void setUp() throws Exception {
    sp = new SAXProcessor();
    tsp = new TSProcessor();
  }

  @Test
  public void test() {
    double dist, distZnorm;
    try {

      dist = sp.approximationDistancePAA(series, 15, 7, 5.0);
      assertEquals("testing approx distance", 0.53333333, dist, 0.000001);

      dist = sp.approximationDistancePAA(series2, 15, 7, 5.0);
      assertEquals("testing approx distance", 0.53333333, dist, 0.000001);

      distZnorm = sp.approximationDistancePAA(series, 15, 7, 0.01);
      distZnorm = sp.approximationDistancePAA(series2, 15, 7, 0.01);
      // population-std znorm (2.1.0); was 0.1192569 with sample std.
      assertEquals("testing approx distance", 0.12344268, distZnorm, 0.000001);

      double newApproximationDistance = sp.approximationDistanceAlphabet(series, 15, 7, 3, 0.01);
      // population-std znorm (2.1.0); was 0.2764062 with sample std. Tight 1e-6
      // delta: the old 0.01 tolerance was barely wider than the ~0.0115 znorm
      // shift it absorbed, so a smaller convention change could have slipped by.
      assertEquals("testing approx distance", 0.2879032, newApproximationDistance, 1e-6);
    }
    catch (Exception e) {
      fail("exception shall not be thrown!");
    }
  }

  /**
   * When stDev equals the z-norm threshold, znorm() normalizes; approximation
   * distance must apply the same rule (not skip normalization with {@code >}).
   */
  @Test
  public void testZnormThresholdBoundaryConsistency() throws Exception {
    double[] boundarySeries = { 0.0, 1.0 };
    double threshold = 0.5;

    assertEquals("population stDev at boundary", threshold, tsp.stDev(boundarySeries), 1e-12);
    assertEquals("znorm at boundary", -1.0, tsp.znorm(boundarySeries, threshold)[0], 1e-12);
    assertEquals("znorm at boundary", 1.0, tsp.znorm(boundarySeries, threshold)[1], 1e-12);

    // winSize=2, paaSize=1: z-normalized PAA mean is 0 vs raw 0.5 → distances 1.0 vs 0.5
    assertEquals("approx distance PAA at znorm threshold boundary", 1.0,
        sp.approximationDistancePAA(boundarySeries, 2, 1, threshold), 1e-12);

    assertEquals("approx distance alphabet at znorm threshold boundary", 0.03257843389829895,
        sp.approximationDistanceAlphabet(boundarySeries, 2, 2, 3, threshold), 1e-12);
  }

}
