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

package com.unfacd.android.jobs;

import android.content.Context;
import android.database.Cursor;

import com.google.protobuf.ByteString;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReportedContentRecord;

import java.io.IOException;
import java.util.Optional;

import androidx.annotation.NonNull;

public class MessageReportedJob extends BaseJob {
  public static final String KEY = "MessageReportedJob";

  private static final String KEY_MESSAGE_ID        = "MESSAGE_ID";
  private static final String KEY_STORAGE_TYPE      = "STORAGE_TYPE";
  private static final String KEY_MESSAGE_STATUS    = "MESSAGE_STATUS";

  private static final String TAG = Log.tag(UnfacdIntroContactJob.class);

  private long messageId = -1;
  private boolean isSms = false;
  private MessageStatus messageStatus = MessageStatus.UNSET;

  //aligned with proto
  public enum MessageStatus {
    UNSET(0),
    REPORTED(1),
    DELETED(2),
    UNREPORTED(3);

    private int value;

    MessageStatus(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  private MessageReportedJob (@NonNull Job.Parameters parameters, long messageId, boolean isSms, MessageStatus messageStatus) {
    super(parameters);
    this.messageId  = messageId;
    this.isSms      = isSms;
    this.messageStatus = messageStatus;
  }

  public MessageReportedJob (long messageId, boolean isSms, MessageStatus messageStatus) {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(3)
                           .build(),
         messageId, isSms, messageStatus);

  }
  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException
  {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (isSms) {
      reportSmsStoredMessage();
    } else {
      reportMmsStoredMessage();
    }
  }

  void reportSmsStoredMessage ()
  {
    MessageDatabase database = SignalDatabase.sms();
    try {
      SmsMessageRecord msg = database.getSmsMessage(messageId);
      sendFlaggedMessage(msg.getRecipient(), msg.getUfsrvGid(), msg.getUfsrvEid(), true);
    } catch (NoSuchMessageException x) {
      Log.e(TAG, String.format("Invalid message id (%d)", messageId));
    } catch (UntrustedIdentityException | NetworkException x) {
      Log.e(TAG, String.format("Cannot send flagged message id (%d): %s", messageId, x.getMessage()));
    }
  }

  void reportMmsStoredMessage ()
  {
    Cursor messageCursor = null;
    try {
      messageCursor = SignalDatabase.mms().getMessageCursor(messageId);
      final MessageRecord message = MmsDatabase.readerFor(messageCursor).getNext();
      sendFlaggedMessage(message.getIndividualRecipient(), message.getUfsrvGid(), message.getUfsrvEid(), false);
    } catch (UntrustedIdentityException | NetworkException x) {
      Log.e(TAG, String.format("Cannot send flagged message id (%d): %s", messageId, x.getMessage()));
    }  finally {
      if (messageCursor != null) messageCursor.close();
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public @NonNull
  Data serialize() {
    Data.Builder builder = new Data.Builder();

    return builder.putLong(KEY_MESSAGE_ID, messageId).putBoolean(KEY_STORAGE_TYPE, isSms).putInt(KEY_MESSAGE_STATUS, messageStatus.getValue()).build();
  }

  @Override
  public void onFailure() {

  }

  private void sendFlaggedMessage(Recipient recipient, long ufsrvGid, long ufsrvEid, boolean isSms) throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(MessageCommand.CommandTypes.FLAG_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    MessageCommand.Builder messageCommandBuilder = buildMessageCommand(context, recipient, System.currentTimeMillis(), ufsrvGid, ufsrvEid, isSms?1:0, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false, UfsrvCommand.TransportType.API_SERVICE, false);

    try {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
                                                                      .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
                                                                      .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
    } catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (NullPointerException e) {
      Log.d(TAG, e.getStackTrace().toString());
      return;
    }
  }

  MessageCommand.Builder
  buildMessageCommand(@NonNull Context context, Recipient recipient, long timestamp, long gid, long eid, long clientId, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    MessageCommand.Builder        messageCommandBuilder = SignalServiceProtos.MessageCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timestamp);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    ReportedContentRecord.Builder reportedContentBuilder = ReportedContentRecord.newBuilder();
    reportedContentBuilder.setGid(gid);
    reportedContentBuilder.setEid(eid);
    reportedContentBuilder.setStatus(ReportedContentRecord.Status.valueOf(messageStatus.getValue()));
    reportedContentBuilder.setOriginator(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    reportedContentBuilder.setClientId(clientId);
    messageCommandBuilder.addReported(reportedContentBuilder.build());

    return  messageCommandBuilder;
  }

  private static class NetworkException extends Exception {

    public NetworkException(Exception ioe) {
      super(ioe);
    }
  }

  public static class Factory implements Job.Factory<MessageReportedJob> {
    @Override
    public @NonNull
    MessageReportedJob create(@NonNull Parameters parameters, @NonNull Data data) {
      MessageStatus messageStatus = MessageStatus.values()[data.getInt(KEY_MESSAGE_STATUS)];
      return new MessageReportedJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getBoolean(KEY_STORAGE_TYPE), messageStatus);
    }
  }

}
