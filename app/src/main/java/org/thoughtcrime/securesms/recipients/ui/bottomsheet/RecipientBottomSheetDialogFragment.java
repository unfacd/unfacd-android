package org.thoughtcrime.securesms.recipients.ui.bottomsheet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPref;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.avatar.view.AvatarView;
import org.thoughtcrime.securesms.badges.BadgeImageView;
import org.thoughtcrime.securesms.badges.view.ViewBadgeBottomSheetDialogFragment;
import org.thoughtcrime.securesms.components.settings.DSLSettingsIcon;
import org.thoughtcrime.securesms.components.settings.conversation.preferences.ButtonStripPreference;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackPhoto80dp;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientExporter;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.BottomSheetUtil;
import org.thoughtcrime.securesms.util.ContextUtil;
import org.thoughtcrime.securesms.util.DrawableUtil;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.SpanUtil;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.Locale;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import kotlin.Unit;

/**
 * A bottom sheet that shows some simple recipient details, as well as some actions (like calling,
 * adding to contacts, etc).
 */
public final class RecipientBottomSheetDialogFragment extends BottomSheetDialogFragment
{

  public static final String TAG = Log.tag(RecipientBottomSheetDialogFragment.class);

  public static final int REQUEST_CODE_SYSTEM_CONTACT_SHEET = 1111;

  private static final String ARGS_RECIPIENT_ID = "RECIPIENT_ID";
  private static final String ARGS_GROUP_ID = "GROUP_ID";

  private RecipientDialogViewModel viewModel;
  private AvatarView               avatar;
  private TextView                 fullName;
  private TextView                 about;
  private TextView                 usernameNumber;
  private Button                   blockButton;
  private Button                   unblockButton;
  private Button                   addContactButton;
  private Button                   contactDetailsButton;
  private Button                   addToGroupButton;
  private Button                   viewSafetyNumberButton;
  private Button                   makeGroupAdminButton;
  private Button                   removeAdminButton;
  private Button                   removeFromGroupButton;
  private ProgressBar              adminActionBusy;
  private View                     noteToSelfDescription;
  private View                     buttonStrip;
  private View                     interactionsContainer;
  private BadgeImageView           badgeImageView;
  //AA+
  private Button                   enableReadReceiptsButton;
  private Button                   disableReadReceiptsButton;
  private Button                   enableProfileSharingButton;
  private Button                   disableProfileSharingButton;
  private Button                   enablePresenceButton;
  private Button                   disablePresenceButton;
  //


  public static BottomSheetDialogFragment create (
          @NonNull RecipientId recipientId,
          @Nullable GroupId groupId)
  {
    Bundle args = new Bundle();
    RecipientBottomSheetDialogFragment fragment = new RecipientBottomSheetDialogFragment();

    args.putString(ARGS_RECIPIENT_ID, recipientId.serialize());
    if (groupId != null) {
      args.putString(ARGS_GROUP_ID, groupId.toString());
    }

    fragment.setArguments(args);

    return fragment;
  }


  @Override
  public void onCreate (@Nullable Bundle savedInstanceState)
  {
    setStyle(DialogFragment.STYLE_NORMAL,
             ThemeUtil.isDarkTheme(requireContext()) ? R.style.Theme_Signal_RoundedBottomSheet
                                                     : R.style.Theme_Signal_RoundedBottomSheet_Light);

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);   //AA+

