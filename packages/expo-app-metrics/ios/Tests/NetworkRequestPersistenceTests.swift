import Foundation
import Testing

@testable import ExpoAppMetrics

private let fixedStart = Date(timeIntervalSince1970: 1_782_131_895)

private func makeTimings(
  fetchStart: Date? = fixedStart,
  responseEnd: Date? = fixedStart.addingTimeInterval(0.25),
  totalDuration: TimeInterval = 0.25
) -> NetworkRequest.Timings {
  return NetworkRequest.Timings(
    fetchStart: fetchStart,
    domainLookupStart: nil,
    domainLookupEnd: nil,
    connectStart: nil,
    connectEnd: nil,
    secureConnectionStart: nil,
    secureConnectionEnd: nil,
    requestStart: nil,
    requestEnd: nil,
    responseStart: nil,
    responseEnd: responseEnd,
    measuredResponseEnd: responseEnd,
    totalDuration: totalDuration
  )
}

private func makeRequest(
  url: String = "https://api.example.com/v1/items?page=2",
  method: String = "GET",
  statusCode: Int? = 200,
  networkProtocol: String? = "h2",
  requestBytesSent: Int64? = 412,
  responseBytesReceived: Int64? = 8_192,
  timings: NetworkRequest.Timings = makeTimings(),
  errorDescription: String? = nil,
  errorType: String? = nil,
  redirects: [NetworkRequest.Redirect] = []
) -> NetworkRequest {
  return NetworkRequest(
    id: UUID(),
    url: URL(string: url)!,
    method: method,
    statusCode: statusCode,
    networkProtocol: networkProtocol,
    requestBytesSent: requestBytesSent,
    responseBytesReceived: responseBytesReceived,
    cameFromNetwork: true,
    timings: timings,
    errorDescription: errorDescription,
    errorType: errorType,
    redirects: redirects
  )
}

private func makeSpan(_ request: NetworkRequest) throws -> SpanRow {
  return try #require(SpanRow.from(request: request, sessionId: "s"))
}

/// Decodes the row's `attributes` JSON blob for assertions.
private func attributesDict(_ row: SpanRow) throws -> [String: Any] {
  let json = try #require(row.attributes)
  let object = try JSONSerialization.jsonObject(with: Data(json.utf8))
  return try #require(object as? [String: Any])
}

/// Decodes the row's `events` JSON blob for assertions.
private func eventsArray(_ row: SpanRow) throws -> [[String: Any]] {
  let json = try #require(row.events)
  let object = try JSONSerialization.jsonObject(with: Data(json.utf8))
  return try #require(object as? [[String: Any]])
}

@AppMetricsActor
@Suite("NetworkRequest to SpanRow mapping")
struct NetworkRequestSpanMappingTests {
  @Test
  func `converts a completed request into a client span with millisecond timestamps`() throws {
    let row = try makeSpan(makeRequest(method: "POST"))
    #expect(row.sessionId == "s")
    #expect(row.name == "POST")
    #expect(row.kind == SpanRow.clientKind)
    #expect(row.startTimestampMs == 1_782_131_895_000)
    #expect(row.endTimestampMs == 1_782_131_895_250)
    #expect(row.parentSpanId == nil)
    #expect(row.events == nil)
  }

  @Test
  func `maps the HTTP semantic-convention attributes the server extracts to columns`() throws {
    let attributes = try attributesDict(makeSpan(makeRequest()))
    #expect(attributes["http.request.method"] as? String == "GET")
    #expect(attributes["http.response.status_code"] as? Int == 200)
    #expect(attributes["url.full"] as? String == "https://api.example.com/v1/items?page=2")
    #expect(attributes["server.address"] as? String == "api.example.com")
    #expect(attributes["http.request.size"] as? Int64 == 412)
    #expect(attributes["http.response.size"] as? Int64 == 8_192)
  }

