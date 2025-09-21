package org.thoughtcrime.securesms.groups.ui.invitesandrequests.requesting;

import android.content.Context;

import com.unfacd.android.utils.UfsrvFenceUtils;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.AsynchronousCallback;

import java.util.Collections;

import androidx.annotation.NonNull;

/**
 * Repository for modifying the requesting members on a single group.
 */
final class RequestingMemberRepository {

  private static final String TAG = Log.tag(RequestingMemberRepository.class);

  private final Context    context;
  private final GroupId.V2 groupId;

  RequestingMemberRepository(@NonNull Context context, @NonNull GroupId.V2 groupId) {
    this.context = context.getApplicationContext();
    this.groupId = groupId;
  }

  void approveRequest(@NonNull Recipient recipient,
                      @NonNull AsynchronousCallback.WorkerThread<Void, GroupChangeFailureReason> callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
      GroupManager.GroupActionResult result = UfsrvFenceUtils.sendFenceLinkJoinAdminAction(groupId, Collections.singleton(recipient.getId()), true);
      if(result.getThreadId() > 0) {
        callback.onComplete(null);
      } else {
        callback.onError(GroupChangeFailureReason.fromException(new Exception("Try again later")));
      }
      /*try {/AA-
        GroupManager.approveRequests(context, groupId, recipientIds);
        callback.onComplete(null);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        callback.onError(GroupChangeFailureReason.fromException(e));
      }*/
    });
  }

  void denyRequest(@NonNull Recipient recipient,
                   @NonNull AsynchronousCallback.WorkerThread<Void, GroupChangeFailureReason> callback)
  {
    SignalExecutors.UNBOUNDED.execute(() -> {
        GroupManager.GroupActionResult result = UfsrvFenceUtils.sendFenceLinkJoinAdminAction(groupId, Collections.singleton(recipient.getId()), false);
        if(result.getThreadId() > 0) {
          callback.onComplete(null);
        } else {
        callback.onError(GroupChangeFailureReason.fromException(new Exception("Try again later")));
        }
      /*try {//AA-
        GroupManager.denyRequests(context, groupId, recipientIds);
        callback.onComplete(null);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        callback.onError(GroupChangeFailureReason.fromException(e));
      }*/
    });
  }
}