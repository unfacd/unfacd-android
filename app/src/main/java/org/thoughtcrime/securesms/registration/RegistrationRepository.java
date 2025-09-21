package org.thoughtcrime.securesms.registration;

import android.app.Application;
import android.text.TextUtils;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;
import org.thoughtcrime.securesms.crypto.storage.SignalServiceAccountDataStoreImpl;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.RotateCertificateJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.pin.PinState;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.registration.VerifyAccountRepository.VerifyAccountWithRegistrationLockResponse;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.ProfileUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.KeyHelper;
import org.whispersystems.signalservice.api.KbsPinData;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.ServiceIdType;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Operations required for finalizing the registration of an account. This is
 * to be used after verifying the code and registration lock (if necessary) with
 * the server and being issued a UUID.
 */
public final class RegistrationRepository {

  private static final String TAG = Log.tag(RegistrationRepository.class);

  private final Application context;

  public RegistrationRepository(@NonNull Application context) {
    this.context = context;
  }

  public int getRegistrationId() {
    int registrationId = SignalStore.account().getRegistrationId();
    if (registrationId == 0) {
      registrationId = KeyHelper.generateRegistrationId(false);
      SignalStore.account().setRegistrationId(registrationId);
    }
    return registrationId;
  }

  public @NonNull ProfileKey getProfileKey(@NonNull String e164) {
    ProfileKey profileKey = findExistingProfileKey(e164);

    if (profileKey == null) {
      profileKey = ProfileKeyUtil.createNew();
      Log.i(TAG, "No profile key found, created a new one");
    }

    return profileKey;
  }

  public Single<ServiceResponse<VerifyAccountResponse>> registerAccountWithoutRegistrationLock(@NonNull RegistrationData registrationData,
                                                                                               @NonNull VerifyAccountResponse response)
  {
    return registerAccount(registrationData, response, null, null);
  }

  public Single<ServiceResponse<VerifyAccountResponse>> registerAccountWithRegistrationLock(@NonNull RegistrationData registrationData,
                                                                                            @NonNull VerifyAccountWithRegistrationLockResponse response,
                                                                                            @NonNull String pin)
  {
    return registerAccount(registrationData, response.getVerifyAccountResponse(), pin, response.getKbsData());
  }

