/**
 * Copyright (C) 2014 Open Whisper Systems
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
package org.whispersystems.signalservice.internal.push;

//import com.facebook.stetho.okhttp.StethoInterceptor;

import android.annotation.SuppressLint;
import android.text.TextUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.StandardIntegrityException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.data.json.JsonEntityB64EncodedEnvelope;
import com.unfacd.android.data.json.JsonEntityB64EncodedEnvelopeList;
import com.unfacd.android.data.json.JsonEntityNonceResponse;
import com.unfacd.android.data.json.JsonEntityPresenceInformation;
import com.unfacd.android.data.json.JsonEntityPresenceInformationList;
import com.unfacd.android.data.json.JsonEntityProfile;
import com.unfacd.android.data.json.JsonEntityRegistrationResponse;
import com.unfacd.android.data.json.JsonEntityUnverifiedAccount;
import com.unfacd.android.data.json.JsonEntityVerifiedAccount;
import com.unfacd.android.fence.FenceDescriptor;
import com.unfacd.android.integrity.IntegrityUtils;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;
import org.signal.libsignal.protocol.util.Pair;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredential;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequest;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequestContext;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialResponse;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyVersion;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialResponse;
import org.signal.storageservice.protos.groups.AvatarUploadAttributes;
import org.signal.storageservice.protos.groups.Group;
import org.signal.storageservice.protos.groups.GroupChange;
import org.signal.storageservice.protos.groups.GroupChanges;
import org.signal.storageservice.protos.groups.GroupExternalCredential;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialRequest;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Base64UrlSafe;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.KeyBackupService;
import org.whispersystems.signalservice.api.account.AccountAttributes;
import org.whispersystems.signalservice.api.account.ChangePhoneNumberRequest;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.groupsv2.CredentialResponse;
import org.whispersystems.signalservice.api.groupsv2.FenceInfo;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2AuthorizationString;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment.ProgressListener;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId;
import org.whispersystems.signalservice.api.messages.calls.CallingResponse;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceInfo;
import org.whispersystems.signalservice.api.messages.multidevice.VerifyDeviceResponse;
import org.whispersystems.signalservice.api.payments.CurrencyConversions;
import org.whispersystems.signalservice.api.profiles.ProfileAndCredential;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfileWrite;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.ServiceIdType;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.ConflictException;
import org.whispersystems.signalservice.api.push.exceptions.ContactManifestMismatchException;
import org.whispersystems.signalservice.api.push.exceptions.DeprecatedVersionException;
import org.whispersystems.signalservice.api.push.exceptions.ExpectationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.ImpossiblePhoneNumberException;
import org.whispersystems.signalservice.api.push.exceptions.MalformedResponseException;
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException;
import org.whispersystems.signalservice.api.push.exceptions.NoContentException;
import org.whispersystems.signalservice.api.push.exceptions.NonNormalizedPhoneNumberException;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResumableUploadResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.RangeException;
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException;
import org.whispersystems.signalservice.api.push.exceptions.RemoteAttestationResponseExpiredException;
import org.whispersystems.signalservice.api.push.exceptions.ResumeLocationInvalidException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.push.exceptions.UsernameMalformedException;
import org.whispersystems.signalservice.api.push.exceptions.UsernameTakenException;
import org.whispersystems.signalservice.api.storage.StorageAuthResponse;
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription;
import org.whispersystems.signalservice.api.subscriptions.SubscriptionClientSecret;
import org.whispersystems.signalservice.api.subscriptions.SubscriptionLevels;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl;
import org.whispersystems.signalservice.internal.configuration.SignalProxy;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalUrl;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryRequest;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryResponse;
import org.whispersystems.signalservice.internal.contacts.entities.KeyBackupRequest;
import org.whispersystems.signalservice.internal.contacts.entities.KeyBackupResponse;
import org.whispersystems.signalservice.internal.contacts.entities.TokenResponse;
import org.whispersystems.signalservice.internal.push.exceptions.ForbiddenException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupExistsException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupMismatchedDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupNotFoundException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupPatchNotAcceptedException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupStaleDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.InvalidUnidentifiedAccessHeaderException;
import org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException;
import org.whispersystems.signalservice.internal.push.exceptions.PaymentsRegionException;
import org.whispersystems.signalservice.internal.push.exceptions.StaleDevicesException;
import org.whispersystems.signalservice.internal.push.http.AcceptLanguagesUtil;
import org.whispersystems.signalservice.internal.push.http.CancelationSignal;
import org.whispersystems.signalservice.internal.push.http.DigestingRequestBody;
import org.whispersystems.signalservice.internal.push.http.NoCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.push.http.OutputStreamFactory;
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec;
import org.whispersystems.signalservice.internal.storage.protos.ReadOperation;
import org.whispersystems.signalservice.internal.storage.protos.StorageItems;
import org.whispersystems.signalservice.internal.storage.protos.StorageManifest;
import org.whispersystems.signalservice.internal.storage.protos.WriteOperation;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Hex;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.util.concurrent.FutureTransformers;
import org.whispersystems.signalservice.internal.util.concurrent.ListenableFuture;
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Single;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

@SuppressLint("NewApi")

/**
 * @author Moxie Marlinspike
 */
public class PushServiceSocket {

  private static final String TAG = Log.tag(PushServiceSocket.class);

  private static final String CREATE_ACCOUNT_SMS_PATH    = "/v1/accounts/sms/code/%s?client=%s";
  private static final String CREATE_ACCOUNT_VOICE_PATH  = "/v1/accounts/voice/code/%s";
  private static final String VERIFY_ACCOUNT_CODE_PATH   = "/v1/accounts/code/%s";
  private static final String REGISTER_GCM_PATH          = "/v1/accounts/gcm/";
  private static final String TURN_SERVER_INFO           = "/v1/accounts/turn";
  private static final String SET_ACCOUNT_ATTRIBUTES     = "/v1/accounts/attributes/";
  private static final String PIN_PATH                   = "/v1/accounts/pin/";
  private static final String REGISTRATION_LOCK_PATH     = "/V1/Account/RegistrationLock";//AA+ "/v1/accounts/registration_lock";
  private static final String REGISTRATION_LOCK_VERIFY   = "/V1/Account/RegistrationLock/Verify";//AA+
  private static final String KBS_FOR_UFSRV_PATH         = "/V1/Account/KBS";//AA+
  private static final String REQUEST_PUSH_CHALLENGE     = "/V1/Account/GCM_PREAUTH/%s/%s";//AA+ ""/v1/accounts/fcm/preauth/%s/%s";
  private static final String WHO_AM_I                   = "/v1/accounts/whoami";
  private static final String SET_USERNAME_PATH          = "/v1/accounts/username/%s";
  private static final String DELETE_USERNAME_PATH       = "/v1/accounts/username";
  private static final String DELETE_ACCOUNT_PATH        = "/v1/accounts/me";
  private static final String CHANGE_NUMBER_PATH         = "/v1/accounts/number";
  private static final String IDENTIFIER_REGISTERED_PATH = "/v1/accounts/account/%s";
  private static final String PROFILE_PATH              = "/v1/profile/%s";
  private static final String PROFILE_USERNAME_PATH     = "/v1/profile/username/%s";

  public static final String UFSRVCMD_LOCATION          = "/V1/Location";

  private static final String PROVISIONING_CODE_PATH    = "/v1/devices/provisioning/code";
  private static final String PROVISIONING_MESSAGE_PATH = "/v1/provisioning/%s";
  private static final String DEVICE_PATH               = "/v1/devices/%s";

  private static final String DIRECTORY_AUTH_PATH       = "/v1/directory/auth";
  private static final String GROUP_MESSAGE_PATH        = "/v1/messages/multi_recipient?ts=%s&online=%s";
  private static final String SENDER_ACK_MESSAGE_PATH   = "/v1/messages/%s/%d";
  private static final String UUID_ACK_MESSAGE_PATH     = "/v1/messages/uuid/%s";
  private static final String ATTACHMENT_V2_PATH        = "/v2/attachments/form/upload";
  private static final String ATTACHMENT_V3_PATH        = "/v3/attachments/form/upload";

  private static final String PAYMENTS_AUTH_PATH        = "/v1/payments/auth";

  private static final String STICKER_MANIFEST_PATH     = "stickers/%s/manifest.proto";
  private static final String STICKER_PATH              = "stickers/%s/full/%d";

  private static final String GROUPSV2_CREDENTIAL       = "/V1/Fence/Certificate/%d/%d";//AA+  /v1/certificate/group/%d/%d";
  private static final String GROUPSV2_GROUP            = "/V1/Fence/ZKGroup";///v1/groups/";
  private static final String GROUPSV2_GROUP_PASSWORD   = "/v1/groups/?inviteLinkPassword=%s";
  private static final String GROUPSV2_GROUP_CHANGES = "/v1/groups/logs/%s?maxSupportedChangeEpoch=%d&includeFirstState=%s&includeLastState=false";
  private static final String GROUPSV2_AVATAR_REQUEST   = "/v1/groups/avatar/form";
  private static final String GROUPSV2_GROUP_JOIN       = "/V1/Fence/Info/%s";//"/v1/groups/join/%s";
  private static final String GROUPSV2_TOKEN            = "/v1/groups/token";

  private static final String PAYMENTS_CONVERSIONS      = "/v1/payments/conversions";
  private static final String SUBMIT_RATE_LIMIT_CHALLENGE       = "/v1/challenge";
  private static final String REQUEST_RATE_LIMIT_PUSH_CHALLENGE = "/v1/challenge/push";

  private static final String DONATION_REDEEM_RECEIPT = "/v1/donation/redeem-receipt";

  private static final String SUBSCRIPTION_LEVELS                 = "/V1/Donation/Subscription/Levels";//"/v1/subscription/levels";
  private static final String UPDATE_SUBSCRIPTION_LEVEL           = "/v1/subscription/%s/level/%s/%s/%s";
  private static final String SUBSCRIPTION                        = "/V1/Donation/Subscription/%s";//"/v1/subscription/%s";
  private static final String CREATE_SUBSCRIPTION_PAYMENT_METHOD  = "/v1/subscription/%s/create_payment_method";
  private static final String DEFAULT_SUBSCRIPTION_PAYMENT_METHOD = "/v1/subscription/%s/default_payment_method/%s";
  private static final String SUBSCRIPTION_RECEIPT_CREDENTIALS    = "/v1/subscription/%s/receipt_credentials";
  private static final String BOOST_AMOUNTS                       = "/V1/Donation/Subscription/Boost/Amounts";//""/v1/subscription/boost/amounts";
  private static final String CREATE_BOOST_PAYMENT_INTENT         = "/v1/subscription/boost/create";
  private static final String BOOST_RECEIPT_CREDENTIALS           = "/v1/subscription/boost/receipt_credentials";
  private static final String BOOST_BADGES                        = "/V1/Donation/Subscription/Boost/Badges";//"/v1/subscription/boost/badges";

  private static final String CDSH_AUTH                           = "/v2/directory/auth";

  private static final String SENDER_CERTIFICATE_PATH         = "/v1/certificate/delivery";
  private static final String SENDER_CERTIFICATE_NO_E164_PATH = "/v1/certificate/delivery?includeE164=false";

  private static final String KBS_AUTH_PATH                  = "/v1/backup/auth";

  private static final String ATTACHMENT_KEY_DOWNLOAD_PATH   = "attachments/%s";
  private static final String ATTACHMENT_ID_DOWNLOAD_PATH    = "attachments/%d";
  private static final String ATTACHMENT_UPLOAD_PATH         = "attachments/";
  private static final String AVATAR_UPLOAD_PATH             = "";

  private static final String ATTACHMENT_DOWNLOAD_PATH  = "attachments/%d";

  //AA+
  private static final String UF_REQUEST_REGISTER_NONCE   = "/V1/Nonce";
  private static final String UF_REGISTER                 = "/V1/Account/New";
  private static final String UF_VERIFY_ACCOUNT           = "/V1/Account/VerifyNew";
  private static final String UF_VERIFY_ACCOUNTSTATUS     = "/V1/Account/VerifyStatus/%s";
  private static final String UF_VOICE_VERIFY_ACCOUNT     = "/V1/Account/VerifyNew/Voice/%s";
  private static final String UF_ACCOUNT_KEYS             = "/V1/Account/Keys";//keys bundle
  private static final String UF_ACCOUNT_KEYS_STATUS      = "/V1/Account/Keys/Status";//count of keys
  private static final String UF_ACCOUNT_SIGNED_PREKEY    = "/V1/Account/Keys/Signed";//sgned pre key
  private static final String UF_ACCOUNT_KEYS_PREKEYS_DEV = "/V1/Account/Keys/PreKeys/%s/%s";//prekey for a device
  private static final String UF_ACCOUNT_GCM              = "/V1/Account/GCM";
  private static final String UF_ACCOUNT_ATTACHEMENTS     = "/V1/Account/Attachment/%s";
  private static final String UF_ACCOUNT_DEVICES          = "/V1/Account/Devices/%s";
  private static final String UF_ACCOUNT_SHARED_CONTACTS  = "/V1/Account/SharedContacts";
  private static final String UF_ACCOUNT_PROFILE          = "/V1/Account/Profile/%s";
  private static final String UF_REGISTRY_USER            = "/V1/Registry/UserToken/%s";
  private static final String UF_REGISTRY_USERID          = "/V1/Registry/UserId/%s";
  private static final String UF_USER_MESSAGE             = "/V1/Message";
  private static final String UF_MESSAGE_GID              = "/V1/Message/Gid/%d/%d";
  private static final String UF_USER_MESSAGE_NONE        = "/V1/MessageNonce";
  private static final String UF_FENCE                    = "/V1/Fence";
  private static final String UF_FENCE_DESCRIPTOR         = "/V1/Fence/%d";
  private static final String UF_FENCE_NEARBY             = "/V1/Fence/NearBy";
  private static final String UF_FENCE_NAMESEARCH         = "/V1/Fence/Search/%s";
  private static final String UF_USER                     = "/V1/User";
  private static final String UF_USER_PRESENCE            = "/V1/User/Presence";
  private static final String UF_CALL                     = "/V1/Call";
  private static final String UF_RECEIPT                  = "/V1/Receipt";
  private static final String UF_STATE                    = "/V1/ActivityState";
  private static final String UF_SYNC                     = "/V1/Sync";
  private static final String UF_CALL_TURN                = "/V1/Call/Turn";
  private static final String UF_NICKNAME                 = "/V1/Nickname/%s";
  private static final String UF_BOOLPREFS                = "/V1/Account/Prefs";
  private static final String UF_BOOLPREFS_STICKYGEOGROUP = "/V1/Account/Prefs/StickyGeogroup/%s/%d";
  private static final String UF_BOOLPREFS_USER           = "/V1/Account/Prefs/%s/%d";
  private static final String UF_SENDER_CERTIFICATE_PATH  = "/V1/Account/Certificate/Delivery";
  private static final String UF_PROFILE_PATH             = "/V1/Account/Profile";
  private static final String UF_PROFILE_WITH_CREDS_PATH  = "/V1/Account/Profile/%s";


  private static final String UFSRV_ATTACHMENT_NONCE_HEADER="X-UFSRV-ATTACHMENT-NONCE";
  //

  private static final String REPORT_SPAM = "/V1/Messages/Report/%s/%s";

  private static final String SERVER_DELIVERED_TIMESTAMP_HEADER = "X-Unfacd-Timestamp";//AA+

  private static final Map<String, String> NO_HEADERS                  = Collections.emptyMap();
  private static final ResponseCodeHandler NO_HANDLER                  = new EmptyResponseCodeHandler();
  private static final List<String>        REQUIRED_REGISTRATION_PATHS = Arrays.asList(
                                                                                       REQUEST_PUSH_CHALLENGE,
                                                                                       UF_REQUEST_REGISTER_NONCE,
                                                                                       UF_REGISTER,
                                                                                       UF_VERIFY_ACCOUNT,
                                                                                       UF_VERIFY_ACCOUNTSTATUS
                                                                                       );

  private static final long CDN2_RESUMABLE_LINK_LIFETIME_MILLIS = TimeUnit.DAYS.toMillis(7);

  private static final int MAX_FOLLOW_UPS = 20;

  private       long      soTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
  private final Set<Call> connections     = new HashSet<>();

  private final ServiceConnectionHolder[]        serviceClients;
  private final Map<Integer, ConnectionHolder[]> cdnClientsMap;
  private final ConnectionHolder[]               contactDiscoveryClients;
  private final ConnectionHolder[]               keyBackupServiceClients;
  private final ConnectionHolder[]               storageClients;
  private final OkHttpClient                     attachmentClient;//AA+

