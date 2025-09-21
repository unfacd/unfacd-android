package org.thoughtcrime.securesms.push;

import android.content.Context;

import com.unfacd.android.R;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;

import java.io.InputStream;

public class SignalServiceTrustStore implements TrustStore {

  private final Context context;

  public SignalServiceTrustStore(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public InputStream getKeyStoreInputStream() {
    return context.getResources().openRawResource(R.raw.whisper);
  }

  @Override
  public String getKeyStorePassword() {
    return "whisper";
  } //todo: AA change
}
