package org.whispersystems.signalservice.api.util;

import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;

public interface CredentialsProvider {
  public ACI getAci ();
  PNI getPni();
  public String getE164();
  int getDeviceId();
  public String getPassword();
  public String getSignalingKey();
  public String getCookie();//AA+
  public String getUser();//AA+
  public void setCookie(String cookie);//AA+
}
