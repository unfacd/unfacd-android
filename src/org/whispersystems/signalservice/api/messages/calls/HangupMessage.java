package org.whispersystems.signalservice.api.messages.calls;


import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

// extends
public class HangupMessage extends UfsrvCallCommand{

  private final long id;

  public HangupMessage(long id, Recipient remoteRecipient, long groupFid)
  {
    //
    super(remoteRecipient, groupFid, SignalServiceProtos.CallCommand.CommandTypes.HANGUP);
    //
    this.id = id;
  }

//  public HangupMessage(long id) {
//    this.id = id;
//  }

  public long getId() {
    return id;
  }
}