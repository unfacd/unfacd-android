package org.thoughtcrime.securesms;

import com.annimon.stream.Stream;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.app.ProgressDialog;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import androidx.preference.PreferenceFragment;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.core.view.ViewCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.appcompat.widget.Toolbar;
import android.telephony.PhoneNumberUtils;

import org.thoughtcrime.securesms.components.SwitchPreferenceCompat;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.jobs.MultiDeviceBlockedUpdateJob;
import org.thoughtcrime.securesms.jobs.RotateProfileKeyJob;
import org.thoughtcrime.securesms.logging.Log;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.color.MaterialColors;
import org.thoughtcrime.securesms.components.ThreadPhotoRailView;
import org.thoughtcrime.securesms.crypto.IdentityKeyParcelable;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.IdentityDatabase.IdentityRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.loaders.ThreadMediaLoader;
import org.thoughtcrime.securesms.database.RecipientDatabase.GeogroupStickyState;//
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.preferences.CorrectedPreferenceFragment;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.preferences.widgets.ColorPickerPreference;
import org.thoughtcrime.securesms.preferences.widgets.ContactPreference;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.DynamicLanguage;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.util.JsonUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ExecutionException;

import com.unfacd.android.data.json.JsonEntityUserPreferences;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.jobs.ReloadGroupJob;
import com.unfacd.android.preferences.CustomMultiSelectListPreference;
import com.unfacd.android.ufsrvcmd.events.AppEventFenceUserPref;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefProfileKey;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.Utils;
import com.unfacd.android.ui.components.PrivateGroupForTwoName;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UfsrvUserUtils;

import static java.lang.String.format;

@SuppressLint("StaticFieldLeak")
public class RecipientPreferenceActivity extends PassphraseRequiredActionBarActivity implements RecipientModifiedListener, LoaderManager.LoaderCallbacks<Cursor>
{
  private static final String TAG = RecipientPreferenceActivity.class.getSimpleName();

  public static final String ADDRESS_EXTRA                = "recipient_address";
  public static final String CAN_HAVE_SAFETY_NUMBER_EXTRA = "can_have_safety_number";

  private static final String PREFERENCE_MUTED                 = "pref_key_recipient_mute";
  private static final String PREFERENCE_MESSAGE_TONE          = "pref_key_recipient_ringtone";
  private static final String PREFERENCE_CALL_TONE             = "pref_key_recipient_call_ringtone";
  private static final String PREFERENCE_MESSAGE_VIBRATE       = "pref_key_recipient_vibrate";
  private static final String PREFERENCE_CALL_VIBRATE          = "pref_key_recipient_call_vibrate";
  private static final String PREFERENCE_BLOCK                 = "pref_key_recipient_block";
  private static final String PREFERENCE_BLOCKED_BY            = "pref_key_recipient_blocked_by";//A+
  private static final String PREFERENCE_COLOR                 = "pref_key_recipient_color";
  private static final String PREFERENCE_IDENTITY              = "pref_key_recipient_identity";
  private static final String PREFERENCE_ABOUT                 = "pref_key_number";
  private static final String PREFERENCE_CUSTOM_NOTIFICATIONS  = "pref_key_recipient_custom_notifications";

  //
  private static final String PREFERENCE_GSTICKY                = "pref_key_geogroup_sticky";
  private static final String PREFERENCE_PROFILEKEY             = "pref_key_share_profile_key";// profile in general, including key
  private static final String PREFERENCE_SHARED_PROFILEKEY      = "pref_key_shared_profile_key";
  private static final String PREFERENCE_SHARE_PRESENCE         = "pref_key_share_presence";
  private static final String PREFERENCE_IGNORE_PRESENCE        = "pref_key_ignore_presence";
  private static final String PREFERENCE_SHARE_READ_RECEIPT     = "pref_key_share_read_receipt";
  private static final String PREFERENCE_IGNORE_READ_RECEIPT    = "pref_key_ignore_read_receipt";
  private static final String PREFERENCE_SHARE_ACTIVITY_STATE   = "pref_key_sharing_activity_state";
  private static final String PREFERENCE_IGNORE_ACTIVITY_STATE  = "pref_key_ignore_shared_activity_state";
  private static final String PREFERENCE_SHARING_CONTACT        = "pref_key_sharing_contact";
  private static final String PREFERENCE_SHARED_CONTACT         = "pref_key_shared_contact";
  private static final String PREFERENCE_SHARE_LOCATION          = "pref_key_share_location";
  private static final String PREFERENCE_IGNORE_LOCATION         = "pref_key_ignore_location";
  private static final String PREFERENCE_PERMISSIONS_GROUP_PRESENTATON = "pref_key_permissions_group_presentation";
  private static final String PREFERENCE_PERMISSIONS_GROUP_MEMBERSHIP = "pref_key_permissions_group_membership";
  //

  private final DynamicTheme    dynamicTheme    = new DynamicNoActionBarTheme();
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();

  private ImageView               avatar;
  private GlideRequests           glideRequests;
  private Address                 address;
  private TextView                threadPhotoRailLabel;
  private ThreadPhotoRailView     threadPhotoRailView;
  private CollapsingToolbarLayout toolbarLayout;

  private static final Map<String, EnumFencePermissions> permissionsPrefKeyHashMap;
  static {
    HashMap<String, EnumFencePermissions> permissionsHashmap=new HashMap<>();
    permissionsHashmap.put("pref_key_permissions_group_presentation", EnumFencePermissions.PRESENTATION);
    permissionsHashmap.put("pref_key_permissions_group_membership"  , EnumFencePermissions.MEMBERSHIP);
    permissionsHashmap.put("pref_key_permissions_group_messaging"   , EnumFencePermissions.MESSAGING);
    permissionsHashmap.put("pref_key_permissions_group_attaching"   , EnumFencePermissions.ATTACHING);
    permissionsHashmap.put("pref_key_permissions_group_calling"   , EnumFencePermissions.CALLING);
    permissionsPrefKeyHashMap= Collections.unmodifiableMap(permissionsHashmap);
  }

  @Override
  public void onPreCreate() {
    dynamicTheme.onCreate(this);
    dynamicLanguage.onCreate(this);
  }

  @Override
  public void onCreate(Bundle instanceState, boolean ready) {
    setContentView(R.layout.recipient_preference_activity);

    this.glideRequests = GlideApp.with(this);
    this.address       = getIntent().getParcelableExtra(ADDRESS_EXTRA);

    Recipient recipient = Recipient.from(this, address, true);

    initializeToolbar();
    setHeader(recipient);
    recipient.addListener(this);

    getSupportLoaderManager().initLoader(0, null, this);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
      Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public void onResume() {
    super.onResume();
    dynamicTheme.onResume(this);
    dynamicLanguage.onResume(this);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.preference_fragment);
    fragment.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    switch (item.getItemId()) {
      case android.R.id.home:
        onBackPressed();
        return true;
    }

    return false;
  }

  @Override
  public void onBackPressed() {
    finish();
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
  }