  private Single<ServiceResponse<VerifyAccountResponse>> registerAccount(@NonNull RegistrationData registrationData,
                                                                         @NonNull VerifyAccountResponse response,
                                                                         @Nullable String pin,
                                                                         @Nullable KbsPinData kbsData)
  {
    return Single.<ServiceResponse<VerifyAccountResponse>>fromCallable(() -> {
      try {
        registerAccountInternal(registrationData, response, pin, kbsData);

        JobManager jobManager = ApplicationDependencies.getJobManager();
        jobManager.add(new DirectoryRefreshJob(false));
        jobManager.add(new RotateCertificateJob());

        //AA+
        ProfileUtil.uploadProfileVersion();//upload profile commitment and version at this stage
        SignalStore.registrationValues().markHasUploadedProfile(); //AA also do this (borrowed from uploadProfile())
        //

        DirectoryRefreshListener.schedule(context);
        RotateSignedPreKeyListener.schedule(context);

        return ServiceResponse.forResult(response, 200, null);
      } catch (IOException e) {
        return ServiceResponse.forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  @WorkerThread
  private void registerAccountInternal(@NonNull RegistrationData registrationData,
                                       @NonNull VerifyAccountResponse response,
                                       @Nullable String pin,
                                       @Nullable KbsPinData kbsData)
          throws IOException, InvalidInputException//AA+ Invalid...
  {
    ACI     aci    = ACI.parseOrThrow(response.getUuid());
    PNI     pni    = PNI.parseOrThrow(UUID.randomUUID().toString());//AA+ set random, as uf doesn't use PNI //response.getPnI()
    boolean hasPin = response.isStorageCapable();

    SignalStore.account().setAci(aci);
    SignalStore.account().setPni(pni);

    ApplicationDependencies.getProtocolStore().aci().sessions().archiveAllSessions();
    ApplicationDependencies.getProtocolStore().pni().sessions().archiveAllSessions();
    SenderKeyUtil.clearAllState();
    
    //AA+
    ProfileKey profileKey;
    try {
      profileKey = new ProfileKey(Base64.decode(response.getProfileKey()));
    } catch (InvalidInputException x) {
      Log.e(TAG, "ERROR registerAccountInternal: COULD NOT DECODE PROFILE KEY");
      throw (x);
    }
    TextSecurePreferences.setUfsrvCookie(context, response.getCookie());
    TextSecurePreferences.setUfsrvUsername(context, response.getUsername());
    TextSecurePreferences.setUfsrvUfAccountCreated(context, true);
    TextSecurePreferences.setUfsrvPendingCookie(context, null);
    if (response.getUid() > 0)  TextSecurePreferences.setUserId(context, response.getUid());
    if (!TextUtils.isEmpty(response.getUfsrvuid())) TextSecurePreferences.setUfsrvUserId(context, response.getUfsrvuid());
    if (!TextUtils.isEmpty(response.getAccessToken())) TextSecurePreferences.setAccessToken(context, response.getAccessToken());
    else TextSecurePreferences.setAccessToken(context, null);

    byte[] unidentifiedAccessKey =  UnidentifiedAccess.deriveAccessKeyFrom(profileKey);
    Log.d(TAG, String.format(Locale.getDefault(), ">>> registerAccountInternal (uid:'%d', ufsrvuid:'%s', uuid:'%s', profile_key:'%s', e164number:'%s', accessToken:'%s'): RECEIVED SIGNON COOKIE: '%s'", response.getUid(), response.getUfsrvuid(), response.getUuid().toString(), response.getProfileKey(), response.getE164Number(), response.getAccessToken(), response.getCookie()));
    //

    SignalServiceAccountManager accountManager = AccountManagerFactory.createAuthenticated(context, aci, pni, response.getUfsrvuid(), SignalServiceAddress.DEFAULT_DEVICE_ID, registrationData.getPassword());//AA+ getUfsrvuid() to allow for proper http Authorization for subsequent api calls
    SignalServiceAccountDataStoreImpl aciProtocolStore = ApplicationDependencies.getProtocolStore().aci();
    SignalServiceAccountDataStoreImpl pniProtocolStore = ApplicationDependencies.getProtocolStore().pni();

    generateAndRegisterPreKeys(ServiceIdType.ACI, accountManager, aciProtocolStore, SignalStore.account().aciPreKeys());
    generateAndRegisterPreKeys(ServiceIdType.PNI, accountManager, pniProtocolStore, SignalStore.account().pniPreKeys());

    if (registrationData.isFcm()) {
      accountManager.setGcmId(Optional.ofNullable(registrationData.getFcmToken()));
    }

    RecipientDatabase recipientDatabase = SignalDatabase.recipients();
    RecipientId       selfId            = Recipient.external(context, response.getUfsrvuid()).getId();//AA+ in place of externalPush. This will fail to fetch server stored details, because AccountManager cannot yet be successfully instantiated with username/password

    recipientDatabase.setProfileSharing(selfId, true);
    recipientDatabase.markRegisteredOrThrow(selfId, aci);
    recipientDatabase.setPni(selfId, pni);
    recipientDatabase.setProfileKey(selfId, profileKey);//AA+ used profileKey returned from backend
    ApplicationDependencies.getRecipientCache().clearSelf();

    SignalStore.account().setE164(response.getE164Number());//AA+ response
    SignalStore.account().setFcmToken(registrationData.getFcmToken());
    SignalStore.account().setFcmEnabled(registrationData.isFcm());

    long now = System.currentTimeMillis();
    saveOwnIdentityKey(selfId, aciProtocolStore, now);
//    saveOwnIdentityKey(selfId, pniProtocolStore, now); //AA- as it was overriding above... todo investigate if it's supposed to save pni as a key

    SignalStore.account().setUfsrvUid(response.getUfsrvuid());//AA+
    SignalStore.account().setUsername(response.getUsername());//AA+
    SignalStore.account().setServicePassword(registrationData.getPassword());
    SignalStore.account().setRegistered(true);
    TextSecurePreferences.setPromptedPushRegistration(context, true);
    TextSecurePreferences.setUnauthorizedReceived(context, false);

    Recipient.ufsrvUidResolve(context, selfId, response.getUfsrvuid());//AA+ This is necessary to  fetch server stored details, because AccountManager can now be successfully instantiated with username/password

    PinState.onRegistration(context, kbsData, pin, hasPin);
  }

  //AA+ public static
  public static void generateAndRegisterPreKeys(@NonNull ServiceIdType serviceIdType,
                                                @NonNull SignalServiceAccountManager accountManager,
                                                @NonNull SignalProtocolStore protocolStore,
                                                @NonNull PreKeyMetadataStore metadataStore)
          throws IOException
  {
    //AA+
    if (serviceIdType == ServiceIdType.PNI) {
      Log.w(TAG, "!! PNI PreKeys generation disabled");
      return;
    }

    SignedPreKeyRecord signedPreKey   = PreKeyUtil.generateAndStoreSignedPreKey(protocolStore, metadataStore, true);
    List<PreKeyRecord> oneTimePreKeys = PreKeyUtil.generateAndStoreOneTimePreKeys(protocolStore, metadataStore);

    if (serviceIdType == ServiceIdType.ACI) {
      Log.w(TAG, "!! Skipping PNI PreKeys backend persistence...");
      accountManager.setPreKeys(serviceIdType, protocolStore.getIdentityKeyPair().getPublicKey(), signedPreKey, oneTimePreKeys);
    }

    metadataStore.setSignedPreKeyRegistered(true);
  }

  private void saveOwnIdentityKey(@NonNull RecipientId selfId, @NonNull SignalServiceAccountDataStoreImpl protocolStore, long now) {
    protocolStore.identities().saveIdentityWithoutSideEffects(selfId,
                                                              protocolStore.getIdentityKeyPair().getPublicKey(),
                                                              IdentityDatabase.VerifiedStatus.VERIFIED,
                                                              true,
                                                              now,
                                                              true);
  }

  @WorkerThread
  private static @Nullable ProfileKey findExistingProfileKey(@NonNull String e164number) {
    RecipientDatabase     recipientDatabase = SignalDatabase.recipients();
    Optional<RecipientId> recipient         = recipientDatabase.getByE164(e164number);

    if (recipient.isPresent()) {
      return ProfileKeyUtil.profileKeyOrNull(Recipient.resolved(recipient.get()).getProfileKey());
    }

    return null;
  }
}