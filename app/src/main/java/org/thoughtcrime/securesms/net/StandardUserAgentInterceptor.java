package org.thoughtcrime.securesms.net;

import android.os.Build;

import com.unfacd.android.BuildConfig;


/**
 * The user agent that should be used by default -- includes app name, version, etc.
 */
public class StandardUserAgentInterceptor extends UserAgentInterceptor {

  public static final String USER_AGENT = "unfacd-Android/" + BuildConfig.VERSION_NAME + " Android/" + Build.VERSION.SDK_INT; //AA+

  public StandardUserAgentInterceptor() {
    super(USER_AGENT);
  }
}