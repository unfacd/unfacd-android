/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.utils;

import android.content.Context;
import android.text.TextUtils;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.jobs.UfsrvProfileRetrieverJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.IProfileOperationDescriptor;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.ProfileCommandDescriptor;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvcmd.events.AppEventFenceUserPref;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefNickname;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPref;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefProfileKey;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefRoamingMode;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.state.SessionStore;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingEndSessionMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPrefs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_BLOCKED;
import static org.thoughtcrime.securesms.database.GroupDatabase.MembershipUpdateMode.REMOVE_MEMBER;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;

public class UfsrvUserUtils
{
  private static final String TAG = Log.tag(UfsrvUserUtils.class);

  public static @Nullable
  Long processUfsrvUserCommand(@NonNull Context context,
                               @NonNull SignalServiceContent content,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Optional<Long> smsMessageId,
                               boolean outgoing) throws MmsException
  {
    UserCommand userCommand      = message.getUfsrvCommand().getUserCommand();

    if (userCommand == null) {
      Log.e(TAG, String.format(Locale.getDefault(), "processUfsrvUserCommand (%d): UserCommand was null: RETURNING", Thread.currentThread().getId()));
      return -1L;
    }

    switch (userCommand.getHeader().getCommand()) {
      case UserCommand.CommandTypes.PREFERENCES_VALUE:
      case UserCommand.CommandTypes.PREFERENCE_VALUE:
          return processUserCommandUserPrefs(context, envelope, message, smsMessageId, outgoing);

      case UserCommand.CommandTypes.FENCE_PREFERENCES_VALUE:
      case UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE:
        return processUserCommandFenceUserPrefs(context, envelope, message, smsMessageId, outgoing);

      case SignalServiceProtos.UserCommand.CommandTypes.END_SESSION_VALUE:
        return processUserCommandEndSession(context, envelope, message, null, smsMessageId, outgoing);

      default:
        Log.e(TAG, String.format(Locale.getDefault(), "processUfsrvUserCommand (%d)(command:'%d', args:'%d'): Received UNDEFINED UserCommand Type: RETURNING", Thread.currentThread().getId(), userCommand.getHeader().getCommand(), userCommand.getHeader().getArgs()));
    }


    return 1L;
  }

  private static Long processUserCommandRejected(@NonNull Context                   context,
                                                 @NonNull SignalServiceEnvelope     envelope,
                                                 @NonNull SignalServiceDataMessage  message,
                                                 @NonNull UserCommand               userCommand)

