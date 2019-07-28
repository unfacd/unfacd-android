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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.textfield.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import org.thoughtcrime.securesms.logging.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.ui.Utils;
import com.unfacd.android.utils.NicknameAvailabilityReceiver;
import com.unfacd.android.utils.IntentServiceCheckNicknameAvailability;
import com.unfacd.android.utils.UfsrvCommandUtils;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.ProfileCommandDescriptor;
import org.thoughtcrime.securesms.components.emoji.EmojiEditText;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.util.InvalidNumberException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;


import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;


public class NicknamePopup extends AnchoredPopupView
{
  private static final String TAG = NicknamePopup.class.getSimpleName();
  private Context   context;

  private ImageView       avatar;
  private TextInputLayout nicknameLayout;
  private EmojiEditText   nickname;
  private ImageView       mNextButton;

  // intent service code for checking nicknames
  public static final int CHECK_NICKNAME = 10;
  private boolean         isNicknameAvailable=true;
  public NicknameAvailabilityReceiver nicknameAvailabilityReceiver;

  private MasterSecret    masterSecret;
  private Bitmap          avatarBmp;

  //better manage the firing of edit change events
  private Timer           timer  = new Timer();
  private final long      DELAY  = 1000; // in ms
  //

  @NonNull private Optional<UserData> userLoaded = Optional.absent();

  public NicknamePopup(Context context) {
    this.context=context;
    setContentView(LayoutInflater.from(context).inflate(R.layout.uf_toolbar_nickname_popup, null));
    setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
    setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
    setFocusable(true);
    setOutsideTouchable(true);
    setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    //setBackgroundDrawable(new BitmapDrawable(context.getResources(), (Bitmap) null));

    // Disable default animation for circular reveal
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      setAnimationStyle(0);
    }

    initializeResources();
    setupNicknameAvailabilityServiceReceiver();

    new LoadUserInfoAsyncTask(Utils.getHostActivity(context), this).execute(TextSecurePreferences.getUfsrvUserId(context));
  }


  private void initializeResources() {
    avatar            = (ImageView) getContentView().findViewById(R.id.avatar);
//    mNextButton       = (ImageView) getContentView().findViewById(R.id.next_button);
//    //mNextButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_arrow_forward_light));
//    mNextButton.setOnClickListener(mOnNextButtonClickListener);
    nickname          = (EmojiEditText)getContentView().findViewById(R.id.nickname);
    nickname.setOnTouchListener(new View.OnTouchListener()
    {
      @Override
      public boolean onTouch (View v, MotionEvent event)
      {
        final int DRAWABLE_RIGHT  = 2;
        final int DRAWABLE_LEFT   = 0;
        //todo check RTL locale and reverse n left if(event.getX() <= (editComment.getCompoundDrawables()[DRAWABLE_LEFT].getBounds().width()))
        if (event.getX() >= (nickname.getRight() - nickname.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width()-nickname.getTotalPaddingRight()))
        {
          nextStep();
          return true;
        }

        return false;
       }
    });

    //support for floating edit
    nicknameLayout = (TextInputLayout) getContentView().findViewById(R.id.nicknameLayout);
    nicknameLayout.setErrorEnabled(true);
    //

    //
    this.nickname.addTextChangedListener(new TextWatcher() {

      public void afterTextChanged(Editable s) {
        if (nickname.getText().length()==0)
        {
          nicknameLayout.setError("");
          isNicknameAvailable=true;
          return;
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
          @Override
          public void run() {
            Intent intent = new Intent(((Activity)context).getApplicationContext(), IntentServiceCheckNicknameAvailability.class);
            intent.putExtra(IntentServiceCheckNicknameAvailability.NICKNAME_EXTRA, nickname.getText().toString());
            intent.putExtra(IntentServiceCheckNicknameAvailability.PENDING_RESULT_EXTRA, nicknameAvailabilityReceiver);//result carrier object
            context.startService(intent);
          }

        }, DELAY);
        //
      }

      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      public void onTextChanged(CharSequence s, int start, int before, int count) {

        if(timer != null) timer.cancel();
      }
    });
    //

//
//    if (TextUtils.isEmpty(TextSecurePreferences.getUfsrvNickname(context)))
//    nickname.setHint("Enter your desired nickname");
//    else nickname.setText(TextSecurePreferences.getUfsrvNickname(context));

    FallbackContactPhoto fallbackContactPhoto = new ResourceContactPhoto(R.drawable.ic_group_white_24dp, R.drawable.ic_group_large);
    avatar.setImageDrawable(fallbackContactPhoto
            .asDrawable(context, ContactColors.UNKNOWN_COLOR.toConversationColor(context)));
    avatar.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
