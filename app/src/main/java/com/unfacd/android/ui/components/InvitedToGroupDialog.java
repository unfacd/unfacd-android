/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.ui.components;

import com.unfacd.android.R;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import androidx.appcompat.app.AlertDialog;
import android.util.Pair;

import com.unfacd.android.utils.Utils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientExporter;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.LinkedList;
import java.util.List;


public class InvitedToGroupDialog extends AsyncTask<Void, Void, Pair<List<Recipient>,List<Recipient>>>
{
    private static final String TAG = Log.tag(InvitedToGroupDialog.class);

    private final Recipient recipient;
    private final Context context;
    private final long threadId;
    private final InvitedToGroupDialog.DialogButtonListener listener;

    private boolean isJoinAccepted = false;

    public InvitedToGroupDialog(Context context, Recipient recipient, InvitedToGroupDialog.DialogButtonListener listener, long threadId) {
      this.recipient    = recipient;
      this.context      = context;
      this.threadId     = threadId;
      this.listener     = listener;
    }

    @Override
    public void onPreExecute() {}

    @Override
    protected Pair<List<Recipient>, List<Recipient>> doInBackground(Void... params) {
      GroupId groupId = recipient.requireGroupId();

      List<Recipient> groupInvitedMembers = SignalDatabase.groups().getGroupInvitedMembers(groupId, false);//display my name if on invite list
      List<Recipient> groupCurrentMembers = SignalDatabase.groups().getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_INCLUDING_SELF);

      return new Pair<>(groupCurrentMembers, groupInvitedMembers);
    }

    @Override
    public void onPostExecute(Pair<List<Recipient>,List<Recipient>> members) {//AA+added pair
      GroupMembers groupMembers = new GroupMembers(members);
      AlertDialog.Builder builder = new AlertDialog.Builder(context);
      builder.setTitle(R.string.ConversationListActivity_Daialog_invited_to_join);
      builder.setIcon(R.drawable.ic_group_24);
      builder.setCancelable(true);
      builder.setItems(groupMembers.getRecipientStrings(), new GroupMembersOnClickListener(context, groupMembers));
      //builder.setPositiveButton(android.R.string.ok, null);

      builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {

          Context self = context;//parent activity
          if (listener != null) {
            int doWhat = listener.onDialogButtonClicked(which, recipient, threadId);
            isJoinAccepted = true;
          }
          /*try {
            byte[] groupId = GroupUtil.getDecodedId(recipients.getPrimaryRecipient().getNumber());
            DatabaseFactory.getGroupDatabase(self).setActive(groupId, false);

            //AA+
            GroupDatabase.GroupRecord groupRec=DatabaseFactory.getGroupDatabase(self).getGroupByCname(groupId);
            //AA+ adde Fencecommand context
            SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandJoinInvitationResponse(ApplicationContext.getInstance(), 0, true);

            SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
                    .setFenceCommand(fenceCommandBuilder.build())
                    .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
            //
            SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                    .setId(ByteString.copyFrom(groupId))
                    .setType(SignalServiceProtos.GroupContext.Type.QUIT)
                    .setFenceMessage(fenceCommandBuilder.build())//AA+
                    .build();

            OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipients, groupContext, null, System.currentTimeMillis(), 0,
                    ufsrvCommandBuilder.build());//AA+ ufsrvcommand
            MessageSender.send(self, masterSecret, outgoingMessage, threadId, false, 0);//AA+ 0 for mode
            DatabaseFactory.getGroupDatabase(self).remove(groupId, TextSecurePreferences.getLocalNumber(self));
            //initializeEnabledCheck();
          } catch (IOException e) {
            Log.w(TAG, e);
            Toast.makeText(self, R.string.ConversationActivity_error_leaving_group, Toast.LENGTH_LONG).show();
          }*/
        }
      });

      builder.show();
    }
    //

    public boolean isJoinAccepted()
    {
      return  this.isJoinAccepted;
    }

    public void display() {
      if (recipient.isGroup()) execute();
        //else                               onPostExecute(recipients);
      else                               {
        List<Recipient> recipientList = new LinkedList<>();
        recipientList.add(recipient);
        onPostExecute(new Pair<>(recipientList, (List<Recipient>)null));
      }
    }

    private static class GroupMembersOnClickListener implements DialogInterface.OnClickListener {
      private final GroupMembers groupMembers;
      private final Context      context;

      public GroupMembersOnClickListener(Context context, GroupMembers members) {
        this.context      = context;
        this.groupMembers = members;
      }

      @Override
      public void onClick(DialogInterface dialogInterface, int item) {
        Recipient recipient = groupMembers.get(item);

        //AA+ seperator
        if (recipient.getUfsrvId() == 0)  return;

        if (recipient.getContactUri() != null) {
          ContactsContract.QuickContact.showQuickContact(context, new Rect(0,0,0,0),
                  recipient.getContactUri(),
                  ContactsContract.QuickContact.MODE_LARGE, null);
        } else {
          context.startActivity(RecipientExporter.export(recipient).asAddContactIntent());
        }
      }
    }

  /**
   * Notifies listeners of which button on the dailog the user pressed
   */
  //AA+
    public  interface DialogButtonListener {
      int onDialogButtonClicked (int button, Recipient recipient, long threadId);
    }
  //
    /**
     * Wraps a List of Recipient (just like @class Recipients),
     * but with focus on the order of the Recipients.
     * So that the order of the RecipientStrings[] matches
     * the internal order.
     *
     * @author Christoph Haefner
     */
    private class GroupMembers {
      private final String TAG = Log.tag(GroupMembers.class);

      private final LinkedList<Recipient> members = new LinkedList<>();

      //AA+ support for invited
      public GroupMembers(Pair<List<Recipient>, List<Recipient>> recipients) {
        for (Recipient recipient : recipients.first) {
          if (isLocalUfsrvUid(recipient)) {
            members.push(recipient);
          } else {
            members.add(recipient);
          }
        }

        if (recipients.second != null) {
          //dumb seperator indicator
          members.add(Recipient.UNKNOWN);

          for (Recipient recipient : recipients.second) {
            if (isLocalUfsrvUid(recipient)) {
              members.push(recipient);
            } else {
              members.add(recipient);
            }
          }
        }
      }

      //AA+
      public CharSequence[] getRecipientStrings() {
        List<CharSequence> recipientStrings = new LinkedList<>();

        for (Recipient recipient : members) {
          if (recipient.getUfsrvId() == 0) {
            recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_invited_members)));
          } else {
            if (isLocalUfsrvUid(recipient)) {
              recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_you)));
            } else {
              recipientStrings.add(recipient.getDisplayName());
            }
          }
        }

        return recipientStrings.toArray(new CharSequence[members.size()]);
      }

      public Recipient get(int index) {
        return members.get(index);
      }

      private boolean isLocalUfsrvUid(Recipient recipient) {
        String localufsrvuid = TextSecurePreferences.getUfsrvUserId(context);
        String ufsrvUid  = recipient.requireAddress().toString();
        return ufsrvUid != null && ufsrvUid.equals(localufsrvuid);
      }
    }


}
