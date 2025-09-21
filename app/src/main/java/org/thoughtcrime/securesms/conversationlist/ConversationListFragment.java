/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.conversationlist;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.airbnb.lottie.SimpleColorFilter;
import com.annimon.stream.Stream;
import com.google.android.material.animation.ArgbEvaluatorCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.protobuf.ByteString;
import com.joaquimley.faboptions.FabOptions;
import com.mikepenz.actionitembadge.library.utils.BadgeStyle;
import com.ogaclejapan.smarttablayout.SmartTabLayout;
import com.ogaclejapan.smarttablayout.utils.v4.FragmentPagerItems;
import com.tomergoldst.tooltips.ToolTip;
import com.tomergoldst.tooltips.ToolTipsManager;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.data.json.JsonEntityStateSync;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvcmd.events.AppEventConversationListNotification;
import com.unfacd.android.ufsrvcmd.events.AppEventMessageNotification;
import com.unfacd.android.ufsrvcmd.events.AppEventNotificationWSConnection;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefNickname;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserReadReceiptShared;
import com.unfacd.android.ufsrvcmd.events.LocationV1;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;
import com.unfacd.android.ufsrvcmd.events.StateSyncV1SystemEvent;
import com.unfacd.android.ui.FenceMapActivity;
import com.unfacd.android.ui.components.InvitedToGroupDialog;
import com.unfacd.android.ui.components.MessageDialogCloseListener;
import com.unfacd.android.ui.components.TintableImage;
import com.unfacd.android.ui.components.intro_contact.AppEventIntroContact;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.ui.components.intro_contact.IntroContactDialogFragment;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.Utils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.DimensionUnit;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.MainFragment;
import org.thoughtcrime.securesms.MainNavigator;
import org.thoughtcrime.securesms.MuteDialog;
import org.thoughtcrime.securesms.NewConversationActivity;
import org.thoughtcrime.securesms.PassphraseRequiredActivity;
import org.thoughtcrime.securesms.badges.models.Badge;
import org.thoughtcrime.securesms.badges.self.expired.CantProcessSubscriptionPaymentBottomSheetDialogFragment;
import org.thoughtcrime.securesms.badges.self.expired.ExpiredBadgeBottomSheetDialogFragment;
import org.thoughtcrime.securesms.components.RatingManager;
import org.thoughtcrime.securesms.components.SearchToolbar;
import org.thoughtcrime.securesms.components.UnreadPaymentsView;
import org.thoughtcrime.securesms.components.menu.ActionItem;
import org.thoughtcrime.securesms.components.menu.SignalBottomActionBar;
import org.thoughtcrime.securesms.components.menu.SignalContextMenu;
import org.thoughtcrime.securesms.components.registration.PulsingFloatingActionButton;
import org.thoughtcrime.securesms.components.reminder.DozeReminder;
import org.thoughtcrime.securesms.components.reminder.ExpiredBuildReminder;
import org.thoughtcrime.securesms.components.reminder.OutdatedBuildReminder;
import org.thoughtcrime.securesms.components.reminder.PushRegistrationReminder;
import org.thoughtcrime.securesms.components.reminder.Reminder;
import org.thoughtcrime.securesms.components.reminder.ReminderView;
import org.thoughtcrime.securesms.components.reminder.ServiceOutageReminder;
import org.thoughtcrime.securesms.components.reminder.UnauthorizedReminder;
import org.thoughtcrime.securesms.components.settings.app.notifications.manual.NotificationProfileSelectionFragment;
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.UnexpectedSubscriptionCancellation;
import org.thoughtcrime.securesms.components.voice.VoiceNoteMediaControllerOwner;
import org.thoughtcrime.securesms.components.voice.VoiceNotePlayerView;
import org.thoughtcrime.securesms.conversation.ConversationFragment;
import org.thoughtcrime.securesms.conversationlist.model.Conversation;
import org.thoughtcrime.securesms.conversationlist.model.UnreadPayments;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase.MarkedMessageInfo;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.ReminderUpdateEvent;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.ui.creategroup.CreateGroupActivity;
import org.thoughtcrime.securesms.insights.InsightsLauncher;
import org.thoughtcrime.securesms.jobs.ServiceOutageDetectionJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.lock.v2.CreateKbsPinActivity;
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity;
import org.thoughtcrime.securesms.megaphone.Megaphone;
import org.thoughtcrime.securesms.megaphone.MegaphoneActionController;
import org.thoughtcrime.securesms.megaphone.MegaphoneViewBuilder;
import org.thoughtcrime.securesms.megaphone.Megaphones;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.notifications.MarkReadReceiver;
import org.thoughtcrime.securesms.notifications.profiles.NotificationProfile;
import org.thoughtcrime.securesms.payments.preferences.PaymentsActivity;
import org.thoughtcrime.securesms.payments.preferences.details.PaymentDetailsFragmentArgs;
import org.thoughtcrime.securesms.payments.preferences.details.PaymentDetailsParcelable;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.ratelimit.RecaptchaProofBottomSheetFragment;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.search.MessageResult;
import org.thoughtcrime.securesms.search.SearchResult;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.util.ActivityTransitionUtil;
import org.thoughtcrime.securesms.util.AppForegroundObserver;
import org.thoughtcrime.securesms.util.AppStartup;
import org.thoughtcrime.securesms.util.BottomSheetUtil;
import org.thoughtcrime.securesms.util.PlayStoreUtil;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.SignalProxyUtil;
import org.thoughtcrime.securesms.util.SnapToTopDataObserver;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.thoughtcrime.securesms.util.Stopwatch;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.WindowUtil;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.thoughtcrime.securesms.util.task.SnackbarAsyncTask;
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog;
import org.thoughtcrime.securesms.util.views.Stub;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper;
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import static android.app.Activity.RESULT_OK;

@SuppressLint("NewApi")
public class ConversationListFragment extends MainFragment implements ActionMode.Callback,
        ConversationListAdapter.OnConversationClickListener,
        ConversationListSearchAdapter.EventListener,
        MegaphoneActionController,
        InvitedToGroupDialog.DialogButtonListener, SmartTabLayout.TabProvider, MessageDialogCloseListener, IntroContactDialogFragment.IntroContactDialogDoneListener //AA+
{
  public static final short MESSAGE_REQUESTS_REQUEST_CODE_CREATE_NAME = 32562;
  public static final short SMS_ROLE_REQUEST_CODE = 32563;

  private static final String TAG = Log.tag(ConversationListFragment.class);

  private static final int MAXIMUM_PINNED_CONVERSATIONS = 4;

  private ActionMode                     actionMode;
  private View                           coordinator;
  private RecyclerView                   list;
  private Stub<ReminderView>             reminderView;
  private Stub<UnreadPaymentsView>       paymentNotificationView;
  private Stub<ViewGroup>                emptyState;
  private TextView                       searchEmptyState;
  private PulsingFloatingActionButton    fab;
  private PulsingFloatingActionButton    cameraFab;
  private View                           toolbarShadow;
  private ConversationListViewModel      viewModel;
  private RecyclerView.Adapter           activeAdapter;
  private ConversationListAdapter        defaultAdapter;
  private ConversationListSearchAdapter  searchAdapter;
  private StickyHeaderDecoration         searchAdapterDecoration;
  private Stub<ViewGroup>                megaphoneContainer;
  private SnapToTopDataObserver          snapToTopDataObserver;
  private Drawable                       archiveDrawable;
  private AppForegroundObserver.Listener appForegroundObserver;
  private VoiceNoteMediaControllerOwner  mediaControllerOwner;
  private Stub<FrameLayout>              voiceNotePlayerViewStub;
  private VoiceNotePlayerView            voiceNotePlayerView;
  private SignalBottomActionBar          bottomActionBar;

  protected ConversationListArchiveItemDecoration archiveDecoration;
  protected ConversationListItemAnimator          itemAnimator;

  //AA+ Fragment
  private GroupMode                         groupMode;
  private TextView                          emptyText;
  private FabOptions                        fabOptions;

  //AA+ Activity
  public static final String INTROCONTACT_MSGID   = "introcontact_msgid";
  public static final String GROUP_MODE           = "group_mode";

  static final public BadgeStyle badgeStyleUnread = new BadgeStyle(BadgeStyle.Style.DEFAULT, R.layout.menu_action_item_badge, Color.parseColor("#ff8833"), Color.parseColor("#ff7733"), Color.parseColor("#EEEEEE"));
  private   TintableImage tintableImgaeUnfacd;
  private   TextView      nickname;

  private ActivityResultChildViewListener activityResultChildViewListener = null;

  final   ToolTipsManager toolTipsManager = new ToolTipsManager();
  private ToolTip toolTip;
  private View          toolTipViewAnchor;

  ////////////

  private static ArrayList<ConversationListFragmentForPager> conversationListFragments = new ArrayList<>();
  private static ConversationListFragmentForPager conversationListFragmentOpen;
  private static ConversationListFragmentForPager conversationListFragmentInvited;
  private static ConversationListFragmentForPager conversationListFragmentLeft;
  private static ConversationListFragmentForPager conversationListFragmentGuardian;
  private static ConversationListFragmentForPager conversationListFragmentArchived;
  private static ConversationListFragmentForPager conversationListFragment;

  private SmartTabLayout smartTabLayout;
  FragmentPagerItemAdapterWithBehaviour pagerAdapter;
  private int pagerPosition = -1;

  private ViewPager viewPager;
  static private Pair<Integer, Integer>[] tipsCopy = new Pair [] {
          new Pair(Integer.valueOf(R.string.conversation_list_pager_open_conversations), Integer.valueOf(ToolTip.GRAVITY_RIGHT)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_invited_conversations),  Integer.valueOf(ToolTip.GRAVITY_CENTER)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_left_conversations),  Integer.valueOf(ToolTip.GRAVITY_LEFT)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_guardians),  Integer.valueOf(ToolTip.GRAVITY_LEFT)),
          new Pair(Integer.valueOf(R.string.conversation_list_pager_archived),  Integer.valueOf(ToolTip.GRAVITY_LEFT))
  };

  static HashMap<GroupMode, ConversationListViewModel> conversationLists = new HashMap<>();
  //////////

  public static ConversationListFragment newInstance() {
    return new ConversationListFragment();
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);

    if (context instanceof VoiceNoteMediaControllerOwner) {
      mediaControllerOwner = (VoiceNoteMediaControllerOwner) context;
    } else {
      throw new ClassCastException("Expected context to be a Listener");
    }
  }

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    setHasOptionsMenu(true);
//    startupStopwatch = new Stopwatch("startup");//xfrag

    int groupModeIndex = getArguments() != null ? getArguments().getInt(GROUP_MODE, 0) : 0;
    groupMode    = GroupMode.values()[groupModeIndex];//AA+
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    coordinator = view.findViewById(R.id.coordinator);//AA should be multiplexed? (replaced old R.id.constraint_layout)
//    list               = view.findViewById(R.id.list);//AA- multiplexed
    fab                       = view.findViewById(R.id.fab);
    cameraFab                 = view.findViewById(R.id.camera_fab);
    searchEmptyState          = view.findViewById(R.id.search_no_results);
    toolbarShadow             = view.findViewById(R.id.conversation_list_toolbar_shadow);
    bottomActionBar           = view.findViewById(R.id.conversation_list_bottom_action_bar);
    reminderView              = new Stub<>(view.findViewById(R.id.reminder));
    emptyState                = new Stub<>(view.findViewById(R.id.empty_state));
    megaphoneContainer        = new Stub<>(view.findViewById(R.id.megaphone_container));
    paymentNotificationView   = new Stub<>(view.findViewById(R.id.payments_notification));
    voiceNotePlayerViewStub   = new Stub<>(view.findViewById(R.id.voice_note_player));

    Toolbar toolbar = getToolbar(view);
    toolbar.setVisibility(View.VISIBLE);

    fab.show();
    cameraFab.show();

    archiveDecoration = new ConversationListArchiveItemDecoration(new ColorDrawable(getResources().getColor(R.color.conversation_list_archive_background_end)));
    itemAnimator      = new ConversationListItemAnimator();

    fab.setOnClickListener(v -> startActivity(new Intent(getActivity(), NewConversationActivity.class)));
    cameraFab.setOnClickListener(v -> {
      Permissions.with(this)
                 .request(Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.ConversationActivity_to_capture_photos_and_video_allow_signal_access_to_the_camera), R.drawable.ic_camera_24)
                 .withPermanentDenialDialog(getString(R.string.ConversationActivity_signal_needs_the_camera_permission_to_take_photos_or_video))
                 .onAllGranted(() -> startActivity(MediaSelectionActivity.camera(requireContext())))
                 .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.ConversationActivity_signal_needs_camera_permissions_to_take_photos_or_video, Toast.LENGTH_LONG).show())
                 .execute();
    });

    //AA+
    nickname = toolbar.findViewById(R.id.toolbar_nickname);
    Recipient thisRecipient = Recipient.self();
    String myNickname = TextUtils.isEmpty(thisRecipient.getNickname()) ?
                        TextSecurePreferences.getUfsrvNickname(requireContext()) :
                        thisRecipient.getNickname();
    if (myNickname.startsWith("*") && myNickname.length() == 1) myNickname = requireContext().getString(R.string.set_your_nickname);
    nickname.setText(myNickname);

    tintableImgaeUnfacd = toolbar.findViewById(R.id.toolbar_unfacd_name);
    tintableImgaeUnfacd.setImageDrawable(getResources().getDrawable(R.drawable.ic_unfacd_name_white));
    tintableImgaeUnfacd.setOnClickListener (v -> {
      getNavigator().goToProfileEditing();
    });

    //AA+
    emptyText   = view.findViewById(R.id.empty_text);
    fabOptions  = view.findViewById(R.id.fab_options);
    fabOptions.setOnClickListener(new FabOptionsClickListener());

    if (groupMode == GroupMode.GROUP_MODE_LOADER_INVITED)  {
      fab.hide();
      cameraFab.hide();
      emptyText.setText(R.string.conversation_list_fragment__you_have_not_received_any_invitations);
    } else if (groupMode == GroupMode.GROUP_MODE_LOADER_LEFT)  {
      fab.hide(); emptyText.setText(R.string.conversation_list_fragment__you_left_will_be_displayed_here);
      cameraFab.hide();
    } else {
      fab.hide();
      cameraFab.hide();
    }
    //

