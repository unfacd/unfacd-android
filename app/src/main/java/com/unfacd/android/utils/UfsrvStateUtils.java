package com.unfacd.android.utils;


import android.content.Context;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.StateCommand;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UfsrvStateUtils {
  private static final String TAG = Log.tag(UfsrvStateUtils.class);

  public static @Nullable
  Long processUfsrvStateCommand(@NonNull Context context,
                                @NonNull SignalServiceContent content,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message) throws MmsException
  {
    StateCommand stateCommand = message.getUfsrvCommand().getStateCommand();

    if (stateCommand == null) {
      Log.e(TAG, String.format("processUfsrvStateCommand (%d): ReceiptCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!stateCommand.hasUidOriginator()) {
      Log.e(TAG, String.format("processUfsrvStateCommand: ERROR: doesn't have key fields provided (fence:'%b', originator:'%b')", stateCommand.hasFid(), stateCommand.hasUidOriginator()));
      return Long.valueOf(-1L);
    }

    long        groupId             = stateCommand.getFid();
    Recipient recipientOriginator   = Recipient.live(new UfsrvUid(stateCommand.getUidOriginator().toByteArray()).toString()).get();
    Log.d(TAG, String.format("processUfsrvStateCommand: Received (fence:'%d', originatorUid:'%s')", groupId, stateCommand.getUidOriginator()));

    switch (stateCommand.getHeader().getCommand())
    {
      case StateCommand.CommandTypes.TYPING_VALUE:
        return processStateCommandTyping(context, envelope, message, recipientOriginator);

      default:
        Log.d(TAG, String.format("processUfsrvStateCommand (type:'%d'): Received UNKNOWN RECEIPT COMMAND TYPE: fid:'%d'", stateCommand.getHeader().getCommand(), stateCommand.getFid()));
    }

    return (long) -1;
  }

  //logic mirrored from PushProcessMessageJob.handleTypingMessage. Also see SignalServiceCipher::createTypingMessage
  private static @Nullable
  Long processStateCommandTyping(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull SignalServiceDataMessage message, @NonNull Recipient recipientOriginator)
  {
    StateCommand stateCommand = message.getUfsrvCommand().getStateCommand();

    if (!stateCommand.hasFid()) {
      Log.e(TAG, String.format("processStateCommandTyping: ERROR: StateCommand doesn't have fid provided"));
      return Long.valueOf(-1L);
    }

    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return Long.valueOf(-1L);
    }

    Recipient author = recipientOriginator;

    long threadId = SignalDatabase.threads().getThreadIdFor(null, stateCommand.getFid());

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return (long) -1;
    }

    if (stateCommand.getState() == StateCommand.StateTypes.STATE_TYPING_STARTED) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationDependencies.getTypingStatusRepository().onTypingStarted(context, threadId, author, 1);
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationDependencies.getTypingStatusRepository().onTypingStopped(context, threadId, author, 1, false);
    }

    return (long) -1;
  }

  public static UfsrvCommand
  buildTypingStateCommand (Context context, long timeSentInMillis, long fid, StateCommand.StateTypes stateType)
  {
    StateCommand.Builder stateCommandBuilder = buildStateCommand(context, fid, timeSentInMillis, StateCommand.CommandTypes.TYPING);

    if (stateCommandBuilder != null) {
      stateCommandBuilder.setState(stateType);
      return (new UfsrvCommand(stateCommandBuilder.build(), false, UfsrvCommand.TransportType.LOCAL_PIPE));
    }

    return null;
  }

  public static StateCommand.Builder buildStateCommand(Context context,
                                                       long fid,
                                                       long timeSentInMillis,
                                                       StateCommand.CommandTypes commandType)
  {
    CommandHeader.Builder commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();
    StateCommand.Builder stateCommandBuilder    = StateCommand.newBuilder();

    commandHeaderBuilder.setCommand(commandType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvStateCommand());//todo: this has to be defined somewhere else
    stateCommandBuilder.setHeader(commandHeaderBuilder.build());

    stateCommandBuilder.setType(commandType);
    stateCommandBuilder.setFid(fid);

    return stateCommandBuilder;
  }

}