package com.unfacd.android.ui.components.intro_contact;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.unfacd.android.R;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Pair;

import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.notifications.AbstractNotificationBuilder;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.util.concurrent.ExecutionException;


public class IntroContactNotificationBuilder extends AbstractNotificationBuilder
{
  private static final String TAG = IntroContactNotificationBuilder.class.getSimpleName();

  public static final int INTRO_CONTACT_NOTIFICATION   = 1970;

  public IntroContactNotificationBuilder(Context context, NotificationPrivacyPreference privacy, long introId, Intent intent) {
    super(context, privacy);

    Optional<Pair<Long, IntroContactDescriptor>> descriptor = DatabaseFactory.getUnfacdIntroContactsDatabase(context).getIntroContact(introId);
    IntroContactDescriptor introContactDescriptor = descriptor.get().second;

    Recipient recipient = Recipient.from(context, descriptor.get().second.getAddress(), false);

    byte [] introAvatar = null;
    if (!TextUtils.isEmpty(introContactDescriptor.avatarId)) {
      introAvatar = loadIntroContactAvatar(context, Address.fromSerialized(introContactDescriptor.avatarId));
    }

    ContactPhoto contactPhoto = recipient.getContactPhoto();
    FallbackContactPhoto fallbackContactPhoto = recipient.getFallbackContactPhoto();

    if (introAvatar != null || contactPhoto != null) {
      try {
        setLargeIcon(GlideApp.with(context.getApplicationContext())
                             .load(introAvatar!=null?introAvatar:fallbackContactPhoto)
                             .diskCacheStrategy(DiskCacheStrategy.ALL)
                             .circleCrop()
                             .submit(context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                                     context.getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height))
                             .get());
      } catch (InterruptedException | ExecutionException e) {
        Log.w(TAG, e);
        setLargeIcon(fallbackContactPhoto.asDrawable(context, recipient.getColor().toConversationColor(context)));
      }
    } else {
      setLargeIcon(fallbackContactPhoto.asDrawable(context, recipient.getColor().toConversationColor(context)));
    }

    setSmallIcon(R.drawable.icon_notification);
    setContentTitle(recipient.getDisplayName());
    setContentText(context.getString(R.string.IntroContactNotificationBuilder_intro_contact));
    setContentIntent(PendingIntent.getActivity(context, 0, intent, 0));
    setOngoing(true);
    setAlarms(null, RecipientDatabase.VibrateState.DEFAULT);
    setChannelId(NotificationChannels.INTRO_CONTACTS);

//  public static Notification getIntroContactNotification(Context context, int type, Recipient recipient, long introId) {
//    Intent contentIntent = new Intent(context, ConversationListActivity.class);
//    contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
//
//    PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, contentIntent, 0);
//
//    NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationChannels.CALLS)
//            .setSmallIcon(R.drawable.icon_notification)
//            .setContentIntent(intent)
//            .setOngoing(true)
//            .setChannelId(NotificationChannels.INTRO_CONTACTS)
//            .setContentTitle(recipient.getName());
//
//
//    builder.setContentText(context.getString(R.string.IntroContactNotificationBuilder_intro_contact));
//    builder.setPriority(NotificationCompat.PRIORITY_MIN);
//    builder.addAction(getActivityNotificationAction(context, ConversationListActivity.INTROCONTACT_MSGID, introId, R.drawable.ic_phone_grey600_32dp, R.string.IntroContactNotificationBuilder_intro_contact_view));


//    if (type == TYPE_INCOMING_CONNECTING) {
//      builder.setContentText(context.getString(R.string.CallNotificationBuilder_connecting));
//      builder.setPriority(NotificationCompat.PRIORITY_MIN);
//    } else if (type == TYPE_INCOMING_RINGING) {
//      builder.setContentText(context.getString(R.string.NotificationBarManager__incoming_signal_call));
//      builder.addAction(getServiceNotificationAction(context, WebRtcCallService.ACTION_DENY_CALL, R.drawable.ic_close_grey600_32dp,   R.string.NotificationBarManager__deny_call));
//      builder.addAction(getActivityNotificationAction(context, WebRtcCallActivity.ANSWER_ACTION, R.drawable.ic_phone_grey600_32dp, R.string.NotificationBarManager__answer_call));
//    } else if (type == TYPE_OUTGOING_RINGING) {
//      builder.setContentText(context.getString(R.string.NotificationBarManager__establishing_signal_call));
//      builder.addAction(getServiceNotificationAction(context, WebRtcCallService.ACTION_LOCAL_HANGUP, R.drawable.ic_call_end_grey600_32dp, R.string.NotificationBarManager__cancel_call));
//    } else {
//      builder.setContentText(context.getString(R.string.NotificationBarManager_signal_call_in_progress));
//      builder.addAction(getServiceNotificationAction(context, WebRtcCallService.ACTION_LOCAL_HANGUP, R.drawable.ic_call_end_grey600_32dp, R.string.NotificationBarManager__end_call));
//    }

//    return builder.build();

//    ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE))
//            .notify((int)0, builder.build());
//
  }

  byte [] loadIntroContactAvatar (Context context, Address avatarIdAddress)
  {
    byte[] avatarBytes = null;

    if (AvatarHelper.getAvatarFile(context, avatarIdAddress).exists() && AvatarHelper.getAvatarFile(context, avatarIdAddress).length() > 0) {
      try {
        avatarBytes = Util.readFully(AvatarHelper.getInputStreamFor(context, avatarIdAddress));
      } catch (IOException e) {
        Log.w(TAG, e);
      }
    }

    return avatarBytes;
  }

  private void setLargeIcon(@Nullable Drawable drawable) {
    if (drawable != null) {
      int    largeIconTargetSize  = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
      Bitmap recipientPhotoBitmap = BitmapUtil.createFromDrawable(drawable, largeIconTargetSize, largeIconTargetSize);

      if (recipientPhotoBitmap != null) {
        setLargeIcon(recipientPhotoBitmap);
      }
    }
  }

  private static NotificationCompat.Action getServiceNotificationAction(Context context, String action, int iconResId, int titleResId) {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(action);

    PendingIntent pendingIntent = PendingIntent.getService(context, 0, intent, 0);

    return new NotificationCompat.Action(iconResId, context.getString(titleResId), pendingIntent);
  }

  private static NotificationCompat.Action getActivityNotificationAction(@NonNull Context context, @NonNull String action, long introId,
                                                                         @DrawableRes int iconResId, @StringRes int titleResId)
  {
    Intent intent = new Intent(context, ConversationListActivity.class);
    intent.setAction(action);
    intent.putExtra(action, introId);

    PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);

    return new NotificationCompat.Action(iconResId, context.getString(titleResId), pendingIntent);
  }
}