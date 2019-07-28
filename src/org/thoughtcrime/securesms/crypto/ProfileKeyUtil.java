package org.thoughtcrime.securesms.crypto;


import android.content.Context;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;

import java.io.IOException;

public class ProfileKeyUtil {

  public static int PROFILE_KEY_SIZE = 32;

  public static synchronized boolean hasProfileKey(@NonNull Context context) {
    return TextSecurePreferences.getProfileKey(context) != null;
  }

  public static synchronized @NonNull byte[] getProfileKey(@NonNull Context context) {
    try {
      String encodedProfileKey = TextSecurePreferences.getProfileKey(context);

      if (encodedProfileKey == null) {
        encodedProfileKey = Util.getSecret(PROFILE_KEY_SIZE);
        TextSecurePreferences.setProfileKey(context, encodedProfileKey);
        // store own todo: dont do this here
//        Recipient recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(TextSecurePreferences.getUfsrvUserId(context)), false);
//        DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipient, Base64.decode(encodedProfileKey));
        //
      }

      return Base64.decode(encodedProfileKey);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // as instructed by ufsrv in some instances
  public static synchronized @NonNull void setProfileKey(@NonNull Context context, String encodedProfileKey) {
      if (encodedProfileKey != null) {
        TextSecurePreferences.setProfileKey(context, encodedProfileKey);
        // store own todo: dont do this here
//        Recipient recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(TextSecurePreferences.getUfsrvUserId(context)), false);
//        DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipient, Base64.decode(encodedProfileKey));
      }

      throw new AssertionError("Empty encodedProfileKey");
  }
  //

  public static synchronized @NonNull byte[] rotateProfileKey(@NonNull Context context) {
    TextSecurePreferences.setProfileKey(context, null);
    return getProfileKey(context);
  }

}