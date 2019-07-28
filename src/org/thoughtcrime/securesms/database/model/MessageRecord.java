/**
 * Copyright (C) 2012 Moxie Marlinpsike
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database.model;

import com.unfacd.android.R;

import android.content.Context;
import androidx.annotation.NonNull;
import android.text.SpannableString;
import android.text.TextUtils;

import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.util.List;
import java.util.Locale;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */

public abstract class MessageRecord extends DisplayRecord {

  private final Recipient                 individualRecipient;
  private final int                       recipientDeviceId;
  private final long                      id;
  private final List<IdentityKeyMismatch> mismatches;
  private final List<NetworkFailure>      networkFailures;
  private final int                       subscriptionId;
  private final long                      expiresIn;
  private final long                      expireStarted;
  private final boolean                   unidentified;

  MessageRecord(long id, String body, Recipient conversationRecipient,
                Recipient individualRecipient, int recipientDeviceId,
                long dateSent, long dateReceived, long threadId,
                int deliveryStatus, int deliveryReceiptCount, long type,
                List<IdentityKeyMismatch> mismatches,
                List<NetworkFailure> networkFailures,
                int subscriptionId, long expiresIn, long expireStarted,
                int readReceiptCount, boolean unidentified,
                SignalServiceProtos.UfsrvCommandWire ufsrvCommand)//
  {
    super(body, conversationRecipient, dateSent, dateReceived,
          threadId, deliveryStatus, deliveryReceiptCount, type, readReceiptCount,
          ufsrvCommand);// ufsrvCommand
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.recipientDeviceId   = recipientDeviceId;
    this.mismatches          = mismatches;
    this.networkFailures     = networkFailures;
    this.subscriptionId      = subscriptionId;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.unidentified        = unidentified;
  }

  public abstract boolean isMms();
  public abstract boolean isMmsNotification();

  public boolean isSecure() {
    return MmsSmsColumns.Types.isSecureType(type);
  }

  public boolean isLegacyMessage() {
    return MmsSmsColumns.Types.isLegacyType(type);
  }

