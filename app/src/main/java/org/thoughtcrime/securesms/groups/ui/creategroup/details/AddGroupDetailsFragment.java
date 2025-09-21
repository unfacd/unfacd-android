package org.thoughtcrime.securesms.groups.ui.creategroup.details;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.dd.CircularProgressButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kyleduo.switchbutton.SwitchButton;
import com.roughike.swipeselector.SwipeItem;
import com.roughike.swipeselector.SwipeSelector;
import com.shawnlin.numberpicker.NumberPicker;
import com.unfacd.android.R;

import com.unfacd.android.ui.components.expandablelayout.ExpandableLayout;

import org.signal.core.util.EditTextUtil;
import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.avatar.picker.AvatarPickerFragment;
import org.thoughtcrime.securesms.components.settings.app.privacy.expire.ExpireTimerSettingsFragment;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.ui.GroupMemberListView;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mediasend.Media;
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.ui.disappearingmessages.RecipientDisappearingMessagesActivity;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.navigation.SafeNavigation;
import org.thoughtcrime.securesms.util.text.AfterTextChanged;
import org.thoughtcrime.securesms.util.views.LearnMoreTextView;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

public class AddGroupDetailsFragment extends LoggingFragment {

  private static final int   AVATAR_PLACEHOLDER_INSET_DP = 18;
  private static final short REQUEST_CODE_AVATAR         = 27621;
  private static final short REQUEST_DISAPPEARING_TIMER  = 28621;

  private CircularProgressButton   create;
  private Callback                 callback;
  private AddGroupDetailsViewModel viewModel;
  private Drawable                 avatarPlaceholder;
  private EditText                 name;
  private Toolbar                  toolbar;
  private View                     disappearingMessagesRow;

