package net.seninp.jmotif.sax.discord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.seninp.jmotif.sax.NumerosityReductionStrategy;
import net.seninp.jmotif.sax.SAXProcessor;
import net.seninp.jmotif.sax.TSProcessor;
import net.seninp.jmotif.sax.alphabet.NormalAlphabet;
import net.seninp.jmotif.sax.datastructure.SAXRecord;
import net.seninp.jmotif.sax.datastructure.SAXRecords;
import net.seninp.jmotif.sax.registry.MagicArrayEntry;

/**
 * Implements HOTSAX discord discovery algorithm.
 *
 * @author psenin
 */
public class HOTSAXImplementation {

  private static TSProcessor tp = new TSProcessor();
  private static SAXProcessor sp = new SAXProcessor();

  private static final Logger LOGGER = LoggerFactory.getLogger(HOTSAXImplementation.class);

  /**
   * Hash-table backed implementation (in contrast to trie). Time series is converted into a
   * SAXRecords data structure first, Hash-table backed magic array created second. HOTSAX applied
   * third. Nearest neighbors are searched only among the subsequences which were produced by SAX
   * with specified numerosity reduction. Thus, if the strategy is EXACT or MINDIST, discords do not
   * match those produced by BruteForce or NONE.
   *
   * @param series The timeseries.
   * @param discordsNumToReport The number of discords to report.
   * @param windowSize SAX sliding window size.
   * @param paaSize SAX PAA value.
   * @param alphabetSize SAX alphabet size.
   * @param strategy the numerosity reduction strategy.
   * @param nThreshold the normalization threshold value.
   * @return The set of discords found within the time series, it may return less than asked for --
   * in this case, there are no more discords.
   * @throws Exception if error occurs.
   */
  public static DiscordRecords series2Discords(double[] series, int discordsNumToReport,
      int windowSize, int paaSize, int alphabetSize, NumerosityReductionStrategy strategy,
      double nThreshold) throws Exception {
    // Historical behavior: an unseeded RNG drives the random-search visit order, so the search
    // trajectory (and distance-call count) varies run-to-run. The reported discords are
    // order-independent, so this does not change results -- only the trajectory.
    return series2Discords(series, discordsNumToReport, windowSize, paaSize, alphabetSize, strategy,
        nThreshold, new Random());
  }

  /**
   * Reproducible overload of
   * {@link #series2Discords(double[], int, int, int, int, NumerosityReductionStrategy, double)}
   * that takes an explicit {@link Random}. Pass a seeded {@code new Random(seed)} to make the
   * random-search visit order -- and hence the distance-call count / search trajectory --
   * reproducible. The reported discords are identical regardless of the RNG.
   *
   * @param series the timeseries.
   * @param discordsNumToReport num of discords to report.
   * @param windowSize SAX sliding window size.
   * @param paaSize SAX PAA value.
   * @param alphabetSize SAX alphabet size.
   * @param strategy the numerosity reduction strategy.
   * @param nThreshold the normalization threshold value.
   * @param rnd the random source for the random-search visit order.
   * @return The set of discords found within the time series.
   * @throws Exception if error occurs.
   */
  public static DiscordRecords series2Discords(double[] series, int discordsNumToReport,
      int windowSize, int paaSize, int alphabetSize, NumerosityReductionStrategy strategy,
      double nThreshold, Random rnd) throws Exception {

    // fix the start time
    Date start = new Date();

    // get the SAX transform done
    NormalAlphabet normalA = new NormalAlphabet();
    SAXRecords sax = sp.ts2saxViaWindow(series, windowSize, paaSize, normalA.getCuts(alphabetSize),
        strategy, nThreshold);
    Date saxEnd = new Date();
    LOGGER.debug("discretized in {}, words: {}, indexes: {}",
        SAXProcessor.timeToString(start.getTime(), saxEnd.getTime()), sax.getRecords().size(),
        sax.getIndexes().size());

    // fill the array for the outer loop
    ArrayList<MagicArrayEntry> magicArray = new ArrayList<MagicArrayEntry>(sax.getRecords().size());
    for (SAXRecord sr : sax.getRecords()) {
      magicArray.add(new MagicArrayEntry(String.valueOf(sr.getPayload()), sr.getIndexes().size()));
    }
    Date hashEnd = new Date();
    LOGGER.debug("Magic array filled in : {}",
        SAXProcessor.timeToString(saxEnd.getTime(), hashEnd.getTime()));

    DiscordRecords discords = getDiscordsWithMagic(series, sax, windowSize, magicArray,
        discordsNumToReport, nThreshold, rnd);

    Date end = new Date();

    LOGGER.debug("{} discords found in {}", discords.getSize(),
        SAXProcessor.timeToString(start.getTime(), end.getTime()));

    return discords;
  }

