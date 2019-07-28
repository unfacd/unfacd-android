/**
 * Copyright (C) 2012 Moxie Marlinspike
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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase.Extra;
import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;


import java.util.Locale;

/**
 * The message record model which represents thread heading messages.
 *
 * @author Moxie Marlinspike
 *
 */

public class ThreadRecord extends DisplayRecord {

  private @Nullable final Uri     snippetUri;
  private @Nullable final String  contentType;
  private @Nullable final Extra   extra;
  private           final long    count;
  private           final int     unreadCount;
  private           final int     distributionType;
  private           final boolean archived;
  private           final long    expiresIn;
  private           final long    lastSeen;

  //
  private           final long    fid;
  private           final long    eid;
  //

  public ThreadRecord(@NonNull String body, @Nullable Uri snippetUri,
                      @Nullable String contentType, @Nullable Extra extra,
                      @NonNull Recipient recipient, long date, long count, int unreadCount,
                      long threadId, int deliveryReceiptCount, int status, long snippetType,
                      int distributionType, boolean archived, long expiresIn,  long lastSeen, int readReceiptCount,
                      long fid, long eid, SignalServiceProtos.UfsrvCommandWire ufsrvCommand)//
  {
    super(body, recipient, date, date, threadId, status, deliveryReceiptCount, snippetType, readReceiptCount,
          ufsrvCommand);//
    this.snippetUri       = snippetUri;
    this.contentType      = contentType;
    this.extra            = extra;
    this.count            = count;
    this.unreadCount      = unreadCount;
    this.distributionType = distributionType;
    this.archived         = archived;
    this.expiresIn        = expiresIn;
    this.lastSeen         = lastSeen;
    //
    this.fid=fid;
    this.eid=eid;
    //
  }

  public @Nullable Uri getSnippetUri() {
    return snippetUri;
  }

  //
  boolean isThreadInvitationToJoin ()
  {
    GroupDatabase groupDatabase= DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    SignalServiceProtos.FenceRecord fenceRecord=UfsrvFenceUtils.getTargetFence(getUfsrvCommand());
    if (fenceRecord!=null) {
      GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroupRecordByFid(UfsrvFenceUtils.getTargetFence(getUfsrvCommand()).getFid());
      if (groupRecord != null && groupRecord.getMode() == GroupDatabase.GROUP_MODE_INVITATION)
        return true;
    }

    return false;
  }
  //

