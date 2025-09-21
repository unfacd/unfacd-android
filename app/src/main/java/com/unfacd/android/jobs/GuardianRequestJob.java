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
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.GuardianNonceHelper;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.signal.libsignal.protocol.InvalidKeyException;
import java.util.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GuardianRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;


public class GuardianRequestJob extends BaseJob {
  public static final String KEY = "GuardianRequestJob";

  private static final String KEY_FID        = "FID";
  private static final String KEY_CHALLENGE  = "CHALLENGE";
  private static final String KEY_ADDRESS    = "ADDRESS";

  private static final String TAG = Log.tag(GuardianRequestJob.class);

  private long fid = -1;
  private String challenge;
  private String recipientAddress;

  private GuardianRequestJob (@NonNull Job.Parameters parameters, long fid, String challenge, String recipientAddress) {
    super(parameters);
    this.fid  = fid;
    this.challenge = challenge;
    this.recipientAddress = recipientAddress;
  }

  public GuardianRequestJob (String recipientAddress) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         0, null, recipientAddress);

  }

  public GuardianRequestJob (long fid, String challenge) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         fid, challenge, null);

  }

  public GuardianRequestJob (long fid, String challenge, String recipientAddress) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         fid, challenge, recipientAddress);

  }

  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException
  {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }
    if (TextUtils.isEmpty(recipientAddress))  sendGuardianRequest();
    else if (fid == 0) sendGuardianUnLink();
    else  sendGuardianLink();
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

    return builder.putLong(KEY_FID, fid).putString(KEY_CHALLENGE, challenge).putString(KEY_ADDRESS, recipientAddress).build();
  }

  @Override
  public void onFailure() {

  }

   private LinkedList<SendMessageResult> sendGuardianRequest()
  {
    List<Recipient>               recipientsList     = SignalDatabase.groups().getGroupMembers(fid, true);
    Recipient                     thisRecipient      = UfsrvUserUtils.myOwnRecipient(false);
    long                          timestamp          =  System.currentTimeMillis();

    try {

      Optional<GuardianNonceHelper.SealedData> guardianChallenge = generateGuardianNonce();
      if (!guardianChallenge.isPresent()) {
        return new LinkedList<>();
      }

      challenge = guardianChallenge.get().serialize();

      MessageCommand.Builder messageCommandBuilder = buildMessageCommand(ApplicationContext.getInstance(),
                                                                         timestamp,
                                                                         MessageCommand.CommandTypes.GUARDIAN_REQUEST_VALUE,
                                                                         0,
                                                                         0);
      List<Address> addressList = new LinkedList<>();
      Recipient recipientOther = Stream.of(recipientsList).filterNot(r -> r.equals(thisRecipient)).collect(() -> new LinkedList<Recipient>(), (l, r) -> l.add(r)).getFirst();
      addressList.add(recipientOther.requireAddress());

      GuardianRecord.Builder guardianRecordBuilder = GuardianRecord.newBuilder();

      UserRecord.Builder userRecordBuilderOriginator = UserRecord.newBuilder();
      userRecordBuilderOriginator.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(UfsrvUserUtils.myOwnRecipient(false).getUfsrvUid()))));
      userRecordBuilderOriginator.setUsername("*");
      UserRecord.Builder userRecordBuilderGuardian = UserRecord.newBuilder();
      userRecordBuilderGuardian.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(recipientOther.getUfsrvUid()))));
      userRecordBuilderGuardian.setUsername("*");

      guardianRecordBuilder.setFid(fid);
      guardianRecordBuilder.setGuardian(userRecordBuilderGuardian.build());
      guardianRecordBuilder.setOriginator(userRecordBuilderOriginator.build());
      messageCommandBuilder.setGuardian(guardianRecordBuilder.build());

      SignalServiceDataMessage groupMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(timestamp)
              .withBody(guardianChallenge.get().serialiseKey())
              .withUfsrvCommand(null)//AA+
              .build();

      return ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(null, false, ContentHint.DEFAULT, groupMessage, getPushAddresses(addressList), SignalServiceMessageSender.IndividualSendEvents.EMPTY, new UfsrvCommand(messageCommandBuilder, true, UfsrvCommand.TransportType.API_SERVICE, false));
    } catch (IOException | InvalidKeyException | EncapsulatedExceptions | java.security.InvalidKeyException ex) {
      Log.d(TAG, ex.getStackTrace().toString());
    }

    return new LinkedList<>();
  }

  private LinkedList<SendMessageResult> sendGuardianLink()
  {
    Recipient                     thisRecipient      = UfsrvUserUtils.myOwnRecipient(false);
    Recipient                     guardedRecipient   = Recipient.live(recipientAddress).get();
    long                           timestamp         =  System.currentTimeMillis();

    try {
      MessageCommand.Builder messageCommandBuilder = buildMessageCommand(ApplicationContext.getInstance(),
                                                                         timestamp,
                                                                         MessageCommand.CommandTypes.GUARDIAN_LINK_VALUE,
                                                                         SignalServiceProtos.CommandArgs.SET_VALUE,
                                                                         0);

      GuardianRecord.Builder guardianRecordBuilder = SignalServiceProtos.GuardianRecord.newBuilder();

      UserRecord.Builder userRecordBuilderOriginator = UserRecord.newBuilder();
      userRecordBuilderOriginator.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(recipientAddress))));
      userRecordBuilderOriginator.setUsername("*");
      UserRecord.Builder userRecordBuilderGuardian = UserRecord.newBuilder();
      userRecordBuilderGuardian.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context)))));
      userRecordBuilderGuardian.setUsername("*");

      guardianRecordBuilder.setFid(fid);
      guardianRecordBuilder.setGuardian(userRecordBuilderGuardian.build());
      guardianRecordBuilder.setOriginator(userRecordBuilderOriginator.build());
      guardianRecordBuilder.setNonce(challenge);
      messageCommandBuilder.setGuardian(guardianRecordBuilder.build());

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

  private LinkedList<SendMessageResult> sendGuardianUnLink()
  {
    long                           timestamp         =  System.currentTimeMillis();

    try {
      MessageCommand.Builder messageCommandBuilder = buildMessageCommand(ApplicationContext.getInstance(),
                                                                         timestamp,
                                                                         MessageCommand.CommandTypes.GUARDIAN_UNLINK_VALUE,
                                                                         SignalServiceProtos.CommandArgs.SET_VALUE,
                                                                         0);

      GuardianRecord.Builder guardianRecordBuilder = SignalServiceProtos.GuardianRecord.newBuilder();

      UserRecord.Builder userRecordBuilderOriginator = UserRecord.newBuilder();
      userRecordBuilderOriginator.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(recipientAddress))));
      userRecordBuilderOriginator.setUsername("*");
      UserRecord.Builder userRecordBuilderGuardian = UserRecord.newBuilder();
      userRecordBuilderGuardian.setUfsrvuid((ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context)))));
      userRecordBuilderGuardian.setUsername("*");

      guardianRecordBuilder.setGuardian(userRecordBuilderGuardian.build());
      guardianRecordBuilder.setOriginator(userRecordBuilderOriginator.build());
      messageCommandBuilder.setGuardian(guardianRecordBuilder.build());

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

  private Optional<GuardianNonceHelper.SealedData> generateGuardianNonce ()
  {
    try {
      String nonce = ApplicationDependencies.getSignalServiceAccountManager().requestGuardianNonce();
      GuardianNonceHelper.SealedData guardianChallenge = GuardianNonceHelper.seal(nonce.getBytes(StandardCharsets.UTF_8), GuardianNonceHelper.generateKey());
      Log.i(TAG, String.format("sendGuardianRequest: Guardian Challenge", guardianChallenge.serialize()));
      TextSecurePreferences.setGuardianeEncryptedSecret(context, guardianChallenge.serialize());

      return Optional.of(guardianChallenge);
    } catch (IOException x) {
      Log.d(TAG, x.getMessage());
    }

    return Optional.empty();
  }

  private static MessageCommand.Builder
  buildMessageCommand(Context context,
                      long timeSentInMillis,
                      int command,
                      int commandArg,
                      long fid)
  {

    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder                = SignalServiceProtos.CommandHeader.newBuilder();
    MessageCommand.Builder messageCommandBuilder              = SignalServiceProtos.MessageCommand.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setArgs(commandArg);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    if (fid > 0) {
      SignalServiceProtos.FenceRecord.Builder fenceBuilder = SignalServiceProtos.FenceRecord.newBuilder();
      fenceBuilder.setFid(fid);
      messageCommandBuilder.addFences(fenceBuilder.build());
    }

    return messageCommandBuilder;
  }

  private List<SignalServiceAddress> getPushAddresses(List<Address> addresses) {
    return Stream.of(addresses).map(this::getPushAddress).toList();
  }

  protected SignalServiceAddress getPushAddress(Address address) {
    return new SignalServiceAddress(Recipient.resolvedFromUfsrvUid(address.serialize()).requireServiceId(), address.toPhoneString());
  }

  public static void sendGuardianRequest (Context context, long fid, String challenge) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new GuardianRequestJob(fid, challenge));
  }

  private static class NetworkException extends Exception {

    public NetworkException(Exception ioe) {
      super(ioe);
    }
  }

  public static class Factory implements Job.Factory<GuardianRequestJob> {
    @Override
    public @NonNull
    GuardianRequestJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new GuardianRequestJob(parameters, data.getLong(KEY_FID), data.getString(KEY_CHALLENGE), data.getString(KEY_ADDRESS));
    }
  }

}
