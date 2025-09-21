package org.thoughtcrime.securesms.profiles;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;

import com.unfacd.android.R;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import java.util.Optional;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;

public class GroupShareProfileView extends FrameLayout {

  private           View      container;
  private @Nullable Recipient recipient;

  private           Boolean   isPairedGroup = false;//AA+

  public GroupShareProfileView(@NonNull Context context) {
    super(context);
    initialize();
  }

  public GroupShareProfileView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public GroupShareProfileView(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public GroupShareProfileView(@NonNull Context context, @Nullable AttributeSet attrs, @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    initialize();
  }

  private void initialize() {
    inflate(getContext(), R.layout.profile_group_share_view, this);

//    //AA+ support for dont show again checkbox
//    View dontShowAgainLayout  = inflate(getContext(), R.layout.dialog_checkbox, null);
//    CheckBox dontShowAgain    = dontShowAgainLayout.findViewById(R.id.skip);
//    dontShowAgain.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
//    //
//
//      @Override
//      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//        if (isChecked) TextSecurePreferences.setProfileShareReminder(getContext(), true);
//      }
//    });

    this.container = findViewById(R.id.container);
    this.container.setOnClickListener(view -> {
      if (recipient != null) {
        //AA+ support for dont show again checkbox
        View dontShowAgainLayout  = inflate(getContext(), R.layout.dialog_checkbox, null);
        CheckBox dontShowAgain    = dontShowAgainLayout.findViewById(R.id.skip);
        dontShowAgain.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
          //

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) TextSecurePreferences.setProfileShareReminder(getContext(), true);
          }
        });

        //AA+
        int messageId;
        if (!isPairedGroup) messageId = R.string.preferences_dialog_message_group_share;
        else                       messageId = R.string.preferences_dialog_message_share_private_group_for_two;

        new AlertDialog.Builder(getContext())
                .setView(dontShowAgainLayout) //AA+
                .setIcon(R.drawable.ic_info_outline)
                .setTitle(R.string.GroupShareProfileView_share_your_profile_name_and_photo_with_this_group)
                .setMessage(messageId) //AA+
                .setPositiveButton(R.string.preferences_dialog_share_profile_key, (dialog, which) -> {
//                  DatabaseFactory.getRecipientDatabase(getContext()).setProfileSharing(recipient.getId(), true);//AA-
                  shareProfileKey(getContext());//AA+
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
      }
    });
  }

  private void shareProfileKey (@NonNull Context context) {
    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {

        UfsrvUserUtils.UfsrvShareProfileWithRecipient(context, recipient, true);
        return null;
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  public void setRecipient(@NonNull Recipient recipient) {
    this.recipient = recipient;
    Optional<GroupId> groupIdOptional = recipient.getGroupId();
    this.isPairedGroup =  recipient.isGroup() && groupIdOptional.isPresent() &&  SignalDatabase.groups().isPairedGroup(groupIdOptional.get());//AA+
  }
}