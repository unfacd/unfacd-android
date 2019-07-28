/**
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import com.unfacd.android.R;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import org.thoughtcrime.securesms.logging.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.unfacd.android.location.ufLocation;

import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.HashSet;
import java.util.Set;


/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 *
 */
//:BACKPORT https://github.com/WhisperSystems/Signal-Android/commit/2a23b53988314c6b0f4216509727fee891d6e36e
public class NewConversationActivity extends ContactSelectionActivity {

  private static final String TAG = NewConversationActivity.class.getSimpleName();

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    //getToolbar().setShowCustomNavigationButton(false);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
  }

  @Override
  public void onContactSelected(String number) {
    Recipient recipient = Recipient.from(this, Address.fromExternal(this, number), true);

    Log.d(TAG, String.format("onContactSelected: Contact selected: number:'%s', nickname:'%s'", number, recipient.getNickname()));

    new CreatePrivateGroupTask(this, null, number).execute();

    //- executed in asyncTask
//    Intent intent = new Intent(this, ConversationActivity.class);
//    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());
//    intent.putExtra(ConversationActivity.TEXT_EXTRA, getIntent().getStringExtra(ConversationActivity.TEXT_EXTRA));
//    intent.setDataAndType(getIntent().getData(), getIntent().getType());
//
//    long existingThread = DatabaseFactory.getThreadDatabase(this).getThreadIdIfExistsFor(recipient);
//
//    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, existingThread);
//    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
//    startActivity(intent);
//    finish();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home:   super.onBackPressed(); return true;
      case R.id.menu_refresh:   handleManualRefresh(); return true;
      case R.id.menu_new_group: handleCreateGroup();   return true;
      case R.id.menu_invite:    handleInvite();        return true;
    }

    return false;
  }

  private void handleManualRefresh() {
    contactsFragment.setRefreshing(true);
    onRefresh();
  }

  private void handleCreateGroup() {
        startActivity(new Intent(this, GroupCreateActivity.class));
      }

  private void handleInvite() {
      startActivity(new Intent(this, InviteActivity.class));
    }

  @Override
  protected boolean onPrepareOptionsPanel(View view, Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();
    inflater.inflate(R.menu.new_conversation_activity, menu);
    super.onPrepareOptionsMenu(menu);
    return true;
  }

  private void handleOpenConversation(long threadId, Recipient recipient) {
    Log.d(TAG, ">> handleOpenConversation: launching ConversationActivity on threadid: "+threadId);
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT);
    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());
    intent.putExtra(ConversationActivity.TEXT_EXTRA, getIntent().getStringExtra(ConversationActivity.TEXT_EXTRA));
    intent.putExtra(ConversationActivity.GROUP_PRIVACY_MODE, GroupDatabase.PrivacyMode.PRIVATE.getValue());
    intent.setDataAndType(getIntent().getData(), getIntent().getType());

    startActivity(intent);
    finish();
  }

  public class CreatePrivateGroupTask extends AsyncTask<Void,Void,Optional<GroupManager.GroupActionResult>>
  {
    protected AppCompatActivity   activity;
    protected Set<Recipient>      members = new HashSet<>();
    protected String              name;

    public CreatePrivateGroupTask(AppCompatActivity activity, String name, String usernameE41/*, Set<Recipient> members*/) {
      this.activity     = activity;
      this.name         = name;

      this.members.add(Recipient.from(activity, Address.fromSerialized(usernameE41), true));
    }

    @Override
    protected Optional<GroupManager.GroupActionResult> doInBackground(Void... aVoid) {
        GroupManager.GroupActionResult result;
        result=GroupManager.createPrivateGroup(activity,
                                               members,
                                                name,
                                               false,
                                                ufLocation.getInstance().getBaseLocationPrefix(),
                                                0.0, 0.0);

        if (result == null)   return Optional.absent();
        else {
          return Optional.of(result);
        }
    }

    @Override
    protected void onPostExecute(Optional<GroupManager.GroupActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (!activity.isFinishing()) {
          handleOpenConversation(result.get().getThreadId(), result.get().getGroupRecipient());
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }

  }
}
