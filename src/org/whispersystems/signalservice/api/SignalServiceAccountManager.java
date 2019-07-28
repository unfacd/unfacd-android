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
package org.whispersystems.signalservice.api;


import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;
import com.unfacd.android.data.json.JsonEntityPresenceInformation;
import com.unfacd.android.data.json.JsonEntityVerifiedAccount;
import com.unfacd.android.data.model.UserIdList;
import com.unfacd.android.location.JsonEntityLocation;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.ProfileCipher;
import org.whispersystems.signalservice.api.crypto.InvalidCiphertextException;
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream;
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo;
import org.whispersystems.signalservice.api.messages.multidevice.DeviceInfo;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.StreamDetails;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.contacts.crypto.ContactDiscoveryCipher;
import org.whispersystems.signalservice.internal.contacts.crypto.Quote;
import org.whispersystems.signalservice.internal.contacts.crypto.RemoteAttestation;
import org.whispersystems.signalservice.internal.contacts.crypto.RemoteAttestationKeys;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedQuoteException;
import org.whispersystems.signalservice.internal.contacts.crypto.UnauthenticatedResponseException;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryRequest;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryResponse;
import org.whispersystems.signalservice.internal.contacts.entities.RemoteAttestationRequest;
import org.whispersystems.signalservice.internal.contacts.entities.RemoteAttestationResponse;
import org.whispersystems.signalservice.internal.crypto.ProvisioningCipher;
import org.whispersystems.signalservice.internal.push.ProfileAvatarData;
import org.whispersystems.signalservice.internal.push.AccountAttributes;
import org.whispersystems.signalservice.internal.push.PreKeyEntity;
import org.whispersystems.signalservice.internal.push.PreKeyState;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;
import org.whispersystems.signalservice.internal.util.Base64;

import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionMessage;