    super.onCreate(savedInstanceState);
  }


  @Override
  public View onCreateView (@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
  {
    View view = inflater.inflate(R.layout.recipient_bottom_sheet, container, false);

    avatar = view.findViewById(R.id.rbs_recipient_avatar);
    fullName = view.findViewById(R.id.rbs_full_name);
    about = view.findViewById(R.id.rbs_about);
    usernameNumber = view.findViewById(R.id.rbs_username_number);
    blockButton = view.findViewById(R.id.rbs_block_button);
    unblockButton = view.findViewById(R.id.rbs_unblock_button);
    addContactButton = view.findViewById(R.id.rbs_add_contact_button);
    contactDetailsButton = view.findViewById(R.id.rbs_contact_details_button);
    addToGroupButton = view.findViewById(R.id.rbs_add_to_group_button);
    viewSafetyNumberButton = view.findViewById(R.id.rbs_view_safety_number_button);
    makeGroupAdminButton = view.findViewById(R.id.rbs_make_group_admin_button);
    removeAdminButton = view.findViewById(R.id.rbs_remove_group_admin_button);
    removeFromGroupButton = view.findViewById(R.id.rbs_remove_from_group_button);
    adminActionBusy = view.findViewById(R.id.rbs_admin_action_busy);
    noteToSelfDescription = view.findViewById(R.id.rbs_note_to_self_description);
    buttonStrip = view.findViewById(R.id.button_strip);
    interactionsContainer = view.findViewById(R.id.interactions_container);
    badgeImageView = view.findViewById(R.id.rbs_badge);
    //AA+
    enableReadReceiptsButton = view.findViewById(R.id.rbs_enable_read_receipt);
    disableReadReceiptsButton = view.findViewById(R.id.rbs_disable_read_receipt);
    enableProfileSharingButton = view.findViewById(R.id.rbs_enable_profile_sharing);
    disableProfileSharingButton = view.findViewById(R.id.rbs_disable_profile_sharing);
    enablePresenceButton= view.findViewById(R.id.rbs_enable_presence_sharing);
    disablePresenceButton = view.findViewById(R.id.rbs_disable_presence_sharing);
    //

    return view;
  }


  @Override
  public void onViewCreated (@NonNull View fragmentView, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(fragmentView, savedInstanceState);

    Bundle arguments = requireArguments();
    RecipientId recipientId = RecipientId.from(Objects.requireNonNull(arguments.getString(ARGS_RECIPIENT_ID)));
    GroupId groupId = GroupId.parseNullableOrThrow(arguments.getString(ARGS_GROUP_ID));

    RecipientDialogViewModel.Factory factory = new RecipientDialogViewModel.Factory(requireContext().getApplicationContext(), recipientId, groupId);

    viewModel = new ViewModelProvider(this, factory).get(RecipientDialogViewModel.class);

    viewModel.getStoryViewState().observe(getViewLifecycleOwner(), state -> {
      avatar.setStoryRingFromState(state);
    });

    viewModel.getRecipient().observe(getViewLifecycleOwner(), recipient -> {
      interactionsContainer.setVisibility(recipient.isSelf() ? View.GONE : View.VISIBLE);

      avatar.setFallbackPhotoProvider(new Recipient.FallbackPhotoProvider()
      {
        @Override
        public @NonNull FallbackContactPhoto getPhotoForLocalNumber ()
        {
          return new FallbackPhoto80dp(R.drawable.ic_note_80, recipient.getAvatarColor());
        }
      });
      avatar.displayChatAvatar(recipient);

      if (!recipient.isSelf()) {
        badgeImageView.setBadgeFromRecipient(recipient);
      }

      if (recipient.isSelf()) {
        avatar.setOnClickListener(v -> {
          dismiss();
          viewModel.onMessageClicked(requireActivity());
        });
      }

      String name = recipient.isSelf() ? requireContext().getString(R.string.note_to_self)
                                       : recipient.getDisplayName(requireContext());
      fullName.setVisibility(TextUtils.isEmpty(name) ? View.GONE : View.VISIBLE);
      SpannableStringBuilder nameBuilder = new SpannableStringBuilder(name);
      if (recipient.isSystemContact() && !recipient.isSelf()) {
        Drawable systemContact = DrawableUtil.tint(ContextUtil.requireDrawable(requireContext(), R.drawable.ic_profile_circle_outline_16),
                                                   ContextCompat.getColor(requireContext(), R.color.signal_text_primary));
        SpanUtil.appendCenteredImageSpan(nameBuilder, systemContact, 16, 16);
      } else if (recipient.showVerified()) {
        SpanUtil.appendCenteredImageSpan(nameBuilder, ContextUtil.requireDrawable(requireContext(), R.drawable.ic_official_28), 28, 28);
      }
      fullName.setText(nameBuilder);

      String aboutText = recipient.getCombinedAboutAndEmoji();
      if (recipient.isReleaseNotes()) {
        aboutText = getString(R.string.ReleaseNotes__signal_release_notes_and_news);
      }

      if (!Util.isEmpty(aboutText)) {
        about.setText(aboutText);
        about.setVisibility(View.VISIBLE);
      }
      else {
        about.setVisibility(View.GONE);
      }

      String usernameNumberString = recipient.hasAUserSetDisplayName(requireContext()) && !recipient.isSelf()
                                    ? recipient.getSmsAddress().map(PhoneNumberFormatter::prettyPrint).orElse("").trim()
                                    : "";
      usernameNumber.setText(usernameNumberString);
      usernameNumber.setVisibility(TextUtils.isEmpty(usernameNumberString) ? View.GONE : View.VISIBLE);
      usernameNumber.setOnLongClickListener(v -> {
        Util.copyToClipboard(v.getContext(), usernameNumber.getText().toString());
        ServiceUtil.getVibrator(v.getContext()).vibrate(250);
        Toast.makeText(v.getContext(), R.string.RecipientBottomSheet_copied_to_clipboard, Toast.LENGTH_SHORT).show();
        return true;
      });

      noteToSelfDescription.setVisibility(recipient.isSelf() ? View.VISIBLE : View.GONE);

      if (RecipientUtil.isBlockable(recipient)) {
        boolean blocked = recipient.isBlocked();

        blockButton.setVisibility(recipient.isSelf() || blocked ? View.GONE : View.VISIBLE);
        unblockButton.setVisibility(recipient.isSelf() || !blocked ? View.GONE : View.VISIBLE);
      }
      else {
        blockButton.setVisibility(View.GONE);
        unblockButton.setVisibility(View.GONE);
      }

      //AA+
      if (recipient.isReadReceiptSharing()) {
        enableReadReceiptsButton.setVisibility(View.GONE);
        disableReadReceiptsButton.setVisibility(View.VISIBLE);
      } else {
        enableReadReceiptsButton.setVisibility(View.VISIBLE);
        disableReadReceiptsButton.setVisibility(View.GONE);
      }

      if (recipient.isProfileSharing()) {
        enableProfileSharingButton.setVisibility(View.GONE);
        disableProfileSharingButton.setVisibility(View.VISIBLE);
      } else {
        enableProfileSharingButton.setVisibility(View.VISIBLE);
        disableProfileSharingButton.setVisibility(View.GONE);
      }

      if (recipient.isPresenceSharing()) {
        enablePresenceButton.setVisibility(View.GONE);
        disablePresenceButton.setVisibility(View.VISIBLE);
      } else {
        enablePresenceButton.setVisibility(View.VISIBLE);
        disablePresenceButton.setVisibility(View.GONE);
      }
      //

      ButtonStripPreference.State  buttonStripState = new ButtonStripPreference.State(
              /* isMessageAvailable = */ !recipient.isBlocked() && !recipient.isSelf() && !recipient.isReleaseNotes(),
              /* isVideoAvailable   = */ !recipient.isBlocked() && !recipient.isSelf() && recipient.isRegistered(),
              /* isAudioAvailable   = */ !recipient.isBlocked() && !recipient.isSelf() && !recipient.isReleaseNotes(),
              /* isMuteAvailable    = */ false,
              /* isSearchAvailable  = */ false,
              /* isAudioSecure      = */ recipient.isRegistered(),
          /* isMuted            = */ false
      );

      ButtonStripPreference.Model buttonStripModel = new ButtonStripPreference.Model(
              buttonStripState,
              DSLSettingsIcon.from(ContextUtil.requireDrawable(requireContext(), R.drawable.selectable_recipient_bottom_sheet_icon_button)),
              () -> {
                dismiss();
                viewModel.onMessageClicked(requireActivity());
                return Unit.INSTANCE;
              },
              () -> {
                viewModel.onSecureVideoCallClicked(requireActivity(), groupId);//AA+ groId
                return Unit.INSTANCE;
              },
              () -> {
                if (buttonStripState.isAudioSecure()) {
                  viewModel.onSecureCallClicked(requireActivity(), groupId);//AA+ groupId
                }
                else {
                  viewModel.onInsecureCallClicked(requireActivity());
                }
                return Unit.INSTANCE;
              },
              () -> Unit.INSTANCE,
              () -> Unit.INSTANCE
      );

      new ButtonStripPreference.ViewHolder(buttonStrip).bind(buttonStripModel);

      if (recipient.isReleaseNotes()) {
        buttonStrip.setVisibility(View.GONE);
      }

      if (recipient.isSystemContact() || recipient.isGroup() || recipient.isSelf() || recipient.isBlocked() || recipient.isReleaseNotes()) {
        addContactButton.setVisibility(View.GONE);
      }
      else {
        addContactButton.setVisibility(View.VISIBLE);
        addContactButton.setOnClickListener(v -> {
          openSystemContactSheet(RecipientExporter.export(recipient).asAddContactIntent());
        });
      }

      if (recipient.isSystemContact() && !recipient.isGroup() && !recipient.isSelf()) {
        contactDetailsButton.setVisibility(View.VISIBLE);
        contactDetailsButton.setOnClickListener(v -> {
          openSystemContactSheet(new Intent(Intent.ACTION_VIEW, recipient.getContactUri()));
        });
      }
      else {
        contactDetailsButton.setVisibility(View.GONE);
      }
    });

    viewModel.getCanAddToAGroup().observe(getViewLifecycleOwner(), canAdd -> {
      addToGroupButton.setText(groupId == null ? R.string.RecipientBottomSheet_add_to_a_group : R.string.RecipientBottomSheet_add_to_another_group);
      addToGroupButton.setVisibility(canAdd ? View.VISIBLE : View.GONE);
    });

    viewModel.getAdminActionStatus().observe(getViewLifecycleOwner(), adminStatus -> {
      makeGroupAdminButton.setVisibility(adminStatus.isCanMakeAdmin() ? View.VISIBLE : View.GONE);
      removeAdminButton.setVisibility(adminStatus.isCanMakeNonAdmin() ? View.VISIBLE : View.GONE);
      removeFromGroupButton.setVisibility(adminStatus.isCanRemove() ? View.VISIBLE : View.GONE);

      if (adminStatus.isCanRemove()) {
        removeFromGroupButton.setOnClickListener(view -> viewModel.onRemoveFromGroupClicked(requireActivity(), adminStatus.isLinkActive(), this::dismiss));
      }
    });

    viewModel.getIdentity().observe(getViewLifecycleOwner(), identityRecord -> {
      viewSafetyNumberButton.setVisibility(identityRecord != null ? View.VISIBLE : View.GONE);

      if (identityRecord != null) {
        viewSafetyNumberButton.setOnClickListener(view -> {
          dismiss();
          viewModel.onViewSafetyNumberClicked(requireActivity(), identityRecord);
        });
      }
    });

    avatar.setOnClickListener(view -> {
      dismiss();
      viewModel.onAvatarClicked(requireActivity());
    });

    badgeImageView.setOnClickListener(view -> {
      dismiss();
      ViewBadgeBottomSheetDialogFragment.show(getParentFragmentManager(), recipientId, null);
    });

    blockButton.setOnClickListener(view -> viewModel.onBlockClicked(requireActivity()));
    unblockButton.setOnClickListener(view -> viewModel.onUnblockClicked(requireActivity()));

    //AA+
    enableReadReceiptsButton.setOnClickListener(view -> viewModel.onEnableReadReceiptsClicked(requireActivity()));
    disableReadReceiptsButton.setOnClickListener(view -> viewModel.onDisableReadReceiptsClicked(requireActivity()));

    enableProfileSharingButton.setOnClickListener(view -> viewModel.onEnableProfileSharingClicked(requireActivity()));
    disableProfileSharingButton.setOnClickListener(view -> viewModel.onDisableProfileSharingClicked(requireActivity()));

    enablePresenceButton.setOnClickListener(view -> viewModel.onEnablePresenceSharingClicked(requireActivity()));
    disablePresenceButton.setOnClickListener(view -> viewModel.onDisablePresenceSharingClicked(requireActivity()));
    //

    makeGroupAdminButton.setOnClickListener(view -> viewModel.onMakeGroupAdminClicked(requireActivity()));
    removeAdminButton.setOnClickListener(view -> viewModel.onRemoveGroupAdminClicked(requireActivity()));

    addToGroupButton.setOnClickListener(view -> {
      dismiss();
      viewModel.onAddToGroupButton(requireActivity());
    });

    viewModel.getAdminActionBusy().observe(getViewLifecycleOwner(), busy -> {
      adminActionBusy.setVisibility(busy ? View.VISIBLE : View.GONE);

      makeGroupAdminButton.setEnabled(!busy);
      removeAdminButton.setEnabled(!busy);
      removeFromGroupButton.setEnabled(!busy);
    });
  }

  //AA+
  @Override
  public void onStop() {
    super.onStop();
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);//AA+
  }

  private void openSystemContactSheet(@NonNull Intent intent)
  {
    try {
      startActivityForResult(intent, REQUEST_CODE_SYSTEM_CONTACT_SHEET);
    }
    catch (ActivityNotFoundException e) {
      Log.w(TAG, "No activity existed to open the contact.");
      Toast.makeText(requireContext(), R.string.RecipientBottomSheet_unable_to_open_contacts, Toast.LENGTH_LONG).show();
    }
  }


  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data)
  {
    if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_SYSTEM_CONTACT_SHEET) {
      viewModel.refreshRecipient();
    }
  }


  @Override
  public void show(@NonNull FragmentManager manager, @Nullable String tag)
  {
    BottomSheetUtil.show(manager, tag, this);
  }

  //AA+
  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventUserPref event)
  {
    if (event.hasErroArg()) {
      Snackbar snackbar = Snackbar.make(getView().getRootView(), describeUserPrefError(event.getUserPreference(), event.getUserCommandErrorArg()), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).setTextColor(getResources().getColor(R.color.colorText));
      snackbar.getView().setBackgroundColor(getResources().getColor(R.color.signal_background_dialog));
      snackbar.show();
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  String describeUserPrefError(SignalServiceProtos.UserPreference userPrefs, SignalServiceProtos.UserCommand.Errors userCommandError)
  {
    switch (userCommandError) {
      case LIST_MEMBERSHIP -> {
        return String.format(Locale.getDefault(), "%s unsuccessful due to: %s", userPrefs.getPrefName(), userCommandError.name());
      }

      default -> {
        return String.format(Locale.getDefault(), "%s unsuccessful due to known error.", userPrefs.getPrefName());
      }
    }
  }
}