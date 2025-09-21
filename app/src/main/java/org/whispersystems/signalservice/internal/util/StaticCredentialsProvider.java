package org.whispersystems.signalservice.internal.util;

import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.util.CredentialsProvider;

public class StaticCredentialsProvider implements CredentialsProvider {

  private final ACI    aci;
  private final PNI    pni;
  private final String e164;
  private final int    deviceId;
  private final String password;

  private final String signalingKey;
  private String cookie = null;

  public StaticCredentialsProvider(ACI aci, PNI pni, String e164, int deviceId, String password, String signalingKey) {
    this.aci          = aci;
    this.pni          = pni;
    this.e164         = e164;
    this.deviceId     = deviceId;
    this.password     = password;
    this.signalingKey = signalingKey;
  }

  @Override
  public ACI getAci() {
    return aci;
  }

  @Override
  public PNI getPni() {
    return pni;
  }

  @Override
  public String getE164() {
    return e164;
  }

  @Override
  public int getDeviceId() {
    return deviceId;
  }

  @Override
  public String getUser() {
    return e164;
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
    this.cookie = cookie;
  }
}
