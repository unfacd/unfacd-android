package org.thoughtcrime.securesms.messagerequests;

import android.content.Context;

import com.google.protobuf.ByteString;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.GroupChangeErrorCallback;
import org.thoughtcrime.securesms.jobs.MultiDeviceMessageRequestResponseJob;
import org.thoughtcrime.securesms.jobs.ReportSpamJob;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

final class MessageRequestRepository {

  private static final String TAG = Log.tag(MessageRequestRepository.class);

  private final Context  context;
  private final Executor executor;

  MessageRequestRepository(@NonNull Context context) {
    this.context  = context.getApplicationContext();
    this.executor = SignalExecutors.BOUNDED;
  }

  void getGroups(@NonNull RecipientId recipientId, @NonNull Consumer<List<String>> onGroupsLoaded) {
    Recipient recipient = Recipient.resolved(recipientId);//AA+
    executor.execute(() -> {
      GroupDatabase groupDatabase = SignalDatabase.groups();
      onGroupsLoaded.accept(groupDatabase.getPushGroupNamesContainingMember(recipient.getUfsrvUid()));//AA+
    });
  }

  void getGroupInfo(@NonNull RecipientId recipientId, @NonNull Consumer<GroupInfo> onGroupInfoLoaded) {
    executor.execute(() -> {
      GroupDatabase                       groupDatabase = SignalDatabase.groups();
      Optional<GroupDatabase.GroupRecord> groupRecord   = groupDatabase.getGroup(recipientId);
      onGroupInfoLoaded.accept(groupRecord.map(record -> {
        if (false && record.isV2Group()) {//A++ false
          DecryptedGroup decryptedGroup = record.requireV2GroupProperties().getDecryptedGroup();
          return new GroupInfo(decryptedGroup.getMembersCount(), decryptedGroup.getPendingMembersCount(), decryptedGroup.getDescription(), 0);
        } else {
          return new GroupInfo(record.getMembers().size(), record.getMembersInvited().size(), "", record.getMembersLinkJoining().size()); ////AA+ Invited list
        }
      }).orElse(GroupInfo.ZERO));
    });
  }

