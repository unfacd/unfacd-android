package org.whispersystems.signalservice.api.messages.calls;


import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

public class AnswerMessage extends UfsrvCallCommand {//AA+ extends

  private final long   id;
  private final String sdp;
  private final byte[] opaque;

  public AnswerMessage(long id, String sdp, byte[] opaque, CallCommand.Builder callCommandOrBuilder) {//AA+
    super(callCommandOrBuilder);//AA+

    this.id     = id;
    this.sdp    = sdp;
    this.opaque = opaque;
  }

  public String getSdp() {
    return sdp;
  }

  public long getId() {
    return id;
  }

  public byte[] getOpaque() {
    return opaque;
  }
}