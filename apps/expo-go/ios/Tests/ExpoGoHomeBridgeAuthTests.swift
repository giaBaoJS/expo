// Copyright 2015-present 650 Industries. All rights reserved.

import XCTest
@testable import Expo_Go

final class ExpoGoHomeBridgeAuthTests: XCTestCase {
  override func setUp() {
    super.setUp()
    AuthenticationService.clearSession()
  }

  override func tearDown() {
    AuthenticationService.clearSession()
    super.tearDown()
  }

  func testNoSessionIsNotAuthenticated() {
    XCTAssertFalse(ExpoGoHomeBridge.shared.isAuthenticated())
    XCTAssertNil(ExpoGoHomeBridge.shared.authenticatedUsername())
    XCTAssertNil(ExpoGoHomeBridge.shared.expiredPartnerSessionMessage())
  }

  func testLiveSessionReportsUsername() async {
    await AuthenticationService.storePartnerSession(
      sessionSecret: "secret",
      username: "partner-private-test",
      expiresAt: Date().addingTimeInterval(60)
    )
    XCTAssertTrue(ExpoGoHomeBridge.shared.isAuthenticated())
    XCTAssertEqual(ExpoGoHomeBridge.shared.authenticatedUsername(), "partner-private-test")
    XCTAssertNil(ExpoGoHomeBridge.shared.expiredPartnerSessionMessage())
  }

  func testExpiredSessionReportsNeitherAuthNorUsername() async {
    await AuthenticationService.storePartnerSession(
      sessionSecret: "secret",
      username: "partner-private-test",
      expiresAt: Date().addingTimeInterval(-1)
    )
    XCTAssertFalse(ExpoGoHomeBridge.shared.isAuthenticated())
    XCTAssertNil(ExpoGoHomeBridge.shared.authenticatedUsername())
    XCTAssertEqual(
      ExpoGoHomeBridge.shared.expiredPartnerSessionMessage(),
      ExpoGoHomeBridge.expiredSessionMessage
    )
  }

  func testSessionWithoutExpiryReportsUsername() async {
    await AuthenticationService.storePartnerSession(
      sessionSecret: "secret",
      username: "partner-private-test",
      expiresAt: nil
    )
    XCTAssertTrue(ExpoGoHomeBridge.shared.isAuthenticated())
    XCTAssertEqual(ExpoGoHomeBridge.shared.authenticatedUsername(), "partner-private-test")
  }
}
