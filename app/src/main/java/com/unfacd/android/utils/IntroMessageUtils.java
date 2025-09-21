package com.unfacd.android.utils;

import android.content.Context;
import android.text.TextUtils;

import com.google.protobuf.ByteString;
import com.unfacd.android.locallyaddressable.LocallyAddressable;
import com.unfacd.android.locallyaddressable.LocallyAddressableEmail;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.ui.components.intro_contact.ResponseStatus;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.internal.push.ProfileAvatarData;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.IntroMessageRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;

public class IntroMessageUtils
{
  private static final String TAG = Log.tag(IntroMessageUtils.class);

  private static final Executor EXECUTOR = SignalExecutors.BOUNDED;

  public static IntroMessageRecord
  buildIntroMessageRecord(IntroContactDescriptor descriptor)
  {
    IntroMessageRecord.Builder builder = IntroMessageRecord.newBuilder();

    if (!descriptor.getAddressable().isUndefined()) {//user provided a potentially valid ufsrvuid
      builder.setTo(descriptor.getAddressable().toString());
    } else {//user used other identifying handle
      String handle = descriptor.getHandle();
      if (TextUtils.isEmpty(handle)) {
        Log.e(TAG, String.format("buildIntroMessageRecord: ERROR: BOTH HANDLE AND ADDRESSABLE UNDEFINED"));
        return null;
      }

      builder.setTo(handle);
      if (handle.startsWith("+")) builder.setHandleType(IntroMessageRecord.HandleType.E164);
      else if (handle.startsWith("@")) builder.setHandleType(IntroMessageRecord.HandleType.NICKNAME);
      else if (LocallyAddressableEmail.isValidEmail(handle))  builder.setHandleType(IntroMessageRecord.HandleType.EMAIL);
    }
    builder.setHandleType(IntroMessageUtils.buildHandleType(descriptor.getAddressable()));
    builder.setOriginator(UfsrvUserUtils.myOwnUfsrvUid());

    if (!TextUtils.isEmpty(descriptor.getMessage())) {
      builder.setMsg(ByteString.copyFromUtf8(descriptor.getMessage()));
    }

    byte[] avatar = descriptor.getAvatarBlob();
    if (avatar != null) {
      SignalServiceAttachment avatarAttachment = SignalServiceAttachmentStream.newStreamBuilder()
              .withContentType("image/jpeg")
              .withStream(new ByteArrayInputStream(avatar))
              .withLength(avatar.length)
              .withKey(Optional.of(ProfileKeyUtil.getSelfProfileKey().serialize())) //AA note the passing of profile key to avoid on the fly key generation further downstream
              .withKeySize(32)
              .build();

      ProfileAvatarData profileAvatarData;
      profileAvatarData = new ProfileAvatarData(avatarAttachment.asStream().getInputStream(),
                                                ProfileCipherOutputStream.getCiphertextLength(avatar.length),
                                                avatarAttachment.getContentType(),
                                                new ProfileCipherOutputStreamFactory(ProfileKeyUtil.getSelfProfileKey()));

      try {
        AttachmentRecord attachmentRecord = ApplicationDependencies.getSignalServiceMessageSender().createAttachmentPointerProfileAvatar(profileAvatarData, ProfileKeyUtil.getSelfProfileKey().serialize());

        Log.d(TAG, String.format("buildIntroMessageRecord: Received attachment ID: '%s', digest:'%s' ", attachmentRecord.getId(), attachmentRecord.getDigest().toString()));
        builder.setAvatar(attachmentRecord);
      } catch (IOException x) {
        Log.d(TAG, String.format("buildIntroMessageRecord: " + x.getMessage()));
        return null;
      }
    }

    return builder.build();
  }

