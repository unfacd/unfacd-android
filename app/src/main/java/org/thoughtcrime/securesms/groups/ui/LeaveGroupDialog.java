package org.thoughtcrime.securesms.groups.ui;

import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import com.annimon.stream.Stream;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.chooseadmin.ChooseNewAdminActivity;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;
import java.util.Optional;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP;

public final class LeaveGroupDialog {
  private static final String TAG = Log.tag(LeaveGroupDialog.class);
  private int groupLeaveMode = GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED;//AA+

  @NonNull  private final FragmentActivity activity;
  @NonNull  private final GroupId.Push     groupId;
  @Nullable private final Runnable         onSuccess;

  private LeaveGroupDialog(@NonNull FragmentActivity activity,
                           @NonNull GroupId.Push groupId,
                           @Nullable Runnable onSuccess) {
    this.activity  = activity;
    this.groupId   = groupId;
    this.onSuccess = onSuccess;
  }

  public static void handleLeavePushGroup(@NonNull FragmentActivity activity,
                                          @NonNull GroupId.Push groupId,
                                          @Nullable Runnable onSuccess) {
    new LeaveGroupDialog(activity, groupId, onSuccess).show();
  }

  public void show() {

    if (true) {//AA+
      showLeaveDialog();
      return;
    }

    if (!groupId.isV2()) {
      showLeaveDialog();
      return;
    }

    SimpleTask.run(activity.getLifecycle(), () -> {
      GroupDatabase.V2GroupProperties groupProperties = SignalDatabase.groups()
                                                                      .getGroup(groupId)
                                                                      .map(GroupDatabase.GroupRecord::requireV2GroupProperties)
                                                                      .orElse(null);

      if (groupProperties != null && groupProperties.isAdmin(Recipient.self())) {
        List<Recipient> otherMemberRecipients = groupProperties.getMemberRecipients(GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
        long            otherAdminsCount      = Stream.of(otherMemberRecipients).filter(groupProperties::isAdmin).count();

        return otherAdminsCount == 0 && !otherMemberRecipients.isEmpty();
      }

      return false;
    }, mustSelectNewAdmin -> {
      if (mustSelectNewAdmin) {
        showSelectNewAdminDialog();
      } else {
        showLeaveDialog();
      }
    });
  }

  private void showSelectNewAdminDialog() {
    new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.LeaveGroupDialog_choose_new_admin)
            .setMessage(R.string.LeaveGroupDialog_before_you_leave_you_must_choose_at_least_one_new_admin_for_this_group)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.LeaveGroupDialog_choose_admin, (d,w) -> activity.startActivity(ChooseNewAdminActivity.createIntent(activity, groupId.requireV2())))
            .show();
  }

  private void showLeaveDialog() {
    //AA+ support for clean storage on leave
    View dontShowAgainLayout  =  activity.getLayoutInflater().inflate(R.layout.dialog_checkbox, null);
    CheckBox dontShowAgain    = dontShowAgainLayout.findViewById(R.id.skip);
    dontShowAgain.setText(R.string.remove_groups_storage);
    dontShowAgain.setOnCheckedChangeListener((buttonView, isChecked) -> groupLeaveMode = GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP);

    new MaterialAlertDialogBuilder(activity)
            .setView(dontShowAgainLayout)//AA+
            .setTitle(R.string.LeaveGroupDialog_leave_group)
            .setCancelable(true)
            .setMessage(R.string.LeaveGroupDialog_you_will_no_longer_be_able_to_send_or_receive_messages_in_this_group)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.LeaveGroupDialog_leave, (dialog, which) -> {
              AlertDialog progressDialog = SimpleProgressDialog.show(activity);
              SimpleTask.run(activity.getLifecycle(), this::leaveGroup, result -> {
                progressDialog.dismiss();
                handleLeaveGroupResult(result);
              });
            })
            .show();
  }

  private @NonNull GroupChangeResult leaveGroup() {
    //AA-
   /* try {
      GroupManager.leaveGroup(activity, groupId);
      return GroupChangeResult.SUCCESS;
    } catch (GroupChangeException | IOException e) {
      Log.w(TAG, e);
      return GroupChangeResult.failure(GroupChangeFailureReason.fromException(e));
    }*/


    Recipient                            groupRecipient = Recipient.externalGroupExact(activity, groupId);
    long                                 threadId       = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
    Optional<OutgoingGroupUpdateMessage> leaveMessage   = GroupUtil.createGroupLeaveMessage(groupRecipient);

    if (threadId != -1 && leaveMessage.isPresent()) {
      MessageSender.send(activity, leaveMessage.get(), threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, true);
      return GroupChangeResult.SUCCESS;
    }

    return GroupChangeResult.failure(GroupChangeFailureReason.OTHER);//todo: be more precise
  }

  private void handleLeaveGroupResult(@NonNull GroupChangeResult result) {
    if (result.isSuccess()) {
      if (onSuccess != null) onSuccess.run();
    } else {
      Toast.makeText(activity, GroupErrors.getUserDisplayMessage(result.getFailureReason()), Toast.LENGTH_LONG).show();
    }
  }
}