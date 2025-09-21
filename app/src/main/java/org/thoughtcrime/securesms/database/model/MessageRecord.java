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

import android.content.Context;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.R;
import com.unfacd.android.fence.FencePermissions;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.thoughtcrime.securesms.components.emoji.EmojiProvider;
import org.thoughtcrime.securesms.components.emoji.parsing.EmojiParser;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.database.model.databaseprotos.GroupCallUpdateDetails;
import org.thoughtcrime.securesms.database.model.databaseprotos.ProfileChangeDetails;
import org.thoughtcrime.securesms.emoji.EmojiSource;
import org.thoughtcrime.securesms.emoji.JumboEmoji;
import org.thoughtcrime.securesms.groups.GroupV1MessageProcessor;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.StringUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs.CREATED_VALUE;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand.CommandTypes.LINKJOIN_VALUE;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */

public abstract class MessageRecord extends DisplayRecord {
  private static final String TAG = Log.tag(MessageRecord.class);

  private final Recipient                individualRecipient;
  private final int                      recipientDeviceId;
  private final long                     id;
  private final Set<IdentityKeyMismatch> mismatches;
  private final Set<NetworkFailure>      networkFailures;
  private final int                      subscriptionId;
  private final long                     expiresIn;
  private final long                     expireStarted;
  private final boolean                  unidentified;
  private final List<ReactionRecord>     reactions;
  private final long                     serverTimestamp;
  private final boolean                  remoteDelete;
  private final long                     notifiedTimestamp;
  private final long                     receiptTimestamp;

  protected Boolean isJumboji = null;

