package org.whispersystems.signalservice.internal.websocket;

import android.content.Intent;
import android.text.TextUtils;

import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.ufsrvcmd.events.AppEventNotificationWSConnection;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.api.util.TlsProxySocketFactory;
import org.whispersystems.signalservice.api.websocket.HealthMonitor;
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState;
import org.whispersystems.signalservice.internal.configuration.SignalProxy;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.Interceptor;
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

  private static final String TAG                       = Log.tag(WebSocketConnection.class);
  public static final int     KEEPALIVE_TIMEOUT_SECONDS = 120;//55; 4 minutes

  //AA+
  public  static final String CONNECTED_STATE_EXTRA        = "ConnectedState";
  public static final String CONNECTEDWS_EVENT             = "org.thoughtcrime.securesms.CONNECTEDWS_EVENT";

  //AA- expanded type from Request to more general
  //private final LinkedList<WebSocketRequestMessage> incomingRequests = new LinkedList<>();
  private final LinkedList<WebSocketMessage>        incomingRequests = new LinkedList<>();
  private final Map<Long, OutgoingRequest>          outgoingRequests = new HashMap<>();
  private final Set<Long>                           keepAlives       = new HashSet<>();

  private final String                                    name;
  private final String                                    wsUri;
  private final TrustStore                                trustStore;
  private final Optional<CredentialsProvider>             credentialsProvider;
  private final String                                    signalAgent;
  private final HealthMonitor healthMonitor;
  private final List<Interceptor>                         interceptors;
  private final Optional<Dns>                             dns;
  private final Optional<SignalProxy>                     signalProxy;
  private final BehaviorSubject<WebSocketConnectionState> webSocketState;

  private WebSocket client;

  public WebSocketConnection(String name,
                             SignalServiceConfiguration serviceConfiguration,
                             Optional<CredentialsProvider> credentialsProvider,
                             String signalAgent,
                             HealthMonitor healthMonitor) {
    this(name, serviceConfiguration, credentialsProvider, signalAgent, healthMonitor, "");
  }

  public WebSocketConnection(String name,
                             SignalServiceConfiguration serviceConfiguration,
                             Optional<CredentialsProvider> credentialsProvider,
                             String signalAgent,
                             HealthMonitor healthMonitor,
                             String extraPathUri)
  {
    this.name                = "[" + name + ":" + System.identityHashCode(this) + "]";
    this.trustStore          = serviceConfiguration.getSignalServiceUrls()[0].getTrustStore();
    this.credentialsProvider = credentialsProvider;
    this.signalAgent         = signalAgent;
    this.interceptors        = serviceConfiguration.getNetworkInterceptors();
    this.dns                 = serviceConfiguration.getDns();
    this.signalProxy         = serviceConfiguration.getSignalProxy();
    this.healthMonitor       = healthMonitor;
    this.webSocketState      = BehaviorSubject.createDefault(WebSocketConnectionState.DISCONNECTED);

    this.wsUri               = BuildConfig.UFSRV_URL;//httpUri; //todo: this needs to be ported to work with domain fronting, or somehow added to SignalServiceNetworkAccess.java
    /*String uri = serviceConfiguration.getSignalServiceUrls()[0].getUrl().replace("https://", "wss://").replace("http://", "ws://");

    if (credentialsProvider.isPresent()) {
      this.wsUri = uri + "/v1/websocket/" + extraPathUri + "?login=%s&password=%s";
    } else {
      this.wsUri = uri + "/v1/websocket/" + extraPathUri;
    }*/
  }


  public String getName() {
    return name;
  }

  public synchronized Observable<WebSocketConnectionState> connect() {
    Log.w(TAG, ">>> WebSocket connect() to " + wsUri + "...");

    if (client == null) {
      String filledUri;
      if (credentialsProvider.isPresent()) {
//        String identifier = Objects.requireNonNull(credentialsProvider.get().getAci()).toString();
//        if (credentialsProvider.get().getDeviceId() != SignalServiceAddress.DEFAULT_DEVICE_ID) {
//          identifier += "." + credentialsProvider.get().getDeviceId();
//        }
//        filledUri = String.format(wsUri, identifier, credentialsProvider.get().getPassword());
        filledUri     = String.format(wsUri, credentialsProvider.get().getUser(), credentialsProvider.get().getPassword());//AA+ //todo: see https://github.com/signalapp/libsignal-service-java/commit/d4c746aeff17d8f9550fec557b4e968a4d4dc8c6
      } else {
        filledUri = wsUri;
      }

      Pair<SSLSocketFactory, X509TrustManager> socketFactory = createTlsSocketFactory(trustStore);

      OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().sslSocketFactory(new Tls12SocketFactory(socketFactory.first()),
                                                                                       socketFactory.second())
                                                                     .connectionSpecs(Util.immutableList(ConnectionSpec.RESTRICTED_TLS))
                                                                     .readTimeout(KEEPALIVE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
                                                                     .dns(dns.orElse(Dns.SYSTEM))
                                                                     .connectTimeout(KEEPALIVE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS);

      for (Interceptor interceptor : interceptors) {
        clientBuilder.addInterceptor(interceptor);
      }
      if (signalProxy.isPresent()) {
        clientBuilder.socketFactory(new TlsProxySocketFactory(signalProxy.get().getHost(), signalProxy.get().getPort(), dns));
      }
      OkHttpClient okHttpClient = clientBuilder.build();
      Request.Builder requestBuilder = new Request.Builder().url(filledUri);
      if (signalAgent != null) {
        requestBuilder.addHeader("X-Signal-Agent", signalAgent);
        //AA+
        requestBuilder.addHeader("Cookie", TextUtils.isEmpty(credentialsProvider.get().getCookie()) ? "0" : credentialsProvider.get().getCookie());
        requestBuilder.addHeader("X-UFSRVCID", "0");
        requestBuilder.addHeader("X-CM-TOKEN", "0");
        //
      }

      webSocketState.onNext(WebSocketConnectionState.CONNECTING);

      this.client = okHttpClient.newWebSocket(requestBuilder.build(), this);
    }
    return webSocketState;
  }

  public synchronized boolean isDead() {
    return client == null;
  }

  public synchronized void disconnect() {
    Log.w(TAG, "WSC disconnect()...");

      if (client != null) {
        client.close(1000, "OK");
        client = null;
        webSocketState.onNext(WebSocketConnectionState.DISCONNECTING);
      }

    notifyAll();
  }

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

    if (incomingRequests.isEmpty() && client == null) {
      throw new IOException("Connection closed!");
    } else if (incomingRequests.isEmpty()) {
      throw new TimeoutException("Timeout exceeded");
    } else {
        Log.d(TAG, String.format(">>> readRequest (%d): Removing from incomingRequests queue (current size: %d)",  Thread.currentThread().getId(), incomingRequests.size()));
        return incomingRequests.removeFirst();
    }
  }

  public synchronized Single<WebsocketResponse> sendRequest(WebSocketRequestMessage request, Optional<List<String>> headers) throws IOException {//AA+ headers
    if (client == null) {
      throw new IOException("No connection!");
    }

    WebSocketMessage message = WebSocketMessage.newBuilder()
                                              .setType(WebSocketMessage.Type.REQUEST)
                                              .setCommand(request.getPath())//AA+
                                              .addAllHeaders(headers.orElse(null))//AA+
                                              .setRequest(request)
                                              .build();

    SingleSubject<WebsocketResponse> single = SingleSubject.create();

    outgoingRequests.put(request.getId(), new OutgoingRequest(single));

    Log.d(TAG, String.format(">>> sendRequest (WS) (hash_sz:'%d'): Generated id: '%d'",   outgoingRequests.size(), request.getId()));

    if (!client.send(ByteString.of(message.toByteArray()))) {
      throw new IOException("sendRequest: Write failed!");
    }

    return single.subscribeOn(Schedulers.io())
                 .observeOn(Schedulers.io())
                 .timeout(10, TimeUnit.SECONDS, Schedulers.io());
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
  }

  //AA+
  public synchronized void sendMessage(WebSocketMessage wsm) throws IOException
  {
    if (client == null) {
      throw new IOException(TAG+" sendMessage: Connection closed!");
    }

   // client.sendMessage(wsm.toByteArray());
    if (!client.send(ByteString.of(wsm.toByteArray()))) {
      throw new IOException("sendMessage: Write failed!");
    }

  }

  public synchronized void sendKeepAlive() throws IOException {
    if (client != null) {
      long id = System.currentTimeMillis();
      log( "Sending keep alive... id: " + id);
      byte[] message = WebSocketMessage.newBuilder()
                                       .setType(WebSocketMessage.Type.REQUEST)
                                       .setRequest(WebSocketRequestMessage.newBuilder()
                                                                          .setId(id)
                                                                          .setPath("/v1/keepalive")
                                                                          .setVerb("GET")
                                                                          .build())
                                       .setCommand("/v1/keepalive")
                                       .build()
                                       .toByteArray();
      keepAlives.add(id);
      if (!client.send(ByteString.of(message))) {
        throw new IOException("Write failed!");
      }
    }
  }

  @Override
  public synchronized void onOpen(WebSocket webSocket, Response response) {
    if (client != null) {
      Log.w(TAG, "onOpen()");
      webSocketState.onNext(WebSocketConnectionState.CONNECTED);

      //AA+
      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventNotificationWSConnection(true));

      //AA+ send connected broadcast for listeners to this intent
      Intent connectedWsIntent = new Intent(CONNECTEDWS_EVENT);
      connectedWsIntent.putExtra(CONNECTED_STATE_EXTRA, true);
      ApplicationContext.getInstance().getBaseContext().sendBroadcast(connectedWsIntent);
      //
    }
  }

  @Override
  public synchronized void onMessage(WebSocket webSocket, ByteString payload) {
    try {
      WebSocketMessage message = WebSocketMessage.parseFrom(payload.toByteArray());

      if (!message.getCommand().equalsIgnoreCase("/v1/keepalive")) {//too verbose otherwise
        Log.w(TAG, String.format(Locale.getDefault(), "onMessage ('%d'):  Command: '%s' Text: '%s'", Thread.currentThread().getId(), message.getCommand(), message));
      }

      if (message.getType().getNumber() == WebSocketMessage.Type.REQUEST_VALUE) {
        Log.d(TAG, "onMessage() -- incoming request");
        incomingRequests.add(message);
      } else if (message.getType().getNumber() == WebSocketMessage.Type.RESPONSE_VALUE) {
        OutgoingRequest listener = outgoingRequests.remove(message.getResponse().getId());
        if (listener != null) {
          listener.onSuccess(new WebsocketResponse(message.getResponse().getStatus(),
                                                   new String(message.getResponse().getBody().toByteArray()),
                                                   message.getHeadersList(),
                                                   !credentialsProvider.isPresent()));//AA headers directly from message
          if (message.getResponse().getStatus() >= 400) {
            healthMonitor.onMessageError(message.getResponse().getStatus(), credentialsProvider.isPresent());
          }
        } else if (keepAlives.remove(message.getResponse().getId())) {
          healthMonitor.onKeepAliveResponse(message.getResponse().getId(), credentialsProvider.isPresent());
        } else {
          Log.d(TAG, "onMessage() -- response received, but no listener");
        }

        incomingRequests.add(message);//AA+ uf semantics response messages arriving via websocket are legit envelope (e.g delivery receipts).
      }

      notifyAll();
    } catch (InvalidProtocolBufferException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  public synchronized void onClosed(WebSocket webSocket, int code, String reason) {
    log("onClose()");
    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventNotificationWSConnection(false));//AA+
    webSocketState.onNext(WebSocketConnectionState.DISCONNECTED);

    cleanupAfterShutdown();

    notifyAll();
  }

  @Override
  public synchronized void onFailure(WebSocket webSocket, Throwable t, Response response) {
    warn("onFailure()", t);

    if (response != null && (response.code() == 401 || response.code() == 403)) {
      webSocketState.onNext(WebSocketConnectionState.AUTHENTICATION_FAILED);
    } else {
      webSocketState.onNext(WebSocketConnectionState.FAILED);
    }

    cleanupAfterShutdown();

    notifyAll();
  }

  private void cleanupAfterShutdown() {
    Iterator<Map.Entry<Long, OutgoingRequest>> iterator = outgoingRequests.entrySet().iterator();

    while (iterator.hasNext()) {
      Map.Entry<Long, OutgoingRequest> entry = iterator.next();
      entry.getValue().onError(new IOException("Closed unexpectedly"));
      iterator.remove();
    }

    if (client != null) {
      log("Client not null when closed");
      client.close(1000, "OK");
      client = null;
    }
  }

  @Override
  public void onMessage(WebSocket webSocket, String text) {
    Log.w(TAG, "onMessage(text)! " + text);
  }

  @Override
  public synchronized void onClosing(WebSocket webSocket, int code, String reason) {
    Log.w(TAG, "onClosing()!...");
    webSocketState.onNext(WebSocketConnectionState.DISCONNECTING);
    webSocket.close(1000, "OK");
  }


  private Pair<SSLSocketFactory, X509TrustManager> createTlsSocketFactory(TrustStore trustStore) {
    try {
      SSLContext     context       = SSLContext.getInstance("TLS");
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(trustStore);
      context.init(null, trustManagers, null);

      return new Pair<>(context.getSocketFactory(), (X509TrustManager) trustManagers[0]);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }

  private void log(String message) {
    Log.i(TAG, name + " " + message);
  }

  @SuppressWarnings("SameParameterValue")
  private void warn(String message) {
    Log.w(TAG, name + " " + message);
  }

  private void warn(Throwable e) {
    Log.w(TAG, name, e);
  }

  @SuppressWarnings("SameParameterValue")
  private void warn(String message, Throwable e) {
    Log.w(TAG, name + " " + message, e);
  }

  private long elapsedTime(long startTime) {
    return System.currentTimeMillis() - startTime;
  }

 /* private class KeepAliveSender extends Thread {

    private final AtomicBoolean stop = new AtomicBoolean(false);

    public void run() {
      while (!stop.get()) {
        try {
          sleepTimer.sleep(TimeUnit.SECONDS.toMillis(KEEPALIVE_TIMEOUT_SECONDS));

          if (!stop.get()) {
            log("Sending keep alive...");
            sendKeepAlive();
          }
        } catch (Throwable e) {
        warn(e);
        }
      }
    }

    public void shutdown() {
      stop.set(true);
    }
  }*/

  private static class OutgoingRequest {
    private final SingleSubject<WebsocketResponse> responseSingle;

    private OutgoingRequest(SingleSubject<WebsocketResponse> future) {
      this.responseSingle = future;
    }

    public void onSuccess(WebsocketResponse response) {
      responseSingle.onSuccess(response);
    }

    public void onError(Throwable throwable) {
      responseSingle.onError(throwable);
    }
  }
}
