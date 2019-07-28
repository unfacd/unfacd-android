package com.unfacd.android.utils;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.telephony.TelephonyManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import org.thoughtcrime.securesms.logging.Log;
import android.view.Display;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.mikepenz.actionitembadge.library.ActionItemBadge;
import com.mikepenz.actionitembadge.library.utils.BadgeStyle;
import com.mikepenz.community_material_typeface_library.CommunityMaterial;
//import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.unfacd.android.ApplicationContext;

import com.unfacd.android.R;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ui.components.TextViewFadeEffectAnimator;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;


public class Utils
{
  private static final Hashtable<String, Typeface> typefaceCache = new Hashtable<>();
  private static Boolean isTablet = null;
  public static float density = 1;
  public static int leftBaseline;
  public static boolean usingHardwareInput;
  private static int prevOrientation = -10;
  public static DisplayMetrics displayMetrics = new DisplayMetrics();
  public static Point displaySize = new Point();

  public static Pattern WEB_URL = null;
  static {
    try {
      final String GOOD_IRI_CHAR = "a-zA-Z0-9\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF";
      final Pattern IP_ADDRESS = Pattern.compile(
              "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                      + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                      + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                      + "|[1-9][0-9]|[0-9]))");
      final String IRI = "[" + GOOD_IRI_CHAR + "]([" + GOOD_IRI_CHAR + "\\-]{0,61}[" + GOOD_IRI_CHAR + "]){0,1}";
      final String GOOD_GTLD_CHAR = "a-zA-Z\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF";
      final String GTLD = "[" + GOOD_GTLD_CHAR + "]{2,63}";
      final String HOST_NAME = "(" + IRI + "\\.)+" + GTLD;
      final Pattern DOMAIN_NAME = Pattern.compile("(" + HOST_NAME + "|" + IP_ADDRESS + ")");
      WEB_URL = Pattern.compile(
              "((?:(http|https|Http|Https):\\/\\/(?:(?:[a-zA-Z0-9\\$\\-\\_\\.\\+\\!\\*\\'\\(\\)"
                      + "\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,64}(?:\\:(?:[a-zA-Z0-9\\$\\-\\_"
                      + "\\.\\+\\!\\*\\'\\(\\)\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,25})?\\@)?)?"
                      + "(?:" + DOMAIN_NAME + ")"
                      + "(?:\\:\\d{1,5})?)" // plus option port number
                      + "(\\/(?:(?:[" + GOOD_IRI_CHAR + "\\;\\/\\?\\:\\@\\&\\=\\#\\~"  // plus option query params
                      + "\\-\\.\\+\\!\\*\\'\\(\\)\\,\\_])|(?:\\%[a-fA-F0-9]{2}))*)?"
                      + "(?:\\b|$)");
    } catch (Exception e) {
      Log.e("Utils", e.getMessage());
    }
  }

  static {
    density = ApplicationContext.getInstance().getResources().getDisplayMetrics().density;
    leftBaseline = isTablet() ? 80 : 72;
    checkDisplaySize();
  }


  public static boolean actionItemDescribeUnread (Activity activity, MenuItem itemBadgeUnread, BadgeStyle badgeStyleUnread, int unread)
  {
    if (unread>0) {
//      ActionItemBadge.update(activity, itemBadgeUnread, FontAwesome.Icon.faw_comments, badgeStyleUnread, (int)unread);
//      ActionItemBadge.update(activity, itemBadgeUnread, GoogleMaterial.Icon.gmd_visibility, badgeStyleUnread, (int)unread);
      ActionItemBadge.update(activity, itemBadgeUnread, CommunityMaterial.Icon.cmd_eye_off_outline, badgeStyleUnread, (int)unread);
    }
    else {
      ActionItemBadge.hide(itemBadgeUnread);
      return true;
    }

    return false;
  }

  public static void actionItemdescribeLocation (TextView locationView, ufLocation location, boolean animate)
  {
   if (location!=null && !TextUtils.isEmpty(location.describeMyLocality()))
    {
      if (animate)
      {
        TextViewFadeEffectAnimator animator = new TextViewFadeEffectAnimator(locationView, new String[]{
                "~ " + location.describeMyLocality()});
        animator.startAnimation();
        animator.stopAnimation();
      }
      else
      {
        locationView.setText("~ " + location.describeMyLocality());
      }
    }
    else
    {
      String altLocationDescriptor = !TextUtils.isEmpty(location.describeMyAdminArea())?location.describeMyAdminArea():
                                      !TextUtils.isEmpty(location.describeMyCountry())?location.describeMyCountry():"*";
      if (animate)
      {
        TextViewFadeEffectAnimator animator = new TextViewFadeEffectAnimator(locationView, new String[]{
                altLocationDescriptor});
        animator.startAnimation();
        animator.stopAnimation();
      }
      else
      {
        locationView.setText(altLocationDescriptor);
      }
    }

  }

  public static void checkDisplaySize() {
    try {
      Configuration configuration = ApplicationContext.getInstance().getResources().getConfiguration();
      usingHardwareInput = configuration.keyboard != Configuration.KEYBOARD_NOKEYS && configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
      WindowManager manager = (WindowManager) ApplicationContext.getInstance().getSystemService(Context.WINDOW_SERVICE);
      if (manager != null) {
        Display display = manager.getDefaultDisplay();
        if (display != null) {
          display.getMetrics(displayMetrics);
          display.getSize(displaySize);
          Log.d("Utils", "display size = " + displaySize.x + " " + displaySize.y + " " + displayMetrics.xdpi + "x" + displayMetrics.ydpi);
        }
      }
    } catch (Exception e) {
      Log.e("Utils", e.getMessage());
    }
  }


  public static boolean isTablet() {
    if (isTablet == null) {
      //isTablet = ApplicationContext.getInstance().getResources().getBoolean(R.bool.isTablet);
    }
    return false;
  }

  public static int dp(float value) {
    if (value == 0) {
      return 0;
    }
    return (int) Math.ceil(density * value);
  }

  public static void lockOrientation(Activity activity) {
    if (activity == null || prevOrientation != -10) {
      return;
    }
    try {
      prevOrientation = activity.getRequestedOrientation();
      WindowManager manager = (WindowManager)activity.getSystemService(Activity.WINDOW_SERVICE);
      if (manager != null && manager.getDefaultDisplay() != null) {
        int rotation = manager.getDefaultDisplay().getRotation();
        int orientation = activity.getResources().getConfiguration().orientation;

        if (rotation == Surface.ROTATION_270) {
          if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
          }
        } else if (rotation == Surface.ROTATION_90) {
          if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
          }
        } else if (rotation == Surface.ROTATION_0) {
          if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
          }
        } else {
          if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
          } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
          }
        }
      }
    } catch (Exception e) {
      Log.d("Utils", e.getMessage());
    }
  }

  public static void unlockOrientation(Activity activity) {
    if (activity == null) {
      return;
    }
    try {
      if (prevOrientation != -10) {
        activity.setRequestedOrientation(prevOrientation);
        prevOrientation = -10;
      }
    } catch (Exception e) {
      Log.e("Utils", e.getMessage());
    }
  }

  public static final int FLAG_TAG_BR = 1;
  public static final int FLAG_TAG_BOLD = 2;
  public static final int FLAG_TAG_COLOR = 4;
  public static final int FLAG_TAG_ALL = FLAG_TAG_BR | FLAG_TAG_BOLD | FLAG_TAG_COLOR;

  public static SpannableStringBuilder replaceTags(String str) {
    return replaceTags(str, FLAG_TAG_ALL);
  }

  public static SpannableStringBuilder replaceTags(String str, int flag) {
    try {
      int start;
      int end;
      StringBuilder stringBuilder = new StringBuilder(str);
      if ((flag & FLAG_TAG_BR) != 0) {
        while ((start = stringBuilder.indexOf("<br>")) != -1) {
          stringBuilder.replace(start, start + 4, "\n");
        }
        while ((start = stringBuilder.indexOf("<br/>")) != -1) {
          stringBuilder.replace(start, start + 5, "\n");
        }
      }
      ArrayList<Integer> bolds = new ArrayList<>();
      if ((flag & FLAG_TAG_BOLD) != 0) {
        while ((start = stringBuilder.indexOf("<b>")) != -1) {
          stringBuilder.replace(start, start + 3, "");
          end = stringBuilder.indexOf("</b>");
          if (end == -1) {
            end = stringBuilder.indexOf("<b>");
          }
          stringBuilder.replace(end, end + 4, "");
          bolds.add(start);
          bolds.add(end);
        }
      }
      ArrayList<Integer> colors = new ArrayList<>();
      if ((flag & FLAG_TAG_COLOR) != 0) {
        while ((start = stringBuilder.indexOf("<c#")) != -1) {
          stringBuilder.replace(start, start + 2, "");
          end = stringBuilder.indexOf(">", start);
          int color = Color.parseColor(stringBuilder.substring(start, end));
          stringBuilder.replace(start, end + 1, "");
          end = stringBuilder.indexOf("</c>");
          stringBuilder.replace(end, end + 4, "");
          colors.add(start);
          colors.add(end);
          colors.add(color);
        }
      }
      SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder(stringBuilder);
      for (int a = 0; a < bolds.size() / 2; a++) {
        spannableStringBuilder.setSpan(new com.unfacd.android.ui.components.TypefaceSpan(Utils.getTypeface("fonts/rmedium.ttf")), bolds.get(a * 2), bolds.get(a * 2 + 1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      for (int a = 0; a < colors.size() / 3; a++) {
        spannableStringBuilder.setSpan(new ForegroundColorSpan(colors.get(a * 3 + 2)), colors.get(a * 3), colors.get(a * 3 + 1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      return spannableStringBuilder;
    } catch (Exception e) {
      Log.e("Utils", e.getMessage());
    }
    return new SpannableStringBuilder(str);
  }


  public static Typeface getTypeface(String assetPath) {
    synchronized (typefaceCache) {
      if (!typefaceCache.containsKey(assetPath)) {
        try {
          Typeface t = Typeface.createFromAsset(ApplicationContext.getInstance().getAssets(), assetPath);
          typefaceCache.put(assetPath, t);
        } catch (Exception e) {
          Log.e("Utils", "Could not get typeface '" + assetPath + "' because " + e.getMessage());
          return null;
        }
      }
      return typefaceCache.get(assetPath);
    }
  }

  public static void reorderToFrontLastActivity (Context context) {
    Class<?> activityClass;
    String lastActivity     = TextSecurePreferences.getStringPreference(context, "xLastActivity", null);

    try {
      activityClass = Class.forName(lastActivity);
    } catch (ClassNotFoundException ex) {
      activityClass = null;
    }

    if (activityClass != null) {
      Intent intent = new Intent(context, activityClass);
      intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
      context.startActivity(intent);
    }
  }

  /**
   * Shake horizantally by offset x.
   * @param view
   * @param x
   * @param num when used in loop 0-5
   */
  public static void shakeView(final View view, final float x, final int num) {
    if (num == 6) {
      view.setTranslationX(0);
      return;
    }
    AnimatorSet animatorSet = new AnimatorSet();
    animatorSet.playTogether(ObjectAnimator.ofFloat(view, "translationX", Utils.dp(x)));
    animatorSet.setDuration(100);
    animatorSet.addListener(new AnimatorListenerAdapterProxy() {
      @Override
      public void onAnimationEnd(Animator animation) {
        shakeView(view, num == 5 ? 0 : -x, num + 1);
      }
    });
    animatorSet.start();
  }

  public static String normalizeNumber(String number, String iso) {
    PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
    String phoneNo = "";
    try {
      Phonenumber.PhoneNumber phone = phoneUtil.parse(number, iso);
      phoneNo = phoneUtil.format(phone, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);
    } catch (NumberParseException e) {
      return number;
    }
    return phoneNo;
  }

  public static String extractCountryCode(String number) {
    String countryCode = "";
    if (number.contains(" ") && number.contains("+")) {
      countryCode = number.substring(0, number.indexOf(' ')).trim();
    }
    return countryCode;
  }

  public static String getSimCardNumber(Activity activity) {
    TelephonyManager tm = (TelephonyManager) activity.getSystemService(activity.TELEPHONY_SERVICE);
    String simcardNumber = tm.getLine1Number() != null ? normalizeNumber(tm.getLine1Number(), "") : "";
    String countryCode = extractCountryCode(simcardNumber);
    if (!TextUtils.isEmpty(countryCode) && countryCode.contains("+")) {
      simcardNumber = simcardNumber.replace(countryCode, "");
    }
    return simcardNumber;
  }

  public static int getCountryCodeLength(String number) {
    int length = 0;
    if (number.length() > 2) {
      for (int i = 0; i <= 3 && length == 0; i++) {
        for (String code : CountryCodes.m_Codes) {
          if (number.substring(0, i).equals(code)) {
            length = i;
          }
        }
      }
    }
    return length;
  }
  public static Long numberToLong(String number) {
    String longNumber = "";
    if (number != null) {
      number = number.replaceAll(" ", "");
      if (number.contains("+")) {
        number = number.replace("+", "");
        number = number.substring(getCountryCodeLength(number), number.length());
      }
      if (number.length() > 0 && number.charAt(0) == '0') {
        number = number.substring(1);
      }
      for (int i = 0; i < number.length(); i++) {
        char a = number.charAt(i);
        if (('0' <= a && a <= '9')) {
          if (Character.isDigit(a)) {
            longNumber += a;
          }
        }
      }
      if (longNumber.trim().length() <= 0) {
        longNumber = "0";
      }
    }
    Long longId = 0L;
    if (longNumber.length() > 11) {
      longNumber = longNumber.toString().substring(0, 11);
    }
    try {
      longId = Long.valueOf(longNumber.trim());
    } catch (NumberFormatException e) {
      Log.w("MYLOG ", "If not parseable, no profile id - so no problem - " + e.getMessage());
    }
    return longId;
  }

  public static void forceOverFlowMenu(Context context) {
    try {
      ViewConfiguration config = ViewConfiguration.get(context);
      Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");

      if (menuKeyField != null) {
        menuKeyField.setAccessible(true);
        menuKeyField.setBoolean(config, false);
      }
    } catch (Exception e) {
    }
  }

  public static byte[] compressString(String srcTxt) throws IOException
  {
    ByteArrayOutputStream rstBao = new ByteArrayOutputStream();
    try (GZIPOutputStream zos = new GZIPOutputStream(rstBao)) {
      zos.write(srcTxt.getBytes());
      zos.finish();
    }

    return rstBao.toByteArray();
    //return Base64.encodeBytes(rstBao.toByteArray());

  }

  public static String deCompressString(final byte[] compressed) throws IOException
  {
    if ((compressed == null) || (compressed.length == 0)) {
      return "";
    }

    StringBuilder outStr = new StringBuilder();

    if (isCompressed(compressed)) {
      GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
      BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(gis, "UTF-8"));

      String line;
      while ((line = bufferedReader.readLine()) != null) {
        outStr.append(line);// += line;
      }
    } else {
      outStr.append(compressed);
    }
    return outStr.toString();

  }

  public static boolean isCompressed(final byte[] compressed) {
    return (compressed[0] == (byte) (GZIPInputStream.GZIP_MAGIC)) && (compressed[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8));
  }

  public static byte[] deCompressBytes(byte[] data) throws IOException, DataFormatException
  {
    // private static final Logger LOG = Logger.getLogger(CompressionUtils.class);
    Inflater inflater = new Inflater();
    inflater.setInput(data);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
    byte[] buffer = new byte[1024];
    while (!inflater.finished()) {
      int count = inflater.inflate(buffer);
      outputStream.write(buffer, 0, count);
    }
    outputStream.close();
    byte[] output = outputStream.toByteArray();
//    LOG.debug("Original: " + data.length);
//    LOG.debug("Compressed: " + output.length);
    return output;
  }

  public static byte[] compressBytes(byte[] data) throws IOException {
    Deflater deflater = new Deflater();
    deflater.setInput(data);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
    deflater.finish();
    byte[] buffer = new byte[1024];
    while (!deflater.finished()) {
      int count = deflater.deflate(buffer); // returns the generated code... index
      outputStream.write(buffer, 0, count);
    }
    outputStream.close();
    byte[] output = outputStream.toByteArray();
//    LOG.debug("Original: " + data.length / 1024 + " Kb");
//    LOG.debug("Compressed: " + output.length / 1024 + " Kb");
    return output;
  }

  public static boolean  isSelfConversation(Context context, Recipient recipient) {
  if (!TextSecurePreferences.isPushRegistered(context)) return false;
  if (recipient.isGroupRecipient())                  return false;

  return Util.isOwnNumber(context, recipient.getAddress());
}

  public static  String getCallingStack() {
    String recentSteps = "";
    StackTraceElement[] traceElements = Thread.currentThread().getStackTrace();
    final int maxStepCount = 3;
    final int skipCount = 2;

    for (int i = Math.min(maxStepCount + skipCount, traceElements.length) - 1; i >= skipCount; i--) {
      String className = traceElements[i].getClassName().substring(traceElements[i].getClassName().lastIndexOf(".") + 1);
      recentSteps += " >> " + className + "." + traceElements[i].getMethodName() + "()";
    }

    return recentSteps;
  }

  public static CharSequence formatPresenceInformation (Context context, Recipient recipient)
  {
    if (!TextUtils.isEmpty(recipient.getPresenceInformation())) {
      Splitter rawPresenceInformation = Splitter.on(',');
      Iterable<String> resultTokens = rawPresenceInformation.split(recipient.getPresenceInformation());
      Recipient.PresenceType presenceType = Recipient.PresenceType.values()[Integer.valueOf(Iterables.get(resultTokens, 0))];
      Long timestamp = Long.valueOf(Iterables.get(resultTokens, 1));

      switch (presenceType) {
        case ONLINE:
          return context.getString(R.string.presence_status_online);
        case OFFLINE:
          CharSequence date = DateUtils.getBriefRelativeTimeSpanString(context,
                                                                       context.getResources().getConfiguration().locale,
                                                                       TimeUnit.SECONDS.toMillis(timestamp));
          return context.getResources().getString(R.string.presence_status_inactive, date);

        default: return "";
      }
    }

    return "";
  }
}
