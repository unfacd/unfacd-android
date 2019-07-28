package org.thoughtcrime.securesms.mms;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.Collections;
import java.util.LinkedList;

public class OutgoingExpirationUpdateMessage extends OutgoingSecureMediaMessage {

  public OutgoingExpirationUpdateMessage(Recipient recipient, long sentTimeMillis, long expiresIn, String ufsrvCommandWireBody) {
    super(recipient, "", new LinkedList<Attachment>(), sentTimeMillis,
          ThreadDatabase.DistributionTypes.CONVERSATION, expiresIn, 0, null, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandWireBody);// support for ufsrvcommand. this meanto be string
  }

  //
  public OutgoingExpirationUpdateMessage(Recipient recipient, long sentTimeMillis, long expiresIn, SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire) {
    super(recipient, "", new LinkedList<Attachment>(), sentTimeMillis,
            ThreadDatabase.DistributionTypes.CONVERSATION, expiresIn, 0, null, Collections.emptyList(),
          Collections.emptyList(),
            ufsrvCommandWire);// ufsrvcommand
  }
  //

  @Override
  public boolean isExpirationUpdate() {
    return true;
  }

}
