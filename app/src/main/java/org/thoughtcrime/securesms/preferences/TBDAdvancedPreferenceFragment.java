package org.thoughtcrime.securesms.preferences;


public class TBDAdvancedPreferenceFragment /*extends CorrectedPreferenceFragment*/
{
  /*private static final String TAG = Log.tag(AdvancedPreferenceFragment.class);

  private static final String PUSH_MESSAGING_PREF   = "pref_toggle_push_messaging";
  private static final String SUBMIT_DEBUG_LOG_PREF = "pref_submit_debug_logs";
  private static final String INTERNAL_PREF         = "pref_internal";
  private static final String ADVANCED_PIN_PREF     = "pref_advanced_pin_settings";
  private static final String DELETE_ACCOUNT        = "pref_delete_account";

  private static final int PICK_IDENTITY_CONTACT = 1;
  private static final int TRANSFER_CURRENCY     = 2;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    initializeIdentitySelection();

    Preference submitDebugLog = this.findPreference(SUBMIT_DEBUG_LOG_PREF);
    submitDebugLog.setOnPreferenceClickListener(new SubmitDebugLogListener());
    submitDebugLog.setSummary(getVersion(getActivity()));

    Preference pinSettings = this.findPreference(ADVANCED_PIN_PREF);
    pinSettings.setOnPreferenceClickListener(preference -> {
      getApplicationPreferencesActivity().pushFragment(new AdvancedPinPreferenceFragment());
      return false;
    });

    Preference internalPreference = this.findPreference(INTERNAL_PREF);
    internalPreference.setVisible(true);//FeatureFlags.internalUser());//AA+ true
    internalPreference.setOnPreferenceClickListener(preference -> {
      if (true || FeatureFlags.internalUser()) {//AA+ true always display
        getApplicationPreferencesActivity().pushFragment(new InternalOptionsPreferenceFragment());
        return true;
      } else {
        return false;
      }
    });

    Preference deleteAccount = this.findPreference(DELETE_ACCOUNT);
    deleteAccount.setOnPreferenceClickListener(preference -> {
      Money.MobileCoin latestBalance = SignalStore.paymentsValues().mobileCoinLatestBalance().getFullAmount().requireMobileCoin();

      if (!latestBalance.equals(Money.MobileCoin.ZERO)) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.AdvancedPreferenceFragment__transfer_mob_balance)
                .setMessage(getString(R.string.AdvancedPreferenceFragment__you_have_a_balance_of_s, latestBalance.toString(FormatterOptions.defaults())))
                .setPositiveButton(R.string.AdvancedPreferenceFragment__transfer, (dialog, which) -> {
                  Intent intent = new Intent(requireContext(), PaymentsActivity.class);
                  intent.putExtra(PaymentsActivity.EXTRA_PAYMENTS_STARTING_ACTION, R.id.action_directly_to_paymentsTransfer);
                  intent.putExtra(PaymentsActivity.EXTRA_STARTING_ARGUMENTS, new PaymentsTransferFragmentArgs.Builder().setFinishOnConfirm(true).build().toBundle());
                  startActivityForResult(intent, TRANSFER_CURRENCY);
                  dialog.dismiss();
                })
                .setNegativeButton(SpanUtil.color(ContextCompat.getColor(requireContext(), R.color.signal_alert_primary), getString(R.string.AdvancedPreferenceFragment__dont_transfer)), (dialog, which) -> {
                  getApplicationPreferencesActivity().pushFragment(new DeleteAccountFragment());
                  dialog.dismiss();
                })
                .show();
      } else {
        getApplicationPreferencesActivity().pushFragment(new DeleteAccountFragment());
      }
      return false;
    });
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.signal_background_tertiary));

    View                   list   = view.findViewById(R.id.recycler_view);
    ViewGroup.LayoutParams params = list.getLayoutParams();

    params.height = ActionBar.LayoutParams.WRAP_CONTENT;
    list.setLayoutParams(params);
    list.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.signal_background_primary));
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
     addPreferencesFromResource(R.xml.preferences_advanced);
   }

  @Override
  public void onResume() {
    super.onResume();
    getApplicationPreferencesActivity().getSupportActionBar().setTitle(R.string.preferences__advanced);

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
    Log.i(TAG, "Got result: " + resultCode + " for req: " + reqCode);
    if (resultCode == Activity.RESULT_OK && reqCode == PICK_IDENTITY_CONTACT) {
      handleIdentitySelection(data);
    } else if (resultCode == Activity.RESULT_OK && reqCode == TRANSFER_CURRENCY) {
      getApplicationPreferencesActivity().pushFragment(new DeleteAccountFragment());
    }
  }

  private @NonNull ApplicationPreferencesActivity getApplicationPreferencesActivity() {
    return (ApplicationPreferencesActivity) requireActivity();
  }

  private void initializePushMessagingToggle() {
    CheckBoxPreference preference = (CheckBoxPreference)this.findPreference(PUSH_MESSAGING_PREF);

    if (TextSecurePreferences.isPushRegistered(getActivity())) {
      preference.setChecked(true);
      preference.setSummary(PhoneNumberFormatter.prettyPrint(TextSecurePreferences.getLocalNumber(getActivity())));

    } else {
      preference.setChecked(false);
//      preference.setSummary(R.string.preferences__free_private_messages_and_calls);//AA-
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
    if (context == null) return "";

    String app     = context.getString(R.string.app_name);
    String version = BuildConfig.VERSION_NAME;

    return String.format("%s %s", app, version);
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
      final Intent intent = new Intent(getActivity(), SubmitDebugLogActivity.class);
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
          SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

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
      builder.setIcon(R.drawable.ic_info_outline);
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
          ApplicationDependencies.getJobManager().add(new ResetGroupsJob());

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

          //AA+
          TextSecurePreferences.setUfsrvCookie(getActivity(), null);
          TextSecurePreferences.setUfsrvUfAccountCreated(getActivity(), false);
          //

          SignalStore.registrationValues().clearRegistrationComplete();

          initializePushMessagingToggle();
          break;
        }
      }

      @Override
      protected Integer doInBackground(Void... params) {
        try {
          Context                     context        = getActivity();
          SignalServiceAccountManager accountManager =  ApplicationDependencies.getSignalServiceAccountManager();

          try {
            accountManager.setGcmId(Optional.<String>empty());//AA+ null should really go away
          } catch (AuthorizationFailedException e) {
            Log.w(TAG, e);
          }

          if (!TextSecurePreferences.isFcmDisabled(context)) {
//            FirebaseInstanceId.getInstance().deleteInstanceId();//AA-
            //AA+
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(task -> Log.d(TAG, "Token Deleted!!!"));
            FirebaseInstallations.getInstance().delete().addOnCompleteListener(task -> Log.d(TAG, "Token Deleted!!!"));
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
        builder.setIcon(R.drawable.ic_info_outline);
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

        //AA+
        TextSecurePreferences.setUfsrvCookie(getActivity(), null);
        TextSecurePreferences.setUfsrvUfAccountCreated(getActivity(), false);
        //

        startActivity(RegistrationNavigationActivity.newIntentForReRegistration(requireContext()));
      }

      return false;
    }
  }*/

}