  @Test
  func `normalizes the network protocol name to a semconv version`() throws {
    // The OS reports `http/1.1`, `h2`, `h3`; semconv's `network.protocol.version` wants the
    // bare version, and the server stores it in a LowCardinality column.
    let expected = [
      "http/1.1": "1.1",
      "http/1.0": "1.0",
      "h2": "2",
      "h3": "3",
    ]
    for (reported, version) in expected {
      let attributes = try attributesDict(makeSpan(makeRequest(networkProtocol: reported)))
      #expect(attributes["network.protocol.version"] as? String == version)
    }
  }

  @Test
  func `omits attributes that were never measured`() throws {
    // A request that died before headers has no status and no byte counts. Sending a
    // placeholder would be indistinguishable from a genuine zero.
    let request = makeRequest(
      statusCode: nil,
      networkProtocol: nil,
      requestBytesSent: nil,
      responseBytesReceived: nil
    )
    let attributes = try attributesDict(makeSpan(request))
    #expect(attributes["http.response.status_code"] == nil)
    #expect(attributes["network.protocol.version"] == nil)
    #expect(attributes["http.request.size"] == nil)
    #expect(attributes["http.response.size"] == nil)
  }

  @Test
  func `records the URL verbatim including the query string`() throws {
    // Redaction is the ingestion layer's job (it strips credentials and sensitive query
    // params), so the SDK must not pre-trim the URL.
    let url = "https://api.example.com/search?q=hello&sig=secret"
    let attributes = try attributesDict(makeSpan(makeRequest(url: url)))
    #expect(attributes["url.full"] as? String == url)
  }

  @Test
  func `leaves the status unset for a successful response`() throws {
    // Semconv: a client span for a 2xx response carries no explicit status.
    let row = try makeSpan(makeRequest(statusCode: 200))
    #expect(row.statusCode == nil)
    #expect(row.statusMessage == nil)
  }

  @Test
  func `marks 4xx and 5xx responses as errors`() throws {
    // Semconv makes any 4xx/5xx an error for a client span, unlike the server-span rule.
    for statusCode in [400, 404, 429, 500, 503] {
      let row = try makeSpan(makeRequest(statusCode: statusCode))
      #expect(row.statusCode == SpanRow.statusError, "expected ERROR for status \(statusCode)")
    }
  }

  @Test
  func `marks a transport failure as an error with the description as the status message`() throws {
    // `errorDescription` is localized free text, so it belongs in the status message. The
    // low-cardinality `error.type` attribute gets a separate, predictable value.
    let row = try makeSpan(
      makeRequest(
        statusCode: nil,
        errorDescription: "The Internet connection appears to be offline.",
        errorType: "NSURLErrorDomain:-1009"
      )
    )
    #expect(row.statusCode == SpanRow.statusError)
    #expect(row.statusMessage == "The Internet connection appears to be offline.")
    let attributes = try attributesDict(row)
    #expect(attributes["error.type"] as? String == "NSURLErrorDomain:-1009")
  }

  @Test
  func `sets the error type to the status code for an HTTP error response`() throws {
    // Semconv: when a request completes with an error status and no exception, `error.type`
    // is the status code as a string.
    let attributes = try attributesDict(makeSpan(makeRequest(statusCode: 503)))
    #expect(attributes["error.type"] as? String == "503")
  }

  @Test
  func `omits the error type on success`() throws {
    let attributes = try attributesDict(makeSpan(makeRequest(statusCode: 204)))
    #expect(attributes["error.type"] == nil)
  }

  @Test
  func `maps each redirect hop onto an event`() throws {
    let request = makeRequest(redirects: [
      NetworkRequest.Redirect(
        fromUrl: URL(string: "https://example.com/a")!,
        toUrl: URL(string: "https://example.com/b")!,
        statusCode: 301
      ),
      NetworkRequest.Redirect(
        fromUrl: URL(string: "https://example.com/b")!,
        toUrl: URL(string: "https://example.com/c")!,
        statusCode: 302
      ),
    ])
    let events = try eventsArray(makeSpan(request))
    #expect(events.count == 2)
    #expect(events.allSatisfy { $0["name"] as? String == "http.redirect" })
    let first = try #require(events.first?["attributes"] as? [String: Any])
    #expect(first["from"] as? String == "https://example.com/a")
    #expect(first["to"] as? String == "https://example.com/b")
    #expect(first["statusCode"] as? Int == 301)
  }

