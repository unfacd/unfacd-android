package org.thoughtcrime.securesms.service;

public class UfsrvConnectionTimeoutException extends Exception {
  public UfsrvConnectionTimeoutException () {
  }

  public UfsrvConnectionTimeoutException (String detailMessage) {
    super(detailMessage);
  }

  public UfsrvConnectionTimeoutException (String detailMessage, Throwable throwable) {
    super(detailMessage, throwable);
  }

  public UfsrvConnectionTimeoutException (Throwable throwable) {
    super(throwable);
  }
}