  private static DiscordRecords getDiscordsWithMagic(double[] series, SAXRecords sax,
      int windowSize, ArrayList<MagicArrayEntry> magicArray, int discordCollectionSize,
      double nThreshold, Random rnd) throws Exception {

    // sort the candidates
    Collections.sort(magicArray);

    // resulting discords collection
    DiscordRecords discords = new DiscordRecords();

    // visit registry
    HashSet<Integer> visitRegistry = new HashSet<Integer>(windowSize * discordCollectionSize);

    // we conduct the search until the number of discords is less than
    // desired
    //
    while (discords.getSize() < discordCollectionSize) {

      LOGGER.trace("currently known discords: {} out of {}", discords.getSize(),
          discordCollectionSize);

      Date start = new Date();
      DiscordRecord bestDiscord = findBestDiscordWithMagic(series, windowSize, sax, magicArray,
          visitRegistry, nThreshold, rnd);
      Date end = new Date();

      // if the discord is null we getting out of the search
      if (bestDiscord.getNNDistance() == 0.0D || bestDiscord.getPosition() == -1) {
        LOGGER.trace("breaking the outer search loop, discords found: {} last seen discord: {}",
            discords.getSize(), bestDiscord);
        break;
      }

      bestDiscord.setInfo(
          "position " + bestDiscord.getPosition() + ", NN distance " + bestDiscord.getNNDistance()
              + ", elapsed time: " + SAXProcessor.timeToString(start.getTime(), end.getTime())
              + ", " + bestDiscord.getInfo());
      LOGGER.debug("{}", bestDiscord.getInfo());

      // collect the result
      //
      discords.add(bestDiscord);

      // and maintain data structures
      //
      // global exclusion zone +/-(windowSize-1) around the found discord (loop
      // end is exclusive), matching saxpy.
      int markStart = bestDiscord.getPosition() - windowSize + 1;
      if (markStart < 0) {
        markStart = 0;
      }
      int markEnd = bestDiscord.getPosition() + windowSize;
      if (markEnd > series.length) {
        markEnd = series.length;
      }
      LOGGER.debug("marking as globally visited [{}, {}]", markStart, markEnd);
      for (int i = markStart; i < markEnd; i++) {
        visitRegistry.add(i);
      }

    }

    // done deal
    //
    return discords;
  }

