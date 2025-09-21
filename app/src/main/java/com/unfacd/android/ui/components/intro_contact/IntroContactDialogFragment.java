package com.unfacd.android.ui.components.intro_contact;

import android.Manifest.permission;
import android.animation.Animator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dd.CircularProgressButton;
import com.google.android.material.snackbar.Snackbar;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvcmd.events.AppEventAvatarDownloaded;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.StreamUtil;
import org.thoughtcrime.securesms.avatar.picker.AvatarPickerFragment;
import org.thoughtcrime.securesms.components.InputAwareLayout;
import org.thoughtcrime.securesms.components.LabeledEditText;
import org.thoughtcrime.securesms.components.emoji.EmojiEventListener;
import org.thoughtcrime.securesms.components.emoji.EmojiToggle;
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.ui.creategroup.details.AddGroupDetailsFragmentDirections;
import org.thoughtcrime.securesms.keyboard.KeyboardPage;
import org.thoughtcrime.securesms.keyboard.KeyboardPagerViewModel;
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardPageFragment;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mediasend.AvatarSelectionActivity;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.navigation.SafeNavigation;
import org.thoughtcrime.securesms.util.views.LearnMoreTextView;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;
import org.thoughtcrime.securesms.util.views.Stub;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.util.OptionalUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;

import static android.app.Activity.RESULT_OK;

