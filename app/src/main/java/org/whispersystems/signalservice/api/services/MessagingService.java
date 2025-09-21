package org.whispersystems.signalservice.api.services;

import android.text.TextUtils;

import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.integrity.IntegrityUtils;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.thoughtcrime.securesms.util.TextSecurePreferences;
import java.util.Optional;
import org.whispersystems.signalservice.api.SignalWebSocket;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.push.exceptions.NotFoundException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.ServiceResponseProcessor;
import org.whispersystems.signalservice.internal.push.GroupMismatchedDevices;
import org.whispersystems.signalservice.internal.push.GroupStaleDevices;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList;
import org.whispersystems.signalservice.internal.push.SendGroupMessageResponse;
import org.whispersystems.signalservice.internal.push.SendMessageResponse;
import org.whispersystems.signalservice.internal.push.exceptions.GroupMismatchedDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.GroupStaleDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.InvalidUnidentifiedAccessHeaderException;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.Util;
import org.whispersystems.signalservice.internal.websocket.DefaultResponseMapper;
import org.whispersystems.signalservice.internal.websocket.ResponseMapper;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage;
import org.whispersystems.util.Base64;

import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import io.reactivex.rxjava3.core.Single;

/**
 * Provide WebSocket based interface to message sending endpoints.
 * <p>
 * Note: To be expanded to have REST fallback and other messaging related operations.
 */
public class MessagingService {
  private final SignalWebSocket signalWebSocket;

  public MessagingService(SignalWebSocket signalWebSocket) {
    this.signalWebSocket = signalWebSocket;
  }

  public Single<ServiceResponse<SendMessageResponse>> send(OutgoingPushMessageList list, Optional<UnidentifiedAccess> unidentifiedAccess, UfsrvCommand ufsrvCommand) {//AA+
    List<String> headers = new LinkedList<String>() {{
      add("content-type:application/json");
    }};

    //AA+
    if (unidentifiedAccess.isPresent()) {
      headers.add("Unidentified-Access-Key:" + org.whispersystems.signalservice.internal.util.Base64.encodeBytes(unidentifiedAccess.get().getUnidentifiedAccessKey()));
    }

    if (!TextUtils.isEmpty(TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()))) {
      headers.add("X-Ufsrv-Cookie:" + TextSecurePreferences.getUfsrvCookie(ApplicationContext.getInstance()));
    }

    if (ufsrvCommand.isIntegritySensitive()) {
      Optional<String> encodedRequestHash = ufsrvCommand.getRequestHashForIntegrityToken();
      if (encodedRequestHash.isPresent()) IntegrityUtils.requestIntegrityVerdict(encodedRequestHash.get(),
                                                                                 (token) -> headers.add("X-Integrity-Token:" + token));
    }
    //

    WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
                                                                    .setId(new SecureRandom().nextLong())
                                                                    .setVerb(JsonUtil.toJson(list))
                                                                    .setPath(ufsrvCommand.getServerCommandPath())
                                                                    //.addAllHeaders(headers)
                                                                    //.setBody(ByteString.copyFrom(JsonUtil.toJson(list).getBytes()))
                                                                    .build();

    ResponseMapper<SendMessageResponse> responseMapper = DefaultResponseMapper.extend(SendMessageResponse.class)
                                                                              .withResponseMapper((status, body, getHeader, unidentified) -> {
                                                                                SendMessageResponse sendMessageResponse = Util.isEmpty(body) ? new SendMessageResponse(false, unidentified)
                                                                                                                                             : JsonUtil.fromJsonResponse(body, SendMessageResponse.class);
                                                                                sendMessageResponse.setSentUnidentfied(unidentified);
                                                                                return ServiceResponse.forResult(sendMessageResponse, status, body);
                                                                              })
                                                                              .withCustomError(404, (status, body, getHeader) -> new UnregisteredUserException(list.getDestination(), new NotFoundException("not found")))
                                                                              .build();

    return signalWebSocket.request(requestMessage, unidentifiedAccess, Optional.of(headers))//AA+ headers
                          .map(responseMapper::map)
                          .onErrorReturn(ServiceResponse::forUnknownError);
  }

  //AA todo port to us SenderKey semantics
  public Single<ServiceResponse<SendGroupMessageResponse>> sendToGroup(byte[] body, byte[] joinedUnidentifiedAccess, long timestamp, boolean online) {
    List<String> headers = new LinkedList<String>() {{
      add("content-type:application/vnd.signal-messenger.mrm");
      add("Unidentified-Access-Key:" + Base64.encodeBytes(joinedUnidentifiedAccess));
    }};

    String path = String.format(Locale.US, "/v1/messages/multi_recipient?ts=%s&online=%s", timestamp, online);

    WebSocketRequestMessage requestMessage = WebSocketRequestMessage.newBuilder()
                                                                    .setId(new SecureRandom().nextLong())
                                                                    .setVerb("PUT")
                                                                    .setPath(path)
//                                                                    .addAllHeaders(headers)
                                                                    .setBody(ByteString.copyFrom(body))
                                                                    .build();

    return signalWebSocket.request(requestMessage, Optional.empty())//AA+ empty
                          .map(DefaultResponseMapper.extend(SendGroupMessageResponse.class)
                                                    .withCustomError(401, (status, errorBody, getHeader) -> new InvalidUnidentifiedAccessHeaderException())
                                                    .withCustomError(404, (status, errorBody, getHeader) -> new NotFoundException("At least one unregistered user in message send."))
                                                    .withCustomError(409, (status, errorBody, getHeader) -> {
                                                      GroupMismatchedDevices[] mismatchedDevices = JsonUtil.fromJsonResponse(errorBody, GroupMismatchedDevices[].class);
                                                      return new GroupMismatchedDevicesException(mismatchedDevices);
                                                    })
                                                    .withCustomError(410, (status, errorBody, getHeader) -> {
                                                      GroupStaleDevices[] staleDevices = JsonUtil.fromJsonResponse(errorBody, GroupStaleDevices[].class);
                                                      return new GroupStaleDevicesException(staleDevices);
                                                    })
                                                    .build()::map)
                          .onErrorReturn(ServiceResponse::forUnknownError);
  }

  public static class SendResponseProcessor<T> extends ServiceResponseProcessor<T> {
    public SendResponseProcessor(ServiceResponse<T> response) {
      super(response);
    }
  }
}