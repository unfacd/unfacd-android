package org.thoughtcrime.securesms.profiles;

import android.content.Context;
import android.os.AsyncTask;
import android.view.View;
import android.widget.FrameLayout;

import com.unfacd.android.R;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientExporter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

public class UnknownSenderView extends FrameLayout {

  private final @NonNull Recipient recipient;
  private final          long      threadId;
  private final          Listener  listener;

  public UnknownSenderView(@NonNull Context context, @NonNull Recipient recipient, long threadId, @NonNull Listener listener) {
    super(context);
    this.recipient = recipient;
    this.threadId  = threadId;
    this.listener  = listener;

    inflate(context, R.layout.unknown_sender_view, this);
    setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    View block         = findViewById(R.id.block);
    View add           = findViewById(R.id.add_to_contacts);
    View profileAccess = findViewById(R.id.share_profile);

    block.setOnClickListener(v -> handleBlock());
    add.setOnClickListener(v -> handleAdd());
    profileAccess.setOnClickListener(v -> handleProfileAccess());
  }

  private void handleBlock() {
    final Context context = getContext();

    new AlertDialog.Builder(getContext())
            .setIcon(R.drawable.ic_alert)
            .setTitle(getContext().getString(R.string.UnknownSenderView_block_s, recipient.getDisplayName(context)))
            .setMessage(R.string.UnknownSenderView_blocked_contacts_will_no_longer_be_able_to_send_you_messages_or_call_you)
            .setPositiveButton(R.string.UnknownSenderView_block, (dialog, which) -> {
              new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                  SignalDatabase.recipients().setBlocked(recipient.getId(), true);
                  if (threadId != -1) SignalDatabase.threads().setHasSentSilently(threadId, true);
                  return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                  listener.onActionTaken();
                }
              }.executeOnExecutor(SignalExecutors.BOUNDED);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
  }

  private void handleAdd() {
    getContext().startActivity(RecipientExporter.export(recipient).asAddContactIntent());
    if (threadId != -1) SignalDatabase.threads().setHasSentSilently(threadId, true);
    listener.onActionTaken();
  }

  private void handleProfileAccess() {
    final Context context = getContext();

    new AlertDialog.Builder(getContext())
            .setIcon(R.drawable.ic_info_outline)
            .setTitle(getContext().getString(R.string.UnknownSenderView_share_profile_with_s, recipient.getDisplayName(context)))
            .setMessage(R.string.UnknownSenderView_the_easiest_way_to_share_your_profile_information_is_to_add_the_sender_to_your_contacts)
            .setPositiveButton(R.string.UnknownSenderView_share_profile, (dialog, which) -> {
              new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                  SignalDatabase.recipients().setProfileSharing(recipient.getId(), true);
                  if (threadId != -1) SignalDatabase.threads().setHasSentSilently(threadId, true);
                  return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                  listener.onActionTaken();
                }
              }.executeOnExecutor(SignalExecutors.BOUNDED);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
  }

  public interface Listener {
    void onActionTaken();
  }
}