  private void initializeToolbar() {
    this.toolbarLayout        = ViewUtil.findById(this, R.id.collapsing_toolbar);
    this.avatar               = ViewUtil.findById(this, R.id.avatar);
    this.threadPhotoRailView  = ViewUtil.findById(this, R.id.recent_photos);
    this.threadPhotoRailLabel = ViewUtil.findById(this, R.id.rail_label);

    this.toolbarLayout.setExpandedTitleColor(getResources().getColor(R.color.white));
    this.toolbarLayout.setCollapsedTitleTextColor(getResources().getColor(R.color.white));

    //
    this.toolbarLayout.setCollapsedTitleTextAppearance(R.style.CollapsedAppBar);
    this.toolbarLayout.setExpandedTitleTextAppearance(R.style.ExpandedAppBar);
    //

    this.threadPhotoRailView.setListener(mediaRecord -> {
      Intent intent = new Intent(RecipientPreferenceActivity.this, MediaPreviewActivity.class);
      intent.putExtra(MediaPreviewActivity.ADDRESS_EXTRA, address);
      intent.putExtra(MediaPreviewActivity.OUTGOING_EXTRA, mediaRecord.isOutgoing());
      intent.putExtra(MediaPreviewActivity.DATE_EXTRA, mediaRecord.getDate());
      intent.putExtra(MediaPreviewActivity.SIZE_EXTRA, mediaRecord.getAttachment().getSize());
      intent.putExtra(MediaPreviewActivity.CAPTION_EXTRA, mediaRecord.getAttachment().getCaption());
      intent.putExtra(MediaPreviewActivity.LEFT_IS_RECENT_EXTRA, ViewCompat.getLayoutDirection(threadPhotoRailView) == ViewCompat.LAYOUT_DIRECTION_LTR);
      intent.setDataAndType(mediaRecord.getAttachment().getDataUri(), mediaRecord.getContentType());
      startActivity(intent);
    });

    this.threadPhotoRailLabel.setOnClickListener(v -> {
      Intent intent = new Intent(this, MediaOverviewActivity.class);
      intent.putExtra(MediaOverviewActivity.ADDRESS_EXTRA, address);
      startActivity(intent);
    });

    Toolbar toolbar = ViewUtil.findById(this, R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setLogo(null);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
      getWindow().setStatusBarColor(Color.TRANSPARENT);
    }
  }

  private void setHeader(@NonNull Recipient recipient) {
    ContactPhoto         contactPhoto  = recipient.isLocalNumber() ? new ProfileContactPhoto(recipient.getAddress(), String.valueOf(TextSecurePreferences.getProfileAvatarId(this)))
                                                                   : recipient.getContactPhoto();
    FallbackContactPhoto fallbackPhoto = recipient.isLocalNumber() ? new ResourceContactPhoto(R.drawable.ic_profile_default, R.drawable.ic_person_large)
                                                                   : recipient.getFallbackContactPhoto();

    glideRequests.load(contactPhoto)
            .fallback(fallbackPhoto.asCallCard(this))
            .error(fallbackPhoto.asCallCard(this))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(this.avatar);

    if (contactPhoto == null) this.avatar.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    else                      this.avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

    this.avatar.setBackgroundColor(recipient.getColor().toActionBarColor(this));
    if (recipient.isPrivateGroupForTwo()) { //
      PrivateGroupForTwoName privateGroupForTwoName = new PrivateGroupForTwoName(this, recipient);
      this.toolbarLayout.setTitle(privateGroupForTwoName.getStylisedName().get());
    } else {
      this.toolbarLayout.setTitle(recipient.getDisplayName());//toShortString()); AA?
    }
    this.toolbarLayout.setContentScrimColor(recipient.getColor().toActionBarColor(this));
  }

  @Override
  public void onModified(final Recipient recipient) {
    Util.runOnMain(() -> setHeader(recipient));
  }

  @Override
  public Loader<Cursor> onCreateLoader(int id, Bundle args) {
    return new ThreadMediaLoader(this, address, true);
  }

  @Override
  public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
    if (data != null && data.getCount() > 0) {
      this.threadPhotoRailLabel.setVisibility(View.VISIBLE);
      this.threadPhotoRailView.setVisibility(View.VISIBLE);
    } else {
      this.threadPhotoRailLabel.setVisibility(View.GONE);
      this.threadPhotoRailView.setVisibility(View.GONE);
    }

    this.threadPhotoRailView.setCursor(glideRequests, data);

