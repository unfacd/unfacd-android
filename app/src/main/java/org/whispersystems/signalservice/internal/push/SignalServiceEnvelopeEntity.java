package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

//AA+  suport for Ufrsv command messages
//AA represents Envelope

public class SignalServiceEnvelopeEntity {
  
  @JsonProperty
  private int type;

  @JsonProperty
  private String relay;

  @JsonProperty
  private long timestamp;

  @JsonProperty
  private String source;

  @JsonProperty
  private String sourceUuid;

  @JsonProperty
  private int sourceDevice;

  @JsonProperty
  private byte[] message;

  @JsonProperty
  private byte[] content;

  @JsonProperty
  private long serverTimestamp;

  @JsonProperty
  private String guid;

  //AA+
  @JsonProperty
  private byte[] fenceCommand;
  @JsonProperty
  private byte[] msgCommand;
  @JsonProperty
  private byte[] userCommand;
  @JsonProperty
  private byte[] ufsrvMessage;
  @JsonProperty
  private int ufsrvType;
  //



  public SignalServiceEnvelopeEntity() {}

  public int getType() {
    return type;
  }

  public String getRelay() {
    return relay;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public String getSourceE164() {
    return source;
  }

  public int getSourceDevice() {
    return sourceDevice;
  }

  public byte[] getMessage() {
    return message;
  }

  public byte[] getContent() {
    return content;
  }

  public long getServerTimestamp() {
    return serverTimestamp;
  }

  public String getSourceUuid() {
    return sourceUuid;
  }

  public boolean hasSource() {
    return sourceUuid != null;
  }

  public String getServerUuid() {
    return guid;
  }

  //AA+

  public byte[] getFenceCommand ()
  {
    return fenceCommand;
  }

  public byte[] getMsgCommand ()
  {
    return msgCommand;
  }

  public byte[] getUserCommand ()
  {
    return userCommand;
  }

  public byte[] getUfsrvMessage ()
  {
    return ufsrvMessage;
  }

  public int getUfsrvType ()
  {
    return ufsrvType;
  }
  //
}