//    initializeListAdapters();//xfrag
    initializeViewModel();
    initialisePagerFragments();
    initialisePagerResources(view); //AA+
//    initializeTypingObserver();//xfrag
//    initializeSearchListener(list);//xfrag
    initializeVoiceNotePlayer();

    RatingManager.showRatingDialogIfNecessary(requireContext());

    TooltipCompat.setTooltipText(requireCallback().getSearchAction(), getText(R.string.SearchToolbar_search_for_conversations_contacts_and_messages));

    requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (!closeSearchIfOpen()) {
          if (!NavHostFragment.findNavController(ConversationListFragment.this).popBackStack()) {
            requireActivity().finish();
          }
        }
      }
    });
  }

  @Override
  public void onResume() {
    super.onResume();

    updateReminders();
    EventBus.getDefault().register(this);
    itemAnimator.disable();
    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this); //AA+

    if (Util.isDefaultSmsProvider(requireContext())) {
      InsightsLauncher.showInsightsModal(requireContext(), requireFragmentManager());
    }

    //AA xfrag
//    if ((!requireCallback().getSearchToolbar().resolved() || !requireCallback().getSearchToolbar().get().isVisible()) && list.getAdapter() != defaultAdapter) {
//      list.removeItemDecoration(searchAdapterDecoration);
//      setAdapter(defaultAdapter);
//    }

