package org.whispersystems.signalservice.internal.push;


import java.util.Optional;

public final class RequestVerificationCodeResponse {
  private final Optional<String> fcmToken;
  private final String cookie;//AA+

  //AA-
  /*public RequestVerificationCodeResponse(Optional<String> fcmToken) {
    this.fcmToken = fcmToken;
  }*/

  //AA+
  public RequestVerificationCodeResponse(String cookie, Optional<String> fcmToken) {
    this.fcmToken = fcmToken;
    this.cookie = cookie;
  }

  public Optional<String> getFcmToken() {
    return fcmToken;
  }


  public String getCookie()
  {
    return cookie;
  }
}