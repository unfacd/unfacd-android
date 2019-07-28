package org.whispersystems.signalservice.api.messages.calls;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

// extends
public class AnswerMessage extends UfsrvCallCommand {

  private final long   id;
  private final String description;

//  public AnswerMessage(long id, String description) {
//    this.id          = id;
//    this.description = description;
//  }

  public AnswerMessage(long id, String description, Recipient remoteRecipient, long groupFid) {
    //
    super(remoteRecipient, groupFid, CallCommand.CommandTypes.ANSWER);
    //
    this.id                   = id;
    this.description          = description;
  }

  public String getDescription() {
    return description;
  }

  public long getId() {
    return id;
  }
}