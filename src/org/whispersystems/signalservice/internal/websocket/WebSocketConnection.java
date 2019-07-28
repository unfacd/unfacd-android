package org.whispersystems.signalservice.internal.websocket;

import android.content.Intent;
import android.text.TextUtils;

import com.google.protobuf.InvalidProtocolBufferException;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.ufsrvcmd.events.AppEventNotificationWSConnection;

import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.SleepTimer;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.api.websocket.ConnectivityListener;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.util.concurrent.SettableFuture;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketMessage;
import static org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage;
import static org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketResponseMessage;

public class WebSocketConnection extends WebSocketListener {

  private static final String TAG                       = WebSocketConnection.class.getSimpleName();
  private static final int    KEEPALIVE_TIMEOUT_SECONDS = 120;//55; 4 minutes

  //
  public  static final String CONNECTED_STATE_EXTRA        = "ConnectedState";
  public static final String CONNECTEDWS_EVENT              = "org.thoughtcrime.securesms.CONNECTEDWS_EVENT";

  //- expanded type from Request to more general
  //private final LinkedList<WebSocketRequestMessage> incomingRequests = new LinkedList<>();
  private final Map<Long, SettableFuture<Pair<Integer, String>>>  outgoingRequests = new HashMap<>();
  private final LinkedList<WebSocketMessage>                      incomingRequests = new LinkedList<>();

  private final String                        wsUri;
  private final TrustStore                    trustStore;
  private final Optional<CredentialsProvider> credentialsProvider;
  private final String                        userAgent;
  private final ConnectivityListener          listener;
  private final SleepTimer                    sleepTimer;

  private WebSocket           client;
  private KeepAliveSender     keepAliveSender;
  private int                 attempts;
  private boolean             connected;

  public WebSocketConnection(String httpUri,
                             TrustStore trustStore,
                             Optional<CredentialsProvider> credentialsProvider,
                             String userAgent,
                             ConnectivityListener listener,
                             SleepTimer timer)
  {
    this.trustStore          = trustStore;
    this.credentialsProvider = credentialsProvider;
    this.userAgent           = userAgent;
    this.listener            = listener;
    this.sleepTimer          = timer;
    this.wsUri               = BuildConfig.UFSRV_URL;//httpUri; //todo: this needs to be ported to work with domain fronting, or somehow added to SignalServiceNetworkAccess.java
    this.attempts            = 0;
    this.connected           = false;
  }


