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
package org.whispersystems.signalservice.api.messages;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.util.OptionalUtil;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.serialize.protos.SignalServiceEnvelopeProto;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.Hex;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;


/**
 * This class represents an encrypted Signal Service envelope.
 *
 * The envelope contains the wrapping information, such as the sender, the
 * message timestamp, the encrypted message type, etc.
 *
  * @author  Moxie Marlinspike
 */

public class SignalServiceEnvelope {

  private static final String TAG = Log.tag(SignalServiceEnvelope.class);

  private static final int SUPPORTED_VERSION =  1;
  private static final int CIPHER_KEY_SIZE   = 32;
  private static final int MAC_KEY_SIZE      = 20;
  private static final int MAC_SIZE          = 10;

  private static final int VERSION_OFFSET    =  0;
  private static final int VERSION_LENGTH    =  1;
  private static final int IV_OFFSET         = VERSION_OFFSET + VERSION_LENGTH;
  private static final int IV_LENGTH         = 16;
  private static final int CIPHERTEXT_OFFSET = IV_OFFSET + IV_LENGTH;

  private final Envelope envelope;//AA this a protobuf type
  private final long     serverDeliveredTimestamp;

  /**
   * Construct an envelope from a serialized, Base64 encoded SignalServiceEnvelope, encrypted
   * with a signaling key.
   *
   * @param message The serialized SignalServiceEnvelope, base64 encoded and encrypted.
   * @param signalingKey The signaling key.
   * @throws IOException
   * @throws InvalidVersionException
   */
  public SignalServiceEnvelope(String message,
                               String signalingKey,
                               boolean isSignalingKeyEncrypted,
                               long serverDeliveredTimestamp)
      throws IOException, InvalidVersionException
  {
    this(Base64.decode(message), signalingKey, isSignalingKeyEncrypted, serverDeliveredTimestamp);
  }

  /**
   * Construct an envelope from a serialized SignalServiceEnvelope, encrypted with a signaling key.
   *
   * @param input The serialized and (optionally) encrypted SignalServiceEnvelope.
   * @param signalingKey The signaling key.
   * @throws InvalidVersionException
   * @throws IOException
   */
  public SignalServiceEnvelope(byte[] input,
                               String signalingKey,
                               boolean isSignalingKeyEncrypted,
                               long serverDeliveredTimestamp)
      throws InvalidVersionException, IOException
  {
    if (!isSignalingKeyEncrypted) {
      this.envelope = Envelope.parseFrom(input);
    } else {
      if (input.length < VERSION_LENGTH || input[VERSION_OFFSET] != SUPPORTED_VERSION) {
        throw new InvalidVersionException("Unsupported version!");
      }

      SecretKeySpec cipherKey = getCipherKey(signalingKey);
      SecretKeySpec macKey    = getMacKey(signalingKey);

      verifyMac(input, macKey);

      this.envelope = Envelope.parseFrom(getPlaintext(input, cipherKey));
    }

    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
  }

  //AA+
  //the message is not  encrypted message from the server end so we omit the key altogether. temporarily replaces
  //public SignalServiceEnvelope(byte[] ciphertext, String signalingKey) above
  public SignalServiceEnvelope(byte[] cleartext, long serverDeliveredTimestamp)
          throws IOException
  {
    if (cleartext == null)  Log.w(TAG, "SignalServiceEnvelope.constructor: raw envelope input was null");
    this.envelope = Envelope.parseFrom(cleartext);

    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
  }

  //AA+
  //the message is not  encrypted message from the server end so we omit the key altogether. temporarily replaces
  //
  public SignalServiceEnvelope(Envelope base, long serverDeliveredTimestamp)
  {
    this.envelope                 = base;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
  }

