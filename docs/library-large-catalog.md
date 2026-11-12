# Library large-catalog performance

This note scopes the Library scaling work in `perf/library-large-catalog`.
The target is usable list/grid interaction with a few through tens of thousands of installed games.
No fixed count can guarantee smoothness without on-device frame and memory measurements.

## Current data and rendering boundaries

- Room publishes a lightweight full catalog projection. Search and locale-aware sort run
  on `Dispatchers.Default`; the latest query cancels obsolete projections.
- Compose uses `LazyColumn` and `LazyVerticalGrid` with stable database-id keys.
  Only composed items request icon decoding, with a bounded three-job semaphore
  and an 8–24 MiB LRU in the Enhanced Icons branch. The icon work is separate
  from filtering and cannot require decoding the entire catalog.
- A live list/grid normally has the same Library source and independent scroll positions.
  Favorites and Collections retain stable database IDs, not transient list indices.
- Keep quick filters, query order, metadata edits, selected-hidden IDs and icon revision
  invalidation semantically unchanged when tuning large-catalog paths.

## Current targeted improvements

- Reconciled controller focus uses its known list/grid position in constant time when
  the focused ID has not moved; scans only when reordering genuinely requires recovery.
- Apps and Collection headers do not eagerly materialize the full visible-ID list
  while selection mode is inactive. The all-selected check is memoized against the
  selected IDs and projection, rather than recomputed on each header animation frame.
- Selection reconciliation checks the small selected-ID set directly against an
  authoritative `Set` without copying potentially tens of thousands of IDs.
- The host prunes only already-cached UI records when the authoritative source revision
  changes; filter and sort changes no longer retain removed entries indefinitely.
- JVM characterization exercises projection at 100, 1k, 5k, 20k, and 50k rows
  without flaky wall-time pass/fail thresholds. Room publication still has a focused
  1k/5k local test; benchmark numbers are diagnostic, not device-performance claims.

- A focused runtime-smoke test renders 20,000 items through the real LibraryScreen
  in list and grid, checks a bounded composed window, and jumps to the final app.
  It demonstrates lazy composition, not sustained frame pacing or scrolling FPS.

## Unresolved scalability boundaries

- Icon file presence is resolved on Dispatchers.IO before publishing the catalog.
  Unchanged rows reuse the prior result within the same generation and workdir.
  Rare out-of-snapshot row lookups retain a synchronous fallback for correctness.
  The fragment still converts every projected row into a UI item on the main thread:
  this O(N) CPU and allocation boundary needs device profiling before further changes.
- Room's full catalog snapshot, locale-aware Collator sort and translated description
  resolution still consume O(N) memory and/or work per refresh. They run off the
  UI thread but could delay first presentation or search under a very large catalog.
  Introduce incremental indexes/paging only with measured evidence and a compatible
  design for exact sort, search ranking, Favorites, and Collections.
- A successful JVM characterization, ordinary CI, or a Compose lazy list does **not**
  establish a 60/120 FPS guarantee with 50k games. Qualify on hardware using controlled
  1k/5k/20k/50k databases, both list and grid, warm and cold icon caches,
  rapid search/filter changes, scroll flings, controller navigation and bulk selection.
  Capture frame overrun P95/P99, main-thread traces, heap/PSS, initial load,
  search latency, and icon decode/normalization activity.

This is a focused preparatory optimization, not authorization to change storage schemas,
drop user metadata, or merge an unrelated rendering refactor.
