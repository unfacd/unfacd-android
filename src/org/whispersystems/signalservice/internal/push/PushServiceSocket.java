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
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;

import android.text.TextUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.unfacd.android.data.json.JsonEntityB64EncodedEnvelope;
import com.unfacd.android.data.json.JsonEntityB64EncodedEnvelopeList;
import com.unfacd.android.data.json.JsonEntityPresenceInformation;
import com.unfacd.android.data.json.JsonEntityPresenceInformationList;
import com.unfacd.android.data.json.JsonEntityUnverifiedAccount;
import com.unfacd.android.data.json.JsonEntityVerifiedAccount;
import com.unfacd.android.data.json.JsonEntityNonceResponse;
import com.unfacd.android.data.json.JsonEntityRegistrationResponse;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment.ProgressListener;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceInfo;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.ExpectationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException;
import org.whispersystems.signalservice.api.push.exceptions.RemoteAttestationResponseExpiredException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.internal.configuration.SignalUrl;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryRequest;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryResponse;
import org.whispersystems.signalservice.internal.contacts.entities.RemoteAttestationRequest;
import org.whispersystems.signalservice.internal.contacts.entities.RemoteAttestationResponse;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.push.exceptions.MismatchedDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.StaleDevicesException;
import org.whispersystems.signalservice.internal.push.http.DigestingRequestBody;
import org.whispersystems.signalservice.internal.push.http.OutputStreamFactory;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Hex;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
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

/**
 * @author Moxie Marlinspike
 */
public class PushServiceSocket {

  private static final String TAG = PushServiceSocket.class.getSimpleName();

  private static final String VERIFY_ACCOUNT_TOKEN_PATH = "/v1/accounts/token/%s";
  private static final String REGISTER_GCM_PATH         = "/v1/accountgcm";
  private static final String TURN_SERVER_INFO          = "/v1/accounts/turn";
  private static final String SET_ACCOUNT_ATTRIBUTES    = "/v1/accounts/attributes/";
  private static final String PIN_PATH                  = "/v1/accounts/pin/";
  private static final String REQUEST_PUSH_CHALLENGE    = "/v1/accounts/fcm/preauth/%s/%s";
  private static final String PROFILE_PATH              = "/v1/profile/%s";

  public static final String UFSRVCMD_LOCATION          = "/v1/location";

  private static final String PROVISIONING_CODE_PATH    = "/v1/devices/provisioning/code";
  private static final String PROVISIONING_MESSAGE_PATH = "/v1/provisioning/%s";
  private static final String DEVICE_PATH               = "/v1/devices/%s";

  private static final String DIRECTORY_TOKENS_PATH     = "/v1/directory/tokens";

  private static final String DIRECTORY_VERIFY_PATH     = "/v1/directory/%s";
  private static final String DIRECTORY_AUTH_PATH       = "/v1/directory/auth";
  private static final String DIRECTORY_FEEDBACK_PATH   = "/v1/directory/feedback-v3/%s";
  private static final String SENDER_ACK_MESSAGE_PATH   = "/v1/messages/%s/%d";
  private static final String UUID_ACK_MESSAGE_PATH     = "/v1/messages/uuid/%s";
  private static final String ATTACHMENT_PATH           = "/v2/attachments/form/upload";

  private static final String STICKER_MANIFEST_PATH     = "stickers/%s/manifest.proto";
  private static final String STICKER_PATH              = "stickers/%s/full/%d";

  private static final String SENDER_CERTIFICATE_PATH   = "/v1/certificate/delivery";

  private static final String ATTACHMENT_DOWNLOAD_PATH  = "attachments/%d";
  private static final String ATTACHMENT_UPLOAD_PATH    = "attachments/";

  //
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
  private static final String UF_REGISTRY_USER            = "/V1/Registry/UserToken/%s";
  private static final String UF_REGISTRY_USERID          = "/V1/Registry/UserId/%s";
  private static final String UF_USER_MESSAGE             = "/V1/Message";
  private static final String UF_FENCE                    = "/V1/Fence";
  private static final String UF_FENCE_NEARBY             = "/V1/Fence/NearBy";
  private static final String UF_FENCE_NAMESEARCH         = "/V1/Fence/Search/%s";
  private static final String UF_USER                     = "/V1/User";
  private static final String UF_USER_PRESENCE            = "/V1/User/Presence";
  private static final String UF_CALL                     = "/V1/Call";
  private static final String UF_RECEIPT                  = "/V1/Receipt";
  private static final String UF_SYNC                     = "/V1/Sync";
  private static final String UF_CALL_TURN                = "/V1/Call/Turn";
  private static final String UF_NICKNAME                 = "/V1/Nickname/%s";
  private static final String UF_BOOLPREFS                = "/V1/Account/Prefs";
  private static final String UF_BOOLPREFS_STICKYGEOGROUP = "/V1/Account/Prefs/StickyGeogroup/%s/%d";
  private static final String UF_BOOLPREFS_USER           = "/V1/Account/Prefs/%s/%d";
  private static final String UF_SENDER_CERTIFICATE_PATH  = "/V1/Account/Certificate/Delivery";

  private static final String UFSRV_ATTACHMENT_NONCE_HEADER="X-UFSRV-ATTACHMENT-NONCE";
  //

  private static final Map<String, String> NO_HEADERS = Collections.emptyMap();
  private static final ResponseCodeHandler NO_HANDLER = new EmptyResponseCodeHandler();

  private       long      soTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
  private final Set<Call> connections     = new HashSet<>();

  private final ServiceConnectionHolder[]  serviceClients;
  private final ConnectionHolder[]         cdnClients;
  private final ConnectionHolder[]         contactDiscoveryClients;
  private final OkHttpClient               attachmentClient;

  private final CredentialsProvider credentialsProvider;
  private final String              userAgent;
  private final SecureRandom        random;

