package org.thoughtcrime.securesms.groups.ui.invitesandrequests.invited;

import com.unfacd.android.R;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupSecretParams;
import org.signal.libsignal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.GroupMemberEntry;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.DefaultValueLiveData;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class PendingMemberInvitesViewModel extends ViewModel {
  //AA+
  final static  GroupSecretParams placeholderGroupSecretParams = GroupSecretParams.generate(new SecureRandom());
  static byte[] newContents = new byte[65];
  //

  private final Context                                                                context;
  private final PendingMemberInvitesRepository pendingMemberRepository;
  private final DefaultValueLiveData<List<GroupMemberEntry.PendingMember>>             whoYouInvited           = new DefaultValueLiveData<>(Collections.emptyList());
  private final DefaultValueLiveData<List<GroupMemberEntry.UnknownPendingMemberCount>> whoOthersInvited        = new DefaultValueLiveData<>(Collections.emptyList());

  private PendingMemberInvitesViewModel(@NonNull Context context,
                                        @NonNull PendingMemberInvitesRepository pendingMemberRepository)
  {
    this.context                 = context;
    this.pendingMemberRepository = pendingMemberRepository;

    pendingMemberRepository.getInvitees(this::setMembersUfsrv);//AA+ ufrsv
  }

  public LiveData<List<GroupMemberEntry.PendingMember>> getWhoYouInvited() {
    return whoYouInvited;
  }

  public LiveData<List<GroupMemberEntry.UnknownPendingMemberCount>> getWhoOthersInvited() {
    return whoOthersInvited;
  }

  private void setInvitees(List<GroupMemberEntry.PendingMember> byYou, List<GroupMemberEntry.UnknownPendingMemberCount> byOthers) {
    whoYouInvited.postValue(byYou);
    whoOthersInvited.postValue(byOthers);
  }

  //AA+ PORTING NOTE: see implementation below
  private void setMembers(PendingMemberInvitesRepository.InviteeResult inviteeResult) {
    List<GroupMemberEntry.PendingMember>             byMe     = new ArrayList<>(inviteeResult.getByMe().size());
    List<GroupMemberEntry.UnknownPendingMemberCount> byOthers = new ArrayList<>(inviteeResult.getByOthers().size());

    for (PendingMemberInvitesRepository.SinglePendingMemberInvitedByYou pendingMember : inviteeResult.getByMe()) {
      byMe.add(new GroupMemberEntry.PendingMember(pendingMember.getInvitee(),
                                                  pendingMember.getInviteeCipherText(),
                                                  inviteeResult.isCanRevokeInvites()));
    }

    for (PendingMemberInvitesRepository.MultiplePendingMembersInvitedByAnother pendingMembers : inviteeResult.getByOthers()) {
      byOthers.add(new GroupMemberEntry.UnknownPendingMemberCount(pendingMembers.getInviter(),
                                                                  pendingMembers.getUuidCipherTexts(),
                                                                  Collections.emptyList(),
                                                                  inviteeResult.isCanRevokeInvites()));
    }

    setInvitees(byMe, byOthers);
  }

  //AA++
  private void setMembersUfsrv(PendingMemberInvitesRepository.InviteeResult inviteeResult) {
    UuidCiphertext placeholderUuidCiphertext = null;

      try {
        placeholderUuidCiphertext = new UuidCiphertext(newContents);
      } catch (InvalidInputException x) {
        Log.e(PendingMemberInvitesViewModel.class.getName(), x.getMessage());
      }

    List<GroupMemberEntry.PendingMember>             byMe     = new ArrayList<>(inviteeResult.getInvitees().size());

    for (Recipient pendingMember : inviteeResult.getInvitees()) {
      byMe.add(new GroupMemberEntry.PendingMember(pendingMember, placeholderUuidCiphertext, true));
    }

    setInvitees(byMe, Collections.emptyList());
  }

  void revokeInviteFor(@NonNull GroupMemberEntry.PendingMember pendingMember) {
    UuidCiphertext inviteeCipherText = pendingMember.getInviteeCipherText();
    Recipient recipientInvitee = pendingMember.getInvitee();//AA+

    InviteRevokeConfirmationDialog.showOwnInviteRevokeConfirmationDialog(context, pendingMember.getInvitee(), () ->
            SimpleTask.run(
                    () -> {
                      pendingMember.setBusy(true);
                      try {
                        return pendingMemberRepository.revokeInvites(Collections.singleton(recipientInvitee));//AA+ invitee
                      } finally {
                        pendingMember.setBusy(false);
                      }
                    },
                    result -> {
                      if (result) {
                        ArrayList<GroupMemberEntry.PendingMember> newList  = new ArrayList<>(whoYouInvited.getValue());
                        Iterator<GroupMemberEntry.PendingMember> iterator = newList.iterator();

                        while (iterator.hasNext()) {
                          if (iterator.next().getInvitee().equals(recipientInvitee)) {//AA+
                            iterator.remove();
                          }
                        }

                        whoYouInvited.setValue(newList);
                      } else {
                        toastErrorCanceling(1);
                      }
                    }
            ));
  }

  void revokeInvitesFor(@NonNull GroupMemberEntry.UnknownPendingMemberCount pendingMembers) {
    InviteRevokeConfirmationDialog.showOthersInviteRevokeConfirmationDialog(context, pendingMembers.getInviter(), pendingMembers.getInviteCount(),
                                                                            () -> SimpleTask.run(
                                                                                    () -> {
                                                                                      pendingMembers.setBusy(true);
                                                                                      try {
                                                                                        return pendingMemberRepository.revokeInvites(pendingMembers.getRecipients());//AA+ recipients
                                                                                      } finally {
                                                                                        pendingMembers.setBusy(false);
                                                                                      }
                                                                                    },
                                                                                    result -> {
                                                                                      if (result) {
                                                                                        ArrayList<GroupMemberEntry.UnknownPendingMemberCount> newList  = new ArrayList<>(whoOthersInvited.getValue());
                                                                                        Iterator<GroupMemberEntry.UnknownPendingMemberCount>  iterator = newList.iterator();

                                                                                        while (iterator.hasNext()) {
                                                                                          if (iterator.next().getInviter().equals(pendingMembers.getInviter())) {
                                                                                            iterator.remove();
                                                                                          }
                                                                                        }

                                                                                        whoOthersInvited.setValue(newList);
                                                                                      } else {
                                                                                        toastErrorCanceling(pendingMembers.getInviteCount());
                                                                                      }
                                                                                    }
                                                                            ));
  }

  private void toastErrorCanceling(int quantity) {
    Toast.makeText(context, context.getResources().getQuantityText(R.plurals.PendingMembersActivity_error_revoking_invite, quantity), Toast.LENGTH_SHORT)
            .show();
  }

  public static class Factory implements ViewModelProvider.Factory {

    private final Context    context;
    private final GroupId    groupId;

    public Factory(@NonNull Context context, @NonNull GroupId groupId) {
      this.context = context;
      this.groupId = groupId;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection unchecked
      return (T) new PendingMemberInvitesViewModel(context, new PendingMemberInvitesRepository(context.getApplicationContext(), groupId));
    }
  }
}