  @Override
  public SpannableString getDisplayBody(@NonNull Context context) {
    //
    if (isGroupUpdate() && isOutgoing()) {//isThreadInvitationToJoin()) {
      return describeOutgoingUpdate(context, getUfsrvCommand());
    }  else if (isProfileLog()) {
      return describeProfileLog(context, getUfsrvCommand());
    } else if (isGroupProfileLog()) {
      return describeGroupProfileLog(context, getUfsrvCommand());
    } else if (isGroupUpdate()) {
      return describeIncomingUpdate (context, getUfsrvCommand());
    } else if (isGroupQuit() && isOutgoing()) {// secon conditional
      return emphasisAdded(context.getString(R.string.requesting_to_leave_group));//-
    } else if (isGroupQuit()) {
      return describeIncomingLeave(context, getUfsrvCommand());
    } else if (isKeyExchange()) {
      return emphasisAdded(context.getString(R.string.ConversationListItem_key_exchange_message));
    } else if (SmsDatabase.Types.isFailedDecryptType(type)) {
      return emphasisAdded(context.getString(R.string.MessageDisplayHelper_bad_encrypted_message));
    } else if (SmsDatabase.Types.isNoRemoteSessionType(type)) {
      return emphasisAdded(context.getString(R.string.MessageDisplayHelper_message_encrypted_for_non_existing_session));
    } else if (SmsDatabase.Types.isEndSessionType(type)) {
      return emphasisAdded(context.getString(R.string.ThreadRecord_secure_session_reset));
    } else if (MmsSmsColumns.Types.isLegacyType(type)) {
      return emphasisAdded(context.getString(R.string.MessageRecord_message_encrypted_with_a_legacy_protocol_version_that_is_no_longer_supported));
    } else if (MmsSmsColumns.Types.isDraftMessageType(type)) {
      String draftText = context.getString(R.string.ThreadRecord_draft);
      return emphasisAdded(draftText + " " + getBody(), 0, draftText.length());
    } else if (SmsDatabase.Types.isOutgoingCall(type)) {
      return emphasisAdded(context.getString(com.unfacd.android.R.string.ThreadRecord_called));
    } else if (SmsDatabase.Types.isIncomingCall(type)) {
      return emphasisAdded(context.getString(com.unfacd.android.R.string.ThreadRecord_called_you));
    } else if (SmsDatabase.Types.isMissedCall(type)) {
      return emphasisAdded(context.getString(com.unfacd.android.R.string.ThreadRecord_missed_call));
    } else if (SmsDatabase.Types.isJoinedType(type)) {
      return emphasisAdded(context.getString(R.string.ThreadRecord_s_is_on_unfacd, getRecipient().toShortString()));
    } else if (SmsDatabase.Types.isExpirationTimerUpdate(type)) {
      int seconds = (int)(getExpiresIn() / 1000);
      if (seconds <= 0) {
        return emphasisAdded(context.getString(R.string.ThreadRecord_disappearing_messages_disabled));
      }
      String time = ExpirationUtil.getExpirationDisplayValue(context, seconds);
      return emphasisAdded(context.getString(R.string.ThreadRecord_disappearing_message_time_updated_to_s, time));
    } else if (SmsDatabase.Types.isIdentityUpdate(type)) {
      if (getRecipient().isGroupRecipient()) return emphasisAdded(context.getString(R.string.ThreadRecord_safety_number_changed));
      else                                    return emphasisAdded(context.getString(R.string.ThreadRecord_your_safety_number_with_s_has_changed, getRecipient().toShortString()));
    } else if (SmsDatabase.Types.isIdentityVerified(type)) {
      return emphasisAdded(context.getString(R.string.ThreadRecord_you_marked_verified));
    } else if (SmsDatabase.Types.isIdentityDefault(type)) {
      return emphasisAdded(context.getString(R.string.ThreadRecord_you_marked_unverified));
    } else if (SmsDatabase.Types.isUnsupportedMessageType(type)) {
      return emphasisAdded(context.getString(R.string.ThreadRecord_message_could_not_be_processed));
    } else {
      if (TextUtils.isEmpty(getBody())) {
        if (extra != null && extra.isSticker()) {
          return new SpannableString(emphasisAdded(context.getString(R.string.ThreadRecord_sticker)));
        } else if (extra != null && extra.isRevealable() && MediaUtil.isImageType(contentType)) {
          return new SpannableString(emphasisAdded(context.getString(R.string.ThreadRecord_disappearing_photo)));
        } else {
          return new SpannableString(emphasisAdded(context.getString(R.string.ThreadRecord_media_message)));
        }
      } else {
        return new SpannableString(getBody());
      }
    }
  }

  private SpannableString emphasisAdded(String sequence) {
    return emphasisAdded(sequence, 0, sequence.length());
  }

  private SpannableString emphasisAdded(String sequence, int start, int end) {
    SpannableString spannable = new SpannableString(sequence);
    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
            start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    return spannable;
  }

  public long getCount() {
    return count;
  }

  public int getUnreadCount() {
    return unreadCount;
  }

  public long getDate() {
    return getDateReceived();
  }

  public boolean isArchived() {
    return archived;
  }

