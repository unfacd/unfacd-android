package org.whispersystems.signalservice.api;

import com.google.protobuf.ByteString;

import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.internal.push.AttachmentUploadAttributes;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList;
import org.whispersystems.signalservice.internal.push.SendMessageResponse;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.websocket.WebSocketConnection;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.thoughtcrime.securesms.logging.Log;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage;
import static org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketResponseMessage;


/**
 * A SignalServiceMessagePipe represents a dedicated connection
 * to the Signal Service, which the server can push messages
 * down through.
 */
public class SignalServiceMessagePipe {

  private final WebSocketConnection           websocket;
  private final Optional<CredentialsProvider> credentialsProvider;

  private static final String TAG = SignalServiceMessagePipe.class.getSimpleName();

  //
  private Object lock;
  private AtomicBoolean callbackDone;
  //

  SignalServiceMessagePipe(WebSocketConnection websocket, Optional<CredentialsProvider> credentialsProvider, Object lock, AtomicBoolean callbackDone) {//  lock
    this.websocket           = websocket;
    this.credentialsProvider = credentialsProvider;

    //
    this.lock=lock;
    this.callbackDone=callbackDone;
    //
    Log.d(TAG, ">>> SignalServiceMessagePipe: INITIATING websocket connect()...");

    this.websocket.connect();
  }

  //
  public boolean isConnected ()
  {
    return (websocket!=null && websocket.isConnected());
  }


  /**
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
   */
  //-
  /*public SignalServiceEnvelope read(long timeout, TimeUnit unit)
      throws InvalidVersionException, IOException, TimeoutException
  {
    return read(timeout, unit, new NullMessagePipeCallback());
  }*/


  // changd return type (from envelop) to proto
  public WebSocketProtos.WebSocketMessage read(long timeout, TimeUnit unit)
          throws InvalidVersionException, IOException, TimeoutException
  {
    return read(timeout, unit, new NullMessagePipeCallback());
  }

  /**
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
   */
  //
  //Invoked from SignaServiceMessageReciever per message iteration. Invokes the reader in WebSocketConnection which polls/returns the incoming queue
  //WebSocketMessage can be eithr: 1)ControlMessage from the server (WebSocketMessage) or Envelope, containing user comms
  //it then invokes the callback from its caller (SignalServiceMessageReceiver) on this message before returning to it.
  //SignalServiceMessageReceiver then does another loop iteration calling this function and cycle continue....
  public WebSocketProtos.WebSocketMessage read(long timeout, TimeUnit unit, MessagePipeCallback callback)
          throws TimeoutException, IOException, InvalidVersionException
  {
    if (!credentialsProvider.isPresent()) {
      throw new IllegalArgumentException("You can't read messages if you haven't specified credentials");
    }

    while (true) {
      //- original message was sent as request type only
      //WebSocketRequestMessage  request  = websocket.readRequest(unit.toMillis(timeout));
      //WebSocketResponseMessage response = createWebSocketResponse(request);
//      boolean                  signalKeyEncrypted = isSignalKeyEncrypted(request);

      // read whole of Webscoket Message object and dissect types could be response or request
      WebSocketProtos.WebSocketMessage wsm  = websocket.readRequest(unit.toMillis(timeout));

      byte[] messageBody;
      boolean                  signalKeyEncrypted;
      WebSocketRequestMessage request=null;
      WebSocketResponseMessage response=null;
      WebSocketResponseMessage returnResponse=null;

      if (wsm.getType().getNumber()== WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE) {
        response = wsm.getResponse();
        messageBody=response.getBody().toByteArray();
      } else if (wsm.getType().getNumber()== WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) {
        request = wsm.getRequest();
        signalKeyEncrypted = isSignalKeyEncrypted(request); // signalKey deprecated
        messageBody=request.getBody().toByteArray();
      } else {
        Log.e(TAG, String.format("read: Received WebsocketMessage of unknown type. Type:'%d', Command: '%s'", wsm.getType().getNumber(), wsm.getCommand()));
        return wsm;
      }

      //Log.e(TAG, String.format("read: Raw Message: '%s'", messageBody.toString()));
      // this acknowledgement is not necessary at this stage
      //returnResponse = createWebSocketResponse(request);
      //

      try {
        synchronized (lock)
        {
          //-
          // if (isSignalServiceEnvelope(request)) {
          // ufsrv semantics
          if (isSignalServiceEnvelope(wsm)) {
            //- disabled user signalling key based encryption
            //SignalServiceEnvelope envelope = new SignalServiceEnvelope(request.getBody().toByteArray(),
            //      credentialsProvider.get().getSignalingKey());

            // we get clear text and in WebSocket.response. later on enable siganallingkey base encryption
            SignalServiceEnvelope envelope = new SignalServiceEnvelope(messageBody);
            //
            callback.onMessage(wsm, envelope, lock);

            while (callbackDone.get()==false) lock.wait();//don't return until callback finished processing

            callbackDone.set(false);//not sure if necessary
            return wsm;
          } else {
            Log.d(TAG, ">>> read: request received is not of Envelop type...");
            callback.onMessage(wsm, null, lock);
            return wsm;
          }
        }
      }
      catch (InterruptedException ex) {Log.d(TAG, ex.getMessage());}
      finally {
        if (returnResponse!=null) websocket.sendResponse(returnResponse); //
      }
    }
  }



