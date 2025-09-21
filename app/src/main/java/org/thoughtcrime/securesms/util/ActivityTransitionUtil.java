package org.thoughtcrime.securesms.util;

import com.unfacd.android.R;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;

public final class ActivityTransitionUtil {

  private ActivityTransitionUtil() {}

  /**
   * To be used with finish
   */
  public static void setSlideOutTransition(@NonNull ComponentActivity activity) {
    activity.overridePendingTransition(R.anim.slide_from_start, R.anim.slide_to_end);
  }

  /**
   * To be used with startActivity
   */
  public static void setSlideInTransition(@NonNull ComponentActivity activity) {
    activity.overridePendingTransition(R.anim.slide_from_end, R.anim.slide_to_start);
  }

}