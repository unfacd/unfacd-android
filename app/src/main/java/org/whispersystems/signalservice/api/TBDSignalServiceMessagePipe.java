package org.whispersystems.signalservice.api;

import java.util.Optional;
import org.signal.libsignal.protocol.InvalidVersionException;

import java.io.IOException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * A TBDSignalServiceMessagePipe represents a dedicated connection
 * to the Signal Service, which the server can push messages
 * down through.
 */
public class TBDSignalServiceMessagePipe
{

 /* private static final String SERVER_DELIVERED_TIMESTAMP_HEADER = "X-Unfcad-Timestamp";//AA++

  private final WebSocketConnection           websocket;
  private final Optional<CredentialsProvider> credentialsProvider;
  private final ClientZkProfileOperations clientZkProfile;

  private static final String TAG = Log.tag(TBDSignalServiceMessagePipe.class);

  //AA+
  private Object lock;
  private AtomicBoolean callbackDone;
  //

  TBDSignalServiceMessagePipe(WebSocketConnection websocket,
                             Optional<CredentialsProvider> credentialsProvider,
                             ClientZkProfileOperations clientZkProfile,
                             Object lock, AtomicBoolean callbackDone) {//AA+  lock
    this.websocket           = websocket;
    this.credentialsProvider = credentialsProvider;
    this.clientZkProfile     = clientZkProfile;

    //AA+
    this.lock         = lock;
    this.callbackDone = callbackDone;
    //
    Log.d(TAG, ">>> TBDSignalServiceMessagePipe: INITIATING websocket connect()...");

    this.websocket.connect();
  }

  //AA+
  public boolean isConnected ()
  {
    return (websocket != null && websocket.isConnected());
  }


  *//**
   * A blocking call that reads a message off the pipe.  When this
   * call returns, the message has been acknowledged and will not
   * be retransmitted.
   *
   * @param timeout The timeout to wait for.
   * @param unit The timeout time unit.
   * @return A new message.
   *
   * @throws InvalidVersionException
   * @throws IOException
   * @throws TimeoutException
   *//*

  public SignalServiceEnvelope read(long timeout, TimeUnit unit)
          throws InvalidVersionException, IOException, TimeoutException
  {
    return read(timeout, unit, new NullMessagePipeCallback());
  }

  *//**
   * A blocking call that reads a message off the pipe (see {@link #read(long, java.util.concurrent.TimeUnit)}
   *
   * Unlike {@link #read(long, java.util.concurrent.TimeUnit)}, this method allows you
   * to specify a callback that will be called before the received message is acknowledged.
   * This allows you to write the received message to durable storage before acknowledging
   * receipt of it to the server.
   *
   * @param timeout The timeout to wait for.
   * @param unit The timeout time unit.
   * @param callback A callback that will be called before the message receipt is
   *                 acknowledged to the server.
   * @return The message read (same as the message sent through the callback).
   * @throws TimeoutException
   * @throws IOException
   * @throws InvalidVersionException
   *//*
  //AA+
  public SignalServiceEnvelope read(long timeout, TimeUnit unit, MessagePipeCallback callback)
          throws TimeoutException, IOException, InvalidVersionException
  {
    while (true) {
      Optional<SignalServiceEnvelope> envelope = readOrEmpty(timeout, unit, callback);

      return envelope.orElse(null);
    }
  }

  *//**
   * Similar to {@link #read(long, TimeUnit, MessagePipeCallback)}, except this will return
   * {@link Optional#absent()} when an empty response is hit, which indicates the websocket is
   * empty.
   *
   * Important: The empty response will only be hit once for each connection. That means if you get
   * an empty response and call readOrEmpty() again on the same instance, you will not get an empty
   * response, and instead will block until you get an actual message. This will, however, reset if
   * connection breaks (if, for instance, you lose and regain network).
   *//*
  //AA+ semantics slightly changes as off https://github.com/signalapp/Signal-Android/commit/a299bafe89ae67084ca93cdd219f22a5db9bb55b && https://github.com/signalapp/Signal-Android/commit/662f0b8fb60e23999b580618584e0c0b9c6bce94
  //ufserver semantics not full aligned or still under review
  public Optional<SignalServiceEnvelope> readOrEmpty(long timeout, TimeUnit unit, MessagePipeCallback callback)
          throws TimeoutException, IOException, InvalidVersionException
  {
    if (!credentialsProvider.isPresent()) {
      throw new IllegalArgumentException("You can't read messages if you haven't specified credentials");
    }

    while (true) {
      //AA- original message was sent as request type only
       WebSocketProtos.WebSocketMessage wsm  = websocket.readRequest(unit.toMillis(timeout));

      byte[] messageBody;
      //AA+
      boolean                  signalKeyEncrypted;
      WebSocketRequestMessage  request        = null;
      WebSocketResponseMessage response       = null;
      WebSocketResponseMessage returnResponse = null;

      //currently, response is only set for fence state sync and location updates
      if (wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE) {
        response = wsm.getResponse();
        messageBody = response.getBody().toByteArray();
      } else if (wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) {
        request = wsm.getRequest();
        //signalKeyEncrypted = isSignalKeyEncrypted(request, wsm.getHeaders); //AA signalKey deprecated
        messageBody = request.getBody().toByteArray();
      } else {
        throw new IllegalStateException();
      }

      //Log.e(TAG, String.format("read: Raw Message: '%s'", messageBody.toString()));
      //AA this acknowledgement is not necessary at this stage
      //returnResponse = createWebSocketResponse(request);
      //

      try {
        synchronized (lock) {
          //AA+ ufsrv semantics
          if (isSignalServiceEnvelope(wsm)) {
            Optional<String> timestampHeader = findHeader(wsm, SERVER_DELIVERED_TIMESTAMP_HEADER);//AA+ wsm
            long             timestamp       = 0;

            if (timestampHeader.isPresent()) {
              try {
                timestamp = Long.parseLong(timestampHeader.get());
              } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse " + SERVER_DELIVERED_TIMESTAMP_HEADER);
              }
            }

            //AA+ we get clear text and in WebSocket.response.
            SignalServiceEnvelope envelope = new SignalServiceEnvelope(messageBody, timestamp);

            callback.onMessage(wsm, envelope, lock);

            while (callbackDone.get() == false) lock.wait();

            callbackDone.set(false);//not sure if necessary
            return Optional.of(envelope);//wsm;
          } else {
            Log.d(TAG, ">>> read: request received is not of Envelop type...");
            callback.onMessage(wsm, null, lock);
            return Optional.empty();
          }
        }
      }
      catch (InterruptedException ex) {Log.d(TAG, ex.getMessage());}
      finally {
        if (returnResponse != null) websocket.sendResponse(returnResponse); //AA+
      }
    }
  }

  //AA+
  public synchronized long sendWebSocketMessage(WebSocketProtos.WebSocketMessage wsm) throws IOException
  {
    if (websocket != null) {
      websocket.sendMessage(wsm);
      if (wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) return wsm.getRequest().getId();
      else if (wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE) return wsm.getResponse().getId();

      //return e;
    }

    return 0;
  }

  //AA+
  public synchronized void sendRequest(WebSocketRequestMessage request) throws IOException
  {
    if (websocket != null) {
      websocket.sendRequest(request, Optional.empty());
    }
  }

  public synchronized void sendResponse(WebSocketResponseMessage response) throws IOException
  {
    if (websocket != null) {
      websocket.sendResponse(response);
    }
  }
  //

  public Future<SendGroupMessageResponse> sendToGroup(byte[] body, byte[] joinedUnidentifiedAccess, long timestamp, boolean online) throws IOException {
    List<String> headers = new LinkedList<String>() {{
      add("content-type:application/vnd.signal-messenger.mrm");
      add("Unidentified-Access-Key:" + Base64.encodeBytes(joinedUnidentifiedAccess));
    }};

    String path = String.format(Locale.US, "/v1/messages/multi_recipient?ts=%s&online=%s", timestamp, online);

    WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
            .setId(new SecureRandom().nextLong())
            .setVerb("PUT")
            .setPath(path)
//            .addAllHeaders(headers)
            .setBody(ByteString.copyFrom(body))
            .build();

    ListenableFuture<WebsocketResponse> response = websocket.sendRequest(requestMessage, Optional.of(headers));//AA+ Optional

    return FutureTransformers.map(response, value -> {
      if (value.getStatus() == 200) {
        return JsonUtil.fromJson(value.getBody(), SendGroupMessageResponse.class);
      } else {
        throw new NonSuccessfulResponseCodeException(value.getStatus());
      }
    });
  }

  public Future<SendMessageResponse> send(OutgoingPushMessageList list, Optional<UnidentifiedAccess> unidentifiedAccess) throws IOException {
    List<String> headers = new LinkedList<String>() {{
      add("content-type:application/json");
    }};

    if (unidentifiedAccess.isPresent()) {
      headers.add("Unidentified-Access-Key:" + Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
    }

    WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
            .setId(new SecureRandom().nextLong())
            .setVerb("PUT")
            .setPath(String.format("/v1/messages/%s", list.getDestination()))
            .setBody(ByteString.copyFrom(JsonUtil.toJson(list).getBytes()))
            .build();

    ListenableFuture<WebsocketResponse> response = websocket.sendRequest(requestMessage, Optional.of(headers));

    return FutureTransformers.map(response, value -> {
      if (value.getStatus() == 404) {
        throw new UnregisteredUserException(list.getDestination(), new NotFoundException("not found"));
      } else if (value.getStatus() == 428) {
        ProofRequiredResponse proofResponse = JsonUtil.fromJson(value.getBody(), ProofRequiredResponse.class);
        String                retryAfterRaw = value.getHeader("Retry-After");
        long                  retryAfter    = Util.parseInt(retryAfterRaw, -1);

        throw new ProofRequiredException(proofResponse, retryAfter);
      } else if (value.getStatus() == 508) {
        throw new ServerRejectedException();
      } else if (value.getStatus() < 200 || value.getStatus() >= 300) {
        throw new IOException("Non-successful response: " + value.getStatus());
      }

      if (Util.isEmpty(value.getBody())) {
        return new SendMessageResponse(false);
      } else {
        return JsonUtil.fromJson(value.getBody(), SendMessageResponse.class);
      }
    });
  }

  //AA+ generalised for direct websocket bound commands (as opposed to api)
  public Future<SendMessageResponse> send(OutgoingPushMessageList list, Optional<UnidentifiedAccess> unidentifiedAccess, UfsrvCommand ufsrvCommand) throws IOException
  {
    List<String> headers = new LinkedList<String>()
    {{
      add("content-type:application/json");
    }};

    if (unidentifiedAccess.isPresent()) {
      headers.add("Unidentified-Access-Key:" + Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
    }

    if (!TextUtils.isEmpty(TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))) {
      headers.add("X-Ufsrv-Cookie:" + TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()));
    }

    WebSocketRequestMessage requestMessage = WebSocketProtos.WebSocketRequestMessage.newBuilder()
            .setId(new SecureRandom().nextLong())
            .setPath(ufsrvCommand.getServerCommandPath())
//                                  .setBody(ByteString.copyFrom(JsonUtil.toJson(list).getBytes()))
            .setVerb(JsonUtil.toJson(list))
            .build();

    //AA BLOCKING CALL on get(). result set async by incoming websocket response processed in WebSocketConnection::onMessage()
    ListenableFuture<WebsocketResponse> response = websocket.sendRequest(requestMessage, Optional.of(headers));

    return FutureTransformers.map(response, value -> {
      if (value.getStatus() == 404) {
        throw new UnregisteredUserException(list.getDestination(), new NotFoundException("not found"));
      } else if (value.getStatus() == 428) {
        ProofRequiredResponse proofResponse = JsonUtil.fromJson(value.getBody(), ProofRequiredResponse.class);
        String                retryAfterRaw = value.getHeader("Retry-After");
        long                  retryAfter    = Util.parseInt(retryAfterRaw, -1);

        throw new ProofRequiredException(proofResponse, retryAfter);
      } else if (value.getStatus() == 508) {
        throw new ServerRejectedException();
      } else if (value.getStatus() < 200 || value.getStatus() >= 300) {
        throw new IOException("Non-successful response: " + value.getStatus());
      }

      if (Util.isEmpty(value.getBody())) {
        return new SendMessageResponse(false);
      }
      else {
        return JsonUtil.fromJson(value.getBody(), SendMessageResponse.class);
      }
    });
  }

  public ListenableFuture<ProfileAndCredential> getProfile(SignalServiceAddress address,
                                                           Optional<ProfileKey> profileKey,
                                                           Optional<UnidentifiedAccess> unidentifiedAccess,
                                                           SignalServiceProfile.RequestType requestType)
          throws IOException
  {
    List<String> headers = new LinkedList<>();

    if (unidentifiedAccess.isPresent()) {
      headers.add("Unidentified-Access-Key:" + Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
    }

    Optional<UUID>                     uuid           = address.getAci();
    SecureRandom                       random         = new SecureRandom();
    ProfileKeyCredentialRequestContext requestContext = null;

    WebSocketRequestMessage.Builder builder = WebSocketRequestMessage.newBuilder()
            .setId(random.nextLong())
            .setVerb("GET");

    if (uuid.isPresent() && profileKey.isPresent()) {
      UUID              target               = uuid.get();
      ProfileKeyVersion profileKeyIdentifier = profileKey.get().getProfileKeyVersion(target);
      String            version              = profileKeyIdentifier.serialize();

      if (requestType == SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL) {
        requestContext = clientZkProfile.createProfileKeyCredentialRequestContext(random, target, profileKey.get());

        ProfileKeyCredentialRequest request           = requestContext.getRequest();
        String                      credentialRequest = Hex.toStringCondensed(request.serialize());

        builder.setPath(String.format("/v1/profile/%s/%s/%s", target, version, credentialRequest));
      } else {
        builder.setPath(String.format("/v1/profile/%s/%s", target, version));
      }
    } else {
      builder.setPath(String.format("/v1/profile/%s", address.getIdentifier()));
    }

    final ProfileKeyCredentialRequestContext finalRequestContext = requestContext;
    WebSocketRequestMessage requestMessage = builder.build();

    return FutureTransformers.map(websocket.sendRequest(requestMessage, Optional.of(headers)), response -> {//AA+ headers
      if (response.getStatus() == 404) {
        throw new NotFoundException("Not found");
      } else if (response.getStatus() < 200 || response.getStatus() >= 300) {
        throw new NonSuccessfulResponseCodeException(response.getStatus(), "Non-successful response: " + response.getStatus());
      }

      SignalServiceProfile signalServiceProfile = JsonUtil.fromJson(response.getBody(), SignalServiceProfile.class);
      ProfileKeyCredential profileKeyCredential = finalRequestContext != null && signalServiceProfile.getProfileKeyCredentialResponse() != null
                                                  ? clientZkProfile.receiveProfileKeyCredential(finalRequestContext, signalServiceProfile.getProfileKeyCredentialResponse())
                                                  : null;

      return new ProfileAndCredential(signalServiceProfile, requestType, Optional.ofNullable(profileKeyCredential));
    });
  }

  public AttachmentV2UploadAttributes getAttachmentV2UploadAttributes() throws IOException {
    try {
      WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
              .setId(new SecureRandom().nextLong())
              .setVerb("GET")
              .setPath("/v2/attachments/form/upload")
              .build();

      WebsocketResponse response = websocket.sendRequest(requestMessage, Optional.empty()).get(10, TimeUnit.SECONDS);

      if (response.getStatus() < 200 || response.getStatus() >= 300) {
        throw new IOException("Non-successful response: " + response.getStatus());
      }

      return JsonUtil.fromJson(response.getBody(), AttachmentV2UploadAttributes.class);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IOException(e);
    }
  }

  public AttachmentV3UploadAttributes getAttachmentV3UploadAttributes() throws IOException {
    try {
      WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
              .setId(new SecureRandom().nextLong())
              .setVerb("GET")
              .setPath("/v3/attachments/form/upload")
              .build();

      WebsocketResponse response = websocket.sendRequest(requestMessage, Optional.empty()).get(10, TimeUnit.SECONDS);

      if (response.getStatus() < 200 || response.getStatus() >= 300) {
        throw new IOException("Non-successful response: " + response.getStatus());
      }

      return JsonUtil.fromJson(response.getBody(), AttachmentV3UploadAttributes.class);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IOException(e);
    }
  }

  *//**
   * Close this connection to the server.
   *//*
  public void shutdown() {
    websocket.disconnect();
  }

  private boolean isSocketEmptyRequest(WebSocketRequestMessage message) {
    return "PUT".equals(message.getVerb()) && "/api/v1/queue/empty".equals(message.getPath());
  }

  private boolean isSignalKeyEncrypted(WebSocketRequestMessage message, List<String> headers) {
//    List<String> headers = message.getHeadersList();//AA-

    if (headers == null || headers.isEmpty()) {
      return true;
    }

    for (String header : headers) {
      String[] parts = header.split(":");

      if (parts.length == 2 && parts[0] != null && parts[0].trim().equalsIgnoreCase("X-Signal-Key")) {
        if (parts[1] != null && parts[1].trim().equalsIgnoreCase("false")) {
          return false;
        }
      }
    }

    return true;
  }

  //AA- private
  public boolean isSignalServiceEnvelope(WebSocketRequestMessage message) {
    return "PUT".equals(message.getVerb()) && "/api/v1/message".equals(message.getPath());
  }

  //AA+ overloadsmsg above for Ufsrv commands
  public boolean isSignalServiceEnvelope(WebSocketProtos.WebSocketMessage message) {
    return message.getCommand().startsWith("ufsrv://");
  }

  private WebSocketResponseMessage createWebSocketResponse(WebSocketRequestMessage request) {
    if (isSignalServiceEnvelope(request)) {
      return WebSocketResponseMessage.newBuilder()
                                     .setId(request.getId())
                                     .setStatus(200)
                                     .setMessage("OK")
                                     .build();
    } else {
      return WebSocketResponseMessage.newBuilder()
                                     .setId(request.getId())
                                     .setStatus(400)
                                     .setMessage("Unknown")
                                     .build();
    }
  }

  private static Optional<String> findHeader(WebSocketProtos.WebSocketMessage message, String targetHeader) {//AA+ arg
    if (message.getHeadersCount() == 0) {
      return Optional.empty();
    }

    for (String header : message.getHeadersList()) {
      if (header.startsWith(targetHeader)) {
        String[] split = header.split(":");
        if (split.length == 2 && split[0].trim().toLowerCase().equals(targetHeader.toLowerCase())) {
          return Optional.of(split[1].trim());
        }
      }
    }

    return Optional.empty();
  }

  *//**
   * For receiving a callback when a new message has been
   * received.
   *//*
  public interface MessagePipeCallback {
    void onMessage(WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock);//AA+  websock, lock
  }

  private static class NullMessagePipeCallback implements MessagePipeCallback {

    @Override
    public void onMessage(WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock) {} //AA+ lock
  }*/

}