  //AA+  support for the ufsrv columns. this is inflated from values saved in the db
  public SignalServiceEnvelope(int type,
                               Optional<SignalServiceAddress> sender,
                               int senderDevice,
                               long timestamp,
                               byte[] legacyMessage,
                               byte[] content,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               String serverGuid,
                               byte[] ufsrvMessage,//AA+
                               int ufsrvType) {
    Envelope.Builder builder = Envelope.newBuilder()
            .setType(Envelope.Type.valueOf(type))
            .setSourceDevice(senderDevice)
            .setTimestamp(timestamp)
            .setServerTimestamp(serverReceivedTimestamp);

    if (sender.isPresent()) {
      builder.setSourceUuid(sender.get().getServiceId().toString());

      if (sender.get().getNumber().isPresent()) {
        builder.setSourceE164(sender.get().getNumber().get());
        builder.setSourceUfsrvUid(sender.get().getNumber().get());//AA+ ufsrv
      }
    }

    if (serverGuid != null) {
      builder.setServerGuid(serverGuid);
    }

    if (legacyMessage != null) builder.setLegacyMessage(ByteString.copyFrom(legacyMessage));
    if (content != null)       builder.setContent(ByteString.copyFrom(content));


    //AA+ MAIN UFSRV CONSTRUCTION block
    //this works for both websocket messages as well as api call via SignalServiceMessageReceiver.retrieveMessages
    if (ufsrvMessage != null) {
      Envelope envelopeTemp = null;

      //inflate Envelope
      try {
        Envelope.Builder builderUfsrv = builder.mergeFrom(ufsrvMessage);
        envelopeTemp = builderUfsrv.build();
      } catch (InvalidProtocolBufferException ex) {
        Log.d(TAG, ex.getMessage());
      }

      try {
        UfsrvCommandWire ufsrvCommandWire = envelopeTemp.getUfsrvCommand();
        builder.setUfsrvCommand(ufsrvCommandWire);
      } catch (NullPointerException e) {
        Log.d(TAG, e.getMessage());
      }
    } else {
      Log.w(TAG, "CONSTRUCTOR: RECEIVED EMPTY UFSRV COMMAND MESSAGE");
    }
    //end of ufsrv block

    this.envelope                 = builder.build();
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
  }

  public SignalServiceEnvelope(int type,
                               Optional<SignalServiceAddress> sender,
                               int senderDevice,
                               long timestamp,
                               byte[] legacyMessage,
                               byte[] content,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               String serverGuid,
                               UfsrvCommandWire ufsrvCommandWire)//AA+
                               {
    Envelope.Builder builder = Envelope.newBuilder()
                                       .setType(Envelope.Type.valueOf(type))
                                       .setSourceDevice(senderDevice)
                                       .setTimestamp(timestamp)
                                       .setServerTimestamp(serverReceivedTimestamp);

    if (sender.isPresent()) {
      if (sender.get().getServiceId() != null) builder.setSourceUuid(sender.get().getServiceId().toString());//AA+ conditional
      if (sender.get().getNumber().isPresent()) {
        builder.setSourceE164(sender.get().getNumber().get());
        builder.setSourceUfsrvUid(sender.get().getNumber().get());//AA+ ufsrv
      }
    }

    if (serverGuid != null) {
      builder.setServerGuid(serverGuid);
    }

    if (legacyMessage != null) builder.setLegacyMessage(ByteString.copyFrom(legacyMessage));
    if (content != null)       builder.setContent(ByteString.copyFrom(content));


    //AA+ MAIN UFSRV CONSTRUCTION block
    if (ufsrvCommandWire != null) {
        builder.setUfsrvCommand(ufsrvCommandWire);
    } else {
      Log.w(TAG, "CONSTRUCTOR: RECEIVED EMPTY UFSRV COMMAND MESSAGE");
    }
    //end of ufsrv block

    this.envelope                 = builder.build();
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
  }

  public String getServerGuid() {
    return envelope.getServerGuid();
  }

  public boolean hasServerGuid() {
    return envelope.hasServerGuid();
  }

  public boolean hasSource() {
    return envelope.hasSourceUfsrvUid() || envelope.hasSourceE164() || envelope.hasSourceUuid(); //AA+ ufsrvuid
  }

  public boolean hasSourceUuid() {
    return envelope.hasSourceUuid();
  }

  //AA+
  public Envelope getEnvelope() {
    return envelope;
  }

  //AA+
  public int getUfsrvType()
  {
    return envelope.getUfsrvCommand().getUfsrvtype().getNumber();
  }

  public FenceCommand getFenceCommand()
  {
    return envelope.getUfsrvCommand().getFenceCommand();
  }

  public MessageCommand getMessageCommand()
  {
    return envelope.getUfsrvCommand().getMsgCommand();
  }

  public UserCommand getUserCommand()
  {
    return envelope.getUfsrvCommand().getUserCommand();
  }

  public SignalServiceProtos.CommandHeader getCommandHeader()
  {
    return envelope.getUfsrvCommand().getHeader();
  }

  public UfsrvCommandWire getUfsrvCommand()
  {
    return envelope.getUfsrvCommand();
  }

  //this is dumb and needs to to be refactored
  public boolean isUfsrvMessage() {
    return (envelope.getUfsrvCommand() != null);

  }