  private final CredentialsProvider              credentialsProvider;
  private final String                           userAgent;
  private final SecureRandom                     random;
  private final ClientZkProfileOperations        clientZkProfileOperations;
  private final boolean                          automaticNetworkRetry;

  public PushServiceSocket(SignalServiceConfiguration configuration,
                           CredentialsProvider credentialsProvider,
                           String signalAgent,
                           ClientZkProfileOperations clientZkProfileOperations,
                           boolean automaticNetworkRetry)
  {
    this.credentialsProvider       = credentialsProvider;
    this.userAgent                 = signalAgent;
    this.automaticNetworkRetry     = automaticNetworkRetry;
    this.serviceClients            = createServiceConnectionHolders(configuration.getSignalServiceUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
    this.cdnClientsMap             = createCdnClientsMap(configuration.getSignalCdnUrlMap(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
    this.contactDiscoveryClients   = createConnectionHolders(configuration.getSignalContactDiscoveryUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
    this.keyBackupServiceClients   = createConnectionHolders(configuration.getSignalKeyBackupServiceUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
    this.storageClients            = createConnectionHolders(configuration.getSignalStorageUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
    this.random                    = new SecureRandom();
    this.clientZkProfileOperations = clientZkProfileOperations;
    this.attachmentClient          = createAttachmentClient();//AA+
  }

  //AA+
  public String getCookie () {
    return this.credentialsProvider.getCookie();
  }

  //AA+ voice verification for ufsrv requires presence of signon cookie
  public void voiceVerifyAccountUfsrv(String regoPendingCookie, Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    Map<String, String> headers = locale != null ? Collections.singletonMap("Accept-Language", locale.getLanguage() + "-" + locale.getCountry()) : NO_HEADERS;
    String path = String.format(UF_VOICE_VERIFY_ACCOUNT, regoPendingCookie);

    if (captchaToken.isPresent()) {
      path += "?captcha=" + captchaToken.get();
    } else if (challenge.isPresent()) {
      path += "?challenge=" + challenge.get();
    }

    ResponseBody responseBody = makeServiceBodyRequest(path, "GET", null, headers, new ResponseCodeHandler() {
                                            @Override
                                            public void handle(int responseCode, ResponseBody body) throws NonSuccessfulResponseCodeException {
                                              if (responseCode == 402) {
                                                throw new CaptchaRequiredException();
                                              }
                                            }
                                          }, Optional.empty(), serviceClients); //AA+ serviceClients -> defaults to service requests

    //doesnt return this type of response
    //JsonEntityNonceResponse uf=JsonUtil.fromJson(readBodyString(responseBody), JsonEntityNonceResponse.class);

    //Log.d(TAG, "voiceVerifyAccountUfsrv: RESPONSE="+uf.getNonce());
  }

  //AA+
  public String getRegistrationNonce() throws IOException {
    String ufResponse = makeServiceRequest(String.format(Locale.US, UF_REQUEST_REGISTER_NONCE), "GET", null, NO_HEADERS);
    JsonEntityNonceResponse uf = JsonUtil.fromJson(ufResponse, JsonEntityNonceResponse.class);

    Log.d(TAG, ">> REGO NONCE="+uf.getNonce());

    return uf.getNonce();

  }

  public String createAccountUfsrv(@NonNull String nonce,  boolean voice, boolean androidSmsRetriever, Locale locale, Optional<String> captchaToken, Optional<String> challenge, Optional<String> fcmToken,  String e164Number, String regoEmail) throws IOException {
    Map<String, String> headers = voice && locale != null ? Collections.singletonMap("Accept-Language", locale.getLanguage() + "-" + locale.getCountry()) : NO_HEADERS;

    //either a challenge or a captcha
    String jsonString = JsonUtil.toJson(new JsonEntityUnverifiedAccount(regoEmail, e164Number, credentialsProvider.getPassword(), nonce, androidSmsRetriever ? "android-ng" : "android", captchaToken.orElse(null), challenge.orElse(null)));

    Log.d(TAG, String.format(">>> createAccountUfsrv: sending json: '%s'",  jsonString));
    String ufResponse = makeServiceRequest(UF_REGISTER, "POST", jsonString, NO_HEADERS, new ResponseCodeHandler() {
                                  @Override
                                  public void handle(int responseCode, ResponseBody responseBody) throws NonSuccessfulResponseCodeException {
                                    if (responseCode == 402) {
                                      throw new CaptchaRequiredException();
                                    }
                                  }
                                });

    JsonEntityRegistrationResponse uf2 = JsonUtil.fromJson(ufResponse, JsonEntityRegistrationResponse.class);
    Log.d(TAG, ">> REGO PENDING COOKIE=" + uf2.getCookie());
    return uf2.getCookie();

  }

  public String fetchGuardianRequestNonce() throws IOException
  {
    Map<String, String> headers = NO_HEADERS;

    String ufResponse = makeServiceRequest(String.format(Locale.US, UF_USER_MESSAGE_NONE), "GET", null, headers);
    JsonEntityNonceResponse uf = JsonUtil.fromJson(ufResponse, JsonEntityNonceResponse.class);

    Log.d(TAG, ">> Guardian Request Nonce: "+uf.getNonce());

    return uf.getNonce();
  }

/*
{"cookie":"14128...","registrationId":4128,"signalingKey":"/H44..==","verificationCode":"637323","voice":true}
 */
  //AA+
  public VerifyAccountResponse verifyAccountCodeUfsrv(String verificationCode,
                                                      String signalingKey,
                                                      int registrationId,
                                                      boolean fetchesMessages,
                                                      String pin,
                                                      String registrationLock,
                                                      byte[] unidentifiedAccessKey,
                                                      boolean unrestrictedUnidentifiedAccess,
                                                      String pendingCookie,
                                                      String username,
                                                      String e164number,
                                                      byte[] profileKey)
          throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey,
                                                                 registrationId,
                                                                 fetchesMessages,
                                                                 verificationCode,
                                                                 pin,
                                                                 registrationLock,
                                                                 unidentifiedAccessKey,
                                                                 unrestrictedUnidentifiedAccess,
                                                                 null,
                                                                 false,
                                                                 "",
                                                                 pendingCookie,
                                                                 username,
                                                                 e164number,
                                                                 profileKey);
    String responseText = makeServiceRequest(String.format(Locale.US, UF_VERIFY_ACCOUNT), "POST", JsonUtil.toJson(signalingKeyEntity));//AA- "PUT"

    return JsonUtil.fromJson(responseText, VerifyAccountResponse.class);
  }

  //when verified, verification code is returned
  public Optional<String> isRegistrationStatusVerified(String registrationCookie)
  {
    try {
      String responseText = makeServiceRequest(String.format(Locale.US, UF_VERIFY_ACCOUNTSTATUS, registrationCookie), "GET", null);
      JsonEntityVerifiedAccount verifiedAccount = JsonUtil.fromJson(responseText, JsonEntityVerifiedAccount.class);
      if (!TextUtils.isEmpty(verifiedAccount.getVerificationCode())) return Optional.of(verifiedAccount.getVerificationCode());
      else return Optional.empty();
    }
    catch (Exception  e) {
      return Optional.empty();
    }
  }

 /* public void verifyAccountToken(String verificationToken, String signalingKey, int registrationId, boolean voice, boolean video, boolean fetchesMessages, String pin, SignalServiceProfile.Capabilities capabilities)
      throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, "", "", null, false, capabilities, "", "", null, null);//AA+ last 4 argumets
    makeServiceRequest(String.format(Locale.US, VERIFY_ACCOUNT_TOKEN_PATH, verificationToken),
                "PUT", JsonUtil.toJson(signalingKeyEntity));
  }*/

  /*public VerifyAccountResponse verifyAccountCode(String verificationCode, String signalingKey, int registrationId, boolean fetchesMessages,
                                                 String pin, String registrationLock,
                                                 byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                                 SignalServiceProfile.Capabilities capabilities,
                                                 boolean discoverableByPhoneNumber)
          throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, registrationLock, unidentifiedAccessKey, unrestrictedUnidentifiedAccess, capabilities, discoverableByPhoneNumber);
    String            requestBody        = JsonUtil.toJson(signalingKeyEntity);
    String            responseBody       = makeServiceRequest(String.format(VERIFY_ACCOUNT_CODE_PATH, verificationCode), "PUT", requestBody);

    return JsonUtil.fromJson(responseBody, VerifyAccountResponse.class);
  }*/

  public VerifyAccountResponse changeNumber(String code, String e164NewNumber, String registrationLock)
          throws IOException
  {
    ChangePhoneNumberRequest changePhoneNumberRequest = new ChangePhoneNumberRequest(e164NewNumber, code, registrationLock);
    String                   requestBody              = JsonUtil.toJson(changePhoneNumberRequest);
    String                   responseBody             = makeServiceRequest(CHANGE_NUMBER_PATH, "PUT", requestBody);

    return JsonUtil.fromJson(responseBody, VerifyAccountResponse.class);
  }

  public void setAccountAttributes(String signalingKey,
                                   int registrationId,
                                   boolean fetchesMessages,
                                   String pin,
                                   String registrationLock,
                                   byte[] unidentifiedAccessKey,
                                   boolean unrestrictedUnidentifiedAccess,
                                   AccountAttributes.Capabilities capabilities,
                                   boolean discoverableByPhoneNumber,
                                   byte[] encryptedDeviceName)
          throws IOException
  {
    if (registrationLock != null && pin != null) {
      throw new AssertionError("Pin should be null if registrationLock is set.");
    }

    String name = (encryptedDeviceName == null) ? null : Base64.encodeBytes(encryptedDeviceName);

    AccountAttributes accountAttributes = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, registrationLock, "", unidentifiedAccessKey, unrestrictedUnidentifiedAccess, capabilities, discoverableByPhoneNumber, name, "", "", null, null);//AA+ last 4 arguments
//    makeServiceRequest(SET_ACCOUNT_ATTRIBUTES, "PUT", JsonUtil.toJson(accountAttributes));
    Log.e(TAG, "setAccountAttributes: NOT IMPLEMENETD ");
  }

  public String getNewDeviceVerificationCode() throws IOException {
    String responseText = makeServiceRequest(PROVISIONING_CODE_PATH, "GET", null);
    return JsonUtil.fromJson(responseText, DeviceCode.class).getVerificationCode();
  }

  public VerifyDeviceResponse verifySecondaryDevice(String verificationCode, AccountAttributes accountAttributes) throws IOException {
    String responseText = makeServiceRequest(String.format(DEVICE_PATH, verificationCode), "PUT", JsonUtil.toJson(accountAttributes));
    return JsonUtil.fromJson(responseText, VerifyDeviceResponse.class);
  }

  public List<DeviceInfo> getDevices() throws IOException {
    String responseText = makeServiceRequest(String.format(UF_ACCOUNT_DEVICES, ""), "GET", null);
    return JsonUtil.fromJson(responseText, DeviceInfoList.class).getDevices();
  }

  public void removeDevice(long deviceId) throws IOException {
    makeServiceRequest(String.format(UF_ACCOUNT_DEVICES, String.valueOf(deviceId)), "DELETE", null);
  }

  public void sendProvisioningMessage(String destination, byte[] body) throws IOException {
    makeServiceRequest(String.format(PROVISIONING_MESSAGE_PATH, destination), "PUT",
                JsonUtil.toJson(new ProvisioningMessage(Base64.encodeBytes(body))));
  }

  public void registerGcmId(String gcmRegistrationId) throws IOException {
    GcmRegistrationId registration = new GcmRegistrationId(gcmRegistrationId, true);
    //AA+
    makeServiceRequest(UF_ACCOUNT_GCM, "POST", JsonUtil.toJson(registration));
  }

  public void unregisterGcmId() throws IOException {
    //AA+
    makeServiceRequest(UF_ACCOUNT_GCM, "DELETE", null);
  }

  public void requestPushChallenge(String gcmRegistrationId, String e164number) throws IOException {
    makeServiceRequest(String.format(Locale.US, REQUEST_PUSH_CHALLENGE, gcmRegistrationId, e164number), "GET", null);
  }

  /** Note: Setting a KBS Pin will clear this */
  public void removeRegistrationLockV1() throws IOException {
    makeServiceRequest(PIN_PATH, "DELETE", null);
  }

  public void setRegistrationLockV2(String registrationLock) throws IOException {
    RegistrationLockV2 accountLock = new RegistrationLockV2(registrationLock);
    makeServiceRequest(REGISTRATION_LOCK_PATH, "POST", JsonUtil.toJson(accountLock));//AA+ POST
  }

  //AA+
  /**
   *
   * @param hashedPinJson serialisased from KbsForUfsrv
   * @return
   * @throws IOException
   */
  public KeyBackupService.KbsForUfsrv verifyRegistrationLockV2(String hashedPinJson, Map<String, String> authorizationHeader)
          throws IOException
  {
    String body = makeServiceRequest(REGISTRATION_LOCK_VERIFY, "POST", hashedPinJson, authorizationHeader);

    if (body != null) {
      return JsonUtil.fromJson(body, KeyBackupService.KbsForUfsrv.class);
    } else {
      throw new MalformedResponseException("Empty response!");
    }
  }

  public void setKbsForUfsr(KeyBackupService.KbsForUfsrv kbsForUfsrv) throws IOException {
    makeServiceRequest(KBS_FOR_UFSRV_PATH, "POST", JsonUtil.toJson(kbsForUfsrv));
  }

  public void disableRegistrationLockV2() throws IOException {
    makeServiceRequest(REGISTRATION_LOCK_PATH, "DELETE", null);
  }

  //AA+
  //curl -Ss -X POST -u a:a https://api.unfacd.io/V1/Account/Prefs/pref_roaming_mode/1
  public String storeGroupPreferences(String jsonPayload) throws IOException {
    String jsonResponse = makeServiceRequest(UF_BOOLPREFS, "POST", jsonPayload);
    return jsonResponse;
  }
//

  //AA+
  public String storeStickyGeogroupPreference(long fid, int on_off) throws IOException {
    String jsonResponse = makeServiceRequest(String.format(UF_BOOLPREFS_STICKYGEOGROUP, fid, on_off), "POST", "");
    return jsonResponse;
  }
  //

  //AA+
  public String storeTogglableUserPreference(String prefName, int on_off) throws IOException {
    String jsonResponse = makeServiceRequest(String.format(UF_BOOLPREFS_USER,  prefName, on_off), "POST", "");
    return jsonResponse;
  }
  //

  //AA+
  public String fencesNearBy(String fenceLocation)  {
    try
    {
      String jsonResponse = makeServiceRequest(String.format(UF_FENCE_NEARBY, fenceLocation), "POST", fenceLocation);
      return jsonResponse;
    }
    catch (IOException e) {
      return null;
    }
  }

  public String SearchfencesByName(String searchText)  {
    try
    {
      String jsonResponse = makeServiceRequest(String.format(UF_FENCE_NAMESEARCH, searchText), "GET", null);
      return jsonResponse;
    }
    catch (IOException e) {
      return null;
    }
  }

  //AA+
  public boolean isNicknamekAvailable(String nickname)  {
    try
    {
      makeServiceRequest(String.format(UF_NICKNAME, nickname), "GET", null);
      return true;
    }
    catch (IOException e) {
      return false;
    }
  }

  public byte[] getSenderCertificate() throws IOException {
    String responseText = makeServiceRequest(UF_SENDER_CERTIFICATE_PATH, "GET", null);
    return JsonUtil.fromJson(responseText, SenderCertificate.class).getCertificate();
  }

  public byte[] getUuidOnlySenderCertificate() throws IOException {
    String responseText = makeServiceRequest(SENDER_CERTIFICATE_NO_E164_PATH, "GET", null);
    return JsonUtil.fromJson(responseText, SenderCertificate.class).getCertificate();
  }

  public SendGroupMessageResponse sendGroupMessage(byte[] body, byte[] joinedUnidentifiedAccess, long timestamp, boolean online)
          throws IOException
  {
    ServiceConnectionHolder connectionHolder = (ServiceConnectionHolder) getRandom(serviceClients, random);

    String path = String.format(Locale.US, GROUP_MESSAGE_PATH, timestamp, online);

    Request.Builder requestBuilder = new Request.Builder();
    requestBuilder.url(String.format("%s%s", connectionHolder.getUrl(), path));
    requestBuilder.put(RequestBody.create(MediaType.get("application/vnd.signal-messenger.mrm"), body));
    requestBuilder.addHeader("Unidentified-Access-Key", Base64.encodeBytes(joinedUnidentifiedAccess));

    if (userAgent != null) {
      requestBuilder.addHeader("X-Unfacd-Agent", userAgent);//AA+ X-unfacd
    }

    if (connectionHolder.getHostHeader().isPresent()) {
      requestBuilder.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    Call call = connectionHolder.getUnidentifiedClient().newCall(requestBuilder.build());

    synchronized (connections) {
      connections.add(call);
    }

    Response response;

    try {
      response = call.execute();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    switch (response.code()) {
      case 200:
        return readBodyJson(response.body(), SendGroupMessageResponse.class);
      case 401:
        throw new InvalidUnidentifiedAccessHeaderException();
      case 404:
        throw new NotFoundException("At least one unregistered user in message send.");
      case 409:
        GroupMismatchedDevices[] mismatchedDevices = readBodyJson(response.body(), GroupMismatchedDevices[].class);
        throw new GroupMismatchedDevicesException(mismatchedDevices);
      case 410:
        GroupStaleDevices[] staleDevices = readBodyJson(response.body(), GroupStaleDevices[].class);
        throw new GroupStaleDevicesException(staleDevices);
      case 508:
        throw new ServerRejectedException();
      default:
        throw new NonSuccessfulResponseCodeException(response.code());
    }
  }

  //AA orig TBD
 /* public SendMessageResponse sendMessage(OutgoingPushMessageList bundle, Optional<UnidentifiedAccess> unidentifiedAccess)
      throws IOException
  {
    try {
//      String jsonPayload = JsonUtil.toJson(bundle);
      //Log.d(TAG, String.format(">>sendMessage: SENDING JSON PYLOAD: '%s'", jsonPayload));
      String responseText = makeServiceRequest(String.format(UF_USER_MESSAGE, bundle.getDestination()), "POST", JsonUtil.toJson(bundle), NO_HEADERS, NO_HANDLER, unidentifiedAccess, serviceClients);
      SendMessageResponse response     = JsonUtil.fromJson(responseText, SendMessageResponse.class);

      response.setSentUnidentfied(unidentifiedAccess.isPresent());

      return response;
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(bundle.getDestination(), nfe);
    }
  }*/

  public abstract class Retry {

    public static final int MAX_RETRIES = 2;

    private int tries = 0;

    abstract public Optional<String> execute() throws Exception;

    public Optional<String> run() throws StandardIntegrityException, RetriesExhaustedException, Exception {
      try {
        return execute();
      } catch(StandardIntegrityException ex) {
        tries++;
        if( MAX_RETRIES == tries) {
          throw new RetriesExhaustedException();
        } else {
          Tasks.await(IntegrityUtils.initialiseIntegrityApi());//refresh the token on the back of INTEGRITY_TOKEN_PROVIDER_INVALID error
          return run();
        }
      } catch(Exception ex) {
        throw ex;
      }
    }

    public class RetriesExhaustedException extends Exception {//just type semantics
      //only thrown if StandardIntegrityException occured up to predefined retry limit
    }
  }

  Optional<String> getTokenForHeader(String hashedToken)
  {
    Retry retry = new Retry() {
      StringBuilder token = new StringBuilder();
      @Override
      public Optional<String> execute() throws StandardIntegrityException
      {
        try {
          Single.just(hashedToken)
                .subscribe(encodedRequestHash -> {
                  IntegrityUtils.requestIntegrityToken(encodedRequestHash, (tokenResponse) -> {//throws StandardIntegrityException if token is stale
                    if (tokenResponse.isPresent()) token.append(tokenResponse.get());
                  });
                })
                .dispose();
        }
        catch (Exception ex) {
          throw ex; // optional exception block
        }

        if (token.length() > 0) {
          return Optional.of(token.toString());
        }
        else {
          return Optional.empty();
        }

      }
    };

    try {
      return retry.run();
    } catch (Exception x) {
      Log.e(TAG, x);
   }

    return Optional.empty();
  }

  //AA+ ufComand semantics
  public SendMessageResponse sendMessage(OutgoingPushMessageList bundle, Optional<UnidentifiedAccess> unidentifiedAccess, UfsrvCommand ufCommand)
          throws IOException
  {
    try {
      final Map<String, String> headers = new HashMap<>();//NO_HEADERS;
      if (ufCommand.isIntegritySensitive()) {
        Single.just(ufCommand.getRequestHashForIntegrityToken())
              .map(Optional::get)
              .subscribe(encodedRequestHash -> {
                Optional<String> tokenResponse = getTokenForHeader(encodedRequestHash);
                if (tokenResponse.isPresent()) {
                  headers.put("X-Integrity-Token", tokenResponse.get());
                }
               /* try {
                  IntegrityUtils.requestIntegrityToken(encodedRequestHash, (tokenResponse) -> {
                    if (tokenResponse.isPresent())
                      headers.put("X-Integrity-Token", tokenResponse.get());
                  });
                }
                catch (StandardIntegrityException x) {
                  Tasks.await(IntegrityUtils.initialiseIntegrityApi());//refresh the token on the back of INTEGRITY_TOKEN_PROVIDER_INVALID error
                  Log.e(TAG, x.getMessage());
                }*/
              })
              .dispose();
      }

     /* if (ufCommand.isIntegritySensitive()) {
        Single.just(ufCommand.getRequestHashForIntegrityToken())
              .map(Optional::get)
              .subscribe(encodedRequestHash -> {
                try {
                  StandardIntegrityManager.StandardIntegrityToken tokenResponse = Tasks.await(IntegrityUtils.provideIntegrityTokenRequestTask(encodedRequestHash));
                  headers.put("X-Integrity-Token", tokenResponse.token());
                } catch (ExecutionException | InterruptedException x) {
                  Log.e(TAG, x.getMessage());
                }
              })
              .dispose();
      }*/

      String responseText = makeServiceRequest(ufCommand.getServerCommandPathArgs(), "POST", JsonUtil.toJson(bundle), headers, NO_HANDLER, unidentifiedAccess, serviceClients);

      SendMessageResponse response = JsonUtil.fromJson(responseText, SendMessageResponse.class);

      response.setSentUnidentfied(unidentifiedAccess.isPresent());

      return response;
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(bundle.getDestination(), nfe);
    }
  }

  public SignalServiceMessagesResult getMessages() throws IOException {
    try (Response response = makeServiceRequest(String.format(UF_USER_MESSAGE, ""), "GET", (RequestBody) null, NO_HEADERS, NO_HANDLER, Optional.empty(), serviceClients)) {
      validateServiceResponse(response);

      long serverDeliveredTimestamp = 0;
      try {
        String stringValue = response.header(SERVER_DELIVERED_TIMESTAMP_HEADER);
        stringValue = stringValue != null ? stringValue : "0";

        serverDeliveredTimestamp = Long.parseLong(stringValue);
      }
      catch (NumberFormatException e) {
        Log.w(TAG, e);
      }

      List<SignalServiceProtos.Envelope> envelopesRaw = reconstructUfsrvEnvelopes(JsonUtil.fromJson(readBodyString(response.body()), JsonEntityB64EncodedEnvelopeList.class).getMessages());
      return new SignalServiceMessagesResult(Optional.of(envelopesRaw), serverDeliveredTimestamp);
    }
  }

  public SignalServiceMessagesResult deleteQueuedMessages() throws IOException {
    try (Response response = makeServiceRequest(String.format(UF_USER_MESSAGE, ""), "DELETE", (RequestBody) null, NO_HEADERS, NO_HANDLER, Optional.empty(), serviceClients)) {
      validateServiceResponse(response);

      long serverDeliveredTimestamp = 0;
      try {
        String stringValue = response.header(SERVER_DELIVERED_TIMESTAMP_HEADER);
        stringValue = stringValue != null ? stringValue : "0";

        serverDeliveredTimestamp = Long.parseLong(stringValue);
      }
      catch (NumberFormatException e) {
        Log.w(TAG, e);
      }

      List<SignalServiceProtos.Envelope> envelopesRaw = reconstructUfsrvEnvelopes(JsonUtil.fromJson(readBodyString(response.body()), JsonEntityB64EncodedEnvelopeList.class).getMessages());
      return new SignalServiceMessagesResult(Optional.empty(), serverDeliveredTimestamp);
    }
  }

  /*public List<SignalServiceProtos.Envelope> getMessages() throws IOException {
    String responseText = makeServiceRequest(String.format(UF_USER_MESSAGE, TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance())), "GET", null);//AA+ ufsrv
    return reconstructUfsrvEnvelopes(JsonUtil.fromJson(responseText, JsonEntityB64EncodedEnvelopeList.class).getMessages());
  }*/

  //AA+
  private List<SignalServiceProtos.Envelope> reconstructUfsrvEnvelopes(List<JsonEntityB64EncodedEnvelope> entities)
  {
    List<SignalServiceProtos.Envelope> envelopes = new LinkedList();

    for (JsonEntityB64EncodedEnvelope entity : entities) {
      try {
        SignalServiceProtos.Envelope envelope = SignalServiceProtos.Envelope.parseFrom(Base64.decode(entity.getMessage()));
        envelopes.add(envelope);
      } catch (IOException ex) {
        Log.d(TAG, ex.getMessage());
      }
    }

    return envelopes;
  }

  public void acknowledgeMessage(String sender, long timestamp) throws IOException {
    makeServiceRequest(String.format(SENDER_ACK_MESSAGE_PATH, sender, timestamp), "DELETE", null);
  }

  public void acknowledgeMessage(String uuid) throws IOException {
    makeServiceRequest(String.format(UUID_ACK_MESSAGE_PATH, uuid), "DELETE", null);
  }

  public void acknowledgeMessage(long gid, long fid) throws IOException {
    makeServiceRequest(String.format(UF_MESSAGE_GID, gid, fid), "DELETE", null);//AA+
  }

  //old prkeys
  public void registerPreKeys(String jsonString)
          throws IOException
  {
    String response = makeServiceRequest(String.format(UF_ACCOUNT_KEYS, ""), "POST",
            jsonString);
  }

  public void registerPreKeys(ServiceIdType serviceIdType, IdentityKey identityKey, SignedPreKeyRecord signedPreKey, List<PreKeyRecord> records) throws IOException
  {
    List<PreKeyEntity> entities = new LinkedList<>();

    try {
      for (PreKeyRecord record : records) {
        PreKeyEntity entity = new PreKeyEntity(record.getId(),
                                               record.getKeyPair().getPublicKey());

        entities.add(entity);
      }
    } catch (InvalidKeyException e) {
      throw new AssertionError("unexpected invalid key", e);
    }

    SignedPreKeyEntity signedPreKeyEntity = new SignedPreKeyEntity(signedPreKey.getId(),
                                                                   signedPreKey.getKeyPair().getPublicKey(),
                                                                   signedPreKey.getSignature());

    //AA-
   /* makeServiceRequest(String.format(Locale.US, PREKEY_PATH, "", serviceIdType.queryParam()),
                       "PUT",
                       JsonUtil.toJson(new PreKeyState(entities, signedPreKeyEntity, identityKey)));*/

    Log.d(TAG, String.format("> registerPreKeys: '%s'", JsonUtil.toJson(signedPreKeyEntity)));
    String response = makeServiceRequest(String.format(UF_ACCOUNT_KEYS, ""),
                                         "POST",
                                         JsonUtil.toJson(new PreKeyState(entities, signedPreKeyEntity, identityKey)));
  }

  public int getAvailablePreKeys(ServiceIdType serviceIdType) throws IOException {
//    String path = String.format(PREKEY_METADATA_PATH, serviceIdType.queryParam());
    String       responseText = makeServiceRequest(UF_ACCOUNT_KEYS_STATUS, "GET", null);

    PreKeyStatus preKeyStatus = JsonUtil.fromJson(responseText, PreKeyStatus.class);

    return preKeyStatus.getCount();
  }

  public List<PreKeyBundle> getPreKeys(SignalServiceAddress destination,
                                       Optional<UnidentifiedAccess> unidentifiedAccess,
                                       int deviceIdInteger)
          throws IOException {
    try {
      String deviceId = String.valueOf(deviceIdInteger);

      if (deviceId.equals("1"))
        deviceId = "*";

      String path = String.format(UF_ACCOUNT_KEYS_PREKEYS_DEV, destination.getNumber().get(), deviceId);

      Log.d(TAG, "Fetching prekeys for " + destination.getIdentifier() + "." + deviceId + ", i.e. GET " + path);

      ResponseBody       responseBody = makeServiceBodyRequest(path, "GET", null, NO_HEADERS, NO_HANDLER, unidentifiedAccess, serviceClients);
      PreKeyResponse     response     = JsonUtil.fromJson(readBodyString(responseBody), PreKeyResponse.class);
      List<PreKeyBundle> bundles      = new LinkedList<>();

      for (PreKeyResponseItem device : response.getDevices()) {
        ECPublicKey preKey                = null;
        ECPublicKey signedPreKey          = null;
        byte[]      signedPreKeySignature = null;
        int         preKeyId              = -1;
        int         signedPreKeyId        = -1;

        if (device.getSignedPreKey() != null) {
          signedPreKey          = device.getSignedPreKey().getPublicKey();
          signedPreKeyId        = device.getSignedPreKey().getKeyId();
          signedPreKeySignature = device.getSignedPreKey().getSignature();
          Log.d(TAG, String.format("getPreKeys: signedPreKeyId: '%d SignedPrekey:'%s'", signedPreKeyId, signedPreKey.toString()));
        }

        if (device.getPreKey() != null) {
          preKeyId = device.getPreKey().getKeyId();
          preKey   = device.getPreKey().getPublicKey();
          Log.d(TAG, String.format("getPreKeys: PreKeyId: '%d SignedPrekey:'%s'", preKeyId, preKey.toString()));
        }

        bundles.add(new PreKeyBundle(device.getRegistrationId(), device.getDeviceId(), preKeyId,
                                     preKey, signedPreKeyId, signedPreKey, signedPreKeySignature,
                                     response.getIdentityKey()));
      }

      return bundles;
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(destination.getIdentifier(), nfe);
    }
  }

  public PreKeyBundle getPreKey(SignalServiceAddress destination, int deviceId) throws IOException {
    try {
      String path = String.format(UF_ACCOUNT_KEYS_PREKEYS_DEV, destination.getNumber().get(), String.valueOf(deviceId));
      String         responseText = makeServiceRequest(path, "GET", null);
      PreKeyResponse response     = JsonUtil.fromJson(responseText, PreKeyResponse.class);

      if (response.getDevices() == null || response.getDevices().size() < 1)
        throw new IOException("Empty prekey list");

      PreKeyResponseItem device                = response.getDevices().get(0);
      ECPublicKey        preKey                = null;
      ECPublicKey        signedPreKey          = null;
      byte[]             signedPreKeySignature = null;
      int                preKeyId              = -1;
      int                signedPreKeyId        = -1;

      if (device.getPreKey() != null) {
        preKeyId = device.getPreKey().getKeyId();
        preKey   = device.getPreKey().getPublicKey();
      }

      if (device.getSignedPreKey() != null) {
        signedPreKeyId        = device.getSignedPreKey().getKeyId();
        signedPreKey          = device.getSignedPreKey().getPublicKey();
        signedPreKeySignature = device.getSignedPreKey().getSignature();
      }

      return new PreKeyBundle(device.getRegistrationId(), device.getDeviceId(), preKeyId, preKey,
                              signedPreKeyId, signedPreKey, signedPreKeySignature, response.getIdentityKey());
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(destination.getIdentifier(), nfe);
    }
  }

  public SignedPreKeyEntity getCurrentSignedPreKey(ServiceIdType serviceIdType) throws IOException {
    try {
//      String path = String.format(SIGNED_PREKEY_PATH, serviceIdType.queryParam());
      String responseText = makeServiceRequest(UF_ACCOUNT_SIGNED_PREKEY, "GET", null);
      return JsonUtil.fromJson(responseText, SignedPreKeyEntity.class);
    } catch (NotFoundException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  public void setCurrentSignedPreKey(ServiceIdType serviceIdType, SignedPreKeyRecord signedPreKey) throws IOException {
//    String path = String.format(SIGNED_PREKEY_PATH, serviceType.queryParam());
    SignedPreKeyEntity signedPreKeyEntity = new SignedPreKeyEntity(signedPreKey.getId(),
                                                                   signedPreKey.getKeyPair().getPublicKey(),
                                                                   signedPreKey.getSignature());
    //makeServiceRequest(SIGNED_PREKEY_PATH, "PUT", JsonUtil.toJson(signedPreKeyEntity));
    Log.d(TAG, String.format("> setCurrentSignedPreKey: sending pre key '%s'", JsonUtil.toJson(signedPreKeyEntity)));
    makeServiceRequest(UF_ACCOUNT_SIGNED_PREKEY, "POST", JsonUtil.toJson(signedPreKeyEntity));

  }

  public void retrieveAttachment(int cdnNumber, SignalServiceAttachmentRemoteId cdnPath, File destination, long maxSizeBytes, ProgressListener listener)
          throws IOException, MissingConfigurationException {
    final String path;
    if (cdnPath.getV2().isPresent()) {
      path = String.format(Locale.US, ATTACHMENT_ID_DOWNLOAD_PATH, cdnPath.getV2().get());
    } else {
      path = String.format(Locale.US, ATTACHMENT_KEY_DOWNLOAD_PATH, cdnPath.getV3().get());
    }
    downloadFromCdn(destination, cdnNumber, path, maxSizeBytes, listener);
  }

  //AA+ return type: returns the nonce as indentifier instead of long id
  public Pair<String, byte[]> sendAttachment(PushAttachmentData attachment) throws IOException {
    ResponseBody responseBody  = makeServiceBodyRequest(String.format(UF_ACCOUNT_ATTACHEMENTS, ""), "GET", null, NO_HEADERS, NO_HANDLER, Optional.empty(), cdnClientsMap.get(0)); //AA+ cdnClients

    AttachmentDescriptor attachmentKey = JsonUtil.fromJson(readBodyString(responseBody), AttachmentDescriptor.class);

    if (attachmentKey == null || attachmentKey.getLocation() == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    Log.w(TAG, "Got attachment content location: '" + attachmentKey.getLocation() + "' Nonce:'" + attachmentKey.getNonce() + "'");

    byte[] digest = uploadAttachment("PUT", attachmentKey.getLocation(), attachment.getData(),
            attachment.getDataSize(), attachment.getOutputStreamFactory(), attachment.getListener(), attachment.getCancelationSignal(), attachmentKey.getNonce());

    return new Pair<>(attachmentKey.getNonce(), digest);//AA+
  }

  //AA this returns the nonce as indentifier instead of long id
  public Pair<String, byte[]> sendProfileAvatarUfsrv(ProfileAvatarData attachment) throws IOException {
    ResponseBody  responseBody      = makeServiceBodyRequest(String.format(UF_ACCOUNT_ATTACHEMENTS, ""), "GET", null, NO_HEADERS, NO_HANDLER, Optional.empty(), cdnClientsMap.get(0)); //AA+ cdnClients

    AttachmentDescriptor attachmentKey = JsonUtil.fromJson(readBodyString(responseBody), AttachmentDescriptor.class);

    if (attachmentKey == null || attachmentKey.getLocation() == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    Log.w(TAG, "sendProfileAvatarUfsrv: Got attachment content location: '" + attachmentKey.getLocation() + "' Nonce:'" + attachmentKey.getNonce() + "'");

    byte[] digest = uploadAttachment("PUT", attachmentKey.getLocation(), attachment.getData(),
                                   attachment.getDataLength(), attachment.getOutputStreamFactory(), null, null, attachmentKey.getNonce());

    return new Pair<>(attachmentKey.getNonce(), digest);//AA+
  }

  public void retrieveProfileAvatarUfsrv(String ufId, File destination, int maxSizeBytes) throws IOException
  {
    String path = String.format(BuildConfig.UFSRVMEDIA_URL + UF_ACCOUNT_ATTACHEMENTS, ufId);
    Log.w(TAG, String.format("retrieveProfileAvatarUfsrv: Downloading from:'%s'", path));
    downloadAttachment(path, destination, maxSizeBytes, null);
  }
  //

  public void retrieveAttachmentUfsrv(String ufId, File destination, long maxSizeBytes, ProgressListener listener) throws IOException {

    String path = String.format(BuildConfig.UFSRVMEDIA_URL + UF_ACCOUNT_ATTACHEMENTS, ufId);
    Log.w(TAG, String.format("retrieveAttachmentUfsrv: Downloading from:'%s'", path));

    downloadAttachment(path, destination, maxSizeBytes, listener);
  }

  public byte[] retrieveAttachmentBytes(String ufId, long maxSizeBytes, ProgressListener listener) throws IOException {
    String path = String.format(BuildConfig.UFSRVMEDIA_URL + UF_ACCOUNT_ATTACHEMENTS, ufId);
    Log.w(TAG, String.format("retrieveAttachmentBytes: Downloading from:'%s'", path));

    byte[] attachmentBytes = downloadAttachmentBytes(path, maxSizeBytes, listener);
    return attachmentBytes;
  }

  public ListenableFuture<SignalServiceProfile> retrieveProfile(SignalServiceAddress target, Optional<UnidentifiedAccess> unidentifiedAccess, Locale locale) {
    ListenableFuture<String> response = submitServiceRequest(String.format(PROFILE_PATH, target.getIdentifier()), "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale), unidentifiedAccess);

    return FutureTransformers.map(response, body -> {
      try {
        return JsonUtil.fromJson(body, SignalServiceProfile.class);
      } catch (IOException e) {
        Log.w(TAG, e);
        throw new MalformedResponseException("Unable to parse entity", e);
      }
    });
  }

  public SignalServiceProfile retrieveProfileByUsername(String username, Optional<UnidentifiedAccess> unidentifiedAccess, Locale locale)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    String response = makeServiceRequest(String.format(PROFILE_USERNAME_PATH, username), "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale), unidentifiedAccess);

    try {
      return JsonUtil.fromJson(response, SignalServiceProfile.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public ListenableFuture<ProfileAndCredential> retrieveVersionedProfileAndCredential(UUID target, ProfileKey profileKey, Optional<UnidentifiedAccess> unidentifiedAccess, Locale locale) {
    ProfileKeyVersion                  profileKeyIdentifier = profileKey.getProfileKeyVersion(target);
    ProfileKeyCredentialRequestContext requestContext       = clientZkProfileOperations.createProfileKeyCredentialRequestContext(random, target, profileKey);
    ProfileKeyCredentialRequest        request              = requestContext.getRequest();

    //AA+
    Optional<RecipientId> uuidUser =SignalDatabase.recipients().getByServiceId(ACI.from(target));
    Recipient                          recipientTarget      = Recipient.resolved(uuidUser.get());
    //

    String version           = profileKeyIdentifier.serialize();
    String credentialRequest = Hex.toStringCondensed(request.serialize());
    String subPath           = String.format("%s/%s/%s", recipientTarget.requireUfsrvUid(), version, credentialRequest);//AA+ recipientTarget

    ListenableFuture<String> response = submitServiceRequest(String.format(UF_PROFILE_WITH_CREDS_PATH, subPath), "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale), unidentifiedAccess);//AA+ UF_PROFILE

    return FutureTransformers.map(response, body -> formatProfileAndCredentialBody(requestContext, body));
  }

  private ProfileAndCredential formatProfileAndCredentialBody(ProfileKeyCredentialRequestContext requestContext, String body)
          throws MalformedResponseException
  {
    try {
      SignalServiceProfile signalServiceProfile = JsonUtil.fromJson(body, SignalServiceProfile.class);

      try {
        ProfileKeyCredential profileKeyCredential = signalServiceProfile.getProfileKeyCredentialResponse() != null
                                                    ? clientZkProfileOperations.receiveProfileKeyCredential(requestContext, signalServiceProfile.getProfileKeyCredentialResponse())
                                                    : null;
        return new ProfileAndCredential(signalServiceProfile, SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL, Optional.ofNullable(profileKeyCredential));
      } catch (VerificationFailedException e) {
        Log.w(TAG, "Failed to verify credential.", e);
        return new ProfileAndCredential(signalServiceProfile, SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL, Optional.empty());
      }
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public ListenableFuture<SignalServiceProfile> retrieveVersionedProfile(UUID target, ProfileKey profileKey, Optional<UnidentifiedAccess> unidentifiedAccess, Locale locale) {
    ProfileKeyVersion profileKeyIdentifier = profileKey.getProfileKeyVersion(target);

    String                   version  = profileKeyIdentifier.serialize();
    String                   subPath  = String.format("%s/%s", target, version);
    ListenableFuture<String> response = submitServiceRequest(String.format(PROFILE_PATH, subPath), "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale), unidentifiedAccess);

    return FutureTransformers.map(response, body -> {
      try {
        return JsonUtil.fromJson(body, SignalServiceProfile.class);
      } catch (IOException e) {
        Log.w(TAG, e);
        throw new MalformedResponseException("Unable to parse entity", e);
      }
    });
  }

  public void retrieveProfileAvatar(String path, File destination, long maxSizeBytes)
          throws IOException {
    try {
      downloadFromCdn(destination, 0, path, maxSizeBytes, null);
    } catch (MissingConfigurationException e) {
      throw new AssertionError(e);
    }
  }

  public byte[] retrieveSticker(byte[] packId, int stickerId)
          throws NonSuccessfulResponseCodeException, PushNetworkException {
    String                hexPackId = Hex.toStringCondensed(packId);
    ByteArrayOutputStream output    = new ByteArrayOutputStream();

    try {
      downloadFromCdn(output, 0, 0, String.format(Locale.US, STICKER_PATH, hexPackId, stickerId), 1024 * 1024, null);
    } catch (MissingConfigurationException e) {
      throw new AssertionError(e);
    }

    return output.toByteArray();
  }

  public byte[] retrieveStickerManifest(byte[] packId)
          throws NonSuccessfulResponseCodeException, PushNetworkException {
    String                hexPackId = Hex.toStringCondensed(packId);
    ByteArrayOutputStream output    = new ByteArrayOutputStream();

    try {
      downloadFromCdn(output, 0, 0, String.format(STICKER_MANIFEST_PATH, hexPackId), 1024 * 1024, null);
    } catch (MissingConfigurationException e) {
      throw new AssertionError(e);
    }

    return output.toByteArray();
  }

  /**
   * @return The avatar URL path, if one was written.
   */
  public Optional<String> writeProfile(SignalServiceProfileWrite signalServiceProfileWrite, ProfileAvatarData profileAvatar)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    String                        requestBody    = JsonUtil.toJson(signalServiceProfileWrite);
    ProfileAvatarUploadAttributes formAttributes;

    String response = makeServiceRequest(String.format(PROFILE_PATH, ""),
                                         "PUT",
                                         requestBody,
                                         NO_HEADERS,
                                         PaymentsRegionException::responseCodeHandler,
                                         Optional.empty());

    if (signalServiceProfileWrite.hasAvatar() && profileAvatar != null) {
      try {
        formAttributes = JsonUtil.fromJson(response, ProfileAvatarUploadAttributes.class);
      } catch (IOException e) {
        Log.w(TAG, e);
        throw new MalformedResponseException("Unable to parse entity", e);
      }
      uploadToCdn0(AVATAR_UPLOAD_PATH, formAttributes.getAcl(), formAttributes.getKey(),
                   formAttributes.getPolicy(), formAttributes.getAlgorithm(),
                   formAttributes.getCredential(), formAttributes.getDate(),
                   formAttributes.getSignature(), profileAvatar.getData(),
                   profileAvatar.getContentType(), profileAvatar.getDataLength(),
                   profileAvatar.getOutputStreamFactory(), null, null);
      return Optional.of(formAttributes.getKey());
    }
    return Optional.empty();
  }

  //AA+
  public Optional<String> writeProfileVersion(@NonNull ProfileKey profileKey)
  {
    JsonEntityProfile profile = new JsonEntityProfile();
    profile.setVersion(profileKey.getProfileKeyVersion(Recipient.self().requireServiceId().uuid()).serialize());
    profile.setCommitment(profileKey.getCommitment(Recipient.self().requireServiceId().uuid()).serialize());
    String jsonString = JsonUtil.toJson(profile);

    String endpoint = String.format(UF_ACCOUNT_PROFILE, Recipient.self().requireUfsrvUid());
    Log.d(TAG, String.format(Locale.getDefault(), ">>> writeProfileVersion (endpoint: '%s'): sending json: '%s'",  endpoint, jsonString));
    try {
      return Optional.of(makeServiceRequest(endpoint, "POST", jsonString, NO_HEADERS, (responseCode, body) -> {
        if (responseCode == 409) {
          throw new CaptchaRequiredException();
        }
      }));
    } catch (Exception x) {
      Log.e(TAG, x.getMessage());
    }

    return Optional.empty();
  }

  public WhoAmIResponse getWhoAmI() throws IOException {
    return JsonUtil.fromJson(makeServiceRequest(WHO_AM_I, "GET", null), WhoAmIResponse.class);
  }

  public boolean isIdentifierRegistered(ServiceId identifier) throws IOException {
    try {
      makeServiceRequestWithoutAuthentication(String.format(IDENTIFIER_REGISTERED_PATH, identifier.toString()), "HEAD", null);
      return true;
    } catch (NotFoundException e) {
      return false;
    }
  }

  public CdshAuthResponse getCdshAuth() throws IOException {
    String body = makeServiceRequest(CDSH_AUTH, "GET", null);
    return JsonUtil.fromJsonResponse(body, CdshAuthResponse.class);
  }

  /*public void requestSmsVerificationCode(boolean androidSmsRetriever, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    String path = String.format(CREATE_ACCOUNT_SMS_PATH, credentialsProvider.getE164(), androidSmsRetriever ? "android-2021-03" : "android");
    if (captchaToken.isPresent()) {
      path += "&captcha=" + captchaToken.get();
    } else if (challenge.isPresent()) {
      path += "&challenge=" + challenge.get();
    }

    makeServiceRequest(path, "GET", null, NO_HEADERS, new VerificationCodeResponseHandler());
  }

  public void requestVoiceVerificationCode(Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    Map<String, String> headers = locale != null ? Collections.singletonMap("Accept-Language", locale.getLanguage() + "-" + locale.getCountry()) : NO_HEADERS;
    String              path    = String.format(CREATE_ACCOUNT_VOICE_PATH, credentialsProvider.getE164());
    if (captchaToken.isPresent()) {
      path += "?captcha=" + captchaToken.get();
    } else if (challenge.isPresent()) {
      path += "?challenge=" + challenge.get();
    }

    makeServiceRequest(path, "GET", null, headers, new VerificationCodeResponseHandler());
  }*/

  public void setUsername(String username) throws IOException {
    makeServiceRequest(String.format(SET_USERNAME_PATH, username), "PUT", "", NO_HEADERS, (responseCode, body) -> {
      switch (responseCode) {
        case 400: throw new UsernameMalformedException();
        case 409: throw new UsernameTakenException();
      }
    }, Optional.<UnidentifiedAccess>empty());
  }

  public void deleteUsername() throws IOException {
    makeServiceRequest(DELETE_USERNAME_PATH, "DELETE", null);
  }

  public void deleteAccount() throws IOException {
    makeServiceRequest(DELETE_ACCOUNT_PATH, "DELETE", null);
  }

  public void requestRateLimitPushChallenge() throws IOException {
    makeServiceRequest(REQUEST_RATE_LIMIT_PUSH_CHALLENGE, "POST", "");
  }

  public void submitRateLimitPushChallenge(String challenge) throws IOException {
    String payload = JsonUtil.toJson(new SubmitPushChallengePayload(challenge));
    makeServiceRequest(SUBMIT_RATE_LIMIT_CHALLENGE, "PUT", payload);
  }

  public void redeemDonationReceipt(ReceiptCredentialPresentation receiptCredentialPresentation, boolean visible, boolean primary) throws IOException {
    String payload = JsonUtil.toJson(new RedeemReceiptRequest(Base64.encodeBytes(receiptCredentialPresentation.serialize()), visible, primary));
    makeServiceRequest(DONATION_REDEEM_RECEIPT, "POST", payload);
  }

  public void submitRateLimitRecaptchaChallenge(String challenge, String recaptchaToken) throws IOException {
    String payload = JsonUtil.toJson(new SubmitRecaptchaChallengePayload(challenge, recaptchaToken));
    makeServiceRequest(SUBMIT_RATE_LIMIT_CHALLENGE, "PUT", payload);
  }

  public SubscriptionClientSecret createBoostPaymentMethod(String currencyCode, long amount, String description) throws IOException {
    String payload = JsonUtil.toJson(new DonationIntentPayload(amount, currencyCode, description));
    String result  = makeServiceRequestWithoutAuthentication(CREATE_BOOST_PAYMENT_INTENT, "POST", payload);
    return JsonUtil.fromJsonResponse(result, SubscriptionClientSecret.class);
  }

  public Map<String, List<BigDecimal>> getBoostAmounts() throws IOException {
    String result = makeServiceRequestWithoutAuthentication(BOOST_AMOUNTS, "GET", null);
    TypeReference<HashMap<String, List<BigDecimal>>> typeRef = new TypeReference<HashMap<String, List<BigDecimal>>>() {};
    return JsonUtil.fromJsonResponse(result, typeRef);
  }

  public SubscriptionLevels getBoostLevels(Locale locale) throws IOException {
    String result = makeServiceRequestWithoutAuthentication(BOOST_BADGES, "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale));
    return JsonUtil.fromJsonResponse(result, SubscriptionLevels.class);
  }

  public ReceiptCredentialResponse submitBoostReceiptCredentials(String paymentIntentId, ReceiptCredentialRequest receiptCredentialRequest) throws IOException {
    String payload  = JsonUtil.toJson(new BoostReceiptCredentialRequestJson(paymentIntentId, receiptCredentialRequest));
    String response = makeServiceRequestWithoutAuthentication(
            BOOST_RECEIPT_CREDENTIALS,
            "POST",
            payload,
            (code, body) -> {
              if (code == 204) throw new NonSuccessfulResponseCodeException(204);
            });

    ReceiptCredentialResponseJson responseJson = JsonUtil.fromJson(response, ReceiptCredentialResponseJson.class);
    if (responseJson.getReceiptCredentialResponse() != null) {
      return responseJson.getReceiptCredentialResponse();
    } else {
      throw new MalformedResponseException("Unable to parse response");
    }
  }

  public SubscriptionLevels getSubscriptionLevels(Locale locale) throws IOException {
    String result = makeServiceRequestWithoutAuthentication(SUBSCRIPTION_LEVELS, "GET", null, AcceptLanguagesUtil.getHeadersWithAcceptLanguage(locale));
    return JsonUtil.fromJsonResponse(result, SubscriptionLevels.class);
  }

  public void updateSubscriptionLevel(String subscriberId, String level, String currencyCode, String idempotencyKey) throws IOException {
    makeServiceRequestWithoutAuthentication(String.format(UPDATE_SUBSCRIPTION_LEVEL, subscriberId, level, currencyCode, idempotencyKey), "PUT", "");
  }

  public ActiveSubscription getSubscription(String subscriberId) throws IOException {
    String response = makeServiceRequestWithoutAuthentication(String.format(SUBSCRIPTION, subscriberId), "GET", null);
    return JsonUtil.fromJson(response, ActiveSubscription.class);
  }

  public void putSubscription(String subscriberId) throws IOException {
    makeServiceRequestWithoutAuthentication(String.format(SUBSCRIPTION, subscriberId), "PUT", "");
  }

  public void deleteSubscription(String subscriberId) throws IOException {
    makeServiceRequestWithoutAuthentication(String.format(SUBSCRIPTION, subscriberId), "DELETE", null);
  }

  public SubscriptionClientSecret createSubscriptionPaymentMethod(String subscriberId) throws IOException {
    String response = makeServiceRequestWithoutAuthentication(String.format(CREATE_SUBSCRIPTION_PAYMENT_METHOD, subscriberId), "POST", "");
    return JsonUtil.fromJson(response, SubscriptionClientSecret.class);
  }

  public void setDefaultSubscriptionPaymentMethod(String subscriberId, String paymentMethodId) throws IOException {
    makeServiceRequestWithoutAuthentication(String.format(DEFAULT_SUBSCRIPTION_PAYMENT_METHOD, subscriberId, paymentMethodId), "POST", "");
  }

  public ReceiptCredentialResponse submitReceiptCredentials(String subscriptionId, ReceiptCredentialRequest receiptCredentialRequest) throws IOException {
    String payload  = JsonUtil.toJson(new ReceiptCredentialRequestJson(receiptCredentialRequest));
    String response = makeServiceRequestWithoutAuthentication(
            String.format(SUBSCRIPTION_RECEIPT_CREDENTIALS, subscriptionId),
            "POST",
            payload,
            (code, body) -> {
              if (code == 204) throw new NonSuccessfulResponseCodeException(204);
            });

    ReceiptCredentialResponseJson responseJson = JsonUtil.fromJson(response, ReceiptCredentialResponseJson.class);
    if (responseJson.getReceiptCredentialResponse() != null) {
      return responseJson.getReceiptCredentialResponse();
    } else {
      throw new MalformedResponseException("Unable to parse response");
    }
  }

  public List<ContactTokenDetails> retrieveDirectory(Set<String> contactTokens)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    try {
      ContactTokenList        contactTokenList = new ContactTokenList(new LinkedList<>(contactTokens));
      String                  response         = makeServiceRequest(UF_ACCOUNT_SHARED_CONTACTS, "POST", JsonUtil.toJson(contactTokenList));//AA+

      ContactTokenDetailsList activeTokens     = JsonUtil.fromJson(response, ContactTokenDetailsList.class);

      return activeTokens.getContacts();
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public List<JsonEntityPresenceInformation> retrievePresenceInformation(String requestBody)
          throws IOException, PushNetworkException
  {
    try {
      String                  response         = makeServiceRequest(UF_USER_PRESENCE, "POST", requestBody);

      JsonEntityPresenceInformationList activeTokens     = JsonUtil.fromJson(response, JsonEntityPresenceInformationList.class);

      return activeTokens.getSharingList();
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity");
    }
  }

  public FenceDescriptor getFenceDescriptor (long fid)
  {
    try {
      String response = makeServiceRequest(String.format(UF_FENCE_DESCRIPTOR, fid), "GET", null);

      return JsonUtil.fromJson(response, FenceDescriptor.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  public ContactTokenDetails getContactTokenDetails(String contactToken) throws IOException {
    try {
      String response = makeServiceRequest(String.format(UF_REGISTRY_USER, contactToken), "GET", null);//AA+
      return JsonUtil.fromJson(response, ContactTokenDetails.class);
    } catch (NotFoundException nfe) {
      return null;
    }
  }

  public ContactTokenDetails getContactTokenDetails(UfsrvUid ufsrvUid) throws IOException {
    try {
      String response = makeServiceRequest(String.format(UF_REGISTRY_USERID, ufsrvUid.getUfsrvUidEncoded()), "GET", null);
      return JsonUtil.fromJson(response, ContactTokenDetails.class);
    } catch (NotFoundException nfe) {
      return null;
    }
  }

  public JsonEntityProfile getUfsrvProfile(UfsrvUid ufsrvUid) throws MalformedResponseException {
    try {
      String response = makeServiceRequest(String.format(UF_ACCOUNT_PROFILE, ufsrvUid.getUfsrvUidEncoded()), "GET", null);
      return JsonUtil.fromJson(response, JsonEntityProfile.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity");
    }
  }

  private AuthCredentials getAuthCredentials(String authPath) throws IOException {
    //AA-
//    String              response = makeServiceRequest(authPath, "GET", null, NO_HEADERS);
//    AuthCredentials     token    = JsonUtil.fromJson(response, AuthCredentials.class);
    //AA+ temp mock implementation
    AuthCredentials     token = JsonUtils.fromJson(String.format("{\"username\":\"%s\", \"password\":\"%s\"}", SignalStore.account().getUfsrvuid(), SignalStore.account().getServicePassword()), AuthCredentials.class);
    return token;
  }

  private String getCredentials(String authPath) throws IOException {
    return getAuthCredentials(authPath).asBasic();
  }

  public String getContactDiscoveryAuthorization() throws IOException {
    return getCredentials(DIRECTORY_AUTH_PATH);
  }

  public String getKeyBackupServiceAuthorization() throws IOException {
    return getCredentials(KBS_AUTH_PATH);
  }

  public AuthCredentials getPaymentsAuthorization() throws IOException {
    return getAuthCredentials(PAYMENTS_AUTH_PATH);
  }

  public TokenResponse getKeyBackupServiceToken(String authorizationToken, String enclaveName)
          throws IOException
  {
    Log.w(TAG, String.format("getKeyBackupServiceToken: NOT ISSUING /v1/token/%s --> returning mock TokenResponse instead...", enclaveName));
    /*ResponseBody body = makeRequest(ClientSet.KeyBackup, authorizationToken, null, "/v1/token/" + enclaveName, "GET", null).body();
11
    if (body != null) {
      return JsonUtil.fromJson(body.string(), TokenResponse.class);
    } else {
      throw new MalformedResponseException("Empty response!");
    }*/
    return new TokenResponse(new byte[32], new byte[16], 7);//AA+
  }

  public DiscoveryResponse getContactDiscoveryRegisteredUsers(String authorizationToken, DiscoveryRequest request, List<String> cookies, String mrenclave)
          throws IOException
  {
    ResponseBody body = makeRequest(ClientSet.ContactDiscovery, authorizationToken, cookies, "/v1/discovery/" + mrenclave, "PUT", JsonUtil.toJson(request)).body();

    if (body != null) {
      return JsonUtil.fromJson(body.string(), DiscoveryResponse.class);
    } else {
      throw new MalformedResponseException("Empty response!");
    }
  }

  public KeyBackupResponse putKbsData(String authorizationToken, KeyBackupRequest request, List<String> cookies, String mrenclave)
          throws IOException
  {
    ResponseBody body = makeRequest(ClientSet.KeyBackup, authorizationToken, cookies, "/v1/backup/" + mrenclave, "PUT", JsonUtil.toJson(request)).body();

    if (body != null) {
      return JsonUtil.fromJson(body.string(), KeyBackupResponse.class);
    } else {
      throw new MalformedResponseException("Empty response!");
    }
  }

  public TurnServerInfo getTurnServerInfo() throws IOException {
    String response = makeServiceRequest(UF_CALL_TURN/*TURN_SERVER_INFO*/, "GET", null);
    return JsonUtil.fromJson(response, TurnServerInfo.class);
  }

  public String getStorageAuth() throws IOException {
    String              response     = makeServiceRequest("/v1/storage/auth", "GET", null);
    StorageAuthResponse authResponse = JsonUtil.fromJson(response, StorageAuthResponse.class);

    return Credentials.basic(authResponse.getUsername(), authResponse.getPassword());
  }

  public StorageManifest getStorageManifest(String authToken) throws IOException {
    ResponseBody response = makeStorageRequest(authToken, "/v1/storage/manifest", "GET", null);

    if (response == null) {
      throw new IOException("Missing body!");
    }

    return StorageManifest.parseFrom(readBodyBytes(response));
  }

  public StorageManifest getStorageManifestIfDifferentVersion(String authToken, long version) throws IOException {
    ResponseBody response = makeStorageRequest(authToken, "/v1/storage/manifest/version/" + version, "GET", null);

    if (response == null) {
      throw new IOException("Missing body!");
    }

    return StorageManifest.parseFrom(readBodyBytes(response));
  }

  public StorageItems readStorageItems(String authToken, ReadOperation operation) throws IOException {
    ResponseBody response = makeStorageRequest(authToken, "/v1/storage/read", "PUT", protobufRequestBody(operation));

    if (response == null) {
      throw new IOException("Missing body!");
    }

    return StorageItems.parseFrom(readBodyBytes(response));
  }

  public Optional<StorageManifest> writeStorageContacts(String authToken, WriteOperation writeOperation) throws IOException {
    try {
      makeAndCloseStorageRequest(authToken, "/v1/storage", "PUT", protobufRequestBody(writeOperation));
      return Optional.empty();
    } catch (ContactManifestMismatchException e) {
      return Optional.of(StorageManifest.parseFrom(e.getResponseBody()));
    }
  }

  public void pingStorageService() throws IOException {
//    makeStorageRequest(null, "/ping", "GET", null);//AA-

    Map<String, String> headers =  NO_HEADERS;

    String ufResponse = makeServiceRequest(String.format(Locale.US, UF_REQUEST_REGISTER_NONCE), "GET", null, headers);
    JsonEntityNonceResponse uf = JsonUtil.fromJson(ufResponse, JsonEntityNonceResponse.class);
  }

  public RemoteConfigResponse getRemoteConfig() throws IOException {
    String response = makeServiceRequest("/v1/config", "GET", null);
    return JsonUtil.fromJson(response, RemoteConfigResponse.class);
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    this.soTimeoutMillis = soTimeoutMillis;
  }

  public void cancelInFlightRequests() {
    synchronized (connections) {
      Log.w(TAG, "Canceling: " + connections.size());
      for (Call connection : connections) {
        Log.w(TAG, "Canceling: " + connection);
        connection.cancel();
      }
    }
  }

  public AttachmentV2UploadAttributes getAttachmentV2UploadAttributes()
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    String response = makeServiceRequest(ATTACHMENT_V2_PATH, "GET", null);
    try {
      return JsonUtil.fromJson(response, AttachmentV2UploadAttributes.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public AttachmentV3UploadAttributes getAttachmentV3UploadAttributes()
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    String response = makeServiceRequest(ATTACHMENT_V3_PATH, "GET", null);
    try {
      return JsonUtil.fromJson(response, AttachmentV3UploadAttributes.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public byte[] uploadGroupV2Avatar(byte[] avatarCipherText, AvatarUploadAttributes uploadAttributes)
          throws IOException
  {
    return uploadToCdn0(AVATAR_UPLOAD_PATH, uploadAttributes.getAcl(), uploadAttributes.getKey(),
                       uploadAttributes.getPolicy(), uploadAttributes.getAlgorithm(),
                       uploadAttributes.getCredential(), uploadAttributes.getDate(),
                       uploadAttributes.getSignature(),
                       new ByteArrayInputStream(avatarCipherText),
                       "application/octet-stream", avatarCipherText.length,
                       new NoCipherOutputStreamFactory(),
                       null, null);
  }

  private byte[] downloadAttachmentBytes(String url, long maxSizeBytes, ProgressListener listener) throws PushNetworkException, NonSuccessfulResponseCodeException {
    String b64 = org.thoughtcrime.securesms.util.Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes());//AA+
    Request request = new Request.Builder().url(url)
                                           .addHeader("Content-Type", "application/octet-stream")
                                           .addHeader("Authorization", "Basic " + b64)//AA+)
                                           .addHeader("X-Ufsrv-Cookie", TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))
                                           .get()
                                           .build();

    Call    call   = attachmentClient.newBuilder()
                                     .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                     .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                     .build()
                                     .newCall(request);

    synchronized (connections) {
      connections.add(call);
    }

    Response     response;
    ResponseBody body;

    try {
      response = call.execute();
      body     = response.body();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    if (!response.isSuccessful()) {
      throw new NonSuccessfulResponseCodeException(response.code(), "Bad response: " + response.code());
    }

    if (body == null) {
      throw new NonSuccessfulResponseCodeException(response.code(), "Response body is empty!");
    }

    try {
      long offset = 0;
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

      if (body == null)                        throw new PushNetworkException("No response body!");
      if (body.contentLength() > maxSizeBytes) throw new PushNetworkException("Response exceeds max size!");

      InputStream  in     = body.byteStream();
      byte[]       buffer = new byte[8192];

      int  read      = 0;
      long totalRead = offset;

      while ((read = in.read(buffer, 0, buffer.length)) != -1) {
        outputStream.write(buffer, 0, read);
        if ((totalRead += read) > maxSizeBytes) throw new PushNetworkException("Response exceeded max size!");

        if (listener != null) {
          listener.onAttachmentProgress(body.contentLength() + offset, totalRead);
        }
      }

      body.close();
      return outputStream.toByteArray();
    } catch (FileNotFoundException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  private void downloadAttachment(String url, File localDestination, long maxSizeBytes, ProgressListener listener) throws PushNetworkException, NonSuccessfulResponseCodeException {
    String b64 = org.thoughtcrime.securesms.util.Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes());//AA+
    Request request = new Request.Builder().url(url)
                                           .addHeader("Content-Type", "application/octet-stream")
                                           .addHeader("Authorization", "Basic " + b64)//AA+)
                                           .addHeader("X-Ufsrv-Cookie", TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))
                                           .get()
                                           .build();

    Call    call   = attachmentClient.newBuilder()
                                     .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                     .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                     .build()
                                     .newCall(request);

    synchronized (connections) {
      connections.add(call);
    }

    Response     response;
    ResponseBody body;

    try {
      response = call.execute();
      body     = response.body();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    if (!response.isSuccessful()) {
      throw new NonSuccessfulResponseCodeException(response.code(), "Bad response: " + response.code());
    }

    if (body == null) {
      throw new NonSuccessfulResponseCodeException(response.code(), "Response body is empty!");
    }

    try {
      long           contentLength = body.contentLength();
      BufferedSource source        = body.source();
      BufferedSink   sink          = Okio.buffer(Okio.sink(localDestination));
      Buffer         sinkBuffer    = sink.buffer();

      if (contentLength > maxSizeBytes) {
        throw new NonSuccessfulResponseCodeException(response.code(), "File exceeds maximum size.");
      }

      long totalBytesRead = 0;

      for (long readCount; (readCount = source.read(sinkBuffer, 8192)) != -1; ) {
        totalBytesRead += readCount;
        sink.emitCompleteSegments();

        if (listener != null) {
          listener.onAttachmentProgress(contentLength, totalBytesRead);
        }
      }

      sink.flush();
      sink.close();
      body.close();
    } catch (FileNotFoundException e) {
      throw new AssertionError(e);
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  public Pair<Long, byte[]> uploadAttachment(PushAttachmentData attachment, AttachmentV2UploadAttributes uploadAttributes)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    long   id     = Long.parseLong(uploadAttributes.getAttachmentId());
    byte[] digest = uploadToCdn0(ATTACHMENT_UPLOAD_PATH, uploadAttributes.getAcl(), uploadAttributes.getKey(),
                                uploadAttributes.getPolicy(), uploadAttributes.getAlgorithm(),
                                uploadAttributes.getCredential(), uploadAttributes.getDate(),
                                uploadAttributes.getSignature(), attachment.getData(),
                                "application/octet-stream", attachment.getDataSize(),
                                attachment.getOutputStreamFactory(), attachment.getListener(),
                                attachment.getCancelationSignal());

    return new Pair<>(id, digest);
  }

  private byte[] uploadAttachment(String method, String url, InputStream data,
                                  long dataSize, OutputStreamFactory outputStreamFactory, ProgressListener listener, CancelationSignal cancelationSignal,
                                  String nonce)//AA+
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    String b64 = org.thoughtcrime.securesms.util.Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes());//AA+
    DigestingRequestBody requestBody = new DigestingRequestBody(data, outputStreamFactory, "application/octet-stream", dataSize, listener, cancelationSignal, 0);
    Request.Builder      request     = new Request.Builder().url(url)
                                                            .addHeader("Authorization", "Basic " + b64)//AA+
                                                            .addHeader(UFSRV_ATTACHMENT_NONCE_HEADER, nonce)//AA+
                                                            .addHeader("X-Ufsrv-Cookie", TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))
                                                            .method(method, requestBody);
    Call                 call        = attachmentClient.newBuilder()
                                                       .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                       .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                       .build()
                                                       .newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    try {
      Response response;

      try {
        response = call.execute();
      } catch (IOException e) {
        throw new PushNetworkException(e);
      }

      if (response.isSuccessful()) return requestBody.getTransmittedDigest();
      else                         throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  public ResumableUploadSpec getResumableUploadSpec(AttachmentV3UploadAttributes uploadAttributes) throws IOException {
    return new ResumableUploadSpec(Util.getSecretBytes(64),
                                   Util.getSecretBytes(16),
                                   uploadAttributes.getKey(),
                                   uploadAttributes.getCdn(),
                                   getResumableUploadUrl(uploadAttributes.getSignedUploadLocation(), uploadAttributes.getHeaders()),
                                   System.currentTimeMillis() + CDN2_RESUMABLE_LINK_LIFETIME_MILLIS);
  }

  public byte[] uploadAttachment(PushAttachmentData attachment) throws IOException {

    if (attachment.getResumableUploadSpec() == null || attachment.getResumableUploadSpec().getExpirationTimestamp() < System.currentTimeMillis()) {
      throw new ResumeLocationInvalidException();
    }

    return uploadToCdn2(attachment.getResumableUploadSpec().getResumeLocation(),
                        attachment.getData(),
                        "application/octet-stream",
                        attachment.getDataSize(),
                        attachment.getOutputStreamFactory(),
                        attachment.getListener(),
                        attachment.getCancelationSignal());
  }

  private void downloadFromCdn(File destination, int cdnNumber, String path, long maxSizeBytes, ProgressListener listener)
          throws IOException, MissingConfigurationException
  {
    try (FileOutputStream outputStream = new FileOutputStream(destination, true)) {
      downloadFromCdn(outputStream, destination.length(), cdnNumber, path, maxSizeBytes, listener);
    }
  }

  private void downloadFromCdn(OutputStream outputStream, long offset, int cdnNumber, String path, long maxSizeBytes, ProgressListener listener)
          throws PushNetworkException, NonSuccessfulResponseCodeException, MissingConfigurationException {
    ConnectionHolder[] cdnNumberClients = cdnClientsMap.get(cdnNumber);
    if (cdnNumberClients == null) {
      throw new MissingConfigurationException("Attempted to download from unsupported CDN number: " + cdnNumber + ", Our configuration supports: " + cdnClientsMap.keySet());
    }
    ConnectionHolder   connectionHolder = getRandom(cdnNumberClients, random);
    OkHttpClient       okHttpClient     = connectionHolder.getClient()
                                                          .newBuilder()
                                                          .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                          .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                          .build();

    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + "/" + path).get();

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    if (offset > 0) {
      Log.i(TAG, "Starting download from CDN with offset " + offset);
      request.addHeader("Range", "bytes=" + offset + "-");
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    Response     response = null;
    ResponseBody body     = null;

    try {
      response = call.execute();

      if (response.isSuccessful()) {
        body = response.body();

        if (body == null)                        throw new PushNetworkException("No response body!");
        if (body.contentLength() > maxSizeBytes) throw new PushNetworkException("Response exceeds max size!");

        InputStream  in     = body.byteStream();
        byte[]       buffer = new byte[32768];

        int  read      = 0;
        long totalRead = offset;

        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
          outputStream.write(buffer, 0, read);
          if ((totalRead += read) > maxSizeBytes) throw new PushNetworkException("Response exceeded max size!");

          if (listener != null) {
            listener.onAttachmentProgress(body.contentLength() + offset, totalRead);
          }
        }

        return;
      } else if (response.code() == 416) {
        throw new RangeException(offset);
      }
    } catch (NonSuccessfulResponseCodeException | PushNetworkException e) {
      throw e;
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      if (body != null) {
        body.close();
      }
      synchronized (connections) {
        connections.remove(call);
      }
    }

    throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
  }

  private byte[] uploadToCdn0(String path, String acl, String key, String policy, String algorithm,
                              String credential, String date, String signature,
                              InputStream data, String contentType, long length,
                              OutputStreamFactory outputStreamFactory, ProgressListener progressListener,
                              CancelationSignal cancelationSignal)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(cdnClientsMap.get(0), random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    DigestingRequestBody file = new DigestingRequestBody(data, outputStreamFactory, contentType, length, progressListener, cancelationSignal, 0);

    RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("acl", acl)
            .addFormDataPart("key", key)
            .addFormDataPart("policy", policy)
            .addFormDataPart("Content-Type", contentType)
            .addFormDataPart("x-amz-algorithm", algorithm)
            .addFormDataPart("x-amz-credential", credential)
            .addFormDataPart("x-amz-date", date)
            .addFormDataPart("x-amz-signature", signature)
            .addFormDataPart("file", "file", file)
            .build();

    Request.Builder request = new Request.Builder()
            .url(connectionHolder.getUrl() + "/" + path)
            .post(requestBody);

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    try {
      Response response;

      try {
        response = call.execute();
      } catch (IOException e) {
        throw new PushNetworkException(e);
      }

      if (response.isSuccessful()) return file.getTransmittedDigest();
      else                         throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  private String getResumableUploadUrl(String signedUrl, Map<String, String> headers) throws IOException {
    ConnectionHolder connectionHolder = getRandom(cdnClientsMap.get(2), random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .eventListener(new LoggingOkhttpEventListener())
            .build();

    Request.Builder request = new Request.Builder().url(buildConfiguredUrl(connectionHolder, signedUrl))
            .post(RequestBody.create(null, ""));

    for (Map.Entry<String, String> header : headers.entrySet()) {
      if (!header.getKey().equalsIgnoreCase("host")) {
        request.header(header.getKey(), header.getValue());
      }
    }

    if (connectionHolder.getHostHeader().isPresent()) {
      request.header("host", connectionHolder.getHostHeader().get());
    }

    request.addHeader("Content-Length", "0");
    request.addHeader("Content-Type", "application/octet-stream");

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    try {
      Response response;

      try {
        response = call.execute();
      } catch (IOException e) {
        throw new PushNetworkException(e);
      }

      if (response.isSuccessful()) {
        return response.header("location");
      } else {
        throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
      }
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  private byte[] uploadToCdn2(String resumableUrl, InputStream data, String contentType, long length, OutputStreamFactory outputStreamFactory, ProgressListener progressListener, CancelationSignal cancelationSignal) throws IOException {
    ConnectionHolder connectionHolder = getRandom(cdnClientsMap.get(2), random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    ResumeInfo           resumeInfo = getResumeInfo(resumableUrl, length);
    DigestingRequestBody file       = new DigestingRequestBody(data, outputStreamFactory, contentType, length, progressListener, cancelationSignal, resumeInfo.contentStart);

    if (resumeInfo.contentStart == length) {
      Log.w(TAG, "Resume start point == content length");
      try (NowhereBufferedSink buffer = new NowhereBufferedSink()) {
        file.writeTo(buffer);
      }
      return file.getTransmittedDigest();
    }

    Request.Builder request = new Request.Builder().url(buildConfiguredUrl(connectionHolder, resumableUrl))
            .put(file)
            .addHeader("Content-Range", resumeInfo.contentRange);

    if (connectionHolder.getHostHeader().isPresent()) {
      request.header("host", connectionHolder.getHostHeader().get());
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    try {
      Response response;

      try {
        response = call.execute();
      } catch (IOException e) {
        throw new PushNetworkException(e);
      }

      if (response.isSuccessful()) return file.getTransmittedDigest();
      else                         throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  private ResumeInfo getResumeInfo(String resumableUrl, long contentLength) throws IOException {
    ConnectionHolder connectionHolder = getRandom(cdnClientsMap.get(2), random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    final long   offset;
    final String contentRange;

    Request.Builder request = new Request.Builder().url(buildConfiguredUrl(connectionHolder, resumableUrl))
            .put(RequestBody.create(null, ""))
            .addHeader("Content-Range", String.format(Locale.US, "bytes */%d", contentLength));

    if (connectionHolder.getHostHeader().isPresent()) {
      request.header("host", connectionHolder.getHostHeader().get());
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    try {
      Response response;

      try {
        response = call.execute();
      } catch (IOException e) {
        throw new PushNetworkException(e);
      }

      if (response.isSuccessful()) {
        offset       = contentLength;
        contentRange = null;
      } else if (response.code() == 308) {
        String rangeCompleted = response.header("Range");

        if (rangeCompleted == null) {
          offset = 0;
        } else {
          offset = Long.parseLong(rangeCompleted.split("-")[1]) + 1;
        }

        contentRange = String.format(Locale.US, "bytes %d-%d/%d", offset, contentLength - 1, contentLength);
      } else if (response.code() == 404) {
        throw new ResumeLocationInvalidException();
      } else {
        throw new NonSuccessfulResumableUploadResponseCodeException(response.code(), "Response: " + response);
      }
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    return new ResumeInfo(contentRange, offset);
  }

  private static HttpUrl buildConfiguredUrl(ConnectionHolder connectionHolder, String url) throws IOException {
    final HttpUrl endpointUrl = HttpUrl.get(connectionHolder.url);
    final HttpUrl resumableHttpUrl;
    try {
      resumableHttpUrl = HttpUrl.get(url);
    } catch (IllegalArgumentException e) {
      throw new IOException("Malformed URL!", e);
    }

    return new HttpUrl.Builder().scheme(endpointUrl.scheme())
            .host(endpointUrl.host())
            .port(endpointUrl.port())
            .encodedPath(endpointUrl.encodedPath())
            .addEncodedPathSegments(resumableHttpUrl.encodedPath().substring(1))
            .encodedQuery(resumableHttpUrl.encodedQuery())
            .encodedFragment(resumableHttpUrl.encodedFragment())
            .build();
  }

  //AA+ clients
  private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers, ResponseCodeHandler responseCodeHandler, Optional<UnidentifiedAccess> unidentifiedAccessKey, ConnectionHolder[]  clients)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    ResponseBody responseBody = makeServiceBodyRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, responseCodeHandler, unidentifiedAccessKey, clients);
    try {
      return responseBody.string();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  private static RequestBody jsonRequestBody(String jsonBody) {
    return jsonBody != null
           ? RequestBody.create(MediaType.parse("application/json"), jsonBody)
           : null;
  }

  private static RequestBody protobufRequestBody(MessageLite protobufBody) {
    return protobufBody != null
           ? RequestBody.create(MediaType.parse("application/x-protobuf"), protobufBody.toByteArray())
           : null;
  }

  private ListenableFuture<String> submitServiceRequest(String urlFragment,
                                                        String method,
                                                        String jsonBody,
                                                        Map<String, String> headers,
                                                        Optional<UnidentifiedAccess> unidentifiedAccessKey)
  {
    OkHttpClient okHttpClient = buildOkHttpClient(unidentifiedAccessKey.isPresent());
    Call         call         = okHttpClient.newCall(buildServiceRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, unidentifiedAccessKey, false));

    synchronized (connections) {
      connections.add(call);
    }

    SettableFuture<String> bodyFuture = new SettableFuture<>();

    call.enqueue(new Callback() {
      @Override
      public void onResponse(Call call, Response response) {
        try (ResponseBody body = response.body()) {
          validateServiceResponse(response);
          bodyFuture.set(readBodyString(body));
        } catch (IOException e) {
          bodyFuture.setException(e);
        }
      }

      @Override
      public void onFailure(Call call, IOException e) {
        bodyFuture.setException(e);
      }
    });

    return bodyFuture;
  }

  private ResponseBody makeServiceBodyRequest(String urlFragment,
                                              String method,
                                              RequestBody body,
                                              Map<String, String> headers,
                                              ResponseCodeHandler responseCodeHandler,
                                              Optional<UnidentifiedAccess> unidentifiedAccessKey,
                                              ConnectionHolder[]  clients)//AA+)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, body, headers, responseCodeHandler, unidentifiedAccessKey, clients).body();
  }

  private Response makeServiceRequest(String urlFragment,
                                      String method,
                                      RequestBody body,
                                      Map<String, String> headers,
                                      ResponseCodeHandler responseCodeHandler,
                                      Optional<UnidentifiedAccess> unidentifiedAccessKey,
                                      ConnectionHolder[]  clients)//AA+
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    Response response = getServiceConnection(urlFragment, method, body, headers, unidentifiedAccessKey, true);//, clients); //AA+ clients

    ResponseBody responseBody = response.body();
    try {
      responseCodeHandler.handle(response.code(), responseBody);

      return validateServiceResponse(response);
    } catch (NonSuccessfulResponseCodeException | PushNetworkException | MalformedResponseException e) {
      if (responseBody != null) {
        responseBody.close();
      }
      throw e;
    }
  }

  private Response validateServiceResponse(Response response)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
    int    responseCode    = response.code();
    String responseMessage = response.message();

    switch (responseCode) {
      case 413:
      case 429: {
        long           retryAfterLong = Util.parseLong(response.header("Retry-After"), -1);
        Optional<Long> retryAfter     = retryAfterLong != -1 ? Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong)) : Optional.empty();
        throw new RateLimitException(responseCode, "Rate limit exceeded: " + responseCode, retryAfter);
      }
      case 401:
      case 403:
        throw new AuthorizationFailedException(responseCode, "Authorization failed!");
      case 404:
        throw new NotFoundException("Not found");
      case 409:
        //AA- ufsrv use for invalid responses
//        MismatchedDevices mismatchedDevices = readResponseJson(response, MismatchedDevices.class);
//
//        throw new MismatchedDevicesException(mismatchedDevices);
        throw new NonSuccessfulResponseCodeException(responseCode, "Bad response: " + responseCode + " " + responseMessage);
      case 410:
        StaleDevices staleDevices = readResponseJson(response, StaleDevices.class);

        throw new StaleDevicesException(staleDevices);
      case 411:
        DeviceLimit deviceLimit = readResponseJson(response, DeviceLimit.class);

        throw new DeviceLimitExceededException(deviceLimit);
      case 417:
        throw new ExpectationFailedException();
      case 423:
        RegistrationLockFailure accountLockFailure      = readResponseJson(response, RegistrationLockFailure.class);
        AuthCredentials         credentials             = accountLockFailure.backupCredentials;
        String                  basicStorageCredentials = credentials != null ? credentials.asBasic() : null;

        throw new LockedException(accountLockFailure.length,
                                  accountLockFailure.timeRemaining,
                                  basicStorageCredentials);
      case 428:
        ProofRequiredResponse proofRequiredResponse = readResponseJson(response, ProofRequiredResponse.class);
        String                retryAfterRaw = response.header("Retry-After");
        long                  retryAfter    = Util.parseInt(retryAfterRaw, -1);

        throw new ProofRequiredException(proofRequiredResponse, retryAfter);

      case 499:
        throw new DeprecatedVersionException();

      case 508:
        throw new ServerRejectedException();
    }

    if (responseCode != 200 && responseCode != 202 && responseCode != 204) {
      throw new NonSuccessfulResponseCodeException(responseCode, "Bad response: " + responseCode + " " + responseMessage);
    }

    return response;
  }

  private Response getServiceConnection(String urlFragment,
                                        String method,
                                        RequestBody body,
                                        Map<String, String> headers,
                                        Optional<UnidentifiedAccess> unidentifiedAccess,
                                        boolean doNotAddAuthenticationOrUnidentifiedAccessKey)
          throws PushNetworkException
  {
    try {
      OkHttpClient okHttpClient = buildOkHttpClient(false);//AA+ false unidentifiedAccess.isPresent());
      Call         call         = okHttpClient.newCall(buildServiceRequest(urlFragment, method, body, headers, unidentifiedAccess, doNotAddAuthenticationOrUnidentifiedAccessKey));

      synchronized (connections) {
        connections.add(call);
      }

      try {
        return call.execute();
      } finally {
        synchronized (connections) {
          connections.remove(call);
        }
      }
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  private OkHttpClient buildOkHttpClient(boolean unidentified) {
    ServiceConnectionHolder connectionHolder = (ServiceConnectionHolder) getRandom(serviceClients, random);
    OkHttpClient            baseClient       = unidentified ? connectionHolder.getUnidentifiedClient() : connectionHolder.getClient();

    return baseClient.newBuilder()
                     .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                     .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                     .retryOnConnectionFailure(automaticNetworkRetry)
                     .build();
  }

  private Request buildServiceRequest(String urlFragment,
                                      String method,
                                      RequestBody body,
                                      Map<String, String> headers,
                                      Optional<UnidentifiedAccess> unidentifiedAccess,
                                      boolean doNotAddAuthenticationOrUnidentifiedAccessKey) {

    ServiceConnectionHolder connectionHolder = (ServiceConnectionHolder) getRandom(serviceClients, random);

    Log.d(TAG, "buildServiceRequest: Opening URL: " + String.format("%s%s", connectionHolder.getUrl(), urlFragment));

    Request.Builder request = new Request.Builder();
    request.url(String.format("%s%s", connectionHolder.getUrl(), urlFragment).replaceAll("\\+", "%2B"));//AA+ replaceAll
    request.method(method, body);

    for (Map.Entry<String, String> header : headers.entrySet()) {
      request.addHeader(header.getKey(), header.getValue());
    }

    //AA-
//    if (!headers.containsKey("Authorization") && !doNotAddAuthenticationOrUnidentifiedAccessKey) {
//      if (unidentifiedAccess.isPresent()) {
//        request.addHeader("Unidentified-Access-Key", Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
//      } else if (credentialsProvider.getPassword() != null) {
//        request.addHeader("Authorization", getAuthorizationHeader(credentialsProvider));
//      }
//    }

    if (userAgent != null) {
      request.addHeader("X-Unfacd-Agent", userAgent);//AA+ X-unfacd
    }

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    if (credentialsProvider.getPassword() != null) {
      request.addHeader("Authorization", getAuthorizationHeader(credentialsProvider));
    }

    if (!TextUtils.isEmpty(TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))) {
      request.addHeader("X-Ufsrv-Cookie", TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance())); //AA+
    }

    return request.build();
  }

  private String makeServiceRequestWithoutAuthentication(String urlFragment, String method, String jsonBody)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequestWithoutAuthentication(urlFragment, method, jsonBody, NO_HANDLER);
  }

  private String makeServiceRequestWithoutAuthentication(String urlFragment, String method, String jsonBody, ResponseCodeHandler responseCodeHandler)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequestWithoutAuthentication(urlFragment, method, jsonBody, NO_HEADERS, responseCodeHandler);
  }

  private String makeServiceRequestWithoutAuthentication(String urlFragment, String method, String jsonBody, Map<String, String> headers)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequestWithoutAuthentication(urlFragment, method, jsonBody, headers, NO_HANDLER);
  }

  private String makeServiceRequestWithoutAuthentication(String urlFragment, String method, String jsonBody, Map<String, String> headers, ResponseCodeHandler responseCodeHandler)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    ResponseBody responseBody = makeServiceRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, responseCodeHandler, Optional.empty(), true).body();
    try {
      return responseBody.string();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

     private String makeServiceRequest(String urlFragment, String method, String jsonBody)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, jsonBody, NO_HEADERS, NO_HANDLER, Optional.empty(), serviceClients); //AA+ serviceClients -> defaults to service requests
  }

  private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, jsonBody, headers, NO_HANDLER, Optional.<UnidentifiedAccess>empty(), serviceClients);//AA+ serviceClients -> defaults to service requests
  }

  private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers, ResponseCodeHandler responseCodeHandler)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, jsonBody, headers, responseCodeHandler, Optional.<UnidentifiedAccess>empty(), serviceClients);
  }

  private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers, Optional<UnidentifiedAccess> unidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, jsonBody, headers, NO_HANDLER, unidentifiedAccessKey, serviceClients);
  }

  private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers, ResponseCodeHandler responseCodeHandler, Optional<UnidentifiedAccess> unidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    ResponseBody responseBody = makeServiceBodyRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, responseCodeHandler, unidentifiedAccessKey);
    try {
      return responseBody.string();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  private ResponseBody makeServiceBodyRequest(String urlFragment,
                                              String method,
                                              RequestBody body,
                                              Map<String, String> headers,
                                              ResponseCodeHandler responseCodeHandler,
                                              Optional<UnidentifiedAccess> unidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, body, headers, responseCodeHandler, unidentifiedAccessKey).body();
  }

  private Response makeServiceRequest(String urlFragment,
                                      String method,
                                      RequestBody body,
                                      Map<String, String> headers,
                                      ResponseCodeHandler responseCodeHandler,
                                      Optional<UnidentifiedAccess> unidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    return makeServiceRequest(urlFragment, method, body, headers, responseCodeHandler, unidentifiedAccessKey, false);
  }

  private Response makeServiceRequest(String urlFragment,
                                      String method,
                                      RequestBody body,
                                      Map<String, String> headers,
                                      ResponseCodeHandler responseCodeHandler,
                                      Optional<UnidentifiedAccess> unidentifiedAccessKey,
                                      boolean doNotAddAuthenticationOrUnidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    Response response = getServiceConnection(urlFragment, method, body, headers, unidentifiedAccessKey, doNotAddAuthenticationOrUnidentifiedAccessKey);

    ResponseBody responseBody = response.body();
    try {
      responseCodeHandler.handle(response.code(), responseBody);

      return validateServiceResponse(response);
    } catch (NonSuccessfulResponseCodeException | PushNetworkException | MalformedResponseException e) {
      if (responseBody != null) {
        responseBody.close();
      }
      throw e;
    }
  }

  private ConnectionHolder[] clientsFor(ClientSet clientSet) {
    switch (clientSet) {
      case ContactDiscovery:
        return contactDiscoveryClients;
      case KeyBackup:
        return keyBackupServiceClients;
      default:
        throw new AssertionError("Unknown attestation purpose");
    }
  }

  Response makeRequest(ClientSet clientSet, String authorization, List<String> cookies, String path, String method, String jsonBody)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(clientsFor(clientSet), random);

    return makeRequest(connectionHolder, authorization, cookies, path, method, jsonBody);
  }

  private Response makeRequest(ConnectionHolder connectionHolder, String authorization, List<String> cookies, String path, String method, String jsonBody)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    OkHttpClient okHttpClient = connectionHolder.getClient()
                                                .newBuilder()
                                                .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                .build();

    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + path);

    if (jsonBody != null) {
      request.method(method, RequestBody.create(MediaType.parse("application/json"), jsonBody));
    } else {
      request.method(method, null);
    }

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }

    if (cookies != null && !cookies.isEmpty()) {
      request.addHeader("Cookie", Util.join(cookies, "; "));
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    Response response;

    try {
      response = call.execute();

      if (response.isSuccessful()) {
        return response;
      }
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    switch (response.code()) {
      case 401:
      case 403:
        throw new AuthorizationFailedException(response.code(), "Authorization failed!");
      case 409:
        throw new RemoteAttestationResponseExpiredException("Remote attestation response expired");
      case 429:
        throw new RateLimitException(response.code(), "Rate limit exceeded: " + response.code());
    }

    throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
  }

  private void makeAndCloseStorageRequest(String authorization, String path, String method, RequestBody body)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    makeAndCloseStorageRequest(authorization, path, method, body, NO_HANDLER);
  }

  private void makeAndCloseStorageRequest(String authorization, String path, String method, RequestBody body, ResponseCodeHandler responseCodeHandler)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ResponseBody responseBody = makeStorageRequest(authorization, path, method, body, responseCodeHandler);
    if (responseBody != null) {
      responseBody.close();
    }
  }

  private ResponseBody makeStorageRequest(String authorization, String path, String method, RequestBody body)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    return makeStorageRequest(authorization, path, method, body, NO_HANDLER);
  }

  private ResponseBody makeStorageRequest(String authorization, String path, String method, RequestBody body, ResponseCodeHandler responseCodeHandler)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    return makeStorageRequestResponse(authorization, path, method, body, responseCodeHandler).body();
  }

  private Response makeStorageRequestResponse(String authorization, String path, String method, RequestBody body, ResponseCodeHandler responseCodeHandler)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(storageClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
                                                        .newBuilder()
                                                        .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                        .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                                                        .build();

    //AA+
    Request storageRequest = buildServiceRequest(path, method, body, new HashMap<String, String>(){{put("X-Group-Authorization", authorization);}}, Optional.empty(), false);

    //AA-
////    Log.d(TAG, "Opening URL: " + String.format("%s%s", connectionHolder.getUrl(), path));
////
////    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + path);
//    request.method(method, body);
//
//    if (connectionHolder.getHostHeader().isPresent()) {
//      request.addHeader("Host", connectionHolder.getHostHeader().get());
//    }
//
//    if (authorization != null) {
//      request.addHeader("Authorization", authorization);
//    }

    Call call = okHttpClient.newCall(storageRequest);//AA+ storageRequest

    synchronized (connections) {
      connections.add(call);
    }

    Response response;

    try {
      response = call.execute();

      if (response.isSuccessful() && response.code() != 204) {
        return response;
      }
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    ResponseBody responseBody = response.body();
    try {
      responseCodeHandler.handle(response.code(), responseBody, response::header);

      switch (response.code()) {
        case 204:
          throw new NoContentException("No content!");
        case 401:
        case 403:
          throw new AuthorizationFailedException(response.code(), "Authorization failed!");
        case 404:
          throw new NotFoundException("Not found");
        case 409:
          if (responseBody != null) {
            throw new ContactManifestMismatchException(readBodyBytes(responseBody));
          } else {
            throw new ConflictException();
          }
        case 429:
          throw new RateLimitException(response.code(), "Rate limit exceeded: " + response.code());
        case 499:
          throw new DeprecatedVersionException();
      }

      throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
    } catch (NonSuccessfulResponseCodeException | PushNetworkException e) {
      if (responseBody != null) {
        responseBody.close();
      }
      throw e;
    }
  }

  public CallingResponse makeCallingRequest(long requestId, String url, String httpMethod, List<Pair<String, String>> headers, byte[] body) {
    ConnectionHolder connectionHolder = getRandom(serviceClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .followRedirects(false)
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();
    RequestBody     requestBody = body != null ? RequestBody.create(null, body) : null;
    Request.Builder builder     = new Request.Builder()
            .url(url)
            .method(httpMethod, requestBody);
    if (headers != null) {
      for (Pair<String, String> header : headers) {
        builder.addHeader(header.first(), header.second());
      }
    }

    Request request = builder.build();

    for (int i = 0; i < MAX_FOLLOW_UPS; i++) {
      try (Response response = okHttpClient.newCall(request).execute()) {
        int responseStatus = response.code();

        if (responseStatus != 307) {
          return new CallingResponse.Success(requestId,
                                             responseStatus,
                                             response.body() != null ? response.body().bytes() : new byte[0]);
        }

        String  location = response.header("Location");
        HttpUrl newUrl   = location != null ? request.url().resolve(location) : null;

        if (newUrl != null) {
          request = request.newBuilder().url(newUrl).build();
        } else {
          return new CallingResponse.Error(requestId, new IOException("Received redirect without a valid Location header"));
        }
      } catch (IOException e) {
        Log.w(TAG, "Exception during ringrtc http call.", e);
        return new CallingResponse.Error(requestId, e);
      }
    }

    Log.w(TAG, "Calling request max redirects exceeded");
    return new CallingResponse.Error(requestId, new IOException("Redirect limit exceeded"));
  }

  private ServiceConnectionHolder[] createServiceConnectionHolders(SignalUrl[] urls,
                                                                   List<Interceptor> interceptors,
                                                                   Optional<Dns> dns,
                                                                   Optional<SignalProxy> proxy)
  {
    List<ServiceConnectionHolder> serviceConnectionHolders = new LinkedList<>();

    for (SignalUrl url : urls) {
      serviceConnectionHolders.add(new ServiceConnectionHolder(createConnectionClient(url, interceptors, dns, proxy),
                                                               createConnectionClient(url, interceptors, dns, proxy),
                                                               url.getUrl(), url.getHostHeader()));
    }

    return serviceConnectionHolders.toArray(new ServiceConnectionHolder[0]);
  }

  private static Map<Integer, ConnectionHolder[]> createCdnClientsMap(final Map<Integer, SignalCdnUrl[]> signalCdnUrlMap,
                                                                      final List<Interceptor> interceptors,
                                                                      final Optional<Dns> dns,
                                                                      final Optional<SignalProxy> proxy) {
    validateConfiguration(signalCdnUrlMap);
    final Map<Integer, ConnectionHolder[]> result = new HashMap<>();
    for (Map.Entry<Integer, SignalCdnUrl[]> entry : signalCdnUrlMap.entrySet()) {
      result.put(entry.getKey(),
                 createConnectionHolders(entry.getValue(), interceptors, dns, proxy));
    }
    return Collections.unmodifiableMap(result);
  }

  private static void validateConfiguration(Map<Integer, SignalCdnUrl[]> signalCdnUrlMap) {
    if (!signalCdnUrlMap.containsKey(0) || !signalCdnUrlMap.containsKey(2)) {
      throw new AssertionError("Configuration used to create PushServiceSocket must support CDN 0 and CDN 2");
    }
  }

  private static ConnectionHolder[] createConnectionHolders(SignalUrl[] urls, List<Interceptor> interceptors, Optional<Dns> dns, Optional<SignalProxy> proxy) {
    List<ConnectionHolder> connectionHolders = new LinkedList<>();

    for (SignalUrl url : urls) {
      connectionHolders.add(new ConnectionHolder(createConnectionClient(url, interceptors, dns, proxy), url.getUrl(), url.getHostHeader()));
    }

    return connectionHolders.toArray(new ConnectionHolder[0]);
  }

  private static OkHttpClient createConnectionClient(SignalUrl url, List<Interceptor> interceptors, Optional<Dns> dns, Optional<SignalProxy> proxy) {
    try {
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(url.getTrustStore());

      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagers, null);

      OkHttpClient.Builder builder = new OkHttpClient.Builder()
                                                     .sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), (X509TrustManager)trustManagers[0])
                                                     .connectionSpecs(url.getConnectionSpecs().orElse(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
                                                     .dns(dns.orElse(Dns.SYSTEM));

      if (proxy.isPresent()) {
        builder.socketFactory(new TlsProxySocketFactory(proxy.get().getHost(), proxy.get().getPort(), dns));
      }

      builder.sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), (X509TrustManager)trustManagers[0])
              .connectionSpecs(url.getConnectionSpecs().orElse(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
              .build();

      builder.connectionPool(new ConnectionPool(5, 45, TimeUnit.SECONDS));

      for (Interceptor interceptor : interceptors) {
        builder.addInterceptor(interceptor);
      }

      return builder.build();
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }

  private OkHttpClient createAttachmentClient() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, null, null);

      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore)null);

      return new OkHttpClient.Builder()
                             .sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()),
                                               (X509TrustManager)trustManagerFactory.getTrustManagers()[0])
                             .connectionSpecs(Util.immutableList(ConnectionSpec.RESTRICTED_TLS))
                             .build();
    } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
      throw new AssertionError(e);
    }
  }

  private String getAuthorizationHeader(CredentialsProvider credentialsProvider) {
    try {
      String identifier = credentialsProvider.getUser();//AA+
      if (credentialsProvider.getDeviceId() != SignalServiceAddress.DEFAULT_DEVICE_ID) {
        identifier += "." + credentialsProvider.getDeviceId();
      }
      return "Basic " + Base64.encodeBytes((identifier + ":" + credentialsProvider.getPassword()).getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private ConnectionHolder getRandom(ConnectionHolder[] connections, SecureRandom random) {
    return connections[random.nextInt(connections.length)];
  }

  public ProfileKeyCredential parseResponse(UUID uuid, ProfileKey profileKey, ProfileKeyCredentialResponse profileKeyCredentialResponse) throws VerificationFailedException {
    ProfileKeyCredentialRequestContext profileKeyCredentialRequestContext = clientZkProfileOperations.createProfileKeyCredentialRequestContext(random, uuid, profileKey);

    return clientZkProfileOperations.receiveProfileKeyCredential(profileKeyCredentialRequestContext, profileKeyCredentialResponse);
  }

  /**
   * Converts {@link IOException} on body byte reading to {@link PushNetworkException}.
   */
  private static byte[] readBodyBytes(ResponseBody response) throws PushNetworkException {
    if (response == null) {
      throw new PushNetworkException("No body!");
    }

    try {
      return response.bytes();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  /**
   * Converts {@link IOException} on body reading to {@link PushNetworkException}.
   */
  private static String readBodyString(ResponseBody body) throws PushNetworkException {
    if (body == null) {
      throw new PushNetworkException("No body!");
    }

    try {
      return body.string();
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  /**
   * Converts {@link IOException} on body reading to {@link PushNetworkException}.
   * {@link IOException} during json parsing is converted to a {@link MalformedResponseException}
   */
  private static <T> T readBodyJson(ResponseBody body, Class<T> clazz) throws PushNetworkException, MalformedResponseException {
    String json = readBodyString(body);
    try {
      return JsonUtil.fromJson(json, clazz);
    } catch (JsonProcessingException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }
  }

  /**
   * Converts {@link IOException} on body reading to {@link PushNetworkException}.
   * {@link IOException} during json parsing is converted to a {@link NonSuccessfulResponseCodeException} with response code detail.
   */
  private static <T> T readResponseJson(Response response, Class<T> clazz)
          throws PushNetworkException, MalformedResponseException
  {
    return readBodyJson(response.body(), clazz);
  }


  private static class GcmRegistrationId {

    @JsonProperty
    private String gcmRegistrationId;

    @JsonProperty
    private boolean webSocketChannel;

    public GcmRegistrationId() {}

    public GcmRegistrationId(String gcmRegistrationId, boolean webSocketChannel) {
      this.gcmRegistrationId = gcmRegistrationId;
      this.webSocketChannel  = webSocketChannel;
    }
  }

  private static class RegistrationLockV2 {
    @JsonProperty
    private String registrationLock;

    public RegistrationLockV2() {}

    public RegistrationLockV2(String registrationLock) {
      this.registrationLock = registrationLock;
    }
  }

  private static class RegistrationLock {
    @JsonProperty
    private String pin;

    public RegistrationLock() {}

    public RegistrationLock(String pin) {
      this.pin = pin;
    }
  }

  public static class RegistrationLockFailure {
    @JsonProperty
    public int length;

    @JsonProperty
    public long timeRemaining;

    @JsonProperty
    public AuthCredentials backupCredentials;
  }

  private static class AttachmentDescriptor {
    @JsonProperty
    private long id;

    //AA+ replacement for id above
    @JsonProperty
    private String nonce; //also known as ufId

    @JsonProperty
    private String location;

    public long getId() {
      return id;
    }

    public String getLocation() {
      return location;
    }

    public String getNonce() {
      return nonce;
    }
  }

  private static class ConnectionHolder {
    private final OkHttpClient     client;
    private final String           url;
    private final Optional<String> hostHeader;

    private ConnectionHolder(OkHttpClient client, String url, Optional<String> hostHeader) {
      this.client     = client;
      this.url        = url;
      this.hostHeader = hostHeader;
    }

    OkHttpClient getClient() {
      return client;
    }

    public String getUrl() {
      return url;
    }

    Optional<String> getHostHeader() {
      return hostHeader;
    }
  }

  private static class ServiceConnectionHolder extends ConnectionHolder {

    private final OkHttpClient unidentifiedClient;

    private ServiceConnectionHolder(OkHttpClient identifiedClient, OkHttpClient unidentifiedClient, String url, Optional<String> hostHeader) {
      super(identifiedClient, url, hostHeader);
      this.unidentifiedClient = unidentifiedClient;
    }

    OkHttpClient getUnidentifiedClient() {
      return unidentifiedClient;
    }
  }

  //AA+
  static final public String getUfsrvFenceCommand ()
  {
    return UF_FENCE;
  }

  static final public String getUfsrvMessageCommand ()
  {
    return UF_USER_MESSAGE;
  }

  static final public String getUfsrvUserCommand ()
  {
    return UF_USER;
  }

  static final public String getUfsrvCallCommand ()
  {
    return UF_CALL;
  }

  static final public String getUfsrvReceiptCommand ()
  {
    return UF_RECEIPT;
  }

  static final public String getUfsrvStateCommand ()
  {
      return UF_STATE;
  }

  static final public String getUfsrvLocationCommand ()
  {
    return UFSRVCMD_LOCATION;
  }

  static final public String getUfsrvSyncCommand ()
  {
    return UF_SYNC;
  }

  private interface ResponseCodeHandler {
    void handle(int responseCode, ResponseBody body) throws NonSuccessfulResponseCodeException, PushNetworkException;

    default void handle(int responseCode, ResponseBody body, Function<String, String> getHeader) throws NonSuccessfulResponseCodeException, PushNetworkException {
      handle(responseCode, body);
    }
  }

  private static class EmptyResponseCodeHandler implements ResponseCodeHandler {
    @Override
    public void handle(int responseCode, ResponseBody body) { }
  }

  public enum ClientSet { ContactDiscovery, KeyBackup }

  public CredentialResponse retrieveGroupsV2Credentials(int today)
          throws IOException
  {
    int    todayPlus7 = today + 7;
    String response   = makeServiceRequest(String.format(Locale.US, GROUPSV2_CREDENTIAL, today, todayPlus7),
                                           "GET",
                                           null,
                                           NO_HEADERS,
                                           Optional.empty());

    return JsonUtil.fromJson(response, CredentialResponse.class);
  }

  private static final ResponseCodeHandler GROUPS_V2_PUT_RESPONSE_HANDLER   = (responseCode, body) -> {
    if (responseCode == 409) throw new GroupExistsException();
  };;
  private static final ResponseCodeHandler GROUPS_V2_GET_LOGS_HANDLER       = NO_HANDLER;
  private static final ResponseCodeHandler GROUPS_V2_GET_CURRENT_HANDLER    = (responseCode, body) -> {
    switch (responseCode) {
      case 403: throw new NotInGroupException();
      case 404: throw new GroupNotFoundException();
    }
  };
  private static final ResponseCodeHandler GROUPS_V2_PATCH_RESPONSE_HANDLER = (responseCode, body) -> {
    if (responseCode == 400) throw new GroupPatchNotAcceptedException();
  };
  private static final ResponseCodeHandler GROUPS_V2_GET_JOIN_INFO_HANDLER  = new ResponseCodeHandler() {
    @Override
    public void handle(int responseCode, ResponseBody body) throws NonSuccessfulResponseCodeException {
      if (responseCode == 403) throw new ForbiddenException();
    }

    @Override
    public void handle(int responseCode, ResponseBody body, Function<String, String> getHeader) throws NonSuccessfulResponseCodeException {
      if (responseCode == 403) {
        throw new ForbiddenException(Optional.ofNullable(getHeader.apply("X-Unfacd-Forbidden-Reason")));
      }
    }
  };

  public void putNewGroupsV2Group(Group group, GroupsV2AuthorizationString authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    makeAndCloseStorageRequest(authorization.toString(),
                               GROUPSV2_GROUP,
                               "POST",//AA+ POST
                               protobufRequestBody(group),
                               GROUPS_V2_PUT_RESPONSE_HANDLER);

  }

  public Group getGroupsV2Group(GroupsV2AuthorizationString authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException
  {
    ResponseBody response = makeStorageRequest(authorization.toString(),
                                               GROUPSV2_GROUP,
                                               "GET",
                                               null,
                                               GROUPS_V2_GET_CURRENT_HANDLER);

    return Group.parseFrom(readBodyBytes(response));
  }

  public AvatarUploadAttributes getGroupsV2AvatarUploadForm(String authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException
  {
    ResponseBody response = makeStorageRequest(authorization,
                                               GROUPSV2_AVATAR_REQUEST,
                                               "GET",
                                               null,
                                               NO_HANDLER);

    return AvatarUploadAttributes.parseFrom(readBodyBytes(response));
  }

  public GroupChange patchGroupsV2Group(GroupChange.Actions groupChange, String authorization, Optional<byte[]> groupLinkPassword)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException
  {
    String path;
    if (groupLinkPassword.isPresent()) {
      path = String.format(GROUPSV2_GROUP_PASSWORD, Base64UrlSafe.encodeBytesWithoutPadding(groupLinkPassword.get()));
    } else {
      path = GROUPSV2_GROUP;
    }

    ResponseBody response = makeStorageRequest(authorization,
                                               path,
                                               "PATCH",
                                               protobufRequestBody(groupChange),
                                               GROUPS_V2_PATCH_RESPONSE_HANDLER);

    return GroupChange.parseFrom(readBodyBytes(response));
  }

  public GroupHistory getGroupsV2GroupHistory(int fromVersion, GroupsV2AuthorizationString authorization, int highestKnownEpoch, boolean includeFirstState)
          throws IOException
  {
    Response response = makeStorageRequestResponse(authorization.toString(),
                                                   String.format(Locale.US, GROUPSV2_GROUP_CHANGES, fromVersion, highestKnownEpoch, includeFirstState),
                                                   "GET",
                                                   null,
                                                   GROUPS_V2_GET_LOGS_HANDLER);

    if (response.body() == null) {
      throw new PushNetworkException("No body!");
    }

    GroupChanges groupChanges;
    try (InputStream input = response.body().byteStream()) {
      groupChanges = GroupChanges.parseFrom(input);
    } catch (IOException e) {
      throw new PushNetworkException(e);
    }

    if (response.code() == 206) {
      String                 contentRangeHeader = response.header("Content-Range");
      Optional<ContentRange> contentRange       = ContentRange.parse(contentRangeHeader);

      if (contentRange.isPresent()) {
        Log.i(TAG, "Additional logs for group: " + contentRangeHeader);
        return new GroupHistory(groupChanges, contentRange);
      } else {
        Log.w(TAG, "Unable to parse Content-Range header: " + contentRangeHeader);
        throw new MalformedResponseException("Unable to parse content range header on 206");
      }
    }

    return new GroupHistory(groupChanges, Optional.empty());
  }

  //AA- see below
  /*public GroupJoinInfo getGroupJoinInfo(Optional<byte[]> groupLinkPassword, GroupsV2AuthorizationString authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException
  {
    String       passwordParam = groupLinkPassword.map(Base64UrlSafe::encodeBytesWithoutPadding).orElse("");
    ResponseBody response      = makeStorageRequest(authorization.toString(),
                                                    String.format(GROUPSV2_GROUP_JOIN, passwordParam),
                                                    "GET",
                                                    null,
                                                    GROUPS_V2_GET_JOIN_INFO_HANDLER);

    return GroupJoinInfo.parseFrom(readBodyBytes(response));
  }*/

  //AA+ modified to read off json stream instead of encoded protobuf
  public FenceInfo getGroupJoinInfo(Optional<byte[]> groupLinkPassword, GroupsV2AuthorizationString authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException, IOException
  {
    String       passwordParam = groupLinkPassword.map(Base64UrlSafe::encodeBytesWithoutPadding).orElse("");
    ResponseBody response      = makeStorageRequest(authorization.toString(),
                                                    String.format(GROUPSV2_GROUP_JOIN, authorization),//AA note using authorization which (conveniently) contains the fid
                                                    "GET",
                                                    null,
                                                    GROUPS_V2_GET_JOIN_INFO_HANDLER);

    return FenceInfo.fromRestApi(response.string());
  }

  public GroupExternalCredential getGroupExternalCredential(GroupsV2AuthorizationString authorization)
          throws NonSuccessfulResponseCodeException, PushNetworkException, InvalidProtocolBufferException
  {
    //AA+
    Log.i(TAG, "NOT SUPPORTED ENDPOINT URL: https://api.unfacd.io/v1/groups/token --> throwing a non fatat exception");
    throw new NonSuccessfulResponseCodeException(404);
    //

   /* ResponseBody response = makeStorageRequest(authorization.toString(),
                                               GROUPSV2_TOKEN,
                                               "GET",
                                               null,
                                               NO_HANDLER);

    return GroupExternalCredential.parseFrom(readBodyBytes(response));*/
  }

  public CurrencyConversions getCurrencyConversions()
          throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException
  {
    String response = makeServiceRequest(PAYMENTS_CONVERSIONS, "GET", null);
    try {
      return JsonUtil.fromJson(response, CurrencyConversions.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new MalformedResponseException("Unable to parse entity", e);
    }
  }

  public static boolean isNotRegistrationPath(String path) {
    if (path == null || path.isEmpty()) {
      return true;
    }

    for (String registrationPath : REQUIRED_REGISTRATION_PATHS) {
      String trimmedRegistrationPath = registrationPath;
      int replacementIndex = registrationPath.indexOf("%s");
      if (replacementIndex >= 0) {
        trimmedRegistrationPath = trimmedRegistrationPath.substring(0, replacementIndex);
      }

      if (path.startsWith(trimmedRegistrationPath)) {
        return false;
      }
    }
    return true;
  }

  public void reportSpam(String e164, String serverGuid)
          throws NonSuccessfulResponseCodeException, MalformedResponseException, PushNetworkException
  {
    makeServiceRequest(String.format(REPORT_SPAM, e164, serverGuid), "POST", "");
  }

  private static class VerificationCodeResponseHandler implements ResponseCodeHandler {
    @Override
    public void handle(int responseCode, ResponseBody responseBody) throws NonSuccessfulResponseCodeException, PushNetworkException {
      switch (responseCode) {
        case 400:
          String body;
          try {
            body = responseBody != null ? responseBody.string() : "";
          } catch (IOException e) {
            throw new PushNetworkException(e);
          }

          if (body.isEmpty()) {
            throw new ImpossiblePhoneNumberException();
          } else {
            try {
              throw NonNormalizedPhoneNumberException.forResponse(body);
            } catch (MalformedResponseException e) {
              Log.w(TAG, "Unable to parse 400 response! Assuming a generic 400.");
              throw new ImpossiblePhoneNumberException();
            }
          }
        case 402:
          throw new CaptchaRequiredException();
      }
    }
  }

  public static final class GroupHistory {
    private final GroupChanges           groupChanges;
    private final Optional<ContentRange> contentRange;

    public GroupHistory(GroupChanges groupChanges, Optional<ContentRange> contentRange) {
      this.groupChanges = groupChanges;
      this.contentRange = contentRange;
    }

    public GroupChanges getGroupChanges() {
      return groupChanges;
    }

    public boolean hasMore() {
      return contentRange.isPresent();
    }

    /**
     * Valid iff {@link #hasMore()}.
     */
    public int getNextPageStartGroupRevision() {
      return contentRange.get().getRangeEnd() + 1;
    }
  }

  private final class ResumeInfo {
    private final String contentRange;
    private final long   contentStart;

    private ResumeInfo(String contentRange, long offset) {
      this.contentRange = contentRange;
      this.contentStart = offset;
    }
  }
}