    Bundle bundle = new Bundle();
    bundle.putParcelable(ADDRESS_EXTRA, address);
    initFragment(R.id.preference_fragment, new RecipientPreferenceFragment(), null, bundle);
  }

  @Override
  public void onLoaderReset(Loader<Cursor> loader) {
    this.threadPhotoRailView.setCursor(glideRequests, null);
  }


  ///--------------------------------------------------------////

  public static class RecipientPreferenceFragment
          extends    CorrectedPreferenceFragment
          implements RecipientModifiedListener
  {

    private Recipient        recipient;
    private boolean           canHaveSafetyNumber;
    //
    private long              fid;
    private GroupDatabase.GroupRecord groupRecord;
    private HashSet<Long> ignoredMembers;

    @Override
    public void onCreate(Bundle icicle) {
      Log.w(TAG, "onCreate (fragment)");
      super.onCreate(icicle);

      ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);   //

      initializeRecipients();

      this.canHaveSafetyNumber = getActivity().getIntent()
              .getBooleanExtra(RecipientPreferenceActivity.CAN_HAVE_SAFETY_NUMBER_EXTRA, false);

      Preference customNotificationsPref  = this.findPreference(PREFERENCE_CUSTOM_NOTIFICATIONS);

      if (NotificationChannels.supported()) {
        ((SwitchPreferenceCompat) customNotificationsPref).setChecked(recipient.getNotificationChannel() != null);
        customNotificationsPref.setOnPreferenceChangeListener(new CustomNotificationsChangedListener());
        this.findPreference(PREFERENCE_MESSAGE_TONE).setDependency(PREFERENCE_CUSTOM_NOTIFICATIONS);
        this.findPreference(PREFERENCE_MESSAGE_VIBRATE).setDependency(PREFERENCE_CUSTOM_NOTIFICATIONS);

        if (recipient.getNotificationChannel() != null) {
          final Context context = getContext();
          new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
              RecipientDatabase db = DatabaseFactory.getRecipientDatabase(getContext());
              db.setMessageRingtone(recipient, NotificationChannels.getMessageRingtone(context, recipient));
              db.setMessageVibrate(recipient, NotificationChannels.getMessageVibrate(context, recipient) ? VibrateState.ENABLED : VibrateState.DISABLED);
              return null;
            }
          }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        }
      } else {
        customNotificationsPref.setVisible(false);
      }

      this.findPreference(PREFERENCE_MESSAGE_TONE)
              .setOnPreferenceChangeListener(new RingtoneChangeListener(false));
      this.findPreference(PREFERENCE_MESSAGE_TONE)
              .setOnPreferenceClickListener(new RingtoneClickedListener(false));
      this.findPreference(PREFERENCE_CALL_TONE)
              .setOnPreferenceChangeListener(new RingtoneChangeListener(true));
      this.findPreference(PREFERENCE_CALL_TONE)
              .setOnPreferenceClickListener(new RingtoneClickedListener(true));
      this.findPreference(PREFERENCE_MESSAGE_VIBRATE)
              .setOnPreferenceChangeListener(new VibrateChangeListener(false));
      this.findPreference(PREFERENCE_CALL_VIBRATE)
              .setOnPreferenceChangeListener(new VibrateChangeListener(true));
      this.findPreference(PREFERENCE_MUTED)
              .setOnPreferenceClickListener(new MuteClickedListener());
      this.findPreference(PREFERENCE_BLOCK)
              .setOnPreferenceClickListener(new BlockClickedListener());
      this.findPreference(PREFERENCE_COLOR)
              .setOnPreferenceChangeListener(new ColorChangeListener());
      ((ContactPreference)this.findPreference(PREFERENCE_ABOUT))
              .setListener(new AboutNumberClickedListener());
      //
      this.findPreference(PREFERENCE_PROFILEKEY)
              .setOnPreferenceChangeListener(new ProfileKeyChangeListener());


      if (recipient.isGroupRecipient()) {
        this.findPreference(PREFERENCE_GSTICKY)
                .setOnPreferenceChangeListener(new GeogroupStickyChangeListener());

        this.findPreference(PREFERENCE_PERMISSIONS_GROUP_PRESENTATON)
                .setOnPreferenceChangeListener(new GroupPermissionChangeListener());
        this.findPreference(PREFERENCE_PERMISSIONS_GROUP_MEMBERSHIP)
                .setOnPreferenceChangeListener(new GroupPermissionChangeListener());

          ((CheckBoxPreference) this.findPreference("pref_toggle_reload_group")).setChecked(false);
          this.findPreference("pref_toggle_reload_group")
                  .setOnPreferenceChangeListener(new ReloadGroupClickListener());
      } else {
        this.findPreference(PREFERENCE_SHARED_PROFILEKEY)
                .setOnPreferenceChangeListener(new ProfileIgnoredChangeListener());

        this.findPreference(PREFERENCE_SHARE_PRESENCE)
                .setOnPreferenceChangeListener(new PresenceChangeListener());
        this.findPreference(PREFERENCE_IGNORE_PRESENCE)
                .setOnPreferenceChangeListener(new PresenceIgnoredChangeListener());

        this.findPreference(PREFERENCE_SHARE_READ_RECEIPT)
                .setOnPreferenceChangeListener(new ReadReceiptChangeListener());
        this.findPreference(PREFERENCE_IGNORE_READ_RECEIPT)
                .setOnPreferenceChangeListener(new ReadReceiptIgnoredChangeListener());

        this.findPreference(PREFERENCE_SHARING_CONTACT)
                .setOnPreferenceClickListener(new ContactSharingChangeListener());

//      this.findPreference(PREFERENCE_SHARE_ACTIVITY_STATE)
//              .setOnPreferenceChangeListener(new PresenceChangeListener());
//      this.findPreference(PREFERENCE_IGNORE_ACTIVITY_STATE)
//              .setOnPreferenceChangeListener(new PresenceIgnoredChangeListener());
      }

      //
    }

    @Override
     public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
      addPreferencesFromResource(R.xml.recipient_preferences);
    }

    @Override
    public void onResume() {
      super.onResume();
      setSummaries(recipient);
    }

    @Override
    public void onDestroy() {
      super.onDestroy();
      this.recipient.removeListener(this);

      ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);//
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
      if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);

        findPreference(PREFERENCE_MESSAGE_TONE).getOnPreferenceChangeListener().onPreferenceChange(findPreference(PREFERENCE_MESSAGE_TONE), uri);
      } else if (requestCode == 2 && resultCode == RESULT_OK && data != null) {
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);

        findPreference(PREFERENCE_CALL_TONE).getOnPreferenceChangeListener().onPreferenceChange(findPreference(PREFERENCE_CALL_TONE), uri);
      }
    }

    private void initializeRecipients() {
      this.recipient = Recipient.from(getActivity(), getArguments().getParcelable(ADDRESS_EXTRA), true);

      this.recipient.addListener(this);

      //
      fid         = UfsrvFenceUtils.fidFromGroupRecipients(getContext(), recipient);
      groupRecord = DatabaseFactory.getGroupDatabase(getContext()).getGroupRecordByFid(fid);
    }

    private void setSummaries(Recipient recipient) {
      CheckBoxPreference    mutePreference            = (CheckBoxPreference) this.findPreference(PREFERENCE_MUTED);
      Preference            customPreference          = this.findPreference(PREFERENCE_CUSTOM_NOTIFICATIONS);
      Preference            ringtoneMessagePreference = this.findPreference(PREFERENCE_MESSAGE_TONE);
      Preference            ringtoneCallPreference    = this.findPreference(PREFERENCE_CALL_TONE);
      ListPreference        vibrateMessagePreference  = (ListPreference) this.findPreference(PREFERENCE_MESSAGE_VIBRATE);
      ListPreference        vibrateCallPreference     = (ListPreference) this.findPreference(PREFERENCE_CALL_VIBRATE);
      ColorPickerPreference colorPreference           = (ColorPickerPreference) this.findPreference(PREFERENCE_COLOR);
      Preference            blockPreference           = this.findPreference(PREFERENCE_BLOCK);
      Preference            blockedByPreference       = this.findPreference(PREFERENCE_BLOCKED_BY);//
      Preference            identityPreference        = this.findPreference(PREFERENCE_IDENTITY);
      PreferenceCategory    callCategory              = (PreferenceCategory)this.findPreference("call_settings");
      PreferenceCategory    aboutCategory             = (PreferenceCategory)this.findPreference("about");
      PreferenceCategory    aboutDivider              = (PreferenceCategory)this.findPreference("about_divider");
      ContactPreference     aboutPreference           = (ContactPreference)this.findPreference(PREFERENCE_ABOUT);
      PreferenceCategory    privacyCategory           = (PreferenceCategory) this.findPreference("privacy_settings");
      PreferenceCategory    divider                   = (PreferenceCategory) this.findPreference("divider");

      mutePreference.setChecked(recipient.isMuted());

      ringtoneMessagePreference.setSummary(ringtoneMessagePreference.isEnabled() ? getRingtoneSummary(getContext(), recipient.getMessageRingtone()) : "");
      ringtoneCallPreference.setSummary(getRingtoneSummary(getContext(), recipient.getCallRingtone()));

      Pair<String, Integer> vibrateMessageSummary = getVibrateSummary(getContext(), recipient.getMessageVibrate());
      Pair<String, Integer> vibrateCallSummary    = getVibrateSummary(getContext(), recipient.getCallVibrate());

      vibrateMessagePreference.setSummary(vibrateMessagePreference.isEnabled() ? vibrateMessageSummary.first : "");
      vibrateMessagePreference.setValueIndex(vibrateMessageSummary.second);

      vibrateCallPreference.setSummary(vibrateCallSummary.first);
      vibrateCallPreference.setValueIndex(vibrateCallSummary.second);

      //
      Preference                 profileKeyPreference             = this.findPreference(PREFERENCE_PROFILEKEY);
      Preference                 profileSharedPreference          = this.findPreference(PREFERENCE_SHARED_PROFILEKEY);
      Preference                 presenceSharePreference          = this.findPreference(PREFERENCE_SHARE_PRESENCE);
      Preference                 presenceIgnorePreference         = this.findPreference(PREFERENCE_IGNORE_PRESENCE);
      Preference                 readReceiptSharePreference       = this.findPreference(PREFERENCE_SHARE_READ_RECEIPT);
      Preference                 readReceiptIgnorePreference      = this.findPreference(PREFERENCE_IGNORE_READ_RECEIPT);
      Preference                 sharingContactPreference         = this.findPreference(PREFERENCE_SHARING_CONTACT);
      Preference                 sharedContactPreference          = this.findPreference(PREFERENCE_SHARED_CONTACT);
      Preference                 activityStateSharePreference     = this.findPreference(PREFERENCE_SHARE_ACTIVITY_STATE);
      Preference                 activityStateIgnorePreference    = this.findPreference(PREFERENCE_IGNORE_ACTIVITY_STATE);

      ListPreference             geogroupStickyPreference  = (ListPreference) this.findPreference(PREFERENCE_GSTICKY);
      CustomMultiSelectListPreference permissionPreferencePresentaton  = (CustomMultiSelectListPreference)this.findPreference(PREFERENCE_PERMISSIONS_GROUP_PRESENTATON);
      CustomMultiSelectListPreference permissionPreferenceMembership   = (CustomMultiSelectListPreference)this.findPreference(PREFERENCE_PERMISSIONS_GROUP_MEMBERSHIP);

      if (recipient.isProfileSharing()) {
        ((CheckBoxPreference)profileKeyPreference).setChecked(true);
        profileKeyPreference.setTitle(R.string.preferences_unshare_profile_key);
        profileKeyPreference.setSummary(R.string.preferences_groups__share_profile_key_summary_sharing);
      } else {
        ((CheckBoxPreference)profileKeyPreference).setChecked(false);
        profileKeyPreference.setTitle(R.string.preferences_groups__share_profile_key);
        profileKeyPreference.setSummary(R.string.preferences_groups__share_profile_key_summary);
      }

      if (recipient.isLocalNumber()) {
        mutePreference.setVisible(false);
        customPreference.setVisible(false);
        ringtoneMessagePreference.setVisible(false);
        vibrateMessagePreference.setVisible(false);

        if (identityPreference != null) identityPreference.setVisible(false);
        if (aboutCategory      != null) aboutCategory.setVisible(false);
        if (aboutDivider       != null) aboutDivider.setVisible(false);
        if (privacyCategory    != null) privacyCategory.setVisible(false);
        if (divider            != null) divider.setVisible(false);
        if (callCategory       != null) callCategory.setVisible(false);
      }

      if (recipient.isGroupRecipient()) {
        if (recipient.getGeogroupSticky() == GeogroupStickyState.DEFAULT) {
          geogroupStickyPreference.setSummary(R.string.preferences_recipients__geogroup_sticky_disabled_summary);
          geogroupStickyPreference.setValueIndex(0);
        } else if (recipient.getGeogroupSticky() == GeogroupStickyState.ENABLED) {
          geogroupStickyPreference.setSummary(R.string.preferences_recipients__geogroup_sticky_enabled_summary);
          geogroupStickyPreference.setValueIndex(1);
        } else {
          geogroupStickyPreference.setSummary(R.string.preferences_recipients__geogroup_sticky_disabled_summary);
          geogroupStickyPreference.setValueIndex(0);
        }

        permissionPreferencePresentaton.setCustomId(String.valueOf(fid));
        permissionPreferencePresentaton.setIgnoredEntries(getIgnoredMembers());
        permissionPreferencePresentaton.setSelectionCriterion(EnumFencePermissions.PRESENTATION.getValue());

        permissionPreferenceMembership.setCustomId(String.valueOf(fid));
        permissionPreferenceMembership.setIgnoredEntries(getIgnoredMembers());
        permissionPreferenceMembership.setSelectionCriterion(EnumFencePermissions.MEMBERSHIP.getValue());

        if (groupRecord.getOwnerUserId() != TextSecurePreferences.getUserId(getContext())) {
          permissionPreferencePresentaton.setDialogActionable(false);
          permissionPreferenceMembership.setDialogActionable(false);
        }

        if (colorPreference    != null) colorPreference.setVisible(false);
        if (identityPreference != null) identityPreference.setVisible(false);
        if (callCategory       != null) callCategory.setVisible(false);
        if (aboutDivider       != null) getPreferenceScreen().removePreference(aboutDivider);
        if (aboutDivider       != null) aboutDivider.setVisible(false);
        if (divider            != null) divider.setVisible(false);

        profileSharedPreference.setVisible(false);
        presenceSharePreference.setVisible(false);
        presenceIgnorePreference.setVisible(false);
        readReceiptSharePreference.setVisible(false);
        readReceiptIgnorePreference.setVisible(false);
        sharingContactPreference.setVisible(false);
        sharedContactPreference.setVisible(false);
        activityStateSharePreference.setVisible(false);
        activityStateIgnorePreference.setVisible(false);
      } else {
        if (recipient.isProfileShared()) {
          ((CheckBoxPreference)profileSharedPreference).setChecked(true);
          profileSharedPreference.setTitle(R.string.preferences_ignore_profile_shared);
          profileSharedPreference.setSummary(R.string.preferences_ignore_profile_shared_summary);
        } else {
          profileSharedPreference.setVisible(false);
          getPreferenceScreen().removePreference(profileSharedPreference);
        }

        if (recipient.isBlocked()) blockedByPreference.setTitle(R.string.RecipientPreferenceActivity_unblock);
        else                       blockedByPreference.setTitle(R.string.RecipientPreferenceActivity_block);

        //
        if (recipient.isBlocked()) {
          ((CheckBoxPreference)blockPreference).setChecked(true);
          blockPreference.setTitle(R.string.preferences_unblock);
          blockPreference.setSummary(R.string.preferences_unblock_summary);
        } else {
          ((CheckBoxPreference)blockPreference).setChecked(false);
          blockPreference.setTitle(R.string.preferences_block);
          blockPreference.setSummary(R.string.preferences_block_summary);
        }

        if (recipient.isBlockShared()) {
          blockedByPreference.setTitle(R.string.preferences_blocked_by);
          blockedByPreference.setSummary(R.string.preferences_blocked_by_summary);
          blockedByPreference.setEnabled(false);
        } else {
          blockedByPreference.setVisible(false);
          getPreferenceScreen().removePreference(blockedByPreference);
        }

        //
        if (recipient.isPresenceSharing()) {
          ((CheckBoxPreference)presenceSharePreference).setChecked(true);
          presenceSharePreference.setTitle(R.string.preferences_unshare_presence);
          presenceSharePreference.setSummary(R.string.preferences_share_presence_summary_sharing);
        } else {
          ((CheckBoxPreference)presenceSharePreference).setChecked(false);
          presenceSharePreference.setTitle(R.string.preferences_share_presence);
          presenceSharePreference.setSummary(R.string.preferences_share_presence_summary);
        }

        if (recipient.isPresenceShared()) {
          ((CheckBoxPreference)presenceIgnorePreference).setChecked(true);
          presenceIgnorePreference.setTitle(R.string.preferences_ignore_presence);
          presenceIgnorePreference.setSummary(R.string.preferences_ignore_presence_summary);
        } else {
          presenceIgnorePreference.setVisible(false);
          getPreferenceScreen().removePreference(presenceIgnorePreference);
        }

        activityStateIgnorePreference.setVisible(false);
        activityStateSharePreference.setVisible(false);

        if (recipient.isReadReceiptSharing()) {
          ((CheckBoxPreference)readReceiptSharePreference).setChecked(true);
          readReceiptSharePreference.setTitle(R.string.preferences_unshare_read_receipt);
          readReceiptSharePreference.setSummary(R.string.preferences_share_read_receipt_summary_sharing);
        } else {
          ((CheckBoxPreference)readReceiptSharePreference).setChecked(false);
          readReceiptSharePreference.setTitle(R.string.preferences_share_read_receipt);
          readReceiptSharePreference.setSummary(R.string.preferences_share_read_receipt_summary);
        }

        if (recipient.isReadReceiptShared()) {
          ((CheckBoxPreference)readReceiptIgnorePreference).setChecked(true);
          readReceiptIgnorePreference.setTitle(R.string.preferences_ignore_read_receipt);
          readReceiptIgnorePreference.setSummary(R.string.preferences_ignore_read_receipt_summary);
        } else {
          readReceiptIgnorePreference.setVisible(false);
          getPreferenceScreen().removePreference(readReceiptIgnorePreference);
        }

        if (recipient.isContactSharing()) {
          ((CheckBoxPreference)sharingContactPreference).setChecked(true);
          sharingContactPreference.setTitle(R.string.preferences_sharing_contact);
          sharingContactPreference.setSummary(R.string.preferences_sharing_contact_summary);
        } else {
          ((CheckBoxPreference)sharingContactPreference).setChecked(false);
          sharingContactPreference.setTitle(R.string.preferences_not_sharing_contact);
          sharingContactPreference.setSummary(R.string.preferences_not_sharing_contact_summary);
        }
        if (recipient.isContactShared()) {
          ((CheckBoxPreference)sharedContactPreference).setChecked(true);
          ((CheckBoxPreference)sharedContactPreference).setEnabled(false);
          sharedContactPreference.setTitle(R.string.preferences_shared_contact);
          sharedContactPreference.setSummary(R.string.preferences_shared_contact_summary);
        } else {
          sharedContactPreference.setVisible(false);
          getPreferenceScreen().removePreference(sharedContactPreference);
        }

        if (geogroupStickyPreference != null) getPreferenceScreen().removePreference(geogroupStickyPreference);
        if (permissionPreferencePresentaton !=null) getPreferenceScreen().removePreference(permissionPreferencePresentaton);
        if (permissionPreferenceMembership !=null) getPreferenceScreen().removePreference(permissionPreferenceMembership);
        //

        colorPreference.setColors(MaterialColors.CONVERSATION_PALETTE.asConversationColorArray(getActivity()));
        colorPreference.setColor(recipient.getColor().toActionBarColor(getActivity()));

        aboutPreference.setTitle(formatAddress(recipient.getAddress()));
        aboutPreference.setSummary(recipient.getCustomLabel());
        aboutPreference.setSecure(recipient.getRegistered() == RecipientDatabase.RegisteredState.REGISTERED);

//        if (identityPreference != null) identityPreference.setVisible(false);// disabled
        //- todo: follow up safety numbers
        IdentityUtil.getRemoteIdentityKey(getActivity(), recipient).addListener(new ListenableFuture.Listener<Optional<IdentityRecord>>() {
          @Override
          public void onSuccess(Optional<IdentityRecord> result) {
            if (result.isPresent()) {
              if (identityPreference != null) identityPreference.setOnPreferenceClickListener(new IdentityClickedListener(result.get()));
              if (identityPreference != null) identityPreference.setEnabled(true);
            } else if (canHaveSafetyNumber) {
              if (identityPreference != null) identityPreference.setSummary(R.string.RecipientPreferenceActivity_available_once_a_message_has_been_sent_or_received);
              if (identityPreference != null) identityPreference.setEnabled(false);
            } else {
              if (identityPreference != null) getPreferenceScreen().removePreference(identityPreference);
            }
          }

          @Override
          public void onFailure(ExecutionException e) {
            if (identityPreference != null) getPreferenceScreen().removePreference(identityPreference);
          }
        });

        this.findPreference("group_permissions").setVisible(false);
        this.findPreference("misc_settings").setVisible(false);
        this.findPreference(PREFERENCE_GSTICKY).setVisible(false);
        this.findPreference(PREFERENCE_PERMISSIONS_GROUP_PRESENTATON).setVisible(false);
        this.findPreference(PREFERENCE_PERMISSIONS_GROUP_MEMBERSHIP).setVisible(false);
        this.findPreference("pref_toggle_reload_group").setVisible(false);
      }
    }

    private Set<Long> getIgnoredMembers ()
    {
      if (groupRecord!=null) {
        if (groupRecord.getOwnerUserId() == TextSecurePreferences.getUserId(getContext())) {
          ignoredMembers = new HashSet<>();
          ignoredMembers.add(Long.valueOf(TextSecurePreferences.getUserId(getContext())));
          return ignoredMembers;
        }
      }

      return new HashSet<Long>();
    }

    private @NonNull String formatAddress(@NonNull Address address) {
      if      (address.isPhone()) return PhoneNumberUtils.formatNumber(address.toPhoneString());
      else if (address.isEmail()) return address.toEmailString();
      else                        return "";
    }

    private @NonNull String getRingtoneSummary(@NonNull Context context, @Nullable Uri ringtone) {
      if (ringtone == null) {
        return context.getString(R.string.preferences__default);
      } else if (ringtone.toString().isEmpty()) {
        return context.getString(R.string.preferences__silent);
      } else {
        Ringtone tone = RingtoneManager.getRingtone(getActivity(), ringtone);

        if (tone != null) {
          return tone.getTitle(context);
        }
      }

      return context.getString(R.string.preferences__default);
    }

    private @NonNull Pair<String, Integer> getVibrateSummary(@NonNull Context context, @NonNull VibrateState vibrateState) {
      if (vibrateState == VibrateState.DEFAULT) {
        return new Pair<>(context.getString(R.string.preferences__default), 0);
      } else if (vibrateState == VibrateState.ENABLED) {
        return new Pair<>(context.getString(R.string.RecipientPreferenceActivity_enabled), 1);
      } else {
        return new Pair<>(context.getString(R.string.RecipientPreferenceActivity_disabled), 2);
      }
    }

    @Override
    public void onModified(final Recipient recipient) {
      Util.runOnMain(() -> {
        if (getContext() != null && getActivity() != null && !getActivity().isFinishing()) {
          setSummaries(recipient);
        }
      });
    }

    private class RingtoneChangeListener implements Preference.OnPreferenceChangeListener {
      private final boolean calls;

      RingtoneChangeListener(boolean calls) {
        this.calls = calls;
      }

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = preference.getContext();

        Uri value = (Uri)newValue;

        Uri defaultValue;

        if (calls) defaultValue = TextSecurePreferences.getCallNotificationRingtone(context);
        else       defaultValue = TextSecurePreferences.getNotificationRingtone(context);

        if (defaultValue.equals(value)) value = null;
        else if (value == null)         value = Uri.EMPTY;

        new AsyncTask<Uri, Void, Void>() {
          @Override
          protected Void doInBackground(Uri... params) {
            if (calls) {
              DatabaseFactory.getRecipientDatabase(context).setCallRingtone(recipient, params[0]);
            } else {
              DatabaseFactory.getRecipientDatabase(context).setMessageRingtone(recipient, params[0]);
              NotificationChannels.updateMessageRingtone(context, recipient, params[0]);
            }
            return null;
          }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, value);

        return false;
      }
    }

    private class RingtoneClickedListener implements Preference.OnPreferenceClickListener {

      private final boolean calls;

      RingtoneClickedListener(boolean calls) {
        this.calls = calls;
      }

      @Override
      public boolean onPreferenceClick(Preference preference) {
        Uri current;
        Uri defaultUri;

        if (calls) {
          current    = recipient.getCallRingtone();
          defaultUri = TextSecurePreferences.getCallNotificationRingtone(getContext());
        } else  {
          current    = recipient.getMessageRingtone();
          defaultUri = TextSecurePreferences.getNotificationRingtone(getContext());
        }

        if      (current == null)              current = Settings.System.DEFAULT_NOTIFICATION_URI;
        else if (current.toString().isEmpty()) current = null;

        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, calls ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current);

        startActivityForResult(intent, calls ? 2 : 1);

        return true;
      }
    }

    private class VibrateChangeListener implements Preference.OnPreferenceChangeListener {

      private final boolean call;

      VibrateChangeListener(boolean call) {
        this.call = call;
      }

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        int          value        = Integer.parseInt((String) newValue);
        final VibrateState vibrateState = VibrateState.fromId(value);
        final Context      context      = preference.getContext();

        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            if (call) {
              DatabaseFactory.getRecipientDatabase(context).setCallVibrate(recipient, vibrateState);
            }
            else {
              DatabaseFactory.getRecipientDatabase(context).setMessageVibrate(recipient, vibrateState);
              NotificationChannels.updateMessageVibrate(context, recipient, vibrateState);
            }
            return null;
          }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);

        return false;
      }
    }

    //
    private class ProfileKeyChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (recipient.isGroupRecipient()) {
          if (!recipient.isProfileSharing()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.preferences_groups__share_profile_key)
                    .setMessage(R.string.preferences_dialog_message_group_share)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preferences_dialog_share_profile_key, (dialog, which) -> shareProfileKey()).show();
          }
          else if (recipient.isProfileSharing()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.preferences_unshare_profile_key)
                    .setMessage(R.string.preferences_dialog_message_group_unshare)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preferences_dialog_unshare_profile_key, (dialog, which) -> unshareProfileKey()).show();
          }
        } else {
          if (!recipient.isProfileSharing()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.preferences_groups__share_profile_key)
                    .setMessage(R.string.preferences_dialog_message_share)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preferences_dialog_share_profile_key, (dialog, which) -> shareProfileKey()).show();
          } else if (recipient.isProfileSharing()) {
            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.preferences_unshare_profile_key)
                    .setMessage(R.string.preferences_dialog_message_unshare)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.preferences_dialog_unshare_profile_key, (dialog, which) -> unshareProfileKey()).show();
          }
        }
        return true;
      }

      void shareProfileKey () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareProfileWithRecipient(getActivity(), recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void unshareProfileKey () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareProfileWithRecipient(getActivity(), recipient, true);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    // dont want this user's presence information (we could still be sharing our presence info with them)
    private class ProfileIgnoredChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.preferences_ignore_profile_shared)
                .setMessage(R.string.preferences_dialog_message_profile_shared_ignore)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preferences_dialog_Remove, (dialog, which) -> ignoreProfile())
                .show();
        return true;
      }

      void ignoreProfile () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            //this is local change
            DatabaseFactory.getRecipientDatabase(getContext()).setProfileShared(recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    //
    private class PresenceChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (!recipient.isPresenceSharing()) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.preferences_share_presence)
                  .setMessage(R.string.preferences_dialog_message_presence_share)
                  .setCancelable(true)
                  .setNegativeButton(android.R.string.cancel, null)
                  .setPositiveButton(R.string.preferences_dialog_share_profile_key, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                      sharePresence();
                    }
                  }).show();
        }
        else {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.preferences_unshare_presence)
                  .setMessage(R.string.preferences_dialog_message_presence_unshare)
                  .setCancelable(true)
                  .setNegativeButton(android.R.string.cancel, null)
                  .setPositiveButton(R.string.preferences_dialog_unshare_profile_key, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                      unsharePresence();
                    }
                  }).show();
        }
        return true;
      }

      void sharePresence () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvSharePresence(getActivity(), recipient, true);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void unsharePresence () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvSharePresence(getActivity(), recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    // dont want this user's presence information (we could still be sharing our presence info with them)
    private class PresenceIgnoredChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.preferences_ignore_presence)
                  .setMessage(R.string.preferences_dialog_message_presence_ignore)
                  .setCancelable(true)
                  .setNegativeButton(android.R.string.cancel, null)
                  .setPositiveButton(R.string.preferences_dialog_Ignore, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                      ignorePresence();
                    }
                  }).show();
        return true;
      }

      void ignorePresence () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            //this is local change
            DatabaseFactory.getRecipientDatabase(getContext()).setPresenceShared(recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    private class ReadReceiptChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (!recipient.isReadReceiptSharing()) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.preferences_share_read_receipt)
                  .setMessage(R.string.preferences_dialog_message_read_receipt_share)
                  .setCancelable(true)
                  .setNegativeButton(android.R.string.cancel, null)
                  .setPositiveButton(R.string.preferences_dialog_share_profile_key, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                      shareReadReceipt();
                    }
                  }).show();
        }
        else {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.preferences_unshare_read_receipt)
                  .setMessage(R.string.preferences_dialog_message_read_receipt_unshare)
                  .setCancelable(true)
                  .setNegativeButton(android.R.string.cancel, null)
                  .setPositiveButton(R.string.preferences_dialog_unshare_profile_key, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                      unshareReadReceipt();
                    }
                  }).show();
        }
        return true;
      }

      void shareReadReceipt () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareReadReceipt(getActivity(), recipient, true);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void unshareReadReceipt () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareReadReceipt(getActivity(), recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    private class ReadReceiptIgnoredChangeListener implements Preference.OnPreferenceChangeListener {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.preferences_ignore_read_receipt)
                .setMessage(R.string.preferences_dialog_message_read_receipt_ignore)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.preferences_dialog_Ignore, new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {
                    ignoreReadReceipt();
                  }
                }).show();
        return true;
      }

      void ignoreReadReceipt () {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            //this is local change
            DatabaseFactory.getRecipientDatabase(getContext()).setReadReceiptShared(recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    private class ContactSharingChangeListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        if (recipient.isContactSharing()) handleUnShareContact(preference.getContext());
        else                              handleShareContact(preference.getContext());

        return true;
      }

      private void handleShareContact(@NonNull final Context context) {
        new AsyncTask<Void, Void, Pair<Integer, Integer>>() {
          @Override
          protected Pair<Integer, Integer> doInBackground(Void... voids) {
            int titleRes = R.string.RecipientPreferenceActivity_share_contact_question;
            int bodyRes  = R.string.RecipientPreferenceActivity_share_contact_description;

            return new Pair<>(titleRes, bodyRes);
          }

          @Override
          protected void onPostExecute(Pair<Integer, Integer> titleAndBody) {
            new AlertDialog.Builder(context)
                    .setTitle(titleAndBody.first)
                    .setMessage(titleAndBody.second)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.RecipientPreferenceActivity_allow, (dialog, which) -> shareRecipient(context, recipient))
                    .show();
          }
        }.execute();
      }

      private void handleUnShareContact(@NonNull Context context) {
        int titleRes = R.string.RecipientPreferenceActivity_unshare_contact_question;
        int bodyRes  = R.string.RecipientPreferenceActivity_unshare_contact_description;

        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.RecipientPreferenceActivity_disallow, (dialog, which) -> unShareRecipient(context, recipient))
                .show();
        //
        //todo: this is not a fence pref -> update ingnore for users