  {
    SignalServiceProtos.UserPreference  userPref;

    if (userCommand.getPrefsCount() <= 0) {
      Log.w(TAG, String.format(Locale.getDefault(), "processUserCommandRejected: Received USER COMMAND ERROR WITH NO PREF DEFINED: '%d', ARGS:'%d' FOR: ERROR:'%d'", userCommand.getHeader().getCommand(), userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsError()));
      return -1L;
    }

    userPref = userCommand.getPrefs(0);
    switch (userCommand.getHeader().getArgsError())
    {
      case UserCommand.Errors.LIST_MEMBERSHIP_VALUE: //context dependant: command: add-to-list: user already in list. remove-from-list: user not in list
      Log.w(TAG, String.format(Locale.getDefault(), "processUserCommandRejected: Received USER COMMAND ERROR ARG: '%d', CMD:'%d' ORIG ARGS:'%d', PrefId:'%d'", userCommand.getHeader().getArgsError(), userCommand.getHeader().getCommand(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
      if (isProfileShareListType(userPref.getPrefId())) {
        Recipient recipientSharingWith = Recipient.live(new UfsrvUid(userCommand.getTargetList(0).getUfsrvuid().toByteArray()).toString()).get();
      }
      break;

      case UserCommand.Errors.INADEQUATE_PERMISSIONS_VALUE:
      case UserCommand.Errors.MISSING_PARAMETER_VALUE:
      default:
        Log.w(TAG, String.format(Locale.getDefault(), "processUserCommandRejected: Received USER COMMAND UNHANDLED ERROR ARG: '%d', CMD:'%d' ORIG ARGS:'%d', PrefId:'%d'", userCommand.getHeader().getCommand(), userCommand.getHeader().getArgsError(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPref(userPref, userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient(), UserCommand.Errors.values()[userCommand.getHeader().getArgsError()]));

    return -1L;
  }

  static boolean isProfileShareListType(UserPrefs prefId)
  {
    return Stream.of(UserPrefs.PROFILE, UserPrefs.ACTIVITY_STATE, UserPrefs.BLOCKING, UserPrefs.READ_RECEIPT, UserPrefs.NETSTATE).filter(prefId::equals).findFirst().isPresent();
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
      userPref = userCommand.getPrefs(0);
      Log.d(TAG, String.format(Locale.getDefault(), "processUserCommandUserPrefs (%d, eid:'%d', args:'%d'): Received pref information: prefid:'%d'", Thread.currentThread().getId(), userCommand.getHeader().getEid(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
    } else {
      Log.e(TAG, String.format(Locale.getDefault(), "processUserCommandUserPrefs (%d): Received no pref record information: RETURNING", Thread.currentThread().getId()));
      return -1L;
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
        return handleUserCommandUserPrefsSharing(context, envelope,message, provideClientOpsForBlockingSharing());// (handleUserCommandUserPrefsBlockedSharing(context, envelope, message, smsMessageId));

      case CONTACTS:
        return (handleUserCommandUserPrefsContactSharing(context, envelope, message, smsMessageId));

      case UNSOLICITED_CONTACT:
        return (handleUserCommandUserPrefsUnsolicitedContact(context, envelope, message, outgoing));

      case BLOCKED_FENCE:
        return (handleUserCommandUserPrefsBlockedFence(context, envelope, message, smsMessageId));

      case LOCATION:
//return (handleUserCommandUserPrefsLocationSharing(context, envelope, message, smsMessageId, outgoing));
      case ACTIVITY_STATE:

      case GEOLOC_TRIGGER:
        return handleUserCommandUserPrefsGeolocTrigger(context, envelope, message);

      case BASELOC_ANCHOR_ZONE:
        return handleUserCommandUserPrefsBaselocZone(context, envelope, message);

      case HOMEBASE_GEOLOC:
        return handleUserCommandUserPrefsHomebaseGeoloc(context, envelope, message);

      default:
        Log.e(TAG, String.format(Locale.getDefault(), "processUserCommandUserPrefs (%d, prefid:'%d', args:'%d'): Received UNKNOWN USER PREF ", Thread.currentThread().getId(), userPref.getPrefId().getNumber(), userCommand.getHeader().getArgs()));
    }

    return -1L;
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


    if (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber()) {
      boolean isSet = userPref.getValuesInt() == 1;
      long uid;

      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsRoamingMode (%d, args:'%d'): Received SERVER ACCEPTED USER PREF PROFILE message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));

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
              Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsRoamingMode (%d, args:'%d'): ERROR: UNKNOWN prefid for roaming mode", Thread.currentThread().getId(), userPref.getPrefId().getNumber()));
          }
        }
      }

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(uid, userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 1, clientArg.getNumber()));
    }
    else
    if (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsRoamingMode (%d): REJECTED USER FENCE PREF message ", Thread.currentThread().getId()));
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(TextSecurePreferences.getUserId(context), userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 0, clientArg.getNumber()));
      //nothing for now
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsRoamingMode (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return -1L;
  }

  private static Long
  handleUserCommandUserPrefsGeolocTrigger(@NonNull Context context,
                                          @NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceDataMessage message)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();


    if (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsGeolocTrigger (prefid:'%d', args:'%d'): Received SERVER ACCEPTED USER PREF PROFILE message ", userPref.getPrefId().getNumber(), userCommand.getHeader().getCommand()));
      UserPrefsUtils.setGeolocTrigger(UserPrefsUtils.GeolocRoamingTrigger.values()[(int)userPref.getValuesInt()]);

//      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(uid, userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 1, clientArg.getNumber()));
    } else if (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber()) {
       Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsGeolocTrigger (%d): REJECTED USERPREF GEOLOCTRIGGER message ", Thread.currentThread().getId()));
//       ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(TextSecurePreferences.getUserId(context), userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 0, clientArg.getNumber()));//nothing for now
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsGeolocTrigger (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return -1L;
  }

  private static Long
  handleUserCommandUserPrefsBaselocZone(@NonNull Context context,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceDataMessage message)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();


    if (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBaselocZone (prefid:'%d', args:'%d'): Received SERVER ACCEPTED USER PREF PROFILE message ", userPref.getPrefId().getNumber(), userCommand.getHeader().getCommand()));
      UserPrefsUtils.setBaselocZone(UserPrefsUtils.BaselocAnchorZone.values()[(int)userPref.getValuesInt()]);

//      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(uid, userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 1, clientArg.getNumber()));
    } else if (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBaselocZone (%d): REJECTED USERPREF BASELOCZONE message ", Thread.currentThread().getId()));
//      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefRoamingMode(TextSecurePreferences.getUserId(context), userPref.getPrefId().getNumber(), userPref.getValuesInt() == 0? false:true, 0, clientArg.getNumber()));//nothing for now
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBaselocZone (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return -1L;
  }


  private static Long
  handleUserCommandUserPrefsHomebaseGeoloc(@NonNull Context context,
                                           @NonNull SignalServiceEnvelope envelope,
                                           @NonNull SignalServiceDataMessage message)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();


    if (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsHomebaseGeoloc (prefid:'%d', args:'%d'): Received SERVER ACCEPTED USER PREF PROFILE message ", userPref.getPrefId().getNumber(), userCommand.getHeader().getCommand()));
      UserPrefsUtils.setHomebaseLocFromPacked(userPref.getValuesStr());
    } else if (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsHomebaseGeoloc (value:'%s'): REJECTED USERPREF BASELOCZONE message ", userPref.getValuesStr()));
      UserPrefsUtils.setHomebaseLocFromPacked(userPref.getValuesStr());//should be empty or previous valid value
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsHomebaseGeoloc (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
    }

    return -1L;
  }


  public static void setRoamingMode(boolean isSet) {

  }

  private static @Nullable Long
  handleUserCommandUserPrefsUnsolicitedContact(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull SignalServiceDataMessage message, boolean outgoing)
  {
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    CommandArgs     clientArg   = userCommand.getHeader().getArgsClient();

    if (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber()) {

      TextSecurePreferences.setUnsolicitedContactAction(context, (int)userPref.getValuesInt()+"");
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPref(userPref, userCommand.getHeader().getArgs(), clientArg));
    }
    else
    if (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber()) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsUnsolicitedContact (%d): REJECTED USER FENCE PREF message ", Thread.currentThread().getId()));
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPref(userPref, userCommand.getHeader().getArgs(), clientArg));
      //nothing for now
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsUnsolicitedContact (%d, arg:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs()));
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

    Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsNick (%d, args:'%d'): Received USER PREF NICK message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));
    if (isCommandAccepted(userCommand)) {
      if (!TextUtils.isEmpty(thisRecipient.getNickname())) {
        if (!thisRecipient.getNickname().equals(userPref.getValuesStr())) {
          SignalDatabase.recipients().setNickname(thisRecipient, userPref.getValuesStr());
          TextSecurePreferences.setUfsrvNickname(context,userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        } else Log.w(TAG, "handleUserCommandUserPrefsNick: Nickname are the same");
      } else {
        //nickname was not set in recipient
        SignalDatabase.recipients().setNickname(thisRecipient, userPref.getValuesStr());
        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
      }
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsNick (%d, nick:'%s'): ACCEPTED USER PREF NICK message ", Thread.currentThread().getId(), userPref.getValuesStr()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary (thisRecipient, userCommand.getHeader().getEid());
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsNick (%d, nick:'%s'): REJECTED USER PREF NICK message ", Thread.currentThread().getId(), userPref.getValuesStr()));
    } else if (isCommandUpdated(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsNick (%d, uid:'%d', nick:'%s'): UPDATED USER PREF NICK message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient != null) {
        if (!TextUtils.equals(userPref.getValuesStr(), recipient.getNickname())) {
          SignalDatabase.recipients().setNickname(recipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefNickname(UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));

          if (userCommand.getHeader().hasEid()) updateEidIfNecessary(recipient, userCommand.getHeader().getEid());

          insertProfileLog(context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        } else {
          Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsNick (%d, uid:'%d', nick:'%s'): NOT UPDATING NICE NAMES: IDENTICAL", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
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

    Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsAvatar (%d, args:'%d'): Received USER PREF AVATAR message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand()));
    if (isCommandAccepted(userCommand)) {
      if (TextUtils.isEmpty(userPref.getValuesStr())) {
        AvatarHelper.delete(context, thisRecipient.getId());
        SignalDatabase.recipients().setAvatarUfId(thisRecipient, null);
        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        Log.w(TAG, "handleUserCommandUserPrefsAvatar: avatar deleted for you");
      } else {
        if (!TextUtils.isEmpty(thisRecipient.getAvatarUfsrvId())) {
          if (!thisRecipient.getAvatarUfsrvId().equals(userPref.getValuesStr())) {
            SignalDatabase.recipients().setAvatarUfId(thisRecipient, userPref.getValuesStr());
            ApplicationDependencies.getJobManager().add(new ProfileAvatarDownloadJob(TextSecurePreferences.getUfsrvUserId(context), userCommand.getAttachments(0).getId(), userCommand.getAttachments(0).getKey().toByteArray()));
            ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
          } else Log.w(TAG, "handleUserCommandUserPrefsAvatar: avatar values are the same");
        } else {
          //avatar was not previously set
          SignalDatabase.recipients().setAvatarUfId(thisRecipient, userPref.getValuesStr());
          ApplicationDependencies.getJobManager().add(new ProfileAvatarDownloadJob(TextSecurePreferences.getUfsrvUserId(context), userCommand.getAttachments(0).getId(), userCommand.getAttachments(0).getKey().toByteArray()));
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), userPref.getValuesStr()));
        }
      }

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(thisRecipient, userCommand.getHeader().getEid());

      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsAvatar (%d, avatar:'%s'): ACCEPTED USER PREF AVATAR message ", Thread.currentThread().getId(), userPref.getValuesStr()));
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsAvatar (%d, avatar:'%s'): REJECTED USER PREF AVATAR message ", Thread.currentThread().getId(), userPref.getValuesStr()));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));
    } else if (isCommandUpdated(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsAvatar (%d, uid:'%d', avatar:'%s'): UPDATED USER PREF AVATAR message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient != null) {
        if (!TextUtils.equals(userPref.getValuesStr(), recipient.getAvatarUfsrvId()) || !AvatarHelper.avatarExists(context, recipient.getId())) {
          SignalDatabase.recipients().setAvatarUfId(recipient, userPref.getValuesStr());
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
          ApplicationDependencies.getJobManager().add(new ProfileAvatarDownloadJob(recipient.getUfsrvUid(), userCommand.getAttachments(0).getId(), userCommand.getAttachments(0).getKey().toByteArray()));

          if (userCommand.getHeader().hasEid()) updateEidIfNecessary(recipient, userCommand.getHeader().getEid());

          insertProfileLog (context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        } else {
          Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsAvatar (%d, uid:'%d', avatar:'%s'): NOT UPDATING AVATAR: IDENTICAL", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userPref.getValuesStr()));
        }
      }
    } else if (isCommandDeleted(userCommand)) {
      Recipient recipient = recipientFromUserCommandOriginator(userCommand, false);
      if (recipient != null) {
        AvatarHelper.delete(context, recipient.getId());
        SignalDatabase.recipients().setAvatarUfId(recipient, null);

        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(recipient, userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, thisRecipient, recipient); //only do it when updates concern other user. This user gets sliding notification
        Log.w(TAG, String.format("handleUserCommandUserPrefsAvatar: avatar deleted for '%s'", recipient.requireAddress()));
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
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand       userCommand = message.getUfsrvCommand().getUserCommand();
    Recipient         thisRecipient = myOwnRecipient(false);

    //server accepted request to share/unshare our profile with a given user
    if (isCommandAccepted(userCommand)) {
      return processUserProfileSharingAccepted(context, userCommand);
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsProfile (%d, uid:'%d'): UFSRV REJECTED USER PREF PROFILE message ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      return processUserCommandRejected(context, envelope, message, userCommand);
    } else if (isCommandAdded(userCommand)) {
      return processUserProfileSharingAdded(context, userCommand);
    } else if (isCommandDeleted(userCommand)) {
      return processUserProfileShareDeleted(context, userCommand);
    } else {
      Log.w(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsProfile (%d, uid_orig:'%d', args:'%d'): Unknown command arg ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray()), userCommand.getHeader().getArgs()));
    }

    return -1L;
  }


  /**
   * Server accepted request to share/unshare our profile with a given user
   */
  private static long
  processUserProfileSharingAccepted(@NonNull Context context, @NonNull UserCommand userCommand)
  {
    RecipientDatabase database = SignalDatabase.recipients();
    Recipient recipientSharingWith = Recipient.live(new UfsrvUid(userCommand.getTargetList(0).getUfsrvuid().toByteArray()).toString()).get();

    if (isCommandClientAdded(userCommand)) {
      SignalDatabase.recipients().setProfileSharing(recipientSharingWith, true);
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileSharingAccepted (%d, nick:'%s'): ACCEPTED USER PREF PROFILE message: ADDED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
    } else if (isCommandClientDeleted(userCommand))  {
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileSharingAccepted (%d, nick:'%s'): ACCEPTED USER PREF PROFILE message: REMOVED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
      database.setProfileSharing(recipientSharingWith, false);
      database.clearProfileKey(recipientSharingWith.getId());
    } else  {
      Log.e(TAG, String.format(Locale.getDefault(), "processUserProfileSharingAccepted (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF PROFILE message: UKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));
      return -1L;
    }

    if (userCommand.getHeader().hasEid()) {
      updateEidIfNecessary(Recipient.self(), userCommand.getHeader().getEid());
    }
    if (userCommand.getTargetList(0).hasEid()) {
      updateEidIfNecessary(recipientSharingWith, userCommand.getTargetList(0).getEid());
    }

    insertProfileLogWhereUserIsMember(context, userCommand, recipientSharingWith);
    ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventUserPrefProfileKey(userCommand.getPrefs(0), recipientSharingWith, CommandArgs.values()[userCommand.getHeader().getArgs()], userCommand.getHeader().getArgsClient()));

    return -1L;
  }

  /**
   * A remote user has shared their profile with this user.
   */
  private static long
  processUserProfileSharingAdded(@NonNull Context context, @NonNull UserCommand userCommand)
  {
    RecipientDatabase database    = SignalDatabase.recipients();
    Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
    if (!recipientOriginator.isUndefined()) {
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileSharingAdded (%d, uid_orig:'%d'): was added to orig users profile sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      try {
        database.setProfileKey(recipientOriginator.getId(), new ProfileKey(userCommand.getProfileKey().toByteArray()));
      } catch (InvalidInputException x) {
        Log.d(TAG, x);
        return -1L;
      }
      database.setProfileShared(recipientOriginator, true);

      ApplicationDependencies.getJobManager().add(new UfsrvProfileRetrieverJob(UfsrvUid.fromEncoded(recipientOriginator.getUfsrvUid())));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(Recipient.self(), userCommand.getHeader().getEid());
      if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());

      insertProfileLogWhereUserIsMember(context, userCommand, Recipient.self());
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileSharingAdded (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
    }

    return -1L;
  }

  /**
   * A remote user has removed their profile sharing with this user.
   */
  private static long
  processUserProfileShareDeleted(@NonNull Context context, @NonNull UserCommand userCommand)
  {
    RecipientDatabase database    = SignalDatabase.recipients();
    Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);

    if (!recipientOriginator.isUndefined()) {
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileShareDeleted (%d, uid_orig:'%d'): Remote user removed their profile sharing with you ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      AvatarHelper.delete(context,recipientOriginator.getId());
      database.clearProfileKey(recipientOriginator.getId());
      database.setProfileShared(recipientOriginator, false);
      database.unsetUfsrvProfile(recipientOriginator.getId());

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(Recipient.self(), userCommand.getHeader().getEid());
      if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());

      insertProfileLogWhereUserIsMember(context, userCommand, Recipient.self());
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "processUserProfileShareDeleted (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
    }

    return -1L;
  }