  @Override
  public SpannableString getDisplayBody(@NonNull Context context) {
    if (isGroupUpdate() && isOutgoing()) {
      return describeOutgoingUpdate(context, getUfsrvCommand());
//
//      return new SpannableString(context.getString(R.string.MessageRecord_updated_group));
    } else if (isGroupUpdate()) {
      return describeIncomingUpdate (context, getUfsrvCommand());
      //-
      //return new SpannableString(GroupUtil.getDescription(context, getBody()).toString());
    }  else if (isProfileLog()) {
      return describeProfileLog(context, getUfsrvCommand());
    } else if (isGroupProfileLog()) {
      return describeGroupProfileLog(context, getUfsrvCommand());
    } else if (isGroupQuit() && isOutgoing()) {
      return new SpannableString(context.getString(R.string.requesting_to_leave_group));
    } else if (isGroupQuit()) {
      //return new SpannableString(context.getString(R.string.ConversationItem_group_action_left, getIndividualRecipient().toShortString()));
      return describeIncomingLeave(context, getUfsrvCommand());
    } else if (isIncomingCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_s_called_you, getIndividualRecipient().getDisplayName()/*getIndividualRecipient().toShortString()*/));
    } else if (isOutgoingCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_called_s, getIndividualRecipient().getDisplayName()/*getIndividualRecipient().toShortString()*/));
    } else if (isMissedCall()) {
      return new SpannableString(context.getString(R.string.MessageRecord_missed_call_from, getIndividualRecipient().getDisplayName()/*getIndividualRecipient().toShortString()*/));
    } else if (isJoined()) {
      return new SpannableString(context.getString(R.string.MessageRecord_s_is_on_unfacd, getIndividualRecipient().toShortString()));
    } else if (isExpirationTimerUpdate()) {
      return describeExpiryUpdate(context, getUfsrvCommand());
//      String sender = isOutgoing() ? context.getString(R.string.MessageRecord_you) : getIndividualRecipient().getDisplayName();//getIndividualRecipient().toShortString();//-
//      String time   = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
//      return new SpannableString(context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, sender, time));
    } else if (isIdentityUpdate()) {
      return new SpannableString(context.getString(R.string.MessageRecord_your_safety_number_with_s_has_changed, getIndividualRecipient().toShortString()));
    } else if (isIdentityVerified()) {
      if (isOutgoing()) return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified, getIndividualRecipient().toShortString()));
      else              return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified_from_another_device, getIndividualRecipient().toShortString()));
    } else if (isIdentityDefault()) {
      if (isOutgoing()) return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified, getIndividualRecipient().toShortString()));
      else              return new SpannableString(context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified_from_another_device, getIndividualRecipient().toShortString()));
    }

    return new SpannableString(getBody());
  }

  public long getId() {
    return id;
  }

  public boolean isPush() {
    return SmsDatabase.Types.isPushType(type) && !SmsDatabase.Types.isForcedSms(type);
  }

  public long getTimestamp() {
    if (isPush() && getDateSent() < getDateReceived()) {
      return getDateSent();
    }
    return getDateReceived();
  }

  public boolean isForcedSms() {
    return SmsDatabase.Types.isForcedSms(type);
  }

  public boolean isIdentityVerified() {
    return SmsDatabase.Types.isIdentityVerified(type);
  }

  public boolean isIdentityDefault() {
    return SmsDatabase.Types.isIdentityDefault(type);
  }

  public boolean isIdentityMismatchFailure() {
    return mismatches != null && !mismatches.isEmpty();
  }

  public boolean isBundleKeyExchange() {
    return SmsDatabase.Types.isBundleKeyExchange(type);
  }

  public boolean isContentBundleKeyExchange() {
    return SmsDatabase.Types.isContentBundleKeyExchange(type);
  }

  public boolean isIdentityUpdate() {
    return SmsDatabase.Types.isIdentityUpdate(type);
  }

  public boolean isCorruptedKeyExchange() {
    return SmsDatabase.Types.isCorruptedKeyExchange(type);
  }

  public boolean isInvalidVersionKeyExchange() {
    return SmsDatabase.Types.isInvalidVersionKeyExchange(type);
  }

  public boolean isUpdate() {
    return isGroupAction() || isJoined() || isExpirationTimerUpdate() || isCallLog() ||
            isEndSession()  || isIdentityUpdate() || isIdentityVerified() || isIdentityDefault() ||
            isProfileLog() || isGroupProfileLog();// isProfileLog()
  }

  public boolean isMediaPending() {
    return false;
  }

  public Recipient getIndividualRecipient() {
    return individualRecipient;
  }

  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  public long getType() {
    return type;
  }

  public List<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return mismatches;
  }

  public List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public boolean hasNetworkFailures() {
    return networkFailures != null && !networkFailures.isEmpty();
  }

  // might reinstate to keep italic formatting https://github.com/signalapp/Signal-Android/blob/24b062d8dde3d2bbcebc110400c95c9ffb649dc5/src/org/thoughtcrime/securesms/database/model/MessageRecord.java
