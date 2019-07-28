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

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientExporter;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.LinkedList;
import java.util.List;


public class InvitedToGroupDialog extends AsyncTask<Void, Void, Pair<List<Recipient>,List<Recipient>>>
{
    private static final String TAG = InvitedToGroupDialog.class.getSimpleName();

    private final Recipient recipient;
    private final Context context;
    private final long threadId;

    private boolean isJoinAccepted=false;

    public InvitedToGroupDialog(Context context, Recipient recipient, long threadId) {
      this.recipient    = recipient;// represents the group as a nominal thing groupId, does not include them though
      this.context      = context;
      this.threadId     = threadId;
    }

    @Override
    public void onPreExecute() {}

    @Override
    protected Pair<List<Recipient>, List<Recipient>> doInBackground(Void... params) {
      String groupId = recipient.getAddress().toGroupString();

      List<Recipient> groupInvitedMembers= DatabaseFactory.getGroupDatabase(context)
              .getGroupInvitedMembers(groupId, false);//display my name if on invite list
      List<Recipient> groupCurrentMembers=DatabaseFactory.getGroupDatabase(context)
              .getGroupMembers(groupId, false);

      return new Pair<>(groupCurrentMembers, groupInvitedMembers);
      //return DatabaseFactory.getGroupDatabase(context)
      //      .getGroupMembers(GroupUtil.getDecodedId(groupId), true);
    }


    @Override
    public void onPostExecute(Pair<List<Recipient>,List<Recipient>> members) {//added pair
      GroupMembers groupMembers = new GroupMembers(members);
      //GroupMembers groupMembersInvited = new GroupMembers(members.second);
      AlertDialog.Builder builder = new AlertDialog.Builder(context);
      builder.setTitle(R.string.ConversationListActivity_Daialog_invited_to_join);
      builder.setIconAttribute(R.attr.group_members_dialog_icon);
      builder.setCancelable(true);
      builder.setItems(groupMembers.getRecipientStrings(), new GroupMembersOnClickListener(context, groupMembers));
      //builder.setPositiveButton(android.R.string.ok, null);


      builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {

          Context self = context;//parent activity
          int doWhat=((DialogButtonListener) context).onDialogButtonClicked(which, recipient, threadId);
          isJoinAccepted=true;
          /*try {
            byte[] groupId = GroupUtil.getDecodedId(recipients.getPrimaryRecipient().getNumber());
            DatabaseFactory.getGroupDatabase(self).setActive(groupId, false);

            //
            GroupDatabase.GroupRecord groupRec=DatabaseFactory.getGroupDatabase(self).getGroupByCname(groupId);
            // adde Fencecommand context
            SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandJoinInvitationResponse(ApplicationContext.getInstance(), 0, true);

            SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
                    .setFenceCommand(fenceCommandBuilder.build())
                    .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
            //
            SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                    .setId(ByteString.copyFrom(groupId))
                    .setType(SignalServiceProtos.GroupContext.Type.QUIT)
                    .setFenceMessage(fenceCommandBuilder.build())//
                    .build();

            OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipients, groupContext, null, System.currentTimeMillis(), 0,
                    ufsrvCommandBuilder.build());// ufsrvcommand
            MessageSender.send(self, masterSecret, outgoingMessage, threadId, false, 0);// 0 for mode
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
      if (recipient.isGroupRecipient()) execute();
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

        // seperator
        if (recipient.getUfsrvId()==0)  return;

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
  //
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
      private final String TAG = GroupMembers.class.getSimpleName();

      private final LinkedList<Recipient> members = new LinkedList<>();

      //-
   /*
    public GroupMembers(Recipients recipients) {
      for (Recipient recipient : recipients.getRecipientsList()) {
        if (isLocalNumber(recipient)) {
          members.push(recipient);
        } else {
          members.add(recipient);
        }
      }
    }*/

      // support for invited
      public GroupMembers(Pair<List<Recipient>, List<Recipient>> recipients) {
        for (Recipient recipient : recipients.first) {
          if (isLocalUfsrvUid(recipient)) {
            members.push(recipient);
          } else {
            members.add(recipient);
          }
        }

        if (recipients.second!=null) {
          //dumb seperator indicator
          members.add(Recipient.makeNullRecipient());

          for (Recipient recipient : recipients.second) {
            if (isLocalUfsrvUid(recipient)) {
              members.push(recipient);
            } else {
              members.add(recipient);
            }
          }
        }
      }

      //
      public CharSequence[] getRecipientStrings() {
        List<CharSequence> recipientStrings = new LinkedList<>();

        for (Recipient recipient : members) {
          if (recipient.getUfsrvId() == 0) {
            recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_invited_members)));
          } else {
            if (isLocalUfsrvUid(recipient)) {
              recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_me)));
            } else {
              recipientStrings.add(recipient.toShortString());
            }
          }
        }

        return recipientStrings.toArray(new CharSequence[members.size()]);
      }

    /*public String[] getRecipientStrings() {
      List<String> recipientStrings = new LinkedList<>();

      for (Recipient recipient : members) {
        if (isLocalNumber(recipient)) {
          recipientStrings.add(context.getString(R.string.GroupMembersDialog_me));
        } else {
          recipientStrings.add(recipient.toShortString());
        }
      }

      return recipientStrings.toArray(new String[members.size()]);
    }*/

      public Recipient get(int index) {
        return members.get(index);
      }

      private boolean isLocalNumber(Recipient recipient) {
          String localNumber = TextSecurePreferences.getUfsrvUserId(context);
          String e164Number  = recipient.getAddress().toPhoneString();
          return e164Number != null && e164Number.equals(localNumber);
      }

      private boolean isLocalUfsrvUid(Recipient recipient) {
        String localufsrvuid = TextSecurePreferences.getUfsrvUserId(context);
        String ufsrvUid  = recipient.getAddress().toString();
        return ufsrvUid != null && ufsrvUid.equals(localufsrvuid);
      }
    }


}
