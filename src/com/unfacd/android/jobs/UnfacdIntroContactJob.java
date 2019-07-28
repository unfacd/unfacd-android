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
import android.text.TextUtils;
import android.util.Pair;

import com.google.protobuf.ByteString;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.ProfileAvatarData;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class UnfacdIntroContactJob extends BaseJob {
  public static final String KEY = "UnfacdIntroContactJob";

  private static final String KEY_INTRO_ID        = "INTRO_ID";

  private static final String TAG = UnfacdIntroContactJob.class.getSimpleName();

  private long introId = -1;
  Optional<Pair<Long, IntroContactDescriptor>> descriptor = Optional.absent();

  private UnfacdIntroContactJob (@NonNull Job.Parameters parameters, long introId) {
    super(parameters);
    this.introId = introId;
    descriptor = DatabaseFactory.getUnfacdIntroContactsDatabase(context).getIntroContact(this.introId);
  }

  public UnfacdIntroContactJob (long introId) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         introId);

  }
  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException, UnfacdIntroContactJob.NetworkException
  {
    sendContactIntroMessage();
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

    return builder.putLong(KEY_INTRO_ID, introId).build();
  }

  @Override
  public void onCanceled() {

  }

  private void sendContactIntroMessage() throws UntrustedIdentityException, NetworkException
  {
    if (!ProfileKeyUtil.hasProfileKey(context)) throw new AssertionError (String.format("profile key not set for this user"));
    if (introId == -1) throw new AssertionError (String.format("IntroMsgId not set"));
    if (!descriptor.isPresent()) throw new AssertionError (String.format(String.format("IntroContactDescriptor not present (introMsgId:'%d')", introId)));

    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(MessageCommand.CommandTypes.INTRO_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    MessageCommand.Builder messageCommandBuilder = buildMessageCommand  (context, descriptor.get().second.getTimestampSent(), commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false);

    try {
      final SignalServiceProtos.UserRecord.Builder  userRecordBuilder       = SignalServiceProtos.UserRecord.newBuilder();
      userRecordBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(descriptor.get().second.getAddress().serialize())));
      userRecordBuilder.setUsername("*");

      if (!TextUtils.isEmpty(descriptor.get().second.getMessage())) {
        final SignalServiceProtos.MessageRecord.Builder messageRecordBuilder = SignalServiceProtos.MessageRecord.newBuilder();
        messageRecordBuilder.setMessage(ByteString.copyFromUtf8(descriptor.get().second.getMessage()));
        messageCommandBuilder.addMessages(messageRecordBuilder.build());
      }

      byte[] avatar = descriptor.get().second.getAvatarBlob();
      if (avatar != null) {
        SignalServiceAttachment avatarAttachment = SignalServiceAttachmentStream.newStreamBuilder()
                .withContentType("image/jpeg")
                .withStream(new ByteArrayInputStream(avatar))
                .withLength(avatar.length)
                .withKey(Optional.of(ProfileKeyUtil.getProfileKey(context))) // note the passing of profile key to avoid on the fly key generation further downstream
                .withKeySize(32)
                .build();

        ProfileAvatarData profileAvatarData;
        profileAvatarData = new ProfileAvatarData(avatarAttachment.asStream().getInputStream(),
                                                  ProfileCipherOutputStream.getCiphertextLength(avatar.length),
                                                  avatarAttachment.getContentType(),
                                                  new ProfileCipherOutputStreamFactory(ProfileKeyUtil.getProfileKey(context)));

        SignalServiceProtos.AttachmentRecord attachmentRecord = ApplicationDependencies.getSignalServiceMessageSender().createAttachmentPointerProfileAvatar(profileAvatarData, ProfileKeyUtil.getProfileKey(context));
        org.whispersystems.libsignal.logging.Log.d(TAG, String.format("sendContactIntroMessage: Received attachment ID: '%s', digest:'%s' ", attachmentRecord.getId(), attachmentRecord.getDigest().toString()));
        userRecordBuilder.setAvatar(attachmentRecord);
        DatabaseFactory.getUnfacdIntroContactsDatabase(context).setAvatarId(introId, attachmentRecord.getId());
      }

      messageCommandBuilder.addTo(userRecordBuilder.build());
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
              .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendMessage(ufsrvMessage, Optional.absent(), ufsrvCommand);
    } catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (NullPointerException e) {
      Log.d(TAG, e.getStackTrace().toString());
      return;
    }
  }

  public static MessageCommand.Builder
  buildMessageCommand  (@NonNull Context context, long timestamp, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    MessageCommand.Builder        messageCommandBuilder = SignalServiceProtos.MessageCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timestamp);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  messageCommandBuilder;
  }

  private static class NetworkException extends Exception {

    public NetworkException(Exception ioe) {
      super(ioe);
    }
  }

  public static class Factory implements Job.Factory<UnfacdIntroContactJob> {
    @Override
    public @NonNull
    UnfacdIntroContactJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new UnfacdIntroContactJob(parameters, data.getLong(KEY_INTRO_ID));
    }
  }

}