public class IntroContactDialogFragment extends DialogFragment implements
        EmojiEventListener,
        EmojiKeyboardPageFragment.Callback,
        InputAwareLayout.OnKeyboardShownListener
{
  private Context                 context;
  private IntroContactViewModel   viewModel;
  private InputAwareLayout        container;
  private ImageView               avatar;
  private View                    avatarPlaceholder;
  private CircularProgressButton  finishButton;
  private LabeledEditText         handle;
  private LabeledEditText         msg;
  private EmojiToggle             emojiToggle;//check EditAboutFragment for an alternative implementation that picks up emojies only
  private Stub<MediaKeyboard>     emojiDrawer;
  private MediaKeyboard           mediaKeyboard;
  private View                    reveal;
  private AlertDialog             avatarProgress;

  private IntroContactDialogDoneListener closeListener = null;
  private Optional<Pair<Long, IntroContactDescriptor>> descriptorProvided = Optional.empty();

  private static final String TAG = org.signal.core.util.logging.Log.tag(IntroContactDialogFragment.class);

  private static final short  REQUEST_CODE_SELECT_AVATAR = 31726;

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    if (getArguments() != null) {
      if (getArguments().getParcelable("descriptor") != null) {
        descriptorProvided = Optional.of(new Pair(getArguments().getLong("msg_id", 0), getArguments().getParcelable("descriptor")));
      }

//      IntroContactRepository repository = new IntroContactRepository(getActivity());
//      IntroContactViewModel.Factory factory    = new IntroContactViewModel.Factory(1repository);

//      viewModel = ViewModelProviders.of(this, factory).get(IntroContactViewModel.class);
//      viewModel.getEvents().observe(this, event -> handleEvent(container, event));

      if (getArguments().getBoolean("notAlertDialog")) {
        return super.onCreateDialog(savedInstanceState);
      }
    }

    return super.onCreateDialog(savedInstanceState);

  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.initiate_contact_dialog_fragment_view, container, false);

  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    initializeResources(view);

    getParentFragmentManager().setFragmentResultListener(AvatarPickerFragment.REQUEST_KEY_SELECT_AVATAR, getViewLifecycleOwner(), (key, bundle) -> {
      Media media = bundle.getParcelable(AvatarPickerFragment.SELECT_AVATAR_MEDIA);
      handleMediaFromResult(media);
    });
  }

  @Override
  public void onResume() {
    super.onResume();
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Log.d(TAG, "onCreate");

    boolean setFullScreen = false;
    if (getArguments() != null) {
      setFullScreen = getArguments().getBoolean("fullScreen");
    }

    if (setFullScreen)
      setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    this.context = context;
  }

  @Override
  public void onDetach() {
    super.onDetach();
    context = null;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
  }

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

  @RequiresPermission(permission.CAMERA)
  private void initializeResources(@NonNull View view) {
    TextView          skipButton       = view.findViewById(R.id.skip_button);
    TextView          laterButton      = view.findViewById(R.id.later_button);
    LearnMoreTextView introTextView    = view.findViewById(R.id.intro_text);

    this.avatar             = view.findViewById(R.id.avatar);
    this.avatarPlaceholder  = view.findViewById(R.id.avatar_placeholder);
    this.handle             = view.findViewById(R.id.handle);
    this.msg                = view.findViewById(R.id.msg);
    this.emojiToggle        = view.findViewById(R.id.emoji_toggle);
    this.emojiDrawer        = new Stub<>(view.findViewById(R.id.emoji_drawer_stub));
    this.container          = view.findViewById(R.id.container);
    this.finishButton       = view.findViewById(R.id.finish_button);
    this.reveal             = view.findViewById(R.id.reveal);

    initializeViewModel();

    if (!descriptorProvided.isPresent()) {
      Optional<Pair<Long, IntroContactDescriptor>> descriptorLast = SignalDatabase.unfacdIntroContacts().getLastUnSentIntroContact();

      this.avatar.setOnClickListener(v -> onAvatarClicked());

      this.finishButton.setOnClickListener(v -> {
        this.finishButton.setIndeterminateProgressMode(true);
        this.finishButton.setProgress(50);
        viewModel.sendIntroRequest(descriptorLast, handle.getText().toString(), msg.getText().toString(), viewModel.canRemoveAvatar()? viewModel.getAvatar().getValue().getAvatar() : null);
        //dialog dismiss happens on animation end callback
      });

      skipButton.setOnClickListener(v-> {
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        if (closeListener != null) {
          closeListener.onIntroContactDialogDone("cancelled");
        }
        dismiss();
      });

      if (descriptorLast.isPresent()) {
        presentHandle(descriptorLast.get().second());
        if (!TextUtils.isEmpty(descriptorLast.get().second().getMessage()))  msg.setText(descriptorLast.get().second().getMessage());
        if (descriptorLast.get().second().getAvatarBlob() != null) {
          viewModel.loadAvatarBytes(descriptorLast.get().second().getAvatarBlob());
        }
      }

      ViewUtil.focusAndShowKeyboard(handle.getInput());
      handle.getInput().selectAll();
    } else {
      //external incoming request mode
      introTextView.setText(R.string.InitiateContactDialog_unfacd_title_msg_received);
      laterButton.setVisibility(View.VISIBLE);
      laterButton.setOnClickListener(v -> {
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);

        if (closeListener != null) {
          closeListener.onIntroContactDialogDone("later");
        }
        dismiss();
      });

      presentHandle(descriptorProvided.get().second());
      if (!TextUtils.isEmpty(descriptorProvided.get().second().getMessage()))  msg.setText(descriptorProvided.get().second().getMessage());
      viewModel.loadAvatar(descriptorProvided.get().second().getAddressable().toString());

      avatar.setEnabled(false);
      handle.getInput().setInputType(0);
      msg.getInput().setInputType(0);
      emojiToggle.setVisibility(View.INVISIBLE);
      view.findViewById(R.id.camera_icon).setVisibility(View.INVISIBLE);
      view.findViewById(R.id.description_text).setVisibility(View.INVISIBLE);

      this.finishButton.setText(R.string.IntroContactNotificationBuilder_intro_contact_accept);
      this.finishButton.setOnClickListener(v -> {
        this.finishButton.setIndeterminateProgressMode(true);
        this.finishButton.setProgress(50);
        viewModel.setIntroContactStatus(descriptorProvided.get(), 1);
        //dialog dismiss happens on animation end callback
      });

      skipButton.setText(R.string.IntroContactNotificationBuilder_intro_contact_ignore);
      skipButton.setOnClickListener(v -> {
        ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
        viewModel.setIntroContactStatus(descriptorProvided.get(), 0);
        dismiss();
      });
    }

    if (SignalStore.settings().isPreferSystemEmoji()) {
      emojiToggle.setVisibility(View.GONE);
    } else {
      emojiToggle.setOnClickListener(this::onEmojiToggleClicked);
    }

  }

  private void initializeViewModel() {
    viewModel = ViewModelProviders.of(this, new IntroContactViewModel.Factory(new IntroContactRepository(getActivity()))).get(IntroContactViewModel.class);
    viewModel.getEvents().observe(this, event -> handleEvent(container, event));

    viewModel.getAvatar().observe(getViewLifecycleOwner(), this::presentAvatar);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == REQUEST_CODE_SELECT_AVATAR && resultCode == RESULT_OK) {
      if (data != null && data.getBooleanExtra("delete", false)) {
        viewModel.onAvatarSelected(requireContext(), null);
        return;
      }

      Media result = data.getParcelableExtra(AvatarSelectionActivity.EXTRA_MEDIA);

      viewModel.onAvatarSelected(requireContext(), result);
    }
  }

  private void handleMediaFromResult(@NonNull Media media) {
    SimpleTask.run(() -> {
       try {
         InputStream stream = BlobProvider.getInstance().getStream(requireContext(), media.getUri());

         return StreamUtil.readFully(stream);
       } catch (IOException ioException) {
         Log.w(TAG, ioException);
         return null;
       }
     },
     (avatarBytes) -> {
       if (avatarBytes != null) {
         viewModel.setAvatarMedia(media);
         viewModel.loadAvatarBytes(avatarBytes);
         GlideApp.with(IntroContactDialogFragment.this)
                 .load(avatarBytes)
                 .skipMemoryCache(true)
                 .diskCacheStrategy(DiskCacheStrategy.NONE)
                 .circleCrop()
                 .into(avatar);
       } else {
         Toast.makeText(requireActivity(), R.string.CreateProfileActivity_error_setting_profile_photo, Toast.LENGTH_LONG).show();
       }
     });
  }

  void onAvatarClicked()
  {
    /*this.avatar.setOnClickListener(v -> Permissions.with(getActivity())
            .request(permission.CAMERA, permission.WRITE_EXTERNAL_STORAGE)
            .ifNecessary()
            .onAllGranted(this::startAvatarSelection)
            .onAnyDenied(() -> Toast.makeText(getActivity(), R.string.InitiateContactDialog_unfacd_unable_to_include_avatar_without_the_camera_permission, Toast.LENGTH_LONG).show())
            .execute());*/
//    this.avatar.setOnClickListener(v -> startAvatarSelection());
    avatar.setOnClickListener(v -> showAvatarPicker());

  }

  private void presentAvatar(@NonNull IntroContactViewModel.AvatarState avatarState) {
    if (avatarState.getAvatar() == null) {
      avatar.setImageDrawable(null);
      avatarPlaceholder.setVisibility(View.VISIBLE);
    } else {
      avatarPlaceholder.setVisibility(View.GONE);
      Glide.with(this)
              .load(avatarState.getAvatar())
              .circleCrop()
              .into(avatar);
    }

    if (avatarProgress == null && avatarState.getLoadingState() == IntroContactViewModel.LoadingState.LOADING) {
      avatarProgress = SimpleProgressDialog.show(requireContext());
    } else if (avatarProgress != null && avatarState.getLoadingState() == IntroContactViewModel.LoadingState.LOADED) {
      avatarProgress.dismiss();
    }
  }

  public IntroContactDialogFragment setCloseListener(IntroContactDialogDoneListener closeListener)
  {
    this.closeListener = closeListener;

    return this;
  }

  /*private void startAvatarSelection() {
    AvatarSelectionBottomSheetDialogFragment.create(viewModel.canRemoveAvatar(),
                                                    true,
                                                    REQUEST_CODE_SELECT_AVATAR,
                                                    false).show(getChildFragmentManager(), "BOTTOM");
  }*/

  private void showAvatarPicker() {
    Media media = viewModel.getAvatarMedia();

    SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), AddGroupDetailsFragmentDirections.actionAddGroupDetailsFragmentToAvatarPicker(null, null)
                                                                                                              .setIsNewGroup(false));
  }

  private void presentHandle(IntroContactDescriptor descriptor) {
    if (descriptor.isHandleProvided()) {
      SpannableString handleText;
      if (!TextUtils.isEmpty(descriptor.getAddressable().toString())) {
        int spanStart = descriptor.getHandle().length() + 1;
        handleText = new SpannableString(String.format("%s %s", descriptor.getHandle(), descriptor.getAddressable()));
        handleText.setSpan(new ForegroundColorSpan(Color.GRAY), spanStart, handleText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else {
        handleText = new SpannableString(String.format("%s", descriptor.getHandle()));
      }
      handle.getInput().setText(handleText);
    } else {
      handle.setText(descriptor.getAddressable().toString());
    }
  }

  private void hideEmoji()
  {
    if (!container.isInputOpen()) {
      //nothing
    } else{
        container.hideCurrentInput(msg.getInput());
      }
//    if (container.isKeyboardOpen()) {
//      container.hideSoftkey(msg.getInput(), null);
//    }
  }

  private void onEmojiToggleClicked(View v) {
    if (!emojiDrawer.resolved()) {
      initializeMediaKeyboardProviders();
      setMediaKeyboard(emojiDrawer.get());
    }

    if (container.getCurrentInput() == emojiDrawer.get()) {
      container.showSoftkey(msg.getInput());
    } else {
      container.show(msg.getInput(), emojiDrawer.get());
    }

   /* if (!emojiDrawer.resolved()) {
      KeyboardPagerViewModel keyboardPagerViewModel = ViewModelProviders.of(this).get(KeyboardPagerViewModel.class);
      keyboardPagerViewModel.setOnlyPage(KeyboardPage.EMOJI);

      emojiToggle.attach(emojiDrawer.get());
    }*/

    /*if (hud.getCurrentInput() == emojiDrawer.get()) {
      hud.showSoftkey(composeText);
    } else {
      hud.hideSoftkey(composeText, () -> hud.post(() -> hud.show(composeText, emojiDrawer.get())));
    }*/
  }

  private void setMediaKeyboard(@NonNull MediaKeyboard mediaKeyboard) {
    this.emojiToggle.attach(mediaKeyboard);
  }

  private void initializeMediaKeyboardProviders() {
    KeyboardPagerViewModel keyboardPagerViewModel = ViewModelProviders.of(IntroContactDialogFragment.this).get(KeyboardPagerViewModel.class);

    switch (TextSecurePreferences.getMediaKeyboardMode(getContext())) {
      case EMOJI:
        keyboardPagerViewModel.switchToPage(KeyboardPage.EMOJI);
        break;
      case STICKER:
        keyboardPagerViewModel.switchToPage(KeyboardPage.STICKER);
        break;
      case GIF:
        keyboardPagerViewModel.switchToPage(KeyboardPage.GIF);
        break;
    }
  }

 /* private void onEmojiToggleClicked(View v) {
    if (!emojiDrawer.resolved()) {
      KeyboardPagerViewModel keyboardPagerViewModel = ViewModelProviders.of(this).get(KeyboardPagerViewModel.class);
      keyboardPagerViewModel.setOnlyPage(KeyboardPage.EMOJI);

      emojiToggle.attach(emojiDrawer.get());
    }

//    if (hud.getCurrentInput() == emojiDrawer.get()) {
//      hud.showSoftkey(composeText);
//    } else {
//      hud.hideSoftkey(composeText, () -> hud.post(() -> hud.show(composeText, emojiDrawer.get())));
//    }
  }*/

  @Override
  public void openEmojiSearch() {
    if (emojiDrawer.resolved()) {
      emojiDrawer.get().onOpenEmojiSearch();
    }
  }

 /* @Override
  public void closeEmojiSearch() {
    if (emojiDrawer.resolved()) {
      emojiDrawer.get().onCloseEmojiSearch();
    }
  }*/

  @Override
  public void onKeyboardShown() {
    container.onKeyboardShown();
  }

  private void initialiseEmojiKeyboard(View view)
  {
    KeyboardPagerViewModel keyboardPagerViewModel = ViewModelProviders.of(getActivity()).get(KeyboardPagerViewModel.class);
    keyboardPagerViewModel.setOnlyPage(KeyboardPage.EMOJI);

    emojiToggle.attach(mediaKeyboard);

    this.emojiToggle.setOnClickListener(v -> {
      if (container.getCurrentInput() == mediaKeyboard) {
        container.showSoftkey(msg.getInput());
      } else {
        container.show(msg.getInput(), mediaKeyboard);
      }
    });

    this.container.addOnKeyboardShownListener(() -> emojiToggle.setToMedia());
    this.msg.setOnClickListener(v -> container.showSoftkey(msg.getInput()));
  }

/*  private void initializeProfileAvatar(String avatarId) {
    if (TextUtils.isEmpty(avatarId))  return;

    LocallyAddressableUfsrvUid addressableUfsrvUid = LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, avatarId);
    org.signal.core.util.logging.Log.d(TAG, String.format("initializeProfileAvatar: Loading avatar file: '%s'", addressableUfsrvUid));

    if (AvatarHelper.avatarExists(context, addressableUfsrvUid)) {
      new AsyncTask<Void, Void, byte[]>() {
        @Override
        protected byte[] doInBackground(Void... params) {
          try {
            return StreamUtil.readFully(AvatarHelper.getAvatar(context, addressableUfsrvUid));
          } catch (IOException e) {
            org.signal.core.util.logging.Log.w(TAG, e);
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
  }*/

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

        if (closeListener != null) {
          closeListener.onIntroContactDialogDone("cancelled");
        }
        dismiss();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}
      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  private void handleFinishedLegacy() {
    finishButton.setProgress(0);
    dismiss();
  }

  private void handleEvent(@NonNull View view, @NonNull IntroContactViewModel.Event event) {
    final Optional<String> displayName;

    displayName = OptionalUtil.or(Optional.of(event.getNumber()));

    final @StringRes int messageResId;
    switch (event.getEventType()) {
      case INTRO_ACTION_WITH_SUCCESS:
        messageResId = R.string.BlockedUsersActivity__s_has_been_blocked;
        break;
      case INTRO_SENT_WITH_ERROR:
        messageResId = R.string.BlockedUsersActivity__failed_to_block_s;
        break;
      case INTRO_SENT_WITH_SUCCESS:
        messageResId = R.string.BlockedUsersActivity__s_has_been_unblocked;
        break;
      default:
        throw new IllegalArgumentException("Unsupported event type " + event);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
    else handleFinishedLegacy();
    Snackbar.make(view, getString(messageResId, displayName.get()), Snackbar.LENGTH_SHORT).show();
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventAvatarDownloaded event)
  {
    org.signal.core.util.logging.Log.d(TAG, String.format("AppEventAvatarDownloaded: recipient: '%s', avatarId:'%s", event.getRecipient().requireUfsrvUid(), event.getAttachmentId()));

    Context context = getActivity();
    if (context == null || ((FragmentActivity) context).isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && ((FragmentActivity) context).isDestroyed())) {
      org.signal.core.util.logging.Log.w(TAG, String.format("AppEventAvatarDownloaded: Won't initialise intro avatar: activity appears not running: recipient: '%s', avatarId:'%s", event.getRecipient().requireAddress(), event.getAttachmentId()));
    }

    viewModel.loadAvatar(event.getAttachmentId());
//    initializeProfileAvatar(event.getAttachmentId());
  }

  public interface IntroContactDialogDoneListener
  {
    void onIntroContactDialogDone(String inputText);
  }


}