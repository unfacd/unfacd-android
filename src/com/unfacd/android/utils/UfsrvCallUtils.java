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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.WebRtcCallService;

import org.whispersystems.libsignal.util.guava.Optional;
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

import java.util.LinkedList;
import java.util.List;

public class UfsrvCallUtils
{
  private static final String TAG = UfsrvCallUtils.class.getSimpleName();

  public static @Nullable
  Long processUfsrvCallCommand(@NonNull Context context,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId,
                                  boolean outgoing) throws MmsException
  {
    GroupDatabase groupDatabase     = DatabaseFactory.getGroupDatabase(context);
    CallCommand   callCommand       = message.getUfsrvCommand().getCallCommand();

    if (callCommand==null) {
      Log.e(TAG, String.format("processUfsrvCallCommand (%d): CallCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!callCommand.hasFence() && !callCommand.hasOriginator()) {
      Log.e(TAG, String.format("processUfsrvCallCommand: CallCommand doesnt have key fields provided (fence:'%b', originator:'%b', to:'%b')", callCommand.hasFence(), callCommand.hasOriginator(), callCommand.getToCount()>0));
      return Long.valueOf(-1L);
    }

    FenceRecord fenceRecord           =  callCommand.getFence();
    long        groupId               = callCommand.getFence().getFid();
    Recipient   recipientOriginator   = Recipient.from(ApplicationContext.getInstance(),
                                                       Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(callCommand.getOriginator().getUfsrvuid().toByteArray())),
                                                       false);
    Log.d(TAG, String.format("processUfsrvCallCommand: CallCommand Received (fence:'%d', originator:'%b', command:'%d')", groupId, callCommand.hasOriginator(), callCommand.getHeader().getCommand()));

    switch (callCommand.getHeader().getCommand())
    {
      case CallCommand.CommandTypes.OFFER_VALUE:
        return processCallCommandOffer(context, envelope, message, recipientOriginator, smsMessageId);

      case CallCommand.CommandTypes.ANSWER_VALUE:
        return processCallCommandAnswer(context, envelope, message, recipientOriginator);

      case CallCommand.CommandTypes.HANGUP_VALUE:
        return processCallCommandHangUp(context, envelope, message, recipientOriginator, smsMessageId);

      case CallCommand.CommandTypes.BUSY_VALUE:
        return processCallCommandBusy(context, envelope, message, recipientOriginator);

      case CallCommand.CommandTypes.ICE_UPDATE_VALUE:
        return processCallCommandIceUpdate(context, envelope, message, recipientOriginator);
      default:
        Log.d(TAG, String.format("processUfsrvCallCommand (eid:'%d', type:'%d'): Received UKNOWN CALL COMMAND TYPE: fid:'%d'", callCommand.getHeader().getEid(), callCommand.getHeader().getCommand(), fenceRecord.getFid()));
    }
    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processCallCommandOffer(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Recipient recipientOriginator,
                                @NonNull Optional<Long> smsMessageId)
  {
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();
    CallCommand.Offer offerContent  = callCommand.getOffer();

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    SignalServiceCallMessage  callMessage;
    callMessage =  SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getId(), offerContent.getDescription(), recipientOriginator, fenceRecord.getFid()));

    //handleCallOfferMessage(envelope, message.getOfferMessage().get(), smsMessageId);
    Log.w(TAG, String.format("processCallCommandOffer... on fid:'%d", fenceRecord.getFid()));
    if (smsMessageId.isPresent()) {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      database.markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_INCOMING_CALL);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callCommand.getOffer().getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, recipientOriginator.getAddress());
      intent.putExtra(WebRtcCallService.EXTRA_FID, fenceRecord.getFid());//
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, callCommand.getOffer().getDescription());
      intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, envelope.getTimestamp());
      context.startService(intent);
    }

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processCallCommandAnswer(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Recipient recipientOriginator)
  {
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Answer answerContent = callCommand.getAnswer();
    SignalServiceCallMessage  callMessage;
    callMessage =  SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getId(), answerContent.getDescription(), recipientOriginator, fenceRecord.getFid()));

    // handleCallAnswerMessage(envelope, message.getAnswerMessage().get());
    Log.w(TAG, String.format("processCallCommandAnswer on fid:'%d'...", fenceRecord.getFid()));
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callCommand.getAnswer().getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, recipientOriginator.getAddress());
    intent.putExtra(WebRtcCallService.EXTRA_FID, fenceRecord.getFid());//
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, callCommand.getAnswer().getDescription());
    context.startService(intent);

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processCallCommandHangUp(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Recipient recipientOriginator,
                                @NonNull Optional<Long> smsMessageId)
  {
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Hangup hangup = callCommand.getHangup();
    SignalServiceCallMessage  callMessage;
    callMessage =  SignalServiceCallMessage.forHangup(new HangupMessage(hangup.getId(), recipientOriginator, fenceRecord.getFid()));

    //handleCallHangupMessage(envelope, message.getHangupMessage().get(), smsMessageId);
    Log.w(TAG, String.format("processCallCommandHangUp on fid:'%d'", fenceRecord.getFid()));
    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callCommand.getHangup().getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, recipientOriginator.getAddress());
      intent.putExtra(WebRtcCallService.EXTRA_FID, fenceRecord.getFid());//
      context.startService(intent);
    }

    return Long.valueOf(-1);
  }

  // not tested https://github.com/WhisperSystems/Signal-Android/commit/b50a3fa2b80fc26d214596b4331724b4922b565e
  private static @Nullable
  Long processCallCommandBusy(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Recipient recipientOriginator)
  {
    GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    CallCommand callCommand         = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord         = callCommand.getFence();

    //based off  private SignalServiceCallMessage createCallMessage(CallCommand content)
    CallCommand.Busy busy = callCommand.getBusy();
    SignalServiceCallMessage  callMessage;
    callMessage =  SignalServiceCallMessage.forBusy(new BusyMessage(busy.getId(), recipientOriginator, fenceRecord.getFid()));

    //handleCallBusyMessage(..)
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_REMOTE_BUSY);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, busy.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, recipientOriginator.getAddress());
    intent.putExtra(WebRtcCallService.EXTRA_FID, fenceRecord.getFid());//
    context.startService(intent);

