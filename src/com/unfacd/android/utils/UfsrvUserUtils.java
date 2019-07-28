package com.unfacd.android.utils;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.ProfileCommandDescriptor;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.IProfileOperationDescriptor;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvcmd.events.AppEventFenceUserPref;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefNickname;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPref;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefProfileKey;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefRoamingMode;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingEndSessionMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;

import java.util.List;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;

public class UfsrvUserUtils
{

  private static final String TAG = UfsrvUserUtils.class.getSimpleName();

  public static @Nullable
  Long processUfsrvUserCommand(@NonNull Context context,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId,
                                  boolean outgoing) throws MmsException
  {
    UserCommand userCommand      = message.getUfsrvCommand().getUserCommand();

    if (userCommand == null) {
      Log.e(TAG, String.format("processUfsrvUserCommand (%d): UserCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (userCommand.getHeader().getCommand())
    {
      case UserCommand.CommandTypes.PREFERENCES_VALUE:
      case UserCommand.CommandTypes.PREFERENCE_VALUE:
        return processUserCommandUserPrefs(context, envelope, message, smsMessageId, outgoing);


      case UserCommand.CommandTypes.FENCE_PREFERENCES_VALUE:
      case UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE:
        return processUserCommandFenceUserPrefs(context, envelope, message, smsMessageId, outgoing);

      case SignalServiceProtos.UserCommand.CommandTypes.END_SESSION_VALUE:
        return processUserCommandEndSession(context, envelope, message, null, smsMessageId, outgoing);

      default:
        Log.e(TAG, String.format("processUfsrvUserCommand (%d)(command:'%d', args:'%d'): Received no valid UserCommand Type: RETURNING", Thread.currentThread().getId(), userCommand.getHeader().getCommand(), userCommand.getHeader().getArgs()));

    }

    return Long.valueOf(-1);
  }

  private static @Nullable Long
  processUserCommandUserPrefs(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Optional<Long> smsMessageId,
                                boolean outgoing)
  {
    UserCommand     userCommand      = message.getUfsrvCommand().getUserCommand();
    SignalServiceProtos.UserPreference  userPref;

    if (userCommand.getPrefsCount() > 0) {
      userPref=userCommand.getPrefs(0);
      Log.d(TAG, String.format("processUserCommandUserPrefs (%d, eid:'%d', args:'%d'): Received pref information: prefid:'%d'", Thread.currentThread().getId(), userCommand.getHeader().getEid(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
    } else {
      Log.e(TAG, String.format("processUserCommandUserPrefs (%d): Received no pref record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (userPref.getPrefId())
    {
      case ROAMING_MODE:
      case RM_WANDERER:
      case RM_CONQUERER:
      case RM_JOURNALER:
        return (handleUserCommandUserPrefsRoamingMode(context, envelope, message, smsMessageId, outgoing));

      case NICKNAME:
        return (handleUserCommandUserPrefsNick(context, envelope, message, smsMessageId, outgoing));

      case USERAVATAR:
        return (handleUserCommandUserPrefsAvatar(context, envelope, message, smsMessageId, outgoing));

      case PROFILE:
        return (handleUserCommandUserPrefsProfile(context, envelope, message, smsMessageId));

      case NETSTATE:
        return (handleUserCommandUserPrefsPresenceSharing(context, envelope, message, smsMessageId, outgoing));

      case READ_RECEIPT:
        return (handleUserCommandUserPrefsReadReceiptSharing(context, envelope, message, smsMessageId));

      case BLOCKING:
        return (handleUserCommandUserPrefsBlockedSharing(context, envelope, message, smsMessageId));

      case CONTACTS:
        return (handleUserCommandUserPrefsContactSharing(context, envelope, message, smsMessageId));

      case UNSOLICITED_CONTACT:
        return (handleUserCommandUserPrefsUnsolicitedContact(context, envelope, message, outgoing));

      case LOCATION:
//return (handleUserCommandUserPrefsLocationSharing(context, envelope, message, smsMessageId, outgoing));
      case ACTIVITY_STATE:
      default:
        Log.e(TAG, String.format("processUserCommandUserPrefs (%d, prefid:'%d', args:'%d'): Received UKNOWN PUSER PREF ", Thread.currentThread().getId(), userPref.getPrefId(), userCommand.getHeader().getArgs()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsRoamingMode(@NonNull Context context,
                                                @NonNull SignalServiceEnvelope envelope,
                                                @NonNull SignalServiceDataMessage message,
                                                @NonNull Optional<Long> smsMessageId,
                                                boolean outgoing)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();


    if (userCommand.getHeader().getArgs()==CommandArgs.ACCEPTED.getNumber()) {
      boolean isSet = userPref.getValuesInt()==1;
      long uid;

      Log.d(TAG, String.format("handleUserCommandUserPrefsRoamingMode (%d, args:'%d'): Received SERVER ACCEPTED USER PREF PROFILE message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));

      if (userCommand.hasOriginator()) {
        uid = UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray());
      } else {
        uid = TextSecurePreferences.getUserId(context);
        //todo: factor this logic out
        if (userPref.getPrefId().getNumber() == SignalServiceProtos.UserPrefs.ROAMING_MODE_VALUE) {
          if (!isSet) UserPrefsUtils.setRoamingMode(context, UserPrefsUtils.RoamingMode.RM_UNSET);
          else  UserPrefsUtils.setRoamingMode(context, UserPrefsUtils.RoamingMode.RM_WANDERER);
        } else {
          if (userPref.getPrefId().getNumber() >= 1 && userPref.getPrefId().getNumber() <= 3) {
            if (isSet)  UserPrefsUtils.setRoamingMode(context, UserPrefsUtils.RoamingMode.values()[userPref.getPrefId().getNumber()]);
            else UserPrefsUtils.setRoamingMode(context, UserPrefsUtils.RoamingMode.RM_WANDERER);
          } else {
              Log.e(TAG, String.format("handleUserCommandUserPrefsRoamingMode (%d, args:'%d'): ERROR: UNKNOWN prefid for roaming mode", Thread.currentThread().getId(), userPref.getPrefId().getNumber()));
          }
        }
      }

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(uid,
                                                                                                userPref.getPrefId().getNumber(),
                                                                                                userPref.getValuesInt()==0?false:true,
                                                                                                1,
                                                                                                clientArg.getNumber()));
    }
    else
    if (userCommand.getHeader().getArgs()==CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsRoamingMode (%d): REJECTED USER FENCE PREF message ", Thread.currentThread().getId()));
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(TextSecurePreferences.getUserId(context),
                                                                                                userPref.getPrefId().getNumber(),
                                                                                                userPref.getValuesInt()==0?false:true,
                                                                                                0,
                                                                                                clientArg.getNumber()));
      //nothing for now
    } else {
      Log.d(TAG, String.format("handleUserCommandUserPrefsRoamingMode (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return Long.valueOf(-1L);
  }

  public static void setRoamingMode (boolean isSet) {

  }

  private static @Nullable Long
  handleUserCommandUserPrefsUnsolicitedContact(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull SignalServiceDataMessage message, boolean outgoing)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();

    if (userCommand.getHeader().getArgs()==CommandArgs.ACCEPTED.getNumber()) {

      TextSecurePreferences.setUnsolicitedContactAction(context, (int)userPref.getValuesInt()+"");
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPref(userPref, userCommand.getHeader().getArgs(), clientArg));
    }
    else
    if (userCommand.getHeader().getArgs()==CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsUnsolicitedContact (%d): REJECTED USER FENCE PREF message ", Thread.currentThread().getId()));
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPref(userPref, userCommand.getHeader().getArgs(), clientArg));
      //nothing for now
    } else {
      Log.d(TAG, String.format("handleUserCommandUserPrefsUnsolicitedContact (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsNick(@NonNull Context context,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId,
                                 boolean outgoing)
  {
    Recipient                           thisRecipient = myOwnRecipient(false);
    UserCommand                         userCommand   = message.getUfsrvCommand().getUserCommand();
    SignalServiceProtos.UserPreference  userPref      = userCommand.getPrefs(0);

    Log.d(TAG, String.format("handleUserCommandUserPrefsNick (%d, args:'%d'): Received USER PREF NICK message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));
    if (isCommandAccepted(userCommand)) {
      if (!TextUtils.isEmpty(thisRecipient.getNickname())) {
        if (!thisRecipient.getNickname().equals(userPref.getValuesStr())) {
          DatabaseFactory.getRecipientDatabase(context).setNickname(thisRecipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        } else Log.w(TAG, "handleUserCommandUserPrefsNick: Nickname are the same");
      } else {
        //nickname was not set in recipient
        DatabaseFactory.getRecipientDatabase(context).setNickname(thisRecipient, userPref.getValuesStr());
        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
      }
      Log.d(TAG, String.format("handleUserCommandUserPrefsNick (%d, nick:'%s'): ACCEPTED USER PREF NICK message ", Thread.currentThread().getId(), userPref.getValuesStr()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary (thisRecipient, userCommand.getHeader().getEid());
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsNick (%d, nick:'%s'): REJECTED USER PREF NICK message ", Thread.currentThread().getId(), userPref.getValuesStr()));
    } else if (isCommandUpdated(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsNick (%d, uid:'%d', nick:'%s'): UPDATED USER PREF NICK message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient!=null) {
        if (!TextUtils.equals(userPref.getValuesStr(), recipient.getNickname())) {
          DatabaseFactory.getRecipientDatabase(context).setNickname(recipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));

          if (userCommand.getHeader().hasEid()) updateEidIfNecessary (recipient, userCommand.getHeader().getEid());

          insertProfileLog (context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        } else {
          Log.d(TAG, String.format("handleUserCommandUserPrefsNick (%d, uid:'%d', nick:'%s'): NOT UPDATING NICE NAMES: IDENTICAL", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
        }
      }
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsAvatar(@NonNull Context context,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId,
                                 boolean outgoing)
  {
    Recipient                           thisRecipient = myOwnRecipient(false);
    UserCommand                         userCommand   = message.getUfsrvCommand().getUserCommand();
    SignalServiceProtos.UserPreference  userPref      = userCommand.getPrefs(0);

    Log.d(TAG, String.format("handleUserCommandUserPrefsAvatar (%d, args:'%d'): Received USER PREF AVATAR message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));
    if (isCommandAccepted(userCommand)) {
      if (TextUtils.isEmpty(userPref.getValuesStr())) {
        AvatarHelper.delete(context, thisRecipient.getAddress());
        DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(thisRecipient, null);
        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        Log.w(TAG, "handleUserCommandUserPrefsAvatar: avatar deleted for you");
      } else {
        if (!TextUtils.isEmpty(thisRecipient.getGroupAvatarUfsrvId())) {
          if (!thisRecipient.getGroupAvatarUfsrvId().equals(userPref.getValuesStr())) {
            DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(thisRecipient, userPref.getValuesStr());
            ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
          }
          else Log.w(TAG, "handleUserCommandUserPrefsAvatar: avatar values are the same");
        }
        else {
          //avatar was not previously set
          DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(thisRecipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        }
      }

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(thisRecipient, userCommand.getHeader().getEid());

      Log.d(TAG, String.format("handleUserCommandUserPrefsAvatar (%d, nick:'%s'): ACCEPTED USER PREF AVATAR message ", Thread.currentThread().getId(), userPref.getValuesStr()));
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsAvatar (%d, nick:'%s'): REJECTED USER PREF AVATAR message ", Thread.currentThread().getId(), userPref.getValuesStr()));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));
    } else if (isCommandUpdated(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsAvatar (%d, uid:'%d', nick:'%s'): UPDATED USER PREF AVATAR message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient!=null) {
        if (!TextUtils.equals(userPref.getValuesStr(), recipient.getGroupAvatarUfsrvId())) {
          DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(recipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new ProfileAvatarDownloadJob(recipient.getUfsrvUid(), userCommand.getAttachments(0)));

          if (userCommand.getHeader().hasEid()) updateEidIfNecessary(recipient, userCommand.getHeader().getEid());

          insertProfileLog (context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        } else {
          Log.d(TAG, String.format("handleUserCommandUserPrefsAvatar (%d, uid:'%d', nick:'%s'): NOT UPDATING AVATAR: IDENTICAL", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
        }
      }
    } else if (isCommandDeleted(userCommand)) {
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient!=null) {
        AvatarHelper.delete(context, recipient.getAddress());
        DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(recipient, null);

        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(recipient, userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        Log.w(TAG, String.format("handleUserCommandUserPrefsAvatar: avatar deleted for '%s'", recipient.getAddress()));
      }
    }

    return Long.valueOf(-1L);
  }


  /**
   * Add/remove a target-user to this session user's sharelist. Thereby, this user is sharing their profile with the target-user. This doesn't automatically share target-user's
   * profile with this user.
   * Under this scenario two acknowledgement messages are sent: one to the originator (called sharing) and another one to the target user (called shared)
   */
  private static @Nullable Long
  handleUserCommandUserPrefsProfile(@NonNull Context context,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @NonNull Optional<Long> smsMessageId)
  {
    Recipient   thisRecipient = myOwnRecipient(false);
    UserCommand userCommand   = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      Recipient recipientSharingWith = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userCommand.getTargetList(0).getUfsrvuid().toByteArray()), false);

      if (isCommandClientAdded(userCommand)) {
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipientSharingWith, true);
        Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, nick:'%s'): ACCEPTED USER PREF PROFILE message: ADDED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
      } else if (isCommandClientDeleted(userCommand))  {
      Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, nick:'%s'): ACCEPTED USER PREF PROFILE message: REMOVED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipientSharingWith, false);
      DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipientSharingWith, null);
    } else  {
      Log.e(TAG, String.format("handleUserCommandUserPrefsProfile (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF PROFILE message: UKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));
      return Long.valueOf(-1L);
    }

    if (userCommand.getHeader().hasEid()) updateEidIfNecessary(thisRecipient, userCommand.getHeader().getEid());
    if (userCommand.getTargetList(0).hasEid()) updateEidIfNecessary(recipientSharingWith, userCommand.getTargetList(0).getEid());

      insertProfileLog (context, userCommand, thisRecipient, recipientSharingWith);
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefProfileKey(userCommand.getPrefs(0), recipientSharingWith, CommandArgs.values()[userCommand.getHeader().getArgs()], userCommand.getHeader().getArgsClient()));
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid:'%d'): UFSRV REJECTED USER PREF AVATAR message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid_orig:'%d'): was added to orig users profile sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipientOriginator, userCommand.getProfileKey().toByteArray());
        DatabaseFactory.getRecipientDatabase(context).setProfileShared(recipientOriginator, true);

        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(thisRecipient, userCommand.getHeader().getEid());
        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());

        insertProfileLog (context, userCommand, thisRecipient, recipientOriginator);
      } else {
          Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        }
    } else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid_orig:'%d'): was removed from orig user's profile sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipientOriginator, null);
        DatabaseFactory.getRecipientDatabase(context).setProfileShared(recipientOriginator, false);

        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(thisRecipient, userCommand.getHeader().getEid());
        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());

        insertProfileLog (context, userCommand, thisRecipient, recipientOriginator);
      } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    } else {
      Log.w(TAG, String.format("handleUserCommandUserPrefsProfile (%d, uid_orig:'%d', args:'%d'): Unknown command arg ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userCommand.getHeader().getArgs()));
    }

    return Long.valueOf(-1L);
  }


  private static @Nullable Long
  handleUserCommandUserPrefsPresenceSharing(@NonNull Context context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceDataMessage message,
                                            @NonNull Optional<Long> smsMessageId,
                                            boolean outgoing)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();

    //server accepted request to share/unshare our presence with a given user
    if (isCommandAccepted(userCommand)) {
      //server accepted a previous request to add/remove a user to this client's sharelist. Doesn't mean the other user accepted that. (see ADDED below for that)
      //this user is allowing his profile to be shared with the other user (profile_sharing)
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (isCommandClientAdded(userCommand)) {
        DatabaseFactory.getRecipientDatabase(context).setPresenceSharing(recipientSharingWith, true);
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s'): ACCEPTED USER PREF PRESENCE message: ADDED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
      } else if (isCommandClientDeleted(userCommand)){
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s'): ACCEPTED USER PREF PRESENCE message: REMOVED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
        DatabaseFactory.getRecipientDatabase(context).setPresenceSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF PRESENCE message: UKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF PRESENCE ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    } else if (isCommandAdded(userCommand)) {//the originator is allowing/shared their presence with this client
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): was added to orig user's presence sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        DatabaseFactory.getRecipientDatabase(context).setPresenceShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

//        insertProfileLog (context, userCommand, thisUser, recipient_originator);
      } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {//the originator is disallowing/unshared their presence with this user
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): was removed orig user's presence sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        DatabaseFactory.getRecipientDatabase(context).setPresenceShared(recipientOriginator, false);

//        insertProfileLog (context, userCommand, thisUser, recipient_originator);
      }
      else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsReadReceiptSharing(@NonNull Context context,
                                               @NonNull SignalServiceEnvelope envelope,
                                               @NonNull SignalServiceDataMessage message,
                                               @NonNull Optional<Long> smsMessageId)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (isCommandClientAdded(userCommand)) {
        DatabaseFactory.getRecipientDatabase(context).setReadReceiptSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        DatabaseFactory.getRecipientDatabase(context).setReadReceiptSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format("handleUserCommandUserPrefsReadReceiptSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsReadReceiptSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        DatabaseFactory.getRecipientDatabase(context).setReadReceiptShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsReadReceiptSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
        DatabaseFactory.getRecipientDatabase(context).setReadReceiptShared(recipientOriginator, false);

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      }
      else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsReadReceiptSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsBlockedSharing(@NonNull Context context,
                                           @NonNull SignalServiceEnvelope envelope,
                                           @NonNull SignalServiceDataMessage message,
                                           @NonNull Optional<Long> smsMessageId)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (isCommandClientAdded(userCommand)) {
        DatabaseFactory.getRecipientDatabase(context).setBlockSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        DatabaseFactory.getRecipientDatabase(context).setBlocked(recipientSharingWith, false);
      } else  Log.e(TAG, String.format("handleUserCommandUserPrefsBlockedSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsBlockedSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        DatabaseFactory.getRecipientDatabase(context).setBlockShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsBlockedSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
        DatabaseFactory.getRecipientDatabase(context).setBlockShared(recipientOriginator, false);

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      }
      else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsBlockedSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  handleUserCommandUserPrefsContactSharing(@NonNull Context context,
                                           @NonNull SignalServiceEnvelope envelope,
                                           @NonNull SignalServiceDataMessage message,
                                           @NonNull Optional<Long> smsMessageId)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (isCommandClientAdded(userCommand)) {
        DatabaseFactory.getRecipientDatabase(context).setContactSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        DatabaseFactory.getRecipientDatabase(context).setContactSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format("handleUserCommandUserPrefsContactSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsContactSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        DatabaseFactory.getRecipientDatabase(context).setContactShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsContactSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
        DatabaseFactory.getRecipientDatabase(context).setContactShared(recipientOriginator, false);

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      }
      else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsContactSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }

  private static @Nullable Long
  processUserCommandFenceUserPrefs(@NonNull Context context,
                              @NonNull SignalServiceEnvelope envelope,
                              @NonNull SignalServiceDataMessage message,
                              @NonNull Optional<Long> smsMessageId,
                              boolean outgoing)
  {
    UserCommand     userCommand      = message.getUfsrvCommand().getUserCommand();
    SignalServiceProtos.FenceUserPreference  userPref;

    if (userCommand.getFencePrefsCount() > 0) {
      userPref=userCommand.getFencePrefs(0);
      Log.d(TAG, String.format("processUserCommandFenceUserPrefs (%d, eid:'%d', args:'%d'): Received pref information: prefid:'%d'", Thread.currentThread().getId(), userCommand.getHeader().getEid(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
    } else {
      Log.e(TAG, String.format("processUserCommandFenceUserPrefs (%d): Received no pref record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (userPref.getPrefId())
    {
      case STICKY_GEOGROUP:
//        return (handleUserCommandUserPrefsNick(context, masterSecret, envelope, message, smsMessageId, outgoing));

      case PROFILE_SHARING:
        return (handleUserCommandFenceUserPrefsProfileSharing(context, envelope, message, smsMessageId));

      case IGNORING:
//        return (handleUserCommandUserPrefsNick(context, masterSecret, envelope, message, smsMessageId, outgoing));

      default:
        Log.e(TAG, String.format("processUserCommandFenceUserPrefs (%d, prefid:'%d', args:'%d'): Received UKNOWN PUSER PREF ", Thread.currentThread().getId(), userPref.getPrefId(), userCommand.getHeader().getArgs()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable Long
  handleUserCommandFenceUserPrefsProfileSharing(@NonNull Context context,
                                                @NonNull SignalServiceEnvelope envelope,
                                                @NonNull SignalServiceDataMessage message,
                                                @NonNull Optional<Long> smsMessageId)
  {
    UserCommand         userCommand = message.getUfsrvCommand().getUserCommand();
    FenceRecord         fenceRecord = userCommand.getFences(0);
    CommandArgs         clientArg   = userCommand.getHeader().getArgsClient();
    Recipient           recipientGroup  = Recipient.fromFid(context, fenceRecord.getFid(), false);

    if (isCommandAccepted(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandFenceUserPrefsProfileSharing (%d, args:'%d', fid:'%d'): Received SERVER ACCEPTED FENCE PREF PROFILE message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand(), fenceRecord.getFid()));
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.fromFid(context, fenceRecord.getFid(), false), clientArg.getNumber()==CommandArgs.SET_VALUE?true:false);
      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientGroup);

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventFenceUserPref(userCommand.getFencePrefs(0), CommandArgs.ACCEPTED, clientArg));
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, fid:'%d'): REJECTED USER FENCE PREF message ", Thread.currentThread().getId(), fenceRecord.getFid()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientGroup);

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventFenceUserPref(userCommand.getFencePrefs(0), CommandArgs.REJECTED, clientArg));
      //nothing for now
    } else {
        Log.d(TAG, String.format("handleUserCommandUserPrefsProfile (%d, arg:'%d', fid:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs(), fenceRecord.getFid()));
    }

    return Long.valueOf(-1L);
  }

  private static
  Long processUserCommandEndSession (@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @Nullable GroupDatabase.GroupRecord groupRecordUfsrv,
                                     @NonNull Optional<Long> smsMessageId,
                                     boolean outgoing)

  {
    SmsDatabase smsDatabase         = DatabaseFactory.getSmsDatabase(context);
    UserCommand userCommand         = envelope.getUserCommand();
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(Address.fromSerialized(UfsrvCommandUtils.getOriginatorUserId(envelope.getUfsrvCommand()).toString()),
                                                                      envelope.getSourceDevice(),
                                                                      message.getTimestamp(),
                                                                      "", Optional.absent(), 0, 0,false,
                                                                      envelope.getUfsrvCommand());

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<MessagingDatabase.InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      sessionStore.deleteAllSessions(recipientOriginator.getAddress().serialize());//)envelope.getSource());//

      SecurityEvent.broadcastSecurityUpdateEvent(context);
      MessageNotifier.updateNotification(context, threadId);
    }

    return Long.valueOf(-1);
  }

  static void insertProfileLog (Context context, UserCommand userCommand, Recipient thisUser, Recipient otherUser)
  {
    if (!otherUser.isGroupRecipient()) {
      List<Long> fids = DatabaseFactory.getGroupDatabase(context).getForTwoGroups(thisUser, otherUser);
      if (fids.size() > 0) {
        UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand);
        for (Long fid : fids) {
          DatabaseFactory.getSmsDatabase(context).insertProfileLog(fid.longValue(), thisUser.getAddress(), ufsrvCommand.buildToSerialise(), true);
        }
      }
    } else {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand);
      DatabaseFactory.getSmsDatabase(context).insertGroupProfileLog(otherUser.getUfsrvId(), thisUser.getAddress(), ufsrvCommand.buildToSerialise(), true);
    }
  }

  static public void UfsrvShareProfileWithRecipient (Context context, Recipient recipient, boolean isUnshare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.PROFILE);
    profileOperationDescriptor.setProfileOperationMode(isUnshare?IProfileOperationDescriptor.ProfileOperationMode.UNSET:IProfileOperationDescriptor.ProfileOperationMode.SET);
    profileOperationDescriptor.setProfileOperationScope(recipient.isGroupRecipient()?IProfileOperationDescriptor.ProfileOperationScope.GROUP:IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.getAddress()));
  }

  static public void UfsrvSharePresence (Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.PRESENCE);
    profileOperationDescriptor.setProfileOperationMode(isShare?IProfileOperationDescriptor.ProfileOperationMode.SET:IProfileOperationDescriptor.ProfileOperationMode.UNSET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.getAddress()));

  }

  static public void UfsrvShareReadReceipt (Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.READ_RECEIPT);
    profileOperationDescriptor.setProfileOperationMode(isShare?IProfileOperationDescriptor.ProfileOperationMode.SET:IProfileOperationDescriptor.ProfileOperationMode.UNSET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.getAddress()));

  }

  static public void UfsrvShareBlocking (Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.BLOCKING);
    profileOperationDescriptor.setProfileOperationMode(isShare?IProfileOperationDescriptor.ProfileOperationMode.SET:IProfileOperationDescriptor.ProfileOperationMode.UNSET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.getAddress()));

  }

  static public void UfsrvShareContact (Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.CONTACTS);
    profileOperationDescriptor.setProfileOperationMode(isShare?IProfileOperationDescriptor.ProfileOperationMode.SET:IProfileOperationDescriptor.ProfileOperationMode.UNSET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.getAddress()));

  }

  /**
   * Main interface for sending boolean user preference commands
   */
  static public void UfsrvSetSettableUserPreference (Context context, Recipient recipient, SignalServiceProtos.UserPrefs prefId, boolean isSet)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.SETTABLE);
    profileOperationDescriptor.setProfileOperationMode(isSet?IProfileOperationDescriptor.ProfileOperationMode.SET:IProfileOperationDescriptor.ProfileOperationMode.UNSET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);

    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), Integer.toString(prefId.getNumber())));
  }

  static public void UfsrvSetMultivalueUserPreference (Context context, Recipient recipient, SignalServiceProtos.UserPrefs prefId, Integer value)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.SETTABLE_MULTI);
    profileOperationDescriptor.setProfileOperationMode(IProfileOperationDescriptor.ProfileOperationMode.SET);
    profileOperationDescriptor.setProfileOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor, value);

    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), Integer.toString(prefId.getNumber())));
  }

  static public void updateEidIfNecessary (Recipient recipient, long eid)
  {
    if (true) {
      DatabaseFactory.getRecipientDatabase(ApplicationContext.getInstance()).setEid(recipient, eid);
    }
  }

  static public Address myOwnAddress ()
  {
    return Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance()));
  }

  static public Recipient myOwnRecipient (boolean async)
  {
    return Recipient.from(ApplicationContext.getInstance(), myOwnAddress(), async);
  }

  static public Recipient recipientFromUserCommandOriginator (UserCommand userCommand, boolean async)
  {
    return Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(userCommand.getOriginator().getUfsrvuid().toByteArray()), async);
  }

  static public boolean isCommandAccepted (UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs()==CommandArgs.ACCEPTED.getNumber());
  }

  static public boolean isCommandRejected (UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs()==CommandArgs.REJECTED.getNumber());
  }

  static public boolean isCommandUpdated (UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs()==CommandArgs.UPDATED.getNumber());
  }

  static public boolean isCommandAdded (UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs()==CommandArgs.ADDED.getNumber());
  }

  static public boolean isCommandDeleted (UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs()==CommandArgs.DELETED.getNumber());
  }

  static public boolean isCommandClientAdded (UserCommand userCommand)
  {
    return userCommand.getHeader().getArgsClient()==CommandArgs.ADDED;
  }

  static public boolean isCommandClientDeleted (UserCommand userCommand)
  {
    return userCommand.getHeader().getArgsClient()==CommandArgs.DELETED;
  }
}