  MessageRecord(long id, String body, Recipient conversationRecipient,
                Recipient individualRecipient, int recipientDeviceId,
                long dateSent, long dateReceived, long dateServer, long threadId,
                int deliveryStatus, int deliveryReceiptCount, long type,
                Set<IdentityKeyMismatch> mismatches,
                Set<NetworkFailure> networkFailures,
                int subscriptionId, long expiresIn, long expireStarted,
                int readReceiptCount, boolean unidentified, @NonNull List<ReactionRecord> reactions, boolean remoteDelete, long notifiedTimestamp,
                int viewedReceiptCount, long receiptTimestamp,
                UfsrvCommandWire ufsrvCommand, long gid, long eid, long fid, int status, int commandType, int commandArg)//AA+
  {
    super(body, conversationRecipient, dateSent, dateReceived,
          threadId, deliveryStatus, deliveryReceiptCount, type,
          readReceiptCount, viewedReceiptCount,
          ufsrvCommand, gid, eid, fid, status, commandType, commandArg);//AA+ ufsrvCommand
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.recipientDeviceId   = recipientDeviceId;
    this.mismatches          = mismatches;
    this.networkFailures     = networkFailures;
    this.subscriptionId      = subscriptionId;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.unidentified        = unidentified;
    this.reactions           = reactions;
    this.serverTimestamp     = dateServer;
    this.remoteDelete        = remoteDelete;
    this.notifiedTimestamp   = notifiedTimestamp;
    this.receiptTimestamp = receiptTimestamp;
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
  @WorkerThread
  public SpannableString getDisplayBody(@NonNull Context context) {
    UpdateDescription updateDisplayBody = getUpdateDisplayBody(context);

    if (updateDisplayBody != null) {
      return new SpannableString(updateDisplayBody.getString());
    }

    return new SpannableString(getBody());
  }

  public @Nullable UpdateDescription getUpdateDisplayBody(@NonNull Context context) {
    if (false && isGroupUpdate() && isGroupV2()) {//AA+ todo bypass for now
      return getGv2ChangeDescription(context, getBody());
    } else if (isGroupUpdate() && isOutgoing()) {
      return staticUpdateDescription(describeOutgoingUpdate(context, getUfsrvCommand()).toString(), R.drawable.ic_update_group_16);
    } else if (isGroupUpdate()) {
      return staticUpdateDescription(describeIncomingUpdate(context, getUfsrvCommand()).toString(), R.drawable.ic_update_group_16);
    }  else if (isProfileLog()) {
      return staticUpdateDescription(describeProfileLog(context, getUfsrvCommand()).toString(), R.drawable.face_man_outline_light_16);
    } else if (isGroupProfileLog()) {
      return staticUpdateDescription(describeGroupProfileLog(context, getUfsrvCommand()).toString(), R.drawable.ic_update_group_16);
    } else if (isGuardianLog()) {
      return staticUpdateDescription(describeGuardianLog(context).toString(), R.drawable.eye_outline_light_16);
    } else if (isGroupQuit() && isOutgoing()) {
      return staticUpdateDescription(context.getString(R.string.requesting_to_leave_group), R.drawable.ic_update_group_leave_16);
    } else if (isGroupQuit()) {
      return staticUpdateDescription(describeIncomingLeave(context, getUfsrvCommand()).toString(), R.drawable.ic_update_group_leave_16);
    } else if (isIncomingAudioCall()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_called_you_date, r.getDisplayName(context), getCallDateString(context)), R.drawable.ic_update_audio_call_incoming_16);
    } else if (isIncomingVideoCall()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_called_you_date, r.getDisplayName(context), getCallDateString(context)), R.drawable.ic_update_video_call_incomg_16);
    } else if (isOutgoingAudioCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_you_called_date, getCallDateString(context)), R.drawable.ic_update_audio_call_outgoing_16);
    } else if (isOutgoingVideoCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_you_called_date, getCallDateString(context)), R.drawable.ic_update_video_call_outgoing_16);
    } else if (isMissedAudioCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_missed_audio_call_date, getCallDateString(context)), R.drawable.ic_update_audio_call_missed_16, ContextCompat.getColor(context, R.color.core_red_shade), ContextCompat.getColor(context, R.color.core_red));
    } else if (isMissedVideoCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_missed_video_call_date, getCallDateString(context)), R.drawable.ic_update_video_call_missed_16, ContextCompat.getColor(context, R.color.core_red_shade), ContextCompat.getColor(context, R.color.core_red));
    } else if (isGroupCall()) {
      return getGroupCallUpdateDescription(context, getBody(), true);
    } else if (isJoined()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_s_is_on_unfacd, getIndividualRecipient().getDisplayName(context)), R.drawable.ic_update_group_add_16);
    } else if (isExpirationTimerUpdate()) {
      //todo use , R.drawable.ic_update_timer_light_16, R.drawable.ic_update_timer_dark_16 for seting expiration (as opposed to cancelling)
      return staticUpdateDescription(describeExpiryUpdate(context, getUfsrvCommand()).toString(), R.drawable.ic_update_timer_disabled_16);
    } else if (isIdentityUpdate()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_your_safety_number_with_s_has_changed, r.getDisplayName(context)), R.drawable.ic_update_safety_number_16);
    } else if (isIdentityVerified()) {
      if (isOutgoing()) return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified, r.getDisplayName(context)), R.drawable.ic_update_verified_16);
      else              return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified_from_another_device, r.getDisplayName(context)), R.drawable.ic_update_verified_16);
    } else if (isIdentityDefault()) {
      if (isOutgoing()) return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified, r.getDisplayName(context)), R.drawable.ic_update_info_16);
      else              return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified_from_another_device, r.getDisplayName(context)), R.drawable.ic_update_info_16);
    } else if (isProfileChange()) {
      return staticUpdateDescription(getProfileChangeDescription(context), R.drawable.ic_update_profile_16);
    } else if (isChangeNumber()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_changed_their_phone_number, r.getDisplayName(context)), R.drawable.ic_phone_16);
    } else if (isBoostRequest()) {
      int message = SignalStore.donationsValues().isLikelyASustainer() ? R.string.MessageRecord_like_this_new_feature_say_thanks_with_a_boost
                                                                       : R.string.MessageRecord_signal_is_powered_by_people_like_you_become_a_sustainer_today;
      return staticUpdateDescription(context.getString(message), 0);
    }  else if (isEndSession()) {
      if (isOutgoing())
        return staticUpdateDescription(context.getString(R.string.SmsMessageRecord_secure_session_reset), R.drawable.ic_update_info_16);
      else
        return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.SmsMessageRecord_secure_session_reset_s, r.getDisplayName(context)), R.drawable.ic_update_info_16);
    } else if (isGroupV1MigrationEvent()) {
      //AA refernce only
//      if (Util.isEmpty(getBody())) {
//        return staticUpdateDescription(context.getString(R.string.MessageRecord_this_group_was_updated_to_a_new_group), R.drawable.ic_update_group_role_light_16, R.drawable.ic_update_group_role_dark_16);
//      } else {
//        int count = getGroupV1MigrationEventInvites().size();
//        return staticUpdateDescription(context.getResources().getQuantityString(R.plurals.MessageRecord_members_couldnt_be_added_to_the_new_group_and_have_been_invited, count, count), R.drawable.ic_update_group_add_light_16, R.drawable.ic_update_group_add_dark_16);
//      }
    } else if (isChatSessionRefresh()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_chat_session_refreshed), R.drawable.ic_refresh_16);
    } else if (isBadDecryptType()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_a_message_from_s_couldnt_be_delivered, r.getDisplayName(context)), R.drawable.ic_error_outline_14);
    }

    return staticUpdateDescription(getBody(), R.drawable.ic_update_group_16);//AA+ don't return null
  }

  public boolean isDisplayBodyEmpty(@NonNull Context context) {
    return getUpdateDisplayBody(context) == null && getBody().isEmpty();
  }

  public boolean isSelfCreatedGroup() {
    return selfCreatedUfsrvGroup(getUfsrvCommand());//AA+

    /*DecryptedGroupV2Context decryptedGroupV2Context = getDecryptedGroupV2Context();

    if (decryptedGroupV2Context == null) {
      return false;
    }
    DecryptedGroupChange change = decryptedGroupV2Context.getChange();

    return selfCreatedGroup(change);*/
  }

  @VisibleForTesting
  @Nullable DecryptedGroupV2Context getDecryptedGroupV2Context() {
    if (!isGroupUpdate() || !isGroupV2()) {
      return null;
    }

    return null;//AA+
   /* DecryptedGroupV2Context decryptedGroupV2Context;
    try {
      byte[] decoded = Base64.decode(getBody());
      decryptedGroupV2Context = DecryptedGroupV2Context.parseFrom(decoded);

    } catch (IOException e) {
      Log.w(TAG, "GV2 Message update detail could not be read", e);
      decryptedGroupV2Context = null;
    }
    return decryptedGroupV2Context;*/
  }

  private static boolean selfCreatedGroup(@NonNull DecryptedGroupChange change) {
    return change.getRevision() == 0 &&
           change.getEditor().equals(UuidUtil.toByteString(SignalStore.account().requireAci().uuid()));

  }

  //AA+
  private static boolean selfCreatedUfsrvGroup(@Nullable UfsrvCommandWire ufsrvCommand) {
    if (ufsrvCommand != null) {
      if (ufsrvCommand.hasFenceCommand() && ufsrvCommand.getFenceCommand()
                                                        .getFencesCount() > 0) {
        FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
        FenceRecord fenceRecord = fenceCommand.getFences(0);
        return fenceRecord.hasOwnerUid() && UfsrvFenceUtils.recipientFromUfsrvId(fenceRecord.getOwnerUid().toByteArray())
                                                           .equals(Recipient.self()) && fenceCommand.getHeader().getArgs() == CREATED_VALUE;
      }
    }
    Log.w(TAG, "selfCreatedUfsrvGroup: ufsrvCommand WAS NULL");
    return false;
  }


  public static @NonNull UpdateDescription getGv2ChangeDescription(@NonNull Context context, @NonNull String body) {
    try {
      ShortStringDescriptionStrategy descriptionStrategy     = new ShortStringDescriptionStrategy(context);
      byte[]                         decoded                 = Base64.decode(body);
      DecryptedGroupV2Context        decryptedGroupV2Context = DecryptedGroupV2Context.parseFrom(decoded);
      GroupsV2UpdateMessageProducer  updateMessageProducer   = new GroupsV2UpdateMessageProducer(context, descriptionStrategy, SignalStore.account().requireAci().uuid());

      if (decryptedGroupV2Context.hasChange() && decryptedGroupV2Context.getGroupState().getRevision() != 0) {
        return UpdateDescription.concatWithNewLines(updateMessageProducer.describeChanges(decryptedGroupV2Context.getPreviousGroupState(), decryptedGroupV2Context.getChange()));
      } else {
        List<UpdateDescription> newGroupDescriptions = new ArrayList<>();
        newGroupDescriptions.add(updateMessageProducer.describeNewGroup(decryptedGroupV2Context.getGroupState(), decryptedGroupV2Context.getChange()));

        if (decryptedGroupV2Context.getChange().hasNewTimer()) {
          updateMessageProducer.describeNewTimer(decryptedGroupV2Context.getChange(), newGroupDescriptions);
        }

        if (selfCreatedGroup(decryptedGroupV2Context.getChange())) {
          newGroupDescriptions.add(staticUpdateDescription(context.getString(R.string.MessageRecord_invite_friends_to_this_group), 0));
        }
        return UpdateDescription.concatWithNewLines(newGroupDescriptions);
      }
    } catch (IOException | IllegalArgumentException e) {
      Log.w(TAG, "GV2 Message update detail could not be read", e);
      return staticUpdateDescription(context.getString(R.string.MessageRecord_group_updated), R.drawable.ic_update_group_16);
    }
  }

  public static @NonNull List<RecipientId> getMentionsRecipients(@NonNull Context context, @Nullable UfsrvCommandWire ufsrvCommandWire) {
    if (ufsrvCommandWire != null) {
      if (ufsrvCommandWire.hasMsgCommand()) {
        SignalServiceProtos.MessageRecord messageRecord = ufsrvCommandWire.getMsgCommand().getMessages(0);
        try {
          List<SignalServiceDataMessage.Mention> mentions = SignalServiceContent.createMentions(ufsrvCommandWire.getMsgCommand()
                                                                                                                .getBodyRangesList(), ufsrvCommandWire.getMsgCommand().getMessagesCount() > 0 ? ufsrvCommandWire.getMsgCommand().getMessages(0).getMessage().toStringUtf8() : null);
          Optional<List<Mention>> mentionsList = UfsrvMessageUtils.getMentions(Optional.of(mentions));
          return mentionsList.get().stream().map(Mention::getRecipientId).collect(Collectors.toList());
        } catch (InvalidMessageStructureException x) {
          Log.w(TAG,  x);
          return Collections.emptyList();
        }
      }

    }

    Log.w(TAG, "getGv2ChangeDescription: UfsrvCommandWire WAS NULL");
    return Collections.emptyList();

  }

  //AA am i on the invited list for this group. Seems to multiplex invites / join requests
  public @Nullable InviteAddState getGv2AddInviteState() {
    //AA+
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();
    Recipient       recipient       = threadDatabase.getRecipientForThreadId(getThreadId());//AA better query on threadid, because querying sms message record based on recipient can return recipient as ufsrv (representing inbound server messages)
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupByGroupId(recipient.getGroupId().orElse(null));

    if (groupRecord == null) {
      return null;
    }

    boolean invited = groupRecord.getMembersInvitedRecipientId().contains(Recipient.self().getId());

    return new InviteAddState(invited, Recipient.self().requireServiceId().uuid());//AA+ todo capture invited by value

    /*DecryptedGroup groupState = decryptedGroupV2Context.getGroupState();
    boolean invited = DecryptedGroupUtil.findPendingByUuid(groupState.getPendingMembersList(), SignalStore.account().requireAci().uuid()).isPresent();

    if (decryptedGroupV2Context.hasChange()) {
      UUID changeEditor = UuidUtil.fromByteStringOrNull(decryptedGroupV2Context.getChange().getEditor());

      if (changeEditor != null) {
        return new InviteAddState(invited, changeEditor);
      }
    }

    Log.w(TAG, "GV2 Message editor could not be determined");
    return null;*/
  }

  //AA+ this to distinguish the semantics for invite vs message request
  public @Nullable InviteAddState getGv2AddMessageRequestState() {
    //AA+
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();
    Recipient       recipient       = threadDatabase.getRecipientForThreadId(getThreadId());//AA better query on threadid, because querying sms message record based on recipient can return recipient as ufsrv (representing inbound server messages)
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupByGroupId(recipient.getGroupId().orElse(null));

    if (groupRecord == null) {
      return null;
    }

    boolean invited = groupRecord.getMembersLinkJoiningRecipientId().contains(Recipient.self().getId());

    return new InviteAddState(true, Recipient.self().requireServiceId().uuid());//AA+ todo capture invited by value


  }
  //

  private static @NonNull UpdateDescription staticUpdateDescription(@NonNull String string,
                                                                    @DrawableRes int iconResource,
                                                                    @ColorInt int lightTint,
                                                                    @ColorInt int darkTint)
  {
    return UpdateDescription.staticDescription(string, iconResource, lightTint, darkTint);
  }

  private @NonNull String getProfileChangeDescription(@NonNull Context context) {
    try {
      byte[]               decoded              = Base64.decode(getBody());
      ProfileChangeDetails profileChangeDetails = ProfileChangeDetails.parseFrom(decoded);

      if (profileChangeDetails.hasProfileNameChange()) {
        String displayName  = getIndividualRecipient().getDisplayName(context);
        String newName      = StringUtil.isolateBidi(ProfileName.fromSerialized(profileChangeDetails.getProfileNameChange().getNew()).toString());
        String previousName = StringUtil.isolateBidi(ProfileName.fromSerialized(profileChangeDetails.getProfileNameChange().getPrevious()).toString());

        if (getIndividualRecipient().isSystemContact()) {
          return context.getString(R.string.MessageRecord_changed_their_profile_name_from_to, displayName, previousName, newName);
        } else {
          return context.getString(R.string.MessageRecord_changed_their_profile_name_to, previousName, newName);
        }
      }
    } catch (IOException e) {
      Log.w(TAG, "Profile name change details could not be read", e);
    }

    return context.getString(R.string.MessageRecord_changed_their_profile, getIndividualRecipient().getDisplayName(context));
  }

  public static @NonNull UpdateDescription getGroupCallUpdateDescription(@NonNull Context context, @NonNull String body, boolean withTime) {
    GroupCallUpdateDetails groupCallUpdateDetails = GroupCallUpdateDetailsUtil.parse(body);

    List<ServiceId> joinedMembers = Stream.of(groupCallUpdateDetails.getInCallUuidsList())
                                          .map(UuidUtil::parseOrNull)
                                          .withoutNulls()
                                          .map(ServiceId::from)
                                          .toList();

    UpdateDescription.StringFactory stringFactory = new GroupCallUpdateMessageFactory(context, joinedMembers, withTime, groupCallUpdateDetails);

    return UpdateDescription.mentioning(joinedMembers, stringFactory, R.drawable.ic_video_16);
  }

  public boolean isGroupV2DescriptionUpdate() {
    DecryptedGroupV2Context decryptedGroupV2Context = getDecryptedGroupV2Context();
    if (decryptedGroupV2Context != null) {
      return decryptedGroupV2Context.hasChange() && getDecryptedGroupV2Context().getChange().hasNewDescription();
    }
    return false;
  }

  public @NonNull String getGroupV2DescriptionUpdate() {
    DecryptedGroupV2Context decryptedGroupV2Context = getDecryptedGroupV2Context();
    if (decryptedGroupV2Context != null) {
      return decryptedGroupV2Context.getChange().hasNewDescription() ? decryptedGroupV2Context.getChange().getNewDescription().getValue() : "";
    }
    return "";
  }

  public boolean isGroupV2JoinRequest(@Nullable ServiceId serviceId) {
    if (serviceId == null) {
      return false;
    }

    return isGroupV2JoinRequest(UuidUtil.toByteString(serviceId.uuid()));
  }

  public boolean isGroupV2JoinRequest(@NonNull ByteString uuid) {
    DecryptedGroupV2Context decryptedGroupV2Context = getDecryptedGroupV2Context();
    if (decryptedGroupV2Context != null && decryptedGroupV2Context.hasChange()) {
      DecryptedGroupChange change = decryptedGroupV2Context.getChange();
      return change.getEditor().equals(uuid) && change.getNewRequestingMembersList().stream().anyMatch(r -> r.getUuid().equals(uuid));
    }
    return false;
  }

  public boolean isCollapsedGroupV2JoinUpdate() {
    return isCollapsedGroupV2JoinUpdate(null);
  }

  public boolean isCollapsedGroupV2JoinUpdate(@Nullable ServiceId serviceId) {
    DecryptedGroupV2Context decryptedGroupV2Context = getDecryptedGroupV2Context();
    if (decryptedGroupV2Context != null && decryptedGroupV2Context.hasChange()) {
      DecryptedGroupChange change = decryptedGroupV2Context.getChange();
      return change.getNewRequestingMembersCount() > 0 &&
              change.getDeleteRequestingMembersCount() > 0 &&
              (serviceId == null || change.getEditor().equals(UuidUtil.toByteString(serviceId.uuid())));
    }
    return false;
  }

  //AA+
  public boolean isLinkJoinAdminAction() {
    if (getUfsrvCommand() != null && getUfsrvCommand().hasFenceCommand()) {
      FenceCommand fenceCommand = getUfsrvCommand().getFenceCommand();
      Recipient recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
      if (fenceCommand.getHeader().getCommand() == LINKJOIN_VALUE && fenceCommand.getHeader().getArgs() == CommandArgs.ADDED_VALUE && !Recipient.self().equals(recipientOriginator)) {
        return true;
      }
    }

    return false;
  }

  //AA+
  public boolean isSelfAdmin() {
    if (getUfsrvCommand().hasFenceCommand() && getUfsrvCommand().getFenceCommand().getFencesCount() > 0) {
      FenceRecord fenceRecord = getUfsrvCommand().getFenceCommand().getFences(0);
      long fenceOwnerId = SignalDatabase.groups().getOwnerUserId(fenceRecord.getFid());
      return fenceOwnerId == UfsrvUid.DecodeUfsrvSequenceId(Recipient.self().getUfrsvUidRaw()
      );
    }

    return false;
  }

  public static @NonNull String createNewContextWithAppendedDeleteJoinRequest(@NonNull MessageRecord messageRecord, int revision, @NonNull ByteString id) {
    DecryptedGroupV2Context decryptedGroupV2Context = messageRecord.getDecryptedGroupV2Context();

    if (decryptedGroupV2Context != null && decryptedGroupV2Context.hasChange()) {
      DecryptedGroupChange change = decryptedGroupV2Context.getChange();

      return Base64.encodeBytes(decryptedGroupV2Context.toBuilder()
                                                       .setChange(change.toBuilder()
                                                                        .setRevision(revision)
                                                                        .addDeleteRequestingMembers(id))
                                                       .build().toByteArray());
    }

    throw new AssertionError("Attempting to modify a message with no change");
  }

  /**
   * Describes a UUID by it's corresponding recipient's {@link Recipient#getDisplayName(Context)}.
   */
  private static class ShortStringDescriptionStrategy implements GroupsV2UpdateMessageProducer.DescribeMemberStrategy {

    private final Context context;

    ShortStringDescriptionStrategy(@NonNull Context context) {
      this.context = context;
    }

    @Override
    public @NonNull String describe(@NonNull ServiceId serviceId) {
      if (serviceId.isUnknown()) {
        return context.getString(R.string.MessageRecord_unknown);
      }
      return Recipient.resolved(RecipientId.from(serviceId, null)).getDisplayName(context);
    }

    //AA+
    @Override
    public @NonNull String describe(@NonNull UfsrvUid ufsrvUid) {
      if (UfsrvUid.UndefinedUfsrvUid.equals(ufsrvUid)) {
        return context.getString(R.string.MessageRecord_unknown);
      }
      return Recipient.resolved(RecipientId.from(ufsrvUid)).getDisplayName(context);
    }
  }

  public long getId() {
    return id;
  }

  public boolean isPush() {
    return SmsDatabase.Types.isPushType(type) && !SmsDatabase.Types.isForcedSms(type);
  }

  public long getTimestamp() {
    if ((isPush() || isCallLog()) && getDateSent() < getDateReceived()) {
      return getDateSent();
    }
    return getDateReceived();
  }

  public long getServerTimestamp() {
    return serverTimestamp;
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

  public boolean isRateLimited() {
    return SmsDatabase.Types.isRateLimited(type);
  }

  public boolean isIdentityUpdate() {
    return SmsDatabase.Types.isIdentityUpdate(type);
  }

  public boolean isCorruptedKeyExchange() {
    return SmsDatabase.Types.isCorruptedKeyExchange(type);
  }

  public boolean isBadDecryptType() {
    return MmsSmsColumns.Types.isBadDecryptType(type);
  }

  public boolean isInvalidVersionKeyExchange() {
    return SmsDatabase.Types.isInvalidVersionKeyExchange(type);
  }

  public boolean isGroupV1MigrationEvent() {
    return SmsDatabase.Types.isGroupV1MigrationEvent(type);
  }

  public @NonNull List<RecipientId> getGroupV1MigrationEventInvites() {
    if (isGroupV1MigrationEvent()) {
      return RecipientId.fromSerializedList(getBody());
    } else {
      return Collections.emptyList();
    }
  }

  public boolean isUpdate() {
    return isGroupAction() || isJoined() || isExpirationTimerUpdate() || isCallLog() ||
            isEndSession()  || isIdentityUpdate() || isIdentityVerified() || isIdentityDefault() || isProfileChange() || isGroupV1MigrationEvent() || isChatSessionRefresh() || isBadDecryptType() ||
            isChangeNumber() || isBoostRequest() ||
            isProfileLog() || isGroupProfileLog() || isReportedMessageLog() || isGuardianLog();//AA+ isProfileLog()
  }

  public boolean isMediaPending() {
    return false;
  }

  //AA+
  public boolean isUfsrvControlMessage() {
    return individualRecipient.requireAddress().equals(Address.UFSRV);
  }

  public Recipient getIndividualRecipient() {
    return individualRecipient.live().get();
  }

  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  public long getType() {
    return type;
  }

  public Set<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return mismatches;
  }

  public Set<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public boolean hasNetworkFailures() {
    return networkFailures != null && !networkFailures.isEmpty();
  }

  public boolean hasFailedWithNetworkFailures() {
    return isFailed() && ((getRecipient().isPushGroup() && hasNetworkFailures()) || !isIdentityMismatchFailure());
  }

  public boolean isChatSessionRefresh() {
    return MmsSmsColumns.Types.isChatSessionRefresh(type);
  }

  public boolean isInMemoryMessageRecord() {
    return false;
  }

  //AA- might reinstate to keep italic formatting https://github.com/signalapp/Signal-Android/blob/24b062d8dde3d2bbcebc110400c95c9ffb649dc5/src/org/thoughtcrime/securesms/database/model/MessageRecord.java
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

  public boolean isViewOnce() {
    return false;
  }

  public boolean isRemoteDelete() {
    return remoteDelete;
  }

  public @NonNull List<ReactionRecord> getReactions() {
    return reactions;
  }

  public boolean hasSelfMention() {
    return false;
  }

  public long getNotifiedTimestamp() {
    return notifiedTimestamp;
  }

  public long getReceiptTimestamp() {
    if (!isOutgoing()) {
      return getDateSent();
    } else {
      return receiptTimestamp;
    }
  }

  public boolean isJumbomoji(Context context) {
    if (isJumboji == null) {
      if (getBody().length() <= EmojiSource.getLatest().getMaxEmojiLength() * JumboEmoji.MAX_JUMBOJI_COUNT) {
        EmojiParser.CandidateList candidates = EmojiProvider.getCandidates(getDisplayBody(context));
        isJumboji = candidates != null && candidates.allEmojis && candidates.size() <= JumboEmoji.MAX_JUMBOJI_COUNT && (candidates.hasJumboForAll() || JumboEmoji.canDownloadJumbo(context));
      } else {
        isJumboji = false;
      }
    }
    return isJumboji;
  }

  public boolean hasMessageRanges() {
    return false;
  }

  public @NonNull BodyRangeList requireMessageRanges() {
    throw new NullPointerException();
  }

  public static final class InviteAddState {

    private final boolean invited;
    private final UUID    addedOrInvitedBy;

    public InviteAddState(boolean invited, @NonNull UUID addedOrInvitedBy) {
      this.invited          = invited;
      this.addedOrInvitedBy = addedOrInvitedBy;
    }

    public @NonNull UUID getAddedOrInvitedBy() {
      return addedOrInvitedBy;
    }

    public boolean isInvited() {
      return invited;
    }
  }

  public SpannableString describeOutgoingUpdate(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        if (fenceCommand.getFences(0).getFid() == 0)
          return new SpannableString(context.getString(R.string.waiting_for_group_confirmation));
        else
          return describeOutgoingUpdateJoin(context, ufsrvCommand);

      case FenceCommand.CommandTypes.LINKJOIN_VALUE:
        return describeOutgoingLinkJoin(context, ufsrvCommand);

      case FenceCommand.CommandTypes.FNAME_VALUE:
        return new SpannableString(context.getString(R.string.requesting_group_name));

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        return new SpannableString(context.getString(R.string.updatings_group_avatar));

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return new SpannableString(context.getString(R.string.sending_grou_invitation));

      case FenceCommand.CommandTypes.INVITE_DELETED_VALUE:
        Recipient recipientDeleted = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
        return new SpannableString(context.getString(R.string.sending_invitation_removal_for_x, recipientDeleted.getDisplayName(context)));

      case FenceCommand.CommandTypes.INVITE_REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.sending_invitation_rejection));

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

  //AA+
  public SpannableString describeProfileLog(Context context, UfsrvCommandWire ufsrvCommand)
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

  public SpannableString handleUserProfileLogExtended(Context context, UserCommand userCommand, String profileDescriptorName)
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

  public SpannableString handleProfileLogExtended(Context context, UserCommand userCommand)
  {
    SignalServiceProtos.UserPreference userPref  = userCommand.getPrefs(0);
    int commandArgs = userCommand.getHeader().getArgs();

    switch (userPref.getPrefId()) {
      case PROFILE:
        return describeProfileLogExtended(context, userCommand, "profile");
      case NETSTATE:
        return describeProfileLogExtended(context, userCommand, "presence information");
      case READ_RECEIPT:
        return describeProfileLogExtended(context, userCommand, "read receipt");
      case BLOCKING:
        return describeProfileLogBlock(context, userCommand);
      case CONTACTS:
        return describeProfileLogContact(context, userCommand);
      case ACTIVITY_STATE:
        return describeProfileLogExtended(context, userCommand, "typing indicator information");
      case BLOCKED_FENCE:
        return describeProfileLogBlockedFence(context, userCommand);
      default:
        return new SpannableString("Unknown UserPreference");
    }

  }

  SpannableString describeProfileLogExtended(Context context, UserCommand userCommand, String profileDescriptorName)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      //server accepted a previous request to add/remove a user to this client's sharelist. Doesn't mean the other user accepted that. (see ADDED below for that)
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient  recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.you_are_now_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.you_are_no_longer_sharing_x_with_y, profileDescriptorName, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();
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

  SpannableString describeProfileLogBlock(Context context, UserCommand userCommand)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient  recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.x_is_now_on_your_blocked_list, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.x_was_removed_from_your_blocked_list, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient  recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();
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

  SpannableString describeProfileLogBlockedFence(Context context, UserCommand userCommand)
  {
    FenceRecord fenceRecord = userCommand.getFencesBlocked(0);
    Recipient recipient = Recipient.resolved(RecipientUfsrvId.from(fenceRecord.getFid()));

    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.successfully_blocked_this_group));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.successfully_unblocked_x, recipient.getDisplayName(context)));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      return new SpannableString(context.getString(R.string.blocking_unblocking_request_unsuccessful));
    } else if (UfsrvUserUtils.isCommandAdded(userCommand)) {//branch for outgoing
      if (UfsrvUserUtils.isCommandClientEmpty(userCommand)) {
        return new SpannableString(context.getString(R.string.requesting_blocking));
      } else { //this branch unsupported at this stage, as wufsrv doesn;t share blickedfence updates by other users
        Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
      }
    } else if (UfsrvUserUtils.isCommandDeleted(userCommand)) {
      if (UfsrvUserUtils.isCommandClientEmpty(userCommand)) {

      } else {
        Recipient recipientOriginator = UfsrvUserUtils.recipientFromUserCommandOriginator(userCommand, false);
        //unsupported at this stage return new SpannableString(context.getString(R.string.x_was_removed_from_their__blocked_list, recipientOriginator.getDisplayName()));
      }
    }

    return new SpannableString(String.format("Unknown block setting: (args:'%d', args_client:'%d')", userCommand.getHeader().getArgs(), userCommand.getHeader().getArgsClient().getNumber()));
  }

  SpannableString describeProfileLogContact(Context context, SignalServiceProtos.UserCommand userCommand)
  {
    if (UfsrvUserUtils.isCommandAccepted(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
      Recipient                      recipientSharingWith   = Recipient.live(new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()).toString()).get();

      if (UfsrvUserUtils.isCommandClientAdded(userCommand)) {
        return new SpannableString(context.getString(R.string.x_is_now_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()));
      } else if (UfsrvUserUtils.isCommandClientDeleted(userCommand)){
        return new SpannableString(context.getString(R.string.x_is_no_longer_allowed_to_share_your_contact_info, recipientSharingWith.getDisplayName()));
      }
    } else if (UfsrvUserUtils.isCommandRejected(userCommand)) {
      SignalServiceProtos.UserRecord userRecordSharingWith  = userCommand.getTargetList(0);
//      Recipient                      recipientSharingWith   = Recipient.fromUfsrvUid(context, new UfsrvUid(userRecordSharingWith.getUfsrvuid().toByteArray()), false);
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

  //AA+
  public SpannableString describeGroupProfileLog(Context context, UfsrvCommandWire ufsrvCommand)
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


  public SpannableString describeGuardianLog(Context context)
  {
    switch (getUfsrvCommandType()) {
      case SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_REQUEST_VALUE:
        if (getUfsrvCommandArg() == CommandArgs.ACCEPTED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_request_accepted));
        } else if (getUfsrvCommandArg() == CommandArgs.SYNCED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_request_received));
        } else if (getUfsrvCommandArg() == CommandArgs.REJECTED_VALUE){
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_request_rejected));
        }
        break;

      case SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_LINK_VALUE:
        if (getUfsrvCommandArg() == CommandArgs.ACCEPTED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_link_accepted, individualRecipient.getDisplayName()));
        } else if (getUfsrvCommandArg() == CommandArgs.SYNCED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_link_received));
        } else if (getUfsrvCommandArg() == CommandArgs.REJECTED_VALUE){
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_link_rejected));
        }
        break;

      case SignalServiceProtos.MessageCommand.CommandTypes.GUARDIAN_UNLINK_VALUE:
        if (getUfsrvCommandArg() == CommandArgs.ACCEPTED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_unlink_accepted, individualRecipient.getDisplayName()));
        } else if (getUfsrvCommandArg() == CommandArgs.SYNCED_VALUE) {
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_unlink_received));
        } else if (getUfsrvCommandArg() == CommandArgs.REJECTED_VALUE){
          return new SpannableString(context.getString(R.string.MessageRecord_guardian_unlink_rejected));
        }
        break;
    }

    return new SpannableString(context.getString(R.string.MessageRecord_guardian_request_initiated));
  }

  public SpannableString describeReportedMessageLog(Context context)
  {
    if (getIndividualRecipient().requireAddress().equals(UfsrvUserUtils.myOwnAddress())) {
      return new SpannableString(context.getString(R.string.MessageDisplayHelper_x_flagged_ypur_message_as_inappropriate, getRecipient().getDisplayName()));
    } else {
      return new SpannableString(context.getString(R.string.MessageDisplayHelper_message_by_x_flagged_inappropriate, getIndividualRecipient().getDisplayName()));
    }

  }

  //AA+
  public boolean isUfsrSyncMessage()
  {
    return subscriptionId == SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_SYNC_VALUE;
  }

  //AA+
  public SpannableString describeExpiryUpdate(Context context, UfsrvCommandWire ufsrvCommand)
  {
    if (ufsrvCommand != null) {
      if (isOutgoing()) {
        int seconds = (int)(getExpiresIn() / 1000);
        if (seconds <= 0) {
          return  new SpannableString(context.getString(R.string.MessageRecord_you_disabled_disappearing_messages));
          //todo: do the same for incoming (for readability new SpannableString(context.getString(R.string.MessageRecord_s_disabled_disappearing_messages, getIndividualRecipient().getDisplayName()));
        }

        String time = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
        return new SpannableString(context.getString(R.string.requesting_message_expiry_setting, time));
      } else {
          FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
          int commandArgs = fenceCommand.getHeader().getArgs();
          String sourceUser = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
          boolean isMe = sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));
          Recipient recipient = Recipient.live(sourceUser).get();
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
      String sender = isOutgoing() ? context.getString(R.string.MessageRecord_you) : getIndividualRecipient().getDisplayName();
      String time   = ExpirationUtil.getExpirationDisplayValue(context, (int) (getExpiresIn() / 1000));
      return new SpannableString(context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, sender, time));
    }
  }

  public SpannableString describeOutgoingUpdateJoin(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.INVITED_VALUE:
        return new SpannableString(context.getString(R.string.accepting_invitation));

      case CommandArgs.DENIED_VALUE:
        return new SpannableString(context.getString(R.string.declining_request));

      case CommandArgs.LINK_BASED_VALUE:
        return new SpannableString(context.getString(R.string.message_record_outgoing_Group_link_join_accepted_joining_group));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group join event(update) (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeOutgoingLinkJoin(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();
    Recipient recipientOriginator = UfsrvFenceUtils.recipientFromFenceCommandOriginator(fenceCommand, false);

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        return new SpannableString(context.getString(R.string.message_record_outgoing_Sending_request_to_join_via_link));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.message_record_outgoing_group_admin_authorised_join_request, recipientOriginator.getDisplayName()));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.message_record_outgoing_group_admin_rejected_join_request, recipientOriginator.getDisplayName()));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group link join event(update) (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdate(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = TextUtils.isEmpty(sourceUser) || sourceUser.equals(UfsrvUid.UndefinedUfsrvUid);
    StringBuilder description = new StringBuilder();

    if (isMe) sourceUser = context.getString(R.string.MessageRecord_you) + " ";
    else      sourceUser = String.format("%s ", getIndividualRecipient().getDisplayName());

    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        return describeIncomingUpdateJoin(context, ufsrvCommand);

      case FenceCommand.CommandTypes.LINKJOIN_VALUE:
        return describeIncomingUpdateLinkJoin(context, ufsrvCommand);

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        description.append(context.getString(R.string.changed_avatar_name));
        return new SpannableString(description.toString());

        //todo: check for COMMAND_ARGS__ACCEPTED for self or COMMAND_ARGS__UPDATED for others
      case FenceCommand.CommandTypes.FNAME_VALUE:
        if (fenceCommand.getHeader().getArgsError() == FenceCommand.Errors.NONE_VALUE) {
          description.append(context.getString(R.string.changed_group_name));
        }
        else description.append(context.getString(R.string.there_was_error_changing_group_name));
        return new SpannableString(description.toString());

      case FenceCommand.CommandTypes.INVITE_VALUE:
        return describeIncomingUpdateInvite(context, ufsrvCommand);

      case FenceCommand.CommandTypes.INVITE_REJECTED_VALUE:
        return describeIncomingInviteRejected(context, ufsrvCommand);

      case FenceCommand.CommandTypes.INVITE_DELETED_VALUE:
        return describeIncomingInviteDeleted(context, ufsrvCommand);

      case FenceCommand.CommandTypes.STATE_VALUE:
        return describeIncomingUpdateState(context, ufsrvCommand);

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        return describeIncomingUpdatePermission(context, ufsrvCommand);

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        return describeIncomingUpdateMaxMembers(context, ufsrvCommand);

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        return describeIncomingUpdateDeliveryMode(context, ufsrvCommand);

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming update event (%d, %d, error:%d)", fenceCommand.getHeader().getCommand(), commandArgs, fenceCommand.getHeader().getArgsError()));
  }

  public SpannableString describeIncomingUpdateState(Context context, UfsrvCommandWire ufsrvCommand)
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

  public SpannableString describeIncomingUpdateJoin(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CREATED_VALUE:
        SpannableStringBuilder s = new SpannableStringBuilder().append(context.getString(R.string.MessageRecord_uf_created_group));
        s.append("\n"); s.append(context.getString(R.string.MessageRecord_invite_friends_to_this_group));
        return SpannableString.valueOf(s);

      case CommandArgs.UNCHANGED_VALUE:
        return new SpannableString(context.getString(R.string.MessageRecord_uf_rejoined_group));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.you_have_joined_the_group));

      case CommandArgs.GEO_BASED_VALUE:
        return new SpannableString(context.getString(R.string.you_have_joined_a_geo_group));

      case CommandArgs.INVITED_VALUE:
      {
        String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
        Recipient recipient =  Recipient.live(sourceUser).get();
        return new SpannableString(context.getString(R.string.user_invited_you_to_join, recipient.getDisplayName()));
      }

      case SignalServiceProtos.CommandArgs.ACCEPTED_INVITE_VALUE:
        return new SpannableString(context.getString(R.string.you_have_accepted_invitation_to_join));
      
      case CommandArgs.INVITED_GEO_VALUE:
        return new SpannableString(context.getString(R.string.you_received_geogroup_invitation, fenceCommand.getFences(0).getFname()));

      //for this client the originator value is unset. This is distinct message from the other join confirmation type, as this carries full group configuration data for the newly joining user
      case CommandArgs.SYNCED_VALUE:
        String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);

        if (!TextUtils.isEmpty(sourceUser) && !sourceUser.equals(UfsrvUid.UndefinedUfsrvUid)) {
          Recipient recipient     =  Recipient.live(sourceUser).get();
          return new SpannableString(context.getString(R.string.user_joined_the_group, recipient.getDisplayName()));
        } else  new SpannableString(context.getString(R.string.the_group_is_fully_loaded));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group join event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateLinkJoin(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();
    Recipient    recipientOriginator;
    boolean hasOriginator = false;

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
        if (!recipientOriginator.equals(Recipient.self())) {
          SpannableStringBuilder s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_Request_s_is_joining_via_join_link_request, recipientOriginator.getDisplayName(context)));
          return SpannableString.valueOf(s);
        } else {
          SpannableStringBuilder s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_Request_to_join_successfully_sent));
          return SpannableString.valueOf(s);
        }

      case CommandArgs.ACCEPTED_VALUE:
        hasOriginator = fenceCommand.hasOriginator();
        if (hasOriginator) {
          recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
          SpannableStringBuilder s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_finalised_link_join_request_for_s, recipientOriginator.getDisplayName(context)));
          return SpannableString.valueOf(s);
        } else {
          Recipient recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getAuthoriser());
          SpannableStringBuilder s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_group_admin_authorised_join_request));
          return SpannableString.valueOf(s);
        }

      case CommandArgs.REJECTED_VALUE:
        hasOriginator = fenceCommand.hasOriginator();
        if (hasOriginator) {
          recipientOriginator = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getOriginator());
          SpannableStringBuilder s = new SpannableStringBuilder(context.getString(R.string.message_record_incoming_finalised_rejected_link_join_request_for_s, recipientOriginator.getDisplayName(context)));
          return SpannableString.valueOf(s);
        } else {
          Recipient recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(fenceCommand.getAuthoriser());
          SpannableStringBuilder s = new SpannableStringBuilder().append(context.getString(R.string.message_record_incoming_group_admin_rejected_join_request));
          return SpannableString.valueOf(s);
        }
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group linkjoin event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateInvite(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand=ufsrvCommand.getFenceCommand();
    int commandArgs = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        if ( fenceCommand.getFences(0).getInvitedMembersCount()>0) {
          Recipient recipient =  Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();

          return new SpannableString(context.getString(R.string.x_has_been_invited_to_join_this_group, recipient.getDisplayName()));
        }
        break;
      case CommandArgs.UNCHANGED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount()>0) {
//          Recipient recipient =  Recipient.fromUfsrvUid(context, new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()), true);
          Recipient recipient =  Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
          return new SpannableString(context.getString(R.string.x_is_already_inviyted, recipient.getDisplayName()));
        }
        break;
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  private static @NonNull
  SpannableString describeIncomingInviteRejected(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
    int          commandArgs   = fenceCommand.getHeader().getArgs();

    switch (commandArgs)
    {
      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.invitation_rejection_processed));

      case CommandArgs.SYNCED_VALUE:
        if (fenceCommand.getFences(0).getInvitedMembersCount() > 0) {
          Recipient recipient = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
          return new SpannableString(context.getString(R.string.invitation_to_join_group_was_rejected_by_x, recipient.getDisplayName()));
        }

    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return new SpannableString(""); //this happens when no messages in the thread
    else return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group invite rejected event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  private static @NonNull SpannableString describeIncomingInviteDeleted(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand  = ufsrvCommand.getFenceCommand();
    int          commandArgs   = fenceCommand.getHeader().getArgs();
    Recipient    recipientUninvited = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
    Recipient    recipientOriginator = UfsrvFenceUtils.recipientFromFenceCommandOriginator(fenceCommand, false);

    switch (commandArgs)
    {
      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.successfully_withdrawn_invitation_for_x, recipientUninvited.getDisplayName(context)));

      case CommandArgs.SYNCED_VALUE:
        if (recipientUninvited.equals(Recipient.self())) {
          return new SpannableString(context.getString(R.string.you_have_been_removed_from_invite_list_by, recipientOriginator.getDisplayName(context)));
        } else {
          return new SpannableString(context.getString(R.string.x_is_no_longer_on_invite_list, recipientUninvited.getDisplayName()));
        }

    }

    if (fenceCommand.getHeader().getCommand() == 0 && commandArgs == 0) return new SpannableString(""); //this happens when no messages in the thread
    else return new SpannableString(String.format(Locale.getDefault(), "Unknown outgoing group invite deleted event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdatePermission(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand             = ufsrvCommand.getFenceCommand();
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceCommand.getFences(0), ufsrvCommand.getFenceCommand().getType());
    UfsrvUid                    useridTarget  = new UfsrvUid(permission.getUsers(0).getUfsrvuid().toByteArray());
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = useridTarget.getUfsrvSequenceId() == TextSecurePreferences.getUserId(context);
    Recipient recipientSource     =  Recipient.live(sourceUser).get();
    Recipient recipientTarget     =  Recipient.live(useridTarget.toString()).get();
    StringBuilder description       = new StringBuilder();



    switch (commandArgs)
    {
      case CommandArgs.ADDED_VALUE:
        if (isMe) return new SpannableString(context.getString(R.string.group_permissions_added_you, recipientSource.getDisplayName(), FencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        else {
          return new SpannableString(context.getString(R.string.group_permissions_added_other, recipientSource.getDisplayName(), recipientTarget.getDisplayName(), FencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        }

      case CommandArgs.DELETED_VALUE:
        if (isMe) return new SpannableString(context.getString(R.string.group_permissions_deleted_you, recipientSource.getDisplayName(), FencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        else {
          return new SpannableString(context.getString(R.string.group_permissions_deleted_other, recipientSource.getDisplayName(), recipientTarget.getDisplayName(), FencePermissions.values()[ufsrvCommand.getFenceCommand().getType().getNumber()].toString()));
        }

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.group_permissions_updated_thread));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.group_permissions_rejected));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group INVITE event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingUpdateMaxMembers(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand             = ufsrvCommand.getFenceCommand();
    UfsrvUid                    useridTarget  = new UfsrvUid(fenceCommand.getOriginator().getUfsrvuid().toByteArray());
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = useridTarget.getUfsrvSequenceId()==TextSecurePreferences.getUserId(context);
    Recipient recipientSource     =  Recipient.live(sourceUser).get();
    Recipient recipientTarget     =  Recipient.live(useridTarget.toString()).get();

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

  public SpannableString describeIncomingUpdateDeliveryMode(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand            fenceCommand        = ufsrvCommand.getFenceCommand();
    int                     commandArgs         = fenceCommand.getHeader().getArgs();
    String                  sourceUser          = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    Recipient               recipientSource     =  Recipient.live(sourceUser).get();
    GroupDatabase.DeliveryMode  deliveryMode    = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

    switch (commandArgs)
    {
      case CommandArgs.UPDATED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_updated_by, recipientSource.getDisplayName(), deliveryMode.getDescriptiveName()));

      case CommandArgs.ACCEPTED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_updated_by_you, deliveryMode.getDescriptiveName()));

      case CommandArgs.REJECTED_VALUE:
        return new SpannableString(context.getString(R.string.delivery_mode_request_rejected));

    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming  group MAXMEMBERS event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  public SpannableString describeIncomingLeave(Context context, UfsrvCommandWire ufsrvCommand)
  {
    FenceCommand fenceCommand = ufsrvCommand.getFenceCommand();
    int commandArgs           = fenceCommand.getHeader().getArgs();
    String sourceUser         = GroupV1MessageProcessor.UfsrvUidEncodedForOriginator(fenceCommand);
    boolean isMe              = TextUtils.isEmpty(sourceUser) || sourceUser.equals(UfsrvUid.UndefinedUfsrvUid) || sourceUser.equals(TextSecurePreferences.getUfsrvUserId(context));
    StringBuilder description = new StringBuilder();

    Recipient recipient;
    if (isMe) {
      sourceUser = context.getString(R.string.MessageRecord_you)+" ";
      recipient =  Recipient.self();
    } else {
      recipient =  Recipient.live(sourceUser).get();
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

        recipient =  Recipient.live(targetUser).get();
        return new SpannableString(context.getString(R.string.user_no_longer_on_invited_list, recipient.getDisplayName()));
    }

    return new SpannableString(String.format(Locale.getDefault(), "Unknown incoming group leave event (%d,%d)", fenceCommand.getHeader().getCommand(), commandArgs));
  }

  private @NonNull String getCallDateString(@NonNull Context context) {
    return DateUtils.getSimpleRelativeTimeSpanString(context, Locale.getDefault(), getDateSent());
  }

  private static @NonNull UpdateDescription fromRecipient(@NonNull Recipient recipient,
                                                          @NonNull Function<Recipient, String> stringGenerator,
                                                          @DrawableRes int iconResource)
  {
    return UpdateDescription.mentioning(Collections.singletonList(recipient.getServiceId().orElse(ServiceId.UNKNOWN)),
                                        () -> stringGenerator.apply(recipient.resolve()),
                                        iconResource);
  }

  private static @NonNull UpdateDescription staticUpdateDescription(@NonNull String string,
                                                                    @DrawableRes int iconResource)
  {
    return UpdateDescription.staticDescription(string, iconResource);
  }

}
