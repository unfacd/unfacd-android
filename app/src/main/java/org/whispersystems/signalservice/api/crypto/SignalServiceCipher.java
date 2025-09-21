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
package org.whispersystems.signalservice.api.crypto;

import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SealedSessionCipher;
import org.signal.libsignal.metadata.SealedSessionCipher.DecryptionResult;
import org.signal.libsignal.metadata.SelfSendException;
import org.signal.libsignal.metadata.certificate.CertificateValidator;
import org.signal.libsignal.metadata.certificate.SenderCertificate;
import org.signal.libsignal.metadata.protocol.UnidentifiedSenderMessageContent;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidRegistrationIdException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PlaintextContent;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import java.util.Optional;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore;
import org.whispersystems.signalservice.api.SignalSessionLock;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceMetadata;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.DistributionId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage;
import org.whispersystems.signalservice.internal.push.PushTransportDetails;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope.Type;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;
import org.whispersystems.signalservice.internal.serialize.SignalServiceAddressProtobufSerializer;
import org.whispersystems.signalservice.internal.serialize.SignalServiceMetadataProtobufSerializer;
import org.whispersystems.signalservice.internal.serialize.protos.SignalServiceContentProto;
import org.whispersystems.signalservice.internal.util.Base64;

import java.util.Collections;
import java.util.List;


/**
 * This is used to encrypt + decrypt received {@link SignalServiceEnvelope}s.
 */
public class SignalServiceCipher {

  private static final String TAG = Log.tag(SignalServiceCipher.class);

  private final SignalServiceAccountDataStore signalProtocolStore;
  private final SignalSessionLock             sessionLock;
  private final SignalServiceAddress          localAddress;
  private final int                           localDeviceId;
  private final CertificateValidator          certificateValidator;

  public SignalServiceCipher(SignalServiceAddress localAddress,
                             int localDeviceId,
                             SignalServiceAccountDataStore signalProtocolStore,
                             SignalSessionLock sessionLock,
                             CertificateValidator certificateValidator)
  {
    this.signalProtocolStore  = signalProtocolStore;
    this.sessionLock          = sessionLock;
    this.localAddress         = localAddress;
    this.localDeviceId        = localDeviceId;
    this.certificateValidator = certificateValidator;
  }

  public byte[] encryptForGroup(
          DistributionId distributionId,
          List<SignalProtocolAddress> destinations,
          SenderCertificate senderCertificate,
          byte[] unpaddedMessage,
          ContentHint contentHint,
          Optional<byte[]> groupId)
          throws NoSessionException, UntrustedIdentityException, InvalidKeyException, InvalidRegistrationIdException
  {
    PushTransportDetails             transport            = new PushTransportDetails();
    SignalProtocolAddress            localProtocolAddress = new SignalProtocolAddress(localAddress.getIdentifier(), localDeviceId);
    SignalGroupCipher                groupCipher          = new SignalGroupCipher(sessionLock, new GroupCipher(signalProtocolStore, localProtocolAddress));
    SignalSealedSessionCipher        sessionCipher        = new SignalSealedSessionCipher(sessionLock, new SealedSessionCipher(signalProtocolStore, localAddress.getServiceId().uuid(), localAddress.getNumber().orElse(null), localDeviceId));
    CiphertextMessage                message              = groupCipher.encrypt(distributionId.asUuid(), transport.getPaddedMessageBody(unpaddedMessage));
    UnidentifiedSenderMessageContent messageContent       = new UnidentifiedSenderMessageContent(message,
                                                                                                 senderCertificate,
                                                                                                 contentHint.getType(),
                                                                                                 groupId);

    return sessionCipher.multiRecipientEncrypt(destinations, messageContent);
  }

  public OutgoingPushMessage encrypt(SignalProtocolAddress        destination,
                                     Optional<UnidentifiedAccess> unidentifiedAccess,
                                     EnvelopeContent              content)
          throws UntrustedIdentityException, InvalidKeyException
  {
    if (unidentifiedAccess.isPresent()) {
      SignalSessionCipher       sessionCipher        = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, destination));
      SignalSealedSessionCipher sealedSessionCipher  = new SignalSealedSessionCipher(sessionLock, new SealedSessionCipher(signalProtocolStore, localAddress.getServiceId().uuid(), localAddress.getNumber().orElse(null), localDeviceId));

      return content.processSealedSender(sessionCipher, sealedSessionCipher, destination, unidentifiedAccess.get().getUnidentifiedCertificate());
    } else {
      SignalSessionCipher sessionCipher = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, destination));

      return content.processUnsealedSender(sessionCipher, destination);
    }
  }