  private static @Nullable Long
  handleUserCommandUserPrefsPresenceSharing(@NonNull Context context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceDataMessage message,
                                            @NonNull Optional<Long> smsMessageId,
                                            boolean outgoing)
  {
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand       userCommand = message.getUfsrvCommand().getUserCommand();

    //server accepted request to share/unshare our presence with a given user
    if (isCommandAccepted(userCommand)) {
      //server accepted a previous request to add/remove a user to this client's sharelist. Doesn't mean the other user accepted that. (see ADDED below for that)
      //this user is allowing his profile to be shared with the other user (profile_sharing)
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (isCommandClientAdded(userCommand)) {
        database.setPresenceSharing(recipientSharingWith, true);
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s'): ACCEPTED USER PREF PRESENCE message: ADDED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
      } else if (isCommandClientDeleted(userCommand)){
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s'): ACCEPTED USER PREF PRESENCE message: REMOVED ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName()));
        database.setPresenceSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF PRESENCE message: UKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLogWhereUserIsMember(context, userCommand, recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF PRESENCE ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      return processUserCommandRejected(context, envelope, message, userCommand);
    } else if (isCommandAdded(userCommand)) {//the originator is allowing/shared their presence with this client
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): was added to orig user's presence sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        database.setPresenceShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {//the originator is disallowing/unshared their presence with this user
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): was removed orig user's presence sharelist ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
        database.setPresenceShared(recipientOriginator, false);

        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(Recipient.self(), userCommand.getHeader().getEid());
        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsPresenceSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
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
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand       userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (isCommandClientAdded(userCommand)) {
        database.setReadReceiptSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        database.setReadReceiptSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsReadReceiptSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLogWhereUserIsMember(context, userCommand, recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsReadReceiptSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      return processUserCommandRejected(context, envelope, message, userCommand);
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        database.setReadReceiptShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
//        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserReadReceiptShared(recipientOriginator, true));//AA disabled in favour if in-group notification
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsReadReceiptSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        database.setReadReceiptShared(recipientOriginator, false);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
//        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserReadReceiptShared(recipientOriginator, false));//AA disabled in favour if in-group notification
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsReadReceiptSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }

  static IProfileSharingClientOps provideClientOpsForBlockingSharing()
  {
    RecipientDatabase database = SignalDatabase.recipients();

    return new ProfileSharingClientOps() {
      @Override
      public long onSharingAccepted(CommandArgs clientArg, Recipient recipientSharingWith) {
        if (clientArg ==  CommandArgs.ADDED) {
          database.setBlockSharing(recipientSharingWith, true);
        } else if (clientArg == CommandArgs.DELETED) {
          database.setBlocked(recipientSharingWith.getId(), false);
        } else {
          Log.e(TAG, String.format(Locale.getDefault(), "getClientOpsForBlockingSharing (%d, ufsrvid:'%d', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getUfsrvId(), clientArg.getNumber()));
        }
        return -1L;
      }

      @Override
      public long onSharingAdded(CommandArgs clientArg, Recipient recipientSharingWith) {
        database.setBlockShared(recipientSharingWith, true);
        return -1L;
      }


      @Override
      public long onSharingDeleted(CommandArgs clientArg, Recipient recipientSharingWith) {
        database.setBlockShared(recipientSharingWith, false);
        return -1L;
      }


      @Override
      public long onSharingRejected(CommandArgs clientArg, Recipient recipientSharingWith) {
        return -1L;
      }
    };

  }


  private static @Nullable Long
  handleUserCommandUserPrefsSharing(@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @NonNull IProfileSharingClientOps clientOps)
  {
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand       userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient recipientSharingWith    = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      clientOps.onSharingAccepted(userCommand.getHeader().getArgsClient(), recipientSharingWith);

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      if (clientOps.isLoggable()) insertProfileLogWhereUserIsMember(context, userCommand, recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      return processUserCommandRejected(context, envelope, message, userCommand);
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        clientOps.onSharingAdded(userCommand.getHeader().getArgsClient(), recipientOriginator);//database.setBlockShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        if (clientOps.isLoggable()) insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsSharing (arg:'%d', uid_orig:'%d'): could not fetch orig_uid", userCommand.getHeader().getArgs(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        clientOps.onSharingDeleted(userCommand.getHeader().getArgsClient(), recipientOriginator);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        if (clientOps.isLoggable()) insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsSharing (arg:'%d', uid_orig:'%d'): could not fetch orig_uid", userCommand.getHeader().getArgs(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return -1L;
  }

  private static @Nullable Long
  handleUserCommandUserPrefsBlockedSharing(@NonNull Context context,
                                           @NonNull SignalServiceEnvelope envelope,
                                           @NonNull SignalServiceDataMessage message,
                                           @NonNull Optional<Long> smsMessageId)
  {
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand       userCommand = message.getUfsrvCommand().getUserCommand();

    if (isCommandAccepted(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
        Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();
      if (isCommandClientAdded(userCommand)) {
        database.setBlockSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        database.setBlocked(recipientSharingWith.getId(), false);
      } else  Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLogWhereUserIsMember(context, userCommand, recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      return processUserCommandRejected(context, envelope, message, userCommand);
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        database.setBlockShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (!recipientOriginator.isUndefined()) {
        database.setBlockShared(recipientOriginator, false);

        insertProfileLogWhereUserIsMember(context, userCommand, recipientOriginator);
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return -1L;
  }

  /**
   * currently only processes for self.
   * @param context
   * @param envelope
   * @param message
   * @param smsMessageId
   * @return
   */
  private static @Nullable Long
  handleUserCommandUserPrefsBlockedFence(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @NonNull Optional<Long> smsMessageId)
  {
    RecipientDatabase database = SignalDatabase.recipients();
    UserCommand     userCommand = message.getUfsrvCommand().getUserCommand();

    FenceRecord fenceRecord = userCommand.getFencesBlocked(0);
    Long fidBlocked = fenceRecord.getFid();
    Recipient groupRecipientBlocked = Recipient.resolved(RecipientUfsrvId.from(fidBlocked));

    if (isCommandAccepted(userCommand)) { //self branch
      if (isCommandClientAdded(userCommand)) {
        finaliseBlockedFenceHandling(context, Recipient.self(), groupRecipientBlocked, fenceRecord);
      } else if (isCommandClientDeleted(userCommand)) {//this user removed a previously blocked fence
        database.updateBlockedFences(Recipient.self().getId(), new LinkedList<Long>() {{add(fidBlocked);}}, GroupDatabase.MembershipUpdateMode.REMOVE_MEMBER);
      } else  Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedSharing (%d, fid:'%d', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), fidBlocked, userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

      insertProfileLogForBlockedFence(context, userCommand, Recipient.self(), groupRecipientBlocked);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedFence (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      insertProfileLogForBlockedFence(context, userCommand, Recipient.self(), groupRecipientBlocked);
    } else if (isCommandAdded(userCommand)) { //another user added a blocked fence
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
//        SignalDatabase.recipients().setBlockShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLogForBlockedFence(context, userCommand, Recipient.self(), groupRecipientBlocked);
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedFence (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    } else if (isCommandDeleted(userCommand)) { //unsupported another user removed blocked fence
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsBlockedFence (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }

    return Long.valueOf(-1L);
  }


  /**
   * Currently, only handle the case for self removal.
   */
  private static void
  finaliseBlockedFenceHandling(@NonNull Context context, @NonNull Recipient recipientBlocking, @NonNull Recipient groupRecipientBlocked, @NonNull FenceRecord fenceRecord)
  {
    GroupDatabase.MembershipUpdateMode  updateMode    = REMOVE_MEMBER;
    GroupDatabase groupDatabase = SignalDatabase.groups();
    RecipientDatabase recipientDatabase = SignalDatabase.recipients();

    recipientDatabase.updateBlockedFences(recipientBlocking.getId(), new LinkedList<Long>() {{add(fenceRecord.getFid());}}, GroupDatabase.MembershipUpdateMode.ADD_MEMBER);
    List<Address> meRemoved = new LinkedList<Address>() {{add(Recipient.self().requireAddress());}};

    groupDatabase.updateInvitedMembers(fenceRecord.getFid(), meRemoved, updateMode);

    groupDatabase.markGroupMode(fenceRecord.getFid(), GROUP_MODE_BLOCKED);
    groupDatabase.setActive(groupRecipientBlocked.requireGroupId(), false);
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
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();
      if (isCommandClientAdded(userCommand)) {
        SignalDatabase.recipients().setContactSharing(recipientSharingWith, true);
      } else if (isCommandClientDeleted(userCommand)){
        SignalDatabase.recipients().setContactSharing(recipientSharingWith, false);
      } else  Log.e(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsContactSharing (%d, nick:'%s', args_client:'%d'): ACCEPTED USER PREF  message: UNKNOWN CLIENT ARG ", Thread.currentThread().getId(), recipientSharingWith.getDisplayName(), userCommand.getHeader().getArgsClient().getNumber()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());
      if (userRecordSharingWith.hasEid())  updateEidIfNecessary(recipientSharingWith, userRecordSharingWith.getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientSharingWith);
    }
    else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsContactSharing (%d, uid:'%d'): UFSRV REJECTED USER PREF  ", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getTargetList(0).getUfsrvuid().toByteArray())));
      //todo: only delete if currently set equals rejected
      //AvatarHelper.delete(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    } else if (isCommandAdded(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator!=null) {
        SignalDatabase.recipients().setContactShared(recipientOriginator, true);

        if (userCommand.getOriginator().hasEid()) updateEidIfNecessary(recipientOriginator, userCommand.getOriginator().getEid());
        if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsContactSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
      }
    }
    else if (isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      if (recipientOriginator != null) {
        SignalDatabase.recipients().setContactShared(recipientOriginator, false);

        insertProfileLog (context, userCommand, myOwnRecipient(false), recipientOriginator);
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsContactSharing (%d, uid_orig:'%d'): could not fetch orig_uid", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(userCommand.getOriginator().getUfsrvuid().toByteArray())));
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
      userPref = userCommand.getFencePrefs(0);
      Log.d(TAG, String.format(Locale.getDefault(), "processUserCommandFenceUserPrefs (%d, eid:'%d', args:'%d'): Received pref information: prefid:'%d'", Thread.currentThread().getId(), userCommand.getHeader().getEid(), userCommand.getHeader().getArgs(), userPref.getPrefId().getNumber()));
    } else {
      Log.e(TAG, String.format(Locale.getDefault(), "processUserCommandFenceUserPrefs (%d): Received no pref record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (userPref.getPrefId())
    {
      case STICKY_GEOGROUP:
//        return (handleUserCommandUserPrefsNick(context, masterSecret, envelope, message, smsMessageId, outgoing));

      case PROFILE_SHARING:
        return (handleUserCommandFenceUserPrefsProfileSharing(context, envelope, message, smsMessageId));

      default:
        Log.e(TAG, String.format(Locale.getDefault(), "processUserCommandFenceUserPrefs (%d, prefid:'%d', args:'%d'): Received UKNOWN PUSER PREF ", Thread.currentThread().getId(), userPref.getPrefId(), userCommand.getHeader().getArgs()));
    }

    return (long) -1;
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
    Recipient           recipientGroup  = Recipient.live(RecipientUfsrvId.from(fenceRecord.getFid())).get();

    if (isCommandAccepted(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandFenceUserPrefsProfileSharing (%d, args:'%d', fid:'%d'): Received SERVER ACCEPTED FENCE PREF PROFILE message ", Thread.currentThread().getId(), userCommand.getHeader().getCommand(), fenceRecord.getFid()));
      SignalDatabase.recipients().setProfileSharing(recipientGroup, clientArg.getNumber() == CommandArgs.SET_VALUE? true:false);
      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientGroup);

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventFenceUserPref(userCommand.getFencePrefs(0), CommandArgs.ACCEPTED, clientArg));
    } else if (isCommandRejected(userCommand)) {
      Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsProfile (%d, fid:'%d'): REJECTED USER FENCE PREF message ", Thread.currentThread().getId(), fenceRecord.getFid()));

      if (userCommand.getHeader().hasEid()) updateEidIfNecessary(myOwnRecipient(false), userCommand.getHeader().getEid());

      insertProfileLog (context, userCommand, myOwnRecipient(false), recipientGroup);

      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventFenceUserPref(userCommand.getFencePrefs(0), CommandArgs.REJECTED, clientArg));
      //nothing for now
    } else {
        Log.d(TAG, String.format(Locale.getDefault(), "handleUserCommandUserPrefsProfile (%d, arg:'%d', fid:'%d'): unknown arg", Thread.currentThread().getId(), userCommand.getHeader().getArgs(), fenceRecord.getFid()));
    }

    return Long.valueOf(-1L);
  }

  private static
  Long processUserCommandEndSession(@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @Nullable GroupDatabase.GroupRecord groupRecordUfsrv,
                                     @NonNull Optional<Long> smsMessageId,
                                     boolean outgoing)

  {
    MessageDatabase smsDatabase               = SignalDatabase.sms();
    UserCommand userCommand               = envelope.getUserCommand();
    LiveRecipient liveRecipientOriginator = Recipient.live(UfsrvCommandUtils.getOriginatorUfsrvUserId(envelope.getUfsrvCommand()).toString());
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(liveRecipientOriginator.getId(),
                                                                      envelope.getSourceDevice(),
                                                                      message.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getTimestamp(),
                                                                      "", Optional.empty(), 0, false, envelope.getServerGuid(),
                                                                      envelope.getUfsrvCommand());

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<MessageDatabase.InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionStore sessionStore = new TextSecureSessionStore(SignalStore.account().getAci());
      Recipient recipientOriginator = recipientFromUserCommandOriginator(userCommand, false);
      sessionStore.deleteAllSessions(recipientOriginator.getUfsrvUid());//)envelope.getSource());//AA+

      SecurityEvent.broadcastSecurityUpdateEvent(context);
     ApplicationDependencies.getMessageNotifier().updateNotification(context, threadId);
    }

    return (long) -1;
  }

  static void insertProfileLogForBlockedFence(Context context, UserCommand userCommand, Recipient thisUser, Recipient groupRecipient)
  {
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand);
    SignalDatabase.sms().insertProfileLog(groupRecipient.getUfsrvId(), thisUser.getId(), userCommand.getHeader().getWhen(), ufsrvCommand.buildToSerialise(), true);
  }

  static void insertProfileLog(Context context, UserCommand userCommand, Recipient thisUser, Recipient otherUser)
  {
    if (!otherUser.isGroup()) {
      List<Long> fids = SignalDatabase.groups().getPairedGroups(thisUser, otherUser);
      if (fids.size() > 0) {
        UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand);
        for (Long fid : fids) {
          SignalDatabase.sms().insertProfileLog(fid.longValue(), thisUser.getId(), userCommand.getHeader().getWhen(), ufsrvCommand.buildToSerialise(), true);
        }
      }
    } else {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand);
      SignalDatabase.sms().insertGroupProfileLog(otherUser.getUfsrvId(), thisUser.getId(), userCommand.getHeader().getWhen(), ufsrvCommand.buildToSerialise(), true);
    }
  }

  static void insertProfileLogWhereUserIsMember(Context context, UserCommand userCommand, Recipient recipient)
  {
    MessageDatabase smsDatabase   = SignalDatabase.sms();
    GroupDatabase   groupDatabase = SignalDatabase.groups();
    UfsrvCommand    ufsrvCommand  = new UfsrvCommand(userCommand);

    try (GroupDatabase.Reader reader = groupDatabase.getActiveGroups()) {
      GroupDatabase.GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembersRecipientId().contains(recipient.getId()) && !groupRecord.isMms()) {
          RecipientId groupRecipientId    = SignalDatabase.recipients().getOrInsertFromGroupId(groupRecord.getId());
          Recipient           groupRecipient = Recipient.resolved(groupRecipientId);
          long                threadId       = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
          OutgoingTextMessage outgoing ;

          smsDatabase.insertProfileLog(groupRecord.getFid(), Recipient.self().getId(), userCommand.getHeader().getWhen(), ufsrvCommand.buildToSerialise(), true);
          SignalDatabase.threads().update(threadId, true);
        }
      }
    }
  }

  /*
  public static void insertProfileLogWhereUserIsMember(Context context, UserCommand userCommand, Recipient recipient, boolean verified, boolean remote)
  {
    long            time          = System.currentTimeMillis();
    MessageDatabase smsDatabase   = SignalDatabase.sms();
    GroupDatabase   groupDatabase = SignalDatabase.groups();

    try (GroupDatabase.Reader reader = groupDatabase.getActiveGroups()) {

      GroupDatabase.GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembersRecipientId().contains(recipient.getId()) && !groupRecord.isMms()) {

          if (remote) {
            IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.of(groupRecord.getId()), 0, false, null, null);

            if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
            else          incoming = new IncomingIdentityDefaultMessage(incoming);

            smsDatabase.insertMessageInbox(incoming);
          } else {
            RecipientId         recipientId    = SignalDatabase.recipients().getOrInsertFromGroupId(groupRecord.getId());
            Recipient           groupRecipient = Recipient.resolved(recipientId);
            long                threadId       = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
            OutgoingTextMessage outgoing ;

            if (verified) outgoing = new OutgoingIdentityVerifiedMessage(recipient);
            else          outgoing = new OutgoingIdentityDefaultMessage(recipient);

            SignalDatabase.sms().insertMessageOutbox(threadId, outgoing, false, time, null);
            SignalDatabase.threads().update(threadId, true);
          }
        }
      }
    }

    if (remote) {
      IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.empty(), 0, false, null, null);

      if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
      else          incoming = new IncomingIdentityDefaultMessage(incoming);

      smsDatabase.insertMessageInbox(incoming);
    } else {
      OutgoingTextMessage outgoing;

      if (verified) outgoing = new OutgoingIdentityVerifiedMessage(recipient);
      else          outgoing = new OutgoingIdentityDefaultMessage(recipient);

      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(recipient);

      Log.i(TAG, "Inserting verified outbox...");
      SignalDatabase.sms().insertMessageOutbox(threadId, outgoing, false, time, null);
      SignalDatabase.threads().update(threadId, true);
    }
  }
   */
  static public void UfsrvShareProfileWithRecipient(Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.PROFILE);
    profileOperationDescriptor.setOperationMode(isShare ? IProfileOperationDescriptor.OperationMode.SET : IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(recipient.isGroup() ? IProfileOperationDescriptor.ProfileOperationScope.GROUP : IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.requireAddress()));
  }

  static public void UfsrvSharePresence(Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.PRESENCE);
    profileOperationDescriptor.setOperationMode(isShare ? IProfileOperationDescriptor.OperationMode.SET : IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.requireAddress()));

  }

  static public void UfsrvShareReadReceipt(Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.READ_RECEIPT);
    profileOperationDescriptor.setOperationMode(isShare ? IProfileOperationDescriptor.OperationMode.SET : IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.requireAddress()));

  }

  static public void UfsrvShareBlocking(Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.BLOCKING);
    profileOperationDescriptor.setOperationMode(isShare ?
                                                IProfileOperationDescriptor.OperationMode.SET :
                                                IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.requireAddress()));

  }