  //
  public synchronized long sendWebSocketMessage(WebSocketProtos.WebSocketMessage wsm) throws IOException
  {
    if (websocket!=null)
    {
      websocket.sendMessage(wsm);
      if (wsm.getType().getNumber()== WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) return wsm.getRequest().getId();
      else if (wsm.getType().getNumber()== WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE) return wsm.getResponse().getId();

      //return e;
    }

    return 0;
  }

  //
  public synchronized void sendRequest(WebSocketRequestMessage request) throws IOException
  {
    if (websocket!=null)
    {
      websocket.sendRequest(request);
    }
  }

  public synchronized void sendResponse(WebSocketResponseMessage response) throws IOException
  {
    if (websocket!=null)
    {
      websocket.sendResponse(response);
    }
  }
  //


  public SendMessageResponse send(OutgoingPushMessageList list, Optional<UnidentifiedAccess> unidentifiedAccess) throws IOException {
    try {
      List<String> headers = new LinkedList<String>() {{
        add("content-type:application/json");
      }};

      if (unidentifiedAccess.isPresent()) {
        headers.add("Unidentified-Access-Key:" + Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
      }

      WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
              .setId(SecureRandom.getInstance("SHA1PRNG").nextLong())
              .setVerb("PUT")
              .setPath(String.format("/v1/messages/%s", list.getDestination()))
              .addAllHeaders(headers)
              .setBody(ByteString.copyFrom(JsonUtil.toJson(list).getBytes()))
              .build();

      Pair<Integer, String> response = websocket.sendRequest(requestMessage).get(10, TimeUnit.SECONDS);

      if (response.first() < 200 || response.first() >= 300) {
        throw new IOException("Non-successful response: " + response.first());
      }

      if (Util.isEmpty(response.second())) return new SendMessageResponse(false);
      else                                 return JsonUtil.fromJson(response.second(), SendMessageResponse.class);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IOException(e);
    }
  }

  public SignalServiceProfile getProfile(SignalServiceAddress address, Optional<UnidentifiedAccess> unidentifiedAccess) throws IOException {
    try {
      List<String> headers = new LinkedList<>();

      if (unidentifiedAccess.isPresent()) {
        headers.add("Unidentified-Access-Key:" + Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
      }

      WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
              .setId(SecureRandom.getInstance("SHA1PRNG").nextLong())
              .setVerb("GET")
              .setPath(String.format("/v1/profile/%s", address.getNumber()))
              .addAllHeaders(headers)
              .build();

      Pair<Integer, String> response = websocket.sendRequest(requestMessage).get(10, TimeUnit.SECONDS);

      if (response.first() < 200 || response.first() >= 300) {
        throw new IOException("Non-successful response: " + response.first());
      }

      return JsonUtil.fromJson(response.second(), SignalServiceProfile.class);
    } catch (NoSuchAlgorithmException nsae) {
      throw new AssertionError(nsae);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IOException(e);
    }
  }

  public AttachmentUploadAttributes getAttachmentUploadAttributes() throws IOException {
    try {
      WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
              .setId(new SecureRandom().nextLong())
              .setVerb("GET")
              .setPath("/v2/attachments/form/upload")
              .build();

      Pair<Integer, String> response = websocket.sendRequest(requestMessage).get(10, TimeUnit.SECONDS);

      if (response.first() < 200 || response.first() >= 300) {
        throw new IOException("Non-successful response: " + response.first());
      }

      return JsonUtil.fromJson(response.second(), AttachmentUploadAttributes.class);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new IOException(e);
    }
  }

  /**
   * Close this connection to the server.
   */
  public void shutdown() {
    websocket.disconnect();
  }

  private boolean isSignalKeyEncrypted(WebSocketRequestMessage message) {
    List<String> headers = message.getHeadersList();

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

  //- private
  /*private*/public boolean isSignalServiceEnvelope(WebSocketRequestMessage message) {
    return "PUT".equals(message.getVerb()) && "/api/v1/message".equals(message.getPath());
  }

  // overloadsmsg above for Ufsrv comands
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

  /**
   * For receiving a callback when a new message has been
   * received.
   */
  public static interface MessagePipeCallback {
    public void onMessage(WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock);//  websock, lock
  }

  private static class NullMessagePipeCallback implements MessagePipeCallback {
    //@Override
    //public void onMessage(SignalServiceEnvelope envelope) {}
    //
    @Override
    public void onMessage(WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock) {}
  }

}
