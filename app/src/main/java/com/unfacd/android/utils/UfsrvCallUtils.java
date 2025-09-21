/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.utils;

import android.content.Context;
import android.content.Intent;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.ringrtc.CallId;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.WebRtcCallService;
import org.thoughtcrime.securesms.service.webrtc.WebRtcData;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UfsrvCallUtils
{
  private static final String TAG = Log.tag(UfsrvCallUtils.class);

  public static @Nullable
  Long processUfsrvCallCommand(@NonNull Context context,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId,
                                  boolean outgoing) throws MmsException
  {
    GroupDatabase groupDatabase     = SignalDatabase.groups();
    CallCommand   callCommand       = message.getUfsrvCommand().getCallCommand();

    if (callCommand == null) {
       Log.e(TAG, String.format(Locale. getDefault(), "processUfsrvCallCommand (%d): CallCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!callCommand.hasFence() && !callCommand.hasOriginator()) {
       Log.e(TAG, String.format(Locale. getDefault(), "processUfsrvCallCommand: CallCommand doesnt have key fields provided (fence:'%b', originator:'%b', to:'%b')", callCommand.hasFence(), callCommand.hasOriginator(), callCommand.getToCount()>0));
      return Long.valueOf(-1L);
    }

    if (callCommand.hasDestinationDeviceId() && callCommand.getDestinationDeviceId() != 1) {
      Log.i(TAG, String.format(Locale.US, "Ignoring call message that is not for this device! intended: %d, this: %d", callCommand.getDestinationDeviceId(), 1));
      return Long.valueOf(-1L);
    }

    FenceRecord fenceRecord           =  callCommand.getFence();
    long        fid                   = callCommand.getFence().getFid();
    Recipient   recipientGroup        = Recipient.resolved(RecipientUfsrvId.from(fid));
    Recipient   recipientOriginator   = Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(callCommand.getOriginator().getUfsrvuid().toByteArray())).serialize()).get();
    Log.d(TAG, String.format("processUfsrvCallCommand: CallCommand Received (fence:'%d', originator:'%b', command:'%d')", fid, callCommand.hasOriginator(), callCommand.getHeader().getCommand()));

    switch (callCommand.getHeader().getCommand())
    {
      case CallCommand.CommandTypes.OFFER_VALUE:
        return processCallCommandOffer(context, envelope, message, recipientGroup, recipientOriginator, smsMessageId);

      case CallCommand.CommandTypes.ANSWER_VALUE:
        return processCallCommandAnswer(context, envelope, message, recipientGroup, recipientOriginator);

      case CallCommand.CommandTypes.HANGUP_VALUE:
        return processCallCommandHangUp(context, envelope, message, recipientGroup, recipientOriginator, smsMessageId);

      case CallCommand.CommandTypes.BUSY_VALUE:
        return processCallCommandBusy(context, envelope, message, recipientGroup, recipientOriginator);

      case CallCommand.CommandTypes.ICE_UPDATE_VALUE:
        return processCallCommandIceUpdate(context, envelope, message, recipientGroup, recipientOriginator);

      case CallCommand.CommandTypes.OPAQUE_MESSAGE_VALUE:
        return processCallCommandOpaqueMessage(context, envelope, message, recipientGroup, recipientOriginator);

      default:
        Log.d(TAG, String.format("processUfsrvCallCommand (eid:'%d', type:'%d'): Received UKNOWN CALL COMMAND TYPE: fid:'%d'", callCommand.getHeader().getEid(), callCommand.getHeader().getCommand(), fenceRecord.getFid()));
    }
    return (long) -1;
  }

  //PORTING NOTE off handleCallOfferMessage
  private static @Nullable
  Long processCallCommandOffer(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Recipient recipientGroup,
                               @NonNull Recipient recipientOriginator,
                               @NonNull Optional<Long> smsMessageId)
  {
    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();
    CallCommand.Offer offerContent  = callCommand.getOffer();

    boolean isMultiRing         = callCommand.getMultiRing();
    Integer destinationDeviceId = callCommand.hasDestinationDeviceId() ? callCommand.getDestinationDeviceId() : null;

    //based off  private SignalServiceCallMessage handleCallOfferMessage((CallCommand content)
    SignalServiceCallMessage  callMessage;
    CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(),  CallCommand.CommandTypes.OFFER, recipientGroup);
    callMessage = SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getId(), offerContent.hasSdp() ? offerContent.getSdp() : null, OfferMessage.Type.fromProto(offerContent.getType()), offerContent.hasOpaque() ? offerContent.getOpaque().toByteArray() : null, callCommandBuilder), isMultiRing, destinationDeviceId);
    Log.w(TAG, String.format("processCallCommandOffer... on fid:'%d", fenceRecord.getFid()));

    if (smsMessageId.isPresent()) {
      MessageDatabase database = SignalDatabase.sms();
      database.markAsMissedCall(smsMessageId.get(), OfferMessage.Type.fromProto(offerContent.getType()) == OfferMessage.Type.VIDEO_CALL);//AA+ type extraction method
    } else {
      Recipient  recipient         = recipientOriginator;//Recipient.externalHighTrustPush(context, content.getSender());
      RemotePeer remotePeer        = new RemotePeer(recipientGroup.getId(), new CallId(offerContent.getId()), -1);
      byte[]     remoteIdentityKey = ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecord(recipient.getId()).map(record -> record.getIdentityKey().serialize()).orElse(null);

      ApplicationDependencies.getSignalCallManager()
                             .receivedOffer(new WebRtcData.CallMetadata(remotePeer,  envelope.getSourceDevice()),
                                            new WebRtcData.OfferMetadata(offerContent.getOpaque().toByteArray(), offerContent.getSdp(), callMessage.getOfferMessage().get().getType()),
                                            new WebRtcData.ReceivedOfferMetadata(remoteIdentityKey,
                                                                                 envelope.getServerReceivedTimestamp(),
                                                                                 envelope.getServerDeliveredTimestamp(),
                                                                                 isMultiRing));
    }

    return (long) -1;
  }

  private static @Nullable
  Long processCallCommandAnswer(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Recipient recipientGroup,
                                @NonNull Recipient recipientOriginator)
  {
    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    boolean isMultiRing         = callCommand.getMultiRing();
    Integer destinationDeviceId = callCommand.hasDestinationDeviceId() ? callCommand.getDestinationDeviceId() : null;

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Answer answerContent = callCommand.getAnswer();
    CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(),  CallCommand.CommandTypes.ANSWER, recipientGroup);
    SignalServiceCallMessage  callMessage = SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getId(), answerContent.hasSdp() ? answerContent.getSdp() : null, answerContent.hasOpaque() ? answerContent.getOpaque().toByteArray() : null, callCommandBuilder), isMultiRing, destinationDeviceId);
    Log.w(TAG, String.format("processCallCommandAnswer on fid:'%d'...", fenceRecord.getFid()));

    RemotePeer remotePeer        = new RemotePeer(recipientGroup.getId(), new CallId(answerContent.getId()), fenceRecord.getFid());
    byte[]     remoteIdentityKey = ApplicationDependencies.getProtocolStore().aci().identities().getIdentityRecord(recipientOriginator.getId()).map(record -> record.getIdentityKey().serialize()).orElse(null);

    ApplicationDependencies.getSignalCallManager()
                           .receivedAnswer(new WebRtcData.CallMetadata(remotePeer, envelope.getSourceDevice()),
                                           new WebRtcData.AnswerMetadata(answerContent.getOpaque().toByteArray(), answerContent.getSdp()),
                                           new WebRtcData.ReceivedAnswerMetadata(remoteIdentityKey, isMultiRing));

    return (long) -1;
  }

  private static @Nullable
  Long processCallCommandHangUp(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Recipient recipientGroup,
                                @NonNull Recipient recipientOriginator,
                                @NonNull Optional<Long> smsMessageId)
  {
    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    boolean isMultiRing         = callCommand.getMultiRing();
    Integer destinationDeviceId = callCommand.hasDestinationDeviceId() ? callCommand.getDestinationDeviceId() : null;

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Hangup hangupContent = callCommand.hasLegacyHangup() ? callCommand.getLegacyHangup() : callCommand.getHangup();
    CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(),  CallCommand.CommandTypes.HANGUP, recipientGroup);
    SignalServiceCallMessage  callMessage =  SignalServiceCallMessage.forHangup(new HangupMessage(hangupContent.getId(), HangupMessage.Type.fromProto(hangupContent.getType()), hangupContent.getDeviceId(), callCommand.hasLegacyHangup(), callCommandBuilder), isMultiRing, destinationDeviceId);

    Log.w(TAG, String.format("processCallCommandHangUp on fid:'%d'", fenceRecord.getFid()));
    if (smsMessageId.isPresent()) {
      SignalDatabase.sms().markAsMissedCall(smsMessageId.get(), false);
    } else {
      RemotePeer remotePeer = new RemotePeer(recipientGroup.getId(), new CallId(hangupContent.getId()), -1);

      ApplicationDependencies.getSignalCallManager()
                             .receivedCallHangup(new WebRtcData.CallMetadata(remotePeer, envelope.getSourceDevice()),
                                                 new WebRtcData.HangupMetadata(callMessage.getHangupMessage().get().getType(), callMessage.getHangupMessage().get().isLegacy(), callMessage.getDestinationDeviceId().get()));
    }

    return (long) -1;
  }

  //AA+ not tested https://github.com/WhisperSystems/Signal-Android/commit/b50a3fa2b80fc26d214596b4331724b4922b565e
  private static @Nullable
  Long processCallCommandBusy(@NonNull Context context,
                              @NonNull SignalServiceEnvelope envelope,
                              @NonNull SignalServiceDataMessage message,
                              @NonNull Recipient recipientGroup,
                              @NonNull Recipient recipientOriginator)
  {
    CallCommand callCommand     = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord     = callCommand.getFence();

    boolean isMultiRing         = callCommand.getMultiRing();
    Integer destinationDeviceId = callCommand.hasDestinationDeviceId() ? callCommand.getDestinationDeviceId() : null;

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Busy busy = callCommand.getBusy();
    CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(),  CallCommand.CommandTypes.BUSY, recipientGroup);
    SignalServiceCallMessage  callMessage =  SignalServiceCallMessage.forBusy(new BusyMessage(busy.getId(), callCommandBuilder), isMultiRing, destinationDeviceId);

    //handleCallBusyMessage(..)
    Intent     intent     = new Intent(context, WebRtcCallService.class);
    RemotePeer remotePeer = new RemotePeer(recipientGroup.getId(), new CallId(callCommand.getBusy().getId()), fenceRecord.getFid());

    ApplicationDependencies.getSignalCallManager()
                           .receivedCallBusy(new WebRtcData.CallMetadata(remotePeer, envelope.getSourceDevice()));

    return (long) -1;
  }

  private static @Nullable
  Long processCallCommandIceUpdate(@NonNull Context context,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @NonNull Recipient recipientGroup,
                                   @NonNull Recipient recipientOriginator)
  {
    CallCommand callCommand = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord = callCommand.getFence();

    List<IceUpdateMessage> messages = new LinkedList<>();
    for (CallCommand.IceUpdate iceUpdate : callCommand.getIceUpdateList()) {
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(),  CallCommand.CommandTypes.ICE_UPDATE, recipientGroup);
      messages.add(new IceUpdateMessage(iceUpdate.getId(), iceUpdate.hasOpaque() ? iceUpdate.getOpaque().toByteArray() : null, iceUpdate.hasSdp() ? iceUpdate.getSdp() : null, callCommandBuilder));
    }

    Log.w(TAG, String.format("processCallCommandIceUpdate on fid:'%d'", fenceRecord.getFid()));

    List<byte[]> iceCandidates = new ArrayList<>(messages.size());
    long         callId        = -1;

    for (IceUpdateMessage iceMessage : messages) {
      iceCandidates.add(iceMessage.getOpaque());
      callId = iceMessage.getId();
    }

    RemotePeer remotePeer = new RemotePeer(recipientGroup.getId(), new CallId(callId), fenceRecord.getFid());
    ApplicationDependencies.getSignalCallManager()
                           .receivedIceCandidates(new WebRtcData.CallMetadata(remotePeer, envelope.getSourceDevice()),
                                                  iceCandidates);

    return (long) -1;
  }

  static private Long processCallCommandOpaqueMessage(@NonNull Context context,
                                                      @NonNull SignalServiceEnvelope envelope,
                                                      @NonNull SignalServiceDataMessage message,
                                                      @NonNull Recipient recipientGroup,
                                                      @NonNull Recipient recipientOriginator)
  {
    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    long messageAgeSeconds = 0;
    if (envelope.getServerReceivedTimestamp() > 0 && envelope.getServerDeliveredTimestamp() >= envelope.getServerReceivedTimestamp()) {
      messageAgeSeconds = (envelope.getServerDeliveredTimestamp() - envelope.getServerReceivedTimestamp()) / 1000;
    }

    Log.w(TAG, String.format("processCallCommandOpaqueMessage on fid:'%d'", fenceRecord.getFid()));

    ApplicationDependencies.getSignalCallManager()
                           .receivedOpaqueMessage(new WebRtcData.OpaqueMessageMetadata(recipientOriginator.requireServiceId().uuid(),
                                                                                       callCommand.getOpaque().toByteArray(),
                                                                                       envelope.getSourceDevice(),
                                                                                       messageAgeSeconds));

    return (long) -1;
  }


  /**
   * Figure out the callee in a PairedGroup
   */
  static public Optional<Recipient> getCallableRecipient(GroupId groupId)
  {
    if (SignalDatabase.groups().isPairedGroup(groupId)) {
      List<Recipient> groupMembers = SignalDatabase.groups().getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_INCLUDING_SELF);
        for (Recipient callableRecipient : groupMembers) {
          if (!callableRecipient.requireAddress().toString().equals(Recipient.self().getUfsrvUid()))
            return Optional.of(callableRecipient);
        }
      }

    return Optional.empty();
  }
}
