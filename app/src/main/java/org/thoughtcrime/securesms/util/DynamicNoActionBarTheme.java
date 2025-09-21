package org.thoughtcrime.securesms.util;

import com.unfacd.android.R;

import androidx.annotation.StyleRes;

public class DynamicNoActionBarTheme extends DynamicTheme {

  protected @StyleRes int getTheme() {
    return R.style.Signal_DayNight_NoActionBar;
  }
}