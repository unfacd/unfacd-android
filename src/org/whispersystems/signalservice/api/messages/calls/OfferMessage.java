package org.whispersystems.signalservice.api.messages.calls;


import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

//= extends
public class OfferMessage extends UfsrvCallCommand{

  private final long   id;
  private final String description;

  public OfferMessage(long id, String description, Recipient remoteRecipient, long groupFid) {// group+recipient
    //
    super(remoteRecipient, groupFid, CallCommand.CommandTypes.OFFER);
    //

    this.id          = id;
    this.description = description;

  }

  public String getDescription() {
    return description;
  }

  public long getId() {
    return id;
  }
}