//

 public OutgoingPushMessage encrypt(SignalProtocolAddress destination,
                                    Optional<UnidentifiedAccess> unidentifiedAccess,
                                    EnvelopeContent content,
                                    UfsrvCommand ufCommand)
         throws UntrustedIdentityException, InvalidKeyException
 {
    if (ufCommand.isE2ee()) {
      if (unidentifiedAccess.isPresent()) {
        SignalSessionCipher       sessionCipher        = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, destination));
        SignalSealedSessionCipher sealedSessionCipher  = new SignalSealedSessionCipher(sessionLock, new SealedSessionCipher(signalProtocolStore, localAddress.getServiceId().uuid(), localAddress.getNumber().orElse(null), localDeviceId));

        return content.processSealedSender(sessionCipher, sealedSessionCipher, destination, unidentifiedAccess.get().getUnidentifiedCertificate());
      } else {
        SignalSessionCipher sessionCipher = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, destination));

        return content.processUnsealedSender(sessionCipher, destination);
      }
    } else {//AA+ branch
      String body = Base64.encodeBytes(content.getUnpaddedMessage());
      return new OutgoingPushMessage(Type.UNKNOWN_VALUE, destination.getDeviceId(), 0/*remoteRegistrationId*/, body);
    }
  }

  /**
   * Decrypt a received {@link SignalServiceEnvelope}
   *
   * @param envelope The received SignalServiceEnvelope
   *
   * @return a decrypted SignalServiceContent
   */
  public SignalServiceContent decrypt(SignalServiceEnvelope envelope)
          throws InvalidMetadataMessageException, InvalidMetadataVersionException,
          ProtocolInvalidKeyIdException, ProtocolLegacyMessageException,
          ProtocolUntrustedIdentityException, ProtocolNoSessionException,
          ProtocolInvalidVersionException, ProtocolInvalidMessageException,
          ProtocolInvalidKeyException, ProtocolDuplicateMessageException,
          SelfSendException, UnsupportedDataMessageException, InvalidMessageStructureException
  {
    try {
      if (envelope.hasLegacyMessage()) {
        Plaintext plaintext = decrypt(envelope, envelope.getLegacyMessage(), false); //AA++ false
        SignalServiceProtos.DataMessage dataMessage = SignalServiceProtos.DataMessage.parseFrom(plaintext.getData());
        SignalServiceContentProto contentProto;

        if (envelope.isUfsrvMessage()) {
          Log.d(TAG, String.format("decrypt: server response path -> FOUND UFSRV MESSAGE WITH LEGACY CONTENT: NOT DECRYPTING"));
          contentProto = SignalServiceContentProto.newBuilder()
                                                  .setLocalAddress(SignalServiceAddressProtobufSerializer.toProtobuf(localAddress))
                                                  .setMetadata(SignalServiceMetadataProtobufSerializer.toProtobuf(plaintext.metadata))
                                                  .setLegacyDataMessage(dataMessage)
                                                  .setEnvelope(envelope.getEnvelope())//AA+
                                                  .build();
          return SignalServiceContent.createFromProto(contentProto, envelope);
//          return new SignalServiceContent(createSignalServiceMessage(plaintext.getMetadata(), dataMessage, envelope), null, 0, 0, false);//AA+ todo: fix undefine args
        } else {
          Log.d(TAG, String.format("decrypt: original path -> FOUND NON-UFSRV MESSAGE WITH LEGACY CONTENT"));
          contentProto = SignalServiceContentProto.newBuilder()
                                                  .setLocalAddress(SignalServiceAddressProtobufSerializer.toProtobuf(localAddress))
                                                  .setMetadata(SignalServiceMetadataProtobufSerializer.toProtobuf(plaintext.getMetadata()))
                                                  .setLegacyDataMessage(dataMessage)
                                                  .setEnvelope(envelope.getEnvelope())//AA+
                                                  .build();
        }

        return SignalServiceContent.createFromProto(contentProto, envelope);
      } else if (envelope.hasContent()) {
        Plaintext                   plaintext = decrypt(envelope, envelope.getContent(), false);
        SignalServiceProtos.Content content   = SignalServiceProtos.Content.parseFrom(plaintext.getData());

        SignalServiceContentProto contentProto = SignalServiceContentProto.newBuilder()
                                                                          .setLocalAddress(SignalServiceAddressProtobufSerializer.toProtobuf(localAddress))
                                                                          .setMetadata(SignalServiceMetadataProtobufSerializer.toProtobuf(plaintext.metadata))
                                                                          .setContent(content)
                                                                          .setEnvelope(envelope.getEnvelope())//AA+
                                                                          .build();

        return SignalServiceContent.createFromProto(contentProto, envelope);
      } else {
        //AA+ this ufrsv Direct own push ie not in reaction to this client's previous command
        Log.d(TAG, String.format("decrypt: FOUND NATIVE UFSRV PUSH MESSAGE..."));
        Plaintext                   plaintext = decrypt(envelope, envelope.getLegacyMessage(), false);
        SignalServiceProtos.DataMessage dataMessage = SignalServiceProtos.DataMessage.parseFrom(plaintext.getData());
        SignalServiceContentProto contentProto = SignalServiceContentProto.newBuilder()
                                                                          .setLocalAddress(SignalServiceAddressProtobufSerializer.toProtobuf(localAddress))
                                                                          .setMetadata(SignalServiceMetadataProtobufSerializer.toProtobuf(plaintext.metadata))
                                                                          .setLegacyDataMessage(dataMessage)
                                                                          .setEnvelope(envelope.getEnvelope())//AA+
                                                                          .build();
        return SignalServiceContent.createFromProto(contentProto, envelope);
//        return new SignalServiceContent(createSignalServiceMessage(envelope, null),null, 0, 0, isNeedsreceipt);//AA+ todo: fix undefined args inline with presealed version
      }
      //

    } catch (InvalidProtocolBufferException e) {
      throw new InvalidMetadataMessageException(e);
    }
  }

  /**
   * Decrypt a received {@link SignalServiceEnvelope}
   *
   * @param envelope The received SignalServiceEnvelope
   *
   * @return a decrypted SignalServiceContent
   * @throws InvalidVersionException
   *    * @throws InvalidMessageException
   *    * @throws InvalidKeyException
   *    * @throws DuplicateMessageException
   *    * @throws InvalidKeyIdException
   *    * @throws UntrustedIdentityException
   *    * @throws LegacyMessageException
   *    * @throws NoSessionException
   */

 private Plaintext decrypt(SignalServiceEnvelope envelope, byte[] ciphertext, boolean decrypt) //AA+ decrypt
          throws InvalidMetadataMessageException, InvalidMetadataVersionException,
          ProtocolDuplicateMessageException, ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException, ProtocolInvalidKeyException,
          ProtocolInvalidVersionException, ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException, ProtocolNoSessionException,
         SelfSendException, InvalidMessageStructureException
  {
    if (decrypt) { //AA+
      try {
        byte[] paddedMessage;
        SignalServiceMetadata metadata;

        if (!envelope.hasSource() && !envelope.isUnidentifiedSender()) {
          throw new InvalidMessageStructureException("Non-UD envelope is missing a source!");
        }

        if (envelope.isPreKeySignalMessage()) {
          SignalProtocolAddress sourceAddress = getPreferredProtocolAddress(signalProtocolStore, envelope.getSourceAddress(), envelope.getSourceDevice());
//          SignalProtocolAddress sourceAddress = new SignalProtocolAddress(envelope.getSourceUuid().get(), envelope.getSourceDevice());
          SignalSessionCipher   sessionCipher = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, sourceAddress));

          paddedMessage = sessionCipher.decrypt(new PreKeySignalMessage(ciphertext));
          metadata = new SignalServiceMetadata(envelope.getSourceAddress(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getServerDeliveredTimestamp(), false, envelope.getServerGuid(), Optional.empty());

          signalProtocolStore.clearSenderKeySharedWith(Collections.singleton(sourceAddress));
        }
        else if (envelope.isSignalMessage()) {
          SignalProtocolAddress sourceAddress = getPreferredProtocolAddress(signalProtocolStore, envelope.getSourceAddress(), envelope.getSourceDevice());
          SignalSessionCipher   sessionCipher = new SignalSessionCipher(sessionLock, new SessionCipher(signalProtocolStore, sourceAddress));

          paddedMessage = sessionCipher.decrypt(new SignalMessage(ciphertext));
          metadata      = new SignalServiceMetadata(envelope.getSourceAddress(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getServerDeliveredTimestamp(), false, envelope.getServerGuid(), Optional.empty());
        }
        else if (envelope.isPlaintextContent()) {
          paddedMessage = new PlaintextContent(ciphertext).getBody();
          metadata      = new SignalServiceMetadata(envelope.getSourceAddress(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getServerDeliveredTimestamp(), false, envelope.getServerGuid(), Optional.empty());
        }
        else if (envelope.isUnidentifiedSender()) {
          SignalSealedSessionCipher sealedSessionCipher = new SignalSealedSessionCipher(sessionLock, new SealedSessionCipher(signalProtocolStore, localAddress.getServiceId().uuid(), localAddress.getNumber().orElse(null), localDeviceId));
          DecryptionResult          result = sealedSessionCipher.decrypt(certificateValidator, ciphertext, envelope.getServerReceivedTimestamp());
          SignalServiceAddress      resultAddress = new SignalServiceAddress(ACI.parseOrThrow(result.getSenderUuid()), result.getSenderE164());
          Optional<byte[]>          groupId = result.getGroupId();
          boolean                   needsReceipt        = true;

          if (envelope.hasSourceUuid()) {
            Log.w(TAG, "[" + envelope.getTimestamp() + "] Received a UD-encrypted message sent over an identified channel. Marking as needsReceipt=false");
            needsReceipt = false;
          }

          if (result.getCiphertextMessageType() == CiphertextMessage.PREKEY_TYPE) {
            signalProtocolStore.clearSenderKeySharedWith(Collections.singleton(new SignalProtocolAddress(result.getSenderUuid(), result.getDeviceId())));
          }

          paddedMessage = result.getPaddedMessage();
          metadata      = new SignalServiceMetadata(resultAddress, result.getDeviceId(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getServerDeliveredTimestamp(), needsReceipt, envelope.getServerGuid(), groupId);
        }
        else {
          throw new InvalidMetadataMessageException("Unknown type: " + envelope.getType());
        }

        PushTransportDetails transportDetails = new PushTransportDetails();
        byte[] data = transportDetails.getStrippedPaddingMessageBody(paddedMessage);

        return new Plaintext(metadata, data);

      }
      catch (DuplicateMessageException e) {
        throw new ProtocolDuplicateMessageException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (LegacyMessageException e) {
        throw new ProtocolLegacyMessageException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (InvalidMessageException e) {
        throw new ProtocolInvalidMessageException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (InvalidKeyIdException e) {
        throw new ProtocolInvalidKeyIdException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (InvalidKeyException e) {
        throw new ProtocolInvalidKeyException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (UntrustedIdentityException e) {
        throw new ProtocolUntrustedIdentityException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (InvalidVersionException e) {
        throw new ProtocolInvalidVersionException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
      catch (NoSessionException e) {
        throw new ProtocolNoSessionException(e, envelope.getSourceIdentifier(), envelope.getSourceDevice());
      }
    }

    //AA+
    Log.d(TAG, ">>decrypt: Not decrypting...");
    boolean isNeedsreceipt = envelope.getUfsrvType() == UfsrvCommand.Type.MESSAGE.ordinal();
    SignalServiceMetadata metadata = new SignalServiceMetadata(envelope.getSourceAddress(), envelope.getSourceDevice(), envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getServerDeliveredTimestamp(), isNeedsreceipt, envelope.getServerGuid(), Optional.empty());
    return new Plaintext(metadata, ciphertext);
    //
  }

  //AA was deleted in https://github.com/signalapp/Signal-Android/commit/642d1984c441e8d54567a746c8eddeee5e7cca79
  private static SignalProtocolAddress getPreferredProtocolAddress(SignalProtocolStore store, SignalServiceAddress address, int sourceDevice) {
    SignalProtocolAddress uuidAddress =  new SignalProtocolAddress(address.getServiceId().toString(), sourceDevice);
    SignalProtocolAddress e164Address = address.getNumber().isPresent() ? new SignalProtocolAddress(address.getNumber().get(), sourceDevice) : null;

    if (uuidAddress != null && store.containsSession(uuidAddress)) {
      return uuidAddress;
    } else if (e164Address != null && store.containsSession(e164Address)) {
      return e164Address;
    } else {
      return new SignalProtocolAddress(address.getIdentifier(), sourceDevice);
    }
  }

  private static class Plaintext {
    private final SignalServiceMetadata metadata;
    private final byte[]   data;

    private Plaintext(SignalServiceMetadata metadata, byte[] data) {
      this.metadata = metadata;
      this.data     = data;
    }

    public SignalServiceMetadata getMetadata() {
      return metadata;
    }

    public byte[] getData() {
      return data;
    }
  }
}