//                    UfsrvUserUtils.UfsrvSetFencePreference(getActivity(), recipient, SignalServiceProtos.FenceUserPrefs.IGNORING, false);
      }

      void shareRecipient (@NonNull final Context context, final Recipient recipient) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareContact(context, recipient, true);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void unShareRecipient (@NonNull final Context context, final Recipient recipient) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareContact(context, recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    private class GeogroupStickyChangeListener implements Preference.OnPreferenceChangeListener {
      boolean isOk=false;
      String  jsonResponse;

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        new GeogroupStickyModePushMessagesTask((ListPreference)preference, newValue).execute();
        return isOk;
      }

      private class GeogroupStickyModePushMessagesTask extends ProgressDialogAsyncTask<Void, Void, Integer>
      {
        private final ListPreference listPreference;
        private final Object newValue;
        private static final int SUCCESS = 0;
        private static final int NETWORK_ERROR = 1;

        public GeogroupStickyModePushMessagesTask (final ListPreference listPreference, final Object newValue)
        {
          super(getActivity(), R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings, R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings);
          this.listPreference = listPreference;
          this.newValue           = newValue;
        }

        @Override
        protected void onPostExecute (Integer result)
        {
          super.onPostExecute(result);
          switch (result)
          {
            case NETWORK_ERROR:
              Toast.makeText(getActivity(),
                      R.string.ApplicationPreferencesActivity_error_connecting_to_server,
                      Toast.LENGTH_LONG).show();
              break;
            case SUCCESS:
                Log.d(TAG, format("json: '%s'", jsonResponse));
                isOk = true;
              break;
          }
        }

        @Override
        protected Integer doInBackground (Void... params)
        {
          try
          {
            Context context = getActivity();
            SignalServiceAccountManager accountManager = AccountManagerFactory.createManager(context);

            int   value                                   = Integer.parseInt((String) newValue);
            final GeogroupStickyState geogroupStickyState = GeogroupStickyState.fromId(value);

            try
            {
              //jsonResponse=accountManager.setUserPreferences(Optional.fromNullable(getGeogroupStickyModeInJson(listPreference, newValue)));
              jsonResponse=accountManager.setStickyGeogroupPreference(fid, (value==0?0:1));
            }
            catch (AuthorizationFailedException e)
            {
              Log.w(TAG, e);
            }

            recipient.setGeogroupSticky(geogroupStickyState);
            DatabaseFactory.getRecipientDatabase(getActivity())
                    .setGeogroupSticky(recipient, geogroupStickyState);

            return SUCCESS;
          }
          catch (IOException ioe)
          {
            Log.w(TAG, ioe);
            return NETWORK_ERROR;
          }
        }
      }
    }

    /**
     * Retrieve all groups which have the sticky geo pref set for it
     * @param preference
     * @param newValue
     * @return
     */
    private String getGeogroupStickyModeInJson (final Preference preference, Object newValue)
    {
      ListPreference             geogroupStickyPreference  = (ListPreference) this.findPreference(PREFERENCE_GSTICKY);

      JsonEntityUserPreferences userPreferences;
      RecipientDatabase database  = DatabaseFactory.getRecipientDatabase(getContext());
      RecipientDatabase.StickyGeogroupsReader  reader    = database.readerForStickyGeogroups(database.getStickyGeogroups());
      List<String> sticky       = new LinkedList<>();

      Recipient recipient;

      while ((recipient = reader.getNext()) != null) {
        if (recipient.isGroupRecipient()) {
          sticky.add(recipient.getAddress().serialize());
        }
      }

      Log.d(TAG, format("getGeogroupStickyModeInJson: List contain '%d' entries", sticky.size()));

      userPreferences=new JsonEntityUserPreferences();
      userPreferences.setStickyGeogroups(sticky);
      return JsonUtil.toJson(userPreferences);
    }

    //
    private class GroupPermissionChangeListener implements Preference.OnPreferenceChangeListener {
      boolean isOk=false;

      public GroupPermissionChangeListener () {
      }
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        new GroupPermissionChangePushMessageTask((CustomMultiSelectListPreference)preference, newValue).execute();
        return isOk;
      }

      private class GroupPermissionChangePushMessageTask extends ProgressDialogAsyncTask<Void, Void, Integer>
      {
        private final CustomMultiSelectListPreference listPreference;
        private final Set<String> newValues;
        private ProgressDialog dialog;
        private static final int SUCCESS = 0;
        private static final int NETWORK_ERROR = 1;

        public GroupPermissionChangePushMessageTask (final CustomMultiSelectListPreference listPreference, final Object newValue)
        {
          super(getActivity(), R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings, R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings);
          this.listPreference = listPreference;
          this.newValues      = (Set)newValue;
        }

        @Override
        protected void onPreExecute() {
          if (groupRecord.getOwnerUserId()!=TextSecurePreferences.getUserId(getContext())) {
            Toast.makeText(getActivity(),
                             R.string.you_dont_have_permissions_to_change_group,
                             Toast.LENGTH_LONG).show();
            return;
          }

          dialog = ProgressDialog.show(getActivity(),
                                       getActivity().getString(R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings),
                                       getActivity().getString(R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings),
                                       true, false);
        }

        @Override
        protected Integer doInBackground (Void... params)
        {
          Context context = getActivity();
          PermissionMembersDelta  usersDelta = new PermissionMembersDelta(listPreference.getOldValues(), newValues);

          Stream.of(usersDelta.getAddedMembers()).forEach((uid) -> UfsrvFenceUtils.sendFencePermissionCommand(context, recipient, fid, uid, permissionsPrefKeyHashMap.get(listPreference.getKey()), CommandArgs.ADDED_VALUE));

          Stream.of(usersDelta.getDeletedMembers()).forEach((uid) -> UfsrvFenceUtils.sendFencePermissionCommand(context, recipient, fid, uid, permissionsPrefKeyHashMap.get(listPreference.getKey()), CommandArgs.DELETED_VALUE));

          return SUCCESS;
        }

        @Override
        protected void onPostExecute(Integer success) {
         if (dialog != null) dialog.cancel();
        }
      }

      class PermissionMembersDelta {
        private Set<String> oldSelectedValues;
        private Set<String> newSelectecValues;

        public PermissionMembersDelta (Set oldValues,Set newValues)
        {
          this.oldSelectedValues = oldValues;
          this.newSelectecValues = newValues;
        }

        public ArrayList<Long> getDeletedMembers() {
          ArrayList<Long> deletedMembers = new ArrayList();
          for (String oldMember: oldSelectedValues) {
            if (!newSelectecValues.contains(oldMember)) deletedMembers.add(Long.valueOf(oldMember));
          }
          return deletedMembers;
        }

        public ArrayList<Long> getAddedMembers() {
          ArrayList<Long> addedMembers = new ArrayList();
          for (String newMember: newSelectecValues) {
            if (!oldSelectedValues.contains(newMember))
              addedMembers.add(Long.valueOf(newMember));
          }
          return addedMembers;
        }
      }
    }
    //

    private class ColorChangeListener implements Preference.OnPreferenceChangeListener {

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        if (context == null) return true;

        final int           value         = (Integer) newValue;
        final MaterialColor selectedColor = MaterialColors.CONVERSATION_PALETTE.getByColor(context, value);
        final MaterialColor currentColor  = recipient.getColor();

        if (selectedColor == null) return true;

        if (preference.isEnabled() && !currentColor.equals(selectedColor)) {

          new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
              DatabaseFactory.getRecipientDatabase(context).setColor(recipient, selectedColor);

              if (recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
                ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(context, recipient.getAddress()));
              }
              return null;
            }
          }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        return true;
      }
    }

    private class MuteClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        if (recipient.isMuted()) handleUnmute(preference.getContext());
        else                     handleMute(preference.getContext());

        return true;
      }

      private void handleMute(@NonNull Context context) {
        MuteDialog.show(context, until -> setMuted(context, recipient, until));

        setSummaries(recipient);
      }

      private void handleUnmute(@NonNull Context context) {
        setMuted(context, recipient, 0);
      }

      private void setMuted(@NonNull final Context context, final Recipient recipient, final long until) {
        recipient.setMuted(until);

        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            DatabaseFactory.getRecipientDatabase(context)
                    .setMuted(recipient, until);
            return null;
          }
       }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }
    }

    private class IdentityClickedListener implements Preference.OnPreferenceClickListener {

      private final IdentityRecord identityKey;

      private IdentityClickedListener(IdentityRecord identityKey) {
       Log.w(TAG, "Identity record: " + identityKey);
        this.identityKey = identityKey;
      }

      @Override
      public boolean onPreferenceClick(Preference preference) {
        Intent verifyIdentityIntent = new Intent(preference.getContext(), VerifyIdentityActivity.class);
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.ADDRESS_EXTRA, recipient.getAddress());
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.IDENTITY_EXTRA, new IdentityKeyParcelable(identityKey.getIdentityKey()));
        verifyIdentityIntent.putExtra(VerifyIdentityActivity.VERIFIED_EXTRA, identityKey.getVerifiedStatus() == IdentityDatabase.VerifiedStatus.VERIFIED);
        startActivity(verifyIdentityIntent);

        return true;
      }
    }

    private class BlockClickedListener implements Preference.OnPreferenceClickListener {
      @Override
      public boolean onPreferenceClick(Preference preference) {
        if (recipient.isBlocked()) handleUnblock(preference.getContext());
        else                       handleBlock(preference.getContext());

        return true;
      }

//      private void handleBlock() {
//        new AlertDialog.Builder(getActivity())
//                .setTitle(R.string.RecipientPreferenceActivity_block_this_contact_question)
//                .setMessage(R.string.RecipientPreferenceActivity_you_will_no_longer_receive_messages_and_calls_from_this_contact)
//                .setCancelable(true)
//                .setNegativeButton(android.R.string.cancel, null)
//                .setPositiveButton(R.string.RecipientPreferenceActivity_block, new DialogInterface.OnClickListener() {
//                  @Override
//                  public void onClick(DialogInterface dialog, int which) {
//                    setBlocked(recipient, true);
//
//                    //
//                    UfsrvUserUtils.UfsrvSetFencePreference(getActivity(), recipient, SignalServiceProtos.FenceUserPrefs.IGNORING, true);
//                  }
//                }).show();
//      }

      private void handleBlock(@NonNull final Context context) {
        new AsyncTask<Void, Void, Pair<Integer, Integer>>() {
          @Override
          protected Pair<Integer, Integer> doInBackground(Void... voids) {
            int titleRes = R.string.RecipientPreferenceActivity_block_this_contact_question;
            int bodyRes  = R.string.RecipientPreferenceActivity_you_will_no_longer_receive_messages_and_calls_from_this_contact;

            if (recipient.isGroupRecipient()) {
              bodyRes = R.string.RecipientPreferenceActivity_block_and_leave_group_description;

              if (recipient.isGroupRecipient() && DatabaseFactory.getGroupDatabase(context).isActive(recipient.getAddress().toGroupString())) {
                titleRes = R.string.RecipientPreferenceActivity_block_and_leave_group;
              } else {
                titleRes = R.string.RecipientPreferenceActivity_block_group;
              }
            }

            return new Pair<>(titleRes, bodyRes);
          }

          @Override
          protected void onPostExecute(Pair<Integer, Integer> titleAndBody) {
            new AlertDialog.Builder(context)
                    .setTitle(titleAndBody.first)
                    .setMessage(titleAndBody.second)
                    .setCancelable(true)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.RecipientPreferenceActivity_block, (dialog, which) -> {
                      blockRecipient(context, recipient);
                    }).show();
          }
        }.execute();
      }

      private void handleUnblock(@NonNull Context context) {
        int titleRes = R.string.RecipientPreferenceActivity_unblock_this_contact_question;
        int bodyRes  = R.string.RecipientPreferenceActivity_you_will_once_again_be_able_to_receive_messages_and_calls_from_this_contact;

        if (recipient.isGroupRecipient()) {
          titleRes = R.string.RecipientPreferenceActivity_unblock_this_group_question;
          bodyRes  = R.string.RecipientPreferenceActivity_unblock_this_group_description;
        }

        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.RecipientPreferenceActivity_unblock, (dialog, which) -> unblockRecipient(context, recipient)).show();
        //
        //todo: this is not a fence pref -> update ingnore for users