  static public void
  sendContactIntroMessage(Context context, Pair<Long, IntroContactDescriptor> descriptor) throws UntrustedIdentityException
  {
    Recipient self        = Recipient.self();
    byte[]    profileKey  = self.getProfileKey();
    long      introId     = descriptor.first();

    if (profileKey == null) throw new AssertionError (String.format("profile key not set for this user"));
    if (introId == -1) throw new AssertionError (String.format("IntroMsgId not set"));

    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(MessageCommand.CommandTypes.INTRO_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    MessageCommand.Builder messageCommandBuilder = UfsrvMessageUtils.buildMessageCommand(context, descriptor.second().getTimestampSent(), commandArgDescriptor, Optional.empty());
    UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false, UfsrvCommand.TransportType.API_SERVICE, false);

    IntroMessageRecord introMessageRecord = IntroMessageUtils.buildIntroMessageRecord(descriptor.second());
    if (introMessageRecord != null) {
      try {
        SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
                .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
                .build();

        messageCommandBuilder.setIntro(introMessageRecord);

         SignalDatabase.unfacdIntroContacts().setAvatarId(introId, introMessageRecord.getAvatar().getId());

        ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
      } catch (IOException |InvalidKeyException ioe) {
        Log.d(TAG, ioe.getStackTrace().toString());
      } catch (NullPointerException e) {
        Log.d(TAG, e.getStackTrace().toString());
        return;
      }

    }

   /* try {
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
                .withKey(Optional.of(ProfileKeyUtil.getProfileKey(context))) //AA note the passing of profile key to avoid on the fly key generation further downstream
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
         SignalDatabase.unfacdIntroContacts().setAvatarId(introId, attachmentRecord.getId());
      }

      messageCommandBuilder.addTo(userRecordBuilder.build());
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
              .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendMessage(ufsrvMessage, Optional.empty(), ufsrvCommand);
    } catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (NullPointerException e) {
      Log.d(TAG, e.getStackTrace().toString());
      return;
    }*/
  }

  /**
   * Send a MessageIntro response message to server
   * @param context
   * @param ufsrvUidTo User receiving the intro message response (the original user who sent first Intro Message)
   * @param responseStatus
   * @throws UntrustedIdentityException
   */
  static public void
  sendContactIntroUserResponseMessage(Context context, String ufsrvUidTo, long eid, long timestamp_client, ResponseStatus responseStatus)
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(MessageCommand.CommandTypes.INTRO_USER_RESPONSE_VALUE, CommandArgs.SET_VALUE);

    MessageCommand.Builder messageCommandBuilder = UfsrvMessageUtils.buildMessageCommand(context, System.currentTimeMillis(), commandArgDescriptor, Optional.of((builder) -> {
                                                                                                                                                                                builder.setEidClient(eid);
                                                                                                                                                                                builder.setWhenClient(timestamp_client);
                                                                                                                                                                              })
    );
    UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false, UfsrvCommand.TransportType.API_SERVICE, false);

    IntroMessageRecord introMessageRecord = IntroMessageUtils.buildIntroMessageRecordForUserResponse(ufsrvUidTo, responseStatus);
    if (introMessageRecord != null) {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
                                                                      .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
                                                                      .build();

      messageCommandBuilder.setIntro(introMessageRecord);

      EXECUTOR.execute(() -> {
        try {
          ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
        } catch (IOException | InvalidKeyException | NullPointerException | UntrustedIdentityException ioe) {
          Log.d(TAG, ioe.getStackTrace().toString());
        }
      });
    }
  }

  public static IntroMessageRecord
  buildIntroMessageRecordForUserResponse(String ufsrvUidTo, ResponseStatus responseStatus)
  {
    IntroMessageRecord.Builder builder = IntroMessageRecord.newBuilder();
    builder.setTo(ufsrvUidTo);
    builder.setOriginator(UfsrvUserUtils.myOwnUfsrvUid());
    builder.setUserResponseType(IntroMessageRecord.UserResponseType.values()[responseStatus.getValue()]);

    return builder.build();
  }

  public static IntroMessageRecord.HandleType
  buildHandleType(LocallyAddressable addressable) {
    switch (addressable.getAddressableType()) {
      case EMAIL:     return IntroMessageRecord.HandleType.EMAIL;
      case PHONE:     return IntroMessageRecord.HandleType.E164;
      case UFSRVUID:  return  IntroMessageRecord.HandleType.UFSRV;
      default:        return IntroMessageRecord.HandleType.NICKNAME;
    }
  }

  private static class NetworkException extends Exception {

    public NetworkException(Exception ioe) {
      super(ioe);
    }
  }
}
