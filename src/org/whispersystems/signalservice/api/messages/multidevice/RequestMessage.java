package org.whispersystems.signalservice.api.messages.multidevice;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage.Request;

public class RequestMessage {

  private final Request request;

  public RequestMessage(Request request) {
    this.request = request;
  }

  public boolean isContactsRequest() {
    return request.getType() == Request.Type.CONTACTS;
  }

  public boolean isGroupsRequest() {
    return request.getType() == Request.Type.GROUPS;
  }

  public boolean isBlockedListRequest() {
    return request.getType() == Request.Type.BLOCKED;
  }

  public boolean isConfigurationRequest() {
    return request.getType() == Request.Type.CONFIGURATION;
  }
}
