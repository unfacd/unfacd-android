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

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;

import java.io.IOException;
import java.util.LinkedList;

public class MessageCommandEndSessionJob extends BaseJob {

  private static final String KEY_FID             = "fid";
  private static final String KEY_MESSAGE_ID      = "messageId";

  public static final String KEY = "MessageCommandEndSessionJob";

  private static final String TAG = ResetGroupsJob.class.getSimpleName();


  private long fid;
  private long messageId;

  public MessageCommandEndSessionJob (long fid, long messageId) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         fid, messageId);

  }

  private MessageCommandEndSessionJob(@NonNull Job.Parameters parameters, long fid, long messageId) {
    super(parameters);
    this.fid = fid;
    this.messageId = messageId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_FID, fid)
            .putLong(KEY_MESSAGE_ID, messageId)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException
  {
    try {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      SmsMessageRecord record = database.getMessage(this.messageId);
      Optional<Recipient>otherRecipient = resolveUid(record.getRecipient().getAddress().serialize());
      if (otherRecipient.isPresent()) {
        Log.w("MessageCommandEndSessionJob", "Sending EndSession...");
        UserCommand.Builder userCommandBuilder = buildEndSessionUserCommand(otherRecipient.get());
        sendEndSessionUserCommand(ApplicationDependencies.getSignalServiceMessageSender(), new UfsrvCommand(userCommandBuilder.build(), false));
        database.markAsSent(messageId, true);
      }
    } catch (NoSuchMessageException ex) {
      Log.e(TAG, ex.getMessage());
    }

  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "Failed to send  after retry exhausted!");
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    Log.w(TAG, exception);
    if (exception instanceof NonSuccessfulResponseCodeException) return false;
    if (exception instanceof PushNetworkException)               return true;

    return false;
  }

  UserCommand.Builder buildEndSessionUserCommand  (Recipient recipient)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    UserRecord.Builder            userRecordBuilder     = UserRecord.newBuilder();
    FenceRecord.Builder           fenceRecordBuilder    = FenceRecord.newBuilder();

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.END_SESSION_VALUE);
    userCommandBuilder.setHeader(commandHeaderBuilder.build());
    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.getAddress().toString());
    userCommandBuilder.addAllTargetList(new LinkedList(){{add(userRecordBuilder.build());}});

    //stricktly speaking not necessary, as encryption user sessions are independent of groups
    if (this.fid>0) {
      fenceRecordBuilder.setFid(this.fid);
      userCommandBuilder.addAllFences(new LinkedList(){{add(fenceRecordBuilder.build());}});
    }

    return  userCommandBuilder;
  }

  private Optional<Recipient> resolveUid (String address)
  {
    Address myAdress = Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context));// uid
    Optional<Address> addressOther = DatabaseFactory.getGroupDatabase(context).privateGroupForTwoGetOther(address, myAdress, true);
    if (addressOther.isPresent()) {
      return Optional.fromNullable(Recipient.from(context, addressOther.get(), false));
    }

    return Optional.absent();
  }

  private void sendEndSessionUserCommand (SignalServiceMessageSender messageSender, UfsrvCommand ufsrvCommand)
  {
    try {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getUser().getHeader().getWhen())
              .build();
      messageSender.sendMessage(ufsrvMessage, Optional.absent(), ufsrvCommand);
    } catch (IOException | UntrustedIdentityException | InvalidKeyException x) {
      Log.d(TAG, x.getMessage());
    }
  }

  public static class Factory implements Job.Factory<MessageCommandEndSessionJob> {
    @Override
    public @NonNull MessageCommandEndSessionJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new MessageCommandEndSessionJob(parameters, data.getLong(KEY_FID), data.getLong(KEY_MESSAGE_ID));
    }
  }
}
