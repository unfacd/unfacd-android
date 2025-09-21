package org.whispersystems.signalservice.api.messages;


import com.unfacd.android.utils.UfsrvMessageUtils;

import java.util.List;

public class SignalServiceReceiptMessage {

  public enum Type {
    UNKNOWN, DELIVERY, READ, VIEWED
  }

  private final Type       type;
  private final List<Long> timestamps;
  private final long       when;

  //AA+ this includes List<Long> timestamps;
  private final List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers;

  public SignalServiceReceiptMessage(Type type, List<Long> timestamps, long when, List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers) {
    this.type       = type;
    this.timestamps = timestamps;
    this.when       = when;

    //AA+
    this.ufsrvMessageIdentifiers  = ufsrvMessageIdentifiers;
  }

  //AA+
  public List<UfsrvMessageUtils.UfsrvMessageIdentifier> getUfsrvMessageIdentifiers () {
    return ufsrvMessageIdentifiers;
  }

  public Type getType() {
    return type;
  }

  public List<Long> getTimestamps() {
    return timestamps;
  }

  public long getWhen() {
    return when;
  }

  public boolean isDeliveryReceipt() {
    return type == Type.DELIVERY;
  }

  public boolean isReadReceipt() {
    return type == Type.READ;
  }

  public boolean isViewedReceipt() {
    return type == Type.VIEWED;
  }
}