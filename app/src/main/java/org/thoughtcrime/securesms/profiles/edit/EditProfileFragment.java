package org.thoughtcrime.securesms.profiles.edit;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.airbnb.lottie.SimpleColorFilter;
import com.amitshekhar.DebugDB;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.dd.CircularProgressButton;
import com.google.android.material.snackbar.Snackbar;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.R;
import com.unfacd.android.utils.IntentServiceCheckNicknameAvailability;
import com.unfacd.android.utils.NicknameAvailabilityReceiver;

import org.signal.core.util.EditTextUtil;
import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.avatar.Avatars;
import org.thoughtcrime.securesms.avatar.picker.AvatarPickerFragment;
import org.thoughtcrime.securesms.components.InputAwareLayout;
import org.thoughtcrime.securesms.components.LabeledEditText;
import org.thoughtcrime.securesms.components.emoji.EmojiEventListener;
import org.thoughtcrime.securesms.components.emoji.EmojiToggle;
import org.thoughtcrime.securesms.components.emoji.MediaKeyboard;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ParcelableGroupId;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.profiles.manage.EditProfileNameFragment;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.navigation.SafeNavigation;
import org.thoughtcrime.securesms.util.text.AfterTextChanged;
import org.thoughtcrime.securesms.util.views.LearnMoreTextView;
import org.whispersystems.signalservice.api.crypto.ProfileCipher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;

import static org.thoughtcrime.securesms.profiles.edit.EditProfileActivity.EXCLUDE_SYSTEM;
import static org.thoughtcrime.securesms.profiles.edit.EditProfileActivity.GROUP_ID;
import static org.thoughtcrime.securesms.profiles.edit.EditProfileActivity.NEXT_BUTTON_TEXT;
import static org.thoughtcrime.securesms.profiles.edit.EditProfileActivity.NEXT_INTENT;
import static org.thoughtcrime.securesms.profiles.edit.EditProfileActivity.SHOW_TOOLBAR;

public class EditProfileFragment extends LoggingFragment implements EmojiEventListener
{
//                                  implements EditProfileActivity.BackHandler { //AA+

  private static final String TAG                        = Log.tag(EditProfileFragment.class);
  private static final int    MAX_DESCRIPTION_GLYPHS     = 480;
  private static final int    MAX_DESCRIPTION_BYTES      = 8192;

  private Toolbar                toolbar;
  private View                   title;
  private ImageView              avatar;
  private CircularProgressButton finishButton;
  private EditText               givenName;
  private EditText               familyName;
  private View                   reveal;
  private TextView               preview;
  private ImageView              avatarPreviewBackground;
  private ImageView              avatarPreview;

  private Intent nextIntent;
  private File captureFile;

  private EditProfileViewModel viewModel;

  private Controller controller;

  //AA+
  private CircularProgressButton        laterButton;
  private InputAwareLayout              inputContainer;
  private LabeledEditText               nickname;
  private EmojiToggle                   emojiToggle;
  private MediaKeyboard                 mediaKeyboard;
  private TextView                      debugText;
  private boolean                       isNicknameAvailable =  true;
  private boolean                       isNicknameChange    =  false;
  public NicknameAvailabilityReceiver   nicknameAvailabilityReceiver;
  private byte[]                        avatarBytesOrig;

  //better manage the firing of edit change events
  private Timer timer  = new Timer();
  private final long      DELAY  = 1000; // in ms
  //

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);

    if (context instanceof Controller) {
      controller = (Controller) context;
    } else {
      throw new IllegalStateException("Context must subclass Controller");
    }
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.profile_create_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    GroupId groupId = GroupId.parseNullableOrThrow(requireArguments().getString(GROUP_ID, null));

    initializeViewModel(requireArguments().getBoolean(EXCLUDE_SYSTEM, false), groupId, savedInstanceState != null);
    initializeResources(view, groupId);
    initializeProfileAvatar();
    initializeProfileName();

    getParentFragmentManager().setFragmentResultListener(AvatarPickerFragment.REQUEST_KEY_SELECT_AVATAR, getViewLifecycleOwner(), (key, bundle) -> {
      if (bundle.getBoolean(AvatarPickerFragment.SELECT_AVATAR_CLEAR)) {
        viewModel.setAvatarMedia(null);
        viewModel.setAvatar(null);
        avatar.setImageDrawable(null);
      } else {
        Media media = bundle.getParcelable(AvatarPickerFragment.SELECT_AVATAR_MEDIA);
        handleMediaFromResult(media);
      }
    });

    //AA+
    initializeNickname();
    initializeEmojiInput();
    setupNicknameAvailabilityServiceReceiver();
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
                       viewModel.setAvatar(avatarBytes);
                       GlideApp.with(EditProfileFragment.this)
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

  //AA+ for emoji
  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);

    if (inputContainer.getCurrentInput() == mediaKeyboard) {
      inputContainer.hideAttachedInput(true);
    }
  }