  static public void UfsrvShareContact(Context context, Recipient recipient, boolean isShare)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.CONTACTS);
    profileOperationDescriptor.setOperationMode(isShare ? IProfileOperationDescriptor.OperationMode.SET : IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), recipient.requireAddress()));

  }

  /**
   * Main interface for sending boolean user preference commands
   */
  static public void UfsrvSetSettableUserPreference(Context context, Recipient recipient, UserPrefs prefId, boolean isSet)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.SETTABLE);
    profileOperationDescriptor.setOperationMode(isSet ? IProfileOperationDescriptor.OperationMode.SET : IProfileOperationDescriptor.OperationMode.UNSET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor);

    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), Integer.toString(prefId.getNumber())));
  }

  /**
   * Generic single integer value pref setter
   */
  public static void
  sendUserProfileIntegerValue(int prefValue, UserPrefs prefId, UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType profileType)
  {
    Log.d(TAG, String.format(Locale.getDefault(), "sendUserProfileIntegerValue: Updating profile id '%d'", prefId.getNumber()));

    UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(profileType);
    profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), String.valueOf(prefValue)));

  }

  public static void
  sendUserProfileHomebaseGeoLocation(String prefValue, UserPrefs prefId, UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType profileType)
  {
    Log.d(TAG, String.format(Locale.getDefault(), "sendUserProfileHomebaseGeoLocation: Updating profile id '%d'", prefId.getNumber()));

    UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(profileType);
    profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), prefValue));

  }

  static public void UfsrvSetMultivalueUserPreference(Context context, Recipient recipient, UserPrefs prefId, Integer value)
  {
    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(IProfileOperationDescriptor.ProfileType.SETTABLE_MULTI);
    profileOperationDescriptor.setOperationMode(IProfileOperationDescriptor.OperationMode.SET);
    profileOperationDescriptor.setOperationScope(IProfileOperationDescriptor.ProfileOperationScope.USER);
    ProfileCommandDescriptor profileCommandDescriptor = new ProfileCommandDescriptor(profileOperationDescriptor, value);

    ApplicationDependencies.getJobManager().add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), Integer.toString(prefId.getNumber())));
  }

  static public void updateEidIfNecessary(Recipient recipient, long eid)
  {
    if (true) {
     SignalDatabase.recipients().setEid(recipient, eid);
    }
  }

  static public Address myOwnAddress()
  {
    return Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance()));
  }

  static public String myOwnUfsrvUid()
  {
    return TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance());
  }

  static public Recipient myOwnRecipient(boolean async)
  {
    return Recipient.self();
  }

  static public Recipient recipientFromUserCommandOriginator(UserCommand userCommand, boolean async)
  {
    return Recipient.live(new UfsrvUid(userCommand.getOriginator().getUfsrvuid().toByteArray()).toString()).get();
  }

  static public boolean isCommandAccepted(UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber());
  }

  static public boolean isCommandRejected(UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber());
  }

  static public boolean isCommandUpdated(UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs() == CommandArgs.UPDATED.getNumber());
  }

  static public boolean isCommandAdded(UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs() == CommandArgs.ADDED.getNumber());
  }

  static public boolean isCommandDeleted(UserCommand userCommand)
  {
    return (userCommand.getHeader().getArgs() == CommandArgs.DELETED.getNumber());
  }

  static public boolean isCommandClientAdded(UserCommand userCommand)
  {
    return userCommand.getHeader().getArgsClient() == CommandArgs.ADDED;
  }

  static public boolean isCommandClientDeleted(UserCommand userCommand)
  {
    return userCommand.getHeader().getArgsClient() == CommandArgs.DELETED;
  }

  static public boolean isCommandClientEmpty(UserCommand userCommand)
  {
    return userCommand.getHeader().getArgsClient() == CommandArgs.UNDEFINED;
  }

  //AA specialised local client actions per share type
   interface IProfileSharingClientOps {
    long onSharingAccepted(SignalServiceProtos.CommandArgs clientArg, Recipient recipientSharingWith);
    long onSharingRejected(SignalServiceProtos.CommandArgs clientArg, Recipient recipientSharingWith);
    long onSharingAdded(SignalServiceProtos.CommandArgs clientArg, Recipient recipientSharingWith);
    long onSharingDeleted(SignalServiceProtos.CommandArgs clientArg, Recipient recipientSharingWith);
    boolean isLoggable();
  }

 /* public interface IProfileSharingClientOpsLoggable extends IProfileSharingClientOps {
   default boolean isLoggable() {
      return true;
    }
  }
  public interface ProfileSharingClientNonLoggable extends IProfileSharingClientOps {
    default boolean isLoggable() {
      return false;
    }
  }*/

  public abstract static class ProfileSharingClientOps implements IProfileSharingClientOps {
    @Override
    public boolean isLoggable() {
      return true;
    }
  }

  public abstract class ProfileSharingClientOpsNonLoggable implements IProfileSharingClientOps {
    @Override
    public boolean isLoggable() {
      return true;
    }
  }
}
