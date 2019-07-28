package org.thoughtcrime.securesms.groups;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.ufsrvcmd.events.AppEventGroupDestroyed;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.jobs.AvatarDownloadJob;
import org.thoughtcrime.securesms.jobs.PushGroupUpdateJob;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingGroupMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup.Type;

import java.io.IOException;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.mms.MmsException;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_JOIN;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_INVITE;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_SYNCED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_GEO_BASED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;

import static org.thoughtcrime.securesms.database.GroupDatabase.MembershipUpdateMode.REMOVE_MEMBER;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

public class GroupMessageProcessor {

  private static final String TAG = GroupMessageProcessor.class.getSimpleName();

  //at this point a Push message will have been stored in PushDatabase about this event, FROM WHICH envelope/ufsrvCommand are sourced
  public static @Nullable
  Long processUfsrvFenceCommand(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Optional<Long> smsMessageId,
                                boolean outgoing)
  {
    GroupDatabase                     groupDatabase     = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase                    threadDatabase    = DatabaseFactory.getThreadDatabase(context);
    SignalServiceProtos.FenceRecord   fenceRecord       = null;
    SignalServiceProtos.FenceCommand  fenceCommand      = message.getUfsrvCommand().getFenceCommand();

    if (fenceCommand==null) {
      Log.e(TAG, String.format("processUfsrvFenceCommand (%d): FenceCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!(fenceCommand.getFencesCount() > 0 && ((fenceRecord=fenceCommand.getFences(0))!=null))) {
      Log.e(TAG, String.format("processUfsrvFenceCommand (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    //fid wouldn't be known if we received incoming join invitation, or join confirmation
    GroupRecord         recordFid     = fenceRecord!=null?groupDatabase.getGroupRecordByFid(fenceRecord.getFid()):null;
    GroupRecord         recordCname   = fenceRecord!=null ? groupDatabase.getGroupByCname(fenceRecord.getCname()) : null;
    GroupRecord         recordUfsrv   = null;
    Long                storedId      = Long.valueOf(-1);

    if      (recordFid!=null)   recordUfsrv=recordFid;
    else if (recordCname!=null) recordUfsrv=recordCname;


    switch (fenceCommand.getHeader().getCommand())
    {
      case FenceCommand.CommandTypes.JOIN_VALUE:
        storedId=processFenceCommandJoin(context, envelope, message, recordUfsrv, outgoing);
         break;

      case FenceCommand.CommandTypes.LEAVE_VALUE:
        storedId=processFenceCommandLeave (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.STATE_VALUE:
        storedId=processFenceCommandState (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.FNAME_VALUE:
        storedId=processFenceCommandFeneName (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.AVATAR_VALUE:
        storedId=processFenceCommandAvatar (context, envelope, message, recordUfsrv, outgoing);
        break;

      //recieved response to previous request to invite members
      case FenceCommand.CommandTypes.INVITE_VALUE:
        storedId=processFenceCommandInvite (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.EXPIRY_VALUE:
        storedId=processFenceCommandMessageExpiry (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.PERMISSION_VALUE:
        storedId=processFenceCommandPermission (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.MAXMEMBERS_VALUE:
        storedId=processFenceCommandMaxMembers (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.JOIN_MODE_VALUE:
//        storedId=processFenceCommandPermission (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.PRIVACY_MODE_VALUE:
//        storedId=processFenceCommandPermission (context, envelope, message, recordUfsrv, outgoing);
        break;

      case FenceCommand.CommandTypes.DELIVERY_MODE_VALUE:
        storedId=processFenceCommandDeliveryMode (context, envelope, message, recordUfsrv, outgoing);
        break;
    }

    if (storedId!=null && storedId>=0) {
      if (fenceCommand.getHeader().getEid()>0)  threadDatabase.updateEidByFid(fenceRecord.getFid(), fenceCommand.getHeader().getEid());
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

    switch (CommandArgs.values()[commandArg])
    {
      case ADDED:
      case DELETED:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE UPDATE (ADD||REMOVE): '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteUpdated(context, envelope, message, groupRecordUfsrv, outgoing);

      case UNCHANGED:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE UNCHANGED: '%d', ARGS:'%d'FOR: fid:'%d'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
        return processFenceCommandInviteUnchanged(context, envelope, message, groupRecordUfsrv, outgoing);

      case ACCEPTED:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE ACCEPTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      case ACCEPTED_PARTIAL:
      case INVITED_GEO:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE PARTIAL ACCEPTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      case REJECTED:
        Log.w(TAG, String.format("processFenceCommandInvite (%d): Received INVITE REJECTED: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        break;

      default:
        Log.w(TAG, String.format("processFenceCommandJoin (%d): Received UNKNOWN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);

  }


  // Main controller for LEAVE command arriving from backend
  private static @Nullable
  Long processFenceCommandLeave(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @Nullable GroupRecord groupRecordUfsrv,
                                boolean outgoing)
  {
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
    SignalServiceGroup group      = message.getGroupInfo().get();
    SignalServiceProtos.FenceRecord fenceRecord   = null;
    SignalServiceProtos.FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    String ufsrvUid = UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    //this user (as opposed to other members)
    if (TextUtils.isEmpty(ufsrvUid) || ufsrvUid.equals(UfsrvUid.UndefinedUfsrvUid)) {
      return processFenceCommandLeaveAccepted(context, envelope, message, groupRecordUfsrv, outgoing);
    } else {
      return processFenceCommandLeaveSynced(context, envelope, message, groupRecordUfsrv, outgoing);
    }

  }
  //

  //
  private static @Nullable
  Long processFenceCommandJoin(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @Nullable GroupRecord groupRecordUfsrv,
                                boolean outgoing)
  {
    FenceCommand fenceCommand = message.getUfsrvCommand().getFenceCommand();

    if (fenceCommand.getHeader().getArgs()==CommandArgs.GEO_BASED_VALUE) {
      //Automatic geo based join for this user based on current roaming mode setting
      return processFenceCommandJoinGeobased(context, envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs()==CommandArgs.INVITED_VALUE || fenceCommand.getHeader().getArgs()==CommandArgs.INVITED_GEO_VALUE) {
      //this user recieved invitation to join a group or recived geo-obased invitation because roaming mode is set to 'journaler'
      return processFenceCommandJoinInvited(context,  envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs()==CommandArgs.SYNCED_VALUE) {
      //somebody else joined, sync membership
      return processFenceCommandJoinSync(context, envelope, message, groupRecordUfsrv, outgoing);
    } else //UNCHANGED indicates a rejoin of a group of which  a user is already a member
    if (fenceCommand.getHeader().getArgs()==CommandArgs.ACCEPTED_VALUE || fenceCommand.getHeader().getArgs()==CommandArgs.ACCEPTED_INVITE_VALUE ||fenceCommand.getHeader().getArgs()==CommandArgs.CREATED_VALUE ||fenceCommand.getHeader().getArgs()==CommandArgs.UNCHANGED_VALUE) {
      //server acknowledging a previous request to join, this should be followed by another JOIN/SYNCED message
      return processFenceCommandJoinAccepted(context, envelope, message, groupRecordUfsrv, outgoing);
    } else if (fenceCommand.getHeader().getArgs()==CommandArgs.REJECTED_VALUE) {
      return processGroupJoinRejected (context, envelope, message, groupRecordUfsrv, false);
    } else
      Log.w(TAG, String.format("processFenceCommandJoin (%d): Received UNKNOWN JOIN COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));

    return Long.valueOf(-1);

  }
  //

  private static Long processGroupJoinRejected (@NonNull Context                     context,
                                                  @NonNull SignalServiceEnvelope     envelope,
                                                  @NonNull SignalServiceDataMessage  dataMessage,
                                                  @NonNull GroupRecord               groupRecord,
                                                           boolean                   outgoing)
{
  FenceCommand fenceCommand = dataMessage.getUfsrvCommand().getFenceCommand();
  Log.w(TAG, String.format("processGroupJoinRejected: Received JOIN REJECTED COMMAND: '%d', ARGS:'%d'FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));

  switch (fenceCommand.getHeader().getArgsError())
  {
    case FenceCommand.Errors.GROUP_DOESNT_EXIST_VALUE:
    return GroupJoinErrorDoesntExist (context, envelope, dataMessage, groupRecord!=null?Optional.of(groupRecord):Optional.absent(), false);

    case FenceCommand.Errors.INVITE_ONLY_VALUE:
      return GroupJoinErrorNotOnInviteList (context, envelope, dataMessage, groupRecord!=null?Optional.of(groupRecord):Optional.absent(), false);

    default:
      Log.w(TAG, String.format("processGroupJoinRejected: Received JOIN REJECTED ERROR: '%d', ARGS:'%d'FOR: ERROR:'%d', fid:'%d'", fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgsError(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
  }

    return Long.valueOf(-1);
  }


  private static Long GroupJoinErrorDoesntExist (@NonNull Context                  context,
                                                     @NonNull SignalServiceEnvelope     envelope,
                                                     @NonNull SignalServiceDataMessage  dataMessage,
                                                     @NonNull Optional<GroupRecord>     groupRecord,
                                                     boolean                            outgoing)
  {
    FenceCommand  fenceCommand  = dataMessage.getUfsrvCommand().getFenceCommand();

    if (groupRecord.isPresent()) {
      Address groupAddress = Address.fromSerialized(groupRecord.get().getEncodedId());
      Recipient recipient = Recipient.from(ApplicationContext.getInstance(), groupAddress, false);

      long threadId = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadIdIfExistsFor(recipient);

      DatabaseFactory.getGroupDatabase(context).cleanUpGroup(groupRecord.get().getEncodedId(), threadId);
    } else {
      long threadId = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadIdFor(null, fenceCommand.getFences(0).getFid());

      if (threadId>0) DatabaseFactory.getGroupDatabase(context).cleanUpGroup(null, threadId);
    }

    return Long.valueOf(-1);
  }


  //currently doesn't inform the user
  private static Long GroupJoinErrorNotOnInviteList (@NonNull Context                  context,
                                                @NonNull SignalServiceEnvelope     envelope,
                                                @NonNull SignalServiceDataMessage  dataMessage,
                                                @NonNull Optional<GroupRecord>               groupRecord,
                                                boolean                            outgoing)
  {
    FenceCommand  fenceCommand  = dataMessage.getUfsrvCommand().getFenceCommand();

    if (groupRecord.isPresent()) {
      Address groupAddress = Address.fromSerialized(groupRecord.get().getEncodedId());
      Recipient recipient = Recipient.from(ApplicationContext.getInstance(), groupAddress, false);

      long threadId = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadIdIfExistsFor(recipient);
      Log.w(TAG, String.format("GroupJoinErrorNotOnInviteList (%d): Received NotOnInviteList error: '%d', ARGS:'%d'FOR: fid:'%d': Issuing 'cleanUpGroup'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));
      DatabaseFactory.getGroupDatabase(context).cleanUpGroup(groupRecord.get().getEncodedId(), threadId);
    }
    else
    {
      Log.w(TAG, String.format("GroupJoinErrorNotOnInviteList (%d): Received NotOnInviteList error NO INTERNAL GROUP RECORD: '%d', ARGS:'%d'FOR: fid:'%d': Issuing 'cleanUpGroup'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid()));

      long threadId = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadIdFor(null, fenceCommand.getFences(0).getFid());
      if (threadId>0) DatabaseFactory.getGroupDatabase(context).cleanUpGroup(null, threadId);
    }

    return Long.valueOf(-1);
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
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandPermission (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

        msg.close();
      }
    }

    return Long.valueOf(threadId);

  }


  //A member has been added/removed into group's permission. Could be this user, others. The originator-and-group-owner gets ACCEPTED,
  //and won't be processed in this block
  private static Long
  processFenceCommandPermissionAdded (@NonNull Context                  context,
                                      @NonNull SignalServiceEnvelope    envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull GroupRecord              groupRecord,
                                      boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.fromFid(context, fenceRecord.getFid(), false);
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    long                    useridTarget  = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());

    return addUserToPermission (context, envelope, message, recipient,
                                     EnumFencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                     useridTarget, outgoing);
  }

  private static Long
  addUserToPermission (@NonNull Context                  context,
                          @NonNull SignalServiceEnvelope    envelope,
                          @NonNull SignalServiceDataMessage message,
                          @NonNull Recipient               recipient,
                          @NonNull EnumFencePermissions     permission,
                          long                     userId,
                          boolean                  outgoing)
  {
    RecipientDatabase databaseRecipientsPrefs = DatabaseFactory.getRecipientDatabase(context);
    databaseRecipientsPrefs.SetGroupPermissionForMember (context, recipient, permission, userId);

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, message.getGroupInfo().get(), groupContext, outgoing);
  }

  private static Long
  removeUserFromPermission (@NonNull Context                  context,
                           @NonNull SignalServiceEnvelope    envelope,
                           @NonNull SignalServiceDataMessage message,
                           @NonNull Recipient               recipient,
                           @NonNull EnumFencePermissions     permission,
                                    long                     userId,
                                    boolean                  outgoing)
  {
    RecipientDatabase databaseRecipientsPrefs = DatabaseFactory.getRecipientDatabase(context);
    databaseRecipientsPrefs.DeleteGroupPermissionForMember(context, recipient, permission, userId);

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, message.getGroupInfo().get(), groupContext, outgoing);
  }

  private static Long
  processFenceCommandPermissionDeleted (@NonNull Context                  context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
{
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.fromFid(context, fenceRecord.getFid(), false);
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if  (permission!=null)
    {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());

      return removeUserFromPermission (context, envelope, message, recipient,
                                       EnumFencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                       useridTarget, outgoing);
    }

    return Long.valueOf(-1);
  }


  private static Long
  processFenceCommandPermissionRejected (@NonNull Context                  context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient     =  Recipient.fromFid(context, fenceRecord.getFid(), false);
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if  (permission!=null)
    {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());
      if (envelope.getFenceCommand().getHeader().getArgsError()==FenceCommand.Errors.PERMISSIONS_VALUE) {//server did not have user on its own list
        //we originally requested to have this user removed. Perhaps our internal view is inconsistent
        if (envelope.getFenceCommand().getHeader().getArgsErrorClient()==CommandArgs.DELETED_VALUE) {
          return removeUserFromPermission (context, envelope, message, recipient,
                                           EnumFencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                           useridTarget, outgoing);
        } else  if (envelope.getFenceCommand().getHeader().getArgsErrorClient()==CommandArgs.ADDED_VALUE) {
          //we originally requested to add user, but user is is already on (in server's view). Perhaps our internal view is inconsistent
          return addUserToPermission (context, envelope, message, recipient,
                                      EnumFencePermissions.values()[envelope.getFenceCommand().getType().getNumber()],
                                      useridTarget, outgoing);
        }
      }
    }

    return Long.valueOf(-1);
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
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandMaxMembers (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

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
    DatabaseFactory.getGroupDatabase(context).updateGroupMaxMembers(fenceRecord.getFid(), fenceRecord.getMaxmembers());

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, message.getGroupInfo().get(), groupContext, outgoing);

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

    if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandDeliveryMode (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

        msg.close();
      }
    }

    return Long.valueOf(threadId);

  }

  private static Long
  processFenceCommandDeliveryModeUpdated (@NonNull Context                context,
                                        @NonNull SignalServiceEnvelope    envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @NonNull GroupRecord              groupRecord,
                                        boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    DatabaseFactory.getGroupDatabase(context).updateGroupDeliveryMode(fenceRecord.getFid(), GroupDatabase.DeliveryMode.values()[fenceRecord.getDeliveryMode().getNumber()]);

    GroupContext groupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    return storeMessage(context, envelope, message.getGroupInfo().get(), groupContext, outgoing);

  }

  private static Long
  processFenceCommandDeliveryModeRejected (@NonNull Context                  context,
                                         @NonNull SignalServiceEnvelope    envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @NonNull GroupRecord              groupRecord,
                                         boolean                           outgoing)
  {
    FenceRecord             fenceRecord   = envelope.getFenceCommand().getFences(0);
    Recipient               recipient    =  Recipient.fromFid(context, fenceRecord.getFid(), false);
    FenceRecord.Permission  permission    = UfsrvFenceUtils.getFenceCommandPermission(fenceRecord, envelope.getFenceCommand().getType());
    if (permission!=null) {
      long useridTarget = UfsrvUid.DecodeUfsrvSequenceId(permission.getUsers(0).getUfsrvuid().toByteArray());
      if (envelope.getFenceCommand().getHeader().getArgsError()==FenceCommand.Errors.PERMISSIONS_VALUE) /*server did not have user on its own list*/ {
      }
    }

    return Long.valueOf(-1);
  }

  //
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
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
    SignalServiceGroup group      = message.getGroupInfo().get();
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format("processFenceCommandState (%d): Received Fence State Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.SYNCED_VALUE:
        return processFenceCommandStateSynced(context, envelope, message, groupRecordUfsrv, outgoing);

      case CommandArgs.RESYNC_VALUE:
        return processFenceCommandStateResyncRequest(context, envelope, message, groupRecordUfsrv, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandState (%d): Received UNKNOWN FENCE STATE COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);

  }
  //

  private static @Nullable
  Long processFenceCommandFeneName(@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @Nullable GroupRecord groupRecordUfsrv,
                                    boolean outgoing)
  {
    SignalServiceGroup group      = message.getGroupInfo().get();
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

    return Long.valueOf(-1);

  }

  private static @Nullable
  Long processFenceCommandAvatar(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @Nullable GroupRecord groupRecord,
                                boolean outgoing)
  {
    SignalServiceGroup group      = message.getGroupInfo().get();
    FenceCommand fenceCommand     = message.getUfsrvCommand().getFenceCommand();
    FenceRecord fenceRecord       = fenceCommand.getFences(0);

    Log.d(TAG, String.format("processFenceCommandAvatar (%d): Received Fence State Command (ARGS:'%d'): FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getArgs(), fenceRecord.getFid(), fenceRecord.getCname()));

    switch (fenceCommand.getHeader().getArgs())
    {
      case CommandArgs.UPDATED_VALUE:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received UPDTED FENCE AVATAR COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleGroupAvatarUpdateUfsrv (context, envelope, group, groupRecord, outgoing);

      case CommandArgs.ACCEPTED_VALUE:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received ACCEPTED FENCE AVATAR COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleGroupAvatarUpdateUfsrv (context,  envelope, group, groupRecord, outgoing);

      default:
        Log.w(TAG, String.format("processFenceCommandAvatar (%d): Received UNKNOWN FENCE STATE COMMAND ARGS: COMMAND: '%d', ARGS:'%d'FOR: fid:'%d', cname:'%s'", Thread.currentThread().getId(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);

  }


  //change in group invite membership: someone has been attached or removed..
  private static @Nullable Long
  processFenceCommandInviteUpdated (@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @Nullable GroupRecord groupRecordUfsrv,
                                    boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();
      byte[]        id         = group.getGroupId()!=null?group.getGroupId():groupRecordUfsrv.getId();

      return UpdateFenceInvitedMembership (context, envelope, group, groupRecordUfsrv, id, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandInviteUpdated (%d): Received SERVER INVITE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable Long
  processFenceCommandInviteUnchanged (@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @Nullable GroupRecord groupRecordUfsrv,
                                    boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();
      byte[]        id         = group.getGroupId()!=null?group.getGroupId():groupRecordUfsrv.getId();

      return UpdateFenceInvitedMembership (context, envelope, group, groupRecordUfsrv, id, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandInviteUpdated (%d): Received SERVER INVITE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processFenceCommandJoinGeobased(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @Nullable GroupRecord groupRecordUfsrv,
                                      boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format("processFenceCommandJoinGeobased (%d): Received SERVER GEO-BASED JOIN REQUEST'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));

    if (groupRecordUfsrv==null) {
      SignalServiceGroup group                = message.getGroupInfo().get();
      GroupDatabase.PrivacyMode privacyMode   = GroupDatabase.PrivacyMode.values()[fenceCommand.getFences(0).getPrivacyMode().getNumber()];
      GroupDatabase.DeliveryMode deliveryMode = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

      return handleFenceCreateUfsrv(context, envelope, message.getGroupInfo().orNull(),
              false, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED,
              GroupDatabase.GroupType.GEO, privacyMode, deliveryMode);
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d): Received SERVER GEO-BASED JOIN REQUEST for EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
      ThreadDatabase  threadDatabase  = DatabaseFactory.getThreadDatabase(context);
      long            threadId        = threadDatabase.getThreadIdFor(null, fenceCommand.getFences(0).getFid());

      if (threadId==-1) {
        Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d, fid:'%d'): ERROR: DATA INTEGRITY: Received SERVER JOIN GEO-BASED REQUEST for EXISTING GROUP': '%s' BUT NO CORRESPONDING Thread WAS FOUND : creating one", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        return handleFencePartialCreateUfsrv(context, envelope, message.getGroupInfo().orNull(), false, GroupDatabase.GROUP_MODE_GEOBASED_JOIN);
      }else {
        Log.e(TAG, String.format("processFenceCommandJoinGeobased (%d, fid:'%d'): Received SERVER GEO-BASED JOIN REQUEST for EXISTING GROUP': '%s' WITH ThreadId: '%d': creating one", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname(), threadId));
        return handleFenceRejoinExistingUfsrv(context, envelope, message.getGroupInfo().orNull(), false, GroupDatabase.GROUP_MODE_GEOBASED_JOIN, threadId);
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
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    GroupDatabase.GroupType groupType       = GroupDatabase.GroupType.values()[fenceCommand.getFences(0).getFenceType().getNumber()];
    GroupDatabase.PrivacyMode privacyMode   = GroupDatabase.PrivacyMode.values()[fenceCommand.getFences(0).getPrivacyMode().getNumber()];
    GroupDatabase.DeliveryMode deliveryMode = GroupDatabase.DeliveryMode.values()[fenceCommand.getFences(0).getDeliveryMode().getNumber()];

    Log.d(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED JOIN REQUEST'. Fence name:'%s', fence_type:'%d', pivacy_mode:'%d', delivery_mode:'%d'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupType.getValue(), privacyMode.getValue(), deliveryMode.getValue()));

    if (groupRecordUfsrv==null) {
      return handleFenceCreateUfsrv(context, envelope, message.getGroupInfo().orNull(), false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else if (!groupRecordUfsrv.isActive()) {
      Log.w(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING INACTIVE GROUP': '%s': REACTIVATING...", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));

      //todo: this may not be a desirable effect, as we nuke everything out of laziness. perhaps just "reactivate" so prior thread data is preserved
      long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, groupRecordUfsrv.getFid()<=0?envelope.getFenceCommand().getFences(0).getFid():groupRecordUfsrv.getFid());
      DatabaseFactory.getGroupDatabase(context).cleanUpGroup (groupRecordUfsrv.getEncodedId(), threadId);

      return handleFenceCreateUfsrv(context, envelope, message.getGroupInfo().orNull(), false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else if (groupRecordUfsrv.getMode()==GROUP_MODE_INVITATION|| groupRecordUfsrv.getMode()==GROUP_MODE_GEOBASED_INVITE) {
      Log.w(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING GROUP': '%s'. isActive:'%b': SYNCING", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));
      long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, groupRecordUfsrv.getFid()<=0?envelope.getFenceCommand().getFences(0).getFid():groupRecordUfsrv.getFid());
      DatabaseFactory.getGroupDatabase(context).cleanUpGroup(groupRecordUfsrv.getEncodedId(), threadId);

      return handleFenceCreateUfsrv(context, envelope, message.getGroupInfo().orNull(), false, GroupDatabase.GROUP_MODE_INVITATION, groupType, privacyMode, deliveryMode);
    } else {
      //todo: check if already member of the invite list, since we have this group on as active it must be doing something for the user, could be inconsistent state
      Log.e(TAG, String.format("processFenceCommandJoinInvited (%d): Received SERVER INVITED REQUEST for EXISTING GROUP': '%s'. isActive:'%b': IGNORING", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname(), groupRecordUfsrv.isActive()));
    }

    return Long.valueOf(-1);
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
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group    = message.getGroupInfo().get();
      GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
      GroupRecord groupRecord     = groupDatabase.getGroupRecordByFid(fenceCommand.getFences(0).getFid());

      if (groupDatabase.isGroupInvitationPending(fenceCommand.getFences(0).getFid())) groupDatabase.markGroupMode(fenceCommand.getFences(0).getFid(), GROUP_MODE_INVITATION_JOIN_ACCEPTED);

      long messageId=handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
      if (messageId>0 && (envelope.getFenceCommand().getHeader().getWhenClient()>0)) {
        Log.d(TAG, String.format("processFenceCommandJoinAccepted (timestamp:'%d', fid:'%d', cname:'%s'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceCommand.getFences(0).getFid(), fenceCommand.getFences(0).getCname()));
        MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
        if (msg != null) {
          Log.d(TAG, String.format("processFenceCommandJoinAccepted (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
          DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

          msg.close();
        }
      }
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinAccepted (%d): Received SERVER ACCEPTED JOIN REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }


  //change in group membership: someone joined.... This is also sent to this user to confirm fence configuration In this case the originator is null, because it is a system message
  private static @Nullable
  Long processFenceCommandJoinSync(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @Nullable GroupRecord groupRecordUfsrv,
                                       boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();

      return handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandJoinSync (%d): Received SERVER JOIN SYNC REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  //change in group membership: this user left...
  private static @Nullable
  Long processFenceCommandLeaveAccepted(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecordUfsrv,
                                         boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();

      return handleGroupLeaveUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandLeaveAccepted (%d): Received SERVER  LEAVE ACCEPTED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  //change in group membership: someone left...
  private static @Nullable
  Long processFenceCommandLeaveSynced(@NonNull Context context,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceDataMessage message,
                                        @Nullable GroupRecord groupRecordUfsrv,
                                        boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();
      Log.d(TAG, String.format("processFenceCommandLeaveSynced (%d): Member %s is LEAVING Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getOriginator().getUsername(), fenceCommand.getFences(0).getCname()));
      return handleGroupLeaveUfsrv(context, envelope, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandLeaveSynced (%d): Received SERVER  LEAVE SYNC REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  //change in group membership: someone I left...
  private static @Nullable
  Long processFenceCommandStateSynced(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @Nullable GroupRecord groupRecordUfsrv,
                                      boolean outgoing)
  {
    SignalServiceProtos.FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format("processFenceCommandStateSynced (%d): Received SERVER FENCE STATE SYNCED'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));

    if (groupRecordUfsrv!=null) {
      SignalServiceGroup group = message.getGroupInfo().get();
     return handleGroupUpdateUfsrvJoinedOrSynced(context, envelope, message, group, groupRecordUfsrv, outgoing);
    } else {
      Log.e(TAG, String.format("processFenceCommandStateSynced (%d): Received SERVER ENCE STATE SYNCED REQUEST for NON EXISTING GROUP': '%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getCname()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processFenceCommandStateResyncRequest (@NonNull Context context,
                                              @NonNull SignalServiceEnvelope envelope,
                                              @NonNull SignalServiceDataMessage message,
                                              @Nullable GroupRecord groupRecordUfsrv,
                                              boolean outgoing)
  {
    FenceCommand fenceCommand=envelope.getUfsrvCommand().getFenceCommand();

    Log.d(TAG, String.format("processFenceCommandStateResyncRequest (%d, fid:'%d'): Received SERVER FENCE STATE RESYNC REQUEST'. Fence name:'%s'", Thread.currentThread().getId(), fenceCommand.getFences(0).getFid()));

    return  UfsrvFenceUtils.sendStateSyncForGroup(fenceCommand.getFences(0).getFid());

  }

  private static @Nullable
  Long processFenceCommandMessageExpiry (@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @Nullable GroupRecord groupRecord,
                                         boolean outgoing)
  {
    SignalServiceGroup group      = message.getGroupInfo().get();
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

    return Long.valueOf(-1);

  }


  //if the originator is other than this user, SignalServiceGroup will be hoax because
  // the server doesnt set that. Hence why we need to check for internal id
  //header.args==COMMAND_ARGS__ACCEPTED if leave was self initiate for self, otherwise indicated as COMMAND_ARGS__SYNCED for others
  //other possible values: COMMAND_ARGS__GEOBASED, BANNED, INVLIDTED etc...
  //
  private static Long handleGroupLeaveUfsrv(@NonNull Context               context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceGroup    group,
                                            @NonNull GroupRecord           record,
                                            boolean  outgoing)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    String groupId              = GroupUtil.getEncodedId(group.getGroupId(), false);
    List<Address>  members      = record.getMembers();

    SignalServiceProtos.FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder                = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.QUIT);
    final String ufsrvUid;

    if (!envelope.getFenceCommand().hasOriginator())  ufsrvUid=TextSecurePreferences.getUfsrvUserId(context);
    else ufsrvUid=UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    if (groupId==null) groupId = groupDatabase.getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), false);

    final Address ufsrvUidAddress = Address.fromSerialized(ufsrvUid);
    final int groupLeaveMode      = groupDatabase.getGroupMode (fenceRecord.getFid());

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
    }

    Address addressLeaving = Stream.of(members)
            .filter(x -> x.compareTo(ufsrvUidAddress)==0)
            .findFirst()
            .orElse(null);
    if (addressLeaving!=null) {
      groupDatabase.remove(groupId, Address.fromSerialized(ufsrvUid));
      if (outgoing) groupDatabase.setActive(groupId, false);

      if (ufsrvUid.equals(TextSecurePreferences.getUfsrvUserId(context))) {
        groupDatabase.setActive(groupId, false);
      }
    } else {
      Log.e(TAG, String.format("handleGroupLeaveUfsrv: ERROR: DATA INTEGRITY {fid:'%d', source:'%s'}: SOURCE WAS NOT ON THE GROUP MEMBERS LIST: DELTEING ANYWAY", fenceRecord.getFid(),  ufsrvUid));
      groupDatabase.setActive(groupId, false);
    }

    if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
      Log.d(TAG, String.format("handleGroupLeaveUfsrv (timestamp:'%d', fid:'%d'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceRecord.getFid()));
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("handleGroupLeaveUfsrv (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceRecord.getFid(), msg.getId()));
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());
        msg.close();
      }
    }

    long threadId = storeMessage(context, envelope, group, builder.build(), outgoing);

    if (groupLeaveMode==GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP) {
      groupDatabase.cleanUpGroup(groupId, threadId);
      ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventGroupDestroyed(groupId));
    }

    return threadId;

  }
  //

  private static Long
  handleGroupLeaveUninvited(@NonNull Context               context,
                            @NonNull SignalServiceEnvelope envelope,
                            @NonNull SignalServiceGroup    group,
                            @NonNull GroupRecord           record,
                            boolean  outgoing)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]        id              = group.getGroupId();
    List<Address>  membersInvited  = record.getMembersInvited();

    //
    FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    UserRecord  userRecord  = fenceRecord.getInvitedMembers(0);

    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.QUIT);//computes group id
//    String originator=new UfsrvUid(envelope.getFenceCommand().getOriginator().getUfsrvuid().toByteArray()).toString();//this could be system user, or this user

    //this user (as opposed to other members)
//    ?if (TextUtils.isEmpty(originator))  originator=TextSecurePreferences.getUfsrvUserId(context);
    //
    String originator;
    if (!envelope.getFenceCommand().hasOriginator())  originator=TextSecurePreferences.getUfsrvUserId(context);
    else originator=UfsrvUidEncodedForOriginator(envelope.getFenceCommand());

    Log.e(TAG, String.format("handleGroupLeaveUninvited: RECEIVED UNINVITE {fid:'%d', originator:'%s', uninvited_uid:'%d'}:", fenceRecord.getFid(), originator, UfsrvUid.DecodeUfsrvSequenceId(userRecord.getUfsrvuid().toByteArray())));

    String invited = userRecord.hasUfsrvuid()?UfsrvUid.EncodedfromSerialisedBytes(userRecord.getUfsrvuid().toByteArray()):"";

    if (membersInvited.contains(invited)) {
      List<Address> usersAffeted=UserRecordsToAddressList(fenceRecord.getInvitedMembersList(),  Optional.absent());

      if (fenceRecord.getInvitedMembersCount()>0) {
        groupDatabase.updateInvitedMembers(fenceRecord.getFid(), usersAffeted, REMOVE_MEMBER);
        if (TextSecurePreferences.getUfsrvUserId(context).equals(invited)) {
          //delete group/thread
          long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, fenceRecord.getFid());
          groupDatabase.cleanUpGroup (GroupUtil.getEncodedId(id, false), threadId);
        } else {
          //store message and update Messagerecord
          return storeMessage(context, envelope, group, builder.build(), outgoing);
        }
      }
    } else {
      Log.e(TAG, String.format("handleGroupLeaveUninvited: ERROR: DATA INTEGRITY {fid:'%d', originator:'%s', uninvited_uid:'%d'}: uninvited user not on list", fenceRecord.getFid(), originator, UfsrvUid.DecodeUfsrvSequenceId(userRecord.getUfsrvuid().toByteArray())));
    }

    return Long.valueOf(-1); //no msg stored for this event
  }

  private static Long handleGroupNameUpdateUfsrv(@NonNull Context               context,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SignalServiceGroup    group,
                                                 @NonNull GroupRecord           groupRecord,
                                                 boolean  outgoing)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]        id            = group.getGroupId();

    SignalServiceProtos.FenceRecord fenceRecord=envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE);

    if (!TextUtils.isEmpty(fenceRecord.getFname()) && !groupRecord.getTitle().equals(fenceRecord.getFname())) {
      Log.e(TAG, String.format("handleGroupNameUpdateUfsrv (fid:'%d', FenceRecord.name:'%s', GroupDatabase.name:'%s'): Updating fence name", fenceRecord.getFid(), fenceRecord.getFname(), groupRecord.getTitle()));
      groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
      String cnameUpdated=UfsrvFenceUtils.updateCnameWithNewFname (fenceRecord.getFid(), groupRecord.getCname(), groupRecord.getTitle(), fenceRecord.getFname());
      groupDatabase.updateCname(fenceRecord.getFid(), cnameUpdated);
    }

    return storeMessage(context, envelope, group, builder.build(), outgoing);

  }

  private static Long handleGroupNameRejectionUfsrv(@NonNull Context               context,
                                                     @NonNull SignalServiceEnvelope envelope,
                                                     @NonNull SignalServiceGroup    group,
                                                     @NonNull GroupRecord           groupRecord,
                                                     boolean  outgoing)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]        id            = group.getGroupId();

    SignalServiceProtos.FenceRecord fenceRecord=envelope.getFenceCommand().getFences(0);
    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE);

    groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
    if (fenceRecord.getCname()!=null) groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

    //delete original request message using timestamp as identifier
    return storeMessage(context, envelope, group, builder.build(), outgoing);

  }

  private static Long handleGroupAvatarUpdateUfsrv(@NonNull Context               context,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SignalServiceGroup    group,
                                                 @NonNull GroupRecord           groupRecord,
                                                 boolean  outgoing)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]        id            = group.getGroupId();
    String groupIdEncoded;

    //
    SignalServiceProtos.FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);
    GroupContext groupContext                   = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE).build();
    if (id == null) {
      try {
        groupIdEncoded = groupDatabase.getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), false);
        id = GroupUtil.getDecodedId(groupIdEncoded);
      } catch (IOException ex) {
        Log.d(TAG, String.format(ex.getMessage()));
        return Long.valueOf(-1);
      }
    }
    else groupIdEncoded = GroupUtil.getEncodedId(id, false);
    //

    if (groupContext.hasAvatar()) {
      SignalServiceAttachment avatar = new SignalServiceAttachmentPointer(groupContext.getAvatar().getUfid(),
                                                                          0,
                                                                          groupContext.getAvatar().getContentType(),
                                                                          groupContext.getAvatar().getKey().toByteArray(),
                                                                          groupContext.getAvatar().hasDigest() ? Optional.of(groupContext.getAvatar().getDigest().toByteArray()) : Optional.<byte[]>absent(),
                                                                          groupContext.getAvatar().hasFileName()? Optional.of(groupContext.getAvatar().getFileName()):Optional.absent(),
                                                                          false);

      groupDatabase.update(groupIdEncoded, null, avatar.asPointer());

      Log.e(TAG, String.format("handleGroupAvatarUpdateUfsrv (fid:'%d', id:'%s): Updating avatar", fenceRecord.getFid(), avatar.asPointer().getUfId()));

      return storeMessage(context, envelope, group, groupContext, outgoing);

    }

    return Long.valueOf(-1);
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
    FenceRecord         fenceRecord   = envelope.getFenceCommand().getFences(0);
    MmsDatabase         database      = DatabaseFactory.getMmsDatabase(context);
    Recipient           recipient     =  Recipient.fromFid(context, fenceRecord.getFid(), false);

    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Address.fromSerialized(envelope.getSource()),
                                                                 message.getTimestamp(), -1,
                                                                 fenceRecord.getExpireTimer(), true, //in millisec
                                                                 0,
                                                                 false,
                                                                 Optional.absent(), message.getGroupInfo(),
                                                                 Optional.absent(),
                                                                 Optional.absent(),
                                                                 Optional.absent(),
                                                                 Optional.absent(),
                                                                 Optional.absent(),
                                                                 envelope.getUfsrvCommand());//
    try
    {
      Optional<MessagingDatabase.InsertResult> result=database.insertSecureDecryptedMessageInbox(mediaMessage, -1);
      if (result.isPresent()) {
        DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, (int)fenceRecord.getExpireTimer()/1000);//in seconds

        if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
          MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
          if (msg != null) {
            Log.d(TAG, String.format("handleMessageExpiryUpdate (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceRecord.getFid(), msg.getId()));
            DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

            msg.close();
          }
        }

        return result.get().getMessageId();
      }
    } catch (MmsException x) {
      Log.d(TAG, x.getMessage());
    }

    return Long.valueOf(-1);
  }
  //

  /**
   *  This reflects a group for which there is a reference in the GroupDatabase, but not in the ThreadDatabase.
   */
  private static @Nullable Long handleFencePartialCreateUfsrv(@NonNull Context context,
                                                       @NonNull SignalServiceEnvelope envelope,
                                                       @NonNull SignalServiceGroup group,
                                                       boolean outgoing,
                                                       int mode)
  {
    SignalServiceProtos.UfsrvCommandWire  ufsrvCommand=envelope.getUfsrvCommand();
    SignalServiceProtos.FenceCommand      fenceCommand=ufsrvCommand.getFenceCommand();
    SignalServiceProtos.FenceRecord       fenceRecord=fenceCommand.getFences(0);
    SignalServiceGroup                    mockGroup=null;
    SignalServiceGroup                    actualGroup;

    GroupContext.Builder    builder;
    SignalServiceAttachment avatar;
    GroupDatabase           groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]                  id            = (group!=null && group.getGroupId()!=null)?group.getGroupId():groupDatabase.allocateGroupId();


    if (group!=null && group.getGroupId()!=null) {
      builder = createGroupContext(group);
      builder.setType(GroupContext.Type.UPDATE);
      avatar = group.getAvatar().orNull();
    } else {
      //create mock object to simulate one received from client, as server originting messages dont always include that
      builder = GroupContext.newBuilder().setId(ByteString.copyFrom(id))
                                          .setType(GroupContext.Type.UPDATE)
                                          .setFenceMessage(fenceCommand)
                                          .addAllMembers(UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()));
      builder.setName(fenceRecord.getFname());

      //Extract the avatar from attachments list.
      List<SignalServiceAttachment> attachments = new LinkedList<>();
      for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
        attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), 0,//  ufid
                                                          pointer.getContentType(),
                                                          pointer.getKey().toByteArray(),
                                                          pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.absent(),
                                                          pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.absent(),
                                                          pointer.getWidth(),
                                                          pointer.getHeight(),
                                                          pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.absent(),
                                                          pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.absent(),
                                                           false,
                                                           pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.absent()));
      }
      avatar=attachments.size()>0?attachments.get(0):null;

      mockGroup=new SignalServiceGroup(SignalServiceGroup.Type.UPDATE, id, fenceRecord.getFname(), UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()), avatar, fenceCommand);
      Log.w(TAG, String.format("handleFencePartialCreateUfsrv (%d): Created a mock GroupContext: fid:'%d', fcname:'%s", Thread.currentThread().getId(), fenceRecord.getFid(), fenceRecord.getCname()));
    }


    if (group!=null)  actualGroup=group;
    else              actualGroup=mockGroup;

    groupDatabase.markGroupMode(fenceRecord.getFid(), mode);

    return storeMessage(context, envelope, actualGroup, builder.build(), outgoing);
  }


private static @Nullable Long handleFenceRejoinExistingUfsrv (@NonNull Context context,
                                                              @NonNull SignalServiceEnvelope envelope,
                                                              @NonNull SignalServiceGroup group,
                                                              boolean outgoing,
                                                              int mode,
                                                              long threadId)
{
  SignalServiceProtos.UfsrvCommandWire  ufsrvCommand=envelope.getUfsrvCommand();
  SignalServiceProtos.FenceCommand      fenceCommand=ufsrvCommand.getFenceCommand();
  SignalServiceProtos.FenceRecord       fenceRecord=fenceCommand.getFences(0);
  SignalServiceGroup                    mockGroup=null;
  SignalServiceGroup                    actualGroup;
  String groupIdEncoded;

  GroupContext.Builder    builder;
  SignalServiceAttachment avatar;
  GroupDatabase           groupDatabase = DatabaseFactory.getGroupDatabase(context);
  byte[]                  id            = (group!=null && group.getGroupId()!=null)?group.getGroupId():groupDatabase.allocateGroupId();

  groupIdEncoded  = GroupUtil.getEncodedId(id, false);

  if (group!=null && group.getGroupId()!=null) {
    builder = createGroupContext(group);
    builder.setType(GroupContext.Type.UPDATE);
    avatar = group.getAvatar().orNull();
  } else {
    //create mock object to simulate one received from client, as server originting messages dont always include that
    builder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(id))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand)
            .addAllMembers(UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()));
    builder.setName(fenceRecord.getFname());

    //Extract the avatar from attachments list.
    List<SignalServiceAttachment> attachments = new LinkedList<>();
    for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), 0,//  ufid
                                                          pointer.getContentType(),
                                                          pointer.getKey().toByteArray(),
                                                          pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.absent(),
                                                          pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.absent(),
                                                          pointer.getWidth(),
                                                          pointer.getHeight(),
                                                          pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.absent(),
                                                          pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.absent(),
                                                          false,
                                                         pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.absent()));
    }
    avatar=attachments.size()>0?attachments.get(0):null;

    //alternative lightweight build
    mockGroup=new SignalServiceGroup(SignalServiceGroup.Type.UPDATE, id, fenceRecord.getFname(), UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()), avatar, fenceCommand);
    Log.w(TAG, String.format("handleFencePartialCreateUfsrv (%d): Created a mock GroupContext: fid:'%d', fcname:'%s", Thread.currentThread().getId(), fenceRecord.getFid(), fenceRecord.getCname()));
  }

  if    (group!=null)   actualGroup=group;
  else                  actualGroup=mockGroup;

  groupDatabase.markGroupMode(fenceRecord.getFid(), mode);

  groupDatabase.setActive(groupIdEncoded, true);

  return storeMessage(context, envelope, actualGroup, builder.build(), outgoing);
}

  //group creation semantics are different under ufsrv:
  //1)we may received invitation, in which case we won't have a corresponding record in groupData
  //and the server will not have groupId provided , which we need internally before Incoming message is stored
  //This reflects a request to create a fence for which we may, OR MAY NOT have a previous record, for example member invitation to join, or geobased
  private static @Nullable Long handleFenceCreateUfsrv(@NonNull Context context,
                                                       @NonNull SignalServiceEnvelope envelope,
                                                       @NonNull SignalServiceGroup group,
                                                       boolean outgoing,
                                                       int mode,
                                                       GroupDatabase.GroupType groupType,
                                                       GroupDatabase.PrivacyMode privacyMode,
                                                       GroupDatabase.DeliveryMode deliveryMode)
  {
    SignalServiceProtos.UfsrvCommandWire  ufsrvCommand=envelope.getUfsrvCommand();
    SignalServiceProtos.FenceCommand      fenceCommand=ufsrvCommand.getFenceCommand();
    SignalServiceProtos.FenceRecord       fenceRecord=fenceCommand.getFences(0);
    SignalServiceGroup                    mockGroup=null;
    SignalServiceGroup                    actualGroup;

    GroupContext.Builder    builder;
    SignalServiceAttachment avatar;
    GroupDatabase           groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[]                  id            = (group!=null && group.getGroupId()!=null)?group.getGroupId():groupDatabase.allocateGroupId();


    if (group!=null && group.getGroupId()!=null) {
      builder = createGroupContext(group);
      builder.setType(GroupContext.Type.UPDATE);
      avatar = group.getAvatar().orNull();
    } else {
      //create mock object to simulate one received from client, as server originating messages dont always include that
      builder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(id))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand)
              .addAllMembers(UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()));
      builder.setName(fenceRecord.getFname());

      List<SignalServiceAttachment> attachments = new LinkedList<>();
      {
        for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
          attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), 0,// ufid
                                                              pointer.getContentType(),
                                                              pointer.getKey().toByteArray(),
                                                              pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.absent(),
                                                              pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.absent(),
                                                              pointer.getWidth(),
                                                              pointer.getHeight(),
                                                              pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.absent(),
                                                              pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.absent(),
                                                              false,
                                                              pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.absent()));
        }
        avatar=attachments.size()>0?attachments.get(0):null;
      }

      //alternative lightweight build
      mockGroup=new SignalServiceGroup(SignalServiceGroup.Type.UPDATE, id, fenceRecord.getFname(), UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()), avatar, fenceCommand);
    }

    if (group!=null)  actualGroup=group;
    else              actualGroup=mockGroup;

    groupDatabase.create (GroupUtil.getEncodedId(id, false),
                          fenceRecord,
                          avatar != null && avatar.isPointer() ? avatar.asPointer() : null,
                          mode,
                          fenceCommand.getHeader().getWhen());

    return storeMessage(context, envelope, actualGroup, builder.build(), outgoing);
  }


  // this mostly defunct now. The bulk of the handling is in processUfsrvFenceCommand
  public static @Nullable Long process(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       boolean outgoing)
  {

//- we tolerate this now, as there are other checks and balances we can use based on ufsrv state
//    if (!message.getGroupInfo().isPresent() || message.getGroupInfo().get().getGroupId() == null) {
//      Log.w(TAG, "Received group message with no id! Ignoring...");
//      return null;
//    }
//

    // diagnostics...
    SignalServiceProtos.FenceRecord fenceRecord=null;
    if (message.getUfsrvCommand().getUfsrvtype()== SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE) {
      SignalServiceProtos.FenceCommand fence = message.getUfsrvCommand().getFenceCommand();
      if (fence != null) {
        if (fence.getFencesCount() > 0) {
          fenceRecord=fence.getFences(0);
          Log.d(TAG, String.format("process (%d): Received fence information: fid:'%d', fcname:'%s", Thread.currentThread().getId(), fenceRecord.getFid(), fenceRecord.getCname()));

        } else {
          Log.d(TAG, String.format("process (%d): Received NO FENCE RECORD: fence eid:'%d", Thread.currentThread().getId(), fence.getHeader().getEid()));
        }
      } else {
        Log.d(TAG, String.format("process (%d): Fence was null", Thread.currentThread().getId()));
      }
    }
    //

    GroupDatabase      database = DatabaseFactory.getGroupDatabase(context);
    SignalServiceGroup group    = message.getGroupInfo().get();
    String             id       = GroupUtil.getEncodedId(group.getGroupId(), false);
    GroupRecord        record   = database.getGroupByGroupId(id);

    // retrieve record using whatever key is available cname or fid
    GroupRecord         recordFid     = fenceRecord!=null?database.getGroupRecordByFid(fenceRecord.getFid()):null;//
    GroupRecord         recordCname   = fenceRecord!=null ? database.getGroupByCname(fenceRecord.getCname()) : null;//
    GroupRecord         recordUfsrv   = null;


    if (recordFid!=null)  recordUfsrv=recordFid;
    else
    if (recordCname!=null) recordUfsrv=recordCname;
    else
    if (record!=null) {
      recordUfsrv=record;
      Log.w(TAG, String.format("process: GroupRecord was assigned from internal id"));
    }


    if ((record != null && group.getType() == SignalServiceGroup.Type.UPDATE) || (recordUfsrv!=null)) {//
      //return handleGroupUpdate(context, masterSecret, envelope, group, record, outgoing);
      //return handleGroupUpdateUfsrvJoinedOrSynced(context, masterSecret, envelope, group, recordUfsrv /*record*/, outgoing);// swapped with record retrieed based on fid
      return null;//
    } else if (record == null && group.getType() == SignalServiceGroup.Type.UPDATE) {
      //return handleGroupCreate(context, masterSecret, envelope, group, outgoing);//-
      return null;//
    } else if (record != null && group.getType() == SignalServiceGroup.Type.QUIT) {
      //return handleGroupLeave(context, masterSecret, envelope, group, record, outgoing);
      return null;//
    } else if (record != null && group.getType() == Type.REQUEST_INFO) {
       return handleGroupInfoRequest(context, envelope, group, record);
    } else {
      Log.w(TAG, "Received unknown type, ignoring...");
      return null;
    }
  }

  private static final boolean isCommandJoinOrStateSync(FenceCommand fenceCommand)
  {
    return  fenceCommand.getHeader().getCommand()== FenceCommand.CommandTypes.JOIN_VALUE ||
            fenceCommand.getHeader().getCommand()== FenceCommand.CommandTypes.STATE_VALUE;
  }


  //Adapt wire command arguments to local context.
  // DONT INVOKE THIS ON CommandArgs.SYNCED_VALUE. because that is not a group creation lifecycle event
  //as far as group mode is concerned for this user
  private static final int setGroupModeFromFenceCommandJoin (FenceCommand fenceCommand)
  {
    CommandHeader commandHeader=fenceCommand.getHeader();

    if (commandHeader.getCommand()!= FenceCommand.CommandTypes.JOIN_VALUE)  return -1;

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
  private static @Nullable Long handleGroupUpdateUfsrvJoinedOrSynced (@NonNull Context context,
                                                                      @NonNull SignalServiceEnvelope envelope,
                                                                      @NonNull SignalServiceDataMessage message,
                                                                      @NonNull SignalServiceGroup group,
                                                                      @NonNull GroupRecord groupRecord,
                                                                      boolean outgoing)
  {
    byte[]        id        = null;
    String groupIdEncoded   = null;
    GroupDatabase groupDatabase  = DatabaseFactory.getGroupDatabase(context);

    try {
      id = group.getGroupId() != null ? group.getGroupId() : GroupUtil.getDecodedId(groupRecord.getEncodedId());
      groupIdEncoded  = groupRecord.getEncodedId();
    } catch (IOException ex) {
      Log.e(TAG, String.format(ex.getMessage()));
    }

    if (id==null) {
      Log.e(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: ERROR: COULD NOT ESTABLISH INTERNAL GROUPID: ALLOCATING ONE..."));

      id=groupDatabase.allocateGroupId();
      groupDatabase.updateGroupId(groupRecord.getFid(), id);
    }

    //
    FenceCommand    fenceCommand    = envelope.getFenceCommand();
    ThreadDatabase  threadDatabase  = DatabaseFactory.getThreadDatabase(context);
    Address         address         = Address.fromExternal(context, GroupUtil.getEncodedId(group.getGroupId(), false));
    Recipient       groupRecipient  = Recipient.from(context, address, false);
    long            threadId        = threadDatabase.getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT);

    if (fenceCommand==null || fenceCommand.getFencesCount()<=0) {
      Log.e(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: Could not load FenceCommand for '%s. Fence count:'%d''", GroupUtil.getEncodedId(id, false), fenceCommand!=null?fenceCommand.getFencesCount():-1));
      return Long.valueOf(-1);
    }

    FenceRecord fenceRecord=fenceCommand.getFences(0);

    if (!groupRecord.isActive()) {
      Log.e(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: RECEIVED SYNC FOR INACTIVE GROUP: stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d'", groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));
      groupDatabase.setActive(groupIdEncoded, true);
    }

    Log.d(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: active:'%b', stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d'", groupRecord.isActive(), groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));

    //fcname is known internally, so this could be a confirmation response to a previous JOIN request
    if (isCommandJoinOrStateSync(fenceCommand) && !TextUtils.isEmpty(groupRecord.getCname()) && groupRecord.getCname().equals(fenceRecord.getCname())) {
      if (fenceRecord.getFid()==groupRecord.getFid())/*fence already in the db as groupRecord has a valid fid*/ {
        Log.d(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: SYNCING existing fence '%s. fid:'%d ", fenceRecord.getCname(), fenceRecord.getFid() ));
      } else {
        //this is a case where this user initiated a join by name therefore we wouldn't have  fid until this confirmation arrived
        Log.d(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: SYNCHING UNCONFIRMED fence '%s. fid:'%d ", fenceRecord.getCname(), fenceRecord.getFid()));

        groupDatabase.updateFid(fenceRecord.getCname(), fenceRecord.getFid());
        threadDatabase.updateFid(threadId, fenceRecord.getFid());
        DatabaseFactory.getRecipientDatabase(context).setUfsrvId(groupRecipient, fenceRecord.getFid());
      }

      Long messageId=updateFenceData (context, envelope,  message, group, groupRecord,  id, threadId, outgoing);

       if (messageId!=null) {
         if (messageId.longValue() > 0 && fenceCommand.getHeader().getArgs() != CommandArgs.SYNCED_VALUE)
           groupDatabase.markGroupMode(fenceRecord.getFid(), setGroupModeFromFenceCommandJoin(fenceCommand));

         return messageId;
       }
    } else {
      Log.e(TAG, String.format("handleGroupUpdateUfsrvJoinedOrSynced: INCONSISTENT FENCE COMMAND DATA (fenceRecord.FID:'%d', groupRecord.FID:'%d'): stored fcname:'%s, received fcname:'%s', ufsrvCommand:'%d', commandArgs:'%d' (if fenceRecord.FID==groupRecord.FID will goahead and sync group name according server's view)", fenceRecord.getFid(), groupRecord.getFid(), groupRecord.getCname(), fenceRecord.getCname(), fenceCommand.getHeader().getCommand(), fenceCommand.getHeader().getArgs()));
      if (fenceRecord.getFid()==groupRecord.getFid())//fence already in the db as groupRecord has a valid fid
      {
        return updateFenceData (context, envelope, message, group, groupRecord,  id, threadId, outgoing);
      }
    }

    return Long.valueOf(-1);
  }
//

//-
//private static @Nullable Long handleGroupUpdate(@NonNull Context context,
//                                                @NonNull MasterSecretUnion masterSecret,
//                                                @NonNull SignalServiceEnvelope envelope,
//                                                @NonNull SignalServiceGroup group,
//                                                @NonNull GroupRecord groupRecord,
//                                                boolean outgoing)
//{
//
//  GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
//  String        groupId       = GroupUtil.getEncodedId(group.getGroupId(), false);
//
//  Set<Address> recordMembers = new HashSet<>(groupRecord.getMembers());
//  Set<Address> messageMembers = new HashSet<>();
//
//  for (String messageMember : group.getMembers().get()) {
//    messageMembers.add(Address.fromExternal(context, messageMember));
//  }
//
//  Set<Address> addedMembers = new HashSet<>(messageMembers);
//  addedMembers.removeAll(recordMembers);
//
//  Set<Address> missingMembers = new HashSet<>(recordMembers);
//  missingMembers.removeAll(messageMembers);
//
//  GroupContext.Builder builder = createGroupContext(group);
//  builder.setType(GroupContext.Type.UPDATE);
//
//  if (addedMembers.size() > 0) {
//    Set<Address> unionMembers = new HashSet<>(recordMembers);
//    unionMembers.addAll(messageMembers);
//    database.updateMembers(groupId, new LinkedList<>(unionMembers));
//
//    builder.clearMembers();
//
//    for (Address addedMember : addedMembers) {
//      builder.addMembers(addedMember.serialize());
//    }
//  } else {
//    builder.clearMembers();
//  }
//
//  if (missingMembers.size() > 0) {
//    // TODO We should tell added and missing about each-other.
//  }
//
//  if (group.getName().isPresent() || group.getAvatar().isPresent()) {
//    SignalServiceAttachment avatar = group.getAvatar().orNull();
//    database.update(groupId, group.getName().orNull(), avatar != null ? avatar.asPointer() : null);
//  }
//
//  if (group.getName().isPresent() && group.getName().get().equals(groupRecord.getTitle())) {
//    builder.clearName();
//  }
//
//  if (!groupRecord.isActive()) database.setActive(groupId, true);
//
//  return storeMessage(context, masterSecret, envelope, group, builder.build(), outgoing);
//}


  private static @NonNull Long
  UpdateFenceInvitedMembership (@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceGroup group,
                                @NonNull GroupRecord groupRecord,
                                @NonNull byte[] groupId,
                                boolean outgoing)
  {
    FenceCommand                        fenceCommand  = envelope.getFenceCommand();
    FenceRecord                         fenceRecord   = fenceCommand.getFences(0);
    int                                 commandArg    = fenceCommand.getHeader().getArgs();
    GroupDatabase.MembershipUpdateMode  updateMode    = (commandArg==CommandArgs.ADDED_VALUE||commandArg==CommandArgs.UNCHANGED_VALUE)?
                                                          GroupDatabase.MembershipUpdateMode.ADD_MEMBER:
                                                          REMOVE_MEMBER;

    updateGroupInviteMembership (context, groupRecord, fenceRecord, updateMode);

    GroupContext.Builder builderGroupContext = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, groupId);

    //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
    builderGroupContext.setFenceMessage(envelope.getFenceCommand());

    if (envelope.getFenceCommand().getHeader().getWhenClient()>0) {
      Log.d(TAG, String.format("processFenceCommandInviteUpdated (timestamp:'%d', fid:'%d'): Deleting original request msg...", envelope.getFenceCommand().getHeader().getWhenClient(), fenceCommand.getFences(0).getFid()));
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getMessageByTimestamp(envelope.getFenceCommand().getHeader().getWhenClient(), false);
      if (msg != null) {
        Log.d(TAG, String.format("processFenceCommandInviteUpdated (fid:'%d', id:'%d'): DELETING PREVIOUS REQUEST MSG", fenceCommand.getFences(0).getFid(), msg.getId()));
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

        msg.close();
      }
    }

    return storeMessage (context, envelope, group, builderGroupContext.build(), outgoing);
  }


  //if we receive such a message griup mode must be in one of those states
  static private void
  updateGroupModeForJoinOrSyncedGroup (@NonNull Context context,
                                       @NonNull GroupRecord groupRecord)
  {
    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    int             groupMode       = groupRecord.getMode();

    //self-healing
    if (groupMode!=GROUP_MODE_JOIN_ACCEPTED && groupMode!=GROUP_MODE_INVITATION_JOIN_ACCEPTED &&
        groupMode!=GROUP_MODE_GEOBASED_JOIN && groupMode!=GROUP_MODE_JOIN_SYNCED &&
        groupMode!=GROUP_MODE_MAKE_NOT_CONFIRMED)
    {
      Log.e(TAG, String.format("updateGroupModeForJoinOrSyncedGroup (groupMode:'%d', fid:'%d', GroupDatabase.cname:'%s'): ERROR: GROUP MODE HAD INVALID VALUE: Reassigning mode to default JOIN_ACCEPTED (it could have been INVITED)", groupRecord.getMode(), groupRecord.getFid(), groupRecord.getCname()));
      groupDatabase.markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_JOIN_ACCEPTED);

    }
  }


  //just shovel stuff in...
  private static Long
  updateFenceData (@NonNull Context context,
                   @NonNull SignalServiceEnvelope envelope,
                   @NonNull SignalServiceDataMessage message,
                   @NonNull SignalServiceGroup group,
                   @NonNull GroupRecord groupRecord,
                   @NonNull byte[] id,
                   @NonNull long threadId,
                   boolean outgoing)
{
    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase  threadDatabase  = DatabaseFactory.getThreadDatabase(context);
    FenceCommand    fenceCommand    = envelope.getFenceCommand();
    FenceRecord     fenceRecord     = fenceCommand.getFences(0);
    long            threadFid       = threadDatabase.getFidForThreadId(threadId);
    Recipient       recipient       =  Recipient.fromFid(context, fenceRecord.getFid(), false);


    if (threadFid<=0) {
      Log.e(TAG, String.format("updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', cname:'%s', threadid:'%d', threadFid:'%d'): FID WAS NOT SET FOR THREADID... updating", fenceRecord.getFid(), fenceRecord.getCname(), threadId, threadFid));
      threadDatabase.updateFid(threadId, fenceRecord.getFid());
    }

    //done upstream
    //if (fenceCommand.getMainHeader().getEid()>0)  threadDatabase.updateEidByFid(fenceRecord.getFid(), fenceCommand.getMainHeader().getEid());

    //self-healing...
    updateGroupModeForJoinOrSyncedGroup (context, groupRecord);

    if (!TextUtils.isEmpty(fenceRecord.getFname()) &&
        (TextUtils.isEmpty(groupRecord.getTitle()) || !groupRecord.getTitle().equals(fenceRecord.getFname()))) {
      Log.e(TAG, String.format("updateFenceData (fid:'%d', FenceRecord.name:'%s', GroupDatabase.name:'%s'): Updating fence name", fenceRecord.getFid(), fenceRecord.getFname(), groupRecord.getTitle()));
      groupDatabase.updateTitle(fenceRecord.getFid(), fenceRecord.getFname());
      if (TextUtils.isEmpty(fenceRecord.getCname()))
        groupDatabase.updateCname(fenceRecord.getFid(), UfsrvFenceUtils.updateCnameWithNewFname (fenceRecord.getFid(), groupRecord.getCname(), groupRecord.getTitle(), fenceRecord.getFname()));
      else
        groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

      groupRecord=groupDatabase.getGroupRecordByFid(fenceRecord.getFid());//reload group record with updated values
    }

    if (TextUtils.isEmpty(groupRecord.getCname()) && !TextUtils.isEmpty(fenceRecord.getCname())) {
      Log.e(TAG, String.format("updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', FenceRecord.cname:'%s'): GroupRecord.CNAME IS NULL: updating", fenceRecord.getFid(), fenceRecord.getCname()));
      groupDatabase.updateCname(fenceRecord.getFid(), fenceRecord.getCname());

      groupRecord=groupDatabase.getGroupRecordByFid(fenceRecord.getFid());
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

    updateFencePermission (context, groupRecord, fenceRecord, threadFid);
    updateFenceAvatar (context, envelope, group, groupRecord);
    UfsrvFenceUtils.updateFencePreferences (context, groupRecord, fenceRecord);

    if (fenceRecord.hasExpireTimer()) {
      DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, (int)fenceRecord.getExpireTimer()/1000);//in seconds
    }

    //todo: not sure this is necessary in light of above. at worst, consolidate the logic
    if (!groupRecord.getCname().equals(fenceRecord.getCname())) {
      Log.e(TAG, String.format("updateFenceData: ERROR: DATA INTEGRITY (fid:'%d', groupRecord.cname:'%s', FenceRecord.cname:'%s', threadid:'%d', threadFid:'%d'): CNAME INCONSISTENT.. updating", fenceRecord.getFid(), groupRecord.getCname(), fenceRecord.getCname(), threadId, threadFid));
      groupDatabase.updateAndResolveCname(fenceRecord.getFid(), fenceRecord.getCname());
    }

    if (fenceRecord.hasLocation()) {
      groupDatabase.updateFenceLocation( fenceRecord.getCname(), fenceRecord.getFid(),
                                    fenceRecord.getLocation().getLongitude(),
                                    fenceRecord.getLocation().getLongitude());
    }

    updateGroupMembership(context, group, groupRecord, fenceRecord, true);

    GroupContext.Builder builder = createGroupContextFromUfsrv(context, envelope.getUfsrvCommand(), GroupContext.Type.UPDATE, id);

    //save our Fence message along with GroupContext just to be consistent with exiting pattern. technically not required
    builder.setFenceMessage(envelope.getFenceCommand());

    if (!groupRecord.isActive()) groupDatabase.setActive(GroupUtil.getEncodedId(id, false), true);

    DatabaseFactory.getRecipientDatabase(context).setUfsrvId(recipient, fenceRecord.getFid());
    DatabaseFactory.getRecipientDatabase(context).setEid(recipient, fenceRecord.getEid());

    if (fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.STATE_VALUE &&
          fenceCommand.getHeader().getArgs() == CommandArgs.SYNCED_VALUE) {
      Log.d(TAG, String.format("updateFenceData (fid:'%d', cname:'%s'): Updated Fence data, but not storing message PURE STATE SYNCED", fenceRecord.getFid(), groupRecord.getCname()));
      MmsDatabase.Reader msg = DatabaseFactory.getMmsDatabase(context).getGroupUpdateMessages();
      if (msg != null) {
        DatabaseFactory.getMmsDatabase(context).delete(msg.getId());

        msg.close();
      }
      return 0L;
    }

    return storeMessage(context, envelope, group, builder.build(), outgoing);
  }
  //

  private static void updateFenceAvatar(@NonNull Context               context,
                                                   @NonNull SignalServiceEnvelope envelope,
                                                   @NonNull SignalServiceGroup    group,
                                                   @NonNull GroupRecord           groupRecord)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    String groupIdEncoded       =  GroupUtil.getEncodedId(group.getGroupId(), false);;


    SignalServiceProtos.FenceRecord fenceRecord = envelope.getFenceCommand().getFences(0);

    if (group.getAvatar().isPresent()) {
      if (groupRecord.getAvatarUfId() == group.getAvatar().get().asPointer().getUfId()) return;
      else {
        SignalServiceAttachmentPointer attachmentPointer = group.getAvatar().get().asPointer();
        SignalServiceAttachment avatar = new SignalServiceAttachmentPointer(attachmentPointer.getUfId(),
                                                                            0,
                                                                            attachmentPointer.getContentType(),
                                                                            attachmentPointer.getKey(),
                                                                            attachmentPointer.getDigest().isPresent() ? attachmentPointer.getDigest() : Optional.<byte[]>absent(),
                                                                            attachmentPointer.getFileName().isPresent() ? attachmentPointer.getFileName() : Optional.<String>absent(),
                                                                            false);
        groupDatabase.update(groupIdEncoded, null, avatar.asPointer());
        Log.d(TAG, String.format("updateFenceAvatar (fid:'%d'): DOWNLOADING AVATAR... id: %s", fenceRecord.getFid(), avatar.asPointer().getUfId()));
        ApplicationContext.getInstance(context).getJobManager()
                .add(new AvatarDownloadJob(group.getGroupId()));
      }
    }

  }

  //todo: implement storing permission users list
  public static void
  updateFencePermission (@NonNull Context context, @NonNull GroupRecord groupRecord, @NonNull FenceRecord fenceRecord,  long fid)
  {
    FenceRecord.Permission fencePermission;
    RecipientDatabase preferenceDatabase  = DatabaseFactory.getRecipientDatabase(context);

    Address                   address          = Address.fromSerialized(groupRecord.getEncodedId());
    Recipient recipient=Recipient.from(context, address, false);

    //EnumPermissionBaseList is not managed by backend
    fencePermission=fenceRecord.getPresentation();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(EnumFencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.EnumPermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.EnumPermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));

    fencePermission=fenceRecord.getMembership();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(EnumFencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.EnumPermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.EnumPermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getMessaging();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(EnumFencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.EnumPermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.EnumPermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getAttaching();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(EnumFencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.EnumPermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.EnumPermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
    fencePermission=fenceRecord.getCalling();
    if (fencePermission.hasListSemantics()) preferenceDatabase.setListSemantics(recipient, new RecipientDatabase.GroupPermission(EnumFencePermissions.values()[fencePermission.getType().getNumber()],
                                                                                                                                 RecipientDatabase.EnumPermissionBaseList.NONE,
                                                                                                                                 RecipientDatabase.EnumPermissionListSemantics.values()[fencePermission.getListSemantics().getNumber()]));
  }

  public static List<Address> UserRecordsToNumbersAddressList (@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    List<Address> numbers=new LinkedList<>();

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

  public static List<String> UserRecordsToNumbersList (@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    List<String> numbers=new LinkedList<>();

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
  public static List<String> UserRecordsToEncodedUfsrvUidList (@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<String> numbers=new LinkedList<>();

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

  public static List<UfsrvUid> UserRecordsToUserIdsList (@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<UfsrvUid>>excludedUsers)
  {
    List<UfsrvUid> userIds=new LinkedList<>();

    if (excludedUsers.isPresent()) {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid)) userIds.add((ufsrvUid));
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%d'", record.getUsername()));
        }
      }
    } else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        UfsrvUid ufsrvUid = new UfsrvUid(record.getUfsrvuid().toByteArray());
        userIds.add(ufsrvUid);
      }
    }

    return userIds;
  }


  //to replace one above gradually...
  public static List<Address> UserRecordsToAddressList (@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    UfsrvUid ufsrvUid;
    List<Address> numbers=new LinkedList<>();

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

  //
  private  static void
  updateGroupInviteMembership (@NonNull Context context,
                               @NonNull GroupRecord groupRecord,
                               @NonNull FenceRecord fenceRecord,
                               GroupDatabase.MembershipUpdateMode updateMode)
  {
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);

//    List<String> usersAffeted=UserRecordsToNumbersList(fenceRecord.getInvitedMembersList(),  Optional.absent());
    List<UfsrvUid> usersAffeted=UserRecordsToUserIdsList(fenceRecord.getInvitedMembersList(),  Optional.absent());

    List<Address> addressListUsersAffeted=new LinkedList<>();
    for (UfsrvUid ufsrvUid: usersAffeted) {
      Recipient recipient = Recipient.fromUfsrvUid(context, ufsrvUid, false);
      addressListUsersAffeted.add(Address.fromUfsrvUid(ufsrvUid, recipient.getAddress().serialize()));
    }

    if (fenceRecord.getInvitedMembersCount()>0) {
      database.updateInvitedMembers(groupRecord.getFid(), addressListUsersAffeted, updateMode);
    }

    return;

  }

  // Updates both, regular and invite memberships
  private  static void
  updateGroupMembership (@NonNull Context context,
                         @NonNull SignalServiceGroup group,
                         @NonNull GroupRecord groupRecord,
                         @NonNull FenceRecord fenceRecord,
                         boolean flagReset)
{
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);

    if (flagReset) {
    //normally for new synched fences

      //not used
      //Set<String> messageMembersUf = new HashSet<>(UserRecordsToNumbersList(fenceRecord.getMembersList()));
      database.updateMembers(groupRecord.getEncodedId(), UserRecordsToAddressList(fenceRecord.getMembersList(), Optional.<List<String>>absent()));

      UfsrvFenceUtils.updateProfileKeyForFenceMembers (context, fenceRecord.getMembersList(), Optional.absent());

      if (fenceRecord.getInvitedMembersCount()>0) {
        List<String> excludedMembers=new LinkedList<>();
        excludedMembers.add(TextSecurePreferences.getUfsrvUserId(context));

        database.updateInvitedMembers(groupRecord.getEncodedId(), UserRecordsToAddressList(fenceRecord.getInvitedMembersList(), Optional.of(excludedMembers)));

        Log.d(TAG, String.format("updateGroupMembership: Invited members contains: '%d'member(s)", fenceRecord.getInvitedMembersList().size()));
      } else {
        Log.d(TAG, String.format("updateGroupMembership: RESETING Invited members to empty"));
        database.updateInvitedMembers(groupRecord.getEncodedId(), new LinkedList<Address>());
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


  // orig
  private static Long handleGroupLeave(@NonNull Context               context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceGroup    group,
                                       @NonNull GroupRecord           record,
                                       boolean  outgoing)
  {
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    String        id       = GroupUtil.getEncodedId(group.getGroupId(), false);
    List<Address> members  = record.getMembers();

    GroupContext.Builder builder = createGroupContext(group);
    builder.setType(GroupContext.Type.QUIT);

    if (members.contains(Address.fromExternal(context, envelope.getSource()))) {
      database.remove(id, Address.fromExternal(context, envelope.getSource()));
      if (outgoing) database.setActive(id, false);

      return storeMessage(context, envelope, group, builder.build(), outgoing);
    }

    return null;
  }


  private static Long handleGroupInfoRequest(@NonNull Context context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceGroup group,
                                            @NonNull GroupRecord record)
  {
    if (record.getMembers().contains(Address.fromExternal(context, envelope.getSource()))) {
     ApplicationContext.getInstance(context)
       .getJobManager()
       .add(new PushGroupUpdateJob(envelope.getSource(), group.getGroupId()));
   }

    return null;
  }


  private static @Nullable Long storeMessage(@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull SignalServiceGroup group,
                                             @NonNull GroupContext storage,
                                             boolean  outgoing)
  {
//-
//    if (group.getAvatar().isPresent()) {
//      Log.d(TAG, String.format("storeMessage: DOWNLOADING AVATAR... POINTER TYPE: %b",group.getAvatar().get().isPointer()));
//      ApplicationContext.getInstance(context).getJobManager()
//                        .add(new AvatarDownloadJob(group.getGroupId()));
//    }
    //
    if (storage.hasAvatar()) {
      Log.d(TAG, String.format("storeMessage: DOWNLOADING AVATAR... id: %s", storage.getAvatar().getUfid()));
      ApplicationContext.getInstance(context).getJobManager()
              .add(new AvatarDownloadJob(storage.getId().toByteArray()));
    }

    try {
      if (outgoing) {
        MmsDatabase               mmsDatabase     = DatabaseFactory.getMmsDatabase(context);
        Address                   addres          = Address.fromExternal(context, GroupUtil.getEncodedId(group.getGroupId(), false));
        Recipient recipient       = Recipient.from(context, addres, false);

        OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipient, storage, null, envelope.getTimestamp(), 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                  envelope.getUfsrvCommand());//
        long                      threadId        = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
        long                      messageId       = mmsDatabase.insertMessageOutbox(outgoingMessage, threadId, false, null);

        mmsDatabase.markAsSent(messageId, true);

        return threadId;
      } else {
        SmsDatabase          smsDatabase  = DatabaseFactory.getSmsDatabase(context);

        Log.d(TAG, "storeMessage: String message with 'outgoing' false");
        String                body         = Base64.encodeBytes(storage.toByteArray());// GroupContext. We serialie UfsrvCommand later at db insertion

        // usfrcommand
        IncomingTextMessage   incoming     = new IncomingTextMessage(Address.fromSerialized(envelope.getSource()), envelope.getSourceDevice(), envelope.getTimestamp(), body, Optional.of(group), 0, 0, false, envelope.getUfsrvCommand());
        IncomingGroupMessage  groupMessage = new IncomingGroupMessage(incoming, storage, body, envelope.getUfsrvCommand());
        //

        Optional<InsertResult> insertResult = smsDatabase.insertMessageInbox(groupMessage);
        if (insertResult.isPresent()) {
          MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
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


  private static GroupContext.Builder createGroupContext(SignalServiceGroup group) {
    GroupContext.Builder builder = GroupContext.newBuilder();
    builder.setId(ByteString.copyFrom(group.getGroupId()));

    if (group.getAvatar().isPresent() && group.getAvatar().get().isPointer()) {
      builder.setAvatar(AttachmentPointer.newBuilder()
                                         .setId(group.getAvatar().get().asPointer().getId())
                                         .setKey(ByteString.copyFrom(group.getAvatar().get().asPointer().getKey()))
                                         .setContentType(group.getAvatar().get().getContentType()));
    }

    if (group.getName().isPresent()) {
      builder.setName(group.getName().get());
    }

    if (group.getMembers().isPresent()) {
      builder.addAllMembers(group.getMembers().get());
    }

    return builder;
  }


  public static GroupContext.Builder createGroupContextFromUfsrv (@NonNull Context context,
                                                                  @NonNull SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire,
                                                                  GroupContext.Type Type)
  {
    GroupRecord   groupRecord   = null;
    FenceCommand fenceCommand   = ufsrvCommandWire.getFenceCommand();
    FenceRecord fenceRecord     = fenceCommand.getFences(0);
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    byte[] id                   = null;
    String groupIdEncoded;

    //todo: use
    groupIdEncoded=groupDatabase.getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), true);
    try {
      id=GroupUtil.getDecodedId(groupIdEncoded);
    } catch (IOException ex) {
      Log.d(TAG, String.format(ex.getMessage()));
    }

   return createGroupContextFromUfsrv(context,ufsrvCommandWire, Type, id);
  }


  public static GroupContext.Builder createGroupContextFromUfsrv (@NonNull Context context,
                                                                  @NonNull SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire,
                                                                  GroupContext.Type Type,
                                                                  byte[] id)
  {

    SignalServiceProtos.FenceCommand  fenceCommand    = ufsrvCommandWire.getFenceCommand();
    SignalServiceProtos.FenceRecord   fenceRecord     = fenceCommand.getFences(0);

    GroupContext.Builder builder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(id))
            .setType(Type)
            .setFenceMessage(fenceCommand)
            .addAllMembers(UserRecordsToEncodedUfsrvUidList(fenceRecord.getMembersList(), Optional.absent()));
    builder.setName(fenceRecord.getFname());

    //Extract the avatar from attachments list.
    List<SignalServiceAttachment> attachments = new LinkedList<>();
    SignalServiceAttachment avatar;
    for (SignalServiceProtos.AttachmentRecord pointer : fenceCommand.getAttachmentsList()) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), //  the ufid
                                                        0,// this the original int id which we no longer support
                                                          pointer.getContentType(),
                                                          pointer.getKey().toByteArray(),
                                                          pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.absent(),
                                                          pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.absent(),
                                                          pointer.getWidth(),
                                                          pointer.getHeight(),
                                                          pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.absent(),
                                                          pointer.hasFileName()? Optional.of(pointer.getFileName()):Optional.absent(),
                                                      false,
                                                         pointer.hasCaption() ? Optional.of(pointer.getCaption()):Optional.absent()));
    }
    avatar=(attachments.size())>0?attachments.get(0):null;
    if (avatar!=null) {
      builder.setAvatar(AttachmentPointer.newBuilder()
              .setId(avatar.asPointer().getId())//this is not supported by ufsrv
              .setUfid(avatar.asPointer().getUfId())
              .setKey(ByteString.copyFrom(avatar.asPointer().getKey()))
              .setDigest(ByteString.copyFrom(avatar.asPointer().getDigest().get()))
              .setContentType(avatar.getContentType()));

    }
    //end Avatar block

    return builder;
  }

  static public String UfsrvUidEncodedForOriginator (FenceCommand fenceCommand)
  {
    return UfsrvUid.EncodedfromSerialisedBytes(fenceCommand.getOriginator().getUfsrvuid().toByteArray());
  }

}