//    EncryptingSmsDatabase database   = DatabaseFactory.getEncryptingSmsDatabase(context);
//
//    Recipients recipients  = groupDatabase.getGroupRecipient(fenceRecord.getFid());//we retrieve
//
//    Pair<Long, Long> messageAndThreadIdPair   = null;
//    Optional<MessagingDatabase.InsertResult> messageAndThreadId = null;
//
//
//    if (callCommand.getMainHeader().getEid()>0)  threadDatabase.updateEidByFid(fenceRecord.getFid(), callCommand.getMainHeader().getEid());
//
//    if (messageAndThreadId.isPresent())
//    {
//      MessageNotifier.updateNotification(context, masterSecret.getMasterSecret().orNull(), messageAndThreadId.get().getThreadId());
//
//      return messageAndThreadId.get().getMessageId();
//    }

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processCallCommandIceUpdate(@NonNull Context context,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Recipient recipientOriginator)
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    CallCommand callCommand = message.getUfsrvCommand().getCallCommand();
    FenceRecord fenceRecord = callCommand.getFence();

    if (callCommand.getIceUpdateCount() > 0)
    {
      List<IceUpdateMessage> iceUpdates = new LinkedList<>();

      for (CallCommand.IceUpdate iceUpdate : callCommand.getIceUpdateList())
      {
        iceUpdates.add(new IceUpdateMessage(iceUpdate.getId(), iceUpdate.getSdpMid(), iceUpdate.getSdpMLineIndex(), iceUpdate.getSdp(), recipientOriginator, fenceRecord.getFid()));
      }

      SignalServiceCallMessage callMessage;
      callMessage = SignalServiceCallMessage.forIceUpdates(iceUpdates);

      //handleCallIceUpdateMessage(envelope, message.getIceUpdateMessages().get());
      Log.w(TAG, String.format("processCallCommandIceUpdate size'%d' on fid:'%d'... ", iceUpdates.size(), fenceRecord.getFid()));
      for (IceUpdateMessage iceMessage : iceUpdates) {
        Intent intent = new Intent(context, WebRtcCallService.class);
        intent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
        intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, iceMessage.getId());
        intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, recipientOriginator.getAddress());
        intent.putExtra(WebRtcCallService.EXTRA_FID, fenceRecord.getFid());//
        intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP, iceMessage.getSdp());
        intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_MID, iceMessage.getSdpMid());
        intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX, iceMessage.getSdpMLineIndex());
        context.startService(intent);
      }
    }

    return Long.valueOf(-1);
  }

}
