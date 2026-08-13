// Copyright 2025-present 650 Industries. All rights reserved.

package expo.modules.appmetrics.networkrequests

import android.util.Log
import expo.modules.appmetrics.storage.MetricsDatabase
import expo.modules.appmetrics.storage.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ExpoAppMetrics"

/**
 * Routes completed requests from `NetworkRequestMonitor` into the `spans` table, attributed to
 * the main session. This is the first span producer: it converts each request into a generic
 * `Span` following the OTel HTTP semantic conventions, so the export layer (`expo-observe`)
 * ships spans without knowing what produced them. Unlike the in-memory ring buffer, the rows
 * survive process death until they're dispatched, pruned with their session, or displaced past
 * the table's row cap. Mirrors the iOS `NetworkRequestPersistence`.
 *
 * Installed on the monitor once the module created the main session (see `AppMetricsModule`);
 * the monitor calls `onNetworkRequestCompleted` for every completion it records.
 */
class NetworkRequestPersistence(
  private val database: MetricsDatabase,
  private val scope: CoroutineScope,
  private val sessionId: () -> String
) : NetworkRequestObserverDelegate {
  /**
   * Persists one completed request as a span. The insert runs on `scope` so the monitor's
   * record path (OkHttp dispatcher threads) never blocks on the database; failures are logged
   * and swallowed — persistence must never break the monitor's fan-out.
   */
  override fun onNetworkRequestCompleted(request: NetworkRequest) {
    val span = request.toSpan(sessionId()) ?: return
    scope.launch {
      try {
        database.spanDao().insertCapped(span)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to persist a network request span", e)
      }
    }
  }
}

/**
 * Builds a span from a completed request snapshot, per the OTel HTTP semantic conventions for a
 * client span: the span is named after the method, a transport failure or a 4xx/5xx response
 * makes it an ERROR, and each redirect hop becomes an `http.redirect` event.
 *
 * The attribute keys mirror the set the ingestion endpoint extracts into dedicated columns
 * (`http.request.method`, `url.full`, `server.address`, ...). The URL is recorded verbatim —
 * redaction of sensitive query values happens at ingestion, per the conventions. `error.type`
 * must stay low-cardinality: the captured exception class for a transport failure, or the bare
 * status code when the response itself was the error; the localized `errorDescription` goes to
 * the status message instead.
 *
 * Returns `null` when the snapshot carries no usable timestamps — without either endpoint of
 * the request window there is nothing to anchor a span to.
 */
internal fun NetworkRequest.toSpan(sessionId: String): Span? {
  val start = timings.fetchStart?.time
  val end = timings.responseEnd?.time
  val durationMs = (timings.totalDuration * 1_000).toLong()
  val resolvedStart = start ?: end?.minus(durationMs) ?: return null
  val resolvedEnd = end ?: start?.plus(durationMs) ?: return null

  val attributes = JSONObject()
  attributes.put("http.request.method", method)
  attributes.put("url.full", url)
  url.toHttpUrlOrNull()?.host?.let { host ->
    attributes.put("server.address", host)
  }
  statusCode?.let { code ->
    attributes.put("http.response.status_code", code)
  }
  semconvProtocolVersion(networkProtocol)?.let { version ->
    attributes.put("network.protocol.version", version)
  }
  requestBytesSent?.let { bytes ->
    attributes.put("http.request.size", bytes)
  }
  responseBytesReceived?.let { bytes ->
    attributes.put("http.response.size", bytes)
  }
  val httpErrorStatus = (statusCode ?: 0) >= 400
  val resolvedErrorType = errorType ?: statusCode?.toString().takeIf { httpErrorStatus }
  resolvedErrorType?.let { value ->
    attributes.put("error.type", value)
  }

  val failed = errorDescription != null || errorType != null || httpErrorStatus
  val events = JSONArray()
  for (redirect in redirects) {
    val event = JSONObject()
    event.put("name", "http.redirect")
    val eventAttributes = JSONObject()
    eventAttributes.put("from", redirect.fromUrl)
    eventAttributes.put("to", redirect.toUrl)
    eventAttributes.put("statusCode", redirect.statusCode)
    event.put("attributes", eventAttributes)
    events.put(event)
  }

  return Span(
    sessionId = sessionId,
    name = method,
    kind = Span.CLIENT_KIND,
    startTimestampMs = resolvedStart,
    endTimestampMs = resolvedEnd,
    statusCode = if (failed) Span.STATUS_ERROR else null,
    statusMessage = if (failed) errorDescription else null,
    attributes = attributes.toString(),
    events = if (events.length() > 0) events.toString() else null
  )
}

/**
 * Bare protocol version per semconv's `network.protocol.version` ("1.1", "2", "3"), mapped from
 * the ALPN-style names OkHttp reports ("http/1.1", "h2", "h3"). Unrecognized values pass through
 * verbatim rather than being dropped.
 */
private fun semconvProtocolVersion(networkProtocol: String?): String? = when {
  networkProtocol == null -> null
  networkProtocol == "h2" -> "2"
  networkProtocol == "h3" -> "3"
  networkProtocol.startsWith("http/") -> networkProtocol.removePrefix("http/")
  else -> networkProtocol
}