//        Crop.pickImage(Utils.getHostActivity(context));

      }
    });
  }

  public void setupNicknameAvailabilityServiceReceiver() {
    nicknameAvailabilityReceiver = new NicknameAvailabilityReceiver(new Handler());

    nicknameAvailabilityReceiver.setReceiver(new NicknameAvailabilityReceiver.Receiver() {
      @Override
      public void onReceiveResult(int resultCode, Bundle resultData) {
        String nicknameCurrent  = TextSecurePreferences.getUfsrvNickname(context);
        if (resultCode == 0)
        {
          isNicknameAvailable=resultData.getBoolean(IntentServiceCheckNicknameAvailability.RESULT_NICKNAME_EXTRA, false);
          if (isNicknameAvailable)
          {
            nicknameLayout.setError("");
            if (nickname.getText().toString().equals(nicknameCurrent))  {nicknameLayout.setHint("Choose a new nickname"); }
            else nicknameLayout.setHint("Hooray! nickname available");
          }
          else
          {
            if (!nickname.getText().toString().equals(nicknameCurrent))  {nicknameLayout.setHint("");nicknameLayout.setError("Nickname not available...");}
            else  {nicknameLayout.setHint("Choose a new nickname"); nicknameLayout.setError("");}
          }
        }
        else
        {
          nicknameLayout.setHint("");
          nicknameLayout.setError("Availability can't be confimed now");
        }
//        if (resultCode == RESULT_OK) {
//          String resultValue = resultData.getString("resultValue");
//          Toast.makeText(MainActivity.this, resultValue, Toast.LENGTH_SHORT).show();
//        }
      }
    });
  }

// MULTI STEP BLOCK
  private View.OnClickListener mOnNextButtonClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      nextStep();
    }
  };

  private void nextStep() {
    handleUserProfileUpdate ();
    this.dismiss();
  }

  @Override
  public void showOnAnchor(@NonNull View anchor, int vertPos, int horizPos, int x, int y) {
    super.showOnAnchor(anchor, vertPos, horizPos, x, y);
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//      circularReveal(anchor);
//    }
  }

  private void handleUserProfileUpdate () {
    new UpdateBasicUserProfileTask(Utils.getHostActivity(context), masterSecret,  avatarBmp, nickname.getText().toString()).execute();
  }

  private class LoadUserInfoAsyncTask extends ProgressDialogAsyncTask<String,Void,Optional<UserData>>
  {
    private Activity         activity;
    private NicknamePopup     view;

    public LoadUserInfoAsyncTask(Activity activity, NicknamePopup view) {
      super(activity,
              R.string.GroupCreateActivity_loading_group_details,
              R.string.please_wait);
      this.activity = activity;
      this.view=view;
    }

    @Override
    protected Optional<UserData> doInBackground(String... e64Username) {
//      final IdentityDatabase directory            = DatabaseFactory.getIdentityDatabase(activity);
//      final Recipient recipient           = Recipient.from(context, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)), false);
//
//      if (recipient != null) {
//        return Optional.of(new UserData(
//                recipient,
//                BitmapUtil.fromByteArray(directory.getAvatar(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)))),
//                                         directory.getAvatar(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context))),
//                                         recipient.getNickname()));
//      } else {
//        return Optional.absent();
//      }
              return Optional.absent();
    }

    @Override
    protected void onPostExecute(Optional<UserData> user) {
      super.onPostExecute(user);

      view.userLoaded = user;
      if (user.isPresent() && !activity.isFinishing()) {

//        view.nickname.setText(user.get().nickname);
        view.nickname.setText(user.get().nickname);
        if (user.get().avatarBmp != null) {
          view.setAvatar(user.get().avatarBytes, user.get().avatarBmp);
        }
      }
    }

  }

  private <T> void setAvatar(T model, Bitmap bitmap) {
    avatarBmp = bitmap;
    GlideApp.with(context)
            .load(model)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
//            .transform(new RoundedCorners(context, avatar.getWidth() / 2))
            .transform(new RoundedCorners(avatar.getWidth() / 2))
            .into(avatar);
  }

  public static UserProfileActionResult
  updateBasicUserProfile (@NonNull  Context        context,
                          @NonNull  MasterSecret   masterSecret,
                          @Nullable Bitmap         avatar,
                           @Nullable String         name)
                          throws InvalidNumberException
  {
    final byte[]                       avatarBytes         = BitmapUtil.toByteArray(avatar);
    ArrayList<UserProfileActionResult> groupActionResults  = new ArrayList<>();
    UserProfileActionResult            profileActionResultName;

    if (!name.equals(TextSecurePreferences.getUfsrvNickname(context)))
    {
      profileActionResultName = sendUserProfileUpdateName(context, name,
              new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE));

      groupActionResults.add(profileActionResultName);
    }
    else
    {
      groupActionResults.add(new UserProfileActionResult(null, -1));
      //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
    }

