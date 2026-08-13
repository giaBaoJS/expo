// Copyright 2025-present 650 Industries. All rights reserved.

package expo.modules.appmetrics.networkrequests

import java.lang.ref.WeakReference
import java.util.Date

/**
 * Receives notifications about HTTP requests observed by `NetworkRequestInterceptor`. Both
 * methods default to no-ops so delegates can opt into either start- or complete-time
 * notifications without implementing the other.
 */
interface NetworkRequestObserverDelegate {
  fun onNetworkRequestStarted(request: NetworkRequestStarted) {}
  fun onNetworkRequestCompleted(request: NetworkRequest) {}

  /**
   * Whether this delegate wants events for a request with the given URL and method. Consulted by
   * the monitor before each fan-out call so a delegate's filter is evaluated before the payload is
   * built. Only the URL and method are passed because those are the only attributes available at
   * both start and completion, which keeps the started/completed decision consistent. Defaults to
   * accepting every request.
   */
  fun shouldObserveRequest(url: String, method: String): Boolean = true
}

/**
 * Aggregates `NetworkRequest` snapshots delivered by the OkHttp interceptor and fans them out to
 * registered delegates. Mirrors the iOS `NetworkRequestMonitor`.
 *
 * The interceptor runs on OkHttp's dispatcher threads; delegates may be added/removed from any
 * thread (JS-facing observers come from the React thread, the singleton is reachable from app
 * code). All shared state is guarded by a single intrinsic lock - the work inside is small and
 * synchronous, so contention is negligible.
 */
class NetworkRequestMonitor internal constructor() {
  /** Maximum number of completed requests retained for debug surfaces. */
  private val recentCapacity = 200

  private val lock = Any()
  private val recentRequests = ArrayDeque<NetworkRequest>()
  private val delegates = mutableListOf<WeakReference<NetworkRequestObserverDelegate>>()

  /**
   * Persists each recorded completion into the metrics database. Held strongly — unlike
   * delegates, persistence is part of the pipeline, not an observer of it. `null` until the
   * module installs it (and in tests that don't exercise persistence).
   */
  private var persistence: NetworkRequestPersistence? = null

  /**
   * Installs the persistence hook and drains the ring buffer through it. The interceptor
   * installs at `Application.onCreate`, but persistence can only start once the module created
   * the main session — requests observed in between sit in the buffer, so draining it here
   * keeps startup traffic. The swap and the snapshot happen under the same lock as `record`,
   * so a concurrent completion is either in the drained snapshot or persisted by `record`,
   * never both.
   */
  fun installPersistence(persistence: NetworkRequestPersistence) {
    val buffered = synchronized(lock) {
      this.persistence = persistence
      recentRequests.toList()
    }
    for (request in buffered) {
      persistence.onNetworkRequestCompleted(request)
    }
  }

  /**
   * Most recently observed completed requests, oldest first. Bounded by `recentCapacity`.
   * Intended for debug surfaces and the TTI summary; not for the dispatch path.
   */
  val recent: List<NetworkRequest>
    get() = synchronized(lock) { recentRequests.toList() }

  /**
   * Folds the requests whose `timings.fetchStart` falls within `[start, end]` into a summary.
   * Used by the TTI metric to attach a per-launch network rollup. Bounded by the ring buffer:
   * under heavy network load the earliest requests in the window may have been evicted, in which
   * case the summary undercounts - acceptable for a TTI-attached signal.
   */
  fun summarize(start: Date, end: Date): NetworkRequestSummary {
    val inWindow = synchronized(lock) {
      recentRequests.filter { request ->
        val fetchStart = request.timings.fetchStart ?: return@filter false
        !fetchStart.before(start) && !fetchStart.after(end)
      }
    }
    return NetworkRequestSummary.from(inWindow)
  }

  /** Adds a delegate. Held weakly - drop the reference to unsubscribe. */
  fun addDelegate(delegate: NetworkRequestObserverDelegate) = synchronized(lock) {
    delegates.removeAll { it.get() == null }
    delegates.add(WeakReference(delegate))
  }

  fun removeDelegate(delegate: NetworkRequestObserverDelegate) = synchronized(lock) {
    delegates.removeAll {
      val strongRef = it.get()
      strongRef === delegate || strongRef == null
    }
  }

  /** Records a completed request: appends to the ring buffer, persists it, and fans out. */
  fun record(request: NetworkRequest) {
    val (persistence, snapshot) = synchronized(lock) {
      recentRequests.addLast(request)
      while (recentRequests.size > recentCapacity) {
        recentRequests.removeFirst()
      }
      delegates.removeAll { it.get() == null }
      persistence to delegates.mapNotNull { it.get() }
    }
    persistence?.onNetworkRequestCompleted(request)
    for (delegate in snapshot) {
      if (delegate.shouldObserveRequest(request.url, request.method)) {
        delegate.onNetworkRequestCompleted(request)
      }
    }
  }

  /**
   * Records that a request has begun. No ring-buffer entry - the started snapshot is purely a
   * notification used to surface in-flight state to subscribers. The corresponding completion
   * event will arrive later with a matching `id`.
   */
  fun recordStart(request: NetworkRequestStarted) {
    val snapshot = synchronized(lock) {
      delegates.removeAll { it.get() == null }
      delegates.mapNotNull { it.get() }
    }
    for (delegate in snapshot) {
      if (delegate.shouldObserveRequest(request.url, request.method)) {
        delegate.onNetworkRequestStarted(request)
      }
    }
  }

  companion object {
    /** Process-wide singleton mirroring `NetworkRequestMonitor.shared` on iOS. */
    val shared: NetworkRequestMonitor = NetworkRequestMonitor()
  }
}
