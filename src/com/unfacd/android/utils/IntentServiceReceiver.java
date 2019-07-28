package com.unfacd.android.utils;


import android.os.Bundle;
import android.os.Handler;
import android.support.v4.os.ResultReceiver;

public class IntentServiceReceiver extends ResultReceiver
{

  private Receiver receiver;

  public IntentServiceReceiver (Handler handler) {
    super(handler);
  }

  public void setReceiver(Receiver receiver) {
    this.receiver = receiver;
  }

  public interface Receiver {
    void onReceiveResult(int resultCode, Bundle resultData);
  }

  // Delegate method which passes the result to the receiver
  @Override
  protected void onReceiveResult(int resultCode, Bundle resultData) {
    if (receiver != null) {
      receiver.onReceiveResult(resultCode, resultData);
    }
  }
}
