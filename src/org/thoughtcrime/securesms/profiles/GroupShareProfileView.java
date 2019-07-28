package org.thoughtcrime.securesms.profiles;

import com.unfacd.android.R;
import com.unfacd.android.utils.UfsrvUserUtils;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ViewUtil;

public class GroupShareProfileView extends FrameLayout {

  private           View      container;
  private @Nullable Recipient recipient;

  private           Boolean   isPrivateGroupForTwo = false;//

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

//    // support for dont show again checkbox
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

    this.container = ViewUtil.findById(this, R.id.container);
    this.container.setOnClickListener(view -> {
      if (recipient != null) {
        // support for dont show again checkbox
        View dontShowAgainLayout  = inflate(getContext(), R.layout.dialog_checkbox, null);
        CheckBox dontShowAgain    = dontShowAgainLayout.findViewById(R.id.skip);
        dontShowAgain.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
          //

          @Override
          public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (isChecked) TextSecurePreferences.setProfileShareReminder(getContext(), true);
          }
        });

        //
        int messageId;
        if (!isPrivateGroupForTwo) messageId = R.string.preferences_dialog_message_group_share;
        else                       messageId = R.string.preferences_dialog_message_share_private_group_for_two;

        new AlertDialog.Builder(getContext())
                .setView(dontShowAgainLayout) //
                .setIconAttribute(R.attr.dialog_info_icon)
                .setTitle(R.string.GroupShareProfileView_share_your_profile_name_and_photo_with_this_group)
                .setMessage(messageId) //
                .setPositiveButton(R.string.preferences_dialog_share_profile_key, (dialog, which) -> {
//                  DatabaseFactory.getRecipientDatabase(getContext()).setProfileSharing(recipient, true);//-
                  shareProfileKey(getContext());//
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

        UfsrvUserUtils.UfsrvShareProfileWithRecipient(context, recipient, false);
        return null;
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  public void setRecipient(@NonNull Recipient recipient) {
    this.recipient = recipient;
    this.isPrivateGroupForTwo = DatabaseFactory.getGroupDatabase(getContext()).isPrivateGroupForTwo(recipient.getAddress().serialize());//
  }
}