//                    UfsrvUserUtils.UfsrvSetFencePreference(getActivity(), recipient, SignalServiceProtos.FenceUserPrefs.IGNORING, false);
      }

      private void setBlocked(@NonNull final Context context, final Recipient recipient, final boolean blocked) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            DatabaseFactory.getRecipientDatabase(context)
                    .setBlocked(recipient, blocked);
            ApplicationContext.getInstance(context)
                    .getJobManager()
                    .add(new MultiDeviceBlockedUpdateJob());
            return null;
          }
       }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void blockRecipient (@NonNull final Context context, final Recipient recipient) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareBlocking(context, recipient, true);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }

      void unblockRecipient (@NonNull final Context context, final Recipient recipient) {
        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {

            UfsrvUserUtils.UfsrvShareBlocking(context, recipient, false);
            return null;
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

      }
    }

    private void setBlocked(final Recipient recipient, final boolean blocked) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          Context context = getActivity();

          DatabaseFactory.getRecipientDatabase(context)
                  .setBlocked(recipient, blocked);

          if (recipient.isGroupRecipient() && DatabaseFactory.getGroupDatabase(context).isActive(recipient.getAddress().toGroupString())) {
            long                                threadId     = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
            Optional<OutgoingGroupMediaMessage> leaveMessage = GroupUtil.createGroupLeaveMessage(context, recipient);

            if (threadId != -1 && leaveMessage.isPresent()) {
              MessageSender.send(context, leaveMessage.get(), threadId, false, null);

              GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
              String        groupId       = recipient.getAddress().toGroupString();
              //- done upon confirmation from backend
//              groupDatabase.setActive(groupId, false);
//              groupDatabase.remove(groupId, Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
            } else {
              Log.w(TAG, "Failed to leave group. Can't block.");
              Toast.makeText(context, R.string.RecipientPreferenceActivity_error_leaving_group, Toast.LENGTH_LONG).show();
            }
          }

          if (blocked && (recipient.resolve().isSystemContact() || recipient.resolve().isProfileSharing())) {
            ApplicationContext.getInstance(context)
                    .getJobManager()
                    .add(new RotateProfileKeyJob());
          }

          //- todo: MultiDeviceBlockedUpdateJob disabled