  public String toB64String()
  {
    return org.thoughtcrime.securesms.util.Base64.encodeBytes(this.envelope.toByteArray());
  }

  public boolean hasUfsrvCommand()
  {
    return envelope.hasUfsrvCommand();
  }
  //

  /**
   * @return The envelope's sender as an E164 number.
   */
  public Optional<String> getSourceE164() {
    return Optional.ofNullable(envelope.getSourceUfsrvUid()); //AA+ ufsrvuid
  }

  /**
   * @return The envelope's sender as a ufsrvUid.
   */
  public Optional<String> getSourceUfsrvUid() {
    return Optional.ofNullable(envelope.getSourceUfsrvUid());
  }

  /**
   * @return The envelope's sender as a UUID.
   */
  public Optional<String> getSourceUuid() {
    if (getSourceE164().isPresent() && getSourceE164().get().equals("")) {//AA+ ufsrv doesn't set uuid for server ge
      return  Optional.ofNullable(UuidUtil.UNKNOWN_UUID.toString());
    }
    return Optional.ofNullable(envelope.getSourceUuid());
  }

  public String getSourceIdentifier() {
    return OptionalUtil.or(getSourceUfsrvUid(), getSourceUuid(), getSourceE164()).orElse(null);//AA+ ufsrv
  }

  public boolean hasSourceDevice() {
    return envelope.hasSourceDevice();
  }

  /**
   * @return The envelope's sender device ID.
   */
  public int getSourceDevice() {
    return envelope.getSourceDevice();
  }

  /**
   * @return The envelope's sender as a SignalServiceAddress.
   */
  public SignalServiceAddress getSourceAddress() {
    return new SignalServiceAddress(ACI.parseOrNull(envelope.getSourceUuid()), envelope.getSourceUfsrvUid().equals("0") ? UfsrvUid.UndefinedUfsrvUid : envelope.getSourceUfsrvUid());//AA+ ufsrvuid
  }

  /**
   * @return The envelope content type.
   */
  public int getType() {
    return envelope.getType().getNumber();
  }

  /**
   * @return The timestamp this envelope was sent.
   */
  public long getTimestamp() {
    return envelope.getTimestamp();
  }

  /**
   * @return The server timestamp of when the server received the envelope.
   */
  public long getServerReceivedTimestamp() {
    return envelope.getServerTimestamp();
  }

  /**
   * @return The server timestamp of when the envelope was delivered to us.
   */
  public long getServerDeliveredTimestamp() {
    return serverDeliveredTimestamp;
  }

  /**
   * @return Whether the envelope contains a SignalServiceDataMessage
   */
  public boolean hasLegacyMessage() {
    return envelope.hasLegacyMessage();
  }

  /**
   * @return The envelope's containing SignalService message.
   */
  public byte[] getLegacyMessage() {
    return envelope.getLegacyMessage().toByteArray();
  }

  /**
   * @return Whether the envelope contains an encrypted SignalServiceContent
   */
  public boolean hasContent() {
    return envelope.hasContent();
  }

  /**
   * @return The envelope's encrypted SignalServiceContent.
   */
  public byte[] getContent() {
    return envelope.getContent().toByteArray();
  }

  /**
    * @return true if the containing message is a {@link org.signal.libsignal.protocol.message.SignalMessage}
   */
  public boolean isSignalMessage() {
    return envelope.getType().getNumber() == Envelope.Type.CIPHERTEXT_VALUE;
  }

  /**
   * @return true if the containing message is a {@link org.signal.libsignal.protocol.message.PreKeySignalMessage}
   */
  public boolean isPreKeySignalMessage() {
    return envelope.getType().getNumber() == Envelope.Type.PREKEY_BUNDLE_VALUE;
  }

  /**
   * @return true if the containing message is a delivery receipt.
   */
  public boolean isReceipt() {
    return envelope.getType().getNumber() == Envelope.Type.RECEIPT_VALUE;
  }

  public boolean isUnidentifiedSender() {
    return envelope.getType().getNumber() == Envelope.Type.UNIDENTIFIED_SENDER_VALUE;
  }

  public boolean isPlaintextContent() {
    return envelope.getType().getNumber() == Envelope.Type.PLAINTEXT_CONTENT_VALUE;
  }