/**
 * The main interface for creating, registering, and
 * managing a Signal Service account.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAccountManager
{

  private final PushServiceSocket pushServiceSocket;
  private final String            user;
  private final String            userAgent;

  private static final String TAG = SignalServiceAccountManager.class.getSimpleName();

  /**
   * Construct a SignalServiceAccountManager.
   *
   * @param configuration The URL for the Signal Service.
   * @param user A Signal Service phone number.
   * @param password A Signal Service password.
   * @param userAgent A string which identifies the client software.
   */
  public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                     String user, String password,
                                     String userAgent)
  {
    this(configuration, new StaticCredentialsProvider(user, password, null), userAgent);
  }

  public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                     CredentialsProvider credentialsProvider,
                                     String userAgent)
  {
    this.pushServiceSocket = new PushServiceSocket(configuration, credentialsProvider, userAgent);
    this.user              = credentialsProvider.getUser();
    this.userAgent         = userAgent;
  }

  public byte[] getSenderCertificate() throws IOException {
    return this.pushServiceSocket.getSenderCertificate();
  }

  public void setPin(Optional<String> pin) throws IOException {
    if (pin.isPresent()) {
        this.pushServiceSocket.setPin(pin.get());
      } else {
        this.pushServiceSocket.removePin();
      }
  }

  /**
   * //
   * get the valueof the UFSRV user account
   * if null it means user needs to relogin to acquire a fresh cookie.
   */
  public String getCookie ()
  {
    return this.pushServiceSocket.getCookie();
  }

  /**
   * Register/Unregister a Google Cloud Messaging registration ID.
   *
   * @param gcmRegistrationId The GCM id to register.  A call with an absent value will unregister.
   * @throws IOException
   */
  public void setGcmId(Optional<String> gcmRegistrationId) throws IOException {

    Log.d(TAG, ">>>setGcmId: id: '" + gcmRegistrationId + "'...");

    if (gcmRegistrationId.isPresent()) {
      this.pushServiceSocket.registerGcmId(gcmRegistrationId.get());
    } else {
      this.pushServiceSocket.unregisterGcmId();
    }

  }

  /**
   * //
   *
   * @param jsonPayload The GCM id to register.  A call with an absent value will unregister.
   * @throws IOException
   */
  public String setPreferences(Optional<String> jsonPayload) throws IOException {

    if (jsonPayload.isPresent()) {
      return this.pushServiceSocket.storeGroupPreferences(jsonPayload.get());
    }

    return null;
  }

  /**
   * //
   * Set user preferences
   *
   * @param jsonPayload User prefrences
   * @throws IOException
   */
  public String setUserPreferences(Optional<String> jsonPayload) throws IOException {

    if (jsonPayload.isPresent()) {
      return this.pushServiceSocket.storeGroupPreferences(jsonPayload.get());
    }

    return null;
  }


  /**
   * //
   *
   *
   * @param fid The fence id for which stickiness is being set
   * @param on_off the desired new setting 1' or '0'
   * @throws IOException
   */
  public String setStickyGeogroupPreference(long fid, int on_off) throws IOException {

    return this.pushServiceSocket.storeStickyGeogroupPreference(fid, on_off);

  }

  public String setTogglableUserPreference(String prefName, int on_off) throws IOException {

    return this.pushServiceSocket.storeTogglableUserPreference(prefName, on_off);

  }

  /**
   * Request a push challenge. A number will be pushed to the GCM (FCM) id. This can then be used
   * during SMS/call requests to bypass the CAPTCHA.
   *
   * @param gcmRegistrationId The GCM (FCM) id to use.
   * @param e164number        The number to associate it with.
   * @throws IOException
   */
  public void requestPushChallenge(String gcmRegistrationId, String e164number) throws IOException {
    this.pushServiceSocket.requestPushChallenge(gcmRegistrationId, e164number);
  }

  /**
   * Request an SMS verification code.  On success, the server will send
   * an SMS verification code to this Signal user.
   * @param androidSmsRetrieverSupported
   * @param captchaToken                 If the user has done a CAPTCHA, include this.
   * @param challenge                    If present, it can bypass the CAPTCHA.
   * @throws IOException
   *
   */
  public String requestSmsVerificationCode(boolean androidSmsRetrieverSupported, Optional<String> captchaToken, Optional<String> challenge, Optional<String> e164Number) throws IOException { //
    return (this.pushServiceSocket.createAccountUfsrv(false, androidSmsRetrieverSupported, null, captchaToken, challenge, e164Number));
  }

  /**
   * Request a Voice verification code.  On success, the server will
   * make a voice call to this Signal user.
   * @param locale
   * @param captchaToken If the user has done a CAPTCHA, include this.
   * @param challenge    If present, it can bypass the CAPTCHA.
   * @throws IOException
   */
  public void requestVoiceVerificationCode(String regoCookie, Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
    //this.pushServiceSocket.createAccountUfsrv(true);
    this.pushServiceSocket.voiceVerifyAccountUfsrv(regoCookie, locale, captchaToken, challenge);
  }

  /**
   * Verify a Signal Service account with a received SMS or voice verification code.
   *
   * @param verificationCode The verification code received via SMS or Voice
   *                         (see {@link #requestSmsVerificationCode} and
   *                         {@link #requestVoiceVerificationCode}).
   * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key,
   *                     concatenated.
   * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
   *                              This value should remain consistent across registrations for the
   *                              same install, but probabilistically differ across registrations
   *                              for separate installs.
   * @throws IOException
   *
   */
  // changed return from void to String. nickname holds 1164number
  public JsonEntityVerifiedAccount verifyAccountWithCode(String verificationCode, String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages, String pin, byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess, String pendingCookie, String username, String e164number,  byte[] profileKey)
      throws IOException
  {
    AccountAttributes signalingKeyEntity = new AccountAttributes(signalingKey, signalProtocolRegistrationId, fetchesMessages, pin, verificationCode, unidentifiedAccessKey, unrestrictedUnidentifiedAccess, pendingCookie, username, e164number, profileKey);
    String jsonString=JsonUtil.toJson(signalingKeyEntity);

    Log.d(TAG, ">>> verifyAccountWithCode: sending json: '" + jsonString + "'");

    return (this.pushServiceSocket.verifyAccountCodeUfsrv(verificationCode, signalingKey,
            signalProtocolRegistrationId, fetchesMessages, pin, unidentifiedAccessKey, unrestrictedUnidentifiedAccess, pendingCookie, username, e164number, profileKey)); // extra args
  }


  /**
   * Refresh account attributes with server.
   *
   * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key, concatenated.
   * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
   *                              This value should remain consistent across registrations for the same
   *                              install, but probabilistically differ across registrations for
   *                              separate installs.
   *
   * @throws IOException
   */
  public void setAccountAttributes(String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages, String pin, byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess)
      throws IOException
  {
    this.pushServiceSocket.setAccountAttributes(signalingKey, signalProtocolRegistrationId, fetchesMessages, pin, unidentifiedAccessKey, unrestrictedUnidentifiedAccess);
  }

  //
  /**
   * Register an identity key, last resort key, signed prekey, and list of one time prekeys
   * with the server.
   *
   * @param identityKey The client's long-term identity keypair.
   * @param signedPreKey The client's signed prekey.
   * @param oneTimePreKeys The client's list of one-time prekeys.
   *
   * @throws IOException
   */
  public void setPreKeys(IdentityKey identityKey,
                         SignedPreKeyRecord signedPreKey, List<PreKeyRecord> oneTimePreKeys)
      throws IOException
  {
    String jsonString;

      List<PreKeyEntity> entities = new LinkedList<>();

      for (PreKeyRecord record : oneTimePreKeys) {
        PreKeyEntity entity = new PreKeyEntity(record.getId(),
                record.getKeyPair().getPublicKey());

        entities.add(entity);
      }

      SignedPreKeyEntity signedPreKeyEntity = new SignedPreKeyEntity(signedPreKey.getId(),
              signedPreKey.getKeyPair().getPublicKey(),
              signedPreKey.getSignature());


    jsonString=JsonUtil.toJson(new PreKeyState(entities, signedPreKeyEntity, identityKey));

    Log.d(TAG, ">>> setPreKeys: sending json: '"+jsonString+"'");


    this.pushServiceSocket.registerPreKeys(jsonString);
  }

  /**
   * @return The server's count of currently available (eg. unused) prekeys for this user.
   * @throws IOException
   */
  public int getPreKeysCount() throws IOException {
    return this.pushServiceSocket.getAvailablePreKeys();
  }

  /**
   * Set the client's signed prekey.
   *
   * @param signedPreKey The client's new signed prekey.
   * @throws IOException
   */
  public void setSignedPreKey(SignedPreKeyRecord signedPreKey) throws IOException {
    this.pushServiceSocket.setCurrentSignedPreKey(signedPreKey);
  }

  /**
   * @return The server's view of the client's current signed prekey.
   * @throws IOException
   */
  public SignedPreKeyEntity getSignedPreKey() throws IOException {
    return this.pushServiceSocket.getCurrentSignedPreKey();
  }

  /**
   * Checks whether a contact is currently registered with the server.
   *
   * @param e164number The contact to check.
   * @return An optional ContactTokenDetails, present if registered, absent if not.
   * @throws IOException
   */
  public Optional<ContactTokenDetails> getContact(String e164number) throws IOException {
    String              contactToken        = createDirectoryServerToken(e164number, true);
    Log.d(TAG, ">>> getContact(): Constructed SHA-1 token: "+ contactToken+" for e164 number: "+e164number);
    ContactTokenDetails contactTokenDetails = this.pushServiceSocket.getContactTokenDetails(contactToken);

    if (contactTokenDetails != null) {
      contactTokenDetails.setUsername(e164number);
    }

    return Optional.fromNullable(contactTokenDetails);
  }

  /**
   * Checks whether a contact is currently registered with the server based on userid.
   *
   * @param ufsrvUid The contact to check.
   * @return An optional ContactTokenDetails, present if registered, absent if not.
   * @throws IOException
   */
  public Optional<ContactTokenDetails> getContact(UfsrvUid ufsrvUid) throws IOException {
    ContactTokenDetails contactTokenDetails = this.pushServiceSocket.getContactTokenDetails(ufsrvUid);

//    if (contactTokenDetails != null) {
//      contactTokenDetails.setNumber(e164number);
//    }

    return Optional.fromNullable(contactTokenDetails);
  }

  public List<ContactTokenDetails> getContacts(Set<String> e164numbers)
          throws IOException
  {

    Map<String, String>       contactTokensMap = createDirectoryServerTokenMap(e164numbers);
    List<ContactTokenDetails> activeTokens     = this.pushServiceSocket.retrieveDirectory(contactTokensMap.keySet());

    for (ContactTokenDetails activeToken : activeTokens) {
      activeToken.setUsername(contactTokensMap.get(activeToken.getToken())); // get the telephone number associated with this toke from the number-token map created above. The server only returns tokens, so we need to find the corresponding number in this local map
    }

    return activeTokens;

  }

  public List<String> getRegisteredUsers(KeyStore iasKeyStore, Set<String> e164numbers, String mrenclave)
          throws IOException, Quote.InvalidQuoteFormatException, UnauthenticatedQuoteException, SignatureException, UnauthenticatedResponseException
  {
    try {
      String            authorization = this.pushServiceSocket.getContactDiscoveryAuthorization();
      Curve25519        curve         = Curve25519.getInstance(Curve25519.BEST);
      Curve25519KeyPair keyPair       = curve.generateKeyPair();

      ContactDiscoveryCipher                        cipher              = new ContactDiscoveryCipher();
      RemoteAttestationRequest                      attestationRequest  = new RemoteAttestationRequest(keyPair.getPublicKey());
      Pair<RemoteAttestationResponse, List<String>> attestationResponse = this.pushServiceSocket.getContactDiscoveryRemoteAttestation(authorization, attestationRequest, mrenclave);

      RemoteAttestationKeys keys      = new RemoteAttestationKeys(keyPair, attestationResponse.first().getServerEphemeralPublic(), attestationResponse.first().getServerStaticPublic());
      Quote                 quote     = new Quote(attestationResponse.first().getQuote());
      byte[]                requestId = cipher.getRequestId(keys, attestationResponse.first());

      cipher.verifyServerQuote(quote, attestationResponse.first().getServerStaticPublic(), mrenclave);
      cipher.verifyIasSignature(iasKeyStore, attestationResponse.first().getCertificates(), attestationResponse.first().getSignatureBody(), attestationResponse.first().getSignature(), quote);

      RemoteAttestation remoteAttestation = new RemoteAttestation(requestId, keys);
      List<String>      addressBook       = new LinkedList<>();

      for (String e164number : e164numbers) {
        addressBook.add(e164number.substring(1));
      }

      DiscoveryRequest  request  = cipher.createDiscoveryRequest(addressBook, remoteAttestation);
      DiscoveryResponse response = this.pushServiceSocket.getContactDiscoveryRegisteredUsers(authorization, request, attestationResponse.second(), mrenclave);
      byte[]            data     = cipher.getDiscoveryResponseData(response, remoteAttestation);

      Iterator<String> addressBookIterator = addressBook.iterator();
      List<String>     results             = new LinkedList<>();

      for (byte aData : data) {
        String candidate = addressBookIterator.next();

        if (aData != 0) results.add('+' + candidate);
      }

      return results;
    } catch (InvalidCiphertextException e) {
      throw new UnauthenticatedResponseException(e);
    }
  }

  public void reportContactDiscoveryServiceMatch() {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceMatch();
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery result match failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceMismatch() {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceMismatch();
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery result mismatch failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceAttestationError(String reason) {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceAttestationError(reason);
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery attestation error failed. Ignoring.", e);
    }
  }

  public void reportContactDiscoveryServiceUnexpectedError(String reason) {
    try {
      this.pushServiceSocket.reportContactDiscoveryServiceUnexpectedError(reason);
    } catch (IOException e) {
      Log.w(TAG, "Request to indicate a contact discovery unexpected error failed. Ignoring.", e);
    }
  }

  public String getNewDeviceVerificationCode() throws IOException {
    return this.pushServiceSocket.getNewDeviceVerificationCode();
  }

  public void addDevice(String deviceIdentifier,
                        ECPublicKey deviceKey,
                        IdentityKeyPair identityKeyPair,
                        Optional<byte[]> profileKey,
                        String code)
          throws InvalidKeyException, IOException
  {
    ProvisioningCipher       cipher  = new ProvisioningCipher(deviceKey);
    ProvisionMessage.Builder message = ProvisionMessage.newBuilder()
            .setIdentityKeyPublic(ByteString.copyFrom(identityKeyPair.getPublicKey().serialize()))
            .setIdentityKeyPrivate(ByteString.copyFrom(identityKeyPair.getPrivateKey().serialize()))
            .setNumber(user)
            .setProvisioningCode(code);

    if (profileKey.isPresent()) {
      message.setProfileKey(ByteString.copyFrom(profileKey.get()));
    }

    byte[] ciphertext = cipher.encrypt(message.build());
    this.pushServiceSocket.sendProvisioningMessage(deviceIdentifier, ciphertext);
  }

  public List<DeviceInfo> getDevices() throws IOException {
    return this.pushServiceSocket.getDevices();
  }

  public void removeDevice(long deviceId) throws IOException {
    this.pushServiceSocket.removeDevice(deviceId);
  }

  public TurnServerInfo getTurnServerInfo() throws IOException {
    return this.pushServiceSocket.getTurnServerInfo();
  }

  public void setProfileName(byte[] key, String name)
          throws IOException
  {
    if (name == null) name = "";

    String ciphertextName = Base64.encodeBytesWithoutPadding(new ProfileCipher(key).encryptName(name.getBytes("UTF-8"), ProfileCipher.NAME_PADDED_LENGTH));

    this.pushServiceSocket.setProfileName(ciphertextName);
  }

  public void setProfileAvatar(byte[] key, StreamDetails avatar)
          throws IOException
  {
    ProfileAvatarData profileAvatarData = null;

    if (avatar != null) {
      profileAvatarData = new ProfileAvatarData(avatar.getStream(),
                                                ProfileCipherOutputStream.getCiphertextLength(avatar.getLength()),
                                                avatar.getContentType(),
                                                new ProfileCipherOutputStreamFactory(key));
    }

    this.pushServiceSocket.setProfileAvatar(profileAvatarData);
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    this.pushServiceSocket.setSoTimeoutMillis(soTimeoutMillis);
  }

  public void cancelInFlightRequests() {
    this.pushServiceSocket.cancelInFlightRequests();
  }

  private String createDirectoryServerToken(String e164number, boolean urlSafe) {
    try {
      MessageDigest digest  = MessageDigest.getInstance("SHA1");
      byte[]        token   = digest.digest(e164number.getBytes());
      String        encoded = android.util.Base64.encodeToString(token, android.util.Base64.NO_PADDING|android.util.Base64.NO_WRAP);

      if (urlSafe) return (encoded.replace('+', '-').replace('/', '_')).substring(0, 14);
      else         return encoded.substring(0, 14);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private Map<String, String> createDirectoryServerTokenMap(Collection<String> e164numbers) {
    Map<String,String> tokenMap = new HashMap<>(e164numbers.size());

    for (String number : e164numbers) {
      //tokenMap.put(createDirectoryServerToken(number, false), number);
      tokenMap.put(createDirectoryServerToken(number, true), number); // always urls safe to cut back on processing on the backend
    }

    return tokenMap;
  }

  //
  /**
   * Request for users' presence information is formatted as json array 'userIds:[]'
   * @param userIdList
   * @return json array
   * @throws IOException
   */
  public List<JsonEntityPresenceInformation> getPresenceUpdate(UserIdList userIdList)
          throws IOException
  {
    List<JsonEntityPresenceInformation> presenceSharingList  = this.pushServiceSocket.retrievePresenceInformation(JsonUtil.toJson(userIdList));

    return presenceSharingList;
  }

  public Optional<String>  isRegistrationStatusVerified (String registrationCookie)
  {
    return this.pushServiceSocket.isRegistrationStatusVerified(registrationCookie);
  }

  public void sendLocation (SignalServiceMessagePipe pipe, ufLocation ufLoc)
          throws IOException
  {
    if (pipe!=null && pipe.isConnected())
    {
      JsonEntityLocation myLocation = new JsonEntityLocation(ufLoc);
      String jsonString = JsonUtil.toJson(myLocation);

      pipe.sendWebSocketMessage(WebSocketProtos.WebSocketMessage.newBuilder()
              .setType(WebSocketProtos.WebSocketMessage.Type.REQUEST)
              .setCommand(PushServiceSocket.UFSRVCMD_LOCATION)
              .setRequest(WebSocketProtos.WebSocketRequestMessage.newBuilder()
                      .setId(System.currentTimeMillis())
                      .setVerb(jsonString)
                      .build()).build());
    }
    else {
      Log.d(TAG, "sendLocation: PIPE NOT CONNECTED...");
    }
  }
}
