package org.thoughtcrime.securesms.sms;

import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import androidx.annotation.NonNull;

public class OutgoingTextMessage {

  private final Recipient recipient;
  private final String    message;
  private final int       subscriptionId;
  private final long      expiresIn;

  private SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire;

  public OutgoingTextMessage(Recipient recipient, String message, int subscriptionId) {
    this(recipient, message, 0, subscriptionId);
  }

  public OutgoingTextMessage(Recipient recipient, String message, long expiresIn, int subscriptionId) {
    this.recipient      = recipient;
    this.message        = message;
    this.expiresIn      = expiresIn;
    this.subscriptionId = subscriptionId;

    this.ufsrvCommandWire = null; //AA+
  }

  protected OutgoingTextMessage(OutgoingTextMessage base, String body) {
    this.recipient     = base.getRecipient();
    this.subscriptionId = base.getSubscriptionId();
    this.expiresIn      = base.getExpiresIn();
    this.message        = body;

    this.ufsrvCommandWire = null;//AA+
  }

  public @NonNull OutgoingTextMessage withExpiry(long expiresIn) {
    return new OutgoingTextMessage(recipient, message, expiresIn, subscriptionId);
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public String getMessageBody() {
    return message;
  }

  public Recipient getRecipient() {
    return recipient;
  }

  public boolean isKeyExchange() {
    return false;
  }

  public boolean isSecureMessage() {
    return false;
  }

  public boolean isEndSession() {
    return false;
  }

  public boolean isPreKeyBundle() {
    return false;
  }

  //AA+
  public SignalServiceProtos.UfsrvCommandWire getUfsrvCommandWire ()
  {
    return ufsrvCommandWire;
  }

  public boolean isIdentityVerified() {
    return false;
  }

  public boolean isIdentityDefault() {
   return false;
 }

  public static OutgoingTextMessage from(SmsMessageRecord record) {
    if (record.isSecure()) {
      return new OutgoingEncryptedMessage(record.getRecipient(), record.getBody(), record.getExpiresIn());
    } else if (record.isKeyExchange()) {
      return new OutgoingKeyExchangeMessage(record.getRecipient(), record.getBody());
    } else if (record.isEndSession()) {
      return new OutgoingEndSessionMessage(new OutgoingTextMessage(record.getRecipient(), record.getBody(), 0, -1));
    } else {
      return new OutgoingTextMessage(record.getRecipient(), record.getBody(), record.getExpiresIn(), record.getSubscriptionId());
    }
  }

  public OutgoingTextMessage withBody(String body) {
    return new OutgoingTextMessage(this, body);
  }
}
