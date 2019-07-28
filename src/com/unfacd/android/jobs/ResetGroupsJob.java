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

import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;

import java.io.IOException;


public class ResetGroupsJob  extends BaseJob {
  public static final String KEY = "ResetGroupsJob";

  private static final String TAG = ResetGroupsJob.class.getSimpleName();

  private ResetGroupsJob(@NonNull Job.Parameters parameters) {
    super(parameters);
  }

  public ResetGroupsJob() {
    this(new Job.Parameters.Builder()
                 .setQueue("LocationRefreshJob")
                 .setMaxAttempts(5)
                 .build());

  }

  @Override
  public @NonNull
  Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException
  {
    Log.w("ResetGroupsJob", "Sending reset for all groups");
    SignalServiceProtos.UserCommand.Builder userCommandBuilder  = buildResetGroupsCommand();
    sendResetCommand(ApplicationDependencies.getSignalServiceMessageSender(),  new UfsrvCommand(userCommandBuilder.build(), false));
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

  SignalServiceProtos.UserCommand.Builder buildResetGroupsCommand  ()
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    Log.d(TAG, String.format("buildResetGroupsCommand: building..."));

    SignalServiceProtos.UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    SignalServiceProtos.CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(SignalServiceProtos.UserCommand.CommandTypes.RESET_VALUE);
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  private void sendResetCommand(SignalServiceMessageSender messageSender, UfsrvCommand ufsrvCommand)
  {
    try {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getUser().getHeader().getWhen())
              .build();
      messageSender.sendMessage(ufsrvMessage, Optional.absent(), ufsrvCommand);
    } catch (IOException |UntrustedIdentityException | InvalidKeyException x) {
      Log.d(TAG, x.getMessage());
    }
  }

  public static class Factory implements Job.Factory<ResetGroupsJob> {
    @Override
    public @NonNull ResetGroupsJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new ResetGroupsJob(parameters);
    }
  }
}
