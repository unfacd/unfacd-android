package org.thoughtcrime.securesms.sms;


import org.thoughtcrime.securesms.recipients.Recipient;

import androidx.annotation.NonNull;

public class OutgoingEncryptedMessage extends OutgoingTextMessage {

  public OutgoingEncryptedMessage(Recipient recipient, String body, long expiresIn) {
    super(recipient, body, expiresIn, -1);
  }

  private OutgoingEncryptedMessage(OutgoingEncryptedMessage base, String body) {
    super(base, body);
  }

  @Override
  public boolean isSecureMessage() {
    return true;
  }

  @Override
  public @NonNull OutgoingTextMessage withExpiry(long expiresIn) {
    return new OutgoingEncryptedMessage(getRecipient(), getMessageBody(), expiresIn);
  };

  @Override
  public OutgoingTextMessage withBody(String body) {
    return new OutgoingEncryptedMessage(this, body);
  }
}
