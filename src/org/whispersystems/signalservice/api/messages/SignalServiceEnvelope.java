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

import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.Hex;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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

  private static final String TAG = SignalServiceEnvelope.class.getSimpleName();

  private static final int SUPPORTED_VERSION =  1;
  private static final int CIPHER_KEY_SIZE   = 32;
  private static final int MAC_KEY_SIZE      = 20;
  private static final int MAC_SIZE          = 10;

  private static final int VERSION_OFFSET    =  0;
  private static final int VERSION_LENGTH    =  1;
  private static final int IV_OFFSET         = VERSION_OFFSET + VERSION_LENGTH;
  private static final int IV_LENGTH         = 16;
  private static final int CIPHERTEXT_OFFSET = IV_OFFSET + IV_LENGTH;

  private final Envelope envelope;// proto

  /**
   * Construct an envelope from a serialized, Base64 encoded SignalServiceEnvelope, encrypted
   * with a signaling key.
   *
   * @param message The serialized SignalServiceEnvelope, base64 encoded and encrypted.
   * @param signalingKey The signaling key.
   * @throws IOException
   * @throws InvalidVersionException
   */
  public SignalServiceEnvelope(String message, String signalingKey, boolean isSignalingKeyEncrypted)
      throws IOException, InvalidVersionException
  {
    this(Base64.decode(message), signalingKey, isSignalingKeyEncrypted);
  }

  /**
   * Construct an envelope from a serialized SignalServiceEnvelope, encrypted with a signaling key.
   *
   * @param input The serialized and (optionally) encrypted SignalServiceEnvelope.
   * @param signalingKey The signaling key.
   * @throws InvalidVersionException
   * @throws IOException
   */
  public SignalServiceEnvelope(byte[] input, String signalingKey, boolean isSignalingKeyEncrypted)
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
  }


  //
  //the message is not  encrypted message from the server end so we omit the key altogether. temporarily replaces
  //public SignalServiceEnvelope(byte[] ciphertext, String signalingKey) above
  public SignalServiceEnvelope(byte[] cleartext)
          throws InvalidVersionException, IOException
  {
    if (cleartext==null)  Log.w(TAG, "SignalServiceEnvelope.constructor: raw envelope input was null");
    this.envelope = Envelope.parseFrom(cleartext);
  }

  //
  //the message is not  encrypted message from the server end so we omit the key altogether. temporarily replaces
  //
  public SignalServiceEnvelope(Envelope base)
  {
    this.envelope = base;
  }


  //  support for the ufsrv columns. this is inflated from values saved in the db
  public SignalServiceEnvelope(int type, String sender, int senderDevice, long timestamp, byte[] legacyMessage, byte[] content, long serverTimestamp, String uuid, byte[] ufsrvMessage, int ufsrvType) {
    Envelope.Builder builder = Envelope.newBuilder()
            .setType(Envelope.Type.valueOf(type))
            .setSource(sender)
            .setSourceDevice(senderDevice)
            .setServerTimestamp(serverTimestamp);

    if (legacyMessage != null) builder.setLegacyMessage(ByteString.copyFrom(legacyMessage));
    if (content != null)       builder.setContent(ByteString.copyFrom(content));


    // MAIN UFSRV CONSTRUCTION block
    //this works for both websocket messages as well as api call via SignalServiceMessageReceiver.retrieveMessages
    if (ufsrvMessage!=null)
    {
      Envelope envelopeTemp = null;

      //inflate Envelope
      try {
        Envelope.Builder builderUfsrv = builder.mergeFrom(ufsrvMessage);
        envelopeTemp = builderUfsrv.build();
      }
      catch (InvalidProtocolBufferException ex) {
        Log.d(TAG, ex.getMessage());
      }

      try {
        SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire = envelopeTemp.getUfsrvCommand();
        builder.setUfsrvCommand(ufsrvCommandWire);
      } catch (NullPointerException e) {
        Log.d(TAG, e.getMessage());
      }
    }
    else
    {
      Log.w(TAG, "CONSTRUCTOR: RECEIVED EMPTY UFSRV COMMAND MESSAGE");
    }
    //end of ufsrv block

    this.envelope = builder.build();
  }

  public SignalServiceEnvelope(int type, long timestamp, byte[] legacyMessage, byte[] content, long serverTimestamp, String uuid) {
    Envelope.Builder builder = Envelope.newBuilder()
            .setType(Envelope.Type.valueOf(type))
            .setTimestamp(timestamp)
            .setServerTimestamp(serverTimestamp);

    if (uuid != null) {
      builder.setServerGuid(uuid);
    }

    if (legacyMessage != null) builder.setLegacyMessage(ByteString.copyFrom(legacyMessage));
    if (content != null)       builder.setContent(ByteString.copyFrom(content));

    this.envelope = builder.build();
  }

  public String getUuid() {
    return envelope.getServerGuid();
  }

  public boolean hasUuid() {
    return envelope.hasServerGuid();
  }

  public boolean hasSource() {
    return envelope.hasSource();
  }

  //
  public int getUfsrvType ()
  {
    return envelope.getUfsrvCommand().getUfsrvtype().getNumber();
  }

  public SignalServiceProtos.FenceCommand getFenceCommand()
  {
    return envelope.getUfsrvCommand().getFenceCommand();
  }

  public SignalServiceProtos.MessageCommand getMessageCommand()
  {
    return envelope.getUfsrvCommand().getMsgCommand();
  }

  public SignalServiceProtos.UserCommand getUserCommand()
  {
    return envelope.getUfsrvCommand().getUserCommand();
  }

  public SignalServiceProtos.CommandHeader getCommandHeader()
  {
    return envelope.getUfsrvCommand().getHeader();
  }

  public SignalServiceProtos.UfsrvCommandWire getUfsrvCommand()
  {
    return envelope.getUfsrvCommand();
  }

  //this is dumb and needs to to be refactored
  public boolean isUfsrvMessage() {
    return (envelope.getUfsrvCommand()!=null);

  }

  public String toB64String ()
  {
    return org.thoughtcrime.securesms.util.Base64.encodeBytes(this.envelope.toByteArray());
  }
  //


  /**
   * @return The envelope's sender.
   */
  public String getSource() {
    return envelope.getSource();
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
    return new SignalServiceAddress(envelope.getSource());
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

  public long getServerTimestamp() {
    return envelope.getServerTimestamp();
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
   * @return true if the containing message is a {@link org.whispersystems.libsignal.protocol.SignalMessage}
   */
  public boolean isSignalMessage() {
    return envelope.getType().getNumber() == Envelope.Type.CIPHERTEXT_VALUE;
  }

  /**
   * @return true if the containing message is a {@link org.whispersystems.libsignal.protocol.PreKeySignalMessage}
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
