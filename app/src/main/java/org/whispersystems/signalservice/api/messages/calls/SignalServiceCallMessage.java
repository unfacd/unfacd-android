package org.whispersystems.signalservice.api.messages.calls;

import java.util.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

import java.util.LinkedList;
import java.util.List;

//AA+ ufsrv
public class SignalServiceCallMessage {

  private final Optional<OfferMessage>           offerMessage;
  private final Optional<AnswerMessage>          answerMessage;
  private final Optional<HangupMessage>          hangupMessage;
  private final Optional<BusyMessage>            busyMessage;
  private final Optional<List<IceUpdateMessage>> iceUpdateMessages;
  private final Optional<OpaqueMessage>          opaqueMessage;
  private final Optional<Integer>                destinationDeviceId;
  private final boolean                          isMultiRing;
  private final Optional<byte[]>                 groupId;
  private final Optional<Long>                   timestamp;

  //AA+
  private  CallCommand.Builder callCommandBuilder;

  private SignalServiceCallMessage(Optional<OfferMessage> offerMessage,
                                   Optional<AnswerMessage> answerMessage,
                                   Optional<List<IceUpdateMessage>> iceUpdateMessages,
                                   Optional<HangupMessage> hangupMessage,
                                   Optional<BusyMessage> busyMessage,
                                   Optional<OpaqueMessage> opaqueMessage,
                                   boolean isMultiRing,
                                   Optional<Integer> destinationDeviceId,
                                   CallCommand.Builder callCommandBuilder) //AA+ call command
  {
    this(offerMessage, answerMessage, iceUpdateMessages, hangupMessage, busyMessage, opaqueMessage, isMultiRing, destinationDeviceId, Optional.empty(), Optional.empty(), callCommandBuilder);
  }

  private SignalServiceCallMessage(Optional<OfferMessage> offerMessage,
                                   Optional<AnswerMessage> answerMessage,
                                   Optional<List<IceUpdateMessage>> iceUpdateMessages,
                                   Optional<HangupMessage> hangupMessage,
                                   Optional<BusyMessage> busyMessage,
                                   Optional<OpaqueMessage> opaqueMessage,
                                   boolean isMultiRing,
                                   Optional<Integer> destinationDeviceId,
                                   Optional<byte[]> groupId,
                                   Optional<Long> timestamp,
                                   CallCommand.Builder callCommandBuilder) //AA+ call command
  {
    this.offerMessage        = offerMessage;
    this.answerMessage       = answerMessage;
    this.iceUpdateMessages   = iceUpdateMessages;
    this.hangupMessage       = hangupMessage;
    this.busyMessage         = busyMessage;
    this.opaqueMessage       = opaqueMessage;
    this.isMultiRing         = isMultiRing;
    this.destinationDeviceId = destinationDeviceId;
    this.groupId             = groupId;
    this.timestamp           = timestamp;

    //AA+
    this.callCommandBuilder = callCommandBuilder;
  }

  public static SignalServiceCallMessage forOffer(OfferMessage offerMessage, boolean isMultiRing, Integer destinationDeviceId) {

    return new SignalServiceCallMessage(Optional.of(offerMessage),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        offerMessage.getCallCommandBuilder());//AA+
  }

  public static SignalServiceCallMessage forAnswer(AnswerMessage answerMessage, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.of(answerMessage),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        answerMessage.getCallCommandBuilder());//AA+
  }

  public static SignalServiceCallMessage forIceUpdates(List<IceUpdateMessage> iceUpdateMessages, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(iceUpdateMessages),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        iceUpdateMessages.get(0).getCallCommandBuilder());//AA+ todo: need to look as we arbitrarily fetch first in list
  }

  public static SignalServiceCallMessage forIceUpdate(final IceUpdateMessage iceUpdateMessage, boolean isMultiRing, Integer destinationDeviceId) {
    List<IceUpdateMessage> iceUpdateMessages = new LinkedList<>();
    iceUpdateMessages.add(iceUpdateMessage);

    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(iceUpdateMessages),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        iceUpdateMessage.getCallCommandBuilder());//AA+
  }

  public static SignalServiceCallMessage forHangup(HangupMessage hangupMessage, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(hangupMessage),
                                        Optional.empty(),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        hangupMessage.getCallCommandBuilder());//AA+
  }

  public static SignalServiceCallMessage forBusy(BusyMessage busyMessage, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(busyMessage),
                                        Optional.empty(),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        busyMessage.getCallCommandBuilder());//AA+
  }

  public static SignalServiceCallMessage forOpaque(OpaqueMessage opaqueMessage, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(opaqueMessage),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        opaqueMessage.getCallCommandBuilder());//AA+
  }


  public static SignalServiceCallMessage forOutgoingGroupOpaque(byte[] groupId, long timestamp, OpaqueMessage opaqueMessage, boolean isMultiRing, Integer destinationDeviceId) {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(opaqueMessage),
                                        isMultiRing,
                                        Optional.ofNullable(destinationDeviceId),
                                        Optional.of(groupId),
                                        Optional.of(timestamp),
                                        opaqueMessage.getCallCommandBuilder());//AA+
  }


  public static SignalServiceCallMessage empty() {
    return new SignalServiceCallMessage(Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty(),false,
                                        Optional.empty(),
                                        null);//AA+
  }

  public Optional<OpaqueMessage> getOpaqueMessage() {
    return opaqueMessage;
  }

  public boolean isMultiRing() {
    return isMultiRing;
  }

  public Optional<Integer> getDestinationDeviceId() {
    return destinationDeviceId;
  }

  public Optional<byte[]> getGroupId() {
    return groupId;
  }

  public Optional<Long> getTimestamp() {
    return timestamp;
  }

  public Optional<List<IceUpdateMessage>> getIceUpdateMessages() {
    return iceUpdateMessages;
  }

  public Optional<AnswerMessage> getAnswerMessage() {
    return answerMessage;
  }

  public Optional<OfferMessage> getOfferMessage() {
    return offerMessage;
  }

  public Optional<HangupMessage> getHangupMessage() {
    return hangupMessage;
  }

  public Optional<BusyMessage> getBusyMessage() {
    return busyMessage;
  }

  //AA+
  public CallCommand.Builder getCallCommandBuilder ()
  {
    return callCommandBuilder;
  }
  //
}