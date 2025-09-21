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

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.libsignal.protocol.InvalidKeyException;
import java.util.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageEffectRecord;

import java.io.IOException;
import java.util.LinkedList;


public class MessageEffectsJob extends BaseJob {
  public static final String KEY = "MessageEffectsJob";

  private static final String KEY_FID        = "FID";
  private static final String KEY_MESSAGE  = "MESSAGE";

  private static final String TAG = Log.tag(GuardianRequestJob.class);

  private long fid = -1;
  private String message;

  private MessageEffectsJob (@NonNull Job.Parameters parameters, long fid, String message) {
    super(parameters);
    this.fid  = fid;
    this.message = message;
  }

  public MessageEffectsJob (long fid, String message) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         fid, message);

  }

  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException
  {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    sendMessageEffect();
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

    return builder.putLong(KEY_FID, fid).putString(KEY_MESSAGE, message).build();
  }

  @Override
  public void onFailure() {

  }

  private LinkedList<SendMessageResult> sendMessageEffect()
  {
    long                           timestamp         =  System.currentTimeMillis();

    try {
      MessageCommand.Builder messageCommandBuilder = buildMessageCommand(ApplicationContext.getInstance(),
                                                                         timestamp,
                                                                         MessageCommand.CommandTypes.EFFECT_VALUE,
                                                                         SignalServiceProtos.CommandArgs.SET_VALUE);

      UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false, UfsrvCommand.TransportType.API_SERVICE, false);

      SignalServiceDataMessage dataMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(timestamp)
              .withUfsrvCommand(null)
              .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(dataMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
    } catch (IOException | InvalidKeyException |  UntrustedIdentityException ex) {
      Log.d(TAG, ex.getStackTrace().toString());
    }

    return new LinkedList<>();
  }

  private MessageCommand.Builder
  buildMessageCommand(Context context,
                      long timeSentInMillis,
                      int command,
                      int commandArg)
  {

    CommandHeader.Builder commandHeaderBuilder                = SignalServiceProtos.CommandHeader.newBuilder();
    MessageCommand.Builder messageCommandBuilder              = SignalServiceProtos.MessageCommand.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setArgs(commandArg);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    if (fid > 0) {
      FenceRecord.Builder fenceBuilder = FenceRecord.newBuilder();
      fenceBuilder.setFid(fid);
      messageCommandBuilder.addFences(fenceBuilder.build());
    }

    MessageRecord.Builder messageRecordBuilder = MessageRecord.newBuilder();
    MessageEffectRecord.Builder effectRecordBuilder = MessageEffectRecord.newBuilder();
    effectRecordBuilder.setType(MessageEffectRecord.Type.RAIN);
    messageRecordBuilder.setEffect(effectRecordBuilder.build());

    messageRecordBuilder.setMessage(ByteString.copyFromUtf8(message));
    messageCommandBuilder.addMessages(messageRecordBuilder.build());

    return messageCommandBuilder;
  }

  public static void sendMessageEffect (Context context, long fid, String message) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new MessageEffectsJob(fid, message));
  }

  public static class Factory implements Job.Factory<MessageEffectsJob> {
    @Override
    public @NonNull
    MessageEffectsJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new MessageEffectsJob(parameters, data.getLong(KEY_FID), data.getString(KEY_MESSAGE));
    }
  }

}