//  @Override
  public boolean onBackPressed() {
    if (inputContainer.isInputOpen()) {
      inputContainer.hideCurrentInput(nickname.getInput());
      return true;
    }

    return false;
  }
//

  private void initializeViewModel(boolean excludeSystem, @Nullable GroupId groupId, boolean hasSavedInstanceState) {
    EditProfileRepository repository;

    if (groupId != null) {
      repository = new EditGroupProfileRepository(requireContext(), groupId);
    } else {
      repository = new EditSelfProfileRepository(requireContext(), excludeSystem);
    }

    EditProfileViewModel.Factory factory = new EditProfileViewModel.Factory(repository, hasSavedInstanceState, groupId);

    viewModel = ViewModelProviders.of(requireActivity(), factory)
            .get(EditProfileViewModel.class);
  }

  private void initializeResources(@NonNull View view, @Nullable GroupId groupId) {
    Bundle  arguments      = requireArguments();
    boolean isEditingGroup = groupId != null;

    this.toolbar                 = view.findViewById(R.id.toolbar);
    this.title                   = view.findViewById(R.id.title);
    this.avatar                  = view.findViewById(R.id.avatar);
    this.givenName               = view.findViewById(R.id.given_name);
    this.familyName              = view.findViewById(R.id.family_name);
    this.finishButton            = view.findViewById(R.id.finish_button);
    this.reveal                  = view.findViewById(R.id.reveal);
    this.preview                 = view.findViewById(R.id.name_preview);
    this.avatarPreviewBackground = view.findViewById(R.id.avatar_background);
    this.avatarPreview           = view.findViewById(R.id.avatar_placeholder);
    this.nextIntent              = arguments.getParcelable(NEXT_INTENT);

    //AA+
    this.laterButton       = view.findViewById(R.id.later_button);
    this.inputContainer     = view.findViewById(R.id.input_container);
    this.nickname           = view.findViewById(R.id.nickname);
    this.emojiToggle        = view.findViewById(R.id.emoji_toggle);
    this.mediaKeyboard      = view.findViewById(R.id.emoji_drawer);
    //

    this.avatar.setOnClickListener(v -> startAvatarSelection());

    view.findViewById(R.id.mms_group_hint)
        .setVisibility(isEditingGroup && groupId.isMms() ? View.VISIBLE : View.GONE);

    if (isEditingGroup) {
      EditTextUtil.addGraphemeClusterLimitFilter(givenName, FeatureFlags.getMaxGroupNameGraphemeLength());
      givenName.addTextChangedListener(new AfterTextChanged(s -> viewModel.setGivenName(s.toString())));
      givenName.setHint(R.string.EditProfileFragment__group_name);
      givenName.requestFocus();
      toolbar.setTitle(R.string.EditProfileFragment__edit_group);
      preview.setVisibility(View.GONE);

      if (groupId.isV2()) {
        EditTextUtil.addGraphemeClusterLimitFilter(familyName, MAX_DESCRIPTION_GLYPHS);
        familyName.addTextChangedListener(new AfterTextChanged(s -> {
          EditProfileNameFragment.trimFieldToMaxByteLength(s, MAX_DESCRIPTION_BYTES);
          viewModel.setFamilyName(s.toString());
        }));
        familyName.setHint(R.string.EditProfileFragment__group_description);
        familyName.setSingleLine(false);
        familyName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        LearnMoreTextView descriptionText = view.findViewById(R.id.description_text);
        descriptionText.setLearnMoreVisible(false);
        descriptionText.setText(R.string.CreateProfileActivity_group_descriptions_will_be_visible_to_members_of_this_group_and_people_who_have_been_invited);
      } else {
        familyName.setVisibility(View.GONE);
        familyName.setEnabled(false);
        view.findViewById(R.id.description_text).setVisibility(View.GONE);
      }
      view.<ImageView>findViewById(R.id.avatar_placeholder).setImageResource(R.drawable.ic_group_outline_40);

      //AA+
      nickname.setVisibility(View.GONE);
      emojiToggle.setVisibility(View.GONE);
    } else {
      EditTextUtil.addGraphemeClusterLimitFilter(givenName, EditProfileNameFragment.NAME_MAX_GLYPHS);
      EditTextUtil.addGraphemeClusterLimitFilter(familyName, EditProfileNameFragment.NAME_MAX_GLYPHS);
      this.givenName.addTextChangedListener(new AfterTextChanged(s -> {
        EditProfileNameFragment.trimFieldToMaxByteLength(s);
        viewModel.setGivenName(s.toString());
      }));
      this.familyName.addTextChangedListener(new AfterTextChanged(s -> {
        EditProfileNameFragment.trimFieldToMaxByteLength(s);
        viewModel.setFamilyName(s.toString());
      }));

      LearnMoreTextView descriptionText = view.findViewById(R.id.description_text);
      descriptionText.setLearnMoreVisible(true);
      descriptionText.setOnLinkClickListener(v -> CommunicationActions.openBrowserLink(requireContext(), getString(R.string.EditProfileFragment__support_link)));
    }

    this.finishButton.setOnClickListener(v -> {
      this.finishButton.setIndeterminateProgressMode(true);
      this.finishButton.setProgress(50);
      handleUpload();
    });

    this.finishButton.setText(arguments.getInt(NEXT_BUTTON_TEXT, R.string.CreateProfileActivity_next));

    if (arguments.getBoolean(SHOW_TOOLBAR, true)) {
      this.toolbar.setVisibility(View.VISIBLE);
      this.toolbar.setNavigationOnClickListener(v -> requireActivity().finish());
      this.title.setVisibility(View.GONE);
    }

    //AA+
    this.laterButton.setOnClickListener(v -> {
      this.laterButton.setIndeterminateProgressMode(true);
      this.laterButton.setProgress(50);
      handleLaterButton();
    });

    if (BuildConfig.DEV_BUILD) {
      this.debugText = view.findViewById(R.id.debug_text);
      debugText.setText(DebugDB.getAddressLog());
      debugText.setVisibility(View.VISIBLE);
    }
    //

    //AA+
    this.nickname.getInput().addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (timer != null) timer.cancel();
      }

      @Override
      public void afterTextChanged(Editable s) {
        EditProfileNameFragment.trimFieldToMaxByteLength(s);
        if (nickname.getText().toString().isEmpty() ||
            nickname.getText().toString().equals(TextSecurePreferences.getUfsrvNickname(requireContext()))) {
          nickname.getInput().setError(null);
          return;
        }

        if (s.toString().getBytes().length > ProfileCipher.getTargetNameLength(s.toString())) {
          nickname.getInput().setError(getString(R.string.CreateProfileActivity_too_long));
          finishButton.setEnabled(false);
          return;
        } else if (nickname.getInput().getError() != null || !finishButton.isEnabled()) {
          nickname.getInput().setError(null);
          finishButton.setEnabled(true);
        }

        viewModel.setNickname(s.toString());

        timer = new Timer();
        timer.schedule(new TimerTask() {
          @Override
          public void run() {
            Intent intent = new Intent(ApplicationContext.getInstance(), IntentServiceCheckNicknameAvailability.class);
            intent.putExtra(IntentServiceCheckNicknameAvailability.NICKNAME_EXTRA, nickname.getInput().getText().toString());
            intent.putExtra(IntentServiceCheckNicknameAvailability.PENDING_RESULT_EXTRA, nicknameAvailabilityReceiver);//result carrier object
            ApplicationContext.getInstance().startService(intent);
          }

        }, DELAY);
      }
    });
  }

  private void initializeProfileName() {
    viewModel.isFormValid().observe(getViewLifecycleOwner(), isValid -> {
      finishButton.setEnabled(isValid);
      finishButton.setAlpha(isValid ? 1f : 0.5f);

      if (!isNicknameChange) {//AA+ conditional only
        finishButton.setEnabled(isValid);
        finishButton.setAlpha(isValid ? 1f : 0.5f);
      }
    });

    //AA+
    viewModel.isNameEditable().observe(getViewLifecycleOwner(), isNameEditable -> {
      if (!isNameEditable) {
        givenName.setClickable(true);
        givenName.setFocusable(false);
        givenName.setInputType(InputType.TYPE_NULL);
        givenName.setOnClickListener(v -> {
          Snackbar.make(v, R.string.CreateProfileActivity__group_name_cannot_be_changed, Snackbar.LENGTH_SHORT).setTextColor(Color.WHITE).show();
        });
      }
    });

    viewModel.givenName().observe(getViewLifecycleOwner(), givenName -> updateFieldIfNeeded(this.givenName, givenName));

    viewModel.familyName().observe(getViewLifecycleOwner(), familyName -> updateFieldIfNeeded(this.familyName, familyName));

    viewModel.profileName().observe(getViewLifecycleOwner(), profileName -> preview.setText(profileName.toString()));
  }

  private void initializeProfileAvatar() {
    viewModel.avatar().observe(getViewLifecycleOwner(), bytes -> {
      if (bytes == null) {
        GlideApp.with(this).clear(avatar);
        return;
      }

      GlideApp.with(this)
              .load(bytes)
              .circleCrop()
              .into(avatar);
    });

    viewModel.avatarColor().observe(getViewLifecycleOwner(), avatarColor -> {
      Avatars.ForegroundColor foregroundColor = Avatars.getForegroundColor(avatarColor);

      avatarPreview.getDrawable().setColorFilter(new SimpleColorFilter(foregroundColor.getColorInt()));
      avatarPreviewBackground.getDrawable().setColorFilter(new SimpleColorFilter(avatarColor.colorInt()));
    });
  }

  //AA+
  private void initializeNickname() {
    viewModel.nickname().observe(this, this::onNicknameChanged);
  }

  private void onNicknameChanged(@NonNull String nickname) {
    updateFieldIfNeeded(this.nickname.getInput(), nickname);
  }
  //

  private static void updateFieldIfNeeded(@NonNull EditText field, @NonNull String value) {
    String fieldTrimmed = field.getText().toString().trim();
    String valueTrimmed = value.trim();

    if (!fieldTrimmed.equals(valueTrimmed)) {
      boolean setSelectionToEnd = field.getText().length() == 0;

      field.setText(value);

      if (setSelectionToEnd) {
        field.setSelection(field.getText().length());
      }
    }
  }

  private void startAvatarSelection() {
    if (viewModel.isGroup()) {
      Parcelable groupId = ParcelableGroupId.from(viewModel.getGroupId());
      SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), EditProfileFragmentDirections.actionCreateProfileFragmentToAvatarPicker((ParcelableGroupId) groupId, viewModel.getAvatarMedia()));
    } else {
      SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), EditProfileFragmentDirections.actionCreateProfileFragmentToAvatarPicker(null, null));
    }
  }

  private void handleUpload() {
    viewModel.getUploadResult().observe(getViewLifecycleOwner(), uploadResult -> {
      if (uploadResult == EditProfileRepository.UploadResult.SUCCESS) {
        if (Build.VERSION.SDK_INT >= 21) {
          handleFinishedLollipop();
        }
        else {
          handleFinishedLegacy();
        }
      } else {
        Toast.makeText(requireContext(), R.string.CreateProfileActivity_problem_setting_profile, Toast.LENGTH_LONG).show();
      }
    });

    viewModel.submitProfile();
  }

  private void handleFinishedLegacy() {
    finishButton.setProgress(0);
    if (nextIntent != null) startActivity(nextIntent);

    controller.onProfileNameUploadCompleted();
  }

  @RequiresApi(api = 21)
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
        if (nextIntent != null && getActivity() != null) {
          startActivity(nextIntent);
        }

        controller.onProfileNameUploadCompleted();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}

      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  //AA+
  private void handleLaterButton() {
//    requireActivity().finish();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleLaterLollipop();
    else                                                       handleLaterLegacy();
  }

  private void handleLaterLegacy() {
    laterButton.setProgress(0);
    if (nextIntent != null) startActivity(nextIntent);
    else {
//      final Intent main = MainActivity.clearTop(getActivity());
//      getActivity().startActivity(main);
      requireActivity().finish();
    }

    controller.onProfileNameUploadCompleted();
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private void handleLaterLollipop() {
    int[] finishButtonLocation = new int[2];
    int[] revealLocation       = new int[2];

    laterButton.getLocationInWindow(finishButtonLocation);
    reveal.getLocationInWindow(revealLocation);

    int finishX = finishButtonLocation[0] - revealLocation[0];
    int finishY = finishButtonLocation[1] - revealLocation[1];

    finishX += laterButton.getWidth() / 2;
    finishY += laterButton.getHeight() / 2;

    Animator animation = ViewAnimationUtils.createCircularReveal(reveal, finishX, finishY, 0f, (float) Math.max(reveal.getWidth(), reveal.getHeight()));
    animation.setDuration(500);
    animation.addListener(new Animator.AnimatorListener() {
      @Override
      public void onAnimationStart(Animator animation) {}

      @Override
      public void onAnimationEnd(Animator animation) {
         laterButton.setProgress(0);
        if (nextIntent != null)  startActivity(nextIntent);
        else {
//          final Intent main = MainActivity.clearTop(getActivity());
//          getActivity().startActivity(main);
          requireActivity().finish();
        }
        controller.onProfileNameUploadCompleted();
      }

      @Override
      public void onAnimationCancel(Animator animation) {}

      @Override
      public void onAnimationRepeat(Animator animation) {}
    });

    reveal.setVisibility(View.VISIBLE);
    animation.start();
  }

  public void setupNicknameAvailabilityServiceReceiver() {
    nicknameAvailabilityReceiver = new NicknameAvailabilityReceiver(new Handler(Looper.getMainLooper()));

    nicknameAvailabilityReceiver.setReceiver((resultCode, resultData) -> {
        String nicknameCurrent  = TextSecurePreferences.getUfsrvNickname(requireContext());
        if (resultCode == 0) {
          isNicknameChange = true;
          isNicknameAvailable = resultData.getBoolean(IntentServiceCheckNicknameAvailability.RESULT_NICKNAME_EXTRA, false);
          if (isNicknameAvailable) {
            nickname.getInput().setError(null);
            if (nickname.getInput().getText().equals(nicknameCurrent))  {
              nickname.getInput().setHint("Choose a new nickname");
            } else {
              nickname.getInput().setHint("Hooray! nickname available");
              finishButton.setEnabled(true);
              finishButton.setAlpha(1f);
            }
          } else {
            if (!nickname.getInput().toString().equals(nicknameCurrent))  {nickname.getInput().setHint(null);nickname.getInput().setError("Nickname not available...");}
            else  {nickname.getInput().setHint("Choose a new nickname"); nickname.getInput().setError(null);}
          }
        } else {
          nickname.getInput().setHint(null);
          nickname.getInput().setError("Availability can't be confimed now");
        }
    });
  }

  private void initializeEmojiInput() {
    this.emojiToggle.attach(mediaKeyboard);

    this.emojiToggle.setOnClickListener(v -> {
      if (inputContainer.getCurrentInput() == mediaKeyboard) {
        inputContainer.showSoftkey(nickname.getInput());
      } else {
        inputContainer.show(nickname.getInput(), mediaKeyboard);
      }
    });

    /*this.mediaKeyboard.setProviders(0, new EmojiKeyboardProvider(requireActivity(), new EmojiKeyboardProvider.EmojiEventListener() {
      @Override
      public void onKeyEvent(KeyEvent keyEvent) {
        nickname.dispatchKeyEvent(keyEvent);
      }

      @Override
      public void onEmojiSelected(String emoji) {
        final int start = nickname.getInput().getSelectionStart();
        final int end   = nickname.getInput().getSelectionEnd();

        nickname.getText().replace(Math.min(start, end), Math.max(start, end), emoji);
        nickname.getInput().setSelection(start + emoji.length());
      }
    }));*/

    this.inputContainer.addOnKeyboardShownListener(() -> emojiToggle.setToMedia());
    this.nickname.setOnClickListener(v -> inputContainer.showSoftkey(nickname.getInput()));
  }

  @Override
  public void onKeyEvent(KeyEvent keyEvent) {
    nickname.dispatchKeyEvent(keyEvent);
  }

  @Override
  public void onEmojiSelected(String emoji) {
    final int start = nickname.getInput().getSelectionStart();
    final int end   = nickname.getInput().getSelectionEnd();

    nickname.getText().replace(Math.min(start, end), Math.max(start, end), emoji);
    nickname.getInput().setSelection(start + emoji.length());
  }
  //

  public interface Controller {
    void onProfileNameUploadCompleted();
  }
}