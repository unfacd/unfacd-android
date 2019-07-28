package org.thoughtcrime.securesms;

import com.unfacd.android.R;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import androidx.appcompat.app.AlertDialog;
import android.util.Pair;

import com.unfacd.android.utils.Utils;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Util;

import java.util.LinkedList;
import java.util.List;


public class GroupMembersDialog extends AsyncTask<Void, Void, Pair<List<Recipient>,List<Recipient>>> // Pair
{

  private static final String TAG = GroupMembersDialog.class.getSimpleName();

  private final Recipient recipient;
  private final Context    context;

  public GroupMembersDialog(Context context, Recipient recipient) {
    this.recipient = recipient;
    this.context    = context;
  }

  @Override
  public void onPreExecute() {}


  @Override
  protected Pair<List<Recipient>, List<Recipient>> doInBackground(Void... params) {
    String groupId = recipient.getAddress().toGroupString();

    List<Recipient> groupInvitedMembers=DatabaseFactory.getGroupDatabase(context)
            .getGroupInvitedMembers(groupId, false);
    List<Recipient> groupCurrentMembers=DatabaseFactory.getGroupDatabase(context)
            .getGroupMembers(groupId, false);

    return new Pair<>(groupCurrentMembers, groupInvitedMembers);
    //return DatabaseFactory.getGroupDatabase(context)
        //      .getGroupMembers(GroupUtil.getDecodedId(groupId), true);
  }

  //- orig
 /* @Override
  protected Recipients doInBackground(Void... params) {
    return DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.getAddress().toGroupString(), true);
  }*/

  //
  @Override
  //public void onPostExecute(List<Recipient> members) {
  public void onPostExecute(Pair<List<Recipient>,List<Recipient>> members) {//added pair
    GroupMembers groupMembers = new GroupMembers(members);
    //GroupMembers groupMembersInvited = new GroupMembers(members.second);
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(R.string.ConversationActivity_group_members);
    builder.setIconAttribute(R.attr.group_members_dialog_icon);
    builder.setCancelable(true);
    builder.setItems(groupMembers.getRecipientStrings(), new GroupMembersOnClickListener(context, groupMembers));
    builder.setPositiveButton(android.R.string.ok, null);
    builder.show();
  }
  //

  //- orig
/*
  @Override
 public void onPostExecute(List<Recipient> members) {
    GroupMembers groupMembers = new GroupMembers(members);
    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(R.string.ConversationActivity_group_members);
    builder.setIconAttribute(R.attr.group_members_dialog_icon);
    builder.setCancelable(true);
    builder.setItems(groupMembers.getRecipientStrings(), new GroupMembersOnClickListener(context, groupMembers));
    builder.setPositiveButton(android.R.string.ok, null);
    builder.show();
  }
*/

  public void display() {
    execute();
//    if (recipients.isGroupRecipient()) execute();
//    //else                               onPostExecute(recipients);
//    else                               onPostExecute(new Pair<>(recipients, (Recipients)null));
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

      if (isLocalNumber(recipient)) {
        Intent intent = new Intent(context, CreateProfileActivity.class);
        intent.putExtra(CreateProfileActivity.EXCLUDE_SYSTEM, true);

        context.startActivity(intent);
      } else {
        Intent intent = new Intent(context, RecipientPreferenceActivity.class);
        intent.putExtra(RecipientPreferenceActivity.ADDRESS_EXTRA, recipient.getAddress());
        intent.putExtra(RecipientPreferenceActivity.CAN_HAVE_SAFETY_NUMBER_EXTRA,
                      /*isSecureText&&*/ !Utils.isSelfConversation(context, recipient)); //we always assume secure identity for now because only unfacd users are considered

        context.startActivity(intent);
      }
      //- see above
//      if (recipient.getContactUri() != null) {
//        ContactsContract.QuickContact.showQuickContact(context, new Rect(0,0,0,0),
//                                                       recipient.getContactUri(),
//                                                       ContactsContract.QuickContact.MODE_LARGE, null);
//      } else {
//        getContext().startActivity(RecipientExporter.export(recipient).asAddContactIntent());
//      }
    }

    boolean isLocalNumber(Recipient recipient) {
      return Util.isOwnNumber(context, recipient.getAddress());
    }
  }

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
     public GroupMembers(List<Recipient> recipients) {
      for (Recipient recipient : recipients) {
        if (isLocalNumber(recipient)) {
          members.push(recipient);
        } else {
          members.add(recipient);
        }
      }
    }*/

    // support for invited
    public GroupMembers(Pair<List<Recipient>,List<Recipient>> recipients) {
      for (Recipient recipient : recipients.first) {
        if (isLocalNumber(recipient)) {
          members.push(recipient);
        } else {
          members.add(recipient);
        }
      }

      if (recipients.second!=null && recipients.second.size()>0)
      {
        //dumb seperator indicator
        members.add(Recipient.makeNullRecipient());

        for (Recipient recipient : recipients.second)
        {
          if (isLocalNumber(recipient))
          {
//            members.push(recipient);
            members.add(recipient);
            //todo: add decoration. dont push as that puts it to the very to, along with regular members
          }
          else
          {
            members.add(recipient);
          }
        }
      }
    }

    //
    public CharSequence[] getRecipientStrings() {
      List<CharSequence> recipientStrings = new LinkedList<>();

      for (Recipient recipient : members)
      {
        if (recipient.getUfsrvId() == 0)
        {
          recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_invited_members)));
        }
        else
        {
          if (isLocalNumber(recipient)) {
            recipientStrings.add(Utils.replaceTags(context.getString(R.string.GroupMembersDialog_me)));
          } else {
            String name = recipient.getDisplayName();//toShortString();

            //-
//            if (recipient.getName() == null && !TextUtils.isEmpty(recipient.getProfileName())) {
//              name += " ~" + recipient.getProfileName();
//            }

            recipientStrings.add(name);
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
          String name = recipient.toShortString();

                  if (recipient.getName() == null && !TextUtils.isEmpty(recipient.getProfileName())) {
              name += " ~" + recipient.getProfileName();
          }

          recipientStrings.add(name);
        }
      }

      return recipientStrings.toArray(new String[members.size()]);
    }*/

    public Recipient get(int index) {
      return members.get(index);
    }

    private boolean isLocalNumber(Recipient recipient) {
      return Util.isOwnNumber(context, recipient.getAddress());
    }
  }
}