//    if (activeAdapter != null) {
//      activeAdapter.notifyItemRangeChanged(0, activeAdapter.getItemCount());
//    }

    SignalProxyUtil.startListeningToWebsocket();

    if (SignalStore.rateLimit().needsRecaptcha()) {
      Log.i(TAG, "Recaptcha required.");
      RecaptchaProofBottomSheetFragment.show(getChildFragmentManager());
    }

    Badge                              expiredBadge                       = SignalStore.donationsValues().getExpiredBadge();
    String                             subscriptionCancellationReason     = SignalStore.donationsValues().getUnexpectedSubscriptionCancelationReason();
    UnexpectedSubscriptionCancellation unexpectedSubscriptionCancellation = UnexpectedSubscriptionCancellation.fromStatus(subscriptionCancellationReason);

    if (expiredBadge != null) {
      SignalStore.donationsValues().setExpiredBadge(null);

      if (expiredBadge.isBoost() || !SignalStore.donationsValues().isUserManuallyCancelled()) {
        Log.w(TAG, "Displaying bottom sheet for an expired badge", true);
        ExpiredBadgeBottomSheetDialogFragment.show(expiredBadge, unexpectedSubscriptionCancellation, getParentFragmentManager());
      }
    } else if (unexpectedSubscriptionCancellation != null && !SignalStore.donationsValues().isUserManuallyCancelled() && SignalStore.donationsValues().getShowCantProcessDialog()) {
      Log.w(TAG, "Displaying bottom sheet for unexpected cancellation: " + unexpectedSubscriptionCancellation, true);
      new CantProcessSubscriptionPaymentBottomSheetDialogFragment().show(getChildFragmentManager(), BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
    } else if (unexpectedSubscriptionCancellation != null && SignalStore.donationsValues().isUserManuallyCancelled()) {
      Log.w(TAG, "Unexpected cancellation detected but not displaying dialog because user manually cancelled their subscription: " + unexpectedSubscriptionCancellation, true);
    } else if (unexpectedSubscriptionCancellation != null && !SignalStore.donationsValues().getShowCantProcessDialog()) {
      Log.w(TAG, "Unexpected cancellation detected but not displaying dialog because user has silenced it.", true);
    }

    //AA+
    Utils.actionItemdescribeLocation(requireCallback().getLocationBadge(), ufLocation.getInstance(), false);
    launchUnfacdIntroContactIfNecessary ();
  }

  @Override
  public void onStart() {
    super.onStart();
    ConversationFragment.prepare(requireContext());
    ApplicationDependencies.getAppForegroundObserver().addListener(appForegroundObserver);
    itemAnimator.disable();
  }

  @Override
  public void onPause() {
    super.onPause();

    fab.stopPulse();
    cameraFab.stopPulse();
    EventBus.getDefault().unregister(this);

    //AA+
    if (getArguments() != null) getArguments().remove(INTROCONTACT_MSGID);
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
  }

  @Override
  public void onStop() {
    super.onStop();
    ApplicationDependencies.getAppForegroundObserver().removeListener(appForegroundObserver);
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater)
  {
    menu.clear();

    inflater.inflate(R.menu.text_secure_normal, menu);
  }

  @Override
  public void onPrepareOptionsMenu(Menu menu) {
    menu.findItem(R.id.menu_insights).setVisible(Util.isDefaultSmsProvider(requireContext()));
    menu.findItem(R.id.menu_clear_passphrase).setVisible(!TextSecurePreferences.isPasswordDisabled(requireContext()));
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case R.id.menu_new_group:            handleCreateGroup();         return true;
      case R.id.menu_settings:             handleDisplaySettings();     return true;
      case R.id.menu_clear_passphrase:     handleClearPassphrase();     return true;
      case R.id.menu_mark_all_read:        handleMarkAllRead();         return true;
      case R.id.menu_invite:               handleInvite();              return true;
      case R.id.menu_insights:             handleInsights();            return true;
      case R.id.menu_notification_profile: handleNotificationProfile(); return true;
      //AA+
      case R.id.menu_intro_contact:        launchUnfacdIntroContactV2(Optional.empty()); return true;
      case R.id.menu_groups_nearby:        handleGroupsNearby(); return true;
      case R.id.menu_new_paired_group:     handleCreatePairedGroup(); return true;
    }

    return false;
  }

  private boolean closeSearchIfOpen()
  {
    if (list != null && ((requireCallback().getSearchToolbar()
                                           .resolved() && requireCallback().getSearchToolbar()
                                                                           .get()
                                                                           .isVisible()) || activeAdapter == searchAdapter)) { //AA+ list != null + extra '()'
      list.removeItemDecoration(searchAdapterDecoration);
      setAdapter(defaultAdapter, list);//AA+ list
      requireCallback().getSearchToolbar()
                       .get()
                       .collapse();
      requireCallback().onSearchClosed();
      return true;
    }

    return false;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (resultCode != RESULT_OK) {
      return;
    }

    if (requestCode == CreateKbsPinActivity.REQUEST_NEW_PIN) {
      Snackbar.make(fab, R.string.ConfirmKbsPinFragment__pin_created, Snackbar.LENGTH_LONG).setTextColor(Color.WHITE).show();
      viewModel.onMegaphoneCompleted(Megaphones.Event.PINS_FOR_ALL);
    } else {
      if (activityResultChildViewListener != null) activityResultChildViewListener.onActivityResultForListener(requestCode, resultCode, data);//AA+ pass to our listener
    }
  }

  @Override
  public void onConversationClicked(@NonNull ThreadRecord threadRecord) {
    hideKeyboard();
    getNavigator().goToConversation(threadRecord.getRecipient().getId(),
                                    threadRecord.getThreadId(),
                                    threadRecord.getDistributionType(),
                                    -1,
                                    threadRecord.getUfsrvFid());//AA+
  }

  @Override
  public void onShowArchiveClick() {
    NavHostFragment.findNavController(this)
                   .navigate(ConversationListFragmentDirections.actionConversationListFragmentToConversationListArchiveFragment());
  }
  
  @Override
  public void onContactClicked(@NonNull Recipient contact) {
    SimpleTask.run(getViewLifecycleOwner().getLifecycle(), () -> {
      return SignalDatabase.threads().getThreadIdIfExistsFor(contact.getId());
    }, threadId -> {
      hideKeyboard();
      getNavigator().goToConversation(contact.getId(),
                                      threadId,
                                      ThreadDatabase.DistributionTypes.DEFAULT,
                                      -1,
                                      contact.getUfsrvId());//AA+ fid
    });
  }

  @Override
  public void onMessageClicked(@NonNull MessageResult message) {
    SimpleTask.run(getViewLifecycleOwner().getLifecycle(), () -> {
      int startingPosition = SignalDatabase.mmsSms().getMessagePositionInConversation(message.getThreadId(), message.getReceivedTimestampMs());
      return Math.max(0, startingPosition);
    }, startingPosition -> {
      hideKeyboard();
      getNavigator().goToConversation(message.getConversationRecipient().getId(),
                                      message.getThreadId(),
                                      ThreadDatabase.DistributionTypes.DEFAULT,
                                      startingPosition,
                                      message.getConversationRecipient().getUfsrvId());//AA+
    });
  }

  @Override
  public void onMegaphoneNavigationRequested(@NonNull Intent intent) {
    startActivity(intent);
  }

  @Override
  public void onMegaphoneNavigationRequested(@NonNull Intent intent, int requestCode) {
    startActivityForResult(intent, requestCode);
  }

  @Override
  public void onMegaphoneToastRequested(@NonNull String string) {
    Snackbar.make(fab, string, Snackbar.LENGTH_LONG)
            .setTextColor(Color.WHITE)
            .show();
  }

  @Override
  public @NonNull Activity getMegaphoneActivity() {
    return requireActivity();
  }

  @Override
  public void onMegaphoneSnooze(@NonNull Megaphones.Event event) {
    viewModel.onMegaphoneSnoozed(event);
  }

  @Override
  public void onMegaphoneCompleted(@NonNull Megaphones.Event event) {
    viewModel.onMegaphoneCompleted(event);
  }

  @Override
  public void onMegaphoneDialogFragmentRequested(@NonNull DialogFragment dialogFragment) {
    dialogFragment.show(getChildFragmentManager(), "megaphone_dialog");
  }

  private void initializeReminderView() {
    reminderView.get().setOnDismissListener(this::updateReminders);
    reminderView.get().setOnActionClickListener(this::onReminderAction);
  }

  private void onReminderAction(@IdRes int reminderActionId) {
    if (reminderActionId == R.id.reminder_action_update_now) {
      PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext());
    }
  }

  private void hideKeyboard() {
    InputMethodManager imm = ServiceUtil.getInputMethodManager(requireContext());
    imm.hideSoftInputFromWindow(requireView().getWindowToken(), 0);
  }

  private void initializeSearchListener(RecyclerView list) {//AA+ list
    requireCallback().getSearchAction().setOnClickListener(v -> {
      requireCallback().onSearchOpened();
      requireCallback().getSearchToolbar().get().display(requireCallback().getSearchAction().getX() + (requireCallback().getSearchAction().getWidth() / 2.0f),
                                                         requireCallback().getSearchAction().getY() + (requireCallback().getSearchAction().getHeight() / 2.0f));

      requireCallback().getSearchToolbar().get().setListener(new SearchToolbar.SearchListener() {
        @Override
        public void onSearchTextChange(String text) {
          String trimmed = text.trim();

          viewModel.onSearchQueryUpdated(trimmed);

          if (trimmed.length() > 0) {
            if (activeAdapter != searchAdapter) {
              setAdapter(searchAdapter, list);
              list.removeItemDecoration(searchAdapterDecoration);
              list.addItemDecoration(searchAdapterDecoration);
            }
          } else {
            if (activeAdapter != defaultAdapter) {
              list.removeItemDecoration(searchAdapterDecoration);
              setAdapter(defaultAdapter, list);
            }
          }
        }

        @Override
        public void onSearchClosed() {
          list.removeItemDecoration(searchAdapterDecoration);
          setAdapter(defaultAdapter, list);
          requireCallback().onSearchClosed();
        }
      });
    });
  }

  //AA+ frag
  private void searchResume(RecyclerView list)
  {
    if ((!requireCallback().getSearchToolbar().resolved() || !requireCallback().getSearchToolbar().get().isVisible()) && list.getAdapter() != defaultAdapter) {
      list.removeItemDecoration(searchAdapterDecoration);
      setAdapter(defaultAdapter, list);
    }
  }

  //AA+ xfrag
  private void listTouchItem(RecyclerView list)
  {
    list.setLayoutManager(new LinearLayoutManager(requireActivity()));
    list.setItemAnimator(null/*itemAnimator*/);//AA- fixes crash when panning across tabs
    list.addOnScrollListener(new ScrollListener());
    list.addItemDecoration(archiveDecoration);

    //removed in https://github.com/signalapp/Signal-Android/commit/b621efa4a57d914b0f4eb39224c2eec4e74a56a7
//    CachedInflater.from(requireContext()).cacheUntilLimit(R.layout.conversation_list_item_view, list, 20);

//    snapToTopDataObserver = new SnapToTopDataObserver(list); //AA done in fragment init

    new ItemTouchHelper(new ArchiveListenerCallback(getResources().getColor(R.color.conversation_list_archive_background_start),
                                                    getResources().getColor(R.color.conversation_list_archive_background_end))).attachToRecyclerView(list);
  }

  private void initializeVoiceNotePlayer() {
    mediaControllerOwner.getVoiceNoteMediaController().getVoiceNotePlayerViewState().observe(getViewLifecycleOwner(), state -> {
      if (state.isPresent()) {
        requireVoiceNotePlayerView().setState(state.get());
        requireVoiceNotePlayerView().show();
      } else if (voiceNotePlayerViewStub.resolved()) {
        requireVoiceNotePlayerView().hide();
      }
    });
  }

  private @NonNull VoiceNotePlayerView requireVoiceNotePlayerView() {
    if (voiceNotePlayerView == null) {
      voiceNotePlayerView = voiceNotePlayerViewStub.get().findViewById(R.id.voice_note_player_view);
      voiceNotePlayerView.setListener(new VoiceNotePlayerViewListener());
    }

    return voiceNotePlayerView;
  }

  //xfrag done in fragment
 /* private void initializeListAdapters() {
   defaultAdapter          = new ConversationListAdapter(GlideApp.with(this), this);
    searchAdapter           = new ConversationListSearchAdapter(GlideApp.with(this), this, Locale.getDefault());
    searchAdapterDecoration = new StickyHeaderDecoration(searchAdapter, false, false, 0);

    setAdapter(defaultAdapter);

     defaultAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
       @Override
      public void onItemRangeInserted(int positionStart, int itemCount) {
        startupStopwatch.split("data-set");
        SignalLocalMetrics.ColdStart.onConversationListDataLoaded();
        defaultAdapter.unregisterAdapterDataObserver(this);
        list.post(() -> {
          AppStartup.getInstance().onCriticalRenderEventEnd();
          startupStopwatch.split("first-render");
          startupStopwatch.stop(TAG);
        });
      }
    });
  }*/

 //AA make sure context variables below are multiplexed by the fragment that owned their allocation
  @SuppressWarnings("rawtypes")
  private void setAdapter(@NonNull RecyclerView.Adapter adapter, RecyclerView list) {//AA+
    RecyclerView.Adapter oldAdapter = activeAdapter;

    activeAdapter = adapter;

    if (oldAdapter == activeAdapter) {
      return;
    }

    if (adapter instanceof ConversationListAdapter) {
      ((ConversationListAdapter) adapter).setPagingController(viewModel.getPagingController());
    }

    list.setAdapter(adapter);

    if (adapter == defaultAdapter) {
      defaultAdapter.registerAdapterDataObserver(snapToTopDataObserver);
    } else {
      defaultAdapter.unregisterAdapterDataObserver(snapToTopDataObserver);
    }
  }

  //xfrag
  /*private void initializeTypingObserver() {
    ApplicationDependencies.getTypingStatusRepository().getTypingThreads().observe(this, threadIds -> {
      if (threadIds == null) {
        threadIds = Collections.emptySet();
      }

      if (defaultAdapter != null) defaultAdapter.setTypingThreads(threadIds);//AA+ conditional
    });
  }*/

  protected boolean isArchived() {
    return false;
  }

  //xfrag done in fragment context in onResume()
  private void initializeViewModel() {

    //   ConversationListViewModel.Factory viewModelFactory = new ConversationListViewModel.Factory(isArchived(),
    //                                                                                               getString(R.string.note_to_self));
    //
    //    viewModel = new ViewModelProvider(this, viewModelFactory).get(ConversationListViewModel.class);//AA-

    //xfrag
   /* viewModel.getSearchResult().observe(getViewLifecycleOwner(), this::onSearchResultChanged);
    viewModel.getMegaphone().observe(getViewLifecycleOwner(), this::onMegaphoneChanged);
    viewModel.getConversationList().observe(getViewLifecycleOwner(), this::onConversationListChanged);
    viewModel.hasNoConversations().observe(getViewLifecycleOwner(), this::updateEmptyState);
    viewModel.getNotificationProfiles().observe(getViewLifecycleOwner(), profiles -> requireCallback().updateNotificationProfileStatus(profiles));
    viewModel.getPipeState().observe(getViewLifecycleOwner(), pipeState -> requireCallback().updateProxyStatus(pipeState));

    appForegroundObserver = new AppForegroundObserver.Listener() {
      @Override
      public void onForeground() {
        viewModel.onVisible();
      }

      @Override
      public void onBackground() { }
    };

    //AA+
    viewModel.getNickname().observe(this, result -> {
      nickname.setText(result);
    });*/

    appForegroundObserver = new AppForegroundObserver.Listener() {
      @Override
      public void onForeground() {
        if (viewModel != null)  viewModel.onVisible();//AA+ conditional
      }

      @Override
      public void onBackground() { }
    };

//    viewModel.getUnreadPaymentsLiveData().observe(getViewLifecycleOwner(), this::onUnreadPaymentsChanged);//xfrag

//    viewModel.getSelectedConversations().observe(getViewLifecycleOwner(), conversations -> {//xfrag
//      defaultAdapter.setSelectedConversations(conversations);
//      updateMultiSelectState();
//    });
  }

  private void onConversationListChanged(@NonNull List<Conversation> conversations) {
    LinearLayoutManager layoutManager    = (LinearLayoutManager) list.getLayoutManager();
    int                 firstVisibleItem = layoutManager != null ? layoutManager.findFirstCompletelyVisibleItemPosition() : -1;

    defaultAdapter.submitList(conversations, () -> {
      if (firstVisibleItem == 0) {
        list.scrollToPosition(0);
      }
      onPostSubmitList(conversations.size());
    });
  }

  private void onUnreadPaymentsChanged(@NonNull Optional<UnreadPayments> unreadPayments) {
    if (unreadPayments.isPresent()) {
      paymentNotificationView.get().setListener(new PaymentNotificationListener(unreadPayments.get()));
      paymentNotificationView.get().setUnreadPayments(unreadPayments.get());
      animatePaymentUnreadStatusIn();
    } else {
      animatePaymentUnreadStatusOut();
    }
  }

  private void animatePaymentUnreadStatusIn() {
    paymentNotificationView.get().setVisibility(View.VISIBLE);
    requireCallback().getUnreadPaymentsDot().animate().alpha(1);
  }

  private void animatePaymentUnreadStatusOut() {
    if (paymentNotificationView.resolved()) {
      paymentNotificationView.get().setVisibility(View.GONE);
    }

    requireCallback().getUnreadPaymentsDot().animate().alpha(0);
  }

  private void onSearchResultChanged(@Nullable SearchResult result) {
    result = result != null ? result : SearchResult.EMPTY;
    searchAdapter.updateResults(result);

    if (result.isEmpty() && activeAdapter == searchAdapter) {
      searchEmptyState.setText(getString(R.string.SearchFragment_no_results, result.getQuery()));
      searchEmptyState.setVisibility(View.VISIBLE);
    } else {
      searchEmptyState.setVisibility(View.GONE);
    }
  }

  private void onMegaphoneChanged(@Nullable Megaphone megaphone) {
    if (megaphone == null) {
      if (megaphoneContainer.resolved()) {
        megaphoneContainer.get().setVisibility(View.GONE);
        megaphoneContainer.get().removeAllViews();
      }
      return;
    }

    View view = MegaphoneViewBuilder.build(requireContext(), megaphone, this);

    megaphoneContainer.get().removeAllViews();

    if (view != null) {
      megaphoneContainer.get().addView(view);
      megaphoneContainer.get().setVisibility(View.VISIBLE);
    } else {
      megaphoneContainer.get().setVisibility(View.GONE);

      if (megaphone.getOnVisibleListener() != null) {
        megaphone.getOnVisibleListener().onEvent(megaphone, this);
      }
    }
    viewModel.onMegaphoneVisible(megaphone);
  }

  @SuppressLint("NewApi")
  private void updateReminders() {
    Context context = requireContext();

    SimpleTask.run(getViewLifecycleOwner().getLifecycle(), () -> {
      if (UnauthorizedReminder.isEligible(context)) {
        return Optional.of(new UnauthorizedReminder(context));
      } else if (ExpiredBuildReminder.isEligible()) {
        return Optional.of(new ExpiredBuildReminder(context));
      } else if (ServiceOutageReminder.isEligible(context)) {
        ApplicationDependencies.getJobManager().add(new ServiceOutageDetectionJob());
        return Optional.of(new ServiceOutageReminder(context));
      } else if (OutdatedBuildReminder.isEligible()) {
        return Optional.of(new OutdatedBuildReminder(context));
      } else if (PushRegistrationReminder.isEligible(context)) {
        return Optional.of((new PushRegistrationReminder(context)));
      } else if (DozeReminder.isEligible(context)) {
        return Optional.of(new DozeReminder(context));
      } else {
        return Optional.<Reminder>empty();
      }
    }, reminder -> {
      if (reminder.isPresent() && getActivity() != null && !isRemoving()) {
        if (!reminderView.resolved()) {
          initializeReminderView();
        }
        reminderView.get().showReminder(reminder.get());
      } else if (reminderView.resolved() && !reminder.isPresent()) {
        reminderView.get().hide();
      }
    });
  }

  private void handleCreateGroup() {
    getNavigator().goToGroupCreation();
  }

  private void handleDisplaySettings() {
    getNavigator().goToAppSettings();
  }

  private void handleClearPassphrase() {
    Intent intent = new Intent(requireActivity(), KeyCachingService.class);
    intent.setAction(KeyCachingService.CLEAR_KEY_ACTION);
    requireActivity().startService(intent);
  }

  private void handleMarkAllRead() {
    Context context = requireContext();

    SignalExecutors.BOUNDED.execute(() -> {
      List<MarkedMessageInfo> messageIds = SignalDatabase.threads().setAllThreadsRead();

       ApplicationDependencies.getMessageNotifier().updateNotification(context);
      MarkReadReceiver.process(context, messageIds);
    });
  }

  private void handleMarkAsRead(@NonNull Collection<Long> ids) {
    Context context = requireContext();

    SimpleTask.run(getViewLifecycleOwner().getLifecycle(), () -> {
      List<MarkedMessageInfo> messageIds = SignalDatabase.threads().setRead(ids, false);

      ApplicationDependencies.getMessageNotifier().updateNotification(context);
      MarkReadReceiver.process(context, messageIds);
      return null;
    }, none -> {
      endActionModeIfActive();
    });
  }

  private void handleMarkAsUnread(@NonNull Collection<Long> ids) {
    Context context = requireContext();

    SimpleTask.run(getViewLifecycleOwner().getLifecycle(), () -> {
      SignalDatabase.threads().setForcedUnread(ids);
      StorageSyncHelper.scheduleSyncForDataChange();
      return null;
    }, none -> {
      endActionModeIfActive();
    });
  }

  private void handleInvite() {
    getNavigator().goToInvite();
  }

  private void handleInsights() {
    getNavigator().goToInsights();
  }

  //AA+
  private void handleGroupsNearby() {
    getNavigator().goToGroupsNearby();
  }

  private void handleCreatePairedGroup() {
    getNavigator().goToPairedGroupCreation();
  }
  //

  private void handleNotificationProfile() {
    NotificationProfileSelectionFragment.show(getParentFragmentManager());
  }

  @SuppressLint("StaticFieldLeak")
  private void handleArchive(@NonNull Collection<Long> ids, boolean showProgress) {
    Set<Long> selectedConversations = new HashSet<>(ids);
    int       count                 = selectedConversations.size();
    String    snackBarTitle         = getResources().getQuantityString(getArchivedSnackbarTitleRes(), count, count);

    new SnackbarAsyncTask<Void>(getViewLifecycleOwner().getLifecycle(),
                                coordinator,
                                snackBarTitle,
                                getString(R.string.ConversationListFragment_undo),
                                getResources().getColor(R.color.amber_500),
                                Snackbar.LENGTH_LONG,
                                showProgress)
    {

      @Override
      protected void onPostExecute(Void result) {
        super.onPostExecute(result);
        endActionModeIfActive();
      }

      @Override
      protected void executeAction(@Nullable Void parameter) {
        archiveThreads(selectedConversations);
      }

      @Override
      protected void reverseAction(@Nullable Void parameter) {
        reverseArchiveThreads(selectedConversations);
      }
    }.executeOnExecutor(SignalExecutors.BOUNDED);
  }

  @SuppressLint("StaticFieldLeak")
  private void handleDelete(@NonNull Collection<Long> ids) {
    int                        conversationsCount = ids.size();
    MaterialAlertDialogBuilder alert              = new MaterialAlertDialogBuilder(requireActivity());
    Context                    context            = requireContext();

    alert.setTitle(context.getResources().getQuantityString(R.plurals.ConversationListFragment_delete_selected_conversations,
                                                            conversationsCount, conversationsCount));
    alert.setMessage(context.getResources().getQuantityString(R.plurals.ConversationListFragment_this_will_permanently_delete_all_n_selected_conversations,
                                                              conversationsCount, conversationsCount));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.delete, (dialog, which) -> {
      final Set<Long> selectedConversations = new HashSet<>(ids);

      if (!selectedConversations.isEmpty()) {
        new AsyncTask<Void, Void, Void>() {
          private ProgressDialog dialog;

          @Override
          protected void onPreExecute() {
            dialog = ProgressDialog.show(requireActivity(),
                                         context.getString(R.string.ConversationListFragment_deleting),
                                         context.getString(R.string.ConversationListFragment_deleting_selected_conversations),
                                         true, false);
          }

          @Override
          protected Void doInBackground(Void... params) {
            //AA+ we need to do this first as otherwise we lose trace of thread/group when model data is deleted below
            UfsrvFenceUtils.sendServerCommandFenceThreadDeleted(getContext(), selectedConversations);
            //AA-
//            SignalDatabase.threads().deleteConversations(selectedConversations);
//            ApplicationDependencies.getMessageNotifier().updateNotification(requireActivity());
            return null;
          }

          @Override
          protected void onPostExecute(Void result) {
            dialog.dismiss();
            endActionModeIfActive();
          }
        }.executeOnExecutor(SignalExecutors.BOUNDED);
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handlePin(@NonNull Collection<Conversation> conversations) {
    final Set<Long> toPin = new LinkedHashSet<>(Stream.of(conversations)
                                                        .filterNot(conversation -> conversation.getThreadRecord().isPinned())
                                                        .map(conversation -> conversation.getThreadRecord().getThreadId())
                                                        .toList());

    if (toPin.size() + viewModel.getPinnedCount() > MAXIMUM_PINNED_CONVERSATIONS) {
      Snackbar.make(fab,
                    getString(R.string.conversation_list__you_can_only_pin_up_to_d_chats, MAXIMUM_PINNED_CONVERSATIONS),
                    Snackbar.LENGTH_LONG)
              .setTextColor(Color.WHITE)
              .show();
      endActionModeIfActive();
      return;
    }

    SimpleTask.run(SignalExecutors.BOUNDED, () -> {
      ThreadDatabase db = SignalDatabase.threads();

      db.pinConversations(toPin);

      return null;
    }, unused -> {
      endActionModeIfActive();
    });
  }

  private void handleUnpin(@NonNull Collection<Long> ids) {
    SimpleTask.run(SignalExecutors.BOUNDED, () -> {
      ThreadDatabase db = SignalDatabase.threads();

      db.unpinConversations(ids);

      return null;
    }, unused -> {
      endActionModeIfActive();
    });
  }

  private void handleMute(@NonNull Collection<Conversation> conversations) {
    MuteDialog.show(requireContext(), until -> {
      updateMute(conversations, until);
    });
  }

  private void handleUnmute(@NonNull Collection<Conversation> conversations) {
    updateMute(conversations, 0);
  }

  private void updateMute(@NonNull Collection<Conversation> conversations, long until) {
    SimpleProgressDialog.DismissibleDialog dialog = SimpleProgressDialog.showDelayed(requireContext(), 250, 250);

    SimpleTask.run(SignalExecutors.BOUNDED, () -> {
      List<RecipientId> recipientIds = conversations.stream()
              .map(conversation -> conversation.getThreadRecord().getRecipient().live().get())
              .filter(r -> r.getMuteUntil() != until)
              .map(Recipient::getId)
              .collect(Collectors.toList());
      SignalDatabase.recipients().setMuted(recipientIds, until);
      return null;
    }, unused -> {
      endActionModeIfActive();
      dialog.dismiss();
    });
  }

  private void handleCreateConversation(long threadId, Recipient recipient, int distributionType) {
    long fid = SignalDatabase.threads().getFidForThreadId(threadId);
    SimpleTask.run(getLifecycle(), () -> {
      ChatWallpaper wallpaper = recipient.resolve().getWallpaper();
      if (wallpaper != null && !wallpaper.prefetch(requireContext(), 250)) {
        Log.w(TAG, "Failed to prefetch wallpaper.");
      }
      return null;
    }, (nothing) -> {
      getNavigator().goToConversation(recipient.getId(), threadId, distributionType, -1, fid);//AA+
    });
  }

  private void startActionMode() {
    actionMode = ((AppCompatActivity) getActivity()).startSupportActionMode(ConversationListFragment.this);
    ViewUtil.animateIn(bottomActionBar, bottomActionBar.getEnterAnimation());
    ViewUtil.fadeOut(fab, 250);
    ViewUtil.fadeOut(cameraFab, 250);
    if (megaphoneContainer.resolved()) {
      ViewUtil.fadeOut(megaphoneContainer.get(), 250);
    }
  }

  private void endActionModeIfActive() {
    if (actionMode != null) {
      endActionMode();
    }
  }

  private void endActionMode() {
    actionMode.finish();
    actionMode = null;
    ViewUtil.animateOut(bottomActionBar, bottomActionBar.getExitAnimation());
    ViewUtil.fadeIn(fab, 250);
    ViewUtil.fadeIn(cameraFab, 250);
    if (megaphoneContainer.resolved()) {
      ViewUtil.fadeIn(megaphoneContainer.get(), 250);
    }
  }

  void updateEmptyState(boolean isConversationEmpty) {
    if (pagerPosition == groupMode.ordinal()) {//AA+ conditional ie only update if list is currently visible
      if (isConversationEmpty) {
        Log.e(TAG, String.format("updateEmptyState(pagerPosition:'%d', groupMode:'%d'): Received an empty data set.", pagerPosition, groupMode.ordinal()));
        list.setVisibility(View.INVISIBLE);
        emptyState.get().setVisibility(View.VISIBLE);
        fab.startPulse(3 * 1000);
        cameraFab.startPulse(3 * 1000);

        SignalStore.onboarding().setShowNewGroup(true);
        SignalStore.onboarding().setShowInviteFriends(true);
      }
      else {
        list.setVisibility(View.VISIBLE);
        fab.stopPulse();
        cameraFab.stopPulse();

        if (emptyState.resolved()) {
          emptyState.get().setVisibility(View.GONE);
        }
      }
    } else {
      Log.i(TAG, String.format("(pagerPosition:'%d', groupMode:'%d'): Mismatched pagerPosition-groupMode", pagerPosition, groupMode.ordinal()));//AA+
    }
  }

  protected void onPostSubmitList(int conversationCount) {
    if (conversationCount >= 6 && (SignalStore.onboarding().shouldShowInviteFriends() || SignalStore.onboarding().shouldShowNewGroup())) {
      SignalStore.onboarding().clearAll();
      ApplicationDependencies.getMegaphoneRepository().markFinished(Megaphones.Event.ONBOARDING);
    }
  }

  @Override
  public void onConversationClick(@NonNull Conversation conversation) {
    if (actionMode == null) {
      handleCreateConversation(conversation.getThreadRecord().getThreadId(), conversation.getThreadRecord().getRecipient(), conversation.getThreadRecord().getDistributionType());
    } else {
      viewModel.toggleConversationSelected(conversation);

      if (viewModel.currentSelectedConversations().isEmpty()) {
        endActionModeIfActive();
      } else {
        updateMultiSelectState();
      }
    }
  }

  @Override
  public boolean onConversationLongClick(@NonNull Conversation conversation, @NonNull View view) {
    if (actionMode != null) {
      onConversationClick(conversation);
      return true;
    }

    view.setSelected(true);

    Collection<Long> id = Collections.singleton(conversation.getThreadRecord().getThreadId());

    List<ActionItem> items = new ArrayList<>();

    if (!conversation.getThreadRecord().isArchived()) {
      if (conversation.getThreadRecord().isRead()) {
        items.add(new ActionItem(R.drawable.ic_unread_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unread_plural, 1), () -> handleMarkAsUnread(id)));
      } else {
        items.add(new ActionItem(R.drawable.ic_read_24, getResources().getQuantityString(R.plurals.ConversationListFragment_read_plural, 1), () -> handleMarkAsRead(id)));
      }

      if (conversation.getThreadRecord().isPinned()) {
        items.add(new ActionItem(R.drawable.ic_unpin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unpin_plural, 1), () -> handleUnpin(id)));
      } else {
        items.add(new ActionItem(R.drawable.ic_pin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_pin_plural, 1), () -> handlePin(Collections.singleton(conversation))));
      }

      if (conversation.getThreadRecord().getRecipient().live().get().isMuted()) {
        items.add(new ActionItem(R.drawable.ic_unmute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unmute_plural, 1), () -> handleUnmute(Collections.singleton(conversation))));
      } else {
        items.add(new ActionItem(R.drawable.ic_mute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_mute_plural, 1), () -> handleMute(Collections.singleton(conversation))));
      }
    }

    items.add(new ActionItem(R.drawable.ic_select_24, getString(R.string.ConversationListFragment_select), () -> {
      viewModel.startSelection(conversation);
      startActionMode();
    }));

    if (conversation.getThreadRecord().isArchived()) {
      items.add(new ActionItem(R.drawable.ic_unarchive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unarchive_plural, 1), () -> handleArchive(id, false)));
    } else {
      items.add(new ActionItem(R.drawable.ic_archive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_archive_plural, 1), () -> handleArchive(id, false)));
    }

    items.add(new ActionItem(R.drawable.ic_delete_24, getResources().getQuantityString(R.plurals.ConversationListFragment_delete_plural, 1), () -> handleDelete(id)));

    new SignalContextMenu.Builder(view, list)
                         .offsetX(ViewUtil.dpToPx(12))
                         .offsetY(ViewUtil.dpToPx(12))
                         .onDismiss(() -> {
                           view.setSelected(false);
                           list.suppressLayout(false);
                         })
                         .show(items);

    list.suppressLayout(true);

    return true;
  }

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    mode.setTitle(requireContext().getResources().getQuantityString(R.plurals.ConversationListFragment_s_selected, 1, 1));
    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    updateMultiSelectState();
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    return true;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    viewModel.endSelection();

    if (Build.VERSION.SDK_INT >= 21) {
      TypedArray color = getActivity().getTheme().obtainStyledAttributes(new int[] {android.R.attr.statusBarColor});
      WindowUtil.setStatusBarColor(getActivity().getWindow(), color.getColor(0, Color.BLACK));
      color.recycle();
    }

    if (Build.VERSION.SDK_INT >= 23) {
      TypedArray lightStatusBarAttr = getActivity().getTheme().obtainStyledAttributes(new int[] {android.R.attr.windowLightStatusBar});
      int        current            = getActivity().getWindow().getDecorView().getSystemUiVisibility();
      int        statusBarMode      = lightStatusBarAttr.getBoolean(0, false) ? current | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                                                              : current & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;

      getActivity().getWindow().getDecorView().setSystemUiVisibility(statusBarMode);

      lightStatusBarAttr.recycle();
    }

    endActionModeIfActive();
  }

  ///AA+
  public void toggleActionFloatMenu(boolean toggleMenu, boolean toggleButton, boolean fastClose) {
//    boolean firstPage = true;//gDataPreferences.getViewPagersLastPage() == 0;
//
//    ((RelativeLayout) findViewById(R.id.fastclose)).setVisibility(fastClose?View.INVISIBLE:View.VISIBLE);
//
//    if(toggleMenu) {
//      if (actionFloatMenu.getVisibility() == View.INVISIBLE) {
//        if(firstPage) {
//          actionFloatMenu.setVisibility(actionFloatMenu.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//          actionFloatMenu.startAnimation(slideUp);
//        }
//      } else {
//        if(firstPage) {
//          actionFloatMenu.startAnimation(slideDown);
//          actionFloatMenu.setVisibility(actionFloatMenu.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//        } else {
//          actionFloatMenu.startAnimation(slideDown);
//          actionFloatMenu.setVisibility(View.INVISIBLE);
//        }
//      }
//    }
//    if(toggleButton) {
//      if (fab.getVisibility() == View.INVISIBLE) {
//        if(firstPage) {
//          fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//          fab.startAnimation(slideUp);
//        }
//      } else {
//        fab.startAnimation(slideDown);
//        fab.setVisibility(fab.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
//      }
//    }
//    if(actionFloatMenu.getVisibility() == View.VISIBLE &&  findViewById(R.id.fastclose).getVisibility() == View.VISIBLE) {
//      findViewById(R.id.overlay_gray).setVisibility(View.VISIBLE);
//      findViewById(R.id.overlay_gray).setOnClickListener(new View.OnClickListener() {
//        @Override
//        public void onClick(View view) {
//          toggleActionFloatMenu(true, false, false);
//        }
//      });
//      fab.setImageBitmap(((BitmapDrawable)getResources().getDrawable(R.drawable.ic_cancel_white_24dp)).getBitmap());
//    } else {
//      fab.setImageBitmap(((BitmapDrawable)getResources().getDrawable(R.drawable.ic_add_white_24dp)).getBitmap());
//      findViewById(R.id.overlay_gray).setVisibility(View.INVISIBLE);
//    }
  }

  private FragmentPagerItems initialisePagerItems ()
  {
    FragmentPagerItems.Creator itemsCreator = FragmentPagerItems.with(requireActivity())
            .add("OPEN", conversationListFragmentOpen.getClass())
            .add("INVITED", conversationListFragmentInvited.getClass())
            .add("LEFT", conversationListFragmentLeft.getClass());
    if (SignalDatabase.recipients().getGuardiansCount(RecipientDatabase.GuardianStatus.LINKED) > 0) {
      itemsCreator.add("GUARDIAN", conversationListFragmentGuardian.getClass());
    }

    if (SignalDatabase.threads().getArchivedConversationListCount() > 0) {
      itemsCreator.add("ARCHIVED", conversationListFragmentArchived.getClass());
    }

    return  itemsCreator.create();
  }

  private void initialisePagerFragments()
  {
    conversationListFragmentOpen    =  ConversationListFragmentForPager.newInstance(this, Locale.getDefault(), GroupMode.GROUP_MODE_LOADER_OPEN);
    conversationListFragmentInvited =  ConversationListFragmentForPager.newInstance(this, Locale.getDefault(), GroupMode.GROUP_MODE_LOADER_INVITED);
    conversationListFragmentLeft    =  ConversationListFragmentForPager.newInstance(this, Locale.getDefault(), GroupMode.GROUP_MODE_LOADER_LEFT);
    conversationListFragments.add(conversationListFragmentOpen);
    conversationListFragments.add(conversationListFragmentInvited);
    conversationListFragments.add(conversationListFragmentLeft);

    if (SignalDatabase.recipients().getGuardiansCount(RecipientDatabase.GuardianStatus.LINKED) > 0) {
      conversationListFragmentGuardian    =  ConversationListFragmentForPager.newInstance(this, Locale.getDefault(), GroupMode.GROUP_MODE_LOADER_GUARDIAN);
      conversationListFragments.add(conversationListFragmentGuardian);
    }

    if (SignalDatabase.threads().getArchivedConversationListCount() > 0) {
      conversationListFragmentArchived    =  ConversationListFragmentForPager.newInstance(this, Locale.getDefault(), GroupMode.GROUP_MODE_LOADER_ARCHIVED);
      conversationListFragments.add(conversationListFragmentArchived);
    }
  }

  private void initialisePagerResources (View view) {
    //sets BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT property for fragments inside FragmentPagerAdapter so only visible fragment get onResume
    pagerAdapter = new FragmentPagerItemAdapterWithBehaviour(getChildFragmentManager(), initialisePagerItems()) {
      @Override
      public int getItemPosition(Object object) {
        return POSITION_NONE;
      }

      @Override
      public Fragment getItem(int i) {
        return conversationListFragments.get(i);
      }
    };

    viewPager = view.findViewById(R.id.pager);
    viewPager.setAdapter(pagerAdapter);
    viewPager.setCurrentItem(TextSecurePreferences.getLastSelectedPager(requireContext()));
    conversationListFragment = conversationListFragments.get(TextSecurePreferences.getLastSelectedPager(requireContext()));

    smartTabLayout = view.findViewById(R.id.sliding_tabs);
    smartTabLayout.setCustomTabView(this);//IMPORTANT: this must be called before  smartTabLayout.setViewPager(viewPager);
    smartTabLayout.setViewPager(viewPager);

    smartTabLayout.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
      @Override
      public void onPageScrolled(int i, float v, int i2) {

      }

      @Override
      public void onPageSelected(int i) {
        TextSecurePreferences.setLastSelectedPager(requireContext(), i);
        toggleActionFloatMenu(i != 0, true, false);

        View tab = smartTabLayout.getTabAt(i);
        View mark = tab.findViewById(R.id.custom_tab_notification_mark);

        if (getUnreadCountForTab(GroupMode.values()[i]) > 0)   mark.setVisibility(View.VISIBLE);

        if (TextSecurePreferences.getTooltipWithIdx (requireContext(), i)) {
          displayPagerTooltip(requireContext(), (ViewGroup) tab.getParent().getParent().getParent(),
                              tab, requireContext().getString(tipsCopy[i].first().intValue()),
                              tipsCopy[i].second().intValue());

          TextSecurePreferences.setTooltipWithIdx (requireContext(), i, false);
        }
      }

      @Override
      public void onPageScrollStateChanged(int i) {

      }
    });
  }

  @Override
  public View createTabView(ViewGroup container, int position, PagerAdapter adapter) {
    LayoutInflater inflater = LayoutInflater.from(container.getContext());
    Resources res = container.getContext().getResources();
    View tab = inflater.inflate(R.layout.uf_conversation_list_pager_tabs, container, false);
    View mark = tab.findViewById(R.id.custom_tab_notification_mark);
    mark.setVisibility(View.GONE);
    ImageView icon = tab.findViewById(R.id.custom_tab_icon);

    switch (position) {
      case 0:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_received_made_black_24dp));
        if (getUnreadCountForTab(GroupMode.values()[position]) > 0)   mark.setVisibility(View.VISIBLE);
        break;

      case 1:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_received_black_24dp));
        if (getUnreadCountForTab(GroupMode.values()[position]) > 0)   mark.setVisibility(View.VISIBLE);
        break;

      case 2:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_call_made_black_24dp));
        if (getUnreadCountForTab(GroupMode.values()[position]) > 0)   mark.setVisibility(View.VISIBLE);
        break;

      case 3:
        icon.setImageDrawable(res.getDrawable(R.drawable.ic_eye_outline_black_24dp));
        if (getUnreadCountForTab(GroupMode.values()[position]) > 0)   mark.setVisibility(View.VISIBLE);
        break;

      case 4:
        icon.setImageDrawable(res.getDrawable(R.drawable.outline_indeterminate_check_box_black_24));
        if (getUnreadCountForTab(GroupMode.values()[position]) > 0)   mark.setVisibility(View.VISIBLE);
        break;
      default:
        throw new IllegalStateException("Invalid position: " + position);
    }

    return tab;
  }

  //todo: ugly hack refactor with object based implementation
  long getUnreadCountForTab (GroupMode groupMode)
  {
    long unreadCount = 0;

    switch  (groupMode)
    {
      case GROUP_MODE_LOADER_OPEN:
        unreadCount = SignalDatabase.threads().getOpenConversationListUnreadCount();
        break;

      case GROUP_MODE_LOADER_INVITED:
        unreadCount = SignalDatabase.threads().getInvitedConversationListUnreadCount();
        break;

      case GROUP_MODE_LOADER_LEFT:
        unreadCount = SignalDatabase.threads().getLeftConversationListUnreadCount();
        break;

      case GROUP_MODE_LOADER_ARCHIVED:
        unreadCount = SignalDatabase.threads().getArchivedConversationListCount();
        break;
    }

    return unreadCount;
  }

  public void displayPagerTooltip (Context context, ViewGroup viewGroup, final View viewAnchor, String tip, int alignment) {
    if (Build.VERSION.SDK_INT > 15) {
      if (toolTipViewAnchor != null) {
        toolTipsManager.findAndDismiss(toolTipViewAnchor);

        if (viewAnchor == toolTipViewAnchor)  {
          toolTipViewAnchor = null;
          return;
        }
      }

      toolTipViewAnchor = viewAnchor;
      toolTip = new ToolTip.Builder(context, toolTipViewAnchor, viewGroup,
                                    tip,
                                    ToolTip.POSITION_BELOW)
              .setBackgroundColor(getResources().getColor(R.color.core_ultramarine_dark))
              .setAlign(alignment)
              .build();

      toolTipsManager.show(toolTip);

      viewAnchor.postDelayed(new Runnable() {
        @Override
        public void run() {
          if (toolTipViewAnchor != null) {
            toolTipsManager.findAndDismiss(toolTipViewAnchor);
            toolTipViewAnchor = null;
          }
        }
      }, 10000);
    }

  }

  private void launchUnfacdIntroContactIfNecessary ()
  {
    if (isLaunchedWithIntroContact()) {
      Optional<Pair <Long, IntroContactDescriptor>> descriptor = descriptorFromIntent();
      if (descriptor.isPresent()) {
        launchUnfacdIntroContactV2(descriptor);
      } else {
        Log.w(TAG, String.format(Locale.getDefault(), "launchUnfacdIntroContactIfNecessary: Launched with IntroContactDescriptor '%d', but could not retrieve db record", getArguments().getLong(INTROCONTACT_MSGID, -1)));
      }
    }
  }

  void launchUnfacdIntroContactV2(Optional<Pair<Long, IntroContactDescriptor>> descriptor)
  {
    IntroContactDialogFragment dialogFragment = new IntroContactDialogFragment();

    Bundle bundle = new Bundle();
    bundle.putBoolean("notAlertDialog", true);
    if (descriptor.isPresent()) {
      bundle.putLong("msg_id", descriptor.get().first());
      bundle.putParcelable("descriptor", descriptor.get().second());
    }

    dialogFragment.setArguments(bundle);
    dialogFragment.setCloseListener(this);

    FragmentTransaction ft = requireActivity().getSupportFragmentManager().beginTransaction();
    Fragment prev = requireActivity().getSupportFragmentManager().findFragmentByTag("intro_dialog");
    if (prev != null) {
      ft.remove(prev);
    }
    ft.addToBackStack(null);

    dialogFragment.show(ft, "intro_dialog");
  }

  @Override
  public void onIntroContactDialogDone(String inputText) {//intro dialog  handler

//    if (TextUtils.isEmpty(inputText)) {
//      textView.setText("Email was not entered");
//    } else
//      textView.setText("Email entered: " + inputText);
  }

  private boolean isLaunchedWithIntroContact()
  {
    return getNavigator().isLaunchedWithIntroContact();
  }

  @Override
  public void onMessageDialogClose(int button)
  {
    switch (button) {
      case 1:
      case 0:
        if (isLaunchedWithIntroContact()) {
//          if (!isTaskRoot ()) super.onBackPressed();

        }
        break;
    }
  }

  // InvitedToGroupDialog.DialogButtonListener implementation
  //user responded to the prompt around invitation to joing a group
  @Override
  public int onDialogButtonClicked(int button, Recipient recipient, long threadId)
  {
    long timeNowInMillis = System.currentTimeMillis();
    GroupId groupId = recipient.requireGroupId();
    SignalDatabase.groups().setActive(groupId, false);

    //AA+
    GroupDatabase.GroupRecord groupRec = SignalDatabase.groups().getGroupByGroupId(groupId);
    //AA+  Fencecommand context
    SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandJoinInvitationResponse(ApplicationContext.getInstance(), groupRec.getFid(), timeNowInMillis, true);
    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //
    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                       .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                       .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                                       .setFenceMessage(fenceCommandBuilder.build())//AA+
                                                                       .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandBuilder.build());//AA+ ufsrvcommand
    MessageSender.send(requireContext(), outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    SignalDatabase.groups().markGroupMode(groupRec.getFid(), GroupDatabase.GROUP_MODE_INVITATION);
    return 0;
  }

  void refreshNickname()
  {
    Recipient thisRecipient = Recipient.self();
    nickname.setText(TextUtils.isEmpty(thisRecipient.getNickname())?"*":thisRecipient.getNickname());

  }

  private void handleInvalidSession()
  {
    AlertDialog.Builder dialog = new AlertDialog.Builder(requireActivity());
    dialog.setTitle(R.string.StateSync_invalid_session);
    dialog.setMessage(R.string.StateSync_unfacd_service_invalidated_this_copy_of_the_app);
    dialog.setPositiveButton(R.string.StateSync_invalid_session_cancel, null);
    dialog.setCancelable(false);
    AlertDialog shownDialog = dialog.show();
    Button button = shownDialog.getButton(AlertDialog.BUTTON_POSITIVE);
    button.setEnabled(false);
  }

  public static ConversationListFragment newInstance(@Nullable Locale locale, GroupMode groupMode) {
    ConversationListFragment fragment = new ConversationListFragment();
    Bundle args = new Bundle();
    args.putSerializable(PassphraseRequiredActivity.LOCALE_EXTRA, locale);
    args.putInt(GROUP_MODE, groupMode.ordinal());
    fragment.setArguments(args);
    return fragment;
  }

  private class FabOptionsClickListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      switch (v.getId()) {
        case R.id.faboptions_camera_first:
          Permissions.with(requireActivity())
                  .request(Manifest.permission.CAMERA)
                  .ifNecessary()
                  .withRationaleDialog(getString(R.string.ConversationActivity_to_capture_photos_and_video_allow_signal_access_to_the_camera), R.drawable.ic_photo_camera_white_48dp)
                  .withPermanentDenialDialog(getString(R.string.ConversationActivity_signal_needs_the_camera_permission_to_take_photos_or_video))
                  .onAllGranted(() -> startActivity(MediaSelectionActivity.camera(requireContext())))
                  .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.ConversationActivity_signal_needs_camera_permissions_to_take_photos_or_video, Toast.LENGTH_LONG).show())
                  .execute();
          break;

        case R.id.faboptions_private_group:
          startActivity(new Intent(getActivity(), NewConversationActivity.class));
          break;

        case R.id.faboptions_new_group:
          startActivity(CreateGroupActivity.newIntent(getActivity()));
          break;

        case R.id.faboptions_groups_nearby:
          askForPermissionIfNeededAndLaunchFenceMap();
          break;

        case R.id.faboptions_intro_contact:
          launchUnfacdIntroContactV2(Optional.empty());
          break;

        case R.id.faboptions_app_settings:
          getNavigator().goToAppSettings();
          break;

        default:
          // no-op
      }
    }
  }

  private void askForPermissionIfNeededAndLaunchFenceMap() {
     final short CODE_UNSPECIFIED = 1;
    Permissions.with(this)
            .request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .ifNecessary()
            .onAllGranted(() -> {
              startActivityForResult(new Intent(getActivity(), FenceMapActivity.class), CODE_UNSPECIFIED);
              ActivityTransitionUtil.setSlideInTransition(requireActivity());
            })
            .withRationaleDialog(getString(R.string.unfacd_needs_access_to_your_location_to_allow_discovery_of_local_groups_and_events),
                                 R.drawable.ic_explore_white_48dp)
            .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.unfacd_needs_access_to_your_location_to_allow_discovery_of_local_groups_and_events, Toast.LENGTH_SHORT)
                    .show())
            .execute();
  }
  //////

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void onEvent(ReminderUpdateEvent event) {
    updateReminders();
  }

  //AA+
  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventNotificationWSConnection event){
    Log.d(TAG, String.format("AppEventNotificationWSConnection: CONNECTON STATE IS '%b'", event.getOnOffState()));
    if (event.getOnOffState())   tintableImgaeUnfacd.setSelected(true);
    else {
      tintableImgaeUnfacd.setSelected(false);
      Utils.shakeView(tintableImgaeUnfacd, 20, 0);

      tintableImgaeUnfacd.postDelayed(new Runnable() {
        @Override
        public void run() {
          //toolTipsManager.findAndDismiss(viewAnchor);
        }
      }, 10000);
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventMessageNotification event){
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventConversationListNotification event)
  {
    for (GroupMode groupModeValue : GroupMode.values()) {
      View tab = smartTabLayout.getTabAt(groupModeValue.ordinal());
      if (tab != null) {
        View mark = tab.findViewById(R.id.custom_tab_notification_mark);

        if (getUnreadCountForTab(GroupMode.values()[groupModeValue.ordinal()]) > 0)
          mark.setVisibility(View.VISIBLE);
        else mark.setVisibility(View.GONE);
      }
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(LocationV1SystemEvent event){
    //msgbus posts too early, before view were initialised
    if (requireCallback().getLocationBadge() != null) {
      final ufLocation mylocation = ((ufLocation) (event.getEventData()));
      Utils.actionItemdescribeLocation(requireCallback().getLocationBadge(), mylocation, false);
    }
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(LocationV1 event){
    //Utils.actionItemdescribeLocation(badgeLocationView, ((ufLocation) event.getEventData()), true);
    Log.d(TAG, "LocationV1: eventName: "+event.getEventName());
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(StateSyncV1SystemEvent event){
    JsonEntityStateSync stateSync = (JsonEntityStateSync)event.getEventData();
    if (stateSync.getSessionState() > 0) {
      handleInvalidSession();
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventPrefNickname event)
  {
    if (event.getUid().longValue() == TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
      refreshNickname();
      getNavigator().getActivity().postTimedNotification(getString(R.string.sticky_notification_your_nickname_successfully_changed));
      Log.d(TAG, "AppEventPrefNickname: own nickname: " + event.getNickname());
    } else {
      requireActivity().runOnUiThread(() -> defaultAdapter.notifyDataSetChanged());
      Log.d(TAG, "AppEventPrefNickname: uid: " + event.getUid());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventPrefUserAvatar event)
  {
    if (event.getUid().longValue() == TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
      getNavigator().getActivity().postTimedNotification(getString(R.string.sticky_notification_your_avatar_successfully_changed));
    } else {
      //other user todo: not needed? causes exception as conversationListFragment.getListAdapter()returns null
//      ConversationListActivity.this.runOnUiThread(() -> conversationListFragment.getListAdapter().notifyDataSetChanged());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventPrefUserReadReceiptShared event)
  {
    if (event.getIsEnabled()) {
      getNavigator().getActivity().postNotification(getString(R.string.sticky_notification_user_enabled_read_receipts_with_you, event.getRecipient().getDisplayName()), true);
    } else {
      getNavigator().getActivity().postNotification(getString(R.string.sticky_notification_user_disabled_read_receipts_with_you, event.getRecipient().getDisplayName()), true);
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventIntroContact event)
  {
    if (event.getStatus() == SignalServiceProtos.CommandArgs.SYNCED) {
      launchUnfacdIntroContactV2(Optional.of(new Pair(event.getMsgId(), event.getContactDescriptor())));
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }
  //AA+ intro msg
  public interface ActivityResultChildViewListener {
    void onActivityResultForListener (int requestCode, int resultCode, Intent data);
  }

  public void setActivityResultChildViewListener (ActivityResultChildViewListener listener) {
    this.activityResultChildViewListener = listener;
  }

  private Optional<Pair<Long, IntroContactDescriptor>> descriptorFromIntent() {
    return SignalDatabase.unfacdIntroContacts().getIntroContact(getArguments().getLong(INTROCONTACT_MSGID, -1));
  }
  //

  @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
  public void onEvent(MessageSender.MessageSentEvent event) {
    EventBus.getDefault().removeStickyEvent(event);
    closeSearchIfOpen();
  }

  //AA+ PORTING NOTE: replicated below in fragments with 'parent' references added and extra method params
  private void updateMultiSelectState() {
    int     count       = viewModel.currentSelectedConversations().size();
    boolean hasUnread   = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().isRead());
    boolean hasUnpinned = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().isPinned());
    boolean hasUnmuted  = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().getRecipient().live().get().isMuted());
    boolean canPin      = viewModel.getPinnedCount() < MAXIMUM_PINNED_CONVERSATIONS;

    if (actionMode != null) {
      actionMode.setTitle(requireContext().getResources().getQuantityString(R.plurals.ConversationListFragment_s_selected, count, count));
    }

    List<ActionItem> items = new ArrayList<>();

    Set<Long> selectionIds = viewModel.currentSelectedConversations()
                                      .stream()
                                      .map(conversation -> conversation.getThreadRecord().getThreadId())
                                      .collect(Collectors.toSet());

    if (hasUnread) {
      items.add(new ActionItem(R.drawable.ic_read_24, getResources().getQuantityString(R.plurals.ConversationListFragment_read_plural, count), () -> handleMarkAsRead(selectionIds)));
    } else {
      items.add(new ActionItem(R.drawable.ic_unread_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unread_plural, count), () -> handleMarkAsUnread(selectionIds)));
    }

    if (!isArchived() && hasUnpinned && canPin) {
      items.add(new ActionItem(R.drawable.ic_pin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_pin_plural, count), () -> handlePin(viewModel.currentSelectedConversations())));
    } else if (!isArchived() && !hasUnpinned) {
      items.add(new ActionItem(R.drawable.ic_unpin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unpin_plural, count), () -> handleUnpin(selectionIds)));
    }

    if (isArchived()) {
      items.add(new ActionItem(R.drawable.ic_unarchive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unarchive_plural, count), () -> handleArchive(selectionIds, true)));
    } else {
      items.add(new ActionItem(R.drawable.ic_archive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_archive_plural, count), () -> handleArchive(selectionIds, true)));
    }

    items.add(new ActionItem(R.drawable.ic_delete_24, getResources().getQuantityString(R.plurals.ConversationListFragment_delete_plural, count), () -> handleDelete(selectionIds)));

    if (hasUnmuted) {
      items.add(new ActionItem(R.drawable.ic_mute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_mute_plural, count), () -> handleMute(viewModel.currentSelectedConversations())));
    } else {
      items.add(new ActionItem(R.drawable.ic_unmute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unmute_plural, count), () -> handleUnmute(viewModel.currentSelectedConversations())));
    }

    items.add(new ActionItem(R.drawable.ic_select_24, getString(R.string.ConversationListFragment_select_all), viewModel::onSelectAllClick));

    bottomActionBar.setItems(items);
  }

  protected Callback requireCallback() {
    return ((Callback) getParentFragment().getParentFragment());
  }

  protected Toolbar getToolbar(@NonNull View rootView) {
    return requireCallback().getToolbar();
  }

  protected @PluralsRes int getArchivedSnackbarTitleRes() {
    return R.plurals.ConversationListFragment_conversations_archived;
  }

  protected @DrawableRes int getArchiveIconRes() {
    return R.drawable.ic_archive_24;
  }

  @WorkerThread
  protected void archiveThreads(Set<Long> threadIds) {
    SignalDatabase.threads().setArchived(threadIds, true);
  }

  @WorkerThread
  protected void reverseArchiveThreads(Set<Long> threadIds) {
    SignalDatabase.threads().setArchived(threadIds, false);
  }

  @SuppressLint("StaticFieldLeak")
  protected void onItemSwiped(long threadId, int unreadCount) {
    archiveDecoration.onArchiveStarted();
    itemAnimator.enable();

    new SnackbarAsyncTask<Long>(getViewLifecycleOwner().getLifecycle(),
                                coordinator,
                                getResources().getQuantityString(R.plurals.ConversationListFragment_conversations_archived, 1, 1),
                                getString(R.string.ConversationListFragment_undo),
                                getResources().getColor(R.color.amber_500),
                                Snackbar.LENGTH_LONG,
                                false)
    {
      private final ThreadDatabase threadDatabase = SignalDatabase.threads();

      private List<Long> pinnedThreadIds;

      @Override
      protected void executeAction(@Nullable Long parameter) {
        Context context = requireActivity();

        pinnedThreadIds = threadDatabase.getPinnedThreadIds();
        threadDatabase.archiveConversation(threadId);

        if (unreadCount > 0) {
          List<MarkedMessageInfo> messageIds = threadDatabase.setRead(threadId, false);
          ApplicationDependencies.getMessageNotifier().updateNotification(context);
          MarkReadReceiver.process(context, messageIds);
        }
      }

      @Override
      protected void reverseAction(@Nullable Long parameter) {
        Context context = requireActivity();

        threadDatabase.unarchiveConversation(threadId);
        threadDatabase.restorePins(pinnedThreadIds);

        if (unreadCount > 0) {
          threadDatabase.incrementUnread(threadId, unreadCount);
          ApplicationDependencies.getMessageNotifier().updateNotification(context);
        }
      }
    }.executeOnExecutor(SignalExecutors.BOUNDED, threadId);
  }

  private class PaymentNotificationListener implements UnreadPaymentsView.Listener {

    private final UnreadPayments unreadPayments;

    private PaymentNotificationListener(@NonNull UnreadPayments unreadPayments) {
      this.unreadPayments = unreadPayments;
    }

    @Override
    public void onOpenPaymentsNotificationClicked() {
      UUID paymentId = unreadPayments.getPaymentUuid();

      if (paymentId == null) {
        goToPaymentsHome();
      } else {
        goToSinglePayment(paymentId);
      }
    }

    @Override
    public void onClosePaymentsNotificationClicked() {
      viewModel.onUnreadPaymentsClosed();
    }

    private void goToPaymentsHome() {
      startActivity(new Intent(requireContext(), PaymentsActivity.class));
    }

    private void goToSinglePayment(@NonNull UUID paymentId) {
      Intent intent = new Intent(requireContext(), PaymentsActivity.class);

      intent.putExtra(PaymentsActivity.EXTRA_PAYMENTS_STARTING_ACTION, R.id.action_directly_to_paymentDetails);
      intent.putExtra(PaymentsActivity.EXTRA_STARTING_ARGUMENTS, new PaymentDetailsFragmentArgs.Builder(PaymentDetailsParcelable.forUuid(paymentId)).build().toBundle());

      startActivity(intent);
    }
  }

  private class ArchiveListenerCallback extends ItemTouchHelper.SimpleCallback {

    private static final long SWIPE_ANIMATION_DURATION = 175;

    private static final float MIN_ICON_SCALE = 0.85f;
    private static final float MAX_ICON_SCALE = 1f;

    private final int archiveColorStart;
    private final int archiveColorEnd;

    private final float ESCAPE_VELOCITY    = ViewUtil.dpToPx(1000);
    private final float VELOCITY_THRESHOLD = ViewUtil.dpToPx(1000);

    private WeakReference<RecyclerView.ViewHolder> lastTouched;

    ArchiveListenerCallback(@ColorInt int archiveColorStart, @ColorInt int archiveColorEnd) {
      super(0, ItemTouchHelper.END);
      this.archiveColorStart = archiveColorStart;
      this.archiveColorEnd   = archiveColorEnd;
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target)
    {
      return false;
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
      return Math.min(ESCAPE_VELOCITY, VELOCITY_THRESHOLD);
    }

    @Override
    public float getSwipeVelocityThreshold(float defaultValue) {
      return VELOCITY_THRESHOLD;
    }

    @Override
    public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      if (viewHolder.itemView instanceof ConversationListItemAction          ||
              viewHolder instanceof ConversationListAdapter.HeaderViewHolder ||
              actionMode != null                                             ||
              viewHolder.itemView.isSelected()                               ||
              activeAdapter == searchAdapter)
      {
        return 0;
      }

      lastTouched = new WeakReference<>(viewHolder);

      return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      if (lastTouched != null) {
        Log.w(TAG, "Falling back to slower onSwiped() event.");
        onTrueSwipe(viewHolder);
        lastTouched = null;
      }
    }

    @Override
    public long getAnimationDuration(@NonNull RecyclerView recyclerView, int animationType, float animateDx, float animateDy) {
      if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS && lastTouched != null && lastTouched.get() != null) {
        onTrueSwipe(lastTouched.get());
        lastTouched = null;
      } else if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
        lastTouched = null;
      }

      return SWIPE_ANIMATION_DURATION;
    }

    private void onTrueSwipe(RecyclerView.ViewHolder viewHolder) {
      final long threadId    = ((ConversationListItem)viewHolder.itemView).getThreadId();
      final int  unreadCount = ((ConversationListItem)viewHolder.itemView).getUnreadCount();

      onItemSwiped(threadId, unreadCount);
    }

    @Override
    public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState,
                            boolean isCurrentlyActive)
    {
      if (viewHolder.itemView instanceof ConversationListItemInboxZero) return;
      float absoluteDx = Math.abs(dX);

      if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
        Resources resources       = getResources();
        View      itemView        = viewHolder.itemView;
        float     percentDx       = absoluteDx / viewHolder.itemView.getWidth();
        int       color           = ArgbEvaluatorCompat.getInstance().evaluate(Math.min(1f, percentDx * (1 / 0.25f)), archiveColorStart, archiveColorEnd);
        float     scaleStartPoint = DimensionUnit.DP.toPixels(48f);
        float     scaleEndPoint   = DimensionUnit.DP.toPixels(96f);

        float scale;
        if (absoluteDx < scaleStartPoint) {
          scale = MIN_ICON_SCALE;
        } else if (absoluteDx > scaleEndPoint) {
          scale = MAX_ICON_SCALE;
        } else {
          scale = Math.min(MAX_ICON_SCALE, MIN_ICON_SCALE + ((absoluteDx - scaleStartPoint) / (scaleEndPoint - scaleStartPoint)) * (MAX_ICON_SCALE - MIN_ICON_SCALE));
        }

        if (absoluteDx > 0) {
          if (archiveDrawable == null) {
            archiveDrawable = Objects.requireNonNull(AppCompatResources.getDrawable(requireContext(), getArchiveIconRes()));
            archiveDrawable.setColorFilter(new SimpleColorFilter(Color.WHITE));
            archiveDrawable.setBounds(0, 0, archiveDrawable.getIntrinsicWidth(), archiveDrawable.getIntrinsicHeight());
          }

          canvas.save();
          canvas.clipRect(itemView.getLeft(), itemView.getTop(), itemView.getRight(), itemView.getBottom());

          canvas.drawColor(color);

          float gutter = resources.getDimension(R.dimen.dsl_settings_gutter);
          float extra  = resources.getDimension(R.dimen.conversation_list_fragment_archive_padding);

          if (ViewUtil.isLtr(requireContext())) {
            canvas.translate(itemView.getLeft() + gutter + extra,
                             itemView.getTop() + (itemView.getBottom() - itemView.getTop() - archiveDrawable.getIntrinsicHeight()) / 2f);
          } else {
            canvas.translate(itemView.getRight() - gutter - extra,
                             itemView.getTop() + (itemView.getBottom() - itemView.getTop() - archiveDrawable.getIntrinsicHeight()) / 2f);
          }

          canvas.scale(scale, scale, archiveDrawable.getIntrinsicWidth() / 2f, archiveDrawable.getIntrinsicHeight() / 2f);

          archiveDrawable.draw(canvas);
          canvas.restore();

          ViewCompat.setElevation(viewHolder.itemView, DimensionUnit.DP.toPixels(4f));
        } else if (absoluteDx == 0) {
          ViewCompat.setElevation(viewHolder.itemView, DimensionUnit.DP.toPixels(0f));
        }

        viewHolder.itemView.setTranslationX(dX);
      } else {
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
      }
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      super.clearView(recyclerView, viewHolder);
      ViewCompat.setElevation(viewHolder.itemView, 0);
      lastTouched = null;
      itemAnimator.postDisable(requireView().getHandler());
    }
  }

  private class ScrollListener extends RecyclerView.OnScrollListener {
    @Override
    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
      if (recyclerView.canScrollVertically(-1)) {
        if (toolbarShadow.getVisibility() != View.VISIBLE) {
          ViewUtil.fadeIn(toolbarShadow, 250);
        }
      } else {
        if (toolbarShadow.getVisibility() != View.GONE) {
          ViewUtil.fadeOut(toolbarShadow, 250);
        }
      }
    }
  }

  private final class VoiceNotePlayerViewListener implements VoiceNotePlayerView.Listener {

    @Override
    public void onCloseRequested(@NonNull Uri uri) {
      if (voiceNotePlayerViewStub.resolved()) {
        mediaControllerOwner.getVoiceNoteMediaController().stopPlaybackAndReset(uri);
      }
    }

    @Override
    public void onSpeedChangeRequested(@NonNull Uri uri, float speed) {
      mediaControllerOwner.getVoiceNoteMediaController().setPlaybackSpeed(uri, speed);
    }

    @Override
    public void onPlay(@NonNull Uri uri, long messageId, double position) {
      mediaControllerOwner.getVoiceNoteMediaController().startSinglePlayback(uri, messageId, position);
    }

    @Override
    public void onPause(@NonNull Uri uri) {
      mediaControllerOwner.getVoiceNoteMediaController().pausePlayback(uri);
    }

    @Override
    public void onNavigateToMessage(long threadId, @NonNull RecipientId threadRecipientId, @NonNull RecipientId senderId, long messageSentAt, long messagePositionInThread) {
      long fid = SignalDatabase.threads().getFidForThreadId(threadId);//AA+
      MainNavigator.get(requireActivity()).goToConversation(threadRecipientId, threadId, ThreadDatabase.DistributionTypes.DEFAULT, (int) messagePositionInThread, fid);
    }
  }

  public interface Callback {
    @NonNull Toolbar getToolbar();
    @NonNull ImageView getSearchAction();
    @NonNull Stub<SearchToolbar> getSearchToolbar();
    @NonNull View getUnreadPaymentsDot();
    @NonNull Stub<Toolbar> getBasicToolbar();
    @NonNull TextView getLocationBadge();//AA+
    void updateNotificationProfileStatus(@NonNull List<NotificationProfile> notificationProfiles);
    void updateProxyStatus(@NonNull WebSocketConnectionState state);
    void onSearchOpened();
    void onSearchClosed();
  }

  //start region ConversationListFragmentForPager
  //AA+
  static public class ConversationListFragmentForPager extends Fragment {
    private RecyclerView                      list;
    private GroupMode                         groupMode;
    private ConversationListViewModel         viewModel;
    private ConversationListAdapter           defaultAdapter;
    private ConversationListSearchAdapter     searchAdapter;
    private StickyHeaderDecoration            searchAdapterDecoration;
    private SnapToTopDataObserver             snapToTopDataObserver;
    private Drawable                          archiveDrawable;

    private Stopwatch                             startupStopwatch;//xfrag

    static private ConversationListFragment   parent;

    @Override
    public void onCreate(Bundle icicle) {
      super.onCreate(icicle);
      groupMode = GroupMode.values()[getArguments().getInt(GROUP_MODE)];
      startupStopwatch = new Stopwatch("startup");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
      Log.d(TAG, java.lang.String.format("ConversationListFragmentForPager:onCreateView: called for %s", groupMode.toString()));
      final View view = inflater.inflate(R.layout.conversation_list_pager_fragment, container, false);
      list = view.findViewById(R.id.list);
//      list.setVisibility(View.VISIBLE);
//      parent.listTouchItem(list);

      snapToTopDataObserver = new SnapToTopDataObserver(list);

      initializeListAdapters();
      viewModel       = allocateViewModel();

      initializeTypingObserver();//xfrag

      return view;
    }

    @Override
    public void onStart()
    {
      super.onStart();
      Log.d(TAG, java.lang.String.format("ConversationListFragmentForPager:onStart: called for %s", groupMode.toString()));
    }

    @Override
    public void onPause()
    {
      super.onPause();
      Log.d(TAG, java.lang.String.format("ConversationListFragmentForPager:onPause: called for %s", groupMode.toString()));

      removeLiveDataObservers();
    }

    @Override
    public void onStop()
    {
      super.onStop();
      Log.d(TAG, java.lang.String.format("ConversationListFragmentForPager:onStop: called for %s", groupMode.toString()));
      removeLiveDataObservers();
    }

    //AA only called for currently visible fragment, everything else get onStart(). see FragmentPagerItemAdapterWithBehaviour
    @Override
    public void onResume()
    {
      super.onResume();

      parent.pagerPosition  = groupMode.ordinal();
      parent.groupMode      = groupMode;

      setupFragmentContextForParent();

      viewModel.getSearchResult().observe(this, parent::onSearchResultChanged);//AA do we use parent.getViewLifecycleOwner() or just getViewLifecycleOwner()
      viewModel.getMegaphone().observe(this, parent::onMegaphoneChanged);

      viewModel.getConversationList().observe(getViewLifecycleOwner(), parent::onConversationListChanged);
//      viewModel.hasNoConversations().observe(this, parent::updateEmptyState);//AA done above

      viewModel.getNotificationProfiles().observe(getViewLifecycleOwner(), parent.requireCallback()::updateNotificationProfileStatus);
      viewModel.getPipeState().observe(getViewLifecycleOwner(), parent.requireCallback()::updateProxyStatus);

      viewModel.getUnreadPaymentsLiveData().observe(getViewLifecycleOwner(), parent::onUnreadPaymentsChanged);

      viewModel.getSelectedConversations().observe(getViewLifecycleOwner(), conversations -> {
        defaultAdapter.setSelectedConversations(conversations);
        updateMultiSelectState(viewModel, parent.actionMode, parent.bottomActionBar);
    });

      //AA+ can't use lambda expression here as compiler will optimise it into singleton class causing the same observer to be used across many lifecyles which is not an allowed state
      viewModel.getNickname().observe(this, new Observer<String>() {
        @Override
        public void onChanged (String s) {
          parent.nickname.setText(s);
        }
      });

//      if (parent.activeAdapter != parent.searchAdapter) {
//        if (viewModel.getConversationList().getValue() != null) {
//          parent.onSubmitList(viewModel.getConversationList().getValue());
//          parent.updateEmptyState(viewModel.getConversationList().getValue().isEmpty());
//        } else {
//          Log.e(TAG, "onResume: ConevrsationList is NULL");
//        }
//      }

      parent.searchResume(this.list);//xfrag

      if (parent.activeAdapter != null) {
        parent.activeAdapter.notifyItemRangeChanged(0, parent.activeAdapter.getItemCount());
      }

      Log.d(TAG, java.lang.String.format("ConversationListFragmentForPager:onResume: called for %s", groupMode.toString()));
    }

    private void removeLiveDataObservers()
    {
      viewModel.getSearchResult().removeObservers(this);
      viewModel.getMegaphone().removeObservers(this);
      viewModel.getConversationList().removeObservers(this);
      viewModel.hasNoConversations().removeObservers(this);

      viewModel.getNickname().removeObservers(this);
    }

    //AA+
    private ConversationListViewModel allocateViewModel()
    {
      ConversationListViewModel.Factory viewModelFactory = new ConversationListViewModel.Factory(parent.isArchived(),
                                                                                                 getString(R.string.note_to_self), groupMode);
      //AA IMPORTANT note 'this' is referring to current fragment, not the main fragment
      conversationLists.put(groupMode, new ViewModelProvider(this, viewModelFactory).get(ConversationListViewModel.class));
      return conversationLists.get(groupMode);
    }

    private void initializeListAdapters() {
      defaultAdapter          = new ConversationListAdapter(GlideApp.with(parent.requireContext()), parent);
      searchAdapter           = new ConversationListSearchAdapter(GlideApp.with(parent.requireContext()), parent, Locale.getDefault());
      searchAdapterDecoration = new StickyHeaderDecoration(searchAdapter, false, false, 0);

      defaultAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
          startupStopwatch.split("data-set");
          SignalLocalMetrics.ColdStart.onConversationListDataLoaded();
          defaultAdapter.unregisterAdapterDataObserver(this);
          list.post(() -> {//AA todo this may be called multiple times: one for each tab (https://github.com/signalapp/Signal-Android/commit/bbc346bd7a09abed0da55111aacfd98eac75608e)
            AppStartup.getInstance().onCriticalRenderEventEnd();
            startupStopwatch.split("first-render");
            startupStopwatch.stop(TAG);
          });
        }
      });
    }

    private void initializeTypingObserver() {
      ApplicationDependencies.getTypingStatusRepository().getTypingThreads().observe(getViewLifecycleOwner(), threadIds -> {//AA+ parent
        if (threadIds == null) {
          threadIds = Collections.emptySet();
        }

        defaultAdapter.setTypingThreads(threadIds);
      });
    }

    void setupFragmentContextForParent()
    {
      conversationListFragment        = this;
      parent.list                     = list;
      parent.defaultAdapter           = defaultAdapter;
      parent.viewModel                = viewModel;
      parent.snapToTopDataObserver    = snapToTopDataObserver;
      parent.searchAdapter            = searchAdapter;
      parent.searchAdapterDecoration  = searchAdapterDecoration;

      parent.listTouchItem(list);
      parent.initializeSearchListener(list);//xfrag

    }

    //xfrag PORTING NOTE: replicated from parent fragment above with 'parent' references added
    private void updateMultiSelectState(ConversationListViewModel viewModel, ActionMode actionMode, SignalBottomActionBar bottomActionBar) {//AA+ added method params to support multi tab implementation
      int     count       = viewModel.currentSelectedConversations().size();
      boolean hasUnread   = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().isRead());
      boolean hasUnpinned = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().isPinned());
      boolean hasUnmuted  = Stream.of(viewModel.currentSelectedConversations()).anyMatch(conversation -> !conversation.getThreadRecord().getRecipient().live().get().isMuted());
      boolean canPin      = viewModel.getPinnedCount() < MAXIMUM_PINNED_CONVERSATIONS;

      if (actionMode != null) {
        actionMode.setTitle(requireContext().getResources().getQuantityString(R.plurals.ConversationListFragment_s_selected, count, count));
      }

      List<ActionItem> items = new ArrayList<>();

      Set<Long> selectionIds = viewModel.currentSelectedConversations()
                                        .stream()
                                        .map(conversation -> conversation.getThreadRecord().getThreadId())
                                        .collect(Collectors.toSet());

      if (hasUnread) {
        items.add(new ActionItem(R.drawable.ic_read_24, getResources().getQuantityString(R.plurals.ConversationListFragment_read_plural, count), () -> parent.handleMarkAsRead(selectionIds)));
      } else {
        items.add(new ActionItem(R.drawable.ic_unread_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unread_plural, count), () -> parent.handleMarkAsUnread(selectionIds)));
      }

      if (!parent.isArchived() && hasUnpinned && canPin) {
        items.add(new ActionItem(R.drawable.ic_pin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_pin_plural, count), () -> parent.handlePin(viewModel.currentSelectedConversations())));
      } else if (!parent.isArchived() && !hasUnpinned) {
        items.add(new ActionItem(R.drawable.ic_unpin_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unpin_plural, count), () -> parent.handleUnpin(selectionIds)));
      }

      if (parent.isArchived()) {
        items.add(new ActionItem(R.drawable.ic_unarchive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unarchive_plural, count), () -> parent.handleArchive(selectionIds, true)));
      } else {
        items.add(new ActionItem(R.drawable.ic_archive_24, getResources().getQuantityString(R.plurals.ConversationListFragment_archive_plural, count), () -> parent.handleArchive(selectionIds, true)));
      }

      items.add(new ActionItem(R.drawable.ic_delete_24, getResources().getQuantityString(R.plurals.ConversationListFragment_delete_plural, count), () -> parent.handleDelete(selectionIds)));

      if (hasUnmuted) {
        items.add(new ActionItem(R.drawable.ic_mute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_mute_plural, count), () -> parent.handleMute(viewModel.currentSelectedConversations())));
      } else {
        items.add(new ActionItem(R.drawable.ic_unmute_24, getResources().getQuantityString(R.plurals.ConversationListFragment_unmute_plural, count), () -> parent.handleUnmute(viewModel.currentSelectedConversations())));
      }

      items.add(new ActionItem(R.drawable.ic_select_24, getString(R.string.ConversationListFragment_select_all), viewModel::onSelectAllClick));

      bottomActionBar.setItems(items);
    }

    public static ConversationListFragmentForPager newInstance(@NonNull ConversationListFragment x, @Nullable Locale locale, GroupMode groupMode) {
      ConversationListFragmentForPager fragment = new ConversationListFragmentForPager();
      Bundle args = new Bundle();
      args.putSerializable(PassphraseRequiredActivity.LOCALE_EXTRA, locale);
      args.putInt(GROUP_MODE, groupMode.ordinal());
      fragment.setArguments(args);
      parent = x;

      return fragment;
    }
  }
  //start region ConversationListFragmentForPager
}