  private ExpandableLayout expandableLayoutAdvanced;
  private ExpandableLayout expandableLayoutPermissions;
  private SwitchButton switchButtonAdvanced;
  private SwitchButton switchButtonPermissions;
  private SwipeSelector deliveryModeSelector;
  private SwipeSelector privacyModeSelector;
  private SwipeSelector joinModeSelector;
  private SwipeSelector semanticsModeSelector;
  private NumberPicker numberPicker;

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);

    if (context instanceof Callback) {
      callback = (Callback) context;
    } else {
      throw new ClassCastException("Parent context should implement AddGroupDetailsFragment.Callback");
    }
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater,
                                     @Nullable ViewGroup container,
                                     @Nullable Bundle savedInstanceState)
  {
    return inflater.inflate(R.layout.add_group_details_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    create                  = view.findViewById(R.id.create);
    name                    = view.findViewById(R.id.name);
    toolbar                 = view.findViewById(R.id.toolbar);
    disappearingMessagesRow = view.findViewById(R.id.group_disappearing_messages_row);

    setCreateEnabled(false, false);

    GroupMemberListView members                  = view.findViewById(R.id.member_list);
    ImageView           avatar                   = view.findViewById(R.id.group_avatar);
    View                mmsWarning               = view.findViewById(R.id.mms_warning);
    LearnMoreTextView   gv2Warning               = view.findViewById(R.id.gv2_warning);
    View                addLater                 = view.findViewById(R.id.add_later);
    TextView            disappearingMessageValue = view.findViewById(R.id.group_disappearing_messages_value);

    members.initializeAdapter(getViewLifecycleOwner());
    avatarPlaceholder = VectorDrawableCompat.create(getResources(), R.drawable.ic_camera_outline_32_ultramarine, requireActivity().getTheme());

    if (savedInstanceState == null) {
      avatar.setImageDrawable(new InsetDrawable(avatarPlaceholder, ViewUtil.dpToPx(AVATAR_PLACEHOLDER_INSET_DP)));
    }

    initialiseModeSelectors(view, false);//AA+

    initializeViewModel();

    avatar.setOnClickListener(v -> showAvatarPicker());
    members.setRecipientClickListener(this::handleRecipientClick);
    EditTextUtil.addGraphemeClusterLimitFilter(name, FeatureFlags.getMaxGroupNameGraphemeLength());
    name.addTextChangedListener(new AfterTextChanged(editable -> viewModel.setName(editable.toString())));
    toolbar.setNavigationOnClickListener(unused -> callback.onNavigationButtonPressed());
    create.setOnClickListener(v -> handleCreateClicked());
    viewModel.getMembers().observe(getViewLifecycleOwner(), list -> {
      addLater.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
      members.setMembers(list);
    });
    viewModel.getCanSubmitForm().observe(getViewLifecycleOwner(), isFormValid -> setCreateEnabled(isFormValid, true));
    viewModel.getIsMms().observe(getViewLifecycleOwner(), isMms -> {
      disappearingMessagesRow.setVisibility(isMms ? View.GONE : View.VISIBLE);
      mmsWarning.setVisibility(isMms ? View.VISIBLE : View.GONE);
      name.setHint(isMms ? R.string.AddGroupDetailsFragment__group_name_optional : R.string.AddGroupDetailsFragment__group_name_required);
      toolbar.setTitle(isMms ? R.string.AddGroupDetailsFragment__create_group : R.string.AddGroupDetailsFragment__name_this_group);
    });
    viewModel.getAvatar().observe(getViewLifecycleOwner(), avatarBytes -> {
      if (avatarBytes == null) {
        avatar.setImageDrawable(new InsetDrawable(avatarPlaceholder, ViewUtil.dpToPx(AVATAR_PLACEHOLDER_INSET_DP)));
      } else {
        GlideApp.with(this)
                .load(avatarBytes)
                .circleCrop()
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(avatar);
      }
    });

    //AA+
//    viewModel.getMembersSize().observe(getViewLifecycleOwner(), membersSize -> {
//      numberPicker.setValue(membersSize);
//    });
    viewModel.setMembersSize(0);

//    viewModel.getPrivacyMode().observe(getViewLifecycleOwner(), privacyMode -> {
//      privacyModeSelector.selectItemAt(privacyMode.getValue());
//    });
    viewModel.setPrivacyMode(GroupDatabase.PrivacyMode.PUBLIC);

//    viewModel.getDeliveryMode().observe(getViewLifecycleOwner(), deliveryMode -> {
//      deliveryModeSelector.selectItemAt(deliveryMode.getValue());
//    });
    viewModel.setDeliveryMode(GroupDatabase.DeliveryMode.MANY);

//    viewModel.getJoinMode().observe(getViewLifecycleOwner(), joinMode -> {
//      joinModeSelector.selectItemAt(joinMode.getValue());
//    });
    viewModel.setJoinMode(GroupDatabase.JoinMode.OPEN);

    joinModeSelector.setOnItemSelectedListener((c) -> viewModel.setJoinMode(GroupDatabase.JoinMode.values()[Integer.valueOf(c.getValue())]));
    deliveryModeSelector.setOnItemSelectedListener((c) -> viewModel.setDeliveryMode(GroupDatabase.DeliveryMode.values()[Integer.valueOf(c.getValue())]));
    privacyModeSelector.setOnItemSelectedListener((c) -> viewModel.setPrivacyMode(GroupDatabase.PrivacyMode.values()[Integer.valueOf(c.getValue())]));

//    viewModel.getGroupPermissions().observe(getViewLifecycleOwner(), groupPermissions -> {
//      semanticsModeSelector.selectItemAt(groupPermissions);
//    });

//    semanticsModeSelector.selectItemAt(groupPreferences.getPermSemanticsMembership().getValue()-1);//todo: this offset doesnt start at 0: update protobuf and other models
//    //cannot be set after group creation
//    semanticsModeSelector.setLocked(true);
    //

    viewModel.getDisappearingMessagesTimer().observe(getViewLifecycleOwner(), timer -> disappearingMessageValue.setText(ExpirationUtil.getExpirationDisplayValue(requireContext(), timer)));
    disappearingMessagesRow.setOnClickListener(v -> {
      startActivityForResult(RecipientDisappearingMessagesActivity.forCreateGroup(requireContext(), viewModel.getDisappearingMessagesTimer().getValue()), REQUEST_DISAPPEARING_TIMER);
    });

    name.requestFocus();

    getParentFragmentManager().setFragmentResultListener(AvatarPickerFragment.REQUEST_KEY_SELECT_AVATAR,
                                                         getViewLifecycleOwner(),
                                                         (key, bundle) -> handleMediaResult(bundle));
  }

  //AA+
  private void initialiseModeSelectors (@NonNull View view, boolean isExistingGroup) {
    if (!isExistingGroup) {
      deliveryModeSelector = view.findViewById(R.id.delivery_mode_selector);
      joinModeSelector = view.findViewById(R.id.join_mode_selector);
      privacyModeSelector = view.findViewById(R.id.privacy_mode_selector);
      semanticsModeSelector = view.findViewById(R.id.semantics_mode_selector);
    }

    deliveryModeSelector.setItems(
            // The first argument is the value for that item, and should in most cases be unique for the
            // current SwipeSelector, just as you would assign values to radio buttons.
            // You can use the value later on to check what the selected item was.
            // The value can be any Object, here we're using ints.
            new SwipeItem("0", "Normal multi-way Mode", "Normal multi-way messaging."),
            new SwipeItem("1", "Broadcast Mode", "Messages are broadcast to the entire group. Replies only seen by onwer."),
            new SwipeItem("2", "One-way Mode", "Messages are broadcast to the entire group. No replies allowed.")
    );
    joinModeSelector.setItems(
            // The first argument is the value for that item, and should in most cases be unique for the
            // current SwipeSelector, just as you would assign values to radio buttons.
            // You can use the value later on to check what the selected item was.
            // The value can be any Object, here we're using ints.
            new SwipeItem("0", "Open Join Mode", "Anybody can join group"),
            new SwipeItem("1", "Invite-only Mode", "Only users with prior invitation can join."),
            new SwipeItem("2", "Open With Key Mode", "User must enter a private key set by group owner."),
            new SwipeItem("3", "Invite-only With Key Mode", "Users require invitation and private key to joing.")
    );
    privacyModeSelector.setItems(
            new SwipeItem("0", "Publicly Visible Mode", "Group is publically visible."),
            new SwipeItem("1", "Private Mode", "Group is hidden from public view.")
    );

    semanticsModeSelector.setItems(
            new SwipeItem("1", "Strict Mode", "Only specifically listed members are permitted (whitelising)."),
            new SwipeItem("2", "Relaxed Mode", "Group permissions are relaxed except for specifically listed members (blacklisting).")//default BLACKLISTING

    );

    switchButtonAdvanced = view.findViewById(R.id.switch_button);
    switchButtonAdvanced.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          expandableLayoutAdvanced.expand();
        } else {
          if (expandableLayoutAdvanced.isExpanded()) {
            expandableLayoutAdvanced.collapse();
          }
        }
      }
    });

    expandableLayoutAdvanced = view.findViewById(R.id.expandable_layout_advanced);
    expandableLayoutAdvanced.setOnExpansionUpdateListener(new ExpandableLayout.OnExpansionUpdateListener() {
      @Override
      public void onExpansionUpdate(float expansionFraction, int state) {
        TextView switchButtonText = view.findViewById(R.id.switch_button_text);
        switch (state)
        {
          case 0:
            switchButtonText.setText("See more advanced settings...");
            break;
          case 3:
            switchButtonText.setText("Advanced Group settings");
            break;
        }
      }
    });

    numberPicker = view.findViewById(R.id.number_picker);

    switchButtonPermissions = view.findViewById(R.id.switch_button_permission);
    switchButtonPermissions.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
          expandableLayoutPermissions.expand();
        } else {
          if (expandableLayoutPermissions.isExpanded()) {
            expandableLayoutPermissions.collapse();
          }
        }
      }
    });

    expandableLayoutPermissions = view.findViewById(R.id.expandable_layout_permissions);
    expandableLayoutPermissions.setOnExpansionUpdateListener(new ExpandableLayout.OnExpansionUpdateListener() {
      @Override
      public void onExpansionUpdate(float expansionFraction, int state) {
        TextView switchButtonText = view.findViewById(R.id.switch_button_permissions_text);
        switch (state)
        {
          case 0:
            switchButtonText.setText("See permissions related settings...");
            break;
          case 3:
            switchButtonText.setText("Group permissions related settings...");
            break;
        }
      }
    });
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (requestCode == REQUEST_DISAPPEARING_TIMER && resultCode == Activity.RESULT_OK && data != null) {
      viewModel.setDisappearingMessageTimer(data.getIntExtra(ExpireTimerSettingsFragment.FOR_RESULT_VALUE, SignalStore.settings().getUniversalExpireTimer()));
    } else {
      super.onActivityResult(requestCode, resultCode, data);
    }
  }

  private void handleMediaResult(Bundle data) {
    if (data.getBoolean(AvatarPickerFragment.SELECT_AVATAR_CLEAR)) {
      viewModel.setAvatarMedia(null);
      viewModel.setAvatar(null);
      return;
    }

    final Media result                                             = data.getParcelable(AvatarPickerFragment.SELECT_AVATAR_MEDIA);
    final DecryptableStreamUriLoader.DecryptableUri decryptableUri = new DecryptableStreamUriLoader.DecryptableUri(result.getUri());

    viewModel.setAvatarMedia(result);

    GlideApp.with(this)
            .asBitmap()
            .load(decryptableUri)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .centerCrop()
            .override(AvatarHelper.AVATAR_DIMENSIONS, AvatarHelper.AVATAR_DIMENSIONS)
            .into(new CustomTarget<Bitmap>() {
              @Override
              public void onResourceReady(@NonNull Bitmap resource, Transition<? super Bitmap> transition) {
                viewModel.setAvatar(Objects.requireNonNull(BitmapUtil.toByteArray(resource)));
              }

              @Override
              public void onLoadCleared(@Nullable Drawable placeholder) {
              }
            });
  }

  private void initializeViewModel() {
    AddGroupDetailsFragmentArgs      args       = AddGroupDetailsFragmentArgs.fromBundle(requireArguments());
    AddGroupDetailsRepository        repository = new AddGroupDetailsRepository(requireContext());
    AddGroupDetailsViewModel.Factory factory    = new AddGroupDetailsViewModel.Factory(Arrays.asList(args.getRecipientIds()), repository);

    viewModel = ViewModelProviders.of(this, factory).get(AddGroupDetailsViewModel.class);

    viewModel.getGroupCreateResult().observe(getViewLifecycleOwner(), this::handleGroupCreateResult);
  }

  private void handleCreateClicked() {
    create.setClickable(false);
    create.setIndeterminateProgressMode(true);
    create.setProgress(50);

    viewModel.create();
  }

  private void handleRecipientClick(@NonNull Recipient recipient) {
    new MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.AddGroupDetailsFragment__remove_s_from_this_group, recipient.getDisplayName(requireContext())))
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
            .setPositiveButton(R.string.AddGroupDetailsFragment__remove, (dialog, which) -> {
              viewModel.delete(recipient.getId());
              dialog.dismiss();
            })
            .show();
  }

  private void handleGroupCreateResult(@NonNull GroupCreateResult groupCreateResult) {
    groupCreateResult.consume(this::handleGroupCreateResultSuccess, this::handleGroupCreateResultError);
  }

  private void handleGroupCreateResultSuccess(@NonNull GroupCreateResult.Success success) {
    callback.onGroupCreated(success.getGroupRecipient().getId(), success.getThreadId(), success.getInvitedMembers());
  }

  private void handleGroupCreateResultError(@NonNull GroupCreateResult.Error error) {
    switch (error.getErrorType()) {
      case ERROR_IO:
      case ERROR_BUSY:
        toast(R.string.AddGroupDetailsFragment__try_again_later);
        break;
      case ERROR_FAILED:
        toast(R.string.AddGroupDetailsFragment__group_creation_failed);
        break;
      case ERROR_INVALID_NAME:
        name.setError(getString(R.string.AddGroupDetailsFragment__this_field_is_required));
        break;
      default:
        throw new IllegalStateException("Unexpected error: " + error.getErrorType().name());
    }
  }

  private void toast(@StringRes int toastStringId) {
    Toast.makeText(requireContext(), toastStringId, Toast.LENGTH_SHORT)
            .show();
  }

  private void setCreateEnabled(boolean isEnabled, boolean animate) {
    if (create.isEnabled() == isEnabled) {
      return;
    }

    create.setEnabled(isEnabled);
    create.animate()
            .setDuration(animate ? 300 : 0)
            .alpha(isEnabled ? 1f : 0.5f);
  }

  private void showAvatarPicker() {
    Media media = viewModel.getAvatarMedia();

    SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), AddGroupDetailsFragmentDirections.actionAddGroupDetailsFragmentToAvatarPicker(null, media).setIsNewGroup(true));
  }

  public interface Callback {
    void onGroupCreated(@NonNull RecipientId recipientId, long threadId, @NonNull List<Recipient> invitedMembers);

    void onNavigationButtonPressed();
  }
}