  /**
   * This method reports the best found discord. Note, that this discord is approximately the best.
   * Due to the fuzzy-logic search with randomization and aggressive labeling of the magic array
   * locations.
   *
   * @param series The series we are looking for discord in.
   * @param windowSize The sliding window size.
   * @param sax The SAX data structure for the reference.
   * @param allWords The magic heuristics array.
   * @param discordRegistry The global visit array.
   * @param nThreshold The z-normalization threshold.
   * @return The best discord instance.
   * @throws Exception If error occurs.
   */
  private static DiscordRecord findBestDiscordWithMagic(double[] series, int windowSize,
      SAXRecords sax, ArrayList<MagicArrayEntry> allWords, HashSet<Integer> discordRegistry,
      double nThreshold, Random rnd) throws Exception {

    // prepare the visits array, note that there can't be more points to visit that in a SAX index
    int[] visitArray = new int[series.length];

    // init tracking variables
    int bestSoFarPosition = -1;
    double bestSoFarDistance = 0.0D;
    String bestSoFarWord = "";

    // discord search stats
    int iterationCounter = 0;
    int distanceCalls = 0;

    LOGGER.debug("iterating over {} entries", allWords.size());

    for (MagicArrayEntry currentEntry : allWords) {

      // look into that entry
      String currentWord = currentEntry.getStr();
      Set<Integer> occurrences = sax.getByWord(currentWord).getIndexes();

      // we shall iterate over these candidate positions first
      for (int currentPos : occurrences) {

        iterationCounter++;

        // make sure it is not a previously found discord passed through the parameters array
        //
        // note, that the discordRegistry contains the whole span of previously found discord,
        // not just it's position....
        //
        //
        if (discordRegistry.contains(currentPos)) {
          continue;
        }

        LOGGER.trace("conducting search for {} at {}, iteration {}", currentWord, currentPos,
            iterationCounter);

        // self-exclusion zone +/-(windowSize-1): the windows at exactly
        // +/-windowSize are non-overlapping and ARE valid NN candidates
        // (matches saxpy; previously this used +/-windowSize and excluded them).
        int markStart = currentPos - windowSize + 1;
        int markEnd = currentPos + windowSize - 1;

        // all the candidates we are not going to try
        HashSet<Integer> alreadyVisited = new HashSet<Integer>(
            IntStream.rangeClosed(markStart, markEnd).boxed().collect(Collectors.toList()));

        // fix the current subsequence trace
        double[] currentCandidateSeq = tp
            .znorm(tp.subseriesByCopy(series, currentPos, currentPos + windowSize), nThreshold);

        // let the search begin ..
        double nearestNeighborDist = Double.MAX_VALUE;
        boolean doRandomSearch = true;

        for (Integer nextOccurrence : occurrences) {

          // just in case there is an overlap
          if (alreadyVisited.contains(nextOccurrence)) {
            continue;
          }
          else {
            alreadyVisited.add(nextOccurrence);
          }

          // get the subsequence and the distance
          // double[] occurrenceSubsequence = tp.subseriesByCopy(series, nextOccurrence,
          // nextOccurrence + windowSize);
          // double dist = ed.distance(currentCandidateSeq, occurrenceSubsequence);
          double dist = distance(currentCandidateSeq, series, nextOccurrence,
              nextOccurrence + windowSize, nThreshold);
          distanceCalls++;

          // keep track of best so far distance
          if (dist < nearestNeighborDist) {
            nearestNeighborDist = dist;
            LOGGER.trace(" ** current NN at {}, distance: {}, pos {}", nextOccurrence,
                nearestNeighborDist, currentPos);
          }
          if (dist < bestSoFarDistance) {
            LOGGER.trace(
                " ** abandoning the occurrences loop, distance {} is less than the best so far {}",
                dist, bestSoFarDistance);
            doRandomSearch = false;
            break;
          }

        }

        // check if we must continue with random neighbors

        if (doRandomSearch) {
          LOGGER.trace("starting random search");

          // init the visit array
          //
          int visitCounter = 0;
          int cIndex = 0;
          for (int i = 0; i < series.length - windowSize; i++) {
            if (!(alreadyVisited.contains(i))) {
              visitArray[cIndex] = i;
              cIndex++;
            }
          }
          cIndex--;

          // shuffle the visit array (rnd is supplied by the caller; an unseeded Random preserves
          // the historical non-reproducible order, a seeded one makes the trajectory reproducible)
          //
          for (int i = cIndex; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            int a = visitArray[index];
            visitArray[index] = visitArray[i];
            visitArray[i] = a;
          }

          // while there are unvisited locations
          while (cIndex >= 0) {

            int randomPos = visitArray[cIndex];
            cIndex--;

            // double[] randomSubsequence = tp.subseriesByCopy(series, randomPos,
            // randomPos + windowSize);
            // double dist = ed.distance(currentCandidateSeq, randomSubsequence);
            double dist = distance(currentCandidateSeq, series, randomPos, randomPos + windowSize,
                nThreshold);
            distanceCalls++;

            // keep track
            if (dist < nearestNeighborDist) {
              LOGGER.trace(" ** current NN at {}, distance: {}", +randomPos, dist);
              nearestNeighborDist = dist;
            }

            // early abandoning of the search:
            // the current word is not discord, we have seen better
            if (dist < bestSoFarDistance) {
              nearestNeighborDist = dist;
              LOGGER.trace(" ** abandoning random visits loop, seen distance {} at iteration {}",
                  nearestNeighborDist, visitCounter);

              break;
            }

            visitCounter = visitCounter + 1;

          } // while inner loop

        } // end of random search loop

        // Update on a strictly larger NN distance, or on an exact tie with a
        // smaller position -- a deterministic lowest-index tie-break so the
        // result is independent of the (RNG-driven) visit order (matches saxpy).
        if (nearestNeighborDist < Double.MAX_VALUE
            && (nearestNeighborDist > bestSoFarDistance
                || (nearestNeighborDist == bestSoFarDistance && currentPos < bestSoFarPosition))) {
          bestSoFarDistance = nearestNeighborDist;
          bestSoFarPosition = currentPos;
          bestSoFarWord = currentWord;
          LOGGER.debug("discord updated: pos {}, dist {}", bestSoFarPosition, bestSoFarDistance);
        }

        LOGGER.trace(" . . iterated {} times, best distance:  {} for a string {} at {}",
            iterationCounter, bestSoFarDistance, bestSoFarWord, bestSoFarPosition);

      } // outer loop inner part
    } // outer loop

    LOGGER.trace("Distance calls: {}", distanceCalls);
    DiscordRecord res = new DiscordRecord(bestSoFarPosition, bestSoFarDistance, bestSoFarWord);
    res.setLength(windowSize);
    res.setInfo("distance calls: " + distanceCalls);

    return res;

  }

  /**
   * Calculates the Euclidean distance between two points. Don't use this unless you need that.
   * 
   * @param subseries The first subsequence -- ASSUMED TO BE Z-normalized.
   * @param series The second point.
   * @param from the initial index of the range to be copied, inclusive
   * @param to the final index of the range to be copied, exclusive. (This index may lie outside the
   * array.)
   * @param nThreshold z-Normalization threshold.
   * @return The Euclidean distance between z-Normalized versions of subsequences.
   */
  private static double distance(double[] subseries, double[] series, int from, int to,
      double nThreshold) throws Exception {
    double[] subsequence = tp.znorm(tp.subseriesByCopy(series, from, to), nThreshold);
    Double sum = 0D;
    for (int i = 0; i < subseries.length; i++) {
      double tmp = subseries[i] - subsequence[i];
      sum = sum + tmp * tmp;
    }
    return Math.sqrt(sum);
  }

}
