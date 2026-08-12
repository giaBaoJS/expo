// Copyright 2015-present 650 Industries. All rights reserved.

import Foundation

/// A partner's QR is the dev server URL plus a param naming the sign-in page, since iOS has no scanner to add UI at scan time.
@objc(EXDeviceLoginLink)
public final class DeviceLoginLink: NSObject {
  @objc public static let queryParamName = "verification_uri_override"

  private override init() {
    super.init()
  }

  /// Only https with a real host is accepted, because the value is shown inside Expo Go's own UI.
  @objc(verificationURIFromURL:)
  public static func verificationURI(from url: URL) -> URL? {
    guard let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
          let value = queryItems.first(where: { $0.name == queryParamName })?.value,
          !value.isEmpty,
          let candidate = URL(string: value),
          candidate.scheme?.lowercased() == "https",
          let host = candidate.host,
          !host.isEmpty else {
      return nil
    }
    return candidate
  }

  @objc(urlByRemovingOverrideFromURL:)
  public static func urlByRemovingOverride(from url: URL) -> URL {
    guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          let queryItems = components.percentEncodedQueryItems else {
      return url
    }

    let remaining = queryItems.filter { $0.name != queryParamName }
    guard remaining.count != queryItems.count else {
      return url
    }

    components.percentEncodedQueryItems = remaining.isEmpty ? nil : remaining
    return components.url ?? url
  }
}
