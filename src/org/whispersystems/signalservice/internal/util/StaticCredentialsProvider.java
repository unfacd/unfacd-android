package org.whispersystems.signalservice.internal.util;

import org.thoughtcrime.securesms.logging.Log;

import org.whispersystems.signalservice.api.util.CredentialsProvider;

public class StaticCredentialsProvider implements CredentialsProvider {

  private final String user;
  private final String password;
  private final String signalingKey;
  private String cookie=null;

  public StaticCredentialsProvider(String user, String password, String signalingKey) {
    this.user         = user;
    this.password     = password;
    this.signalingKey = signalingKey;
  }

  @Override
  public String getUser() {
    return user;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getSignalingKey() {
    return signalingKey;
  }

  @Override
  public String getCookie() {return cookie;}

  @Override
  public void setCookie (String cookie)
  {
    this.cookie=cookie;
  }
}