  public synchronized void connect()
  {
    Log.w(TAG, ">>> WebSocket connect() to "+wsUri+"...");

    //- OKhttp

    if (client == null) {
      String                                   filledUri     = String.format(wsUri, credentialsProvider.get().getUser(), credentialsProvider.get().getPassword()); //todo: see https://github.com/signalapp/libsignal-service-java/commit/d4c746aeff17d8f9550fec557b4e968a4d4dc8c6
      Pair<SSLSocketFactory, X509TrustManager> socketFactory = createTlsSocketFactory(trustStore);

      OkHttpClient okHttpClient = new OkHttpClient.Builder()
              .sslSocketFactory(new Tls12SocketFactory(socketFactory.first()), socketFactory.second())
              .connectionSpecs(Util.immutableList(ConnectionSpec.RESTRICTED_TLS))
              .readTimeout(KEEPALIVE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
              .connectTimeout(KEEPALIVE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
              .build();

      Request.Builder requestBuilder = new Request.Builder().url(filledUri);

      if (userAgent != null) {
        requestBuilder.addHeader("X-Unfacd-Agent", userAgent);
        //
        requestBuilder.addHeader("Cookie", TextUtils.isEmpty(credentialsProvider.get().getCookie())?"0":credentialsProvider.get().getCookie());
        requestBuilder.addHeader("X-UFSRVCID", "0");
        requestBuilder.addHeader("X-CM-TOKEN", "0");
        //
      }

      if (listener != null) {
        listener.onConnecting();
      }

      this.connected = false;
      this.client    = okHttpClient.newWebSocket(requestBuilder.build(), this);

    }
  }

  public synchronized void disconnect() {
    Log.w(TAG, "WSC disconnect()...");

    if (client != null) {
      client.close(1000, "OK");
      client    = null;
      connected = false;
    }

    if (keepAliveSender != null) {
      keepAliveSender.shutdown();
      keepAliveSender = null;
    }
  }


  //public synchronized WebSocketRequestMessage readRequest(long timeoutMillis)
  public synchronized WebSocketMessage readRequest(long timeoutMillis)
      throws TimeoutException, IOException
  {
    if (client == null) {
      throw new IOException("Connection closed!");
    }

    long startTime = System.currentTimeMillis();

    while (client != null && incomingRequests.isEmpty() && elapsedTime(startTime) < timeoutMillis) {
      Util.wait(this, Math.max(1, timeoutMillis - elapsedTime(startTime)));
    }

    if      (incomingRequests.isEmpty() && client == null) throw new IOException("Connection closed!");
    else if (incomingRequests.isEmpty())                   throw new TimeoutException("Timeout exceeded");
    else
    {
      Log.d(TAG, String.format(">>> readRequest (%d): Removing from incomingRequests queue (current size: %d)",  Thread.currentThread().getId(), incomingRequests.size()));

      WebSocketMessage ws = incomingRequests.removeFirst();

      return ws;
    }
  }


  public synchronized Future<Pair<Integer, String>> sendRequest(WebSocketRequestMessage request) throws IOException {
    if (client == null || !connected) throw new IOException("sendRequest: No connection!");

    WebSocketMessage message = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(request)
            .build();

    SettableFuture<Pair<Integer, String>> future = new SettableFuture<>();
    outgoingRequests.put(request.getId(), future);

    if (!client.send(ByteString.of(message.toByteArray()))) {
      throw new IOException("sendRequest: Write failed!");
    }

    return future;
  }

  public synchronized void sendResponse(WebSocketResponseMessage response) throws IOException {
    if (client == null) {
      throw new IOException("Connection closed!");
    }

    WebSocketMessage message = WebSocketMessage.newBuilder()
                                               .setType(WebSocketMessage.Type.RESPONSE)
                                               .setResponse(response)
                                               .build();

    if (!client.send(ByteString.of(message.toByteArray()))) {
      throw new IOException("sendResponse: Write failed!");
    }
    //client.send(message.toByteArray()); //WebSocketClient
  }

  //
  public synchronized void sendMessage (WebSocketMessage wsm) throws IOException
  {
    if (client == null) {
      throw new IOException(TAG+" sendMessage: Connection closed!");
    }

   // client.sendMessage(wsm.toByteArray());
    if (!client.send(ByteString.of(wsm.toByteArray()))) {
      throw new IOException("sendMessage: Write failed!");
    }


  }


  private synchronized void sendKeepAlive() throws IOException {
    if (keepAliveSender != null && client != null) {
      byte[] message = WebSocketMessage.newBuilder()
              .setType(WebSocketMessage.Type.REQUEST)
              .setRequest(WebSocketRequestMessage.newBuilder()
                      .setId(System.currentTimeMillis())
                      .setPath("/v1/keepalive")
                      .setVerb("GET")
                      .build()).build()
              .toByteArray();

      if (!client.send(ByteString.of(message))) {
        throw new IOException("Write failed!");
      }
    }
  }

  @Override
  public synchronized void onOpen(WebSocket webSocket, Response response) {
    if (client != null && keepAliveSender == null) {
      Log.w(TAG, "onOpen()");
      attempts        = 0;
      connected       = true;
      keepAliveSender = new KeepAliveSender();
      keepAliveSender.start();

      if (listener != null) listener.onConnected();

      //
      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventNotificationWSConnection(true));

      // send connected broadcast for listeners to this intent
      Intent connectedWsIntent = new Intent(CONNECTEDWS_EVENT);
      connectedWsIntent.putExtra(CONNECTED_STATE_EXTRA, true);
      ApplicationContext.getInstance().getBaseContext().sendBroadcast(connectedWsIntent);
      //

    }
  }

  @Override
  public synchronized void onMessage(WebSocket webSocket, ByteString payload) {
    Log.w(TAG, "WSC onMessage()");
    try {
      WebSocketMessage message = WebSocketMessage.parseFrom(payload.toByteArray());

      Log.w(TAG, String.format("onMessage ('%d'):  Command: '%s' Text: '%s'", Thread.currentThread().getId(), message.getCommand(), message.toString()));

      if (true/*message.getType().getNumber() == WebSocketMessage.Type.REQUEST_VALUE*/)  {
        //incomingRequests.add(message.getRequest());
        incomingRequests.add(message);//
      } else if (message.getType().getNumber() == WebSocketMessage.Type.RESPONSE_VALUE) {
        SettableFuture<Pair<Integer, String>> listener = outgoingRequests.get(message.getResponse().getId());
        if (listener != null) listener.set(new Pair<>(message.getResponse().getStatus(),
                new String(message.getResponse().getBody().toByteArray())));
      }

      notifyAll();
    } catch (InvalidProtocolBufferException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  public synchronized void onClosed(WebSocket webSocket, int code, String reason) {
    Log.w(TAG, "onClose()...");
    this.connected = false;

    //
    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventNotificationWSConnection(false));

    Iterator<Map.Entry<Long, SettableFuture<Pair<Integer, String>>>> iterator = outgoingRequests.entrySet().iterator();

    while (iterator.hasNext()) {
      Map.Entry<Long, SettableFuture<Pair<Integer, String>>> entry = iterator.next();
      entry.getValue().setException(new IOException("Closed: " + code + ", " + reason));
      iterator.remove();
    }

    if (keepAliveSender != null) {
      keepAliveSender.shutdown();
      keepAliveSender = null;
    }

    if (listener != null) {
      listener.onDisconnected();
    }

    Util.wait(this, Math.min(++attempts * 200, TimeUnit.SECONDS.toMillis(15)));

    if (client != null) {
      client.close(1000, "OK");
      client    = null;
      connected = false;
      connect();
    }

    notifyAll();
  }

  //
  public boolean isConnected()
  {
    return (client!=null && connected==true);//client.isConnected());

  }

  //
  public boolean isClosed()
  {
    return (client==null || connected==false);//client.isClosed());

  }

  @Override
  public synchronized void onFailure(WebSocket webSocket, Throwable t, Response response) {
    Log.w(TAG, "onFailure()");
    Log.w(TAG, t);

    if (response != null && (response.code() == 401 || response.code() == 403)) {
      if (listener != null) listener.onAuthenticationFailure();
    }

    if (client != null) {
      onClosed(webSocket, 1000, "OK");
    }
  }


  @Override
  public void onMessage(WebSocket webSocket, String text) {
    Log.w(TAG, "onMessage(text)! " + text);
  }

  @Override
  public synchronized void onClosing(WebSocket webSocket, int code, String reason) {
    Log.w(TAG, "onClosing()!...");
    webSocket.close(1000, "OK");
  }


  private Pair<SSLSocketFactory, X509TrustManager> createTlsSocketFactory(TrustStore trustStore) {
    try {
      SSLContext     context       = SSLContext.getInstance("TLS");
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(trustStore);
      context.init(null, trustManagers, null);

      return new Pair<>(context.getSocketFactory(), (X509TrustManager)trustManagers[0]);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }


  private long elapsedTime(long startTime) {
    return System.currentTimeMillis() - startTime;
  }

  private class KeepAliveSender extends Thread {

    private AtomicBoolean stop = new AtomicBoolean(false);

    public void run() {
      while (!stop.get()) {
        try {
          sleepTimer.sleep(TimeUnit.SECONDS.toMillis(KEEPALIVE_TIMEOUT_SECONDS));

          Log.d(TAG, "Sending keep alive...");
          sendKeepAlive();
        } catch (Throwable e) {
          Log.w(TAG, e);
        }
      }
    }

    public void shutdown() {
      stop.set(true);
    }
  }
}
