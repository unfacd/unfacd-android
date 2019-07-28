package org.whispersystems.signalservice.api.messages.calls;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

import java.util.LinkedList;
import java.util.List;

// ufsrv
public class SignalServiceCallMessage {

  private final Optional<OfferMessage>           offerMessage;
  private final Optional<AnswerMessage>          answerMessage;
  private final Optional<HangupMessage>          hangupMessage;
  private final Optional<BusyMessage>            busyMessage;
  private final Optional<List<IceUpdateMessage>> iceUpdateMessages;

  //
  private  CallCommand.Builder callCommandBuilder;

  private SignalServiceCallMessage(Optional<OfferMessage> offerMessage,
                                   Optional<AnswerMessage> answerMessage,
                                   Optional<List<IceUpdateMessage>> iceUpdateMessages,
                                   Optional<HangupMessage> hangupMessage,
                                   Optional<BusyMessage> busyMessage,
                                   CallCommand.Builder callCommandBuilder) // callcommand
  {
    this.offerMessage      = offerMessage;
    this.answerMessage     = answerMessage;
    this.iceUpdateMessages = iceUpdateMessages;
    this.hangupMessage     = hangupMessage;
    this.busyMessage       = busyMessage;

    //
    this.callCommandBuilder = callCommandBuilder;
  }



  public static SignalServiceCallMessage forOffer(OfferMessage offerMessage) {

    return new SignalServiceCallMessage(Optional.of(offerMessage),
            Optional.<AnswerMessage>absent(),
            Optional.<List<IceUpdateMessage>>absent(),
            Optional.<HangupMessage>absent(),
            Optional.<BusyMessage>absent(),
            offerMessage.getCallCommandBuilder());//
  }

  public static SignalServiceCallMessage forAnswer(AnswerMessage answerMessage) {
    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.of(answerMessage),
            Optional.<List<IceUpdateMessage>>absent(),
            Optional.<HangupMessage>absent(),
            Optional.<BusyMessage>absent(),
            answerMessage.getCallCommandBuilder());//
  }

  public static SignalServiceCallMessage forIceUpdates(List<IceUpdateMessage> iceUpdateMessages) {
    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.<AnswerMessage>absent(),
            Optional.of(iceUpdateMessages),
            Optional.<HangupMessage>absent(),
            Optional.<BusyMessage>absent(),
            iceUpdateMessages.get(0).getCallCommandBuilder());// todo: need to look as we arbitrarily fetch first in list
  }

  public static SignalServiceCallMessage forIceUpdate(final IceUpdateMessage iceUpdateMessage) {
    List<IceUpdateMessage> iceUpdateMessages = new LinkedList<>();
    iceUpdateMessages.add(iceUpdateMessage);

    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.<AnswerMessage>absent(),
            Optional.of(iceUpdateMessages),
            Optional.<HangupMessage>absent(),
            Optional.<BusyMessage>absent(),
            iceUpdateMessage.getCallCommandBuilder());//
  }

  public static SignalServiceCallMessage forHangup(HangupMessage hangupMessage) {
    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.<AnswerMessage>absent(),
            Optional.<List<IceUpdateMessage>>absent(),
            Optional.of(hangupMessage),
            Optional.<BusyMessage>absent(),
            hangupMessage.getCallCommandBuilder());//
  }

  public static SignalServiceCallMessage forBusy(BusyMessage busyMessage) {
    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.<AnswerMessage>absent(),
            Optional.<List<IceUpdateMessage>>absent(),
            Optional.<HangupMessage>absent(),
            Optional.of(busyMessage),
            busyMessage.getCallCommandBuilder());//
  }


  public static SignalServiceCallMessage empty() {
    return new SignalServiceCallMessage(Optional.<OfferMessage>absent(),
            Optional.<AnswerMessage>absent(),
            Optional.<List<IceUpdateMessage>>absent(),
            Optional.<HangupMessage>absent(),
            Optional.<BusyMessage>absent(),
            null);//
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

  //
  public CallCommand.Builder getCallCommandBuilder ()
  {
    return callCommandBuilder;
  }
  //
}