//          ApplicationContext.getInstance(context)
//                  .getJobManager()
//                  .add(new MultiDeviceBlockedUpdateJob());
          return null;
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class AboutNumberClickedListener implements ContactPreference.Listener {

      @Override
      public void onMessageClicked() {
        //CommunicationActions.startConversation(getContext(), recipient, null); //-
        Recipient me = Recipient.fromUfsrvUid(getContext(), new UfsrvUid(TextSecurePreferences.getUfsrvUserId(getContext())), false);
        List<Long>fids=UfsrvFenceUtils.getForTwoGroups(me, recipient);
        if (fids.isEmpty()) {
          UfsrvFenceUtils.createGroupForTwo (getContext(), recipient);
        } else {
          Recipient recipientGroup = Recipient.fromFid(getContext(), fids.get(0).longValue(), false);
          long threadId            = DatabaseFactory.getThreadDatabase(getContext()).getThreadIdFor(null, fids.get(0).longValue());
          UfsrvFenceUtils.openConversation(getContext(), threadId, recipientGroup, ThreadDatabase.DistributionTypes.DEFAULT, Optional.absent(), Optional.absent());
        }
      }

      @Override
      public void onSecureCallClicked() {
        Recipient me = Recipient.fromUfsrvUid(getContext(), new UfsrvUid(TextSecurePreferences.getUfsrvUserId(getContext())), false);
        List<Long>fids=UfsrvFenceUtils.getForTwoGroups(me, recipient);
        if (fids.size()>0) {
          CommunicationActions.startVoiceCall(getActivity(), recipient, fids.get(0).longValue());
        } else {
          Toast.makeText(getContext(), R.string.you_dont_have_private_group_with_this_user, Toast.LENGTH_LONG).show();
        }
      }

      @Override
      public void onInSecureCallClicked() {
        try {
          Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                                         Uri.parse("tel:" + recipient.getAddress().serialize()));
          startActivity(dialIntent);
        } catch (ActivityNotFoundException anfe) {
          Log.w(TAG, anfe);
          Dialogs.showAlertDialog(getContext(),
                                  getString(R.string.ConversationActivity_calls_not_supported),
                                  getString(R.string.ConversationActivity_this_device_does_not_appear_to_support_dial_actions));
        }
      }
    }

    private class CustomNotificationsChangedListener implements Preference.OnPreferenceChangeListener {

      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = preference.getContext();
        final boolean enabled = (boolean) newValue;

        new AsyncTask<Void, Void, Void>() {
          @Override
          protected Void doInBackground(Void... params) {
            if (enabled) {
              String channel = NotificationChannels.createChannelFor(context, recipient);
              DatabaseFactory.getRecipientDatabase(context).setNotificationChannel(recipient, channel);
            } else {
              NotificationChannels.deleteChannelFor(context, recipient);
              DatabaseFactory.getRecipientDatabase(context).setNotificationChannel(recipient, null);
            }
            return null;
          }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);

        return true;
      }
    }

    private class ReloadGroupClickListener implements Preference.OnPreferenceChangeListener {

      @Override
      public boolean onPreferenceChange (final Preference preference, Object newValue)
      {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIconAttribute(R.attr.dialog_info_icon);
        builder.setTitle(R.string.recipient_preferences__reload_group);
        builder.setMessage(R.string.recipient_preferences__reload_group);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {

            //just for visuals
//          PreferenceFragment frag=(PreferenceFragment)(getActivity()).getSupportFragmentManager().findFragmentById(R.id.container);
            PreferenceFragment frag=(PreferenceFragment)(getActivity()).getFragmentManager().findFragmentById(R.id.container);
            CheckBoxPreference pref=(CheckBoxPreference)frag.findPreference("pref_toggle_reload_group");
            pref.setChecked(false);
            dialog.cancel();

          }
        });
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            ApplicationContext.getInstance(ApplicationContext.getInstance())
                    .getJobManager()
                    .add(new ReloadGroupJob(fid));

          }
        });
        builder.show();

        ((CheckBoxPreference)preference).setChecked(true);
        return false;
      }
    }

    @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
    public void onEvent(AppEventFenceUserPref event)
    {
      switch (event.getFenceUserPreference().getPrefId()) {
        case PROFILE_SHARING:
          if (event.commandArgsServer==CommandArgs.ACCEPTED) {
            if (true || event.commandArgsClient==CommandArgs.SET) {
              ((RecipientPreferenceActivity)Utils.getHostActivity(getContext())).postTimedNotification("Success! Profile sharing set.");
              setSummaries(recipient);
            }
          } else if (event.commandArgsServer==CommandArgs.REJECTED) {
            ((RecipientPreferenceActivity)Utils.getHostActivity(getContext())).postTimedNotification("Could not change profile sharing.");
          }
          break;
        case STICKY_GEOGROUP:
          break;
      }
    }

    @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
    public void onEvent(AppEventUserPrefProfileKey event)
    {
      switch (event.getUserPreference().getPrefId()) {
        case PROFILE:
          if (event.commandArgsServer==CommandArgs.ACCEPTED) {
            if (true || event.commandArgsClient==CommandArgs.SET) {
              ((RecipientPreferenceActivity)Utils.getHostActivity(getContext())).postTimedNotification(getContext().getString(R.string.success_profile_sharing_with_x, event.getRecipient().getDisplayName()));
              setSummaries(recipient);
            }
          } else if (event.commandArgsServer==CommandArgs.REJECTED) {
            ((RecipientPreferenceActivity)Utils.getHostActivity(getContext())).postTimedNotification("Could not change profile sharing.");
          }
          break;
      }

    }

  }
}