  public byte[] serialize() {
    SignalServiceEnvelopeProto.Builder builder = SignalServiceEnvelopeProto.newBuilder()
            .setType(getType())
            .setDeviceId(getSourceDevice())
            .setTimestamp(getTimestamp())
            .setServerReceivedTimestamp(getServerReceivedTimestamp())
            .setServerDeliveredTimestamp(getServerDeliveredTimestamp());

    if (getSourceUuid().isPresent()) {
      builder.setSourceUuid(getSourceUuid().get());
    }

    if (getSourceE164().isPresent()) {
      builder.setSourceE164(getSourceE164().get());
    }

    if (hasLegacyMessage()) {
      builder.setLegacyMessage(ByteString.copyFrom(getLegacyMessage()));
    }

    if (hasContent()) {
      builder.setContent(ByteString.copyFrom(getContent()));
    }

    if (hasServerGuid()) {
      builder.setServerGuid(getServerGuid());
    }

    //AA+
    if (hasUfsrvCommand()) {
      builder.setUfsrvCommand(getUfsrvCommand());
    }

    return builder.build().toByteArray();
  }

  public static SignalServiceEnvelope deserialize(byte[] serialized) {
    SignalServiceEnvelopeProto proto = null;
    try {
      proto = SignalServiceEnvelopeProto.parseFrom(serialized);
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
    }

    return new SignalServiceEnvelope(proto.getType(),
                                     SignalServiceAddress.fromRaw(proto.getSourceE164().equals("0") ? UuidUtil.UNKNOWN_UUID.toString() : proto.getSourceUuid(), proto.getSourceE164().equals("0") ? UfsrvUid.UndefinedUfsrvUid : proto.getSourceE164()),//AA+ extra conditional to fill out missing uuid for server inbound
                                     proto.getDeviceId(),
                                     proto.getTimestamp(),
                                     proto.hasLegacyMessage() ? proto.getLegacyMessage().toByteArray() : null,
                                     proto.hasContent() ? proto.getContent().toByteArray() : null,
                                     proto.getServerReceivedTimestamp(),
                                     proto.getServerDeliveredTimestamp(),
                                     proto.getServerGuid(),
                                     proto.getUfsrvCommand());//AA+
  }

  private byte[] getPlaintext(byte[] ciphertext, SecretKeySpec cipherKey) throws IOException {
    try {
      byte[] ivBytes = new byte[IV_LENGTH];
      System.arraycopy(ciphertext, IV_OFFSET, ivBytes, 0, ivBytes.length);
      IvParameterSpec iv = new IvParameterSpec(ivBytes);

      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, cipherKey, iv);

      return cipher.doFinal(ciphertext, CIPHERTEXT_OFFSET,
                            ciphertext.length - VERSION_LENGTH - IV_LENGTH - MAC_SIZE);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException e) {
      throw new AssertionError(e);
    } catch (BadPaddingException e) {
      Log.w(TAG, e);
      throw new IOException("Bad padding?");
    }
  }

  private void verifyMac(byte[] ciphertext, SecretKeySpec macKey) throws IOException {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(macKey);

      if (ciphertext.length < MAC_SIZE + 1)
        throw new IOException("Invalid MAC!");

      mac.update(ciphertext, 0, ciphertext.length - MAC_SIZE);

      byte[] ourMacFull  = mac.doFinal();
      byte[] ourMacBytes = new byte[MAC_SIZE];
      System.arraycopy(ourMacFull, 0, ourMacBytes, 0, ourMacBytes.length);

      byte[] theirMacBytes = new byte[MAC_SIZE];
      System.arraycopy(ciphertext, ciphertext.length-MAC_SIZE, theirMacBytes, 0, theirMacBytes.length);

      Log.w(TAG, "Our MAC: " + Hex.toString(ourMacBytes));
      Log.w(TAG, "Thr MAC: " + Hex.toString(theirMacBytes));

      if (!Arrays.equals(ourMacBytes, theirMacBytes)) {
        throw new IOException("Invalid MAC compare!");
      }
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }


  private SecretKeySpec getCipherKey(String signalingKey) throws IOException {
    byte[] signalingKeyBytes = Base64.decode(signalingKey);
    byte[] cipherKey         = new byte[CIPHER_KEY_SIZE];
    System.arraycopy(signalingKeyBytes, 0, cipherKey, 0, cipherKey.length);

    return new SecretKeySpec(cipherKey, "AES");
  }


  private SecretKeySpec getMacKey(String signalingKey) throws IOException {
    byte[] signalingKeyBytes = Base64.decode(signalingKey);
    byte[] macKey            = new byte[MAC_KEY_SIZE];
    System.arraycopy(signalingKeyBytes, CIPHER_KEY_SIZE, macKey, 0, macKey.length);

    return new SecretKeySpec(macKey, "HmacSHA256");
  }

}
