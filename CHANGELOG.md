# Changelog

All notable changes to jmotif-sax are documented here.
This project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [2.0.2] — 2026-07-22

Maintenance and quality release on top of **2.0.1**. No public API changes.

### Changed
- **Build:** added a **PMD + SpotBugs** static-analysis quality gate (`-Pquality`); fixed
  resource leaks surfaced by the gate.
- **Dependencies:** `logback-classic` is now **test** scope only (runtime logging is the
  caller's responsibility).
- **Logging:** removed dead commented-out debug prints in `SAXProcessor`, `SAXWorker`, and
  `HOTSAXImplementation`.

### Fixed
- **`approximationDistance` z-norm threshold** is now consistent with `znorm()` (same
  `n_threshold` semantics).

### Performance
- **`TSProcessor` mean/var/stDev:** avoid `Integer` boxing in hot paths (#24).

### Docs
- README cross-links to GrammarViz 3.0 and the jMotif stack; badge URLs updated.

## [2.0.1] — 2026-07-09

Stack alignment release: depends on the 2.0.0 cross-implementation convention set
(population z-norm, fractional PAA, on-breakpoint symbol above the cut, z-normed discord
distance, lowest-index tie-break). Published to Maven Central.

## [2.0.0] — 2026-06-30

First release of the aligned 2.x line. See git tag `jmotif-sax-2.0.0` and the
[jmotif-conformance](https://github.com/jMotif/jmotif-conformance) golden tests for
behavioral details shared with saxpy and jmotif-R.
