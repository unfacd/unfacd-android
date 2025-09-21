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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.unfacd.android.R;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.location.ufLocation;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;


/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 *
 */
//AA BACKPORT https://github.com/WhisperSystems/Signal-Android/commit/2a23b53988314c6b0f4216509727fee891d6e36e
public class NewConversationActivity extends ContactSelectionActivity
        implements ContactSelectionListFragment.ListCallback
{
  private static final String TAG = Log.tag(NewConversationActivity.class);

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    //getToolbar().setShowCustomNavigationButton(false);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setTitle(R.string.NewConversationActivity__new_message);
  }

  @Override
  public void onBeforeContactSelected(@NonNull Optional<RecipientId> recipientId, String number, @NonNull Consumer<Boolean> callback) {
    if (recipientId.isPresent()) {
      launch(Recipient.resolved(recipientId.get()));
    } else {
      Log.i(TAG, "[onContactSelected] Maybe creating a new recipient.");

      if (SignalStore.account().isRegistered() && NetworkConstraint.isMet(getApplication())) {
        Log.i(TAG, "[onContactSelected] Doing contact refresh.");

        AlertDialog progress = SimpleProgressDialog.show(this);

        SimpleTask.run(getLifecycle(), () -> {
          Recipient resolved = Recipient.external(this, number);

          if (!resolved.isRegistered()) {//AA- // || !resolved.hasServiceId()) {
            Log.i(TAG, "[onContactSelected] Not registered or no UUID. Doing a directory refresh.");
            try {
              ContactDiscovery.refresh(LocallyAddressableUfsrvUid.from(resolved.requireServiceIdUfsrv()));
              resolved = Recipient.resolved(resolved.getId());
            } catch (IOException e) {
              Log.w(TAG, "[onContactSelected] Failed to refresh directory for new contact.");
            }
          }

          return resolved;
        }, resolved -> {
          progress.dismiss();
          launch(resolved);
        });
      } else {
        launch(Recipient.external(this, number));
      }
    }

    callback.accept(true);
  }

  @Override
  public void onSelectionChanged() {
  }

  private Single<Optional<GroupManager.GroupActionResult>> createPairedGroup(@NonNull Context context, @NonNull Recipient recipient)
  {
    return Single.fromCallable(() -> {
      Set<Recipient>      members = new HashSet<>();

      members.add(recipient);
      GroupManager.GroupActionResult result = GroupManager.createPairedGroup(context,
                                                                             members,
                                                                             "", //this will trigger creation of a unique name based on groupid+other stuff
                                                                             false,
                                                                             ufLocation.getInstance().getBaseLocationPrefix(),
                                                                             0.0, 0.0);
      return Optional.ofNullable(result);
    });
  }

  private void launch(Recipient recipient) {
    createPairedGroup(getApplicationContext(), recipient)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSuccess(groupActionResult -> {
              if (groupActionResult.isPresent() && groupActionResult.get().getThreadId() > -1) {
                if (!this.isFinishing()) {
                  handleOpenConversation(groupActionResult.get().getThreadId(), groupActionResult.get().getGroupRecipient());
                }
              } else {
                Toast.makeText(getApplicationContext(),
                               R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
              }
            }).subscribe();




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
    startActivity(CreateGroupActivity.newIntent(this));
  }

  private void handleInvite() {
      startActivity(new Intent(this, InviteActivity.class));
    }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.clear();
    getMenuInflater().inflate(R.menu.new_conversation_activity, menu);

    super.onCreateOptionsMenu(menu);
    return true;
  }

  @Override
  public void onInvite() {
    handleInvite();
    finish();
  }

  @Override
  public void onNewGroup(boolean forceV1) {
    handleCreateGroup();
    finish();
  }

  private void handleOpenConversation(long threadId, Recipient recipient) {
    Log.d(TAG, ">> handleOpenConversation: launching ConversationParentFragment on threadid: " + threadId);
    long   existingThread = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());
    Intent intent         = ConversationIntents.createBuilder(this, recipient.getId(), existingThread)
            .withDraftText(getIntent().getStringExtra(Intent.EXTRA_TEXT))
            .withDataUri(getIntent().getData())
            .withDataType(getIntent().getType())
            .build();

    startActivity(intent);
    finish();
  }
}
