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

import com.unfacd.android.data.json.JsonEntityProfile;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.util.Base64;
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream;
import org.whispersystems.signalservice.api.crypto.ProfileCipherInputStream;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment.ProgressListener;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest;
import org.whispersystems.signalservice.api.profiles.ProfileAndCredential;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceMessagesResult;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.sticker.StickerProtos;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.util.concurrent.FutureTransformers;
import org.whispersystems.signalservice.internal.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The primary interface for receiving Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceMessageReceiver {

  private final PushServiceSocket socket;

  private static final String TAG = Log.tag(SignalServiceMessageReceiver.class);

  /**
   * Construct a SignalServiceMessageReceiver.
   *
   * @param urls The URL of the Signal Service.
   *                   the server's TLS signing certificate.
   * @param credentials The Signal Service user's credentials.
   */
  public SignalServiceMessageReceiver(SignalServiceConfiguration  urls,
                                      CredentialsProvider credentials,
                                      String signalAgent,
                                      ClientZkProfileOperations clientZkProfileOperations,
                                      boolean automaticNetworkRetry)
  {
    this.socket = new PushServiceSocket(urls, credentials, signalAgent, clientZkProfileOperations, automaticNetworkRetry);

  }

  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment. If this file exists, it is
   *                    assumed that this is previously-downloaded content that can be resumed.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, long maxSizeBytes)
          throws IOException, InvalidMessageException, MissingConfigurationException {
    return retrieveAttachment(pointer, destination, maxSizeBytes, null);
  }

  public ListenableFuture<ProfileAndCredential> retrieveProfile(SignalServiceAddress address,
                                                                Optional<ProfileKey> profileKey,
                                                                Optional<UnidentifiedAccess> unidentifiedAccess,
                                                                SignalServiceProfile.RequestType requestType,
                                                                Locale locale)
  {
    ServiceId serviceId = address.getServiceId();

    if (profileKey.isPresent()) {
      if (requestType == SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL) {
        return socket.retrieveVersionedProfileAndCredential(serviceId.uuid(), profileKey.get(), unidentifiedAccess, locale);
      } else {
        return FutureTransformers.map(socket.retrieveVersionedProfile(serviceId.uuid(), profileKey.get(), unidentifiedAccess, locale), profile -> {
          return new ProfileAndCredential(profile,
                                          SignalServiceProfile.RequestType.PROFILE,
                                          Optional.empty());
        });
      }
    } else {
      return FutureTransformers.map(socket.retrieveProfile(address, unidentifiedAccess, locale), profile -> {
        return new ProfileAndCredential(profile,
                                        SignalServiceProfile.RequestType.PROFILE,
                                        Optional.empty());
      });
    }
  }

  public SignalServiceProfile retrieveProfileByUsername(String username, Optional<UnidentifiedAccess> unidentifiedAccess, Locale locale)
          throws IOException
  {
    return socket.retrieveProfileByUsername(username, unidentifiedAccess, locale);
  }

  public InputStream retrieveProfileAvatar(String path, File destination, ProfileKey profileKey, long maxSizeBytes)
          throws IOException
  {
    socket.retrieveProfileAvatar(path, destination, maxSizeBytes);
    return new ProfileCipherInputStream(new FileInputStream(destination), profileKey);
  }

  public FileInputStream retrieveGroupsV2ProfileAvatar(String path, File destination, long maxSizeBytes)
          throws IOException
  {
    socket.retrieveProfileAvatar(path, destination, maxSizeBytes);
    return new FileInputStream(destination);
  }

  //AA++
  public InputStream retrieveProfileAvatarUfsrv(String path, File destination, ProfileKey profileKey, int maxSizeBytes)
          throws IOException
  {
    socket.retrieveProfileAvatarUfsrv(path, destination, maxSizeBytes);
    return new ProfileCipherInputStream(new FileInputStream(destination), profileKey);
  }

  //AA+
  public byte[] retrieveAttachmentBytes(String ufId, long maxSizeBytes, ProgressListener listener)
          throws IOException
  {
    return socket.retrieveAttachmentBytes(ufId, maxSizeBytes, listener);
    //todo profile attachments need to be decrypted as per  return AttachmentCipherInputStream.createForAttachment(destination, pointer.getSize().orElse(0), pointer.getKey(), pointer.getDigest().get());
  }

  public JsonEntityProfile getUfsrvProfile(UfsrvUid ufsrvUid)
          throws IOException, VerificationFailedException
  {
      return socket.getUfsrvProfile(ufsrvUid);
  }

  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment. If this file exists, it is
   *                    assumed that this is previously-downloaded content that can be resumed.
   * @param listener An optional listener (may be null) to receive callbacks on download progress.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, long maxSizeBytes, ProgressListener listener)
          throws IOException, InvalidMessageException, MissingConfigurationException {
    if (!pointer.getDigest().isPresent()) throw new InvalidMessageException("No attachment digest!");

    Log.d(TAG, String.format("retrieveAttachment: retrieving attachment ufId: '%s'... key:'%s'", pointer.getUfId(), Base64.encodeBytes(pointer.getKey())));
    socket.retrieveAttachmentUfsrv(pointer.getUfId(), destination, maxSizeBytes, listener);//AA+
    return AttachmentCipherInputStream.createForAttachment(destination, pointer.getSize().orElse(0), pointer.getKey(), pointer.getDigest().get());
    //return AttachmentCipherInputStream.createFor(destination, pointer.getSize().orElse(0), pointer.getKey(), pointer.getDigest().get());
  }

  public InputStream retrieveSticker(byte[] packId, byte[] packKey, int stickerId)
          throws IOException, InvalidMessageException
  {
    byte[] data = socket.retrieveSticker(packId, stickerId);
    return AttachmentCipherInputStream.createForStickerData(data, packKey);
  }

  /**
   * Retrieves a {@link SignalServiceStickerManifest}.
   *
   * @param packId The 16-byte packId that identifies the sticker pack.
   * @param packKey The 32-byte packKey that decrypts the sticker pack.
   * @return The {@link SignalServiceStickerManifest} representing the sticker pack.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public SignalServiceStickerManifest retrieveStickerManifest(byte[] packId, byte[] packKey)
          throws IOException, InvalidMessageException
  {
    byte[] manifestBytes = socket.retrieveStickerManifest(packId);

    InputStream           cipherStream = AttachmentCipherInputStream.createForStickerData(manifestBytes, packKey);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    Util.copy(cipherStream, outputStream);

    StickerProtos.Pack                             pack     = StickerProtos.Pack.parseFrom(outputStream.toByteArray());
    List<SignalServiceStickerManifest.StickerInfo> stickers = new ArrayList<>(pack.getStickersCount());
    SignalServiceStickerManifest.StickerInfo       cover    = pack.hasCover() ? new SignalServiceStickerManifest.StickerInfo(pack.getCover().getId(), pack.getCover().getEmoji(), pack.getCover().getContentType())
                                                                              : null;

    for (StickerProtos.Pack.Sticker sticker : pack.getStickersList()) {
      stickers.add(new SignalServiceStickerManifest.StickerInfo(sticker.getId(), sticker.getEmoji(), sticker.getContentType()));
    }

    return new SignalServiceStickerManifest(pack.getTitle(), pack.getAuthor(), cover, stickers);
  }

  public List<SignalServiceEnvelope> retrieveMessages() throws IOException {
    return retrieveMessages(new NullMessageReceivedCallback());
  }

  //AA+
  public long deleteEnqueuedMessages()
          throws IOException
  {
    Log.d(TAG, "deleteEnqueuedMessages: Deleting server queued messages via API...");
    socket.deleteQueuedMessages();//server return number of deleted messages
    return 0;
  }

  //AA+ IMPORTANT >> modified to work directly off SignalServiceProtos.Envelope as opposed to SignalServiceEnvelopeEntity
  public List<SignalServiceEnvelope> retrieveMessages(MessageReceivedCallback callback)
          throws IOException
  {
    Log.d(TAG, "retrieveMessages:Retrieving server queued messages via API...");
    List<SignalServiceEnvelope>        results       = new LinkedList<>();
    SignalServiceMessagesResult result = socket.getMessages(); //AA+ SignalServiceProtos.Envelope
    List<SignalServiceProtos.Envelope> entities = result.getEnvelopesRaw();

    for (SignalServiceProtos.Envelope entity : entities) {
      SignalServiceEnvelope envelope =  new SignalServiceEnvelope(entity, result.getServerDeliveredTimestamp()); //AA+ replaces commented block below

      //AA- added as per https://github.com/signalapp/libsignal-service-java/commit/d4c746aeff17d8f9550fec557b4e968a4d4dc8c6?diff=split#diff-8c6ca45f1a3a2f4f010d673ec6f7a4b2
//       SignalServiceEnvelope envelope;
//
//      if (entity.hasSource() && entity.getSourceDevice() > 0) {
//        SignalServiceAddress address = new SignalServiceAddress(ServiceId.parseOrThrow(entity.getSourceUuid()), entity.getSourceE164());
//        envelope = new SignalServiceEnvelope(entity.getType(),
//                                             Optional.of(address),
//                                             entity.getSourceDevice(),
//                                             entity.getTimestamp(),
//                                             entity.getMessage(),
//                                             entity.getContent(),
//                                             entity.getServerTimestamp(),
//                                             messageResult.getServerDeliveredTimestamp(),
//                                             entity.getServerUuid());
//      } else {
//        envelope = new SignalServiceEnvelope(entity.getType(),
//                                             entity.getTimestamp(),
//                                             entity.getMessage(),
//                                             entity.getContent(),
//                                             entity.getServerTimestamp(),
//                                             messageResult.getServerDeliveredTimestamp(),
//                                             entity.getServerUuid());
//      }

      callback.onMessage(envelope);
      results.add(envelope);

      Log.d(TAG, String.format("retrieveMessages: !Retrieved '%d' queued messages...", results.size()));

      if (envelope.getCommandHeader().hasGid()) {
       try {
         socket.acknowledgeMessage(envelope.getCommandHeader().getGid(), envelope.getCommandHeader().getFid());
       } catch (NonSuccessfulResponseCodeException x) {
         //AA this is to prevent RestStrategy from issuing ApplicationDependencies.resetSignalServiceMessageReceiver()
       }
      }

      //AA-
      /*if (envelope.hasServerGuid()) {
        socket.acknowledgeMessage(envelope.getServerGuid());
      } else {
        socket.acknowledgeMessage(entity.getSourceE164(), entity.getTimestamp());
      }*/
    }
    return results;
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    socket.setSoTimeoutMillis(soTimeoutMillis);
  }

  public interface MessageReceivedCallback {
    public void onMessage(SignalServiceEnvelope envelope);
  }

  public static class NullMessageReceivedCallback implements MessageReceivedCallback {
    @Override
    public void onMessage(SignalServiceEnvelope envelope) {}
  }

}