//  protected SpannableString new SpannableString(String sequence) {
//    SpannableString spannable = new SpannableString(sequence);
//    spannable.setSpan(new RelativeSizeSpan(0.9f), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//
//    return spannable;
//  }

  public boolean equals(Object other) {
    return other != null                              &&
            other instanceof MessageRecord             &&
            ((MessageRecord) other).getId() == getId() &&
            ((MessageRecord) other).isMms() == isMms();
  }

  public int hashCode() {
    return (int)getId();
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public long getExpireStarted() {
    return expireStarted;
  }

  public boolean isUnidentified() {
    return unidentified;
  }

  public SpannableString describeOutgoingUpdate (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand=ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        if (fenceCommand.getFences(0).getFid()==0)
          return new SpannableString(context.getString(R.string.waiting_for_group_confirmation));
        else
          return describeOutgoingUpdateJoin(context, ufsrvCommand);

      case FenceCommand.CommandTypes.FNAME_VALUE:
        return new SpannableString(context.getString(R.string.requesting_group_name));

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return new SpannableString(context.getString(R.string.updatings_group_avatar));

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return new SpannableString(context.getString(R.string.sending_grou_invitation));

      case FenceCommand.CommandTypes.STATE_VALUE:
        return new SpannableString(context.getString(R.string.requesting_group_update));

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return new SpannableString(context.getString(R.string.requesting_groups_permissions_update));

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return new SpannableString(context.getString(R.string.updating_group_maxmembers_thread));

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        return new SpannableString(context.getString(R.string.updating_group_delivery_mode_thread));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  //
  public SpannableString describeProfileLog (Context context, UfsrvCommandWire ufsrvCommand)
  {
    SignalServiceProtos.UserCommand userCommand     = ufsrvCommand.getUserCommand();
    SignalServiceProtos.UserPreference userPref    = userCommand.getPrefs(0);

    // pref changes to other users
    Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);

    if (recipient != null) {
      switch (userPref.getPrefId())
      {
        case NICKNAME:
//            return emphasisAdded(context.getString(R.string.nickname_updated_for, recipient.getUsername()));
          return handleUserProfileLogExtended (context, userCommand, "Nickname");
        case USERAVATAR:
//            return emphasisAdded(context.getString(R.string.avatar_updated_for, recipient.getDisplayName()));
          return handleUserProfileLogExtended (context, userCommand, "Avatar");
        default:
          return handleProfileLogExtended(context, userCommand);
      }
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown User Profile type (%d)", userPref.getPrefId()));

  }

  public SpannableString handleUserProfileLogExtended (Context context, SignalServiceProtos.UserCommand userCommand, String profileDescriptorName)
  {
    int commandArg = userCommand.getHeader().getArgs();
    Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);

    if (commandArg==CommandArgs.UPDATED_VALUE) {
      return new SpannableString(context.getString(R.string.x_updated_for, profileDescriptorName, recipient.getDisplayName()));
    } else if (commandArg==CommandArgs.DELETED_VALUE) {
        return new SpannableString(context.getString(R.string.x_deleted_their, recipient.getDisplayName(), profileDescriptorName));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Uknown User Profile update (%d)", commandArg));
  }

  public SpannableString handleProfileLogExtended (Context context, SignalServiceProtos.UserCommand userCommand)
  {
    SignalServiceProtos.UserPreference userPref  = userCommand.getPrefs(0);
    int commandArgs = userCommand.getHeader().getArgs();

    switch (userPref.getPrefId()) {
      case PROFILE:
        return describeProfileLogExtended (context, userCommand, "Profile");
      case NETSTATE:
        return describeProfileLogExtended (context, userCommand, "Presence");
      case READ_RECEIPT:
        return describeProfileLogExtended (context, userCommand, "Read Receipt");
      case BLOCKING:
        return describeProfileLogBlock (context, userCommand);
      case CONTACTS:
        return describeProfileLogContact (context, userCommand);
      case ACTIVITY_STATE:
        return describeProfileLogExtended (context, userCommand, "Typing Indicator");
      default:
        return new SpannableString("Unknown UserPreference");
    }

  }

  SpannableString describeProfileLogExtended (Context context, SignalServiceProtos.UserCommand userCommand, String profileDescriptorName)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      //server accepted a previous request to add/remove a user to this client's sharelist. Doesn't mean the other user accepted that. (see ADDED below for that)
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.you_are_now_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.you_are_no_longer_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);
      return new SpannableString(context.getString(R.string.unfacd_rejected_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()));
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      //the originator is allowing/shared their presence with this client
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.x_is_now_sharing_their_with_you, recipientOriginator.getDisplayName(), profileDescriptorName));
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.x_is_no_longer_sharing_their_with_you, recipientOriginator.getDisplayName(), profileDescriptorName));
    }

    return new SpannableString(String.format("Unknown UserPreference: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()));
  }

  SpannableString describeProfileLogBlock (Context context, SignalServiceProtos.UserCommand userCommand)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.x_is_now_on_your_blocked_list, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.x_was_removed_from_your_blocked_list, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);
      return new SpannableString(context.getString(R.string.unfacd_rejected_your_block_request));
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.x_was_added_to_their__blocked_list, recipientOriginator.getDisplayName()));
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.x_was_removed_from_their__blocked_list, recipientOriginator.getDisplayName()));
    }

    return new SpannableString(String.format("Unknown block setting: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()));
  }

  SpannableString describeProfileLogContact (Context context, SignalServiceProtos.UserCommand userCommand)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.x_is_now_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.x_is_no_longer_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);
      return new SpannableString(context.getString(R.string.unfacd_rejected_your_contact_request));
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.x_is_now_allowing_u_to_share_their_contact, recipientOriginator.getDisplayName()));
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      return new SpannableString(context.getString(R.string.you_are_no_longer_allowed_to_share_x_contact, recipientOriginator.getDisplayName()));
    }

    return new SpannableString(String.format("Unknown block setting: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()));
  }

  //
  public SpannableString describeGroupProfileLog (Context context, UfsrvCommandWire ufsrvCommand)
  {
    SignalServiceProtos.UserCommand userCommand=ufsrvCommand.getUserCommand();
    SignalServiceProtos.FenceUserPreference userPref  = userCommand.getFencePrefs(0);
    int commandArgs = userCommand.getHeader().getArgs();

    if (UfsrvUserUtils.isCommandUpdated(userCommand)) {
      Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      if (recipient != null) {
        switch (userPref.getPrefId())
        {
          case PROFILE_SHARING:
            return new SpannableString(context.getString(R.string.profile_sharing_with_group_update));

          case STICKY_GEOGROUP:
            return new SpannableString(context.getString(R.string.avatar_updated_for, recipient.getDisplayName()));

          default:
            break;
        }
      }
    }

    return new SpannableString("Unknown Fence UserPreference");
  }

  //
  public SpannableString describeExpiryUpdate (Context context, UfsrvCommandWire ufsrvCommand)
  {
    if (ufsrvCommand!=null) {
      if (isOutgoing()) {
        int seconds = (int)(getExpiresIn() / 1000);
        if (seconds <= 0) {
          return  new SpannableString(context.getString(R.string.MessageRecord_you_disabled_disappearing_messages));
          //todo: do the same for incoming (for readability new SpannableString(context.getString(R.string.MessageRecord_s_disabled_disappearing_messages, getIndividualRecipient().toShortString()));
        }

        String time = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
        return new SpannableString(context.getString(R.string.requesting_message_expiry_setting, time));
      } else {
          FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
          int commandArgs = fenceCommand.getHeader().getArgs();
          String sourceUser = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
          boolean isMe = sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));
          Recipient recipient = Recipient.from(context, Address.fromSerialized(sourceUser), true);
          StringBuilder description = new StringBuilder();

          switch (commandArgs) {
            case CommandArgs.ACCEPTED_VALUE:
            case CommandArgs.UPDATED_VALUE:
              if (isMe) sourceUser = context.getString(R.string.MessageRecord_you) + " ";
              else      sourceUser = String.format("%s ", recipient.getDisplayName());
              description.append(sourceUser);

              String time = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
              int seconds = (int)(getExpiresIn() / 1000);
              if (seconds <= 0) {
                if (isMe)   return  new SpannableString(context.getString(R.string.MessageRecord_you_disabled_disappearing_messages));
                else        new SpannableString(context.getString(R.string.MessageRecord_s_disabled_disappearing_messages, sourceUser));
              } else {
                return new SpannableString(context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, sourceUser, time));
              }

            default:
              if (fenceCommand.getHeader().hasArgsError()) {
                return new SpannableString(context.getString(R.string.your_request_for_expiry_update_didnt_complete));
              }
              else {
                return new SpannableString(String.format(Locale.getDefault(), "Unknown expiry update event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
              }
          }
      }
    }
    else {//this branch is dead
      String sender = isOutgoing() ? context.getString(R.string.MessageRecord_you) : getIndividualRecipient().getDisplayName();//getIndividualRecipient().toShortString();//-
      String time   = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
      return new SpannableString(context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, sender, time));
    }
  }
  //

  public SpannableString describeOutgoingUpdateJoin (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand=ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.INVITED_VALUE:
        return new SpannableString(context.getString(R.string.accepting_invitation));

      case CommandArgs.DENIED_VALUE:
        return new SpannableString(context.getString(R.string.declining_request));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group join event(update) (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdate (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = TextUtils.isEmpty(sourceUser) || sourceUser.equals(UfsrvUid.UndefinedUfsrvUid);
    StringBuilder description = new StringBuilder();

    if (isMe) sourceUser = context.getString(R.string.MessageRecord_you)+" ";
    else      sourceUser = String.format("%s ", getIndividualRecipient().getDisplayName());

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        return describeIncomingUpdateJoin (context, ufsrvCommand);

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        description.append(context.getString(R.string.changed_avatar_name));
        return new SpannableString(description.toString());

        //todo: check for COMMAND_ARGS__ACCEPTED for self or COMMAND_ARGS__UPDATED for others
      case FenceCommand.CommandTypes.FNAME_VALUE:
        if (fenceCommand.getHeader().getArgsError()==FenceCommand.Errors.NONE_VALUE) {
          description.append(context.getString(R.string.changed_group_name));
        }
        else description.append(context.getString(R.string.there_was_error_changing_group_name));
        return new SpannableString(description.toString());

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return describeIncomingUpdateInvite (context, ufsrvCommand);

      case FenceCommand.CommandTypes.STATE_VALUE:
        return describeIncomingUpdateState (context, ufsrvCommand);

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return describeIncomingUpdatePermission (context, ufsrvCommand);

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return describeIncomingUpdateMaxMembers (context, ufsrvCommand);

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        return describeIncomingUpdateDeliveryMode (context, ufsrvCommand);

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming update event (%d, %d, error:%d)", fenceCommand.getHeader().getCommand(), commandArgs, fenceCommand.getHeader().getArgsError()));
  }

  public SpannableString describeIncomingUpdateState (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.SYNCED_VALUE:
        return new SpannableString(context.getString(R.string.group_info_updated));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group STATE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateJoin (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand=ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.CREATED_VALUE:
        return new SpannableString(context.getString(R.string.MessageRecord_uf_created_group));

      case CommandArgs.UNCHANGED_VALUE:
        return new SpannableString(context.getString(R.string.MessageRecord_uf_rejoined_group));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.you_have_joined_the_group));

      case CommandArgs.GEO_BASED_VALUE:
        return new SpannableString(context.getString(R.string.you_have_joined_a_geo_group));

      case CommandArgs.INVITED_VALUE:
      {
        String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        Recipient recipient =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
        return new SpannableString(context.getString(R.string.user_invited_you_to_join, recipient.getDisplayName()));
      }

      case SignalServiceProtos.CommandArgs.ACCEPTED_INVITE_VALUE:
        return new SpannableString(context.getString(R.string.you_have_accepted_invitation_to_join));
      
      case CommandArgs.INVITED_GEO_VALUE:
        return new SpannableString(context.getString(R.string.you_received_geogroup_invitation, fenceCommand.getFences(0).getFname()));

      //for this client the originator value is unset. This is distinct message from the other join confirmation type, as this carries full group configuration data for the newly joining user
      case CommandArgs.SYNCED_VALUE:
        String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);

        if (!TextUtils.isEmpty(sourceUser) && !sourceUser.equals(UfsrvUid.UndefinedUfsrvUid)) {
          Recipient recipient     =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
          return new SpannableString(context.getString(R.string.user_joined_the_group, recipient.getDisplayName()));
        } else  new SpannableString(context.getString(R.string.the_group_is_fully_loaded));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group join event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateInvite (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand=ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        if ( fenceCommand.getFences(0).getInvitedMembersCount()>0) {
          Recipient recipient =  Recipient.fromUfsrvUid(context, new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()), true);

          return new SpannableString(context.getString(R.string.x_has_been_invited_to_join_this_group, recipient.getDisplayName()));
        }
        break;
      case CommandArgs.UNCHANGED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount()>0) {
          Recipient recipient =  Recipient.fromUfsrvUid(context, new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()), true);
          return new SpannableString(context.getString(R.string.x_is_already_inviyted, recipient.getDisplayName()));
        }
        break;


    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdatePermission (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand             = ufsrvCommand.getFenceCommand();
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceCommand.getFences(0), ufsrvCommand.getFenceCommand().getType());
    UfsrvUid                    useridTarget  = new UfsrvUid(permission.getUsers(0).getUfsrvuid().toByteArray());
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = useridTarget.getUfsrvSequenceId()==TextSecurePreferences.getUserId(context);
    Recipient recipientSource     =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
    Recipient recipientTarget     =  Recipient.fromUfsrvUid(context, useridTarget, true);
    StringBuilder description       = new StringBuilder();



    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        if (isMe) return new SpannableString(context.getString(R.string.group_permissions_added_you, recipientSource.getDisplayName(), EnumFencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        else {
          return new SpannableString(context.getString(R.string.group_permissions_added_other, recipientSource.getDisplayName(), recipientTarget.getDisplayName(), EnumFencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        }

      case CommandArgs.DELETED_VALUE:
        if (isMe) return new SpannableString(context.getString(R.string.group_permissions_deleted_you, recipientSource.getDisplayName(), EnumFencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        else {
          return new SpannableString(context.getString(R.string.group_permissions_deleted_other, recipientSource.getDisplayName(), recipientTarget.getDisplayName(), EnumFencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        }

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.group_permissions_updated_thread));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.group_permissions_rejected));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateMaxMembers (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand             = ufsrvCommand.getFenceCommand();
    UfsrvUid                    useridTarget  = new UfsrvUid(fenceCommand.getOriginator().getUfsrvuid().toByteArray());
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = useridTarget.getUfsrvSequenceId()==TextSecurePreferences.getUserId(context);
    Recipient recipientSource     =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
    Recipient recipientTarget     =  Recipient.fromUfsrvUid(context, useridTarget, true);

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return new SpannableString(context.getString(R.string.maxmembers_updated_by, recipientSource.getDisplayName(), fenceCommand.getFences(0).getMaxmembers()));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.maxmembers_updated_by_you, recipientSource.getDisplayName(), fenceCommand.getFences(0).getMaxmembers()));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.maxmembers_request_rejected));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateDeliveryMode (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand            fenceCommand        = ufsrvCommand.getFenceCommand();
    int                     commandArgs         = fenceCommand.getHeader().getArgs();
    String                  sourceUser          = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient               recipientSource     =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
    GroupDatabase.DeliveryMode  deliveryMode    = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_updated_by, recipientSource.getDisplayName(), deliveryMode.getDescriptiveName()));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_updated_by_you, recipientSource.getDisplayName(), deliveryMode.getDescriptiveName()));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_request_rejected));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingLeave (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = TextUtils.isEmpty(sourceUser) || sourceUser.equals(UfsrvUid.UndefinedUfsrvUid) || sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));
    StringBuilder description = new StringBuilder();

    Recipient recipient;
    if (isMe) {
      sourceUser = context.getString(R.string.MessageRecord_you)+" ";
      recipient =  Recipient.from(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), true);
    } else {
      recipient =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
      sourceUser = String.format("%s ", recipient.getDisplayName());
    }
    description.append(sourceUser);

    switch (commandArgs)
    {
      case  CommandArgs.ACCEPTED_VALUE:
      case  CommandArgs.GEO_BASED_VALUE://we roll them into one for now

        return new SpannableString(description.append(context.getString(R.string.have_left_the_group_thread)).toString());

      case  CommandArgs.SYNCED_VALUE:
        return new SpannableString(description.append(context.getString(R.string.have_left_the_group_thread)).toString());

      case CommandArgs.UNINVITED_VALUE:
        //semantics slightly different for uninvite, as it could originate from server, not user
        String targetUser = UfsrvUid.EncodedfromSerialisedBytes(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray());
        isMe  = TextUtils.isEmpty(sourceUser) || targetUser.equals(TextSecurePreferences.getUfsrvUserId(context));

        if (isMe) return new SpannableString(description.append(context.getString(R.string.you_no_longer_on_invite_list)).toString());

        recipient =  Recipient.from(context, Address.fromSerialized(targetUser), true);
        return new SpannableString(context.getString(R.string.user_no_longer_on_invited_list, recipient.getDisplayName()));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming group leave event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }
}
