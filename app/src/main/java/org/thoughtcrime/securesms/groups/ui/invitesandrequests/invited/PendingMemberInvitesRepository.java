package org.thoughtcrime.securesms.groups.ui.invitesandrequests.invited;

import android.content.Context;

import com.unfacd.android.utils.UfsrvFenceUtils;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

/**
 * Repository for modifying the pending members on a single group.
 */
final class PendingMemberInvitesRepository
{

  private static final String TAG = Log.tag(PendingMemberInvitesRepository.class);

  private final Context    context;
  private final GroupId    groupId;
  private final Executor   executor;

  PendingMemberInvitesRepository (@NonNull Context context, @NonNull GroupId groupId) {
    this.context  = context.getApplicationContext();
    this.executor = SignalExecutors.BOUNDED;
    this.groupId  = groupId;
  }

//  public void getInvitees(@NonNull Consumer<InviteeResult> onInviteesLoaded) {
//    executor.execute(() -> {
//      GroupDatabase                                groupDatabase      = SignalDatabase.groups();
//      GroupDatabase.V2GroupProperties              v2GroupProperties  = groupDatabase.getGroup(groupId).get().requireV2GroupProperties();
//      DecryptedGroup                               decryptedGroup     = v2GroupProperties.getDecryptedGroup();
//      List<DecryptedPendingMember>                 pendingMembersList = decryptedGroup.getPendingMembersList();
//      List<SinglePendingMemberInvitedByYou>        byMe               = new ArrayList<>(pendingMembersList.size());
//      List<MultiplePendingMembersInvitedByAnother> byOthers           = new ArrayList<>(pendingMembersList.size());
//      ByteString                                   self               = SignalStore.account().requireAci().toByteString();
//      boolean                                      selfIsAdmin        = v2GroupProperties.isAdmin(Recipient.self());
//
//      Stream.of(pendingMembersList)
//              .groupBy(DecryptedPendingMember::getAddedByUuid)
//              .forEach(g ->
//                       {
//                         ByteString                   inviterUuid    = g.getKey();
//                         List<DecryptedPendingMember> invitedMembers = g.getValue();
//
//                         if (self.equals(inviterUuid)) {
//                           for (DecryptedPendingMember pendingMember : invitedMembers) {
//                             try {
//                               Recipient      invitee        = GroupProtoUtil.pendingMemberToRecipient(context, pendingMember);
//                               UuidCiphertext uuidCipherText = new UuidCiphertext(pendingMember.getUuidCipherText().toByteArray());
//
//                               byMe.add(new SinglePendingMemberInvitedByYou(invitee, uuidCipherText));
//                             } catch (InvalidInputException e) {
//                               Log.w(TAG, e);
//                             }
//                           }
//                         } else {
//                           Recipient                 inviter         = GroupProtoUtil.uuidByteStringToRecipient(context, inviterUuid);
//                           ArrayList<UuidCiphertext> uuidCipherTexts = new ArrayList<>(invitedMembers.size());
//
//                           for (DecryptedPendingMember pendingMember : invitedMembers) {
//                             try {
//                               uuidCipherTexts.add(new UuidCiphertext(pendingMember.getUuidCipherText().toByteArray()));
//                             } catch (InvalidInputException e) {
//                               Log.w(TAG, e);
//                             }
//                           }
//
//                           byOthers.add(new MultiplePendingMembersInvitedByAnother(inviter, uuidCipherTexts));
//                         }
//                       }
//              );
//
//      onInviteesLoaded.accept(new InviteeResult(byMe, byOthers, selfIsAdmin));
//    });
//  }

  //AA- see below
  /*@WorkerThread
  boolean revokeInvites(@NonNull Collection<UuidCiphertext> uuidCipherTexts) {
    try {
      GroupManager.revokeInvites(context, groupId, uuidCipherTexts);
      return true;
    } catch (GroupChangeException | IOException e) {
      Log.w(TAG, e);
      return false;
    }
  }*/

  @WorkerThread
  boolean revokeInvites(@NonNull Collection<Recipient> recipients) {
    return UfsrvFenceUtils.deleteInvitationFor(context, groupId, recipients) > 0;

  }

  //AA++
  public void getInvitees(@NonNull Consumer<InviteeResult> onInviteesLoaded) {
    executor.execute(() -> {
      GroupDatabase                                groupDatabase      = SignalDatabase.groups();
      List<Recipient>                              invitees           = groupDatabase.getGroupInvitedMembers(groupId, true);

      boolean                                       groupOwnedByme    = groupDatabase.amIGroupOwner(groupId);
      onInviteesLoaded.accept(new InviteeResult(invitees, groupOwnedByme));
    });
  }
  //

  public static final class InviteeResult {
    private final List<SinglePendingMemberInvitedByYou>        byMe;
    private final List<MultiplePendingMembersInvitedByAnother> byOthers;
    private final boolean                                      canRevokeInvites;
    private final List<Recipient>                              invitees;//AA+

    private InviteeResult(List<SinglePendingMemberInvitedByYou> byMe,
                          List<MultiplePendingMembersInvitedByAnother> byOthers,
                          boolean canCancelInvites)
    {
      this.byMe                   = byMe;
      this.byOthers               = byOthers;
      this.canRevokeInvites = canCancelInvites;
      this.invitees               = new LinkedList<>();//AA+
    }

    //AA+
    private InviteeResult(List<Recipient> invitees, boolean canCancelInvites)
    {
      this.byMe     = null;
      this.byOthers = null;
      this.canRevokeInvites = canCancelInvites;
      this.invitees = invitees;
    }

    public List<SinglePendingMemberInvitedByYou> getByMe() {
      return byMe;
    }

    public List<MultiplePendingMembersInvitedByAnother> getByOthers() {
      return byOthers;
    }

    //AA+
    public List<Recipient> getInvitees () {
      return invitees;
    }

    public boolean isCanRevokeInvites () {
      return canRevokeInvites;
    }
  }

  public final static class SinglePendingMemberInvitedByYou {
    private final Recipient      invitee;
    private final UuidCiphertext inviteeCipherText;

    private SinglePendingMemberInvitedByYou(@NonNull Recipient invitee, @NonNull UuidCiphertext inviteeCipherText) {
      this.invitee           = invitee;
      this.inviteeCipherText = inviteeCipherText;
    }

    public Recipient getInvitee() {
      return invitee;
    }

    public UuidCiphertext getInviteeCipherText() {
      return inviteeCipherText;
    }
  }

  public final static class MultiplePendingMembersInvitedByAnother {
    private final Recipient                  inviter;
    private final Collection<UuidCiphertext> uuidCipherTexts;

    private MultiplePendingMembersInvitedByAnother(@NonNull Recipient inviter, @NonNull Collection<UuidCiphertext> uuidCipherTexts) {
      this.inviter         = inviter;
      this.uuidCipherTexts = uuidCipherTexts;
    }

    public Recipient getInviter() {
      return inviter;
    }

    public Collection<UuidCiphertext> getUuidCipherTexts() {
      return uuidCipherTexts;
    }
  }
}