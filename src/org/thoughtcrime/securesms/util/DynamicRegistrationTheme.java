package org.thoughtcrime.securesms.util;

import com.unfacd.android.R;

import android.app.Activity;

public class DynamicRegistrationTheme extends DynamicTheme {
  @Override
  protected int getSelectedTheme(Activity activity) {
    String theme = TextSecurePreferences.getTheme(activity);

    if (theme.equals("dark")) return R.style.TextSecure_DarkRegistrationTheme;

    return R.style.TextSecure_LightRegistrationTheme;
  }
}