  public PushServiceSocket(SignalServiceConfiguration signalServiceConfiguration, CredentialsProvider credentialsProvider, String userAgent) {
    this.credentialsProvider               = credentialsProvider;
    this.userAgent                         = userAgent;
    this.serviceClients                    = createServiceConnectionHolders(signalServiceConfiguration.getSignalServiceUrls());
    this.cdnClients                        = createConnectionHolders(signalServiceConfiguration.getSignalCdnUrls());
    this.contactDiscoveryClients           = createConnectionHolders(signalServiceConfiguration.getSignalContactDiscoveryUrls());
    this.attachmentClient                  = createAttachmentClient();
    this.random                            = new SecureRandom();
  }

  //
  public String getCookie () {
    return this.credentialsProvider.getCookie();
  }


  // voice verification for ufsrv requires presence of signon cookie
  public void voiceVerifyAccountUfsrv(String regoPendingCookie, Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    Map<String, String> headers = locale != null ? Collections.singletonMap("Accept-Language", locale.getLanguage() + "-" + locale.getCountry()) : NO_HEADERS;
    String path = String.format(UF_VOICE_VERIFY_ACCOUNT, regoPendingCookie);

    if (captchaToken.isPresent()) {
      path += "?captcha=" + captchaToken.get();
    } else if (challenge.isPresent()) {
      path += "?challenge=" + challenge.get();
    }

    String ufResponse = makeServiceRequest(path, "GET", null, headers, new ResponseCodeHandler() {
                                            @Override
                                            public void handle(int responseCode) throws NonSuccessfulResponseCodeException {
                                              if (responseCode == 402) {
                                                throw new CaptchaRequiredException();
                                              }
                                            }
                                          }, Optional.absent(), serviceClients); // serviceClients -> defaults to service requests

    //doesnt return this type of response
    //JsonEntityNonceResponse uf=JsonUtil.fromJson(ufResponse, JsonEntityNonceResponse.class);

    //Log.d(TAG, "voiceVerifyAccountUfsrv: RESPONSE="+uf.getNonce());
  }


  //
  public String createAccountUfsrv(boolean voice, boolean androidSmsRetriever, Locale locale, Optional<String> captchaToken, Optional<String> challenge, Optional<String> e164Number) throws IOException {
    Map<String, String> headers = voice && locale != null ? Collections.singletonMap("Accept-Language", locale.getLanguage() + "-" + locale.getCountry()) : NO_HEADERS;

    String ufResponse = makeServiceRequest(String.format(UF_REQUEST_REGISTER_NONCE), "GET", null, headers);
    JsonEntityNonceResponse uf = JsonUtil.fromJson(ufResponse, JsonEntityNonceResponse.class);

    Log.d(TAG, ">> REGO NONCE="+uf.getNonce());

    //either a challenge or a captcha
    String jsonString = JsonUtil.toJson(new JsonEntityUnverifiedAccount(credentialsProvider.getUser(), e164Number.get(), credentialsProvider.getPassword(), uf.getNonce(), androidSmsRetriever ? "android-ng" : "android", captchaToken.orNull(), challenge.orNull()));

    android.util.Log.d(TAG, String.format(">>> createAccountUfsrv: sending json: '%s'",  jsonString));
    ufResponse = makeServiceRequest(UF_REGISTER, "POST", jsonString, NO_HEADERS, new ResponseCodeHandler() {
                                  @Override
                                  public void handle(int responseCode) throws NonSuccessfulResponseCodeException {
                                    if (responseCode == 402) {
                                      throw new CaptchaRequiredException();
                                    }
                                  }
                                });

    JsonEntityRegistrationResponse uf2 = JsonUtil.fromJson(ufResponse, JsonEntityRegistrationResponse.class);
    Log.d(TAG, ">> REGO PENDING COOKIE=" + uf2.getCookie());
    return uf2.getCookie();

  }

/*
{"cookie":"14128...","registrationId":4128,"signalingKey":"/H44..==","verificationCode":"637323","voice":true}
 */
  //
  public JsonEntityVerifiedAccount verifyAccountCodeUfsrv(String verificationCode, String signalingKey, int registrationId, boolean fetchesMessages, String pin,  byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                                          String pendingCookie, String username, String e164number, byte[] profileKey)
          throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, verificationCode, unidentifiedAccessKey, unrestrictedUnidentifiedAccess, pendingCookie, username, e164number, profileKey);
    String responseText = makeServiceRequest(String.format(UF_VERIFY_ACCOUNT), "POST", JsonUtil.toJson(signalingKeyEntity));//"PUT"

    return JsonUtil.fromJson(responseText, JsonEntityVerifiedAccount.class);
  }

  //when verified, verification code is returned
  public Optional<String> isRegistrationStatusVerified (String registrationCookie)
  {
    try {
      String responseText = makeServiceRequest(String.format(UF_VERIFY_ACCOUNTSTATUS, registrationCookie), "GET", null);
      JsonEntityVerifiedAccount verifiedAccount = JsonUtil.fromJson(responseText, JsonEntityVerifiedAccount.class);
      if (!TextUtils.isEmpty(verifiedAccount.getVerificationCode())) return Optional.of(verifiedAccount.getVerificationCode());
      else return Optional.absent();
    }
    catch (Exception  e) {
      return Optional.absent();
    }
  }

  public void verifyAccountToken(String verificationToken, String signalingKey, int registrationId, boolean voice, boolean video, boolean fetchesMessages, String pin)
      throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, "", null, false, "", "", null, null);// last 4 argumets
    makeServiceRequest(String.format(VERIFY_ACCOUNT_TOKEN_PATH, verificationToken),
                "PUT", JsonUtil.toJson(signalingKeyEntity));
  }

  public void setAccountAttributes(String signalingKey, int registrationId, boolean fetchesMessages, String pin, byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess) throws IOException {
    AccountAttributes accountAttributes = new AccountAttributes(signalingKey, registrationId, fetchesMessages, pin, "", unidentifiedAccessKey, unrestrictedUnidentifiedAccess,"", "",null, null);// last 4 arguments
//    makeServiceRequest(SET_ACCOUNT_ATTRIBUTES, "PUT", JsonUtil.toJson(accountAttributes));
    Log.e(TAG, "setAccountAttributes: NOT IMPLEMENETD ");
  }

  public String getNewDeviceVerificationCode() throws IOException {
    String responseText = makeServiceRequest(PROVISIONING_CODE_PATH, "GET", null);
    return JsonUtil.fromJson(responseText, DeviceCode.class).getVerificationCode();
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
    //
    makeServiceRequest(UF_ACCOUNT_GCM, "POST", JsonUtil.toJson(registration));
  }

  public void unregisterGcmId() throws IOException {
    //
    makeServiceRequest(UF_ACCOUNT_GCM, "DELETE", null);
  }

  public void requestPushChallenge(String gcmRegistrationId, String e164number) throws IOException {
    makeServiceRequest(String.format(Locale.US, REQUEST_PUSH_CHALLENGE, gcmRegistrationId, e164number), "GET", null);
  }

  public void setPin(String pin) throws IOException {
    RegistrationLock accountLock = new RegistrationLock(pin);
    makeServiceRequest(PIN_PATH, "PUT", JsonUtil.toJson(accountLock));
  }

  public void removePin() throws IOException {
    makeServiceRequest(PIN_PATH, "DELETE", null);
  }

  //
  //curl -Ss -X POST -u a:a https://api.unfacd.io/V1/Account/Prefs/pref_roaming_mode/1
  public String storeGroupPreferences(String jsonPayload) throws IOException {
    String jsonResponse=makeServiceRequest(UF_BOOLPREFS, "POST", jsonPayload);
    return jsonResponse;
  }
