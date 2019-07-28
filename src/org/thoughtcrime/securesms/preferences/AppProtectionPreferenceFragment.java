package org.thoughtcrime.securesms.preferences;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvcmd.events.AppEventUserPref;
import com.unfacd.android.ui.Utils;
import com.unfacd.android.utils.UfsrvUserUtils;
import com.unfacd.android.utils.UserPrefsUtils;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import android.widget.Toast;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.BlockedContactsActivity;
import org.thoughtcrime.securesms.PassphraseChangeActivity;
import org.thoughtcrime.securesms.components.SwitchPreferenceCompat;
import org.thoughtcrime.securesms.crypto.MasterSecretUtil;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.MultiDeviceConfigurationUpdateJob;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.lock.RegistrationLockDialog;
import org.thoughtcrime.securesms.preferences.widgets.SignalListPreference;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import mobi.upod.timedurationpicker.TimeDurationPickerDialog;

// ListSummaryPreferenceFragment
public class AppProtectionPreferenceFragment extends ListSummaryPreferenceFragment {

  private static final String PREFERENCE_CATEGORY_BLOCKED        = "preference_category_blocked";
  private static final String PREFERENCE_UNIDENTIFIED_LEARN_MORE = "pref_unidentified_learn_more";

  private CheckBoxPreference disablePassphrase;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);//

    disablePassphrase = (CheckBoxPreference) this.findPreference("pref_enable_passphrase_temporary");

    this.findPreference(TextSecurePreferences.REGISTRATION_LOCK_PREF).setOnPreferenceClickListener(new AccountLockClickListener());
    this.findPreference(TextSecurePreferences.SCREEN_LOCK).setOnPreferenceChangeListener(new ScreenLockListener());
    this.findPreference(TextSecurePreferences.SCREEN_LOCK_TIMEOUT).setOnPreferenceClickListener(new ScreenLockTimeoutListener());

    this.findPreference(TextSecurePreferences.CHANGE_PASSPHRASE_PREF).setOnPreferenceClickListener(new ChangePassphraseClickListener());
    this.findPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF).setOnPreferenceClickListener(new PassphraseIntervalClickListener());
    this.findPreference(TextSecurePreferences.READ_RECEIPTS_PREF).setOnPreferenceChangeListener(new ReadReceiptToggleListener());
    this.findPreference(TextSecurePreferences.TYPING_INDICATORS).setOnPreferenceChangeListener(new TypingIndicatorsToggleListener());
    this.findPreference(TextSecurePreferences.LINK_PREVIEWS).setOnPreferenceChangeListener(new LinkPreviewToggleListener());
    this.findPreference(PREFERENCE_CATEGORY_BLOCKED).setOnPreferenceClickListener(new BlockedContactsClickListener());
    this.findPreference(TextSecurePreferences.SHOW_UNIDENTIFIED_DELIVERY_INDICATORS).setOnPreferenceChangeListener(new ShowUnidentifiedDeliveryIndicatorsChangedListener());
    this.findPreference(TextSecurePreferences.UNIVERSAL_UNIDENTIFIED_ACCESS).setOnPreferenceChangeListener(new UniversalUnidentifiedAccessChangedListener());
    this.findPreference(PREFERENCE_UNIDENTIFIED_LEARN_MORE).setOnPreferenceClickListener(new UnidentifiedLearnMoreClickListener());
    disablePassphrase.setOnPreferenceChangeListener(new DisablePassphraseClickListener());

    //
    findPreference(TextSecurePreferences.UFSRV_UNSOLICITED_CONTACT_ACTION).setOnPreferenceChangeListener(new UnsolicitedContactActionClickListener());

    initializeVisibility();
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_app_protection);
  }

  @Override
  public void onResume() {
    super.onResume();
    ((ApplicationPreferencesActivity) getActivity()).getSupportActionBar().setTitle(R.string.preferences__privacy);

    if (!TextSecurePreferences.isPasswordDisabled(getContext())) initializePassphraseTimeoutSummary();
    else                                                         initializeScreenLockTimeoutSummary();

    disablePassphrase.setChecked(!TextSecurePreferences.isPasswordDisabled(getActivity()));
  }

  //
  @Override
  public void onDestroy() {
    super.onDestroy();
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
  }

  private void initializePassphraseTimeoutSummary() {
    int timeoutMinutes = TextSecurePreferences.getPassphraseTimeoutInterval(getActivity());
    this.findPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF)
            .setSummary(getResources().getQuantityString(R.plurals.AppProtectionPreferenceFragment_minutes, timeoutMinutes, timeoutMinutes));
  }

  private void initializeScreenLockTimeoutSummary() {
    long timeoutSeconds = TextSecurePreferences.getScreenLockTimeout(getContext());
    long hours          = TimeUnit.SECONDS.toHours(timeoutSeconds);
    long minutes        = TimeUnit.SECONDS.toMinutes(timeoutSeconds) - (TimeUnit.SECONDS.toHours(timeoutSeconds) * 60  );
    long seconds        = TimeUnit.SECONDS.toSeconds(timeoutSeconds) - (TimeUnit.SECONDS.toMinutes(timeoutSeconds) * 60);

    findPreference(TextSecurePreferences.SCREEN_LOCK_TIMEOUT)
            .setSummary(timeoutSeconds <= 0 ? getString(R.string.AppProtectionPreferenceFragment_none) :
                        String.format("%02d:%02d:%02d", hours, minutes, seconds));
  }

  private void initializeVisibility() {
    if (TextSecurePreferences.isPasswordDisabled(getContext())) {
      findPreference("pref_enable_passphrase_temporary").setVisible(false);
      findPreference(TextSecurePreferences.CHANGE_PASSPHRASE_PREF).setVisible(false);
      findPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF).setVisible(false);
      findPreference(TextSecurePreferences.PASSPHRASE_TIMEOUT_PREF).setVisible(false);

      KeyguardManager keyguardManager = (KeyguardManager)getContext().getSystemService(Context.KEYGUARD_SERVICE);
      if (!keyguardManager.isKeyguardSecure()) {
        ((SwitchPreferenceCompat)findPreference(TextSecurePreferences.SCREEN_LOCK)).setChecked(false);
        findPreference(TextSecurePreferences.SCREEN_LOCK).setEnabled(false);
      }
    } else {
      findPreference(TextSecurePreferences.SCREEN_LOCK).setVisible(false);
      findPreference(TextSecurePreferences.SCREEN_LOCK_TIMEOUT).setVisible(false);
    }

    //
    this.findPreference(TextSecurePreferences.ALWAYS_RELAY_CALLS_PREF).setVisible(false);
    this.findPreference(TextSecurePreferences.TYPING_INDICATORS).setVisible(false);
    this.findPreference(TextSecurePreferences.READ_RECEIPTS_PREF).setVisible(false);
    this.findPreference(TextSecurePreferences.UNIVERSAL_UNIDENTIFIED_ACCESS).setVisible(false);

    initializeUnsolicitedContactActionSummary((SignalListPreference)findPreference(TextSecurePreferences.UFSRV_UNSOLICITED_CONTACT_ACTION));

    PreferenceCategory preferenceCategory = (PreferenceCategory) findPreference("category_sealed_sender");
    preferenceCategory.setVisible(false);

    preferenceCategory = (PreferenceCategory)findPreference("category_registration_lock");
    preferenceCategory.setVisible(false);
    //
  }

  private class ScreenLockListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      boolean enabled = (Boolean)newValue;
      TextSecurePreferences.setScreenLockEnabled(getContext(), enabled);

      Intent intent = new Intent(getContext(), KeyCachingService.class);
      intent.setAction(KeyCachingService.LOCK_TOGGLED_EVENT);
      getContext().startService(intent);
      return true;
    }
  }

  private class ScreenLockTimeoutListener implements Preference.OnPreferenceClickListener {

    @Override
    public boolean onPreferenceClick(Preference preference) {
      SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

      new TimeDurationPickerDialog(getContext(), (view, duration) -> {
        if (duration == 0) {
          TextSecurePreferences.setScreenLockTimeout(getContext(), 0);
        } else {
          long timeoutSeconds = Math.max(TimeUnit.MILLISECONDS.toSeconds(duration), 60);
          TextSecurePreferences.setScreenLockTimeout(getContext(), timeoutSeconds);
        }

        initializeScreenLockTimeoutSummary();
      }, 0).show();

      return true;
    }
  }

  private class AccountLockClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

      if (((SwitchPreferenceCompat)preference).isChecked()) {
        RegistrationLockDialog.showRegistrationUnlockPrompt(getContext(), (SwitchPreferenceCompat)preference, accountManager);
      } else {
        RegistrationLockDialog.showRegistrationLockPrompt(getContext(), (SwitchPreferenceCompat)preference, accountManager);
      }

      return true;
    }
  }

  private class BlockedContactsClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      Intent intent = new Intent(getActivity(), BlockedContactsActivity.class);
      startActivity(intent);
      return true;
    }
  }

  private class ReadReceiptToggleListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      boolean enabled = (boolean)newValue;
      ApplicationContext.getInstance(getContext())
              .getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(enabled,
                                                         TextSecurePreferences.isTypingIndicatorsEnabled(requireContext()),
                                                         TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(getContext()),
                                                         TextSecurePreferences.isLinkPreviewsEnabled(getContext())));

      return true;
    }
  }

  private class TypingIndicatorsToggleListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      boolean enabled = (boolean)newValue;
      ApplicationContext.getInstance(getContext())
              .getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(requireContext()),
                                                         enabled,
                                                         TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(getContext()),
                                                         TextSecurePreferences.isLinkPreviewsEnabled(getContext())));

      if (!enabled) {
        ApplicationContext.getInstance(requireContext()).getTypingStatusRepository().clear();
      }

      return true;
    }
  }

  private class LinkPreviewToggleListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      boolean enabled = (boolean)newValue;
      ApplicationContext.getInstance(requireContext())
              .getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(requireContext()),
                                                         TextSecurePreferences.isTypingIndicatorsEnabled(requireContext()),
                                                         TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(requireContext()),
                                                         enabled));

      return true;
    }
  }

  private class UnsolicitedContactActionClickListener implements Preference.OnPreferenceChangeListener
  {
    @Override
    public boolean onPreferenceChange(Preference preference, Object value)
    {
      boolean isOk = true;

      int action = Integer.valueOf(value.toString());
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          UfsrvUserUtils.UfsrvSetMultivalueUserPreference(getActivity(), UfsrvUserUtils.myOwnRecipient(false), SignalServiceProtos.UserPrefs.UNSOLICITED_CONTACT, Integer.valueOf(action));
          return null;
        }
      }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      return isOk;
    }

  }

  protected void initializeUnsolicitedContactActionSummary (SignalListPreference pref)
  {
    int action = Integer.valueOf(TextSecurePreferences.getUnsolicitedContactAction(getContext()));
    UserPrefsUtils.UnsolicitedContactAction contactAction = UserPrefsUtils.UnsolicitedContactAction.values()[action];
    if (contactAction == UserPrefsUtils.UnsolicitedContactAction.BLOCK) {//pref.getValue().charAt(0) == '0') {
      pref.setSummary(getString(R.string.preferences__unsolicited_contact_action_block));
    }
    else {
      pref.setSummary(getString(R.string.preferences__unsolicited_contact_action_allow));
    }

//    ListSummaryPreferenceFragment.displayList(getContext(), pref, action+"");
  }

  public static CharSequence getSummary(Context context) {
    final int    privacySummaryResId = R.string.ApplicationPreferencesActivity_privacy_summary;
    final String onRes               = context.getString(R.string.ApplicationPreferencesActivity_on);
    final String offRes              = context.getString(R.string.ApplicationPreferencesActivity_off);

    if (TextSecurePreferences.isPasswordDisabled(context) && !TextSecurePreferences.isScreenLockEnabled(context)) {
      if (TextSecurePreferences.isRegistrationtLockEnabled(context)) {
        return context.getString(privacySummaryResId, offRes, onRes);
      } else {
        return context.getString(privacySummaryResId, offRes, offRes);
      }
    } else {
      if (TextSecurePreferences.isRegistrationtLockEnabled(context)) {
        return context.getString(privacySummaryResId, onRes, onRes);
      } else {
        return context.getString(privacySummaryResId, onRes, offRes);
      }
    }
  }

  // Derecated

  private class ChangePassphraseClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      if (MasterSecretUtil.isPassphraseInitialized(getActivity())) {
        startActivity(new Intent(getActivity(), PassphraseChangeActivity.class));
      } else {
        Toast.makeText(getActivity(),
                       R.string.ApplicationPreferenceActivity_you_havent_set_a_passphrase_yet,
                       Toast.LENGTH_LONG).show();
      }

      return true;
    }
  }

  private class PassphraseIntervalClickListener implements Preference.OnPreferenceClickListener {

    @Override
    public boolean onPreferenceClick(Preference preference) {
      new TimeDurationPickerDialog(getContext(), (view, duration) -> {
        int timeoutMinutes = Math.max((int)TimeUnit.MILLISECONDS.toMinutes(duration), 1);

        TextSecurePreferences.setPassphraseTimeoutInterval(getActivity(), timeoutMinutes);

        initializePassphraseTimeoutSummary();

      }, 0).show();

      return true;
    }
  }

  private class DisablePassphraseClickListener implements Preference.OnPreferenceChangeListener {

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
      if (((CheckBoxPreference)preference).isChecked()) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.ApplicationPreferencesActivity_disable_passphrase);
        builder.setMessage(R.string.ApplicationPreferencesActivity_this_will_permanently_unlock_signal_and_message_notifications);
        builder.setIconAttribute(R.attr.dialog_alert_icon);
        builder.setPositiveButton(R.string.ApplicationPreferencesActivity_disable, (dialog, which) -> {
          MasterSecretUtil.changeMasterSecretPassphrase(getActivity(),
                                                        KeyCachingService.getMasterSecret(getContext()),
                                                        MasterSecretUtil.UNENCRYPTED_PASSPHRASE);

          TextSecurePreferences.setPasswordDisabled(getActivity(), true);
          ((CheckBoxPreference)preference).setChecked(false);

          Intent intent = new Intent(getActivity(), KeyCachingService.class);
          intent.setAction(KeyCachingService.DISABLE_ACTION);
          getActivity().startService(intent);

          initializeVisibility();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
      } else {
        Intent intent = new Intent(getActivity(), PassphraseChangeActivity.class);
        startActivity(intent);
      }

      return false;
    }
  }

  private class ShowUnidentifiedDeliveryIndicatorsChangedListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
      boolean enabled = (boolean) newValue;
      ApplicationContext.getInstance(getContext())
              .getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(getContext()),
                                                         TextSecurePreferences.isTypingIndicatorsEnabled(getContext()),
                                                         enabled,
                                                         TextSecurePreferences.isLinkPreviewsEnabled(getContext())));

      return true;
    }
  }

  private class UniversalUnidentifiedAccessChangedListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object o) {
      ApplicationContext.getInstance(getContext())
              .getJobManager()
              .add(new RefreshAttributesJob());
      return true;
    }
  }

  private class UnidentifiedLearnMoreClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      CommunicationActions.openBrowserLink(preference.getContext(), "https://unfacd.com");
      return true;
    }
  }

  @Subscribe(sticky = false, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventUserPref event)
  {
    switch (event.getUserPreference().getPrefId()) {
      case UNSOLICITED_CONTACT:
        String actionaName= UserPrefsUtils.UnsolicitedContactAction.values()[(int)event.getUserPreference().getValuesInt()].toString();
        if (event.commandArgsServer == SignalServiceProtos.CommandArgs.ACCEPTED.getNumber()) {
            ((ApplicationPreferencesActivity) Utils.getHostActivity(getContext())).postTimedNotification(getContext().getString(R.string.usepref_unsolicited_contact_action_changed, actionaName));
          initializeUnsolicitedContactActionSummary((SignalListPreference)findPreference(TextSecurePreferences.UFSRV_UNSOLICITED_CONTACT_ACTION));
        } else if (event.commandArgsServer == SignalServiceProtos.CommandArgs.REJECTED.getNumber()) {
          ((ApplicationPreferencesActivity)Utils.getHostActivity(getContext())).postTimedNotification(getContext().getString(R.string.usepref_unsolicited_contact_action_not_changed));
        }
        break;
    }
  }


}