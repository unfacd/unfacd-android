package org.thoughtcrime.securesms.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragment;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import org.thoughtcrime.securesms.logging.Log;
import android.widget.Toast;

import com.google.firebase.iid.FirebaseInstanceId;


import com.unfacd.android.ApplicationContext;
import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.LogSubmitActivity;
import com.unfacd.android.R;
import com.unfacd.android.jobs.ResetGroupsJob;

import org.thoughtcrime.securesms.RegistrationActivity;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.contacts.ContactIdentityManager;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;

import java.io.IOException;


public class AdvancedPreferenceFragment extends CorrectedPreferenceFragment
{
  private static final String TAG = AdvancedPreferenceFragment.class.getSimpleName();

  private static final String PUSH_MESSAGING_PREF   = "pref_toggle_push_messaging";
  private static final String SUBMIT_DEBUG_LOG_PREF = "pref_submit_debug_logs";

  private static final int PICK_IDENTITY_CONTACT = 1;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    initializeIdentitySelection();

    Preference submitDebugLog = this.findPreference(SUBMIT_DEBUG_LOG_PREF);
    submitDebugLog.setOnPreferenceClickListener(new SubmitDebugLogListener());
    submitDebugLog.setSummary(getVersion(getActivity()));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
     addPreferencesFromResource(R.xml.preferences_advanced);
   }

  @Override
  public void onResume() {
    super.onResume();
    ((ApplicationPreferencesActivity) getActivity()).getSupportActionBar().setTitle(R.string.preferences__advanced);

    initializePushMessagingToggle();
    initializeResetGroupsToggle();
  }

  @Override
  public void onDestroy ()
  {
    super.onDestroy();
  }

  @Override
  public void onActivityResult(int reqCode, int resultCode, Intent data) {
    super.onActivityResult(reqCode, resultCode, data);

    Log.w(TAG, "Got result: " + resultCode + " for req: " + reqCode);
    if (resultCode == Activity.RESULT_OK && reqCode == PICK_IDENTITY_CONTACT) {
      handleIdentitySelection(data);
    }
  }

  private void initializePushMessagingToggle() {
    CheckBoxPreference preference = (CheckBoxPreference)this.findPreference(PUSH_MESSAGING_PREF);

    if (TextSecurePreferences.isPushRegistered(getActivity())) {
      preference.setChecked(true);
      preference.setSummary(TextSecurePreferences.getLocalNumber(getActivity()));

    } else {
      preference.setChecked(false);
//      preference.setSummary(R.string.preferences__free_private_messages_and_calls);//-
      preference.setSummary(R.string.preferences__signal_messages_and_calls_cancelled);
    }

    preference.setOnPreferenceChangeListener(new PushMessagingClickListener());
  }

  private void initializeResetGroupsToggle() {
    ((CheckBoxPreference)this.findPreference("pref_toggle_reset_membership")).setChecked(false);
    this.findPreference("pref_toggle_reset_membership")
            .setOnPreferenceChangeListener(new ResetGroupsClickListener());
  }

  private void initializeIdentitySelection() {
    ContactIdentityManager identity = ContactIdentityManager.getInstance(getActivity());

    Preference preference = this.findPreference(TextSecurePreferences.IDENTITY_PREF);

    if (identity.isSelfIdentityAutoDetected()) {
      this.getPreferenceScreen().removePreference(preference);
    } else {
      Uri contactUri = identity.getSelfIdentityUri();

      if (contactUri != null) {
        String contactName = ContactAccessor.getInstance().getNameFromContact(getActivity(), contactUri);
        preference.setSummary(String.format(getString(R.string.ApplicationPreferencesActivity_currently_s),
                                            contactName));
      }

      preference.setOnPreferenceClickListener(new IdentityPreferenceClickListener());
    }
  }

  private @NonNull String getVersion(@Nullable Context context) {
    try {
      if (context == null) return "";

      String app     = context.getString(R.string.app_name);
      String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;

      return String.format("%s %s", app, version);
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, e);
      return context.getString(R.string.app_name);
    }
  }

  private class IdentityPreferenceClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      Intent intent = new Intent(Intent.ACTION_PICK);
      intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
      startActivityForResult(intent, PICK_IDENTITY_CONTACT);
      return true;
    }
  }

  private void handleIdentitySelection(Intent data) {
    Uri contactUri = data.getData();

    if (contactUri != null) {
      TextSecurePreferences.setIdentityContactUri(getActivity(), contactUri.toString());
      initializeIdentitySelection();
    }
  }

  private class SubmitDebugLogListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
      final Intent intent = new Intent(getActivity(), LogSubmitActivity.class);
      startActivity(intent);
      return true;
    }
  }

  private class WebRtcClickListener implements Preference.OnPreferenceChangeListener {

    boolean isOk=false;
    String  jsonResponse;

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      new WebRtcPreferenceTask((CheckBoxPreference)preference, newValue).execute();
      return isOk;
    }

    private class WebRtcPreferenceTask extends ProgressDialogAsyncTask<Void, Void, Integer>
    {
      private final CheckBoxPreference checkBoxPreference;
      private final Object newValue;
      private static final int SUCCESS = 0;
      private static final int NETWORK_ERROR = 1;

      public WebRtcPreferenceTask (final CheckBoxPreference checkBoxPreference, final Object newValue)
      {
        super(getActivity(), R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings, R.string.ApplicationPreferencesActivity_groups_updating_your_group_settings);
        this.checkBoxPreference = checkBoxPreference;
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
            Log.d(TAG, String.format("json: '%s'", jsonResponse));
            TextSecurePreferences.setWebrtcCallingEnabled(getContext(), (Boolean)newValue);
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

          try
          {
            jsonResponse=accountManager.setTogglableUserPreference ("video_calling", "true".equals(newValue.toString())?1:0);
          }
          catch (AuthorizationFailedException e)
          {
            Log.w(TAG, e);
            return NETWORK_ERROR;
          }

          return SUCCESS;
        }
        catch (IOException ioe)
        {
          Log.w(TAG, ioe);
          return NETWORK_ERROR;
        }
      }
    }