//

  //
  public String storeStickyGeogroupPreference (long fid, int on_off) throws IOException {
    String jsonResponse=makeServiceRequest(String.format(UF_BOOLPREFS_STICKYGEOGROUP, fid, on_off), "POST", "");
    return jsonResponse;
  }
  //

  //
  public String storeTogglableUserPreference (String prefName, int on_off) throws IOException {
    String jsonResponse=makeServiceRequest(String.format(UF_BOOLPREFS_USER,  prefName, on_off), "POST", "");
    return jsonResponse;
  }
  //

  //
  public String fencesNearBy (String fenceLocation)  {
    try
    {
      String jsonResponse=makeServiceRequest(String.format(UF_FENCE_NEARBY, fenceLocation), "POST", fenceLocation);
      return jsonResponse;
    }
    catch (IOException e) {
      return null;
    }
  }

  public String SearchfencesByName (String searchText)  {
    try
    {
      String jsonResponse=makeServiceRequest(String.format(UF_FENCE_NAMESEARCH, searchText), "GET", null);
      return jsonResponse;
    }
    catch (IOException e) {
      return null;
    }
  }

  //
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

  // orig
  public SendMessageResponse sendMessage(OutgoingPushMessageList bundle, Optional<UnidentifiedAccess> unidentifiedAccess)
      throws IOException
  {
    try {
      String jsonPayload=JsonUtil.toJson(bundle);
      //Log.d(TAG, String.format(">>sendMessage: SENDING JSON PYLOAD: '%s'", jsonPayload));
      String responseText = makeServiceRequest(String.format(UF_USER_MESSAGE, bundle.getDestination()), "POST", JsonUtil.toJson(bundle), NO_HEADERS, NO_HANDLER, unidentifiedAccess, serviceClients);
      if (responseText == null) return new SendMessageResponse(false);
      else                      return JsonUtil.fromJson(responseText, SendMessageResponse.class);
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(bundle.getDestination(), nfe);
    }
  }

  // ufComand semantics
  public SendMessageResponse sendMessage(OutgoingPushMessageList bundle, Optional<UnidentifiedAccess> unidentifiedAccess, UfsrvCommand ufCommand)
          throws IOException
  {
    try {
      String jsonPayload=JsonUtil.toJson(bundle);
//      Log.d(TAG, String.format(">>sendMessage: SENDING JSON PYLOAD: '%s'", jsonPayload));
      String responseText = makeServiceRequest(ufCommand.getServerCommandPathArgs(), "POST", JsonUtil.toJson(bundle), NO_HEADERS, NO_HANDLER, unidentifiedAccess, serviceClients);

      if (responseText == null) return new SendMessageResponse(false);
      else                      return JsonUtil.fromJson(responseText, SendMessageResponse.class);
    } catch (NotFoundException nfe) {
      throw new UnregisteredUserException(bundle.getDestination(), nfe);
    }
  }

  public List<SignalServiceProtos.Envelope> getMessages() throws IOException {
    String responseText = makeServiceRequest(String.format(UF_USER_MESSAGE, TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance())), "GET", null);// ufsrv
    return reconstructUfrsvEnvelopes(JsonUtil.fromJson(responseText, JsonEntityB64EncodedEnvelopeList.class).getMessages());
  }

  //
  private List<SignalServiceProtos.Envelope> reconstructUfrsvEnvelopes (List<JsonEntityB64EncodedEnvelope> entities)
  {
    List<SignalServiceProtos.Envelope> envelopes=new LinkedList();

    for (JsonEntityB64EncodedEnvelope entity : entities)
    {
      try
      {
        SignalServiceProtos.Envelope envelope = SignalServiceProtos.Envelope.parseFrom(Base64.decode(entity.getMessage()));
        envelopes.add(envelope);
      }
      catch (IOException ex)
      {
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


  public void registerPreKeys(String jsonString)
          throws IOException
  {
    makeServiceRequest(String.format(UF_ACCOUNT_KEYS, ""), "POST",
            jsonString);
  }

  public int getAvailablePreKeys() throws IOException {
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

      String path = String.format(UF_ACCOUNT_KEYS_PREKEYS_DEV, destination.getNumber(), deviceId);

      if (destination.getRelay().isPresent()) {
        path = path + "?relay=" + destination.getRelay().get();
      }

      String             responseText = makeServiceRequest(path, "GET", null, NO_HEADERS, NO_HANDLER, unidentifiedAccess, serviceClients);
      PreKeyResponse     response     = JsonUtil.fromJson(responseText, PreKeyResponse.class);
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
      throw new UnregisteredUserException(destination.getNumber(), nfe);
    }
  }

  public PreKeyBundle getPreKey(SignalServiceAddress destination, int deviceId) throws IOException {
    try {
      String path = String.format(UF_ACCOUNT_KEYS_PREKEYS_DEV, destination.getNumber(), String.valueOf(deviceId));
      if (destination.getRelay().isPresent()) {
        path = path + "?relay=" + destination.getRelay().get();
      }

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
      throw new UnregisteredUserException(destination.getNumber(), nfe);
    }
  }

  public SignedPreKeyEntity getCurrentSignedPreKey() throws IOException {
    try {
      //String responseText = makeServiceRequest(SIGNED_PREKEY_PATH, "GET", null);
      String responseText = makeServiceRequest(UF_ACCOUNT_SIGNED_PREKEY, "GET", null);
      return JsonUtil.fromJson(responseText, SignedPreKeyEntity.class);
    } catch (NotFoundException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  public void setCurrentSignedPreKey(SignedPreKeyRecord signedPreKey) throws IOException {
    SignedPreKeyEntity signedPreKeyEntity = new SignedPreKeyEntity(signedPreKey.getId(),
                                                                   signedPreKey.getKeyPair().getPublicKey(),
                                                                   signedPreKey.getSignature());
    //makeServiceRequest(SIGNED_PREKEY_PATH, "PUT", JsonUtil.toJson(signedPreKeyEntity));
//    Log.d(TAG, String.format("> setCurrentSignedPreKey: sending pre key '%s'", JsonUtil.toJson(signedPreKeyEntity)));
    makeServiceRequest(UF_ACCOUNT_SIGNED_PREKEY, "POST", JsonUtil.toJson(signedPreKeyEntity));

  }

  public void retrieveAttachment(long attachmentId, File destination, int maxSizeBytes, ProgressListener listener)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    downloadFromCdn(destination, String.format(ATTACHMENT_DOWNLOAD_PATH, attachmentId), maxSizeBytes, listener);
  }

  // return type
  public Pair<String, byte[]> sendAttachment(PushAttachmentData attachment) throws IOException {
    String               response      = makeServiceRequest(String.format(UF_ACCOUNT_ATTACHEMENTS, ""), "GET", null, NO_HEADERS, NO_HANDLER, Optional.absent(), cdnClients); // cdnClients

    AttachmentDescriptor attachmentKey = JsonUtil.fromJson(response, AttachmentDescriptor.class);

    if (attachmentKey == null || attachmentKey.getLocation() == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    Log.w(TAG, "Got attachment content location: '" + attachmentKey.getLocation() + "' Nonce:'" + attachmentKey.getNonce() + "'");

    byte[] digest = uploadAttachment("PUT", attachmentKey.getLocation(), attachment.getData(),
            attachment.getDataSize(), attachment.getOutputStreamFactory(), attachment.getListener(), attachmentKey.getNonce());

    return new Pair<>(attachmentKey.getNonce(), digest);//
  }


  // this returns the nonce as indentifier instead of long id
  public Pair<String, byte[]> sendAttachmentUf(PushAttachmentData attachment) throws IOException {
    String               response      = makeServiceRequest(String.format(UF_ACCOUNT_ATTACHEMENTS, ""), "GET", null, NO_HEADERS, NO_HANDLER, Optional.absent(), cdnClients);

    AttachmentDescriptor attachmentKey = JsonUtil.fromJson(response, AttachmentDescriptor.class);

    if (attachmentKey == null || attachmentKey.getLocation() == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    Log.w(TAG, "sendAttachmentUf: Got attachment content location: '" + attachmentKey.getLocation() + "' Nonce:'" + attachmentKey.getNonce() + "'");

    byte[] digest=uploadAttachment("PUT", attachmentKey.getLocation(), attachment.getData(),
            attachment.getDataSize(), attachment.getOutputStreamFactory(), attachment.getListener(), attachmentKey.getNonce());

    return new Pair<>(attachmentKey.getNonce(), digest);//
  }

  // this returns the nonce as indentifier instead of long id
  public Pair<String, byte[]> sendProfileAvatarUfsrv (ProfileAvatarData attachment) throws IOException {
    String               response      = makeServiceRequest(String.format(UF_ACCOUNT_ATTACHEMENTS, ""), "GET", null, NO_HEADERS, NO_HANDLER, Optional.absent(), cdnClients); // cdnClients

    AttachmentDescriptor attachmentKey = JsonUtil.fromJson(response, AttachmentDescriptor.class);

    if (attachmentKey == null || attachmentKey.getLocation() == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    Log.w(TAG, "sendAttachmentUf: Got attachment content location: '" + attachmentKey.getLocation() + "' Nonce:'" + attachmentKey.getNonce() + "'");

    byte[] digest=uploadAttachment("PUT", attachmentKey.getLocation(), attachment.getData(),
                                   attachment.getDataLength(), attachment.getOutputStreamFactory(), null, attachmentKey.getNonce());

    return new Pair<>(attachmentKey.getNonce(), digest);//
  }

  public void retrieveProfileAvatarUfsrv(String ufId, File destination, int maxSizeBytes) throws IOException
  {
//    downloadFromCdn(destination, path, maxSizeBytes);
    String path = String.format(BuildConfig.UFSRVMEDIA_URL+UF_ACCOUNT_ATTACHEMENTS, ufId);//
    Log.w(TAG, String.format("retrieveProfileAvatarUfsrv: Downloading from:'%s'", path));
    downloadAttachment(path, destination, maxSizeBytes, null);
  }
  //

  public void retrieveAttachmentUfsrv(long attachmentId, String ufId, File destination, int maxSizeBytes, ProgressListener listener) throws IOException {

    String path = String.format(BuildConfig.UFSRVMEDIA_URL+UF_ACCOUNT_ATTACHEMENTS, ufId);//
    Log.w(TAG, String.format("retrieveAttachmentUfsrv: Downloading from:'%s'", path));

    downloadAttachment(path, destination, maxSizeBytes, listener);
  }

  public SignalServiceProfile retrieveProfile(SignalServiceAddress target, Optional<UnidentifiedAccess> unidentifiedAccess)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    try {
      String response = makeServiceRequest(String.format(PROFILE_PATH, target.getNumber()), "GET", null);
      return JsonUtil.fromJson(response, SignalServiceProfile.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new NonSuccessfulResponseCodeException("Unable to parse entity");
    }
  }

  public void retrieveProfileAvatar(String path, File destination, int maxSizeBytes)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    downloadFromCdn(destination, path, maxSizeBytes, null);
  }

  public void retrieveSticker(File destination, byte[] packId, int stickerId)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    String hexPackId = Hex.toStringCondensed(packId);
    downloadFromCdn(destination, String.format(STICKER_PATH, hexPackId, stickerId), 1024 * 1024, null);
  }

  public byte[] retrieveSticker(byte[] packId, int stickerId)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    String                hexPackId = Hex.toStringCondensed(packId);
    ByteArrayOutputStream output    = new ByteArrayOutputStream();

    downloadFromCdn(output, String.format(STICKER_PATH, hexPackId, stickerId), 1024 * 1024, null);

    return output.toByteArray();
  }

  public byte[] retrieveStickerManifest(byte[] packId)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    String                hexPackId = Hex.toStringCondensed(packId);
    ByteArrayOutputStream output    = new ByteArrayOutputStream();

    downloadFromCdn(output, String.format(STICKER_MANIFEST_PATH, hexPackId), 1024 * 1024, null);

    return output.toByteArray();
  }

  public void setProfileName(String name) throws NonSuccessfulResponseCodeException, PushNetworkException {
    makeServiceRequest(String.format(PROFILE_PATH, "name/" + (name == null ? "" : URLEncoder.encode(name))), "PUT", "");
  }

  public void setProfileAvatar(ProfileAvatarData profileAvatar)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    String                        response       = makeServiceRequest(String.format(PROFILE_PATH, "form/avatar"), "GET", null);
    ProfileAvatarUploadAttributes formAttributes;

    try {
      formAttributes = JsonUtil.fromJson(response, ProfileAvatarUploadAttributes.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new NonSuccessfulResponseCodeException("Unable to parse entity");
    }

    if (profileAvatar != null) {
      uploadToCdn("", formAttributes.getAcl(), formAttributes.getKey(),
                  formAttributes.getPolicy(), formAttributes.getAlgorithm(),
                  formAttributes.getCredential(), formAttributes.getDate(),
                  formAttributes.getSignature(), profileAvatar.getData(),
                  profileAvatar.getContentType(), profileAvatar.getDataLength(),
                  profileAvatar.getOutputStreamFactory(), null);
    }
  }

  public List<ContactTokenDetails> retrieveDirectory(Set<String> contactTokens)
      throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    try {
      ContactTokenList        contactTokenList = new ContactTokenList(new LinkedList<>(contactTokens));
      String                  response         = makeServiceRequest(UF_ACCOUNT_SHARED_CONTACTS, "POST", JsonUtil.toJson(contactTokenList));//

      ContactTokenDetailsList activeTokens     = JsonUtil.fromJson(response, ContactTokenDetailsList.class);

      return activeTokens.getContacts();
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new NonSuccessfulResponseCodeException("Unable to parse entity");
    }
  }

  public List<JsonEntityPresenceInformation> retrievePresenceInformation(String requestBody)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    try {
      String                  response         = makeServiceRequest(UF_USER_PRESENCE, "POST", requestBody);

      JsonEntityPresenceInformationList activeTokens     = JsonUtil.fromJson(response, JsonEntityPresenceInformationList.class);

      return activeTokens.getSharingList();
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new NonSuccessfulResponseCodeException("Unable to parse entity");
    }
  }

  public ContactTokenDetails getContactTokenDetails(String contactToken) throws IOException {
    try {
      String response = makeServiceRequest(String.format(UF_REGISTRY_USER, contactToken), "GET", null);//
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

  public String getContactDiscoveryAuthorization() throws IOException {
    String response = makeServiceRequest(DIRECTORY_AUTH_PATH, "GET", null);
    ContactDiscoveryCredentials token = JsonUtil.fromJson(response, ContactDiscoveryCredentials.class);
    return Credentials.basic(token.getUsername(), token.getPassword());
  }

  public Pair<RemoteAttestationResponse, List<String>> getContactDiscoveryRemoteAttestation(String authorization, RemoteAttestationRequest request, String mrenclave)
          throws IOException
  {
    Response     response   = makeContactDiscoveryRequest(authorization, new LinkedList<String>(), "/v1/attestation/" + mrenclave, "PUT", JsonUtil.toJson(request));
    ResponseBody body       = response.body();
    List<String> rawCookies = response.headers("Set-Cookie");
    List<String> cookies    = new LinkedList<>();

    for (String cookie : rawCookies) {
      cookies.add(cookie.split(";")[0]);
    }

    if (body != null) {
      return new Pair<>(JsonUtil.fromJson(body.string(), RemoteAttestationResponse.class), cookies);
    } else {
      throw new NonSuccessfulResponseCodeException("Empty response!");
    }
  }

  public DiscoveryResponse getContactDiscoveryRegisteredUsers(String authorizationToken, DiscoveryRequest request, List<String> cookies, String mrenclave)
          throws IOException
  {
    ResponseBody body = makeContactDiscoveryRequest(authorizationToken, cookies, "/v1/discovery/" + mrenclave, "PUT", JsonUtil.toJson(request)).body();

    if (body != null) {
      return JsonUtil.fromJson(body.string(), DiscoveryResponse.class);
    } else {
      throw new NonSuccessfulResponseCodeException("Empty response!");
    }
  }

  public void reportContactDiscoveryServiceMatch() throws IOException {
    makeServiceRequest(String.format(DIRECTORY_FEEDBACK_PATH, "ok"), "PUT", "");
  }

  public void reportContactDiscoveryServiceMismatch() throws IOException {
    makeServiceRequest(String.format(DIRECTORY_FEEDBACK_PATH, "mismatch"), "PUT", "");
  }

  public void reportContactDiscoveryServiceAttestationError(String reason) throws IOException {
    ContactDiscoveryFailureReason failureReason = new ContactDiscoveryFailureReason(reason);
    makeServiceRequest(String.format(DIRECTORY_FEEDBACK_PATH, "attestation-error"), "PUT", JsonUtil.toJson(failureReason));
  }

   public void reportContactDiscoveryServiceUnexpectedError(String reason) throws IOException {
    ContactDiscoveryFailureReason failureReason = new ContactDiscoveryFailureReason(reason);
    makeServiceRequest("/v1/directory/feedback-v2/unexpected-error", "PUT", JsonUtil.toJson(failureReason));
  }

  public TurnServerInfo getTurnServerInfo() throws IOException {
    String response = makeServiceRequest(UF_CALL_TURN/*TURN_SERVER_INFO*/, "GET", null);
    return JsonUtil.fromJson(response, TurnServerInfo.class);
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

  private void downloadAttachment(String url, File localDestination, int maxSizeBytes, ProgressListener listener) throws PushNetworkException, NonSuccessfulResponseCodeException {
    String b64 = org.thoughtcrime.securesms.util.Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes());//
    Request request = new Request.Builder().url(url)
            .addHeader("Content-Type", "application/octet-stream")
            .addHeader("Authorization", "Basic "+b64)//)
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
      throw new NonSuccessfulResponseCodeException("Bad response: " + response.code());
    }

    if (body == null) {
      throw new NonSuccessfulResponseCodeException("Response body is empty!");
    }

    try {
      long           contentLength = body.contentLength();
      BufferedSource source        = body.source();
      BufferedSink   sink          = Okio.buffer(Okio.sink(localDestination));
      Buffer         sinkBuffer    = sink.buffer();

      if (contentLength > maxSizeBytes) {
        throw new NonSuccessfulResponseCodeException("File exceeds maximum size.");
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

  public Pair<Long, byte[]> uploadAttachment(PushAttachmentData attachment, AttachmentUploadAttributes uploadAttributes)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    long   id     = Long.parseLong(uploadAttributes.getAttachmentId());
    byte[] digest = uploadToCdn(ATTACHMENT_UPLOAD_PATH, uploadAttributes.getAcl(), uploadAttributes.getKey(),
                                uploadAttributes.getPolicy(), uploadAttributes.getAlgorithm(),
                                uploadAttributes.getCredential(), uploadAttributes.getDate(),
                                uploadAttributes.getSignature(), attachment.getData(),
                                "application/octet-stream", attachment.getDataSize(),
                                attachment.getOutputStreamFactory(), attachment.getListener());

    return new Pair<>(id, digest);
  }

  private byte[] uploadAttachment(String method, String url, InputStream data,
                                  long dataSize, OutputStreamFactory outputStreamFactory, ProgressListener listener,
                                  String nonce)//
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    String b64 = org.thoughtcrime.securesms.util.Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes());//
    DigestingRequestBody requestBody = new DigestingRequestBody(data, outputStreamFactory, "application/octet-stream", dataSize, listener);
    Request.Builder      request     = new Request.Builder().url(url)
            .addHeader("Authorization", "Basic "+b64)//
            .addHeader(UFSRV_ATTACHMENT_NONCE_HEADER, nonce)//
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
      else                         throw new NonSuccessfulResponseCodeException("Response: " + response);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  public AttachmentUploadAttributes getAttachmentUploadAttributes() throws NonSuccessfulResponseCodeException, PushNetworkException {
    String response = makeServiceRequest(ATTACHMENT_PATH, "GET", null);
    try {
      return JsonUtil.fromJson(response, AttachmentUploadAttributes.class);
    } catch (IOException e) {
      Log.w(TAG, e);
      throw new NonSuccessfulResponseCodeException("Unable to parse entity");
    }
  }

  private void downloadFromCdn(File destination, String path, int maxSizeBytes, ProgressListener listener)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(cdnClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + "/" + path).get();

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    Response response;

    try {
      response = call.execute();

      if (response.isSuccessful()) {
        ResponseBody body = response.body();

        if (body == null)                        throw new PushNetworkException("No response body!");
        if (body.contentLength() > maxSizeBytes) throw new PushNetworkException("Response exceeds max size!");

        InputStream  in     = body.byteStream();
        OutputStream out    = new FileOutputStream(destination);
        byte[]       buffer = new byte[32768];

        int read, totalRead = 0;

        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
          out.write(buffer, 0, read);
          if ((totalRead += read) > maxSizeBytes) throw new PushNetworkException("Response exceeded max size!");

          if (listener != null) {
            listener.onAttachmentProgress(body.contentLength(), totalRead);
          }
        }

        return;
      }
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    throw new NonSuccessfulResponseCodeException("Response: " + response);
  }

  private void downloadFromCdn(OutputStream outputStream, String path, int maxSizeBytes, ProgressListener listener)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(cdnClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + "/" + path).get();

    if (connectionHolder.getHostHeader().isPresent()) {
      request.addHeader("Host", connectionHolder.getHostHeader().get());
    }

    Call call = okHttpClient.newCall(request.build());

    synchronized (connections) {
      connections.add(call);
    }

    Response response;

    try {
      response = call.execute();

      if (response.isSuccessful()) {
        ResponseBody body = response.body();

        if (body == null)                        throw new PushNetworkException("No response body!");
        if (body.contentLength() > maxSizeBytes) throw new PushNetworkException("Response exceeds max size!");

        InputStream  in     = body.byteStream();
        byte[]       buffer = new byte[32768];

        int read, totalRead = 0;

        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
          outputStream.write(buffer, 0, read);
          if ((totalRead += read) > maxSizeBytes) throw new PushNetworkException("Response exceeded max size!");

          if (listener != null) {
            listener.onAttachmentProgress(body.contentLength(), totalRead);
          }
        }

        return;
      }
    } catch (IOException e) {
      throw new PushNetworkException(e);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }

    throw new NonSuccessfulResponseCodeException("Response: " + response);
  }

  private byte[] uploadToCdn(String path, String acl, String key, String policy, String algorithm,
                             String credential, String date, String signature,
                             InputStream data, String contentType, long length,
                             OutputStreamFactory outputStreamFactory, ProgressListener progressListener)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(cdnClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    DigestingRequestBody file = new DigestingRequestBody(data, outputStreamFactory, contentType, length, progressListener);

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
      else                         throw new NonSuccessfulResponseCodeException("Response: " + response);
    } finally {
      synchronized (connections) {
        connections.remove(call);
      }
    }
  }

  // clients
  private String makeServiceRequest(String urlFragment, String method, String body, Map<String, String> headers, ResponseCodeHandler responseCodeHandler, Optional<UnidentifiedAccess> unidentifiedAccessKey, ConnectionHolder[]  clients)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    Response response = null;
    try {
      response = getServiceConnection(urlFragment, method, body, headers, unidentifiedAccessKey, clients); // clients
    } catch (Exception x) {
      Log.d(TAG, x.getMessage());
      throw new PushNetworkException(x);//
    }
    int    responseCode;
    String responseMessage;
    String responseBody;

    try {
      responseCode    = response.code();
      responseMessage = response.message();
      responseBody    = response.body().string();
    } catch (IOException ioe) {
      throw new PushNetworkException(ioe);
    }

    responseCodeHandler.handle(responseCode);

    switch (responseCode) {
      case 413:
        throw new RateLimitException("Rate limit exceeded: " + responseCode);
      case 401:
      case 403:
        throw new AuthorizationFailedException("Authorization failed!");
      case 404:
        throw new NotFoundException("Not found");
      case 409:
        MismatchedDevices mismatchedDevices;

        try {
          mismatchedDevices = JsonUtil.fromJson(responseBody, MismatchedDevices.class);
        } catch (JsonProcessingException e) {
//          Log.w(TAG, e);
//          throw new NonSuccessfulResponseCodeException("Bad response: " + responseCode + " " + responseMessage);
          throw new PushNetworkException("Bad response: " + responseCode + " " + responseMessage);
        } catch (IOException e) {
          throw new PushNetworkException(e);
        }

        throw new MismatchedDevicesException(mismatchedDevices);
      case 410:
        StaleDevices staleDevices;

        try {
          staleDevices = JsonUtil.fromJson(responseBody, StaleDevices.class);
        } catch (JsonProcessingException e) {
          throw new NonSuccessfulResponseCodeException("Bad response: " + responseCode + " " + responseMessage);
        } catch (IOException e) {
          throw new PushNetworkException(e);
        }

        throw new StaleDevicesException(staleDevices);
      case 411:
        DeviceLimit deviceLimit;

        try {
          deviceLimit = JsonUtil.fromJson(responseBody, DeviceLimit.class);
        } catch (JsonProcessingException e) {
          throw new NonSuccessfulResponseCodeException("Bad response: " + responseCode + " " + responseMessage);
        } catch (IOException e) {
          throw new PushNetworkException(e);
        }

        throw new DeviceLimitExceededException(deviceLimit);
      case 417:
        throw new ExpectationFailedException();
      case 423:
        RegistrationLockFailure accountLockFailure;

        try {
          accountLockFailure = JsonUtil.fromJson(responseBody, RegistrationLockFailure.class);
        } catch (JsonProcessingException e) {
          Log.w(TAG, e);
          throw new NonSuccessfulResponseCodeException("Bad response: " + responseCode + " " + responseMessage);
        } catch (IOException e) {
          throw new PushNetworkException(e);
        }

        throw new LockedException(accountLockFailure.length, accountLockFailure.timeRemaining);
    }

    if (responseCode != 200 && responseCode != 204) {
      throw new NonSuccessfulResponseCodeException("Bad response: " + responseCode + " " +
                                                           responseMessage);
    }

    return responseBody;
  }

  private String makeServiceRequest(String urlFragment, String method, String body)
      throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    return makeServiceRequest(urlFragment, method, body, NO_HEADERS, NO_HANDLER, Optional.absent(), serviceClients); // serviceClients -> defaults to service requests
  }

  private String makeServiceRequest(String urlFragment, String method, String body, Map<String, String> headers)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    return makeServiceRequest(urlFragment, method, body, headers, NO_HANDLER, Optional.<UnidentifiedAccess>absent(), serviceClients);// serviceClients -> defaults to service requests
  }

  private String makeServiceRequest(String urlFragment, String method, String body, Map<String, String> headers, ResponseCodeHandler responseCodeHandler)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    return makeServiceRequest(urlFragment, method, body, headers, responseCodeHandler, Optional.<UnidentifiedAccess>absent(), serviceClients);
  }

  private String makeServiceRequest(String urlFragment, String method, String body, Map<String, String> headers, Optional<UnidentifiedAccess> unidentifiedAccessKey)
          throws NonSuccessfulResponseCodeException, PushNetworkException
  {
    return makeServiceRequest(urlFragment, method, body, headers, NO_HANDLER, unidentifiedAccessKey, serviceClients);
  }

  // clients
  private Response getServiceConnection(String urlFragment, String method, String body, Map<String, String> headers, Optional<UnidentifiedAccess> unidentifiedAccess, ConnectionHolder[]  clients)
          throws PushNetworkException
  {
    try {
      ConnectionHolder connectionHolder        = getRandom(clients, random); // no support for unidentifiedclient see https://github.com/signalapp/libsignal-service-java/commit/0674df19c5314e8a649dea11986e1dd0c066db8c
//      OkHttpClient            baseClient       = unidentifiedAccess.isPresent() ? connectionHolder.getUnidentifiedClient() : connectionHolder.getClient();
      OkHttpClient            baseClient       = connectionHolder.getClient(); //
      OkHttpClient            okHttpClient     = baseClient.newBuilder()
              .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
              .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
              .build();

      //
      // Add Stetho interceptor
      //okHttpClient.networkInterceptors().add(new StethoInterceptor());
      //

      Request.Builder request = new Request.Builder();
      request.url(String.format("%s%s", connectionHolder.getUrl(), urlFragment).replaceAll("\\+", "%2B"));

      if (body != null) {
        if (!TextUtils.isEmpty(body)) request.method(method, RequestBody.create(MediaType.parse("application/json"), body));
        else  request.method(method, RequestBody.create(MediaType.parse("text/plain"), body));
      } else {
        request.method(method, null);
      }

      for (Map.Entry<String, String> header : headers.entrySet()) {
        request.addHeader(header.getKey(), header.getValue());
      }

//      if (unidentifiedAccess.isPresent()) { // false todo: unidentified access http request header turned off
//        request.addHeader("Unidentified-Access-Key", Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
//      }

      if (credentialsProvider.getPassword() != null) {
        request.addHeader("Authorization", getAuthorizationHeader(credentialsProvider));
      }

      if (userAgent != null) {
        request.addHeader("X-Ufsrv-Agent", userAgent);
      }

      if (connectionHolder.getHostHeader().isPresent()) {
        request.addHeader("Host", connectionHolder.getHostHeader().get());
      }

      if (!TextUtils.isEmpty(TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))) {
        request.addHeader("X-Ufsrv-Cookie", TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance())); //
      }

      Call call = okHttpClient.newCall(request.build());

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

  private Response getServiceConnection(String urlFragment, String method, String body, Optional<UnidentifiedAccess> unidentifiedAccess)
      throws PushNetworkException
  {
    return getServiceConnection(urlFragment, method, body, NO_HEADERS, unidentifiedAccess, serviceClients);
  }

  private Response makeContactDiscoveryRequest(String authorization, List<String> cookies, String path, String method, String body)
          throws PushNetworkException, NonSuccessfulResponseCodeException
  {
    ConnectionHolder connectionHolder = getRandom(contactDiscoveryClients, random);
    OkHttpClient     okHttpClient     = connectionHolder.getClient()
            .newBuilder()
            .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .build();

    Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + path);

    if (body != null) {
      request.method(method, RequestBody.create(MediaType.parse("application/json"), body));
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
        throw new AuthorizationFailedException("Authorization failed!");
      case 409:
        throw new RemoteAttestationResponseExpiredException("Remote attestation response expired");
      case 429:
        throw new RateLimitException("Rate limit exceeded: " + response.code());
    }

    throw new NonSuccessfulResponseCodeException("Response: " + response);
  }

  private ServiceConnectionHolder[] createServiceConnectionHolders(SignalUrl[] urls) {
    List<ServiceConnectionHolder> serviceConnectionHolders = new LinkedList<>();

    for (SignalUrl url : urls) {
      serviceConnectionHolders.add(new ServiceConnectionHolder(createConnectionClient(url),
                                                               createConnectionClient(url),
                                                               url.getUrl(), url.getHostHeader()));
    }

    return serviceConnectionHolders.toArray(new ServiceConnectionHolder[0]);
  }

  private ConnectionHolder[] createConnectionHolders(SignalUrl[] urls) {
    List<ConnectionHolder> connectionHolders = new LinkedList<>();

    for (SignalUrl url : urls) {
      connectionHolders.add(new ConnectionHolder(createConnectionClient(url), url.getUrl(), url.getHostHeader()));
    }

    return connectionHolders.toArray(new ConnectionHolder[0]);
  }

  private OkHttpClient createConnectionClient(SignalUrl url) {
    try {
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(url.getTrustStore());

      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagers, null);

      return new OkHttpClient.Builder()
              .sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), (X509TrustManager)trustManagers[0])
              .connectionSpecs(url.getConnectionSpecs().or(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
              .build();
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
      return "Basic " + Base64.encodeBytes((credentialsProvider.getUser() + ":" + credentialsProvider.getPassword()).getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private ConnectionHolder getRandom(ConnectionHolder[] connections, SecureRandom random) {
    return connections[random.nextInt(connections.length)];
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

  private static class RegistrationLock {
    @JsonProperty
    private String pin;

    public RegistrationLock() {}

    public RegistrationLock(String pin) {
      this.pin = pin;
    }
  }

  private static class RegistrationLockFailure {
    @JsonProperty
    private int length;

    @JsonProperty
    private long timeRemaining;
  }

  private static class AttachmentDescriptor {
    @JsonProperty
    private long id;

    // replacement for id above
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

  //
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

  static final public String getUfsrvSyncCommand ()
  {
    return UF_SYNC;
  }

  private interface ResponseCodeHandler {
    void handle(int responseCode) throws NonSuccessfulResponseCodeException, PushNetworkException;
  }

  private static class EmptyResponseCodeHandler implements ResponseCodeHandler {
    @Override
    public void handle(int responseCode) { }
  }
}
