package org.whispersystems.signalservice.internal.push;

import java.util.Optional;

import java.util.List;

public final class SignalServiceMessagesResult {
  private final List<SignalServiceEnvelopeEntity> envelopes;
  private final long serverDeliveredTimestamp;
  private final Optional<List<SignalServiceProtos.Envelope>> envelopesRaw;//AA+

  SignalServiceMessagesResult(List<SignalServiceEnvelopeEntity> envelopes, long serverDeliveredTimestamp) {
    this.envelopes                = envelopes;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.envelopesRaw             = Optional.empty();//AA+
  }

  SignalServiceMessagesResult(Optional<List<SignalServiceProtos.Envelope>> envelopesRaw, long serverDeliveredTimestamp) {//AA+
    this.envelopes                = null;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.envelopesRaw             = envelopesRaw;
  }

  public List<SignalServiceEnvelopeEntity> getEnvelopes() {
    return envelopes;
  }

  public long getServerDeliveredTimestamp() {
    return serverDeliveredTimestamp;
  }

  //AA+
  public List<SignalServiceProtos.Envelope> getEnvelopesRaw() {//AA+
    return envelopesRaw.orElse(null);
  }
}