  @WorkerThread
  @NonNull MessageRequestState getMessageRequestState(@NonNull Recipient recipient, long threadId) {
    if (recipient.isBlocked()) {
      if (recipient.isGroup()) {
        return MessageRequestState.BLOCKED_GROUP;
      } else {
        return MessageRequestState.BLOCKED_INDIVIDUAL;
      }
    } else if (threadId <= 0) {
      return MessageRequestState.NONE;
    } else if (recipient.isPushV2Group()) {
      switch (getGroupMemberLevel(recipient.getId())) {
        case NOT_A_MEMBER:
        case FULL_MEMBER://AA+
          return MessageRequestState.NONE;
        case PENDING_MEMBER:
          return MessageRequestState.GROUP_V2_INVITE;
        case LINKJOINING_MEMBER://AA+
          return MessageRequestState.GROUP_LINK_JOIN_AUTHORIZATION;
        default:
          if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
            return MessageRequestState.NONE;
          } else {
            return MessageRequestState.GROUP_V2_ADD;
          }
      }
    } else if (!RecipientUtil.isLegacyProfileSharingAccepted(recipient) && isLegacyThread(recipient)) {
      if (recipient.isGroup()) {
        return MessageRequestState.LEGACY_GROUP_V1;
      } else {
        return MessageRequestState.LEGACY_INDIVIDUAL;
      }
    } else if (recipient.isPushV1Group()) {
      if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
        if (false /*FeatureFlags.groupsV1ForcedMigration()*/) {//AA-
          if (recipient.getParticipants().size() > FeatureFlags.groupLimits().getHardLimit()) {
            return MessageRequestState.DEPRECATED_GROUP_V1_TOO_LARGE;
          } else {
            return MessageRequestState.DEPRECATED_GROUP_V1;
          }
        } else {
          return MessageRequestState.NONE;
        }
      } else if (!recipient.isActiveGroup()) {
        return MessageRequestState.NONE;
      } else {
        return MessageRequestState.GROUP_V1;
      }
    } else {
      if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
        return MessageRequestState.NONE;
      } else {
        return MessageRequestState.INDIVIDUAL;
      }
    }
  }

  /*void acceptMessageRequest(@NonNull LiveRecipient liveRecipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestAccepted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(()-> {
      if (false && liveRecipient.get().isPushV2Group()) {//AA+ false this branch is not useful at this stage
        try {
          Log.i(TAG, "GV2 accepting invite");
          GroupManager.acceptInvite(context, liveRecipient.get().requireGroupId().requireV2());

          RecipientDatabase recipientDatabase = SignalDatabase.recipients();
          recipientDatabase.setProfileSharing(liveRecipient.getId(), true);

          onMessageRequestAccepted.run();
        } catch (GroupChangeException | IOException e) {
          Log.w(TAG, e);
          error.onError(GroupChangeFailureReason.fromException(e));
        }
      } else {
        RecipientDatabase recipientDatabase = SignalDatabase.recipients();
        recipientDatabase.setProfileSharing(liveRecipient.getId(), true);

        MessageSender.sendProfileKey(context, threadId);

        List<MessageDatabase.MarkedMessageInfo> messageIds = SignalDatabase.threads
                .setEntireThreadRead(threadId);
        ApplicationDependencies.getMessageNotifier().updateNotification(context);
        MarkReadReceiver.process(context, messageIds);

        List<MessageDatabase.MarkedMessageInfo> viewedInfos = SignalDatabase.mms()
                .getViewedIncomingMessages(threadId);

        SendViewedReceiptJob.enqueue(threadId, liveRecipient.getId(), viewedInfos, Stream.of(viewedInfos).map(info -> info.getSyncMessageId()).map(syncMessageId -> syncMessageId.getUfsrvMessageIdentifier()).toList());

        if (TextSecurePreferences.isMultiDevice(context)) {
          ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forAccept(liveRecipient.getId()));
        }

        onMessageRequestAccepted.run();
      }
    });
  }
*/
  //AA+
  void acceptMessageRequest(@NonNull LiveRecipient liveRecipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestAccepted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(()-> {
      GroupId groupId = liveRecipient.get().requireGroupId();
      GroupDatabase.GroupRecord groupRec = SignalDatabase.groups().getGroupByGroupId(groupId);
      SignalDatabase.groups().setActive(groupId, false);

      sendGroupJoinRequest(context, groupId, groupRec.getFid(), threadId, liveRecipient.get());

      SignalDatabase.groups().markGroupMode(groupRec.getFid(), GroupDatabase.GROUP_MODE_INVITATION);
      /*GroupDatabase.GroupRecord groupRec = SignalDatabase.groups().getGroupByGroupId(groupId);

      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandJoinInvitationResponse(context, groupRec.getFid(), timeNowInMillis, true);
      UfsrvCommandWire.Builder ufsrvCommandBuilder = UfsrvCommandWire.newBuilder()
                                                                     .setFenceCommand(fenceCommandBuilder.build())
                                                                     .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                         .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                         .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                                         .setFenceMessage(fenceCommandBuilder.build())
                                                                         .build();

      OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(liveRecipient.get(), groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
      MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

      SignalDatabase.groups().markGroupMode(groupRec.getFid(), GroupDatabase.GROUP_MODE_INVITATION);*/

      onMessageRequestAccepted.run();
    });

  }

  //AA+
  void
  sendGroupJoinRequest(Context context, @NonNull GroupId groupId, long fid,  long threadId, Recipient recipient)
  {
    long timeNowInMillis = System.currentTimeMillis();

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandJoinInvitationResponse(context, fid, timeNowInMillis, true);
    UfsrvCommandWire.Builder ufsrvCommandBuilder = UfsrvCommandWire.newBuilder()
                                                                   .setFenceCommand(fenceCommandBuilder.build())
                                                                   .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                                    .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                                    .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                                                    .setFenceMessage(fenceCommandBuilder.build())
                                                                                    .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
    MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

  }

 /* void deleteMessageRequest(@NonNull LiveRecipient recipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestDeleted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient resolved = recipient.resolve();

     if (resolved.isGroup() && resolved.requireGroupId().isPush()) {
        try {
          GroupManager.leaveGroupFromBlockOrMessageRequest(context, resolved.requireGroupId().requirePush());
        } catch (GroupChangeException | GroupPatchNotAcceptedException e) {
          if (SignalDatabase.groups().isCurrentMember(resolved.requireGroupId().requirePush(), Recipient.self().getId())) {
            Log.w(TAG, "Failed to leave group, and we're still a member.", e);
            error.onError(GroupChangeFailureReason.fromException(e));
            return;
          } else {
            Log.w(TAG, "Failed to leave group, but we're not a member, so ignoring.");
          }
        } catch (IOException e) {
          Log.w(TAG, e);
          error.onError(GroupChangeFailureReason.fromException(e));
          return;
        }
      }

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forDelete(recipient.getId()));
      }

      ThreadDatabase threadDatabase = SignalDatabase.threads();
      threadDatabase.deleteConversation(threadId);

      onMessageRequestDeleted.run();
    });
  }*/

  //AA+
  void deleteMessageRequest(@NonNull LiveRecipient recipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestDeleted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(()-> {
      sendDeleteMessageRequest(recipient, threadId);

      onMessageRequestDeleted.run();
    });
  }


  /**
   * For ufsrv this referred to as "rejection". In contrast, when an invite is revoked by another member
   * that's referred to as "delete". See {@link com.unfacd.android.utils.UfsrvFenceUtils#deleteInvitationFor(Context, GroupId, Collection)}
   */
  void sendDeleteMessageRequest(@NonNull LiveRecipient recipient, long threadId)
  {
      long timeNowInMillis = System.currentTimeMillis();
      Recipient resolved = recipient.resolve();
      GroupId groupId = resolved.requireGroupId();
      SignalDatabase.groups().setActive(groupId, false);
      GroupDatabase.GroupRecord groupRec = SignalDatabase.groups().getGroupByGroupId(groupId);

      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandForType(Collections.emptyList(), groupRec.getFid(), timeNowInMillis, FenceCommand.CommandTypes.INVITE_REJECTED, SignalServiceProtos.CommandArgs.SET_VALUE);
      UfsrvCommandWire.Builder ufsrvCommandBuilder = UfsrvCommandWire.newBuilder()
                                                                     .setFenceCommand(fenceCommandBuilder.build())
                                                                     .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                         .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                         .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                                         .setFenceMessage(fenceCommandBuilder.build())
                                                                         .build();

      OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(resolved, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
      MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

  }

  void blockMessageRequest(@NonNull LiveRecipient liveRecipient,
                           @NonNull Runnable onMessageRequestBlocked,
                           @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      sendUserPrefBlockedFence(context, recipient.getUfsrvId(), null);

      onMessageRequestBlocked.run();
    });
  }

