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

import org.thoughtcrime.securesms.util.Base64;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream;
import org.whispersystems.signalservice.api.crypto.ProfileCipherInputStream;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment.ProgressListener;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceStickerManifest;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.SleepTimer;
import org.whispersystems.signalservice.api.websocket.ConnectivityListener;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.sticker.StickerProtos;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.websocket.WebSocketConnection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The primary interface for receiving Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SignalServiceMessageReceiver {

  private final PushServiceSocket           socket;
  private final SignalServiceConfiguration  urls;
  private final CredentialsProvider         credentialsProvider;
  private final String                      userAgent;
  private final ConnectivityListener       connectivityListener;
  private final SleepTimer                 sleepTimer;

  //A+ promoted to higher visibility
  private SignalServiceMessagePipe pipe;
  private Object lock=new Object();
  //
  private static final String TAG = SignalServiceMessageReceiver.class.getSimpleName();

  //
  public SignalServiceMessagePipe getPipe() {return this.pipe;}

  /**
   * Construct a SignalServiceMessageReceiver.
   *
   * @param urls The URL of the Signal Service.
   * @param user The Signal Service username (eg. phone number).
   * @param password The Signal Service user password.
   * @param signalingKey The 52 byte signaling key assigned to this user at registration.
   */
  public SignalServiceMessageReceiver(SignalServiceConfiguration urls,
                                      String user, String password,
                                      String signalingKey, String userAgent,
                                      ConnectivityListener listener,
                                      SleepTimer timer)
  {
    this(urls, new StaticCredentialsProvider(user, password, signalingKey), userAgent, listener, timer);
  }

  /**
   * Construct a SignalServiceMessageReceiver.
   *
   * @param urls The URL of the Signal Service.
   *                   the server's TLS signing certificate.
   * @param credentials The Signal Service user's credentials.
   */
  public SignalServiceMessageReceiver(SignalServiceConfiguration  urls,
                                      CredentialsProvider credentials,
                                      String userAgent,
                                      ConnectivityListener listener,
                                      SleepTimer timer)
  {
    this.urls                 = urls;
    this.credentialsProvider = credentials;
    this.socket              = new PushServiceSocket(urls, credentials, userAgent);
    this.userAgent           = userAgent;
    this.connectivityListener = listener;
    this.sleepTimer           = timer;

  }

  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, int maxSizeBytes)
      throws IOException, InvalidMessageException
  {
    return retrieveAttachment(pointer, destination, maxSizeBytes, null);
  }

  public SignalServiceProfile retrieveProfile(SignalServiceAddress address, Optional<UnidentifiedAccess> unidentifiedAccess) throws IOException
  {
    return socket.retrieveProfile(address, unidentifiedAccess);
  }

  public InputStream retrieveProfileAvatar(String path, File destination, byte[] profileKey, int maxSizeBytes)
   throws IOException
 {
    socket.retrieveProfileAvatar(path, destination, maxSizeBytes);
    return new ProfileCipherInputStream(new FileInputStream(destination), profileKey);
 }

  public InputStream retrieveProfileAvatarUfsrv(String path, File destination, byte[] profileKey, int maxSizeBytes)
          throws IOException
  {
    socket.retrieveProfileAvatarUfsrv(path, destination, maxSizeBytes);
    return new ProfileCipherInputStream(new FileInputStream(destination), profileKey);
  }
  /**
   * Retrieves a SignalServiceAttachment.
   *
   * @param pointer The {@link SignalServiceAttachmentPointer}
   *                received in a {@link SignalServiceDataMessage}.
   * @param destination The download destination for this attachment.
   * @param listener An optional listener (may be null) to receive callbacks on download progress.
   *
   * @return An InputStream that streams the plaintext attachment contents.
   * @throws IOException
   * @throws InvalidMessageException
   */
  public InputStream retrieveAttachment(SignalServiceAttachmentPointer pointer, File destination, int maxSizeBytes, ProgressListener listener)
      throws IOException, InvalidMessageException
  {
    if (!pointer.getDigest().isPresent()) throw new InvalidMessageException("No attachment digest!");

    Log.d(TAG, String.format("retrieveAttachment: retrieving attachment ufId: '%s'... key:'%s'", pointer.getUfId(), Base64.encodeBytes(pointer.getKey())));
    socket.retrieveAttachmentUfsrv(pointer.getId(), pointer.getUfId(), destination, maxSizeBytes, listener);//
    return AttachmentCipherInputStream.createForAttachment(destination, pointer.getSize().or(0), pointer.getKey(), pointer.getDigest().get());
    //return AttachmentCipherInputStream.createFor(destination, pointer.getSize().or(0), pointer.getKey(), pointer.getDigest().get());
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
    SignalServiceStickerManifest.StickerInfo       cover    = pack.hasCover() ? new SignalServiceStickerManifest.StickerInfo(pack.getCover().getId(), pack.getCover().getEmoji())
                                                                              : null;

    for (StickerProtos.Pack.Sticker sticker : pack.getStickersList()) {
      stickers.add(new SignalServiceStickerManifest.StickerInfo(sticker.getId(), sticker.getEmoji()));
    }

    return new SignalServiceStickerManifest(pack.getTitle(), pack.getAuthor(), cover, stickers);
  }

  /**
   * Creates a pipe for receiving SignalService messages.
   *
   * Callers must call {@link SignalServiceMessagePipe#shutdown()} when finished with the pipe.
   *
   * @return A SignalServiceMessagePipe for receiving Signal Service messages.
   */
  public SignalServiceMessagePipe createMessagePipe(AtomicBoolean callbackDone) {
    WebSocketConnection webSocket = new WebSocketConnection(urls.getSignalServiceUrls()[0].getUrl(),
                                                            urls.getSignalServiceUrls()[0].getTrustStore(),
                                                            Optional.of(credentialsProvider), userAgent, connectivityListener,
                                                            sleepTimer);
    this.pipe=new SignalServiceMessagePipe(webSocket, Optional.of(credentialsProvider), lock, callbackDone); // lock

    return this.pipe;

     //-
//    return new SignalServiceMessagePipe(webSocket, Optional.of(credentialsProvider));
  }

  public SignalServiceMessagePipe createUnidentifiedMessagePipe(AtomicBoolean callbackDone) {
    WebSocketConnection webSocket = new WebSocketConnection(urls.getSignalServiceUrls()[0].getUrl(),
                                                            urls.getSignalServiceUrls()[0].getTrustStore(),
                                                            Optional.<CredentialsProvider>absent(), userAgent, connectivityListener,
                                                            sleepTimer);

    return new SignalServiceMessagePipe(webSocket, Optional.of(credentialsProvider), lock, callbackDone);
  }

  public List<SignalServiceEnvelope> retrieveMessages() throws IOException {
    return retrieveMessages(new NullMessageReceivedCallback());
  }


  // modified to work directly off SignalServiceProtos.Envelope as opposed to SignalServiceEnvelopeEntity
  public List<SignalServiceEnvelope> retrieveMessages(MessageReceivedCallback callback)
          throws IOException
  {
    Log.d(TAG, "retrieveMessages:Retrieving message via API...");
    List<SignalServiceEnvelope>       results  = new LinkedList<>();
    List<SignalServiceProtos.Envelope> entities = socket.getMessages(); // SignalServiceProtos.Envelope

    for (SignalServiceProtos.Envelope entity : entities) {
      SignalServiceEnvelope envelope =  new SignalServiceEnvelope(entity); // replaces commented block below

      //- added as per https://github.com/signalapp/libsignal-service-java/commit/d4c746aeff17d8f9550fec557b4e968a4d4dc8c6?diff=split#diff-8c6ca45f1a3a2f4f010d673ec6f7a4b2
//      SignalServiceEnvelope envelope;
//
//      if (entity.getSource() != null && entity.getSourceDevice() > 0) {
//        envelope = new SignalServiceEnvelope(entity.getType(), entity.getSource(),
//                                             entity.getSourceDevice(), entity.getTimestamp(),
//                                             entity.getMessage(), entity.getContent(),
//                                             entity.getServerTimestamp(), entity.getServerUuid());
//      } else {
//        envelope = new SignalServiceEnvelope(entity.getType(), entity.getTimestamp(),
//                                             entity.getMessage(), entity.getContent(),
//                                             entity.getServerTimestamp(), entity.getServerUuid());
//      }

      callback.onMessage(envelope);
      results.add(envelope);

      //- todo: enable socket.acknowledgeMessage
//      if (envelope.hasUuid()) socket.acknowledgeMessage(envelope.getUuid());
//      else                    socket.acknowledgeMessage(entity.getSource(), entity.getTimestamp());
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
