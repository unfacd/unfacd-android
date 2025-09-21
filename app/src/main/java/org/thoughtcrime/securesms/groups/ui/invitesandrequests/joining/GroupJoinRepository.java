package org.thoughtcrime.securesms.groups.ui.invitesandrequests.joining;

import android.content.Context;

import com.unfacd.android.utils.UfsrvFenceUtils;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.local.DecryptedGroupJoinInfo;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.util.AsynchronousCallback;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.groupsv2.GroupLinkNotActiveException;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

final class GroupJoinRepository {

  private static final String TAG = Log.tag(GroupJoinRepository.class);

  private final Context            context;
  private final GroupInviteLinkUrl groupInviteLinkUrl;

  GroupJoinRepository(@NonNull Context context, @NonNull GroupInviteLinkUrl groupInviteLinkUrl) {
    this.context            = context;
    this.groupInviteLinkUrl = groupInviteLinkUrl;
  }

  void getGroupDetails(@NonNull AsynchronousCallback.WorkerThread<GroupDetails, FetchGroupDetailsError> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        callback.onComplete(getGroupDetails());
      } catch (IOException e) {
        callback.onError(FetchGroupDetailsError.NetworkError);
      } catch (GroupLinkNotActiveException e) {
        callback.onError(e.getReason() == GroupLinkNotActiveException.Reason.BANNED ? FetchGroupDetailsError.BannedFromGroup : FetchGroupDetailsError.GroupLinkNotActive);
      } catch (VerificationFailedException e) {
        callback.onError(FetchGroupDetailsError.GroupLinkNotActive);
      }
    });
  }

  void joinGroup(@NonNull GroupDetails groupDetails,
                 @NonNull AsynchronousCallback.WorkerThread<JoinGroupSuccess, JoinGroupError> callback)
  {
    //AA+
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.GroupActionResult groupActionResult = UfsrvFenceUtils.sendFenceLinkJoinRequest(groupInviteLinkUrl.getGroupMasterKey(), groupInviteLinkUrl.getFid(), groupDetails.getGroupName());
        if (groupActionResult.getThreadId() > 0)
          callback.onComplete(new JoinGroupSuccess(groupActionResult.getGroupRecipient(), groupActionResult.getThreadId()));
        else callback.onError(JoinGroupError.FAILED);
      } catch (Exception e) {
        callback.onError(JoinGroupError.NETWORK_ERROR);
      }
    });

   /* SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.GroupActionResult groupActionResult = GroupManager.joinGroup(context,
                                                                                  groupInviteLinkUrl.getGroupMasterKey(),
                                                                                  groupInviteLinkUrl.getPassword(),
                                                                                  groupDetails.getJoinInfo(),
                                                                                  groupDetails.getAvatarBytes());

        callback.onComplete(new JoinGroupSuccess(groupActionResult.getGroupRecipient(), groupActionResult.getThreadId()));
      } catch (IOException e) {
        callback.onError(JoinGroupError.NETWORK_ERROR);
      } catch (GroupChangeBusyException e) {
        callback.onError(JoinGroupError.BUSY);
      } catch (GroupLinkNotActiveException e) {
        callback.onError(e.getReason() == GroupLinkNotActiveException.Reason.BANNED ? JoinGroupError.BANNED : JoinGroupError.GROUP_LINK_NOT_ACTIVE);
      } catch (GroupChangeFailedException | MembershipNotSuitableForV2Exception e) {
        callback.onError(JoinGroupError.FAILED);
      }
    });*/
  }

  @WorkerThread
  private @NonNull GroupDetails getGroupDetails()
          throws VerificationFailedException, IOException, GroupLinkNotActiveException
  {
    DecryptedGroupJoinInfo joinInfo = GroupManager.getGroupJoinInfoFromServer(context,
                                                                              groupInviteLinkUrl.getGroupMasterKey(),
                                                                              groupInviteLinkUrl.getPassword(), groupInviteLinkUrl.getFid());//AA+ fid

    byte[] avatarBytes = tryGetAvatarBytes(joinInfo);

    return new GroupDetails(joinInfo, avatarBytes);
  }

  private @Nullable byte[] tryGetAvatarBytes(@NonNull DecryptedGroupJoinInfo joinInfo) {
    SignalServiceMessageReceiver receiver    = ApplicationDependencies.getSignalServiceMessageReceiver();
    try {
      return receiver.retrieveAttachmentBytes(joinInfo.getAvatar(), AvatarHelper.AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE, null);
    } catch (IOException e) {
      Log.w(TAG, "Failed to get group avatar", e);
      return null;
    }
    /*try {
      return AvatarGroupsV2DownloadJob.downloadGroupAvatarBytes(context, groupInviteLinkUrl.getGroupMasterKey(), joinInfo.getAvatar());
    } catch (IOException e) {
      Log.w(TAG, "Failed to get group avatar", e);
      return null;
    }*/
  }
}