  @Test
  func `assigns generated identifiers to each span`() throws {
    let first = try makeSpan(makeRequest())
    let second = try makeSpan(makeRequest())
    #expect(first.traceId.count == 32)
    #expect(first.spanId.count == 16)
    #expect(first.traceId != second.traceId)
  }

  @Test
  func `derives a missing end timestamp from the total duration`() throws {
    // The `setState:` fallback path can produce a snapshot before the OS reported a
    // response end; the row still needs a usable window for the span.
    let timings = makeTimings(fetchStart: fixedStart, responseEnd: nil, totalDuration: 1.5)
    let row = try makeSpan(makeRequest(timings: timings))
    #expect(row.startTimestampMs == 1_782_131_895_000)
    #expect(row.endTimestampMs == 1_782_131_896_500)
  }

  @Test
  func `returns nil when the request carries no usable timestamps`() {
    // Without either endpoint of the window there is nothing to anchor a span to. The
    // factory always sets both, so this only guards direct construction.
    let timings = makeTimings(fetchStart: nil, responseEnd: nil, totalDuration: 0)
    let row = SpanRow.from(request: makeRequest(timings: timings), sessionId: "s")
    #expect(row == nil)
  }
}

@AppMetricsActor
@Suite("NetworkRequestPersistence")
struct NetworkRequestPersistenceTests {
  private func withTemporaryDatabase(_ body: (MetricsDatabase) throws -> Void) throws {
    let directoryUrl = FileManager.default.temporaryDirectory
      .appendingPathComponent("NetworkRequestPersistenceTests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directoryUrl, withIntermediateDirectories: true)
    defer {
      try? FileManager.default.removeItem(at: directoryUrl)
    }
    let database = try MetricsDatabase(directoryUrl: directoryUrl)
    try body(database)
  }

  private func insertSession(id: String, into database: MetricsDatabase) throws {
    try database.insert(
      session: SessionRow(
        id: id,
        type: "main",
        startTimestamp: "2026-08-12T12:00:00Z",
        isActive: true
      )
    )
  }

  @Test
  func `persists a completed request as a span attributed to the provided session`() throws {
    try withTemporaryDatabase { database in
      try insertSession(id: "main-session", into: database)
      let persistence = NetworkRequestPersistence(database: database) {
        return "main-session"
      }
      persistence.persist(makeRequest())
      let rows = try database.getSpans(afterId: -1)
      #expect(rows.count == 1)
      #expect(rows.first?.sessionId == "main-session")
      #expect(rows.first?.name == "GET")
      #expect(rows.first?.kind == SpanRow.clientKind)
    }
  }

  @Test
  func `persists each request the monitor records`() throws {
    try withTemporaryDatabase { database in
      try insertSession(id: "main-session", into: database)
      let monitor = NetworkRequestMonitor()
      monitor.persistence = NetworkRequestPersistence(database: database) {
        return "main-session"
      }
      monitor.record(makeRequest(method: "GET"))
      monitor.record(makeRequest(method: "POST"))
      let rows = try database.getSpans(afterId: -1)
      #expect(rows.map(\.name) == ["GET", "POST"])
    }
  }

  @Test
  func `drops a request whose session row does not exist yet`() throws {
    // The sessions FK protects referential integrity; persistence must degrade to a dropped
    // row rather than throw into the monitor's record path.
    try withTemporaryDatabase { database in
      let persistence = NetworkRequestPersistence(database: database) {
        return "never-inserted"
      }
      persistence.persist(makeRequest())
      let rows = try database.getSpans(afterId: -1)
      #expect(rows.isEmpty)
    }
  }
}
