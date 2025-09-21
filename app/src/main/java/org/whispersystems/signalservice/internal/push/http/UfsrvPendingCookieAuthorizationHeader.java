package org.whispersystems.signalservice.internal.push.http;

import java.util.HashMap;
import java.util.Map;

//AA+
public class UfsrvPendingCookieAuthorizationHeader
{
  private static final String UFSRV_PENDING_COOKIE_HEADER = "X-Unfacd-Pending-Cookie";

  public static Map<String, String> getHeaders(String pendingCookie) {
    Map<String, String> headers = new HashMap<>();
    headers.put(UFSRV_PENDING_COOKIE_HEADER, pendingCookie);

    return headers;
  }
}
