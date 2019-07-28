package org.whispersystems.signalservice.api.messages.calls;


import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

// extends
public class BusyMessage extends UfsrvCallCommand{

  private final long id;

  public BusyMessage(long id, Recipient remoteRecipient, long groupFid) {
    //
    super(remoteRecipient, groupFid, SignalServiceProtos.CallCommand.CommandTypes.BUSY);
      this.id = id;
  }

//  public BusyMessage(long id) {
//    this.id = id;
//  }

  public long getId() {
    return id;
  }
}