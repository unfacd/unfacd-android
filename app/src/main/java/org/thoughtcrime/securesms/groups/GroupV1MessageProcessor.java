package org.thoughtcrime.securesms.groups;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.fence.FencePermissions;
import com.unfacd.android.ufsrvcmd.events.AppEventGroupDestroyed;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;

import net.zetetic.database.sqlcipher.SQLiteConstraintException;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase.InsertResult;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.AvatarGroupsV1DownloadJob;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingGroupUpdateMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup.Type;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupContext;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.reactivex.rxjava3.core.Single;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_INVITE;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_JOIN;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION_REJECTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_SYNCED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_GEO_BASED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_REJECTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LINKJOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LINKJOIN_REJECTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import static org.thoughtcrime.securesms.database.GroupDatabase.MembershipUpdateMode.ADD_MEMBER;
import static org.thoughtcrime.securesms.database.GroupDatabase.MembershipUpdateMode.REMOVE_MEMBER;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

@SuppressLint("NewApi")
public class GroupV1MessageProcessor
{

  private static final String TAG = Log.tag(GroupV1MessageProcessor.class);

  //at this point a Push message will have been stored in PushDatabase about this event, FROM WHICH envelope/ufsrvCommand are sourced
  public static @Nullable
  Long processUfsrvFenceCommand(@NonNull Context context,
                                @NonNull SignalServiceContent content,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                boolean outgoing)
  {
    GroupDatabase  groupDatabase     = SignalDatabase.groups();
    ThreadDatabase threadDatabase    = SignalDatabase.threads();
    FenceRecord    fenceRecord       = null;
    FenceCommand   fenceCommand      = message.getUfsrvCommand().getFenceCommand();

    if (fenceCommand == null) {
      Log.e(TAG, String.format(Locale.getDefault(), "processUfsrvFenceCommand (%d): FenceCommand was null: RETURNING", Thread.currentThread().getId()));
      return -1L;
    }

    if (!(fenceCommand.getFencesCount() > 0 && ((fenceRecord = fenceCommand.getFences(0)) != null))) {
      Log.e(TAG, String.format(Locale.getDefault(), "processUfsrvFenceCommand (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return -1L;
    }

    //fid wouldn't be known if we received incoming join invitation, or join confirmation
    GroupRecord         recordFid     = fenceRecord != null ? groupDatabase.getGroupRecordByFid(fenceRecord.getFid()) : null;
    GroupRecord         recordCname   = fenceRecord != null ? groupDatabase.getGroupByCname(fenceRecord.getCname()) : null;
    GroupRecord         recordUfsrv   = null;
    Long                storedId      = Long.valueOf(-1);

    if      (recordFid != null)   recordUfsrv = recordFid;
    else if (recordCname != null) recordUfsrv = recordCname;


    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        storedId = processFenceCommandJoin(context, envelope, message, recordUfsrv, outgoing);
         break;

      case FenceCommand.CommandTypes.LEAVE_VALUE:
        storedId = processFenceCommandLeave(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.STATE_VALUE:
        storedId = processFenceCommandState(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.FNAME_VALUE:
        storedId = processFenceCommandFenceName(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.DESCRIPTION_VALUE:
        storedId = processFenceCommandFenceDescription(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        storedId = processFenceCommandAvatar(context, envelope, message, recordUfsrv, outgoing);
        break;

      //recieved response to previous request to invite members
      case FenceCommand.CommandTypes.INVITE_VALUE:
        storedId = processFenceCommandInvite(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.INVITE_REJECTED_VALUE:
        storedId = processFenceCommandInviteRejected(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.INVITE_DELETED_VALUE:
        storedId = processFenceCommandInviteDeleted(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.EXPIRY_VALUE:
        storedId = processFenceCommandMessageExpiry(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        storedId = processFenceCommandPermission(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        storedId = processFenceCommandMaxMembers(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.JOIN_MODE_VALUE:
//        storedId = processFenceCommandPermission(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.PRIVACY_MODE_VALUE:
//        storedId = processFenceCommandPermission(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        storedId = processFenceCommandDeliveryMode(context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.LINKJOIN_VALUE:
        storedId = processFenceCommandLinkJoin(context, envelope, message, recordUfsrv, outgoing);
        break;
    }

    if (storedId != null && storedId >= 0) {
      Recipient groupRecipient = Recipient.live(RecipientUfsrvId.from(fenceRecord.getFid())).get();
      if (fenceCommand.getHeader().getEid() > 0)  SignalDatabase.recipients().setEid(groupRecipient, fenceRecord.getEid());
    }

    return storedId;
  }
  //

  /**
   *  Processing block for server's reciept to user's request to invite new members to group
   * @param context
   * @param envelope
   * @param message
   * @param groupRecordUfsrv
   * @param outgoing
   * @return
   */
  private static @Nullable
  Long processFenceCommandInvite(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @Nullable GroupRecord groupRecordUfsrv,
                               boolean outgoing)
  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();
    int commandArg            = fenceCommand.getHeader().getArgs();

    switch (commandArg)
    {
      case CommandArgs.ADDED_VALUE:
      case CommandArgs.DELETED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE UPDATE (ADD||REMOVE): '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteUpdated(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.UNCHANGED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE UNCHANGED: '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteUnchanged(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE ACCEPTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      case CommandArgs.ACCEPTED_PARTIAL_VALUE:
      case CommandArgs.INVITED_GEO_VALUE:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE PARTIAL ACCEPTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      case CommandArgs.REJECTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE REJECTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      default:
        Log.w(TAG, String.format("processFenceCommandJoin (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }

  /**
   *  Processing block for server's invitation rejection issued by a user (that was previously invited)
   * @param context
   * @param envelope
   * @param message
   * @param groupRecordUfsrv
   * @param outgoing
   * @return
   */
  private static @Nullable
  Long processFenceCommandInviteRejected(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecordUfsrv,
                                         boolean outgoing)
  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();
    int commandArg            = fenceCommand.getHeader().getArgs();

    switch (commandArg)
    {
      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInviteRejected (%d): Received ACCEPTED UPDATE (REJECT): '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteRejectAccepted(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.SYNCED_VALUE:
        Log.w(TAG, String.format("processFenceCommandInviteRejected (%d): Received SYNCED UPDATE (REJECT): '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteRejectSynced(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandInviteRejected (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }

  /**
   *  Processing block for a user which was deleted from the invite list by admin, or original
   *  inviter. This is distinct for rejection, which is handled by its own processing block, where the
   *  invitee has initiated the deletion.
   */
  private static @Nullable
  Long processFenceCommandInviteDeleted(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecordUfsrv,
                                         boolean outgoing)
  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();
    int commandArg            = fenceCommand.getHeader().getArgs();

    switch (commandArg)
    {
      case CommandArgs.ACCEPTED_VALUE:
        if (fenceCommand.getHeader().getWhenClient() > 0) {
          Log.d(TAG, String.format("processFenceCommandInviteDeleted (timestamp:'%d', fid:'%d'): Deleting original request msg...", fenceCommand.getHeader().getWhenClient(), fenceCommand.getFences(0).getFid()));
          MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
          if (msg != null) {
            Log.d(TAG, String.format("processFenceCommandInviteDeleted (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
            SignalDatabase.mms().deleteMessage(msg.getId());

            msg.close();
          }
        }

      case CommandArgs.SYNCED_VALUE:
        Log.d(TAG, String.format("processFenceCommandInviteDeleted (%d): Received INVITE SYNCED (ADD||REMOVE): '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteDeletedSynced(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandInviteDeleted (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }

  //AA Main controller for LEAVE command arriving from backend
  private static @Nullable
  Long processFenceCommandLeave(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @Nullable GroupRecord groupRecordUfsrv,
                                boolean outgoing)
  {
    SignalServiceProtos.FenceRecord fenceRecord   = null;
    SignalServiceProtos.FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    String ufsrvUid = UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    if (fenceCommand.getHeader().getArgs() == CommandArgs.REJECTED_VALUE) {
      return processGroupLeaveRejected(context, envelope, message, groupRecordUfsrv);
    } else {
      //this user (as opposed to other members)
      if (TextUtils.isEmpty(ufsrvUid) || ufsrvUid.equals(UfsrvUid.UndefinedUfsrvUid)) {
        return processFenceCommandLeaveAccepted(context, envelope, message, groupRecordUfsrv, outgoing);
      }
      else {
        return processFenceCommandLeaveSynced(context, envelope, message, groupRecordUfsrv, outgoing);
      }
    }

  }

  private static Long processGroupLeaveRejected(@NonNull Context                   context,
                                                @NonNull SignalServiceEnvelope     envelope,
                                                @NonNull SignalServiceDataMessage  message,
                                                @NonNull GroupRecord groupRecordUfsrv)

  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();
    SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
    Log.w(TAG, String.format(Locale.getDefault(), "processGroupLeaveRejected: Received LEAVE COMMAND: '%d', ARGS:'%d' FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));

    switch (fenceCommand.getHeader().getArgsError())
    {
      case FenceCommand.Errors.NOT_MEMBER_VALUE:
        return handleGroupLeaveUfsrv(context, envelope, group, groupRecordUfsrv, false);

      default:
        Log.w(TAG, String.format("processGroupLeaveRejected: Received LEAVE REJECTED ERROR: '%d', ARGS:'%d'FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
    }

    return (long) -1;
  }
  //

  //AA+
  private static @Nullable
  Long processFenceCommandJoin(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @Nullable GroupRecord groupRecordUfsrv,
                               boolean outgoing)
  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    if (fenceCommand.getHeader().getArgs() == CommandArgs.GEO_BASED_VALUE) {
      //Automatic geo based join for this user based on current roaming mode setting
      return processFenceCommandJoinGeobased(context, envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs() == CommandArgs.INVITED_VALUE || fenceCommand.getHeader().getArgs() == CommandArgs.INVITED_GEO_VALUE) {
      //this user recieved invitation to join a group or recived geo-obased invitation because roaming mode is set to 'journaler'
      return processFenceCommandJoinInvited(context,  envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs() == CommandArgs.SYNCED_VALUE) {
      //somebody else joined, sync membership
      return processFenceCommandJoinSync(context, envelope, message, groupRecordUfsrv, outgoing);
    } else //UNCHANGED indicates a rejoin of a group of which  a user is already a member
    if (fenceCommand.getHeader().getArgs() == CommandArgs.ACCEPTED_VALUE || fenceCommand.getHeader().getArgs() == CommandArgs.ACCEPTED_INVITE_VALUE ||fenceCommand.getHeader().getArgs() == CommandArgs.CREATED_VALUE ||fenceCommand.getHeader().getArgs() == CommandArgs.UNCHANGED_VALUE) {
      //server acknowledging a previous request to join, this should be followed by another JOIN/SYNCED message
      return processFenceCommandJoinAccepted(context, envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs() == CommandArgs.REJECTED_VALUE) {
      return processGroupJoinRejected(context, envelope, message, groupRecordUfsrv, false);
    } else
      Log.w(TAG, String.format("processFenceCommandJoin (%d): Received UNKNOWN JOIN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    return (long) -1;

  }
  //

  private static Long processGroupJoinRejected(@NonNull Context                     context,
                                               @NonNull SignalServiceEnvelope     envelope,
                                               @NonNull SignalServiceDataMessage  dataMessage,
                                               @NonNull GroupRecord               groupRecord,
                                                        boolean                   outgoing)
{
  FenceCommand fenceCommand = dataMessage.getUfsrvCommand().getFenceCommand();
  Log.w(TAG, String.format(Locale.getDefault(), "processGroupJoinRejected: Received JOIN REJECTED COMMAND: '%d', ARGS:'%d'FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));

  switch (fenceCommand.getHeader().getArgsError())
  {
    case FenceCommand.Errors.GROUP_DOESNT_EXIST_VALUE:
    return GroupJoinErrorDoesntExist(context, envelope, dataMessage, groupRecord != null? Optional.of(groupRecord):Optional.empty(), false);

    case FenceCommand.Errors.INVITE_ONLY_VALUE:
      return GroupJoinErrorNotOnInviteList(context, envelope, dataMessage, groupRecord != null? Optional.of(groupRecord):Optional.empty(), false);

      //server doesn't have key for fence
    case FenceCommand.Errors.WRONG_KEY_VALUE:
      return GroupJoinErrorKeyError(context, envelope, dataMessage, groupRecord != null? Optional.of(groupRecord):Optional.empty(), false);

    default:
      Log.w(TAG, String.format("processGroupJoinRejected: Received JOIN REJECTED ERROR: '%d', ARGS:'%d'FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
  }

    return (long) -1;
  }


  private static Long GroupJoinErrorDoesntExist(@NonNull Context                  context,
                                                @NonNull SignalServiceEnvelope     envelope,
                                                @NonNull SignalServiceDataMessage  dataMessage,
                                                @NonNull Optional<GroupRecord>     groupRecord,
                                                boolean                            outgoing)
  {
    FenceCommand  fenceCommand  = dataMessage.getUfsrvCommand().getFenceCommand();

    if (groupRecord.isPresent()) {
      Recipient recipient = Recipient.live(groupRecord.get().getRecipientId()).get();

      long threadId = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());

     SignalDatabase.groups().cleanUpGroup(groupRecord.get().getId(), threadId);
    } else {
      long threadId = SignalDatabase.threads().getThreadIdFor(null, fenceCommand.getFences(0).getFid());

      if (threadId > 0) SignalDatabase.groups().cleanUpGroup(null, threadId);
    }

    return (long) -1;
  }

  //currently doesn't inform the user
  private static Long GroupJoinErrorNotOnInviteList(@NonNull Context                    context,
                                                      @NonNull SignalServiceEnvelope     envelope,
                                                      @NonNull SignalServiceDataMessage  dataMessage,
                                                      @NonNull Optional<GroupRecord>     groupRecord,
                                                      boolean                            outgoing)
  {
    FenceCommand  fenceCommand  = dataMessage.getUfsrvCommand().getFenceCommand();

    if (groupRecord.isPresent()) {
      Recipient recipient = Recipient.live(groupRecord.get().getRecipientId()).get();

      long threadId = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());
      Log.w(TAG, String.format("GroupJoinErrorNotOnInviteList (%d): Received NotOnInviteList error: '%d', ARGS:'%d'FOR: fid:'%d': Issuing 'cleanUpGroup'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
     SignalDatabase.groups().cleanUpGroup(groupRecord.get().getId(), threadId);
    }
    else
    {
      Log.w(TAG, String.format("GroupJoinErrorNotOnInviteList (%d): Received NotOnInviteList error NO INTERNAL GROUP RECORD: '%d', ARGS:'%d'FOR: fid:'%d': Issuing 'cleanUpGroup'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));

      long threadId = SignalDatabase.threads().getThreadIdFor(null, fenceCommand.getFences(0).getFid());
      if (threadId > 0)SignalDatabase.groups().cleanUpGroup(null, threadId);
    }

    return (long) -1;
  }

  private static Long GroupJoinErrorKeyError(@NonNull Context                    context,
                                               @NonNull SignalServiceEnvelope     envelope,
                                               @NonNull SignalServiceDataMessage  dataMessage,
                                               @NonNull Optional<GroupRecord>     groupRecord,
                                               boolean                            outgoing)
  {
    FenceCommand  fenceCommand  = dataMessage.getUfsrvCommand().getFenceCommand();

//    if (groupRecord.isPresent()) {
//      Recipient recipient = Recipient.live(groupRecord.get().getRecipientId()).get();
//
//      long threadId = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());
//
//     SignalDatabase.groups().cleanUpGroup(groupRecord.get().getId(), threadId);
//    } else {
//      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(null, fenceCommand.getFences(0).getFid());
//
//      if (threadId > 0)SignalDatabase.groups().cleanUpGroup(null, threadId);
//    }

    UfsrvFenceUtils.sendServerCommandFenceLeave(fenceCommand.getFences(0).getFid());

    return (long) -1;
  }

  private static @Nullable
  Long processFenceCommandPermission(@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @Nullable GroupRecord groupRecordUfsrv,
                                     boolean outgoing)
  {
    long threadId = -1;
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.ADDED_VALUE:
        Log.w(TAG, String.format("processFenceCommandPermission (%d): Received PERMISSION ADDED: '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandPermissionAdded(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.DELETED_VALUE:
        Log.w(TAG, String.format("processFenceCommandPermission (%d): Received PERMISSION DELETED: '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandPermissionDeleted(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandPermission (%d): Received PERMISSION ACCEPTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      case CommandArgs.REJECTED_VALUE:
        return processFenceCommandPermissionRejected(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandPermission (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    }

    if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandPermission (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }

    return Long.valueOf(threadId);

  }


  //A member has been added/removed into group's permission. Could be this user, others. The originator-and-group-owner gets ACCEPTED,
  //and won't be processed in this block
  private static Long
  processFenceCommandPermissionAdded(@NonNull Context                  context,
                                      @NonNull SignalServiceEnvelope    envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull GroupRecord              groupRecord,
                                      boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.live(fenceRecord.getFid()).get();
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    long                    useridTarget  = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());

    return addUserToPermission (context, envelope, message, recipient,
                                FencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                useridTarget, outgoing);
  }

  private static Long
  addUserToPermission(@NonNull Context                     context,
                          @NonNull SignalServiceEnvelope    envelope,
                          @NonNull SignalServiceDataMessage message,
                          @NonNull Recipient                recipient,
                          @NonNull FencePermissions permission,
                          long                     userId,
                          boolean                  outgoing)
  {
    RecipientDatabase databaseRecipientsPrefs = SignalDatabase.recipients();
    databaseRecipientsPrefs.SetGroupPermissionForMember (context, recipient, permission, userId);

    GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);
  }

  private static Long
  removeUserFromPermission(@NonNull Context                 context,
                           @NonNull SignalServiceEnvelope    envelope,
                           @NonNull SignalServiceDataMessage message,
                           @NonNull Recipient                recipient,
                           @NonNull FencePermissions permission,
                                    long                     userId,
                                    boolean                  outgoing)
  {
    RecipientDatabase databaseRecipientsPrefs = SignalDatabase.recipients();
    databaseRecipientsPrefs.DeleteGroupPermissionForMember(context, recipient, permission, userId);

    GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);
  }

  private static Long
  processFenceCommandPermissionDeleted(@NonNull Context                  context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
{
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.live(fenceRecord.getFid()).get();
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if  (permission != null) {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());

      return removeUserFromPermission (context, envelope, message, recipient,
                                       FencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                       useridTarget, outgoing);
    }

    return (long) -1;
  }


  private static Long
  processFenceCommandPermissionRejected(@NonNull Context                  context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.live(fenceRecord.getFid()).get();
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if  (permission != null) {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());
      if (envelope.getFenceCommand().getHeader().getArgsError() == FenceCommand.Errors.PERMISSIONS_VALUE) {//server did not have user on its own list
        //we originally requested to have this user removed. Perhaps our internal view is inconsistent
        if (envelope.getFenceCommand().getHeader().getArgsErrorClient() == CommandArgs.DELETED_VALUE) {
          return removeUserFromPermission(context, envelope, message, recipient,
                                          FencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                          useridTarget, outgoing);
        } else  if (envelope.getFenceCommand().getHeader().getArgsErrorClient() == CommandArgs.ADDED_VALUE) {
          //we originally requested to add user, but user is is already on (in server's view). Perhaps our internal view is inconsistent
          return addUserToPermission(context, envelope, message, recipient,
                                     FencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                     useridTarget, outgoing);
        }
      }
    }

    return (long) -1;
  }


  private static @Nullable
  Long processFenceCommandMaxMembers(@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @Nullable GroupRecord groupRecordUfsrv,
                                     boolean outgoing)
  {
    long threadId = -1;
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandMaxMembers (%d, originator:'%d'): Received MAXNUMBERS UPDATED: FOR fid:'%d'", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(fenceCommand.getOriginator().getUfsrvuid().toByteArray()), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandMaxMembersUpdated(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandMaxMembers (%d): Received PERMISSION ACCEPTED: FOR fid:'%d'", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandMaxMembersUpdated(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.REJECTED_VALUE:
        threadId = processFenceCommandPermissionRejected(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandMaxMembers (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    }

    if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandMaxMembers (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }

    return Long.valueOf(threadId);

  }

  private static Long
  processFenceCommandMaxMembersUpdated (@NonNull Context                  context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
   SignalDatabase.groups().updateGroupMaxMembers(fenceRecord.getFid(), fenceRecord.getMaxmembers());

    GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);

  }


  private static @Nullable
  Long processFenceCommandDeliveryMode(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @Nullable GroupRecord groupRecordUfsrv,
                                       boolean outgoing)
  {
    long threadId = -1;
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandDeliveryMode (%d, originator:'%d'): Received DELIVERYMODE UPDATED: FOR fid:'%d'", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(fenceCommand.getOriginator().getUfsrvuid().toByteArray()), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandDeliveryModeUpdated(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandDeliveryMode (%d): Received DELIVERYMODE: FOR fid:'%d'", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandDeliveryModeUpdated(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.REJECTED_VALUE:
        threadId = processFenceCommandDeliveryModeRejected(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      default:
        Log.w(TAG, String.format("processFenceCommandDeliveryMode (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    }

    if (envelope.getFenceCommand().getHeader().getWhenClient() > 0) {
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandDeliveryMode (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }

    return Long.valueOf(threadId);

  }

  private static Long
  processFenceCommandDeliveryModeUpdated(@NonNull Context                context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
   SignalDatabase.groups().updateGroupDeliveryMode(fenceRecord.getFid(), GroupDatabase.DeliveryMode.values()[fenceRecord.getDeliveryMode().getNumber()]);

    GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);

  }

  private static Long
  processFenceCommandDeliveryModeRejected(@NonNull Context                  context,
                                         @NonNull SignalServiceEnvelope    envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @NonNull GroupRecord              groupRecord,
                                         boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if (permission != null) {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());
      if (envelope.getFenceCommand().getHeader().getArgsError() == FenceCommand.Errors.PERMISSIONS_VALUE) /*server did not have user on its own list*/ {
      }
    }

    return (long) -1;
  }


  private static @Nullable
  Long processFenceCommandLinkJoin(@NonNull Context context,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @Nullable GroupRecord groupRecordUfsrv,
                                   boolean outgoing)
  {
    long threadId = -1;
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.ADDED_VALUE:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandLinkJoin (%d, originator:'%d'): Received LinkJoin ADDED: FOR fid:'%d'", Thread.currentThread().getId(), UfsrvUid.DecodeUfsrvSequenceId(fenceCommand.getOriginator().getUfsrvuid().toByteArray()), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandLinkJoinAdded(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format(Locale.getDefault(),"processFenceCommandLinkJoin (%d): Received LinJoin ACCEPTED BY ADMIN: FOR fid:'%d'", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid()));
        threadId = processFenceCommandLinkJoinAccepted(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      case CommandArgs.REJECTED_VALUE://Authorising admin rejected user request
        threadId = processFenceCommandLinkJoinRejected(context, envelope, message, groupRecordUfsrv, outgoing);
        break;

      default:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandLinkJoin (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    }

    return Long.valueOf(threadId);

  }


  /**
   * User's link join request was passed through by backend and added to group's linkjoin members list, awaiting authorising admin's approval.
   * @param context
   * @param envelope
   * @param message
   * @param groupRecord
   * @param outgoing
   * @return
   */
  private static Long
  processFenceCommandLinkJoinAdded(@NonNull Context                  context,
                                   @NonNull SignalServiceEnvelope    envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @NonNull GroupRecord              groupRecord,
                                   boolean                           outgoing)
  {
    Recipient recipientOriginator = Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(envelope.getFenceCommand().getOriginator().getUfsrvuid().toByteArray())).serialize()).get();
    SignalDatabase.groups().updateLinkJoinMembers(groupRecord.getFid(), new LinkedList<>() {{ add(recipientOriginator.requireAddress()); }}, ADD_MEMBER);

    if (Recipient.self().equals(recipientOriginator)) {
      SignalDatabase.groups().updateCname(groupRecord.getFid(), envelope.getFenceCommand().getFences(0).getCname());
      //The requesting user only gets "Added" back if AUTHORIZATION REQUIRED
      deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());
      GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());
      GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
      return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);
    } else {
      //This user is an authorising member
      GroupId groupId = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());
      GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
      Long id = storeLinkJoinAdminActionMessage(context, envelope, Optional.of(groupId), groupContext);

      Recipient.live(SignalDatabase.groups().getGroupRecipientId(groupRecord.getId()).get()).refresh();

      return id;
    }
  }

  /**
   * User's link join request was accepted by admin for group admission.
   *
   * The logic for originator is inverted: for user being accepted originator is the admin.
   * @param context
   * @param envelope
   * @param message
   * @param groupRecord
   * @param outgoing
   * @return
   */
  private static Long
  processFenceCommandLinkJoinAccepted(@NonNull Context                  context,
                                      @NonNull SignalServiceEnvelope    envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull GroupRecord              groupRecord,
                                      boolean                           outgoing)
  {
    boolean hasOriginator = envelope.getFenceCommand().hasOriginator();
    GroupId     groupId   = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    long meessagId =  storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);
    Recipient recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(envelope.getUfsrvCommand().getFenceCommand().getAuthoriser());

    //this user has been notified that their previous linkjoin request was accepted, so must join withing set time, otherwise they'll have to be accepted again
    if (!hasOriginator) {
      SignalDatabase.groups().updateLinkJoinMembers(groupRecord.getFid(), new LinkedList<>() {{ add(Recipient.self().requireAddress()); }}, REMOVE_MEMBER);
      MessageSender.sendFenceCommandJoinAcceptedLinkJoin(context, groupId, groupRecord);
      SignalDatabase.groups().markGroupMode(groupRecord.getFid(), GROUP_MODE_LINKJOIN_ACCEPTED);
    } else {
      //this is an authorising member and is being notified of a user admitted by another admin
      Recipient   recipientOriginator = Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(envelope.getFenceCommand().getOriginator().getUfsrvuid().toByteArray())).serialize()).get();
      deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());
      SignalDatabase.groups().updateLinkJoinMembers(groupRecord.getFid(), new LinkedList<>() {{ add(recipientOriginator.requireAddress()); }}, REMOVE_MEMBER);
    }

    Recipient.live(SignalDatabase.groups().getGroupRecipientId(groupId).get()).refresh();

    return Long.valueOf(meessagId);

  }

  private static Long
  processFenceCommandLinkJoinRejected(@NonNull Context                context,
                                      @NonNull SignalServiceEnvelope    envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull GroupRecord              groupRecord,
                                      boolean                           outgoing)
  {
    boolean hasOriginator = envelope.getFenceCommand().hasOriginator();
    GroupId     groupId             = GroupId.v2(message.getGroupContext().get().getGroupV2().get().getMasterKey());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    long meessagId =  storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);
    Recipient recipientAuthoriser = UfsrvFenceUtils.recipientFromUserRecord(envelope.getUfsrvCommand().getFenceCommand().getAuthoriser());

    if (!hasOriginator) {
      SignalDatabase.groups().updateLinkJoinMembers(groupRecord.getFid(), new LinkedList<Address>() {{ add(Recipient.self().requireAddress()); }}, REMOVE_MEMBER);
      SignalDatabase.groups().markGroupMode(groupRecord.getFid(), GROUP_MODE_LINKJOIN_REJECTED);
      SignalDatabase.groups().setActive(groupId, false);
    } else {
      //this is an authorising member and is being notified of a user admitted by another admin
      Recipient   recipientOriginator = Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(envelope.getFenceCommand().getOriginator().getUfsrvuid().toByteArray())).serialize()).get();
      deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());
      SignalDatabase.groups().updateLinkJoinMembers(groupRecord.getFid(), new LinkedList<Address>() {{ add(recipientOriginator.requireAddress()); }}, REMOVE_MEMBER);
    }

    Recipient.live(SignalDatabase.groups().getGroupRecipientId(groupId).get()).refresh();

    return Long.valueOf(meessagId);

  }

  //AA+
  /**
   * A StateSync command for a given fence. default action is toupdate group's details
   * @param context
   * @param envelope
   * @param message
   * @param groupRecordUfsrv if available, reflects the group record as stored in groupDatabase
   * @param outgoing
   * @return
   */
  private static @Nullable
  Long processFenceCommandState(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @Nullable GroupRecord groupRecordUfsrv,
                                boolean outgoing)
  {
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format(Locale. getDefault(), "processFenceCommandState (%d): Received Fence State Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.SYNCED_VALUE:
        return processFenceCommandStateSynced(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.RESYNC_VALUE:
        return processFenceCommandStateResyncRequest(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format(Locale. getDefault(), "processFenceCommandState (%d): Received UNKNOWN FENCE STATE COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }
  //

  private static @Nullable
  Long processFenceCommandFenceName(@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @Nullable GroupRecord groupRecordUfsrv,
                                    boolean outgoing)
  {
    SignalServiceGroupV2 group      = message.getGroupContext().get().getGroupV2().get();
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format("processFenceCommandFeneName (%d): Received FenceName Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      //updated by othres
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandFeneName (%d): Received FENCE NAME UPDATED COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fname:'%s', fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFname(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
       return handleGroupNameUpdateUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);

      //this user's request has been accepted
      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandFeneName (%d): Received FENCE NAME ACCEPTED COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fname:'%s', fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFname(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleGroupNameUpdateUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);

      case CommandArgs.REJECTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandFeneName (%d): Received FENCE NAME REJECTED COMMAND ARGS: ARGS:'%d'FOR: fname_returned:'%s', fid:'%d'. args_error:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFname(), fenceCommand.getFences(0).getFid(), fenceCommand.getHeader().getArgsError()));
        return handleGroupNameRejectionUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandFeneName (%d): Received UNKNOWN FENCE NAME COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }

  private static @Nullable
  Long processFenceCommandFenceDescription(@NonNull Context context,
                                           @NonNull SignalServiceEnvelope envelope,
                                           @NonNull SignalServiceDataMessage message,
                                           @NonNull GroupRecord groupRecordUfsrv,
                                           boolean outgoing)
  {
    SignalServiceGroupV2 group      = message.getGroupContext().get().getGroupV2().get();
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format(Locale.getDefault(), "processFenceCommandFenceDescription (%d): (ARGS:'%d'): FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid()));

    switch (fenceCommand.getHeader().getArgs())
    {
      //updated by othres
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandFenceDescription (%d): Received FENCE DESCRIPTION UPDATED fid:'%d'", Thread.currentThread().getId(),fenceCommand.getFences(0).getFid()));
        return handleGroupDescriptionUpdate(context, envelope, group, groupRecordUfsrv, outgoing);

      //this user's request has been accepted
      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandFenceDescription (%d): Received FENCE DESCRIPTION ACCEPTED fid:'%d'", Thread.currentThread().getId(),fenceCommand.getFences(0).getFid()));
        return handleGroupDescriptionUpdate(context, envelope, group, groupRecordUfsrv, outgoing);

      case CommandArgs.REJECTED_VALUE:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandFenceDescription (%d): Received FENCE DESCRIPTION REJECTED fid:'%d'. args_error:'%d'", Thread.currentThread().getId(),fenceCommand.getFences(0).getFid(), fenceCommand.getHeader().getArgsError()));
//        return handleGroupDescriptionRejectionUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format(Locale.getDefault(), "processFenceCommandFenceDescription (%d): Received UNKNOWN FENCE NAME COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
    }

    return (long) -1;

  }

  private static @Nullable
  Long processFenceCommandAvatar(@NonNull Context context,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @Nullable GroupRecord groupRecord,
                                 boolean outgoing)
  {
    SignalServiceGroupV2 group    = message.getGroupContext().get().getGroupV2().get();
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format("processFenceCommandAvatar (%d): Received Fence State Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received UPDTED FENCE AVATAR COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleGroupAvatarUpdateUfsrv(context, envelope, group, groupRecord, outgoing);

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received ACCEPTED FENCE AVATAR COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleGroupAvatarUpdateUfsrv(context,  envelope, group, groupRecord, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received UNKNOWN Avatar COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }


  //change in group invite membership: someone has been attached or removed..
  private static @Nullable Long
  processFenceCommandInviteUpdated(@NonNull Context context,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @Nullable GroupRecord groupRecordUfsrv,
                                   boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();

      return UpdateFenceInvitedMembership(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandInviteUpdated (%d): Received SERVER INVITE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  /**groupDatabase.markGroupMode(fenceRecord.getFid(), GROUP_MODE_LEAVE_ACCEPTED);
   * A notification for a user who rejected an invitation
   * @return
   */
  private static @Nullable Long
  processFenceCommandInviteRejectAccepted(@NonNull Context context,
                                          @NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceDataMessage message,
                                          @Nullable GroupRecord groupRecord,
                                          boolean outgoing)
  {
    FenceCommand                        fenceCommand  = envelope.getFenceCommand();
    FenceRecord                         fenceRecord   = fenceCommand.getFences(0);

    if (groupRecord != null) {
      GroupDatabase.MembershipUpdateMode  updateMode    = REMOVE_MEMBER;
      GroupDatabase database = SignalDatabase.groups();

      List<Address> meRemoved = new LinkedList<Address>() {{add(Recipient.self().requireAddress());}};

      if (fenceRecord.getInvitedMembersCount() > 0) {
        database.updateInvitedMembers(groupRecord.getFid(), meRemoved, updateMode);
      }

      database.markGroupMode(fenceRecord.getFid(), GROUP_MODE_INVITATION_REJECTED);

      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
      GroupId groupId = GroupId.v2(group.getMasterKey());
      GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId.getDecodedId());

      //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
      builderGroupContext.setFenceMessage(envelope.getFenceCommand());

      if (fenceCommand.getHeader().getWhenClient() > 0) {
        Log.d(TAG, String.format("processFenceCommandInviteRejectAccepted (timestamp:'%d', fid:'%d'): Deleting original request msg...", fenceCommand.getHeader().getWhenClient(), fenceCommand.getFences(0).getFid()));
        MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
        if (msg != null) {
          Log.d(TAG, String.format("processFenceCommandInviteRejectAccepted (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
          SignalDatabase.mms().deleteMessage(msg.getId());

          msg.close();
        }
      }

      database.setActive(groupId, false);

      return storeMessage(context, envelope, Optional.of(groupId), builderGroupContext.build(), outgoing);

    } else {
      Log.e(TAG, String.format("processFenceCommandInviteRejectAccepted (%d): Received SERVER INVITE REJECT ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }


  /**
   * A notification for a user who rejected an invitation
   * @return
   */
  private static @Nullable Long
  processFenceCommandInviteRejectSynced(@NonNull Context context,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @Nullable GroupRecord groupRecord,
                                        boolean outgoing)
  {
    FenceCommand                        fenceCommand  = envelope.getFenceCommand();
    FenceRecord                         fenceRecord   = fenceCommand.getFences(0);

    if (groupRecord != null) {
      GroupDatabase.MembershipUpdateMode  updateMode    = REMOVE_MEMBER;
      updateGroupInviteMembership(context, groupRecord, fenceRecord, updateMode);

      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
      GroupId groupId = GroupId.v2(group.getMasterKey());
      GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId.getDecodedId());

      //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
      builderGroupContext.setFenceMessage(envelope.getFenceCommand());

      return storeMessage(context, envelope, Optional.of(groupId), builderGroupContext.build(), outgoing);

    } else {
      Log.e(TAG, String.format("processFenceCommandInviteRejectSynced (%d): Received SERVER INVITE REJECT SYNC REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  /** A processing block for a an invite deletion msg. User is deleted by other members. Self deletion
   * is known as rejection.
   * @warning Currently, only processes teh first user on the invite list
   * @return
   */
  private static @Nullable Long
  processFenceCommandInviteDeletedSynced(@NonNull Context context,
                                          @NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceDataMessage message,
                                          @Nullable GroupRecord groupRecord,
                                          boolean outgoing)
  {
    FenceCommand fenceCommand        = envelope.getFenceCommand();
    FenceRecord  fenceRecord         = fenceCommand.getFences(0);
    Recipient    recipientUninvited  = Recipient.live(new UfsrvUid(fenceCommand.getFences(0).getInvitedMembers(0).getUfsrvuid().toByteArray()).toString()).get();
    Recipient    recipientOriginator = UfsrvFenceUtils.recipientFromFenceCommandOriginator(fenceCommand, false);


    if (groupRecord != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
      GroupId groupId = GroupId.v2(group.getMasterKey());

      if (recipientUninvited.equals(Recipient.self())) {
        GroupDatabase.MembershipUpdateMode  updateMode    = REMOVE_MEMBER;
        GroupDatabase database = SignalDatabase.groups();

        List<Address> meRemoved = Collections.singletonList(Recipient.self().requireAddress());

        if (fenceRecord.getInvitedMembersCount() > 0) {
          database.updateInvitedMembers(groupRecord.getFid(), meRemoved, updateMode);
        }

        database.markGroupMode(fenceRecord.getFid(), GROUP_MODE_INVITATION_REJECTED);
        database.setActive(groupId, false);
      } else {
        //NOOP
      }

      GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId.getDecodedId());

      //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
      builderGroupContext.setFenceMessage(envelope.getFenceCommand());

      return storeMessage(context, envelope, Optional.of(groupId), builderGroupContext.build(), outgoing);

    } else {
      Log.e(TAG, String.format("processFenceCommandInviteDeletedSynced (%d): Received SERVER INVITE DELETE REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  private static @Nullable Long
  processFenceCommandInviteUnchanged(@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @Nullable GroupRecord groupRecordUfsrv,
                                    boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();

      return UpdateFenceInvitedMembership(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandInviteUpdated (%d): Received SERVER INVITE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  private static @Nullable
  Long processFenceCommandJoinGeobased(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @Nullable GroupRecord groupRecordUfsrv,
                                      boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format("processFenceCommandJoinGeobased (%d): Received SERVER GEO-BASED JOIN REQUEST'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));

    if (groupRecordUfsrv == null) {
      SignalServiceGroupV2 group              = message.getGroupContext().get().getGroupV2().get();
      GroupDatabase.PrivacyMode privacyMode   = GroupDatabase.PrivacyMode.values()[fenceCommand.getFences(0).getPrivacyMode().getNumber()];
      GroupDatabase.DeliveryMode deliveryMode = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

      return handleFenceCreateUfsrv(context, envelope, message.getGroupContext().get().getGroupV2().orElse(null),
              false, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED,
              GroupDatabase.GroupType.GEO, privacyMode, deliveryMode);
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d): Received SERVER GEO-BASED JOIN REQUEST for EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
      ThreadDatabase  threadDatabase  = SignalDatabase.threads();
      long            threadId        = threadDatabase.getThreadIdFor(null, fenceCommand.getFences(0).getFid());

      if (threadId == -1) {
        Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d, fid:'%d'): ERROR: DATA INTEGRITY: Received SERVER JOIN GEO-BASED REQUEST for EXISTING GROUP': '%s' BUT NO CORRESPONDING Thread WAS FOUND : creating one", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleFencePartialCreateUfsrv(context, envelope, message.getGroupContext().get().getGroupV2().orElse(null), false, GroupDatabase.GROUP_MODE_GEOBASED_JOIN);
      } else {
        Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d, fid:'%d'): Received SERVER GEO-BASED JOIN REQUEST for EXISTING GROUP': '%s' WITH ThreadId: '%d': creating one", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname(), threadId));
        return handleFenceRejoinExistingUfsrv(context, envelope, message.getGroupContext().get().getGroupV2().orElse(null), false, GroupDatabase.GROUP_MODE_GEOBASED_JOIN, threadId);
      }
    }

  }

  private static @Nullable
  Long processFenceCommandJoinInvited(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @Nullable GroupRecord groupRecordUfsrv,
                                      boolean outgoing)
  {
    FenceCommand fenceCommand                 = envelope.getUfsrvCommand().getFenceCommand();
    SignalServiceGroupV2 group                = message.getGroupContext().isPresent()? message.getGroupContext().get().getGroupV2().orElse(null) : null;

    GroupDatabase.GroupType groupType       = GroupDatabase.GroupType.values()[fenceCommand.getFences(0).getFenceType().getNumber()];
    GroupDatabase.PrivacyMode privacyMode   = GroupDatabase.PrivacyMode.values()[fenceCommand.getFences(0).getPrivacyMode().getNumber()];
    GroupDatabase.DeliveryMode deliveryMode = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

    Log.d(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED JOIN REQUEST'. Fence name:'%s', fence_type:'%d', pivacy_mode:'%d', delivery_mode:'%d'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupType.getValue(), privacyMode.getValue(), deliveryMode.getValue()));

    if (groupRecordUfsrv == null) {
      return handleFenceCreateUfsrv(context, envelope, group, false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else if (!groupRecordUfsrv.isActive()) {
      Log.w(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING INACTIVE GROUP': '%s': REACTIVATING...", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));

      //todo: this may not be a desirable effect, as we nuke everything out of laziness. perhaps just "reactivate" so prior thread data is preserved
      long threadId = SignalDatabase.threads().getThreadIdFor(null, groupRecordUfsrv.getFid()<=0?envelope.getFenceCommand().getFences(0).getFid():groupRecordUfsrv.getFid());
     SignalDatabase.groups().cleanUpGroup(groupRecordUfsrv.getId(), threadId);

      return handleFenceCreateUfsrv(context, envelope, group, false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else if (groupRecordUfsrv.getMode() == GROUP_MODE_INVITATION || groupRecordUfsrv.getMode() == GROUP_MODE_GEOBASED_INVITE) {
      Log.w(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING GROUP': '%s'. isActive:'%b': SYNCING", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));
      long threadId = SignalDatabase.threads().getThreadIdFor(null, groupRecordUfsrv.getFid() <= 0? envelope.getFenceCommand().getFences(0).getFid():groupRecordUfsrv.getFid());
     SignalDatabase.groups().cleanUpGroup(groupRecordUfsrv.getId(), threadId);

      return handleFenceCreateUfsrv(context, envelope, group, false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else {
      //todo: check if already member of the invite list, since we have this group on as active it must be doing something for the user, could be inconsistent state
      Log.e(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING GROUP': '%s'. isActive:'%b': IGNORING", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));
    }

    return (long) -1;
  }


  //The server accepted a previous join request from this user, based on self-initiated or we accepted a prior invitation
  //For newly created group, we get CREATED as opposed to ACCEPTED
  private static @Nullable
  Long processFenceCommandJoinAccepted(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @Nullable GroupRecord groupRecordUfsrv,
                                       boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group  = message.getGroupContext().get().getGroupV2().get();
      GroupDatabase groupDatabase = SignalDatabase.groups();

      if (groupDatabase.isGroupInvitationPending(fenceCommand.getFences(0).getFid())) groupDatabase.markGroupMode(fenceCommand.getFences(0).getFid(), GROUP_MODE_INVITATION_JOIN_ACCEPTED);

      long messageId = handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
      if (messageId > 0 && (envelope.getFenceCommand().getHeader().getWhenClient() > 0)) {
        Log.d(TAG, String.format("processFenceCommandJoinAccepted (timestamp:'%d', fid:'%d', cname:'%s'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
        if (msg != null) {
          Log.d(TAG, String.format("processFenceCommandJoinAccepted (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
          SignalDatabase.mms().deleteMessage(msg.getId());

          msg.close();
        }
      }
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinAccepted (%d): Received SERVER ACCEPTED JOIN REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }


  //change in group membership: someone joined.... This is also sent to this user to confirm fence configuration In this case the originator is null, because it is a system message
  private static @Nullable
  Long processFenceCommandJoinSync(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @Nullable GroupRecord groupRecordUfsrv,
                                       boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();

      return handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinSync (%d): Received SERVER JOIN SYNC REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  //change in group membership: this user left...
  private static @Nullable
  Long processFenceCommandLeaveAccepted(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecordUfsrv,
                                         boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupContext    signalServiceGroupContext = message.getGroupContext().get();
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();

      return handleGroupLeaveUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandLeaveAccepted (%d): Received SERVER  LEAVE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  //change in group membership: someone left...
  private static @Nullable
  Long processFenceCommandLeaveSynced(@NonNull Context context,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @Nullable GroupRecord groupRecordUfsrv,
                                        boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
      Log.d(TAG, String.format("processFenceCommandLeaveSynced (%d): Member '%s' is LEAVING Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getOriginator().getUsername(), fenceCommand.getFences(0).getCname()));
      return handleGroupLeaveUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandLeaveSynced (%d): Received SERVER  LEAVE SYNC REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  //change in group membership: someone I left...
  private static @Nullable
  Long processFenceCommandStateSynced(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @Nullable GroupRecord groupRecordUfsrv,
                                      boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format(Locale.getDefault(), "processFenceCommandStateSynced (%d): Received SERVER FENCE STATE SYNCED'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));

    if (groupRecordUfsrv != null) {
      SignalServiceGroupV2 group = message.getGroupContext().get().getGroupV2().get();
     return handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format(Locale.getDefault(), "processFenceCommandStateSynced (%d): Received SERVER FENCE STATE SYNCED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;
  }

  private static @Nullable
  Long processFenceCommandStateResyncRequest(@NonNull Context context,
                                              @NonNull SignalServiceEnvelope envelope,
                                              @NonNull SignalServiceDataMessage message,
                                              @Nullable GroupRecord groupRecordUfsrv,
                                              boolean outgoing)
  {
    FenceCommand fenceCommand = envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format(Locale.getDefault(), "processFenceCommandStateResyncRequest (%d, fid:'%d'): Received SERVER FENCE STATE RESYNC REQUEST'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    return  UfsrvFenceUtils.sendStateSyncForGroup(fenceCommand.getFences(0).getFid());

  }

  private static @Nullable
  Long processFenceCommandMessageExpiry(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecord,
                                         boolean outgoing)
  {
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format("processFenceCommandMessageExpiry (%d): Received Fence State Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandMessageExpiry (%d): Received UPDTED FENCE EXPIRY COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleMessageExpiryUpdate (context, envelope, message, groupRecord, outgoing);

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandMessageExpiry (%d): Received ACCEPTED FENCE EXPIRY COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        //todo: currenly expiry  is set prior to ufsrv confirmation. Enable when when ready to only set upon receipt of this message
        return handleMessageExpiryUpdate (context, envelope, message, groupRecord, outgoing);

      case CommandArgs.REJECTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandMessageExpiry (%d): Received REJECTED FENCE EXPIRY COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleMessageExpiryUpdate (context, envelope, message, groupRecord, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandMessageExpiry (%d): Received UNKNOWN FENCE EXPIRY COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return (long) -1;

  }


  //if the originator is other than this user, SignalServiceGroup will be hoax because
  // the server doesnt set that. Hence why we need to check for internal id
  //header.args==COMMAND_ARGS__ACCEPTED if leave was self initiate for self, otherwise indicated as COMMAND_ARGS__SYNCED for others
  //other possible values: COMMAND_ARGS__GEOBASED, BANNED, INVLIDTED etc...
  //
  private static Long handleGroupLeaveUfsrv(@NonNull Context               context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceGroupV2  group,
                                            @NonNull GroupRecord           record,
                                            boolean  outgoing)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    GroupId groupId = GroupId.v2(group.getMasterKey());
    List<Address>  members      = record.getMembers();

    FenceRecord fenceRecord       = envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder  = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.QUIT);
    final String ufsrvUid;

    if (!envelope.getFenceCommand().hasOriginator())  ufsrvUid = TextSecurePreferences.getUfsrvUserId(context);
    else ufsrvUid = UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    if (groupId == null) groupId = groupDatabase.getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), false).requireV1();

    final Address ufsrvUidAddress = Address.fromSerialized(ufsrvUid);
    final int groupLeaveMode      = groupDatabase.getGroupMode(fenceRecord.getFid());

    switch (envelope.getFenceCommand().getHeader().getArgs())
    {
      case CommandArgs.ACCEPTED_VALUE:
        groupDatabase.markGroupMode(fenceRecord.getFid(), GROUP_MODE_LEAVE_ACCEPTED);
        break;
      case CommandArgs.GEO_BASED_VALUE:
        groupDatabase.markGroupMode(fenceRecord.getFid(), GROUP_MODE_LEAVE_GEO_BASED);
        break;
      case CommandArgs.UNINVITED_VALUE:
        return handleGroupLeaveUninvited(context, envelope, group, record, outgoing);
      case CommandArgs.REJECTED_VALUE:
        groupDatabase.markGroupMode(fenceRecord.getFid(), GROUP_MODE_LEAVE_REJECTED);
    }

    if (envelope.getFenceCommand().getHeader().getWhenClient() > 0) {
      Log.d(TAG, String.format("handleGroupLeaveUfsrv (timestamp:'%d', fid:'%d'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceRecord.getFid()));
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("handleGroupLeaveUfsrv (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceRecord.getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());
        msg.close();
      }
    }

    long threadId = storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);

    Address addressLeaving = Stream.of(members)
                                   .filter(x -> x.compareTo(ufsrvUidAddress) == 0)
                                   .findFirst()
                                   .orElse(null);
   //todo this block below will only work momentarily until stored message above is processed by MessageRecord.describeIncomingLeave() which will cause the input panel to reappear. (it works correctly if the group is viewed freshly from "groups left" tap.
    if (addressLeaving != null) {
      groupDatabase.remove(groupId, Address.fromSerialized(ufsrvUid));
      if (outgoing) groupDatabase.setActive(groupId, false);

      if (ufsrvUid.equals(TextSecurePreferences.getUfsrvUserId(context))) {
        groupDatabase.setActive(groupId, false);
      }
    } else {
      Log.e(TAG, String.format("handleGroupLeaveUfsrv: ERROR: DATA INTEGRITY {fid:'%d', source:'%s'}: SOURCE WAS NOT ON THE GROUP MEMBERS LIST: DELTEING ANYWAY", fenceRecord.getFid(),  ufsrvUid));
      groupDatabase.setActive(groupId, false);
    }

    if (groupLeaveMode == GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP) {
      groupDatabase.cleanUpGroup(groupId, threadId);
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventGroupDestroyed(groupId.toString()));
    }

    return threadId;

  }
  //

  private static Long
  handleGroupLeaveUninvited(@NonNull Context               context,
                            @NonNull SignalServiceEnvelope envelope,
                            @NonNull SignalServiceGroupV2    group,
                            @NonNull GroupRecord           record,
                            boolean  outgoing)
  {
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    GroupId         groupId         = GroupId.v2(group.getMasterKey());
    List<Address>   membersInvited  = record.getMembersInvited();

    //AA+
    FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    UserRecord  userRecord  = fenceRecord.getInvitedMembers(0);

    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.QUIT);//computes group id

    String originator;
    if (!envelope.getFenceCommand().hasOriginator())  originator = TextSecurePreferences.getUfsrvUserId(context);
    else originator = UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    Log.e(TAG, String.format("handleGroupLeaveUninvited: RECEIVED UNINVITE {fid:'%d', originator:'%s', uninvited_uid:'%d'}:", fenceRecord.getFid(), originator, UfsrvUid.DecodeUfsrvSequenceId(userRecord.getUfsrvuid().toByteArray())));

    String invited = userRecord.hasUfsrvuid()?UfsrvUid.EncodedfromSerialisedBytes(userRecord.getUfsrvuid().toByteArray()):"";

    if (membersInvited.contains(invited)) {
      List<Address> usersAffeted = UserRecordsToAddressList(fenceRecord.getInvitedMembersList(),  Optional.empty());

      if (fenceRecord.getInvitedMembersCount() > 0) {
        groupDatabase.updateInvitedMembers(fenceRecord.getFid(), usersAffeted, REMOVE_MEMBER);
        if (TextSecurePreferences.getUfsrvUserId(context).equals(invited)) {
          //delete group/thread
          long threadId = SignalDatabase.threads().getThreadIdFor(null, fenceRecord.getFid());
          groupDatabase.cleanUpGroup(groupId, threadId);
        } else {
          //store message and update Messagerecord
          return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);
        }
      }
    } else {
      Log.e(TAG, String.format("handleGroupLeaveUninvited: ERROR: DATA INTEGRITY {fid:'%d', originator:'%s', uninvited_uid:'%d'}: uninvited user not on list", fenceRecord.getFid(), originator, UfsrvUid.DecodeUfsrvSequenceId(userRecord.getUfsrvuid().toByteArray())));
    }

    return (long) -1; //no msg stored for this event
  }

  private static Long handleGroupNameUpdateUfsrv(@NonNull Context               context,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SignalServiceGroupV2    group,
                                                 @NonNull GroupRecord           groupRecord,
                                                 boolean  outgoing)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    GroupId       groupId       = GroupId.v2(group.getMasterKey());

    FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE);

    if (!TextUtils.isEmpty(fenceRecord.getFname()) && !groupRecord.getTitle().equals(fenceRecord.getFname())) {
      Log.e(TAG, String.format("handleGroupNameUpdateUfsrv (fid:'%d', FenceRecord.name:'%s', GroupDatabase.name:'%s'): Updating fence name", fenceRecord.getFid(), fenceRecord.getFname(), groupRecord.getTitle()));
      groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
      String cnameUpdated = UfsrvFenceUtils.updateCnameWithNewFname (fenceRecord.getFid(), groupRecord.getCname(), groupRecord.getTitle(), fenceRecord.getFname());
      groupDatabase.updateCname(fenceRecord.getFid(), cnameUpdated);
    }

    deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);

  }

  private static Long handleGroupNameRejectionUfsrv(@NonNull Context                  context,
                                                     @NonNull SignalServiceEnvelope   envelope,
                                                     @NonNull SignalServiceGroupV2    group,
                                                     @NonNull GroupRecord             groupRecord,
                                                     boolean  outgoing)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    GroupId groupId = GroupId.v2(group.getMasterKey());

    FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE);

    groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
    if (fenceRecord.getCname() != null) groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

    deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);

  }

  private static Long handleGroupDescriptionUpdate(@NonNull Context               context,
                                                   @NonNull SignalServiceEnvelope envelope,
                                                   @NonNull SignalServiceGroupV2    group,
                                                   @NonNull GroupRecord           groupRecord,
                                                   boolean  outgoing)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    GroupId       groupId       = GroupId.v2(group.getMasterKey());

    FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE);

    if (TextUtils.isEmpty(fenceRecord.getDescription())) {
      GroupDatabase.GroupDataDescriptor.setGroupDescriptorProperty(groupId, GroupDatabase.GroupDataDescriptor.AttributesNames.DESCRIPTION, "");
    }
    GroupDatabase.GroupDataDescriptor.setGroupDescriptorProperty(groupId, GroupDatabase.GroupDataDescriptor.AttributesNames.DESCRIPTION, fenceRecord.getDescription());

    deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);

  }

  private static Long handleGroupAvatarUpdateUfsrv(@NonNull Context               context,
                                                 @NonNull SignalServiceEnvelope   envelope,
                                                 @NonNull SignalServiceGroupV2    group,
                                                 @NonNull GroupRecord             groupRecord,
                                                 boolean  outgoing)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    GroupId       groupId       = GroupId.v2(group.getMasterKey());
    FenceRecord   fenceRecord   = envelope.getUfsrvCommand().getFenceCommand().getFences(0);

    GroupContext groupContext   = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();

    if (fenceRecord.hasAvatar()) {
      SignalServiceProtos.AttachmentRecord attachmentRecord = fenceRecord.getAvatar();
      SignalServiceAttachment avatar = new SignalServiceAttachmentPointer(attachmentRecord.getId(),
                                                                          0,
                                                                          attachmentRecord.getContentType(),
                                                                          attachmentRecord.getKey().toByteArray(),
                                                                          attachmentRecord.hasDigest() ? Optional.of(attachmentRecord.getDigest().toByteArray()) : Optional.<byte[]>empty(),
                                                                          attachmentRecord.hasFileName()? Optional.of(attachmentRecord.getFileName()):Optional.empty(),
                                                                          false,
                                                                          (attachmentRecord.getFlags() & SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE) != 0,
                                                                          false);

      groupDatabase.update(groupId, null, avatar.asPointer());

      Log.e(TAG, String.format("handleGroupAvatarUpdateUfsrv (fid:'%d', id:'%s): Updating avatar", fenceRecord.getFid(), avatar.asPointer().getUfId()));

      deletePreviousRequestMessage(envelope.getFenceCommand().getHeader().getWhenClient());

      return storeMessage(context, envelope, Optional.of(groupId), groupContext, outgoing);

    }

    return (long) -1;
  }


  /**
   * Purge a previously sent request record from relevant storage (typically to reduce noise control messages in conversation windows
   *
   * @param timestamp timestamp identifier of message
   */
  private static void deletePreviousRequestMessage(long timestamp)
  {
    if (timestamp > 0) {
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(timestamp, false);
      if (msg != null) {
        Log.d(TAG, String.format("deletePreviousRequestMessage (timestamp:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", timestamp, msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }
  }

  //based on private void handleExpirationUpdate(@NonNull MasterSecretUnion masterSecret,
//  @NonNull SignalServiceEnvelope envelope,
//  @NonNull SignalServiceDataMessage message,
//  @NonNull Optional<Long> smsMessageId)
  private static Long handleMessageExpiryUpdate(@NonNull Context                  context,
                                                @NonNull SignalServiceEnvelope    envelope,
                                                @NonNull SignalServiceDataMessage message,
                                                @NonNull GroupRecord              groupRecord,
                                                         boolean                  outgoing)
  {
    FenceCommand        fenceCommand  = envelope.getFenceCommand();
    FenceRecord         fenceRecord   = envelope.getFenceCommand().getFences(0);
    MessageDatabase     database      = SignalDatabase.mms();
    Recipient           recipient    =  Recipient.live(fenceRecord.getFid()).get();

    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Recipient.live(envelope.getSourceIdentifier()).get().getId(),
                                                                 message.getTimestamp(),
                                                                 envelope.getServerReceivedTimestamp(),
                                                                 message.getTimestamp(),
                                                                 StoryType.NONE,
                                                                 null,
                                                                 false,
                                                                 UfsrvCommandWire.UfsrvType.UFSRV_FENCE_VALUE,
                                                                 fenceRecord.getExpireTimer(),
                                                                 true, //in millisec
                                                                 false,
                                                                 false,
                                                                 Optional.empty(), message.getGroupContext(),
                                                                 Optional.empty(),
                                                                 Optional.empty(),
                                                                 Optional.empty(),
                                                                 Optional.empty(),
                                                                 Optional.empty(),
                                                                 Optional.empty(),
                                                                 envelope.getServerGuid(),
                                                                 fenceCommand.getHeader().getGid(),//AA+
                                                                 fenceCommand.getHeader().getEid(),
                                                                 fenceCommand.getFences(0).getFid(),
                                                                 0,
                                                                 fenceCommand.getHeader().getCommand(),
                                                                 fenceCommand.getHeader().getArgs(),
                                                                 envelope.getUfsrvCommand());
    try {
      Optional<MessageDatabase.InsertResult> result = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);
      if (result.isPresent()) {
        SignalDatabase.recipients().setExpireMessages(recipient.getId(), (int)fenceRecord.getExpireTimer() / 1000);//in seconds

        if (envelope.getFenceCommand().getHeader().getWhenClient() > 0) {
          MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
          if (msg != null) {
            Log.d(TAG, String.format("handleMessageExpiryUpdate (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceRecord.getFid(), msg.getId()));
            SignalDatabase.mms().deleteMessage(msg.getId());

            msg.close();
          }
        }

        return result.get().getMessageId();
      }
    } catch (MmsException x) {
      Log.d(TAG, x.getMessage());
    }

    return (long) -1;
  }
  //

  /**
   *  This reflects a group for which there is a reference in the GroupDatabase, but not in the ThreadDatabase.
   */
  private static @Nullable Long handleFencePartialCreateUfsrv(@NonNull Context context,
                                                       @NonNull SignalServiceEnvelope envelope,
                                                       @NonNull SignalServiceGroupV2 group,
                                                       boolean outgoing,
                                                       int mode)
  {
    UfsrvCommandWire  ufsrvCommand = envelope.getUfsrvCommand();
    FenceCommand      fenceCommand = ufsrvCommand.getFenceCommand();
    FenceRecord       fenceRecord = fenceCommand.getFences(0);

    GroupDatabase           groupDatabase = SignalDatabase.groups();
    GroupContext.Builder    builder;

    GroupId groupId = GroupId.v2(group.getMasterKey());
    byte[] id = groupId.getDecodedId();

    builder = createGroupContext(fenceCommand);

    groupDatabase.markGroupMode(fenceRecord.getFid(), mode);

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);
  }


  private static @Nullable Long handleFenceRejoinExistingUfsrv(@NonNull Context context,
                                                                @NonNull SignalServiceEnvelope envelope,
                                                                @NonNull SignalServiceGroupV2 group,
                                                                boolean outgoing,
                                                                int mode,
                                                                long threadId)
  {
    UfsrvCommandWire    ufsrvCommand  = envelope.getUfsrvCommand();
    FenceCommand        fenceCommand  = ufsrvCommand.getFenceCommand();
    FenceRecord         fenceRecord   = fenceCommand.getFences(0);
    SignalServiceGroup                    mockGroup     = null;
    SignalServiceGroup                    actualGroup;

    GroupContext.Builder    builder;
    SignalServiceAttachment avatar;
    GroupDatabase           groupDatabase = SignalDatabase.groups();

    GroupId groupId = GroupId.v2(group.getMasterKey());
    byte[] id = groupId.getDecodedId();

    builder = createGroupContext(fenceCommand);

    groupDatabase.markGroupMode(fenceRecord.getFid(), mode);

    groupDatabase.setActive(groupId, true);

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);
  }


  /**
   * Delete group identified by given GroupMasterKey if this user on the invited list of the group.
   * @param groupMasterKey
   */
 static void
 deleteGroupIfNecessary(@NonNull GroupMasterKey groupMasterKey)
  {
    GroupDatabase           groupDatabase = SignalDatabase.groups();
    GroupId groupId = GroupId.v2(groupMasterKey);
    assert(GroupId.isGroupIdV2Allocated(groupId.getDecodedId()));

    Single.just(groupDatabase.getGroupByMasterKey(groupMasterKey))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .subscribe(rec -> {
            if (groupDatabase.amOnGroupList(groupDatabase.getInvitedMembers(rec.getFid()))) {
              Log.e(TAG, String.format(Locale.getDefault(), "deleteGroupIfNecessary {groupId:'%s', fid:'%d'}: !! POSSIBLE UNIQUE CONSTRAINT VIOLATION: ATTEMPTING DELETE LOCAL RECORD IF POSSIBLE", groupId, rec.getFid()));
              groupDatabase.deleteGroup(groupId);
            }
          })
          .dispose();
  }

  //group creation semantics are different under ufsrv:
  //1)we may received invitation, in which case we won't have a corresponding record in groupData
  //and the server will not have groupId provided , which we need internally before Incoming message is stored
  //This reflects a request to create a fence for which we may, OR MAY NOT have a previous record, for example member invitation to join, or geobased
  private static @Nullable Long
  handleFenceCreateUfsrv(@NonNull Context context,
                         @NonNull SignalServiceEnvelope envelope,
                         @NonNull SignalServiceGroupV2 group,
                         boolean outgoing,
                         int mode,
                         GroupDatabase.GroupType groupType,
                         GroupDatabase.PrivacyMode privacyMode,
                         GroupDatabase.DeliveryMode deliveryMode)
  {
    SignalServiceProtos.UfsrvCommandWire  ufsrvCommand  = envelope.getUfsrvCommand();
    SignalServiceProtos.FenceCommand      fenceCommand  = ufsrvCommand.getFenceCommand();
    SignalServiceProtos.FenceRecord       fenceRecord   = fenceCommand.getFences(0);
    SignalServiceGroup                    mockGroup     = null;
    SignalServiceGroup                    actualGroup;

    GroupContext.Builder    builder;
    GroupDatabase           groupDatabase = SignalDatabase.groups();
    GroupMasterKey          groupMasterKey = null;
    try {
      groupMasterKey = new GroupMasterKey(fenceRecord.getFkey().toByteArray());
    } catch (InvalidInputException x) {
      Log.e(TAG, x.toString());
      return (long) -1;
    }

    GroupId groupId = GroupId.v2(groupMasterKey);
    assert(GroupId.isGroupIdV2Allocated(groupId.getDecodedId()));

    deleteGroupIfNecessary(groupMasterKey);

    builder = createGroupContext(fenceCommand);

    try {
      groupDatabase.create(GroupId.v2(groupMasterKey),
                           groupMasterKey,
                           fenceRecord,
                           avatarFromAttachmentPointer(builder.getAvatar()).asPointer(),
                           mode,
                           fenceCommand.getHeader().getWhen());

      return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);
    } catch (SQLiteConstraintException x) {
      Log.e(TAG, String.format(Locale.getDefault(), "sendStateSyncJoin {fid:'%d', cname:'%s': !! POSSIBLE UNIQUE CONSTRAINT VIOLATION: ATTEMPTING DELETE LOCAL RECORD IF POSSIBLE", fenceRecord.getFid(), fenceRecord.getCname()));
    }

    return (long) -1;
  }


  private static SignalServiceAttachment
  avatarFromAttachmentPointer(AttachmentPointer pointer)
  {
    SignalServiceAttachment avatar = null;

    if (pointer != null) {
      avatar = new SignalServiceAttachmentPointer(pointer.getUfid(),
                                                  0, SignalServiceAttachmentRemoteId.from("0"),
                                                  pointer.getContentType(),
                                                  pointer.getKey().toByteArray(),
                                                  pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.empty(),
                                                  pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.empty(),
                                                  pointer.getWidth(),
                                                  pointer.getHeight(),
                                                  pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.empty(),
                                                  pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.empty(),
                                                  false,
                                                  (pointer.getFlags() & SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE) != 0,
                                                  false,
                                                  pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.empty(),
                                                  pointer.hasBlurHash() ? Optional.of(pointer.getBlurHash()) : Optional.empty(),
                                                  pointer.hasUploadTimestamp() ? pointer.getUploadTimestamp() : 0);
    }

    return avatar;
  }

  //AA this mostly defunct now. The bulk of the handling is in processUfsrvFenceCommand
  public static @Nullable Long process(@NonNull Context context,
                                       @NonNull SignalServiceDataMessage message,
                                       boolean outgoing)
  {


    SignalServiceGroupContext    signalServiceGroupContext = message.getGroupContext().get();
    Optional<SignalServiceGroup> groupV1                   = signalServiceGroupContext.getGroupV1();
//
//    if (signalServiceGroupContext.getGroupV2().isPresent()) {
//      throw new AssertionError("Cannot process GV2");
//    }
////AA- we tolerate this now, as there are other checks and balances we can use based on ufsrv state
//    if (!groupV1.isPresent() || groupV1.get().getGroupId() == null) {
//      Log.w(TAG, "Received group message with no id! Ignoring...");
//      return null;
//    }
//

    //AA+ diagnostics...
    SignalServiceProtos.FenceRecord fenceRecord = null;
    if (message.getUfsrvCommand().getUfsrvtype() == SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE) {
      SignalServiceProtos.FenceCommand fence = message.getUfsrvCommand().getFenceCommand();
      if (fence != null) {
        if (fence.getFencesCount() > 0) {
          fenceRecord = fence.getFences(0);
          Log.d(TAG, String.format("process (%d): Received fence information: fid:'%d', fcname:'%s", Thread.currentThread().getId(), fenceRecord.getFid(), fenceRecord.getCname()));
        } else {
          Log.d(TAG, String.format("process (%d): Received NO FENCE RECORD: fence eid:'%d", Thread.currentThread().getId(), fence.getHeader().getEid()));
        }
      } else {
        Log.d(TAG, String.format("process (%d): Fence was null", Thread.currentThread().getId()));
      }
    }
    //

    GroupDatabase         database = SignalDatabase.groups();
    SignalServiceGroup    group    = groupV1.get();
    GroupId               id       = GroupId.v2orThrow(group.getGroupId());
    GroupRecord           record   = database.getGroupByGroupId(id);

    //AA+ retrieve record using whatever key is available cname or fid
    GroupRecord         recordFid     = fenceRecord != null ? database.getGroupRecordByFid(fenceRecord.getFid()) : null;//AA+
    GroupRecord         recordCname   = fenceRecord != null ? database.getGroupByCname(fenceRecord.getCname())   : null;//AA+
    GroupRecord         recordUfsrv   = null;


    if (recordFid != null)  recordUfsrv = recordFid;
    else
    if (recordCname != null) recordUfsrv = recordCname;
    else
    if (record != null) {
      recordUfsrv = record;
      Log.w(TAG, String.format("process: GroupRecord was assigned from internal id"));
    }

    if ((record != null && group.getType() == SignalServiceGroup.Type.UPDATE) || (recordUfsrv != null)) {//AA+
      //return handleGroupUpdate(context, masterSecret, envelope, group, record, outgoing);
      //return handleGroupUpdateUfsrvJoinedOrSynced(context, masterSecret, envelope, group, recordUfsrv /*record*/, outgoing);//AA+ swapped with record retrieed based on fid
      return null;//AA+
    } else if (record == null && group.getType() == SignalServiceGroup.Type.UPDATE) {
      //return handleGroupCreate(context, masterSecret, envelope, group, outgoing);//AA-
      return null;//AA+
    } else if (record != null && group.getType() == SignalServiceGroup.Type.QUIT) {
      //return handleGroupLeave(context, masterSecret, envelope, group, record, outgoing);
      return null;//AA+
    } else if (record != null && group.getType() == Type.REQUEST_INFO) {
       return handleGroupInfoRequest(context, null, record);
    } else {
      Log.w(TAG, "Received unknown type, ignoring...");
      return null;
    }
  }

  private static final boolean
  isCommandJoinOrStateSync(FenceCommand fenceCommand)
  {
    return  fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.JOIN_VALUE ||
            fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.STATE_VALUE;
  }


  //Adapt wire command arguments to local context.
  // DONT INVOKE THIS ON CommandArgs.SYNCED_VALUE. because that is not a group creation lifecycle event
  //as far as group mode is concerned for this user
  private static final int
  setGroupModeFromFenceCommandJoin(FenceCommand fenceCommand)
  {
    CommandHeader commandHeader = fenceCommand.getHeader();

    if (commandHeader.getCommand() != FenceCommand.CommandTypes.JOIN_VALUE)  return -1;

    switch (commandHeader.getArgs())
    {
      case CommandArgs.ACCEPTED_VALUE:
      case CommandArgs.CREATED_VALUE:
      case CommandArgs.UNCHANGED_VALUE:
        return GROUP_MODE_JOIN_ACCEPTED;//you joined

      case CommandArgs.ACCEPTED_INVITE_VALUE:
        return GROUP_MODE_INVITATION_JOIN_ACCEPTED;

      case CommandArgs.GEO_BASED_VALUE:
        return GROUP_MODE_GEOBASED_JOIN;

      case CommandArgs.INVITED_VALUE:
        return GROUP_MODE_INVITATION;

      case CommandArgs.INVITED_GEO_VALUE:
        return GROUP_MODE_GEOBASED_INVITE;

      default:
        Log.e(TAG, String.format("setGroupModeFromFenceCommandJoin: Uknown CommandArgs ('%d') for JOIN command...", commandHeader.getArgs()));
    }

    return -1;
  }


  /**
   * Process a server message with focus on join and state synching
   *
   */
  private static @Nullable Long
  handleGroupUpdateUfsrvJoinedOrSynced(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @NonNull SignalServiceGroupV2 group,
                                       @NonNull GroupRecord groupRecord,
                                       boolean outgoing)
  {
    GroupDatabase groupDatabase  = SignalDatabase.groups();
    FenceCommand  fenceCommand   = envelope.getFenceCommand();

    GroupId groupId = GroupId.v2(group.getMasterKey());
    byte[] id = groupId.getDecodedId();

    if (fenceCommand == null || fenceCommand.getFencesCount() <= 0) {
      Log.e(TAG, String.format(Locale.getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: Could not load FenceCommand (%s). Fence count:'%d''", groupId.toString(), fenceCommand!=null?fenceCommand.getFencesCount():-1));
      return (long) -1;
    }

    //AA+
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();
    Recipient       groupRecipient  = Recipient.live(groupRecord.getRecipientId()).get();
    long            threadId        = threadDatabase.getOrCreateThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT);

    FenceRecord fenceRecord = fenceCommand.getFences(0);

    if (!groupRecord.isActive()) {
      Log.e(TAG, String.format(Locale.getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: RECEIVED SYNC FOR INACTIVE GROUP: stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d'", groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));
      groupDatabase.setActive(groupId, true);
    }

    Log.d(TAG, String.format(Locale.getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: active:'%b', stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d'", groupRecord.isActive(), groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));

    //fcname is known internally, so this could be a confirmation response to a previous JOIN request
    if (isCommandJoinOrStateSync(fenceCommand) && !TextUtils.isEmpty(groupRecord.getCname()) && groupRecord.getCname().equals(fenceRecord.getCname())) {
      if (fenceRecord.getFid() == groupRecord.getFid())/*fence already in the db as groupRecord has a valid fid*/ {
        Log.d(TAG, String.format(Locale. getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: SYNCING existing fence '%s. fid:'%d ", fenceRecord.getCname(), fenceRecord.getFid() ));
      } else {
        //this is a case where this user initiated a join by name therefore we wouldn't have  fid until this confirmation arrived
        Log.d(TAG, String.format(Locale. getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: SYNCHING UNCONFIRMED fence '%s. fid:'%d ", fenceRecord.getCname(), fenceRecord.getFid()));

        groupDatabase.updateFid(fenceRecord.getCname(), fenceRecord.getFid());
        threadDatabase.updateFid(threadId, fenceRecord.getFid());
        SignalDatabase.recipients().setUfsrvId(groupRecipient, fenceRecord.getFid());
      }

      Long messageId = updateFenceData(context, envelope,  message, group, groupRecord,  id, threadId, outgoing);

       if (messageId != null) {
         if (messageId > 0 && fenceCommand.getHeader().getArgs() != CommandArgs.SYNCED_VALUE)
           groupDatabase.markGroupMode(fenceRecord.getFid(), setGroupModeFromFenceCommandJoin(fenceCommand));

         return messageId;
       }
    } else {
      Log.e(TAG, String.format(Locale. getDefault(), "handleGroupUpdateUfsrvJoinedOrSynced: INCONSISTENT FENCE COMMAND DATA (fenceRecord.FID:'%d', groupRecord.FID:'%d'): stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d' (if fenceRecord.FID==groupRecord.FID will goahead and sync group name according server's view)", fenceRecord.getFid(), groupRecord.getFid(), groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));
      if (fenceRecord.getFid() == groupRecord.getFid()) {//fence already in the db as groupRecord has a valid fid
        return updateFenceData(context, envelope, message, group, groupRecord,  id, threadId, outgoing);
      }
    }

    return (long) -1;
  }
//

  private static @NonNull Long
  UpdateFenceInvitedMembership(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceGroupV2 group,
                               @NonNull GroupRecord groupRecord,
                               boolean outgoing)
  {
    FenceCommand                        fenceCommand  = envelope.getFenceCommand();
    FenceRecord                         fenceRecord   = fenceCommand.getFences(0);
    int                                 commandArg    = fenceCommand.getHeader().getArgs();
    GroupDatabase.MembershipUpdateMode  updateMode    = (commandArg == CommandArgs.ADDED_VALUE || commandArg == CommandArgs.UNCHANGED_VALUE)?
                                                          GroupDatabase.MembershipUpdateMode.ADD_MEMBER:
                                                          REMOVE_MEMBER;

    updateGroupInviteMembership(context, groupRecord, fenceRecord, updateMode);

    GroupId groupId = GroupId.v2(group.getMasterKey());

    GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId.getDecodedId());

    //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
    builderGroupContext.setFenceMessage(envelope.getFenceCommand());

    if (envelope.getFenceCommand().getHeader().getWhenClient() > 0) {
      Log.d(TAG, String.format("processFenceCommandInviteUpdated (timestamp:'%d', fid:'%d'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceCommand.getFences(0).getFid()));
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandInviteUpdated (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }

    return storeMessage(context, envelope, Optional.of(groupId), builderGroupContext.build(), outgoing);
  }

  private static @NonNull Long
  RemoveFenceInvitedMembership(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceGroupV2 group,
                               @NonNull GroupRecord groupRecord,
                               boolean outgoing)
  {
    FenceCommand                        fenceCommand  = envelope.getFenceCommand();
    GroupDatabase.MembershipUpdateMode  updateMode    = REMOVE_MEMBER;

//    updateGroupInviteMembership(context, groupRecord, fenceRecord, updateMode);
    GroupDatabase database = SignalDatabase.groups();
    List<Address> removeMe = new LinkedList<Address>() {{ add(Recipient.self().requireAddress()); }};
    database.updateInvitedMembers(groupRecord.getFid(), removeMe, updateMode);

    GroupId groupId = GroupId.v2(group.getMasterKey());

    GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId.getDecodedId());

    //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
    builderGroupContext.setFenceMessage(envelope.getFenceCommand());

    if (envelope.getFenceCommand().getHeader().getWhenClient() > 0) {
      Log.d(TAG, String.format("RemoveFenceInvitedMembership (timestamp:'%d', fid:'%d'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceCommand.getFences(0).getFid()));
      MmsDatabase.Reader msg = SignalDatabase.mms().getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("RemoveFenceInvitedMembership (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
    }

    return storeMessage(context, envelope, Optional.of(groupId), builderGroupContext.build(), outgoing);
  }

  //if we receive such a message group mode then it must be in one of the states tested below
  static private void
  updateGroupModeForJoinOrSyncedGroup(@NonNull Context context, @NonNull GroupRecord groupRecord)
  {
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    int             groupMode       = groupRecord.getMode();

    //self-healing
    if (groupMode != GROUP_MODE_JOIN_ACCEPTED && groupMode != GROUP_MODE_LINKJOIN_ACCEPTED && groupMode != GROUP_MODE_INVITATION_JOIN_ACCEPTED &&
        groupMode != GROUP_MODE_GEOBASED_JOIN && groupMode != GROUP_MODE_JOIN_SYNCED &&
        groupMode != GROUP_MODE_MAKE_NOT_CONFIRMED) {
      Log.e(TAG, String.format("updateGroupModeForJoinOrSyncedGroup (groupMode:'%d', fid:'%d', GroupDatabase.cname:'%s'): ERROR: GROUP MODE HAD INVALID VALUE: Reassigning mode to default JOIN_ACCEPTED (it could have been INVITED)", groupRecord.getMode(), groupRecord.getFid(), groupRecord.getCname()));
      groupDatabase.markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_JOIN_ACCEPTED);

    }
  }

  //just shovel stuff in...
  private static Long
  updateFenceData(@NonNull Context context,
                  @NonNull SignalServiceEnvelope envelope,
                  @NonNull SignalServiceDataMessage message,
                  @NonNull SignalServiceGroupV2 group,
                  @NonNull GroupRecord groupRecord,
                  @NonNull byte[] id,
                  @NonNull long threadId,
                  boolean outgoing)
{
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();
    FenceCommand    fenceCommand    = envelope.getFenceCommand();
    FenceRecord     fenceRecord     = fenceCommand.getFences(0);
    long            threadFid       = threadDatabase.getFidForThreadId(threadId);
    Recipient       recipient       =  Recipient.live(fenceRecord.getFid()).get();

    GroupId groupId = GroupId.v2(group.getMasterKey());

    if (threadFid <= 0) {
      Log.e(TAG, String.format(Locale.getDefault(), "updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', cname:'%s', threadid:'%d', threadFid:'%d'): FID WAS NOT SET FOR THREADID... updating", fenceRecord.getFid(), fenceRecord.getCname(), threadId, threadFid));
      threadDatabase.updateFid(threadId, fenceRecord.getFid());
    }

    //done upstream
    //if (fenceCommand.getMainHeader().getEid()>0)  threadDatabase.updateEidByFid(fenceRecord.getFid(), fenceCommand.getMainHeader().getEid());

    //self-healing...
    updateGroupModeForJoinOrSyncedGroup(context, groupRecord);

    if (!TextUtils.isEmpty(fenceRecord.getFname()) &&
        (TextUtils.isEmpty(groupRecord.getTitle()) || !groupRecord.getTitle().equals(fenceRecord.getFname()))) {
      Log.e(TAG, String.format(Locale.getDefault(), "updateFenceData (fid:'%d', FenceRecord.name:'%s', GroupDatabase.name:'%s'): Updating fence name", fenceRecord.getFid(), fenceRecord.getFname(), groupRecord.getTitle()));
      groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
      if (TextUtils.isEmpty(fenceRecord.getCname()))
        groupDatabase.updateCname(fenceRecord.getFid(), UfsrvFenceUtils.updateCnameWithNewFname (fenceRecord.getFid(), groupRecord.getCname(), groupRecord.getTitle(), fenceRecord.getFname()));
      else
        groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

      groupRecord = groupDatabase.getGroupRecordByFid(fenceRecord.getFid());//reload group record with updated values
    }

    if (TextUtils.isEmpty(groupRecord.getCname()) && !TextUtils.isEmpty(fenceRecord.getCname())) {
      Log.e(TAG, String.format(Locale.getDefault(), "updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', FenceRecord.cname:'%s'): GroupRecord.CNAME IS NULL: updating", fenceRecord.getFid(), fenceRecord.getCname()));
      groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

      groupRecord = groupDatabase.getGroupRecordByFid(fenceRecord.getFid());
    }

    if (fenceRecord.hasOwnerUid())      {
      UfsrvUid ufsrvUid = new UfsrvUid(fenceRecord.getOwnerUid().toByteArray());
      groupDatabase.updateGroupOwnerUid(fenceRecord.getFid(), ufsrvUid.getUfsrvSequenceId());
    }
    if (fenceRecord.hasDeliveryMode())  groupDatabase.updateGroupDeliveryMode(fenceRecord.getFid(), GroupDatabase.DeliveryMode.values()[fenceRecord.getDeliveryMode().getNumber()]);
    if (fenceRecord.hasJoinMode())      groupDatabase.updateGroupJoinMode(fenceRecord.getFid(), GroupDatabase.JoinMode.values()[fenceRecord.getJoinMode().getNumber()]);
    if (fenceRecord.hasPrivacyMode())   groupDatabase.updateGroupPrivacyMode(fenceRecord.getFid(), GroupDatabase.PrivacyMode.values()[fenceRecord.getPrivacyMode().getNumber()]);
    if (fenceRecord.hasFenceType())     groupDatabase.updateGroupType(fenceRecord.getFid(), GroupDatabase.GroupType.values()[fenceRecord.getFenceType().getNumber()]);
    if (fenceRecord.hasMaxmembers())    groupDatabase.updateGroupMaxMembers(fenceRecord.getFid(), fenceRecord.getMaxmembers());

    updateFencePermission(context, groupRecord, fenceRecord, threadFid);
    updateFenceAvatar(context, fenceCommand, group, groupRecord);
    UfsrvFenceUtils.updateFencePreferences(context, groupRecord, fenceRecord);

    if (fenceRecord.hasExpireTimer()) {
      SignalDatabase.recipients().setExpireMessages(recipient.getId(), (int)fenceRecord.getExpireTimer()/1000);//in seconds
    }

    //todo: not sure this is necessary in light of above. at worst, consolidate the logic
    if (!groupRecord.getCname().equals(fenceRecord.getCname())) {
      Log.e(TAG, String.format(Locale.getDefault(), "updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', groupRecord.cname:'%s', FenceRecord.cname:'%s', threadid:'%d', threadFid:'%d'): CNAME INCONSISTENT.. updating", fenceRecord.getFid(), groupRecord.getCname(), fenceRecord.getCname(), threadId, threadFid));
      groupDatabase.updateAndResolveCname(fenceRecord.getFid(), fenceRecord.getCname());
    }

    if (fenceRecord.hasLocation()) {
      groupDatabase.updateFenceLocation(fenceRecord.getCname(), fenceRecord.getFid(), fenceRecord.getLocation().getLongitude(), fenceRecord.getLocation().getLongitude());
    }

    updateGroupMembership(context, group, groupRecord, fenceRecord, true);

    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, id);

    //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
    builder.setFenceMessage(envelope.getFenceCommand());

    if (!groupRecord.isActive()) groupDatabase.setActive(GroupId.v2orThrow(id), true);

    SignalDatabase.recipients().setUfsrvId(recipient, fenceRecord.getFid());
    SignalDatabase.recipients().setEid(recipient, fenceRecord.getEid());

    if (fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.STATE_VALUE &&
          fenceCommand.getHeader().getArgs() == CommandArgs.SYNCED_VALUE) {
      Log.d(TAG, String.format(Locale.getDefault(), "updateFenceData (fid:'%d', cname:'%s'): Updated Fence data, but not storing message PURE STATE SYNCED", fenceRecord.getFid(), groupRecord.getCname()));
      MmsDatabase.Reader msg = SignalDatabase.mms().getGroupUpdateMessages();
      if (msg != null) {
        SignalDatabase.mms().deleteMessage(msg.getId());

        msg.close();
      }
      return 0L;
    }

    return storeMessage(context, envelope, Optional.of(groupId), builder.build(), outgoing);
  }
  //

  private static void updateFenceAvatar(@NonNull Context               context,
                                        @NonNull FenceCommand fenceCommand,
                                        @NonNull SignalServiceGroupV2    group,
                                        @NonNull GroupRecord           groupRecord)
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    FenceRecord   fenceRecord   = fenceCommand.getFences(0);
    GroupId       groupId       =  GroupId.v2(group.getMasterKey());


    if (fenceRecord.hasAvatar()) {
      SignalServiceProtos.AttachmentRecord attachmentRecord = fenceRecord.getAvatar();
      if (groupRecord.getAvatarUfId() == attachmentRecord.getId()) return;
      else {
//        SignalServiceAttachmentPointer attachmentPointer = group.getAvatar().get().asPointer();
        SignalServiceAttachment avatar = new SignalServiceAttachmentPointer(attachmentRecord.getId(),
                                                                            0,
                                                                            attachmentRecord.getContentType(),
                                                                            attachmentRecord.getKey().toByteArray(),
                                                                            attachmentRecord.hasDigest() ? Optional.of(attachmentRecord.getDigest().toByteArray()) : Optional.empty(),
                                                                            attachmentRecord.hasFileName() ? Optional.of(attachmentRecord.getFileName()) : Optional.empty(),
                                                                            false,
                                                                            false,
                                                                            false);
        groupDatabase.update(groupId, null, avatar.asPointer());
        Log.d(TAG, String.format("updateFenceAvatar (fid:'%d'): DOWNLOADING AVATAR... id: %s", fenceRecord.getFid(), avatar.asPointer().getUfId()));
        ApplicationDependencies.getJobManager()
                .add(new AvatarGroupsV1DownloadJob(groupId.requireV2()));
      }
    }

  }

  //todo: implement storing permission users list
  public static void
  updateFencePermission(@NonNull Context context, @NonNull GroupRecord groupRecord, @NonNull FenceRecord fenceRecord,  long fid)
  {
    FenceRecord.Permission fencePermission;
    RecipientDatabase preferenceDatabase  = SignalDatabase.recipients();

    Recipient recipient = Recipient.live(groupRecord.getRecipientId()).get();

    //EnumPermissionBaseList is not managed by backend
    fencePermission = fenceRecord.getPresentation();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(FencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.PermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.PermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));

    fencePermission=fenceRecord.getMembership();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(FencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.PermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.PermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getMessaging();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(FencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.PermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.PermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getAttaching();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(FencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.PermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.PermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getCalling();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(FencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.PermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.PermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
  }

  public static List<Address>
  UserRecordsToNumbersAddressList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    List<Address> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        if (!excludedUsers.get().contains(record.getUsername()))
          numbers.add(Address.fromSerialized(record.getUsername()));
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", record.getUsername()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        numbers.add(Address.fromSerialized(record.getUsername()));
      }
    }

    return numbers;
  }

  public static List<String>
  UserRecordsToNumbersList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    List<String> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        if (!excludedUsers.get().contains(record.getUsername())) numbers.add((record.getUsername()));
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", record.getUsername()));
        }
      }
    } else {
        for (SignalServiceProtos.UserRecord record : userRecords) {
          numbers.add((record.getUsername()));
        }
      }

    return numbers;
  }

  //gradually to replace one above
  public static List<String> UserRecordsToEncodedUfsrvUidList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<String> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid.toString()))
          numbers.add(ufsrvUid.toString());
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", ufsrvUid.toString()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        numbers.add(ufsrvUid.toString());
      }
    }

    return numbers;
  }

  public static List<UfsrvUid>
  UserRecordsToUserIdsList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<UfsrvUid>>excludedUsers)
  {
    List<UfsrvUid> userIds = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid)) userIds.add((ufsrvUid));
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%d'", record.getUsername()));
        }
      }
    } else {
      for (UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        userIds.add(ufsrvUid);
      }
    }

    return userIds;
  }

  public static List<RecipientId>
  UserRecordsToRecipientIdsList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<RecipientId>>excludedUsers)
  {
    List<RecipientId> recipientIds = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        Recipient recipient = Recipient.live(ufsrvUid.toString()).get();
        if (!excludedUsers.get().contains(recipient.getId())) recipientIds.add((recipient.getId()));
        else {
          Log.d(TAG, String.format("UserRecordsToRecipientIdsList: Found excluded member :'%d'", record.getUsername()));
        }
      }
    } else {
      for (UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        Recipient recipient = Recipient.live(ufsrvUid.toString()).get();
        recipientIds.add(recipient.getId());
      }
    }

    return recipientIds;
  }

  //to replace one above gradually...
  public static List<Address>
  UserRecordsToAddressList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<Address> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid.toString()))
          numbers.add(Address.fromSerialized(ufsrvUid.toString()));
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", ufsrvUid.toString()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        numbers.add(Address.fromSerialized(ufsrvUid.toString()));
      }
    }

    return numbers;
  }

  public static List<GroupContext.Member>
  UserRecordsToGroupContextMembers(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<GroupContext.Member> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        GroupContext.Member.Builder builder = GroupContext.Member.newBuilder();
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid.toString()))
          numbers.add(builder.setE164(ufsrvUid.toString()).build());
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", ufsrvUid.toString()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        GroupContext.Member.Builder builder = GroupContext.Member.newBuilder();
        numbers.add(builder.setE164(ufsrvUid.toString()).build());
      }
    }

    return numbers;
  }

  public static List<SignalServiceAddress> UserRecordsToSignalServiceAddressList(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<SignalServiceAddress> numbers = new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid.toString()))
          numbers.add(new SignalServiceAddress(null, ufsrvUid.getUfsrvUidEncoded()));
        else {
          Log.d(TAG, String.format("UserRecordsToSignalServiceAddressList: Found excluded member :'%s'", ufsrvUid.toString()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        numbers.add(new SignalServiceAddress(null, ufsrvUid.getUfsrvUidEncoded()));
      }
    }

    return numbers;
  }

  //AA+
  private  static void
  updateGroupInviteMembership(@NonNull Context context,
                              @NonNull GroupRecord groupRecord,
                              @NonNull FenceRecord fenceRecord,
                              GroupDatabase.MembershipUpdateMode updateMode)
  {
    GroupDatabase database = SignalDatabase.groups();

    List<UfsrvUid> usersAffected = UserRecordsToUserIdsList(fenceRecord.getInvitedMembersList(),  Optional.empty());

    List<Address> addressListUsersAffected = new LinkedList<>();
    for (UfsrvUid ufsrvUid: usersAffected) {
      Recipient recipient = Recipient.live(ufsrvUid.toString()).get();
      addressListUsersAffected.add(recipient.requireAddress());
    }

    if (fenceRecord.getInvitedMembersCount() > 0) {
      database.updateInvitedMembers(groupRecord.getFid(), addressListUsersAffected, updateMode);
    }

    return;

  }

  //AA+ Updates both, regular and invite memberships
  private  static void
  updateGroupMembership(@NonNull Context context,
                        @NonNull SignalServiceGroupV2 group,
                        @NonNull GroupRecord groupRecord,
                        @NonNull FenceRecord fenceRecord,
                        boolean flagReset)
{
    GroupDatabase database = SignalDatabase.groups();

    if (flagReset) {
    //normally for new synced fences

      //not used
      //Set<String> messageMembersUf = new HashSet<>(UserRecordsToNumbersList(fenceRecord.getMembersList()));
      GroupId groupId = groupRecord.getId();
      database.updateMembers(groupId, UserRecordsToAddressList(fenceRecord.getMembersList(), Optional.empty()));

      UfsrvFenceUtils.updateEidForFenceMembers(fenceRecord.getMembersList(), Optional.empty());
      UfsrvFenceUtils.updateProfileKeyForFenceMembers(context, fenceRecord.getMembersList(), Optional.empty());

      if (fenceRecord.getInvitedMembersCount() > 0) {
        List<String> excludedMembers = new LinkedList<>();
        excludedMembers.add(TextSecurePreferences.getUfsrvUserId(context));

        database.updateInvitedMembers(groupId, UserRecordsToAddressList(fenceRecord.getInvitedMembersList(), Optional.of(excludedMembers)));

        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: Invited members contains: '%d' member(s)", fenceRecord.getInvitedMembersList().size()));
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: RESETTING Invited members to empty"));
        database.updateInvitedMembers(groupId, new LinkedList<>());
      }

      if (fenceRecord.getLinkjoinMembersCount() > 0) {
        database.updateInvitedMembers(groupId, UserRecordsToAddressList(fenceRecord.getLinkjoinMembersList(), Optional.empty()));

        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: LinkJoin (aka requesting) members contains: '%d' member(s)", fenceRecord.getInvitedMembersList().size()));
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: RESETTING LinkJoin (aka requesting) members to empty"));
        database.updateLinkJoinMembers(groupId, new LinkedList<>());
      }

      if (fenceRecord.getBannedMembersCount() > 0) {
        database.updateBannedMembers(groupId, UserRecordsToAddressList(fenceRecord.getBannedMembersList(), Optional.empty()));

        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: Banned members contains: '%d' member(s)", fenceRecord.getBannedMembersList().size()));
      } else {
        Log.d(TAG, String.format(Locale.getDefault(), "updateGroupMembership: RESETTING Banned members to empty"));
        database.updateBannedMembers(groupId, new LinkedList<>());
      }

      return;
    }

    //this continues from original code whichnot needed as we already extracted the groups above
    //The group context happens in the calling method

    /*Set<String> recordMembers = new HashSet<>(groupRecord.getMembers());
    Set<String> messageMembers = new HashSet<>(group.getMembers().get());

    Set<String> addedMembers = new HashSet<>(messageMembers);
    addedMembers.removeAll(recordMembers);//remove members stored in internal db that are referenced in the incoming group list that leavs us with new members only

    Set<String> missingMembers = new HashSet<>(recordMembers);
    missingMembers.removeAll(messageMembers);//remove all from existing record that are in the new group, leavs us with members that are no longer in the new group

    GroupContext.Builder builder = createGroupContext(group);
    builder.setType(GroupContext.Type.UPDATE);


    if (addedMembers.size() > 0) {
      Set<String> unionMembers = new HashSet<>(recordMembers);
      unionMembers.addAll(messageMembers);
      database.updateMembers(groupRecord.getEncodedId(), new LinkedList<>(unionMembers));

      builder.clearMembers().addAllMembers(addedMembers);
    } else {
      builder.clearMembers();
    }

    if (missingMembers.size() > 0) {
      // TODO We should tell added and missing about each-other.
    }*/
  }

  //AA orig not up to date
  private static Long handleGroupLeave(@NonNull Context               context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceGroup    group,
                                       @NonNull GroupRecord           record,
                                       boolean  outgoing)
  {
  /*  GroupDatabase     database = SignalDatabase.groups();
    GroupId.V1               id       = GroupId.v1orThrow(group.getGroupId());
    List<RecipientId> members  = record.getMembersRecipientId();//AA++

    GroupContext.Builder builder = createGroupContext(group);
    builder.setType(GroupContext.Type.QUIT);

    if (members.contains(Recipient.external(context, envelope.getSource()).getId())) {
      database.remove(id, Recipient.external(context, envelope.getSource()).getId());
      if (outgoing) database.setActive(id, false);

      return storeMessage(context, envelope, group, builder.build(), outgoing);
    }*/

    return null;
  }


  private static Long handleGroupInfoRequest(@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull GroupRecord record)
  {
   /* Address address = Address.fromSerialized(envelope.getSourceIdentifier());
    if (record.getMembers().contains(address)) {
      ApplicationDependencies.getJobManager()
       .add(new PushGroupUpdateJob(Recipient.external(context, envelope.getSourceIdentifier()).getId(), record.getId()));
   }
*/
    return null;

  }


  private static @Nullable Long storeMessage(@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull Optional<GroupId> groupId,//AA++
                                             @NonNull GroupContext storage,
                                             boolean  outgoing)
  {
    //AA+
    if (storage.hasAvatar()) {
      Log.d(TAG, String.format("storeMessage: DOWNLOADING AVATAR... id: %s", storage.getAvatar().getUfid()));
      ApplicationDependencies.getJobManager()
                             .add(new AvatarGroupsV1DownloadJob(GroupId.v2orThrow(storage.getId().toByteArray())));
    }

    try {
      if (outgoing) {
        MessageDatabase            mmsDatabase     = SignalDatabase.mms();
        RecipientId               recipientId     = SignalDatabase.recipients().getOrInsertFromGroupId(groupId.orElse(null));//AA++
        Recipient                 recipient       = Recipient.resolved(recipientId);

        OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipient, storage, null, envelope.getTimestamp(), 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                    envelope.getUfsrvCommand());//AA+
        long                      threadId        = SignalDatabase.threads().getOrCreateThreadIdFor(recipient);
        long                      messageId       = mmsDatabase.insertMessageOutbox(outgoingMessage, threadId, false, null);

        mmsDatabase.markAsSent(messageId, true);

        return threadId;
      } else {
        MessageDatabase            smsDatabase  = SignalDatabase.sms();

        Log.d(TAG, "storeMessage: String message with 'outgoing' false");
        String                body         = Base64.encodeBytes(storage.toByteArray());//AA GroupContext. We serialise UfsrvCommand later at db insertion

        //AA+ usfrcommand
        IncomingTextMessage   incoming     = new IncomingTextMessage(Recipient.external(context, envelope.getSourceIdentifier()).getId(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), System.currentTimeMillis(), body, groupId, 0, false, envelope.getServerGuid(), envelope.getUfsrvCommand());
        IncomingGroupUpdateMessage groupMessage = new IncomingGroupUpdateMessage(incoming, storage, body, envelope.getUfsrvCommand());
        //

        Optional<InsertResult> insertResult = smsDatabase.insertMessageInbox(groupMessage);
        if (insertResult.isPresent()) {
          ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
          Log.d(TAG, String.format("storeMessage: Storing group update with messageId:'%d and threadId:'%d'", insertResult.get().getMessageId(), insertResult.get().getThreadId()));
          return insertResult.get().getThreadId();
        } else {
          return null;
        }
      }
    } catch (MmsException e) {
      Log.w(TAG, e);
    }

    return null;
  }


  private static @Nullable Long storeLinkJoinAdminActionMessage(@NonNull Context context,
                                                                @NonNull SignalServiceEnvelope envelope,
                                                                @NonNull Optional<GroupId> groupId,//AA++
                                                                @NonNull GroupContext storage)
  {
    MessageDatabase  smsDatabase  = SignalDatabase.sms();
    String                body    = Base64.encodeBytes(storage.toByteArray());//AA GroupContext. We serialise UfsrvCommand later at db insertion

    //AA+ usfrcommand
    IncomingTextMessage   incoming     = new IncomingTextMessage(Recipient.self().getId(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), System.currentTimeMillis(), body, groupId, 0, false, envelope.getServerGuid(), envelope.getUfsrvCommand());
    IncomingGroupUpdateMessage groupMessage = new IncomingGroupUpdateMessage(incoming, storage, body, envelope.getUfsrvCommand());
    //

    Optional<InsertResult> insertResult = smsDatabase.insertMessageInbox(groupMessage);
    if (insertResult.isPresent()) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      Log.d(TAG, String.format("storeLinkJoinAdminActionMessage: Storing group update with messageId:'%d and threadId:'%d'", insertResult.get().getMessageId(), insertResult.get().getThreadId()));
      return insertResult.get().getThreadId();
    } else {
      return (long) -1;
    }
  }

  private static GroupContext.Builder createGroupContext(SignalServiceGroup group) {
    GroupContext.Builder builder = GroupContext.newBuilder();
    builder.setId(ByteString.copyFrom(group.getGroupId()));

    if (group.getAvatar().isPresent()       &&
            group.getAvatar().get().isPointer() &&
            !TextUtils.isEmpty(group.getAvatar().get().asPointer().getUfId()))//AA+
    {
      builder.setAvatar(AttachmentPointer.newBuilder()
                                .setCdnId(group.getAvatar().get().asPointer().getRemoteId().getV2().get())
                                .setUfid(group.getAvatar().get().asPointer().getUfId())//AA+
                                .setKey(ByteString.copyFrom(group.getAvatar().get().asPointer().getKey()))
                                .setContentType(group.getAvatar().get().getContentType()));
    }

    if (group.getName().isPresent()) {
      builder.setName(group.getName().get());
    }

    if (group.getMembers().isPresent()) {
      builder.addAllMembersE164(Stream.of(group.getMembers().get())
                                        .filter(a -> a.getNumber().isPresent())
                                        .map(a -> a.getNumber().get())
                                        .toList());
      builder.addAllMembers(Stream.of(group.getMembers().get())
                                    .filter(address -> address.getNumber().isPresent())
                                    .map(address -> address.getNumber().get())
                                    .map(GroupV1MessageProcessor::createMember)
                                    .toList());
    }

    return builder;
  }

  //Also See createGroupContextFromUfsrv()
  private static GroupContext.Builder createGroupContext(FenceCommand fenceCommand) {
    GroupContext.Builder  builder     = null;
    FenceRecord           fenceRecord = fenceCommand.getFences(0);
    GroupId               groupId;

    try {
      groupId = GroupId.v2(new GroupMasterKey(fenceRecord.getFkey().toByteArray()));
    } catch (InvalidInputException x) {
      throw new IllegalStateException("ERROR DERIVING GROUP MASTERKEY");
    }

    byte[] id = groupId.getDecodedId();

    builder = GroupContext.newBuilder().setId(ByteString.copyFrom(id))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand)
            .addAllMembers(UserRecordsToGroupContextMembers(fenceRecord.getMembersList(), Optional.empty()));
    builder.setName(fenceRecord.getFname());

    //Extract the avatar from attachments list.
    List<SignalServiceAttachment> attachments = new LinkedList<>();
    for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(),
                                                         0, SignalServiceAttachmentRemoteId.from("0"),
                                                         pointer.getContentType(),
                                                         pointer.getKey().toByteArray(),
                                                         pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.empty(),
                                                         pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.empty(),
                                                         pointer.getWidth(),
                                                         pointer.getHeight(),
                                                         pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.empty(),
                                                         pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.empty(),
                                                         false,
                                                         (pointer.getFlags() & SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE) != 0,
                                                         (pointer.getFlags() & SignalServiceProtos.AttachmentPointer.Flags.GIF_VALUE) != 0,
                                                         pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.empty(),
                                                         pointer.hasBlurHash() ? Optional.of(pointer.getBlurHash()) : Optional.<String>empty(),
                                                         pointer.hasUploadTimestamp() ? pointer.getUploadTimestamp() : 0));
    }

    SignalServiceAttachment avatar = attachments.size() > 0 ? attachments.get(0) : null;

    if (avatar != null) {
      builder.setAvatar(AttachmentPointer.newBuilder()
                                .setCdnNumber(0)//this is not supported by ufsrv
                                .setUfid(avatar.asPointer().getUfId())
                                .setKey(ByteString.copyFrom(avatar.asPointer().getKey()))
                                .setDigest(ByteString.copyFrom(avatar.asPointer().getDigest().get()))
                                .setWidth(avatar.asPointer().getWidth())
                                .setHeight(avatar.asPointer().getHeight())
                                .setContentType(avatar.getContentType()));

    }

//    builder.addMembers(UserRecordsToSignalServiceAddressList(fenceRecord.getMembersList(), Optional.empty()));

    return builder;
  }

  public static GroupContext.Member createMember(@NonNull String e164) {
    GroupContext.Member.Builder member = GroupContext.Member.newBuilder();
    member.setE164(e164);

    return member.build();
  }

  public static GroupContext.Builder createGroupContextFromUfsrv(@NonNull Context context,
                                                                 @NonNull UfsrvCommandWire ufsrvCommandWire,
                                                                 GroupContext.Type Type)
  {
    FenceCommand fenceCommand   = ufsrvCommandWire.getFenceCommand();
    FenceRecord fenceRecord     = fenceCommand.getFences(0);
    GroupDatabase groupDatabase = SignalDatabase.groups();
    byte[] id                   = null;
    GroupId       groupId;

    //todo: use
    groupId = groupDatabase.getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), true);
    id = groupId.getDecodedId();

   return createGroupContextFromUfsrv(context,ufsrvCommandWire, Type, id);
  }


  public static GroupContext.Builder createGroupContextFromUfsrv(@NonNull Context context,
                                                                 @NonNull UfsrvCommandWire ufsrvCommandWire,
                                                                 GroupContext.Type Type,
                                                                 byte[] id)
  {

    SignalServiceProtos.FenceCommand  fenceCommand    = ufsrvCommandWire.getFenceCommand();
    SignalServiceProtos.FenceRecord   fenceRecord     = fenceCommand.getFences(0);

    GroupContext.Builder builder = GroupContext.newBuilder()
                                               .setId(ByteString.copyFrom(id))
                                               .setType(Type)
                                               .setFenceMessage(fenceCommand)
                                               .addAllMembers(UserRecordsToGroupContextMembers(fenceRecord.getMembersList(), Optional.empty()));
    builder.setName(fenceRecord.getFname());

    //Extract the avatar from attachments list.
    List<SignalServiceAttachment> attachments = new LinkedList<>();
    SignalServiceAttachment avatar;
    for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), //AA+  the ufid
                                                        0, SignalServiceAttachmentRemoteId.from("0"),
                                                          pointer.getContentType(),
                                                          pointer.getKey().toByteArray(),
                                                          pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.empty(),
                                                          pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.empty(),
                                                          pointer.getWidth(),
                                                          pointer.getHeight(),
                                                          pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.empty(),
                                                          pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.empty(),
                                                      false,
                                                         (pointer.getFlags() & SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE) != 0,
                                                         false,
                                                         pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.empty(),
                                                         pointer.hasBlurHash() ? Optional.of(pointer.getBlurHash()) : Optional.empty(),
                                                         pointer.hasUploadTimestamp() ? pointer.getUploadTimestamp() : 0));
    }
    avatar = (attachments.size()) > 0 ? attachments.get(0) : null;
    if (avatar != null) {
      builder.setAvatar(AttachmentPointer.newBuilder()
             .setCdnNumber(0)//this is not supported by ufsrv
             .setUfid(avatar.asPointer().getUfId())
             .setKey(ByteString.copyFrom(avatar.asPointer().getKey()))
             .setDigest(ByteString.copyFrom(avatar.asPointer().getDigest().get()))
             .setContentType(avatar.getContentType()));

    }
    //end Avatar block

    return builder;
  }

  static public String UfsrvUidEncodedForOriginator(FenceCommand fenceCommand)
  {
    return UfsrvUid.EncodedfromSerialisedBytes(fenceCommand.getOriginator().getUfsrvuid().toByteArray());
  }

}