  public int getDistributionType() {
    return distributionType;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public long getLastSeen() {
    return lastSeen;
  }

  //
  public long getFid(){ return fid;}

  public SpannableString describeOutgoingUpdate (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        if (fenceCommand.getFences(0).getFid()==0)
          return emphasisAdded(context.getString(R.string.waiting_for_group_confirmation));
        else
          return describeOutgoingUpdateJoin(context, ufsrvCommand);

//      //currently this is bundled into JOIN with fid=0
//      case FenceCommand.CommandTypes.MAKE_VALUE:
//        return emphasisAdded(context.getString(R.string.updatings_group_avatar));

      case FenceCommand.CommandTypes.FNAME_VALUE:
        return emphasisAdded(context.getString(R.string.requesting_group_name));

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return emphasisAdded(context.getString(R.string.updatings_group_avatar));

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return emphasisAdded(context.getString(R.string.sending_grou_invitation));

      case FenceCommand.CommandTypes.STATE_VALUE:
        return emphasisAdded(context.getString(R.string.requesting_group_update));

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return emphasisAdded(context.getString(R.string.updating_group_permission_thread));

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return emphasisAdded(context.getString(R.string.updating_group_maxmembers_thread));

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown outgoing group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeProfileLog (Context context, UfsrvCommandWire ufsrvCommand)
  {
    UserCommand userCommand     = ufsrvCommand.getUserCommand();
    UserPreference  userPref    = userCommand.getPrefs(0);
    int             commandArgs = userCommand.getHeader().getArgs();

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

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown User Profile type (%d)", userPref.getPrefId()));
  }

  public SpannableString handleUserProfileLogExtended (Context context, SignalServiceProtos.UserCommand userCommand, String profileDescriptorName)
  {
    int commandArg = userCommand.getHeader().getArgs();
    Recipient recipient = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);

    if (commandArg==CommandArgs.UPDATED_VALUE) {
      return emphasisAdded(context.getString(R.string.x_updated_for, profileDescriptorName, recipient.getDisplayName()));
    } else if (commandArg==CommandArgs.DELETED_VALUE) {
      return emphasisAdded(context.getString(R.string.x_deleted_their, recipient.getDisplayName(), profileDescriptorName));
    }

    return emphasisAdded(String.format(Locale.getDefault(), "Uknown User Profile update (%d)", commandArg));
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

  public SpannableString describeOutgoingUpdateJoin (Context context, UfsrvCommandWire ufsrvCommand)
  {
   FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
   int          commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.INVITED_VALUE:
        return emphasisAdded(context.getString(R.string.accepting_invitation));

      case SignalServiceProtos.CommandArgs.DENIED_VALUE:
        return emphasisAdded(context.getString(R.string.declining_request));
    }

    if (fenceCommand.getHeader().getCommand()==0 && commandArgs==0) return emphasisAdded(""); //this happens when no messages in the thread
    else return emphasisAdded(String.format(Locale.getDefault(), "Unknown outgoing group join event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdate (Context context, SignalServiceProtos.UfsrvCommandWire ufsrvCommand)
  {
    if (ufsrvCommand==null) {
      Log.e("ThreadRecord", String.format("ThreadRecord: EMPTY UFSRV COMMAND"));
      return emphasisAdded("Error: Undefined content");
    }

    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case SignalServiceProtos.FenceCommand.CommandTypes.JOIN_VALUE:
        return describeIncomingUpdateJoin (context, ufsrvCommand);

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return emphasisAdded(context.getString(R.string.group_avatar_changed_thread));

      case FenceCommand.CommandTypes.FNAME_VALUE:
//        return emphasisAdded(context.getString(R.string.group_name_changed_thread));
      if (fenceCommand.getHeader().getArgsError()==FenceCommand.Errors.NONE_VALUE) {
        return emphasisAdded(context.getString(R.string.group_name_changed_thread));
      } else return emphasisAdded(context.getString(R.string.error_changing_group_name_thread));

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return (describeIncomingUpdateInvite (context, ufsrvCommand));

      case FenceCommand.CommandTypes.STATE_VALUE:
        return (describeIncomingUpdateState (context, ufsrvCommand));

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return (describeIncomingFencePermission (context, ufsrvCommand));

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return describeIncomingUpdateMaxMembers (context, ufsrvCommand);

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        return describeIncomingUpdateDeliveryMode (context, ufsrvCommand);
    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }


  public SpannableString describeIncomingUpdateJoin (Context context, SignalServiceProtos.UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.CREATED_VALUE:
        return emphasisAdded(context.getString(R.string.MessageRecord_uf_created_group));

      case CommandArgs.UNCHANGED_VALUE:
        return emphasisAdded(context.getString(R.string.MessageRecord_uf_rejoined_group));

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context.getString(R.string.you_have_joined_the_group));

      case CommandArgs.GEO_BASED_VALUE:
        return emphasisAdded(context.getString(R.string.you_have_joined_a_geo_group));

      case CommandArgs.INVITED_VALUE:
      {
        String sourceUser = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        return emphasisAdded(context.getString(R.string.user_invited_you_to_join_thread, Recipient.from(context, Address.fromSerialized(sourceUser), true).getDisplayName()));
      }
      case CommandArgs.INVITED_GEO_VALUE:
        return emphasisAdded(context.getString(R.string.you_received_geogroup_invitation_thread));
      
      case CommandArgs.ACCEPTED_INVITE_VALUE:
        return emphasisAdded(context.getString(R.string.jined_by_prior_invitation));

      //for this user the originator value is unset. This is distinct message from the other join confirmation type, as this carries full group configuration data for the newly joining user
      case CommandArgs.SYNCED_VALUE:
        String sourceUser         = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        if (!TextUtils.isEmpty(sourceUser) && !sourceUser.equals(UfsrvUid.UndefinedUfsrvUid))
          return emphasisAdded(context.getString(R.string.user_joined_the_group, Recipient.from(context, Address.fromSerialized(sourceUser), true).getDisplayName()));
        else emphasisAdded(context.getString(R.string.the_group_is_fully_loaded));

    }

    if (fenceCommand.getHeader().getCommand()==0 && commandArgs==0) return emphasisAdded(""); //this happens when no messages in the thread
    else return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming group event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }


  public SpannableString describeIncomingUpdateInvite (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.ADDED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount()>0) {
          Recipient recipient =  Recipient.fromUfsrvUid(context, new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()), true);
          return emphasisAdded(context.getString(R.string.x_newly_invited, recipient.getDisplayName()));
        }
        break;
      case CommandArgs.UNCHANGED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount()>0) {
          Recipient recipient =  Recipient.fromUfsrvUid(context, new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()), true);
          return new SpannableString(context.getString(R.string.x_is_already_inviyted, recipient.getDisplayName()));
        }
        break;

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingFencePermission (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        //todo: check permission user's list for self
          return emphasisAdded(context.getString(R.string.group_permissions_updated_thread));

      case CommandArgs.DELETED_VALUE:
        //todo: check permission user's list for self
        return emphasisAdded(context.getString(R.string.group_permissions_updated_thread));

      case CommandArgs.ACCEPTED_VALUE:
          return emphasisAdded(context.getString(R.string.group_permissions_updated_thread));

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context.getString(R.string.group_permissions_rejected_thread));
    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming Fence Permission event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateMaxMembers (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand       = ufsrvCommand.getFenceCommand();
    int commandArgs                 = fenceCommand.getHeader().getArgs();
    String sourceUser               = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient recipientSource       =  Recipient.from(context, Address.fromSerialized(sourceUser), true);

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return emphasisAdded(context.getString(R.string.maxmembers_updated_thread));

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context.getString(R.string.maxmembers_updated_by_you, recipientSource.getDisplayName(), fenceCommand.getFences(0).getMaxmembers()));

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context.getString(R.string.maxmembers_request_rejected_thread));

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateDeliveryMode (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand       = ufsrvCommand.getFenceCommand();
    int commandArgs                 = fenceCommand.getHeader().getArgs();
    String sourceUser               = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient recipientSource       =  Recipient.from(context, Address.fromSerialized(sourceUser), true);

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return emphasisAdded(context.getString(R.string.delivery_mode_updated_thread));

      case CommandArgs.ACCEPTED_VALUE:
        return emphasisAdded(context.getString(R.string.delivery_mode_updated_by_you, recipientSource.getDisplayName(), fenceCommand.getFences(0).getDeliveryMode().getNumber()));

      case CommandArgs.REJECTED_VALUE:
        return emphasisAdded(context.getString(R.string.delivery_mode_request_rejected_thread));

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateState (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand  fenceCommand  = ufsrvCommand.getFenceCommand();
    int           commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case SignalServiceProtos.CommandArgs.SYNCED_VALUE:
          return emphasisAdded(context.getString(R.string.group_information_has_been_updated));

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming  group STATE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }


  public SpannableString describeIncomingLeave (Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand     = ufsrvCommand.getFenceCommand();
    int           commandArgs     = fenceCommand.getHeader().getArgs();
    String        sourceUser      = GroupMessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean       isMe            = TextUtils.isEmpty(sourceUser)|| sourceUser.equals(UfsrvUid.UndefinedUfsrvUid) || sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));

    StringBuilder description     = new StringBuilder();

    Recipient    recipient;
    if (isMe) {
      sourceUser = "You";
      recipient      =  Recipient.from(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), true);
    }
    else      {
      recipient      =  Recipient.from(context, Address.fromSerialized(sourceUser), true);
      sourceUser = String.format("%s ", recipient.getDisplayName());
    }
    description.append(sourceUser);

    switch (commandArgs)
    {
      case  SignalServiceProtos.CommandArgs.ACCEPTED_VALUE:
      case  SignalServiceProtos.CommandArgs.GEO_BASED_VALUE://we roll them into one for now

        return emphasisAdded(description.append(context.getString(R.string.have_left_the_group_thread)).toString());

      case  SignalServiceProtos.CommandArgs.SYNCED_VALUE:
        return emphasisAdded(description.append(context.getString(R.string.have_left_the_group_thread)).toString());

      case SignalServiceProtos.CommandArgs.UNINVITED_VALUE:
        //semantics slightly different for uninvite, as it could originate from server, not user
        String invitedUser = UfsrvUid.EncodedfromSerialisedBytes(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray());

        isMe=TextUtils.isEmpty(sourceUser) || invitedUser.equals(TextSecurePreferences.getUfsrvUserId(context));
        if (isMe) return emphasisAdded(description.append(context.getString(R.string.you_no_longer_on_invite_list_thread)).toString());

        recipient =  Recipient.from(context, Address.fromSerialized(invitedUser), true);
        return emphasisAdded(context.getString(R.string.user_no_longer_on_invited_list_thread, recipient.getDisplayName()));

    }

    return emphasisAdded(String.format(Locale.getDefault(), "Unknown incoming group leave event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }
}

