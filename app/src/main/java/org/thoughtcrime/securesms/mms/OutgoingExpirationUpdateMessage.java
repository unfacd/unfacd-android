package org.thoughtcrime.securesms.mms;

import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.Collections;
import java.util.LinkedList;

public class OutgoingExpirationUpdateMessage extends OutgoingSecureMediaMessage {

  public OutgoingExpirationUpdateMessage(Recipient recipient, long sentTimeMillis, long expiresIn, String ufsrvCommandWireBody) {
    super(recipient,
          "",
          new LinkedList<>(),
          sentTimeMillis,
          ThreadDatabase.DistributionTypes.CONVERSATION,
          expiresIn,
          false,
          StoryType.NONE,
          null,
          false,
          null,
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          ufsrvCommandWireBody);//AA+ support for ufsrvcommand. this meant to be string
  }

  //AA+
  public OutgoingExpirationUpdateMessage(Recipient recipient, long sentTimeMillis, long expiresIn, SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire) {
    super(recipient,
          "",
          new LinkedList<>(),
          sentTimeMillis,
          ThreadDatabase.DistributionTypes.CONVERSATION,
          expiresIn,
          false,
          StoryType.NONE,
          null,
          false,
          null,
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          ufsrvCommandWire);//AA+ ufsrvcommand
  }
  //

  @Override
  public boolean isExpirationUpdate() {
    return true;
  }

}
