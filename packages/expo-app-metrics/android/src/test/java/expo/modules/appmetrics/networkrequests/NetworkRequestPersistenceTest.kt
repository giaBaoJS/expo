package expo.modules.appmetrics.networkrequests

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import expo.modules.appmetrics.storage.MetricsDatabase
import expo.modules.appmetrics.storage.Session
import expo.modules.appmetrics.storage.Span
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Date
import java.util.UUID

private val fixedStart = Date(1_782_131_895_000)

private fun makeTimings(
  fetchStart: Date? = fixedStart,
  responseEnd: Date? = Date(fixedStart.time + 250),
  totalDuration: Double = 0.25
) = NetworkRequest.Timings(
  fetchStart = fetchStart,
  domainLookupStart = null,
  domainLookupEnd = null,
  connectStart = null,
  connectEnd = null,
  secureConnectionStart = null,
  secureConnectionEnd = null,
  requestStart = null,
  requestEnd = null,
  responseStart = null,
  responseEnd = responseEnd,
  measuredResponseEnd = responseEnd,
  totalDuration = totalDuration
)

private fun makeRequest(
  url: String = "https://api.example.com/v1/items?page=2",
  method: String = "GET",
  statusCode: Int? = 200,
  networkProtocol: String? = "h2",
  requestBytesSent: Long? = 412,
  responseBytesReceived: Long? = 8_192,
  timings: NetworkRequest.Timings = makeTimings(),
  errorDescription: String? = null,
  errorType: String? = null,
  redirects: List<NetworkRequest.Redirect> = emptyList()
) = NetworkRequest(
  id = UUID.randomUUID(),
  url = url,
  method = method,
  statusCode = statusCode,
  networkProtocol = networkProtocol,
  requestBytesSent = requestBytesSent,
  responseBytesReceived = responseBytesReceived,
  timings = timings,
  errorDescription = errorDescription,
  errorType = errorType,
  redirects = redirects
)

private fun makeSpan(request: NetworkRequest): Span {
  return checkNotNull(request.toSpan(sessionId = "s"))
}