//    @Override
//    public boolean onPreferenceChange(Preference preference, Object newValue) {
//
//      TextSecurePreferences.setWebrtcCallingEnabled(getContext(), (Boolean)newValue);
//      ApplicationContext.getInstance(getContext())
//              .getJobManager()
//              .add(new RefreshAttributesJob(getContext()));
//      return true;
//    }
  }

  private class ResetGroupsClickListener implements Preference.OnPreferenceChangeListener {

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
      builder.setIconAttribute(R.attr.dialog_info_icon);
      builder.setTitle(R.string.preferences__reset_group_membership);
      builder.setMessage(R.string.preferences__reset_group_membership_dialog);
      builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {

          //just for visuals
//          PreferenceFragment frag=(PreferenceFragment)(getActivity()).getSupportFragmentManager().findFragmentById(R.id.container);
          PreferenceFragment frag=(PreferenceFragment)(getActivity()).getFragmentManager().findFragmentById(R.id.container);
          CheckBoxPreference pref=(CheckBoxPreference)frag.findPreference("pref_toggle_reset_membership");
          pref.setChecked(false);
          dialog.cancel();

        }
      });
      builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          ApplicationContext.getInstance(ApplicationContext.getInstance())
                  .getJobManager()
                  .add(new ResetGroupsJob());

        }
      });
      builder.show();

      ((CheckBoxPreference)preference).setChecked(true);
      return false;
    }
  }

  private class PushMessagingClickListener implements Preference.OnPreferenceChangeListener {
    private static final int SUCCESS       = 0;
    private static final int NETWORK_ERROR = 1;

    private class DisablePushMessagesTask extends ProgressDialogAsyncTask<Void, Void, Integer> {
      private final CheckBoxPreference checkBoxPreference;

      public DisablePushMessagesTask(final CheckBoxPreference checkBoxPreference) {
        super(getActivity(), R.string.ApplicationPreferencesActivity_unregistering, R.string.ApplicationPreferencesActivity_unregistering_from_signal_messages_and_calls);
        this.checkBoxPreference = checkBoxPreference;
      }

      @Override
      protected void onPostExecute(Integer result) {
        super.onPostExecute(result);
        switch (result) {
        case NETWORK_ERROR:
          Toast.makeText(getActivity(),
                         R.string.ApplicationPreferencesActivity_error_connecting_to_server,
                         Toast.LENGTH_LONG).show();
          break;
        case SUCCESS:
          TextSecurePreferences.setPushRegistered(getActivity(), false);
          //
          TextSecurePreferences.setUfsrvCookie(getActivity(), null);
          TextSecurePreferences.setUfsrvUfAccountCreated(getActivity(), false);
          //

          initializePushMessagingToggle();
          break;
        }
      }

      @Override
      protected Integer doInBackground(Void... params) {
        try {
          Context                     context        = getActivity();
          SignalServiceAccountManager accountManager = AccountManagerFactory.createManager(context);

          try {
            accountManager.setGcmId(Optional.<String>absent());// null should really go away
          } catch (AuthorizationFailedException e) {
            Log.w(TAG, e);
          }

          if (!TextSecurePreferences.isFcmDisabled(context)) {
            FirebaseInstanceId.getInstance().deleteInstanceId();
          }

          return SUCCESS;
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
          return NETWORK_ERROR;
        }
      }
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, Object newValue) {
      if (((CheckBoxPreference)preference).isChecked()) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setIconAttribute(R.attr.dialog_info_icon);
        builder.setTitle(R.string.ApplicationPreferencesActivity_disable_signal_messages_and_calls);
        builder.setMessage(R.string.ApplicationPreferencesActivity_disable_signal_messages_and_calls_by_unregistering);
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            new DisablePushMessagesTask((CheckBoxPreference)preference).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
          }
        });
        builder.show();
      } else {

        //
        TextSecurePreferences.setUfsrvCookie(getActivity(), null);
        TextSecurePreferences.setUfsrvUfAccountCreated(getActivity(), false);
        //

        Intent nextIntent = new Intent(getActivity(), ApplicationPreferencesActivity.class);

        Intent intent = new Intent(getActivity(), RegistrationActivity.class);
        intent.putExtra(RegistrationActivity.RE_REGISTRATION_EXTRA, true);
        intent.putExtra("next_intent", nextIntent);
        startActivity(intent);
      }

      return false;
    }
  }

}
