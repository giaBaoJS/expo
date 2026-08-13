// Copyright 2025-present 650 Industries. All rights reserved.

import Foundation

/// Routes completed requests from `NetworkRequestMonitor` into the `spans` table, attributed to
/// the main session. This is the first span producer: it converts each request into a generic
/// `SpanRow` following the OTel HTTP semantic conventions, so the export layer (`expo-observe`)
/// ships spans without knowing what produced them. Unlike the in-memory ring buffer, the rows
/// survive process death until they're dispatched, pruned with their session, or displaced past
/// the table's row cap.
///
/// Installed on the monitor at launch (see `AppMetricsAppDelegateSubscriber`); the monitor calls
/// `persist` synchronously on `AppMetricsActor` for every completion it records.
@AppMetricsActor
final class NetworkRequestPersistence: Sendable {
  private let database: MetricsDatabase?
  private let sessionId: @Sendable () -> String

  /// `database` is optional so a failed database open degrades to dropped rows instead of
  /// blocking monitor start. `sessionId` is a closure so constructing the persistence doesn't
  /// force the session machinery into existence before the app delegate finished wiring it.
  init(database: MetricsDatabase?, sessionId: @escaping @Sendable () -> String) {
    self.database = database
    self.sessionId = sessionId
  }

  /// Persists one completed request as a span. Failures are logged and swallowed — persistence
  /// must never break the monitor's fan-out to its delegates.
  func persist(_ request: NetworkRequest) {
    guard let database, let row = SpanRow.from(request: request, sessionId: sessionId()) else {
      return
    }
    do {
      try database.insert(span: row)
    } catch {
      logger.warn("[AppMetrics] Failed to persist a network request span: \(error.localizedDescription)")
    }
  }
}

extension SpanRow {
  /// Builds a span row from a completed request snapshot, per the OTel HTTP semantic conventions
  /// for a client span: the span is named after the method, a transport failure or a 4xx/5xx
  /// response makes it an ERROR, and each redirect hop becomes an `http.redirect` event.
  ///
  /// The attribute keys mirror the set the ingestion endpoint extracts into dedicated columns
  /// (`http.request.method`, `url.full`, `server.address`, ...). The URL is recorded verbatim —
  /// redaction of sensitive query values happens at ingestion, per the conventions. `error.type`
  /// must stay low-cardinality: the captured `domain:code` pair for a transport failure, or the
  /// bare status code when the response itself was the error; the localized `errorDescription`
  /// goes to the status message instead.
  ///
  /// Returns `nil` when the snapshot carries no usable timestamps — without either endpoint of
  /// the request window there is nothing to anchor a span to. The capture factory always sets
  /// both, so this only guards directly constructed values.
  static func from(request: NetworkRequest, sessionId: String) -> SpanRow? {
    let start = request.timings.fetchStart
    let end = request.timings.responseEnd
    let duration = request.timings.totalDuration
    guard
      let resolvedStart = start ?? end?.addingTimeInterval(-duration),
      let resolvedEnd = end ?? start?.addingTimeInterval(duration)
    else {
      return nil
    }

    var attributes: [String: Any] = [
      "http.request.method": request.method,
      "url.full": request.url.absoluteString,
    ]
    if let host = request.url.host {
      attributes["server.address"] = host
    }
    if let statusCode = request.statusCode {
      attributes["http.response.status_code"] = statusCode
    }
    if let version = semconvProtocolVersion(request.networkProtocol) {
      attributes["network.protocol.version"] = version
    }
    if let requestBytesSent = request.requestBytesSent {
      attributes["http.request.size"] = requestBytesSent
    }
    if let responseBytesReceived = request.responseBytesReceived {
      attributes["http.response.size"] = responseBytesReceived
    }
    let httpErrorStatus = (request.statusCode ?? 0) >= 400
    if let errorType = request.errorType ?? (httpErrorStatus ? request.statusCode.map(String.init) : nil) {
      attributes["error.type"] = errorType
    }

    let failed = request.errorDescription != nil || request.errorType != nil || httpErrorStatus
    let events: [[String: Any]] = request.redirects.map { redirect in
      return [
        "name": "http.redirect",
        "attributes": [
          "from": redirect.fromUrl.absoluteString,
          "to": redirect.toUrl.absoluteString,
          "statusCode": redirect.statusCode,
        ],
      ]
    }

    return SpanRow(
      sessionId: sessionId,
      name: request.method,
      kind: SpanRow.clientKind,
      startTimestampMs: resolvedStart.unixMilliseconds,
      endTimestampMs: resolvedEnd.unixMilliseconds,
      statusCode: failed ? SpanRow.statusError : nil,
      statusMessage: failed ? request.errorDescription : nil,
      attributes: serializeJSON(attributes),
      events: events.isEmpty ? nil : serializeJSON(events)
    )
  }

  /// Bare protocol version per semconv's `network.protocol.version` ("1.1", "2", "3"), mapped
  /// from the ALPN-style names the OS reports ("http/1.1", "h2", "h3"). Unrecognized values pass
  /// through verbatim rather than being dropped.
  private static func semconvProtocolVersion(_ networkProtocol: String?) -> String? {
    switch networkProtocol {
    case nil:
      return nil
    case "h2":
      return "2"
    case "h3":
      return "3"
    case .some(let other):
      return other.hasPrefix("http/") ? String(other.dropFirst("http/".count)) : other
    }
  }

  /// Serializes a JSON-compatible value built above; the inputs are all strings and numbers, so
  /// a failure means a programming error and degrading to `nil` (dropped blob) is safe.
  private static func serializeJSON(_ object: Any) -> String? {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else {
      logger.warn("[AppMetrics] Failed to serialize span attributes")
      return nil
    }
    return String(data: data, encoding: .utf8)
  }
}

extension Date {
  fileprivate var unixMilliseconds: Int64 {
    return Int64((timeIntervalSince1970 * 1_000).rounded())
  }
}