private fun attributes(span: Span): JSONObject {
  return JSONObject(checkNotNull(span.attributes))
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NetworkRequestToSpanMappingTest {
  @Test
  fun `converts a completed request into a client span with millisecond timestamps`() {
    val span = makeSpan(makeRequest(method = "POST"))
    assertEquals("s", span.sessionId)
    assertEquals("POST", span.name)
    assertEquals(Span.CLIENT_KIND, span.kind)
    assertEquals(1_782_131_895_000, span.startTimestampMs)
    assertEquals(1_782_131_895_250, span.endTimestampMs)
    assertNull(span.parentSpanId)
    assertNull(span.events)
  }

  @Test
  fun `maps the HTTP semantic-convention attributes the server extracts to columns`() {
    val attributes = attributes(makeSpan(makeRequest()))
    assertEquals("GET", attributes.getString("http.request.method"))
    assertEquals(200, attributes.getInt("http.response.status_code"))
    assertEquals("https://api.example.com/v1/items?page=2", attributes.getString("url.full"))
    assertEquals("api.example.com", attributes.getString("server.address"))
    assertEquals(412L, attributes.getLong("http.request.size"))
    assertEquals(8_192L, attributes.getLong("http.response.size"))
  }

  @Test
  fun `normalizes the network protocol name to a semconv version`() {
    // OkHttp reports `http/1.1`, `h2`, `h3`; semconv's `network.protocol.version` wants the
    // bare version, and the server stores it in a LowCardinality column.
    val expected = mapOf(
      "http/1.1" to "1.1",
      "http/1.0" to "1.0",
      "h2" to "2",
      "h3" to "3"
    )
    for ((reported, version) in expected) {
      val attributes = attributes(makeSpan(makeRequest(networkProtocol = reported)))
      assertEquals(version, attributes.getString("network.protocol.version"))
    }
  }

  @Test
  fun `omits attributes that were never measured`() {
    // A request that died before headers has no status and no byte counts. Sending a
    // placeholder would be indistinguishable from a genuine zero.
    val request = makeRequest(
      statusCode = null,
      networkProtocol = null,
      requestBytesSent = null,
      responseBytesReceived = null
    )
    val attributes = attributes(makeSpan(request))
    assertFalse(attributes.has("http.response.status_code"))
    assertFalse(attributes.has("network.protocol.version"))
    assertFalse(attributes.has("http.request.size"))
    assertFalse(attributes.has("http.response.size"))
  }

  @Test
  fun `records the URL verbatim including the query string`() {
    // Redaction is the ingestion layer's job (it strips credentials and sensitive query
    // params), so the SDK must not pre-trim the URL.
    val url = "https://api.example.com/search?q=hello&sig=secret"
    val attributes = attributes(makeSpan(makeRequest(url = url)))
    assertEquals(url, attributes.getString("url.full"))
  }

  @Test
  fun `leaves the status unset for a successful response`() {
    // Semconv: a client span for a 2xx response carries no explicit status.
    val span = makeSpan(makeRequest(statusCode = 200))
    assertNull(span.statusCode)
    assertNull(span.statusMessage)
  }

  @Test
  fun `marks 4xx and 5xx responses as errors`() {
    // Semconv makes any 4xx/5xx an error for a client span, unlike the server-span rule.
    for (statusCode in listOf(400, 404, 429, 500, 503)) {
      val span = makeSpan(makeRequest(statusCode = statusCode))
      assertEquals("expected ERROR for status $statusCode", Span.STATUS_ERROR, span.statusCode)
    }
  }

  @Test
  fun `marks a transport failure as an error with the description as the status message`() {
    // `errorDescription` is localized free text, so it belongs in the status message. The
    // low-cardinality `error.type` attribute gets a separate, predictable value.
    val span = makeSpan(
      makeRequest(
        statusCode = null,
        errorDescription = "Unable to resolve host",
        errorType = "java.net.UnknownHostException"
      )
    )
    assertEquals(Span.STATUS_ERROR, span.statusCode)
    assertEquals("Unable to resolve host", span.statusMessage)
    assertEquals("java.net.UnknownHostException", attributes(span).getString("error.type"))
  }

  @Test
  fun `sets the error type to the status code for an HTTP error response`() {
    // Semconv: when a request completes with an error status and no exception, `error.type`
    // is the status code as a string.
    val attributes = attributes(makeSpan(makeRequest(statusCode = 503)))
    assertEquals("503", attributes.getString("error.type"))
  }

  @Test
  fun `omits the error type on success`() {
    val attributes = attributes(makeSpan(makeRequest(statusCode = 204)))
    assertFalse(attributes.has("error.type"))
  }

  @Test
  fun `maps each redirect hop onto an event`() {
    val request = makeRequest(
      redirects = listOf(
        NetworkRequest.Redirect(
          fromUrl = "https://example.com/a",
          toUrl = "https://example.com/b",
          statusCode = 301
        ),
        NetworkRequest.Redirect(
          fromUrl = "https://example.com/b",
          toUrl = "https://example.com/c",
          statusCode = 302
        )
      )
    )
    val events = JSONArray(checkNotNull(makeSpan(request).events))
    assertEquals(2, events.length())
    val first = events.getJSONObject(0)
    assertEquals("http.redirect", first.getString("name"))
    val attributes = first.getJSONObject("attributes")
    assertEquals("https://example.com/a", attributes.getString("from"))
    assertEquals("https://example.com/b", attributes.getString("to"))
    assertEquals(301, attributes.getInt("statusCode"))
  }

  @Test
  fun `derives a missing end timestamp from the total duration`() {
    // A snapshot recorded before the body finished can lack a response end; the row still
    // needs a usable window for the span.
    val timings = makeTimings(fetchStart = fixedStart, responseEnd = null, totalDuration = 1.5)
    val span = makeSpan(makeRequest(timings = timings))
    assertEquals(1_782_131_895_000, span.startTimestampMs)
    assertEquals(1_782_131_896_500, span.endTimestampMs)
  }

  @Test
  fun `returns null when the request carries no usable timestamps`() {
    // Without either endpoint of the window there is nothing to anchor a span to.
    val timings = makeTimings(fetchStart = null, responseEnd = null, totalDuration = 0.0)
    assertNull(makeRequest(timings = timings).toSpan(sessionId = "s"))
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class NetworkRequestPersistenceTest {
  // Shared by `runTest` and Room's executors: persistence inserts are fire-and-forget, and
  // Room's suspending DAO calls hop to its executors, which live outside the test scheduler's
  // virtual time. Pinning them to the same scheduler makes `advanceUntilIdle` actually wait
  // for the inserts instead of racing them.
  private val testDispatcher = StandardTestDispatcher()

  private lateinit var database: MetricsDatabase

  @Before
  fun setUp() {
    // Surface swallowed persistence warnings in the test output.
    ShadowLog.stream = System.out
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room
      .inMemoryDatabaseBuilder(context, MetricsDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  private suspend fun insertSession(id: String) {
    database.sessionDao().insert(
      Session(id = id, startTimestamp = "2026-08-13T10:00:00.000Z")
    )
  }

  @Test
  fun `persists a completed request as a span attributed to the provided session`() = runTest(testDispatcher) {
    insertSession("main-session")
    val persistence = NetworkRequestPersistence(
      database = database,
      scope = this,
      sessionId = { "main-session" }
    )
    persistence.onNetworkRequestCompleted(makeRequest())
    testScheduler.advanceUntilIdle()
    val rows = database.spanDao().getAll()
    assertEquals(1, rows.size)
    assertEquals("main-session", rows.single().sessionId)
    assertEquals("GET", rows.single().name)
  }

  @Test
  fun `installing on the monitor backfills buffered requests and persists new ones`() = runTest(testDispatcher) {
    // The interceptor installs at Application.onCreate, but persistence can only start once
    // the module created the session. Requests observed in between sit in the monitor's ring
    // buffer, so installation drains it — startup traffic is not lost.
    insertSession("main-session")
    val monitor = NetworkRequestMonitor()
    monitor.record(makeRequest(method = "GET"))
    val persistence = NetworkRequestPersistence(
      database = database,
      scope = this,
      sessionId = { "main-session" }
    )
    monitor.installPersistence(persistence)
    monitor.record(makeRequest(method = "POST"))
    testScheduler.advanceUntilIdle()
    val rows = database.spanDao().getAll()
    assertEquals(listOf("GET", "POST"), rows.map { it.name })
  }

  @Test
  fun `drops a request whose session row does not exist yet`() = runTest(testDispatcher) {
    // The sessions FK protects referential integrity; persistence must degrade to a dropped
    // row rather than throw into the monitor's record path.
    val persistence = NetworkRequestPersistence(
      database = database,
      scope = this,
      sessionId = { "never-inserted" }
    )
    persistence.onNetworkRequestCompleted(makeRequest())
    testScheduler.advanceUntilIdle()
    assertTrue(database.spanDao().getAll().isEmpty())
  }
}
