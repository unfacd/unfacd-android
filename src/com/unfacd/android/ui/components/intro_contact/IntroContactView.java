package com.unfacd.android.ui.components.intro_contact;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dd.CircularProgressButton;
import com.kongzue.dialog.v3.MessageDialog;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.jobs.UnfacdIntroContactJob;
import com.unfacd.android.ufsrvcmd.events.AppEventAvatarDownloaded;
import com.unfacd.android.ui.components.MessageDialogCloseListener;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import androidx.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.avatar.AvatarSelection;
import org.thoughtcrime.securesms.components.InputAwareLayout;
import org.thoughtcrime.securesms.components.LabeledEditText;
import org.thoughtcrime.securesms.components.emoji.EmojiKeyboardProvider;
import org.thoughtcrime.securesms.components.emoji.EmojiToggle;
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.profiles.ProfileMediaConstraints;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.File;
import java.io.IOException;

//this has dependency on the MessageDialog parent object for the purpose of dismissing the containing dialog
@SuppressLint("StaticFieldLeak")
public class IntroContactView extends LinearLayout implements ConversationListActivity.ActivityResultChildViewListener
{
  private Context                 context;
  private InputAwareLayout        container;
  private ImageView               avatar;
  private CircularProgressButton  finishButton;
  private LabeledEditText         handle;
  private LabeledEditText         msg;
  private EmojiToggle             emojiToggle;
  private MediaKeyboard           mediaKeyboard;
  private View                    reveal;

  private MessageDialog           messageDialog;
  private MessageDialogCloseListener closeListener = null;
  private Recipient               recipientViewed = null;
  private Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided = Optional.absent();

  private byte[]                  avatarBytes;
  private File                    captureFile;

  private static final String TAG = IntroContactView.class.getSimpleName();

  public IntroContactView (Context context) {
    this(context, null);
  }

  public IntroContactView (Context context, AttributeSet attrs) {
    super(context, attrs);
    this.context = context;
    initialize(context);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public IntroContactView (Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize(context);
  }


  public IntroContactView setContainerContext (MessageDialog messageDialog)
  {
    this.messageDialog = messageDialog;

    return this;
  }

  public IntroContactView setRecipientViewed (Recipient recipientViewed)
  {
    this.recipientViewed = recipientViewed;

    return this;
  }

  public IntroContactView setDescriptor (Optional<Pair<Long, IntroContactDescriptor>> descriptor)
  {
    this.descriptorProvided = descriptor;

    return this;
  }

  public IntroContactView setCloseListener (MessageDialogCloseListener closeListener)
  {
    this.closeListener = closeListener;

    return this;
  }

  public void finalise ()
  {
    if (this.messageDialog == null) {
      throw new IllegalStateException ("ContainerContext not set");
    }

    messageDialog.setOnDismissListener(() -> handleCloseListener(0));

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);

    initializeResources();
    initializeEmojiInput();
//    initializeProfileName(getIntent().getBooleanExtra(EXCLUDE_SYSTEM, false));

  }

  private void initialize(Context context) {
    LayoutInflater.from(context).inflate(R.layout.initiate_contact_view, this, true);

  }

