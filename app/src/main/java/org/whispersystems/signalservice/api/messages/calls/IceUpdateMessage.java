package org.whispersystems.signalservice.api.messages.calls;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

//AA+ extends
public class IceUpdateMessage extends UfsrvCallCommand {

  private final long   id;
  private final byte[] opaque;
  private final String sdp;

  public IceUpdateMessage(long id, byte[] opaque, String sdp, CallCommand.Builder callCommandBuilder) {//AA+ last 2 args
    //AA+
    super(callCommandBuilder);

    this.id     = id;
    this.opaque = opaque;
    this.sdp    = sdp;
  }

  public byte[] getOpaque() {
    return opaque;
  }

  public String getSdp() {
    return sdp;
  }

  public long getId()
  {
    return id;
  }
}