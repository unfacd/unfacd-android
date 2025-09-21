package org.whispersystems.signalservice.api.messages.calls;


import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

//AA+ extends
public class BusyMessage extends UfsrvCallCommand {

  private final long id;

  public BusyMessage(long id, SignalServiceProtos.CallCommand.Builder callCommandBuilder) {
    //AA+
    super(callCommandBuilder);
      this.id = id;
  }

//  public BusyMessage(long id) {
//    this.id = id;
//  }

  public long getId() {
    return id;
  }
}