  private void initializeResources() {
    TextView skipButton       = ViewUtil.findById(this, R.id.skip_button);

    this.avatar       = ViewUtil.findById(this, R.id.avatar);
    this.handle       = ViewUtil.findById(this, R.id.handle);
    this.msg          = ViewUtil.findById(this, R.id.msg);
    this.emojiToggle  = ViewUtil.findById(this, R.id.emoji_toggle);
    this.mediaKeyboard= ViewUtil.findById(this, R.id.emoji_drawer);
    this.container    = ViewUtil.findById(this, R.id.container);
    this.finishButton = ViewUtil.findById(this, R.id.finish_button);
    this.reveal       = ViewUtil.findById(this, R.id.reveal);

    if (!descriptorProvided.isPresent()) {
      Optional<Pair<Long, IntroContactDescriptor>> descriptorLast = DatabaseFactory.getUnfacdIntroContactsDatabase(context).getLastUnSentIntroContact();

      this.avatar.setOnClickListener(view -> Permissions.with((Activity) context)
              .request(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
              .ifNecessary()
              .onAnyResult(this::startAvatarSelection)
              .execute());

      this.finishButton.setOnClickListener(view -> {
        this.finishButton.setIndeterminateProgressMode(true);
        this.finishButton.setProgress(50);
        new SendUnfacdIntroContact(descriptorLast, handle.getText().toString(), msg.getText().toString(), avatarBytes).execute();
        //dialog dismiss happens on animation end callback
      });

      skipButton.setOnClickListener(view -> {
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        messageDialog.doDismiss();
      });

      if (descriptorLast.isPresent()) {
        handle.setText(descriptorLast.get().second.getAddress().serialize());
        if (!TextUtils.isEmpty(descriptorLast.get().second.getMessage()))  msg.setText(descriptorLast.get().second.getMessage());
        if (descriptorLast.get().second.getAvatarBlob() != null) {
          avatarBytes = descriptorLast.get().second.getAvatarBlob();
          GlideApp.with(context)
                  .load(avatarBytes)
                  .skipMemoryCache(true)
                  .diskCacheStrategy(DiskCacheStrategy.NONE)
                  .circleCrop()
                  .into(avatar);
        }
      }
    } else {
      //external request mode
      handle.setText(descriptorProvided.get().second.getAddress().serialize());
      if (!TextUtils.isEmpty(descriptorProvided.get().second.getMessage()))  msg.setText(descriptorProvided.get().second.getMessage());
      initializeProfileAvatar (descriptorProvided.get().second.getAvatarId());

      avatar.setEnabled(false);
      handle.getInput().setInputType(0);
      msg.getInput().setInputType(0);
      emojiToggle.setVisibility(INVISIBLE);
      ViewUtil.findById(this, R.id.camera_icon).setVisibility(INVISIBLE);
      ViewUtil.findById(this, R.id.description_text).setVisibility(INVISIBLE);

      this.finishButton.setText(R.string.IntroContactNotificationBuilder_intro_contact_accept);
      this.finishButton.setOnClickListener(view -> {
        this.finishButton.setIndeterminateProgressMode(true);
        this.finishButton.setProgress(50);
        new RetrieveUnfacdIntroContact(descriptorProvided, 1).execute();
        //dialog dismiss happens on animation end callback
      });

      skipButton.setText(R.string.IntroContactNotificationBuilder_intro_contact_ignore);
      skipButton.setOnClickListener(view -> {
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        new RetrieveUnfacdIntroContact(descriptorProvided, 0).execute();
//        messageDialog.doDismiss();
      });
    }
  }

  private void initializeEmojiInput() {
    this.emojiToggle.attach(mediaKeyboard);

    this.emojiToggle.setOnClickListener(v -> {
      if (container.getCurrentInput() == mediaKeyboard) {
        container.showSoftkey(msg.getInput());
      } else {
        container.show(msg.getInput(), mediaKeyboard);
      }
    });

    this.mediaKeyboard.setProviders(0, new EmojiKeyboardProvider(context, new EmojiKeyboardProvider.EmojiEventListener() {
      @Override
      public void onKeyEvent(KeyEvent keyEvent) {
        msg.dispatchKeyEvent(keyEvent);
      }

      @Override
      public void onEmojiSelected(String emoji) {
        final int start = msg.getInput().getSelectionStart();
        final int end   = msg.getInput().getSelectionEnd();

        msg.getText().replace(Math.min(start, end), Math.max(start, end), emoji);
        msg.getInput().setSelection(start + emoji.length());
      }
    }));

    this.container.addOnKeyboardShownListener(() -> emojiToggle.setToMedia());
    this.msg.setOnClickListener(v -> container.showSoftkey(msg.getInput()));
  }

  private void initializeProfileAvatar (String avatarId) {
    if (TextUtils.isEmpty(avatarId))  return;

    Address avatarIdAdress = Address.fromSerialized(avatarId);
    Log.d(TAG, String.format("initializeProfileAvatar: Loading avatar file: '%s'", avatarIdAdress));

    if (AvatarHelper.getAvatarFile(context, avatarIdAdress).exists() && AvatarHelper.getAvatarFile(context, avatarIdAdress).length() > 0) {
      new AsyncTask<Void, Void, byte[]>() {
        @Override
        protected byte[] doInBackground(Void... params) {
          try {
            return Util.readFully(AvatarHelper.getInputStreamFor(context, avatarIdAdress));
          } catch (IOException e) {
            Log.w(TAG, e);
            return null;
          }
        }

        @Override
        protected void onPostExecute(byte[] result) {
          if (result != null) {
            avatarBytes = result;
            GlideApp.with(context)
                    .load(result)
                    .circleCrop()
                    .into(avatar);
          }
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
  }

  private void startAvatarSelection() {
    captureFile = AvatarSelection.startAvatarSelection((Activity)context, avatarBytes != null, true);
  }

  private void handleFinishedLegacy() {
    finishButton.setProgress(0);
    messageDialog.doDismiss();
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private void handleFinishedLollipop() {
    int[] finishButtonLocation = new int[2];
    int[] revealLocation       = new int[2];

    finishButton.getLocationInWindow(finishButtonLocation);
    reveal.getLocationInWindow(revealLocation);

    int finishX = finishButtonLocation[0] - revealLocation[0];
    int finishY = finishButtonLocation[1] - revealLocation[1];

    finishX += finishButton.getWidth() / 2;
    finishY += finishButton.getHeight() / 2;

    Animator animation = ViewAnimationUtils.createCircularReveal(reveal, finishX, finishY, 0f, (float) Math.max(reveal.getWidth(), reveal.getHeight()));
    animation.setDuration(500);
    animation.addListener(new Animator.AnimatorListener() {
      @Override
      public void onAnimationStart(Animator animation) {}

      @Override
      public void onAnimationEnd(Animator animation) {
        finishButton.setProgress(0);
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        messageDialog.doDismiss();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}
      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  private void handleCloseListener (int button)
  {
    if (this.closeListener != null) {
      closeListener.onMessageDialogClose(button);
    }
  }

  @Override
  public void onActivityResultForListener (int requestCode, int resultCode, Intent data)
  {
    switch (requestCode) {
      case AvatarSelection.REQUEST_CODE_AVATAR:
        if (resultCode == Activity.RESULT_OK) {
          Uri outputFile = Uri.fromFile(new File(context.getCacheDir(), "cropped"));
          Uri inputFile  = (data != null ? data.getData() : null);

          if (inputFile == null && captureFile != null) {
            inputFile = Uri.fromFile(captureFile);
          }

          if (data != null && data.getBooleanExtra("delete", false)) {
            avatarBytes = null;
            avatar.setImageDrawable(new ResourceContactPhoto(R.drawable.ic_camera_alt_white_24dp).asDrawable(context, getResources().getColor(R.color.grey_400)));
          } else {
            AvatarSelection.circularCropImage((Activity)context, inputFile, outputFile, R.string.CropImageActivity_profile_avatar);
          }
        }

        break;
      case AvatarSelection.REQUEST_CODE_CROP_IMAGE:
        if (resultCode == Activity.RESULT_OK) {
          new AsyncTask<Void, Void, byte[]>() {
            @Override
            protected byte[] doInBackground(Void... params) {
              try {
                BitmapUtil.ScaleResult result = BitmapUtil.createScaledBytes(context, AvatarSelection.getResultUri(data), new ProfileMediaConstraints());
                return result.getBitmap();
              } catch (BitmapDecodingException e) {
                Log.w(TAG, e);
                return null;
              }
            }

            @Override
            protected void onPostExecute(byte[] result) {
              if (result != null) {
                avatarBytes = result;
                GlideApp.with(context)
                        .load(avatarBytes)
                        .skipMemoryCache(true)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .circleCrop()
                        .into(avatar);
              } else {
                Toast.makeText(context, R.string.CreateProfileActivity_error_setting_profile_photo, Toast.LENGTH_LONG).show();
              }
            }
          }.execute();
        }
        break;
    }
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventAvatarDownloaded event)
  {
    Log.d(TAG, String.format("AppEventAvatarDownloaded: recipient: '%s', avatarId:'%s", event.getRecipient().getAddress(), event.getAttachmentId()));

    if (context == null || ((Activity) context).isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && ((Activity) context).isDestroyed())) {
      Log.w(TAG, String.format("AppEventAvatarDownloaded: Won't initialise intro avatar: activity appears not running: recipient: '%s', avatarId:'%s", event.getRecipient().getAddress(), event.getAttachmentId()));
    }

    initializeProfileAvatar (event.getAttachmentId());
  }

  private class
  SendUnfacdIntroContact extends AsyncTask<Void,Void,Integer> {
    final String handle;
    final String message;
    final byte[]avatar;
          long introId = -1;
          Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided;

    public SendUnfacdIntroContact (Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided, final String handle, final String message, final byte[] avatar)
    {
      this.handle   = handle;
      this.message  = message;
      this.avatar   = avatar;
      this.descriptorProvided = descriptorProvided;
    }

    @Override
    protected void onPreExecute () {
    }

    @Override
    protected Integer doInBackground(Void... aVoid) {
      IntroContactDescriptor descriptorNew = new IntroContactDescriptor(Address.fromSerialized(handle), message, null, IntroContactDescriptor.IntroDirection.OUTGOING, System.currentTimeMillis());
      this.introId = DatabaseFactory.getUnfacdIntroContactsDatabase(context).insertIntroContact(descriptorNew);

      if (descriptorProvided.isPresent()) {
        //todo do we blow off old record?
//        DatabaseFactory.getUnfacdIntroContactsDatabase(context).deleteContactIntro(descriptorProvided.get().first);
      }

      if (avatar != null) {
        DatabaseFactory.getUnfacdIntroContactsDatabase(context).setAvatarBlob(introId, avatar);
      }

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new UnfacdIntroContactJob(introId));
      return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
        super.onPostExecute(result);
        if (result.intValue() == 0) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
          else                                                       handleFinishedLegacy();
        } else        {
//          Toast.makeText(CreateProfileActivity.this, R.string.CreateProfileActivity_problem_setting_profile, Toast.LENGTH_LONG).show();
        }
    }
  }

  private class
  RetrieveUnfacdIntroContact extends AsyncTask<Void,Void,Integer> {
    final int action;
    final Optional<Pair<Long, IntroContactDescriptor>> descriptor;

    public RetrieveUnfacdIntroContact ( Optional<Pair<Long, IntroContactDescriptor>> descriptor, int action) {
      this.descriptor = descriptor;
      this.action     = action;
    }

    @Override
    protected void onPreExecute () {
    }

    @Override
    protected Integer doInBackground(Void... aVoid) {
      Address address = descriptor.get().second.getAddress();
      if (action == 1) {
        Recipient recipient = Recipient.from(context, address, false);
        if (recipient != null) {
//        DatabaseFactory.getRecipientDatabase(context).se
          DatabaseFactory.getUnfacdIntroContactsDatabase(context).setResponseStatus(descriptor.get().first, IntroContactDescriptor.ResponseStatus.ACCEPTED);
        }
      } else {
        DatabaseFactory.getUnfacdIntroContactsDatabase(context).setResponseStatus(descriptor.get().first, IntroContactDescriptor.ResponseStatus.IGNORED);
      }
      return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
      super.onPostExecute(result);
      if (result.intValue() == 0) {
        if (action ==1) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
          else handleFinishedLegacy();
        } else {
          messageDialog.doDismiss();
        }
      } else        {
//          Toast.makeText(CreateProfileActivity.this, R.string.CreateProfileActivity_problem_setting_profile, Toast.LENGTH_LONG).show();
      }
    }
  }

}