/*
  void blockMessageRequest(@NonNull LiveRecipient liveRecipient,
                           @NonNull Runnable onMessageRequestBlocked,
                           @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      try {
        RecipientUtil.block(context, recipient);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
        return;
      }
      liveRecipient.refresh();

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forBlock(liveRecipient.getId()));
      }

      onMessageRequestBlocked.run();
    });
  }
*/

  void blockAndReportSpamMessageRequest(@NonNull LiveRecipient liveRecipient,
                                        long threadId,
                                        @NonNull Runnable onMessageRequestBlocked,
                                        @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      sendDeleteMessageRequest(liveRecipient, threadId);
      ApplicationDependencies.getJobManager().add(new ReportSpamJob(threadId, System.currentTimeMillis()));
//      UfsrvUserUtils.UfsrvShareBlocking(context, recipient, true);
      sendUserPrefBlockedFence(context, recipient.getUfsrvId(), null);

      onMessageRequestBlocked.run();
    });
  }

  /*void blockAndDeleteMessageRequest(@NonNull LiveRecipient liveRecipient,
                                    long threadId,
                                    @NonNull Runnable onMessageRequestBlocked,
                                    @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      try{
        RecipientUtil.block(context, recipient);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
        return;
      }
      liveRecipient.refresh();

      ApplicationDependencies.getJobManager().add(new ReportSpamJob(threadId, System.currentTimeMillis()));

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forBlockAndReportSpam(liveRecipient.getId()));
      }

      onMessageRequestBlocked.run();
    });
  }*/

  private static void
  sendUserPrefBlockedFence(@NonNull  Context      context,
                            @Nullable Long fid,
                            @NonNull  UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    Log.d(TAG, String.format("sendUserPrefBlockedFence: Updating for fid '%d'", fid));

    UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.BLOCKED_FENCE);
    profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), fid.toString()));

  }

  void unblockAndAccept(@NonNull LiveRecipient liveRecipient, long threadId, @NonNull Runnable onMessageRequestUnblocked) {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();

      RecipientUtil.unblock(context, recipient);

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forAccept(liveRecipient.getId()));
      }

      onMessageRequestUnblocked.run();
    });
  }

  private GroupDatabase.MemberLevel getGroupMemberLevel(@NonNull RecipientId recipientId) {
    return SignalDatabase.groups()
                         .getGroup(recipientId)
                         .map(g -> g.memberLevel(Recipient.self()))
                         .orElse(GroupDatabase.MemberLevel.NOT_A_MEMBER);
  }

  @WorkerThread
  private boolean isLegacyThread(@NonNull Recipient recipient) {
    Context context  = ApplicationDependencies.getApplication();
    Long    threadId = SignalDatabase.threads().getThreadIdFor(recipient.getId());

    return threadId != null &&
            (RecipientUtil.hasSentMessageInThread(context, threadId) || RecipientUtil.isPreMessageRequestThread(context, threadId));

  }
}