//    if (avatar!=null)
//    {
//      if (!Arrays.equals(avatarBytes, groupRecord.getAvatar()))
//      {
//        groupActionResultAvatar = sendGroupUpdateAvatar(context, masterSecret, groupId, avatarBytes, new Long(groupRecord.getFid()),
//                new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.FenceCommand.CommandTypes.AVATAR_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));
//        groupDatabase.updateAvatar(groupId, avatarBytes);
//        groupActionResults.add(groupActionResultAvatar);
//      }
//      else
//      {
//        //todo:user message not necessarily as user may not want to change that it is just present as a loaded field value
//      }
//    }
//    return groupActionResults;
    return groupActionResults.get(0);//return the first for now
  }


private static UserProfileActionResult
sendUserProfileUpdateName(@NonNull  Context      context,
                          @Nullable String       nickname,
                          @NonNull  UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
{
  Log.d(TAG, String.format("sendUserProfileUpdateName: Updating profile '%s'", nickname));

//  UserCommand.Builder userCommandBuilder=UfsrvUserCommandProfileJob
//                                          .buildProfileUpdateForNickname  (context, nickname, commandArgDescriptor);
//
//  ApplicationContext.getInstance(context)
//                    .getJobManager()
//                    .add(new UfsrvUserCommandProfileJob(context, new UfsrvCommand(userCommandBuilder.build(), false).serialiseThis()));

  ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
  profileOperationDescriptor.setProfileType(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.NAME);
  profileOperationDescriptor.setProfileOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileOperationMode.SET);
  UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);
  ApplicationContext.getInstance(context)
          .getJobManager()
          .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), nickname));

  return new UserProfileActionResult(null, -1);
}

  private class
  UpdateBasicUserProfileTask extends UserProfileTask
  {
    private byte[]          groupId;

    public UpdateBasicUserProfileTask(Activity activity,
                                      MasterSecret masterSecret,
                                      Bitmap avatar, String name)
    {
      super(activity, masterSecret, avatar, name);
    }

    @Override
    protected Optional<UserProfileActionResult> doInBackground(Void... aVoid) {
      try {
        return Optional.of(updateBasicUserProfile(context, masterSecret, avatar, name));//GroupManager.updateGroup(activity, masterSecret, avatar, name));
      } catch (InvalidNumberException e) {
        return Optional.absent();
      }
    }

    @Override
    protected void onPostExecute(Optional<UserProfileActionResult> result) {
      if (result.isPresent() && result.get().getThreadId() > -1) {
        if (true){//!activity.isFinishing()) {
//          Intent intent = activity.getIntent();
//          intent.putExtra(GROUP_THREAD_EXTRA, result.get().getThreadId());
//          intent.putExtra(GROUP_RECIPIENT_EXTRA, result.get().getGroupRecipient().getIds());
//          activity.setResult(RESULT_OK, intent);
//          activity.finish();
        }
      } else {
        super.onPostExecute(result);
        Toast.makeText(activity.getApplicationContext(),
                R.string.GroupCreateActivity_contacts_invalid_number, Toast.LENGTH_LONG).show();
      }
    }

  }

  private abstract static class
  UserProfileTask extends AsyncTask<Void,Void,Optional<UserProfileActionResult>>
  {
    protected Activity activity;
    protected MasterSecret        masterSecret;
    protected Bitmap              avatar;
    protected String              name;

    public UserProfileTask(Activity activity,
                           MasterSecret        masterSecret,
                           Bitmap              avatar,
                           String              name)
    {
      this.activity     = activity;
      this.masterSecret = masterSecret;
      this.avatar       = avatar;
      this.name         = name;
    }

    @Override
    protected void onPreExecute() {
//      activity.findViewById(R.id.group_details_layout).setVisibility(View.GONE);
//      activity.findViewById(R.id.creating_group_layout).setVisibility(View.VISIBLE);
//      activity.findViewById(R.id.menu_create_group).setVisibility(View.GONE);
//      final int titleResId = activity.groupToUpdate.isPresent()
//              ? R.string.GroupCreateActivity_updating_group
//              : R.string.GroupCreateActivity_creating_group;
//      activity.creatingText.setText(activity.getString(titleResId, activity.getGroupName()));
    }

    @Override
    protected void onPostExecute(Optional<UserProfileActionResult> groupActionResultOptional) {
//      if (activity.isFinishing()) return;
//      activity.findViewById(R.id.group_details_layout).setVisibility(View.VISIBLE);
//      activity.findViewById(R.id.creating_group_layout).setVisibility(View.GONE);
//      activity.findViewById(R.id.menu_create_group).setVisibility(View.VISIBLE);
    }

  }

  public static class
  UserProfileActionResult {
    private Recipient groupRecipient;
    private long       threadId;

    public UserProfileActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }

  private static class
  UserData {
    Recipient       recipient;
    Bitmap         avatarBmp;
    byte[]         avatarBytes;
    String         nickname;

    public UserData(Recipient recipient, Bitmap avatarBmp, byte[] avatarBytes, String nickname) {//  groupRecord
      this.recipient  = recipient;
      this.avatarBmp   = avatarBmp;
      this.avatarBytes = avatarBytes;
      this.nickname    = nickname;
    }

  }

}