package org.thoughtcrime.securesms.components.reminder;

import android.content.Context;

import com.unfacd.android.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.PlayStoreUtil;

import java.util.List;

/**
 * Shown to admins when there are pending group join requests.
 */
public final class PendingGroupJoinRequestsReminder extends Reminder {

  private PendingGroupJoinRequestsReminder(@Nullable CharSequence title,
                                           @NonNull CharSequence text)
  {
    super(title, text);
  }

  public static Reminder create(@NonNull Context context, int count) {
    String   message  = context.getResources().getQuantityString(R.plurals.PendingGroupJoinRequestsReminder_d_pending_member_requests, count, count);
    Reminder reminder = new PendingGroupJoinRequestsReminder(null, message);

    reminder.addAction(new Action(context.getString(R.string.PendingGroupJoinRequestsReminder_view), R.id.reminder_action_review_join_requests));

    return reminder;
  }

  @Override
  public boolean isDismissable() {
    return true;
  }

  @Override
  public @NonNull Importance getImportance() {
    return Importance.NORMAL;
  }
}