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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.google.protobuf.ByteString;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
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
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;
import org.whispersystems.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedList;


public class UfsrvUserCommandProfileJob extends BaseJob {
  public static final String KEY = "UfsrvUserCommandProfileJob";

  private static final String KEY_COMMAND_DESCRIPTOR        = "profile_command_descriptor";
  private static final String KEY_ADDRESS                   = "address";
  private static final String KEY_EXTRA_ARG                 = "extra_arg";

  private static final String TAG = UfsrvUserCommandProfileJob.class.getSimpleName();

  private byte[]                    avatar = null;
  private ProfileCommandDescriptor  profileCommandDescriptor;

  private String          extraArg;
  private Recipient       recipient;

  public interface IProfileOperationDescriptor {
    enum ProfileType {
      PROFILE(0),
      NAME(1),
      AVATAR(2),
      PRESENCE(3),
      SETTABLE(4),//boolean preference
      SETTABLE_MULTI(5),
      READ_RECEIPT(6),
      ACTIVITY_STATE(7),
      BLOCKING(8),
      CONTACTS(9);

      private int value;

      ProfileType (int value) {
        this.value = value;
      }

      public int getValue () {
        return value;
      }
    }

    enum ProfileOperationMode {
      SET(0),
      UNSET(1);

      private int value;

      ProfileOperationMode (int value) {
        this.value = value;
      }

      public int getValue () {
        return value;
      }
    }

    enum ProfileOperationScope {
      USER(0),
      GROUP(1);

      private int value;

      ProfileOperationScope (int value) {
        this.value = value;
      }

      public int getValue () {
        return value;
      }
    }

  }

  static public class ProfileCommandDescriptor implements Serializable {
    private ProfileOperationDescriptor profileOperationDescriptor;
    private Object                     valueHolder;

    public ProfileCommandDescriptor (ProfileOperationDescriptor profileOperationDescriptor) {
      this.profileOperationDescriptor = profileOperationDescriptor;
    }

    public ProfileCommandDescriptor (ProfileOperationDescriptor profileOperationDescriptor, Object valueHolder) {
      this.profileOperationDescriptor = profileOperationDescriptor;
      this.valueHolder                = valueHolder;
    }

    public ProfileOperationDescriptor getProfileOperationDescriptor () {
      return profileOperationDescriptor;
    }

    public Object getValueHolder () {
      return valueHolder;
    }

    public void setProfileOperationDescriptor (ProfileOperationDescriptor profileOperationDescriptor) {
      this.profileOperationDescriptor = profileOperationDescriptor;
    }

    static public class ProfileOperationDescriptor implements IProfileOperationDescriptor, Serializable {
      IProfileOperationDescriptor.ProfileType profileType;
      IProfileOperationDescriptor.ProfileOperationMode profileOperationMode;
      IProfileOperationDescriptor.ProfileOperationScope profileOperationScope;

      public ProfileType getProfileType () {
        return profileType;
      }

      public ProfileOperationMode getProfileOperationMode () {
        return profileOperationMode;
      }

      public ProfileOperationScope getProfileOperationScope () {
        return profileOperationScope;
      }

      public void setProfileType (ProfileType profileType) {
        this.profileType = profileType;
      }

      public void  setProfileOperationMode (ProfileOperationMode profileOperationMode) {
        this.profileOperationMode = profileOperationMode;
      }

      public void setProfileOperationScope (ProfileOperationScope profileOperationScope) {
        this.profileOperationScope = profileOperationScope;
      }
    }
  }

  static public class ProfileCommandHelper {
    static public  String serialise (ProfileCommandDescriptor profileCommandDescriptor)
    {
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeObject(profileCommandDescriptor);
        byte b[] = bos.toByteArray();
        out.close();
        bos.close();

        return org.whispersystems.signalservice.internal.util.Base64.encodeBytes(b);
      } catch (IOException x) {
        Log.e("profileCommandDescriptor", x.getMessage());
        return "";
      }
    }

    static public ProfileCommandDescriptor deserialise (String profileCommandDescriptor)
    {
      if (!TextUtils.isEmpty(profileCommandDescriptor)) {
        try {
          ByteArrayInputStream bis = new ByteArrayInputStream(org.whispersystems.signalservice.internal.util.Base64.decode(profileCommandDescriptor));
          ObjectInput in = new ObjectInputStream(bis);
          return ((ProfileCommandDescriptor) in.readObject());
        } catch (IOException|ClassNotFoundException x) {
          Log.e("ProfileCommandDescriptor", x.getMessage());
        }
      }

      return null;
    }
  }

  private UfsrvUserCommandProfileJob(@NonNull Job.Parameters parameters, String profileCommandDescriptor, Address address) {
    super(parameters);

    if (TextUtils.isEmpty(profileCommandDescriptor)) {
      Log.e(TAG, "Invalid UfsrvCommand...");
      return;
    }

    try {
      this.profileCommandDescriptor       = ProfileCommandHelper.deserialise(profileCommandDescriptor);
      if (address!=null)  this.recipient  = Recipient.from(context, address, false);
      else                this.recipient  = null;
    } catch (Exception x) {
      Log.e(TAG, x.getMessage());
      this.recipient    = null;
      return;
    }
  }

  private UfsrvUserCommandProfileJob(@NonNull Job.Parameters parameters, String profileCommandDescriptor, String extraArg) {
    super(parameters);

    if (TextUtils.isEmpty(profileCommandDescriptor)) {
      Log.e(TAG, "Invalid UfsrvCommand...");
      return;
    }

    try {
      this.profileCommandDescriptor = ProfileCommandHelper.deserialise(profileCommandDescriptor);
      this.extraArg                 = extraArg;
    } catch (Exception x) {
      Log.e(TAG, "Invalid UfsrvCommand...");
      this.recipient    = null;
      return;
    }
  }

  public UfsrvUserCommandProfileJob (String profileCommandDescriptor, String extraArg) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         profileCommandDescriptor,
         extraArg);

  }

  public UfsrvUserCommandProfileJob (String profileCommandDescriptor, Address address) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         profileCommandDescriptor,
         address);

  }

  @Override
  public void onRun()
          throws IOException, UntrustedIdentityException, NetworkException
  {
    switch (profileCommandDescriptor.getProfileOperationDescriptor().profileType) {
      case PROFILE:
        sendProfileSharing();
      case NAME:
        sendProfileNameUpdate();
        break;
      case AVATAR:
        sendAvatarUpdate();
        break;
      case PRESENCE:
        sendPresenceSharing();
        break;
      case READ_RECEIPT:
        sendReadReceiptSharing();
        break;
      case BLOCKING:
        sendBlockingSharing();
        break;
      case CONTACTS:
        sendContactSharing();
        break;
      case ACTIVITY_STATE:
        break;
      case SETTABLE:
        sendUserSettablePreference();
        break;
      case SETTABLE_MULTI:
        sendUserSettableMultiPreference();
        break;

      default:
        Log.w(TAG, "Unknown profile type received: "+profileCommandDescriptor.getProfileOperationDescriptor().profileType);

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

    if (avatar!=null && avatar.length>0)    builder.putString(KEY_EXTRA_ARG, Base64.encodeBytes(avatar));
    else                                    builder.putString(KEY_EXTRA_ARG, extraArg);

    if (profileCommandDescriptor !=null) builder.putString(KEY_COMMAND_DESCRIPTOR, ProfileCommandHelper.serialise(profileCommandDescriptor));
    else                                 builder.putString(KEY_COMMAND_DESCRIPTOR, "");

    if (recipient!=null)  builder.putString(KEY_ADDRESS, recipient.getAddress().serialize());
    else                  builder.putString(KEY_ADDRESS, "");

    if (!TextUtils.isEmpty(extraArg)) builder.putString(KEY_EXTRA_ARG, extraArg);
    else                              builder.putString(KEY_EXTRA_ARG, "");

    return builder.build();
  }

  @Override
  public void onCanceled() {

  }

  //KEY_EXTRA_ARG for profilename
  private void sendProfileNameUpdate() throws IOException, UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder=UfsrvUserCommandProfileJob.buildProfileUpdateForNickname  (context, extraArg, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  //KEY_ADDRESS for group id
  private void sendProfileSharing ()  throws IOException, UntrustedIdentityException, NetworkException
  {
    byte[] myProfileKey = ProfileKeyUtil.getProfileKey(context);

    UfsrvCommandUtils.CommandArgDescriptor  commandArgDescriptor;
    UserCommand.Builder                     userCommandBuilder;

    if (recipient.isGroupRecipient()) {
      if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);
        userCommandBuilder  = buildSettableUserPreferenceForFence  (context, SignalServiceProtos.FenceUserPrefs.PROFILE_SHARING, recipient, commandArgDescriptor);
      } else {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UNSET_VALUE);
        userCommandBuilder  = buildSettableUserPreferenceForFence  (context, SignalServiceProtos.FenceUserPrefs.PROFILE_SHARING, recipient, commandArgDescriptor);
      }

//      UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildProfileKeyShare(context, myProfileKey, commandArgDescriptor);

//TBD we let the backend collate users if necessary
//      List<Address> groupMembers= DatabaseFactory.getGroupDatabase(context).getCurrentMembers(recipient.getAddress().toGroupString());
//      for (Address member : groupMembers) {
//        if (Util.isOwnNumber(context, member))  continue;
//
//        Recipient           recipientMember     = Recipient.from(context, member, false);
//        SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();
//
//        userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipientMember.getUfrsvUidRaw()));
//        userRecordBuilder.setUsername("*");
//        userCommandBuilder.addTargetList(userRecordBuilder.build());
//      }

//      SignalServiceProtos.FenceRecord.Builder  fenceRecordBuilder   = SignalServiceProtos.FenceRecord.newBuilder();
//      fenceRecordBuilder.setFid(recipient.getUfsrvId());
//      userCommandBuilder.addFences(fenceRecordBuilder.build());

      if (true/*groupMembers.size()>0*/) {
        UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
        sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
      }
    } else {
      if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      } else {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);
      }

      userCommandBuilder  = UfsrvUserCommandProfileJob.buildProfileKeyShare(context, myProfileKey, commandArgDescriptor);

      SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

      userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
      userRecordBuilder.setUsername("*");
      userCommandBuilder.addTargetList(userRecordBuilder.build());

      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
      sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
    }

  }

  //KEY_ADDRESS for target user
  private void sendPresenceSharing () throws IOException, UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildPresenceShare(context,
                                                                                            isShare,
                                                                                            commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.getAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendReadReceiptSharing () throws IOException, UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildReadReceiptShare(context, isShare, commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.getAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendBlockingSharing () throws IOException, UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildShareListMessage(context, isShare, SignalServiceProtos.UserPrefs.BLOCKING, commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.getAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendContactSharing () throws IOException, UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildShareListMessage(context, isShare, SignalServiceProtos.UserPrefs.CONTACTS, commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.getAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  //KEY_EXTRA_ARG for b64 encoded avatar
  private void sendAvatarUpdate() throws IOException, UntrustedIdentityException, NetworkException
  {
    if (this.avatar==null) {
      if (!TextUtils.isEmpty(extraArg)) this.avatar = Base64.decode(extraArg);
    }

    if (!ProfileKeyUtil.hasProfileKey(context)) throw new AssertionError (String.format("profile key not set for this user"));

    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder=UfsrvUserCommandProfileJob.buildProfileUpdateForAvatar  (context, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder, false);

    try {
      final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

      if (avatar!=null) {
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

        AttachmentRecord attachmentRecord = ApplicationDependencies.getSignalServiceMessageSender().createAttachmentPointerProfileAvatar(profileAvatarData, ProfileKeyUtil.getProfileKey(context));
        org.whispersystems.libsignal.logging.Log.d(TAG, String.format("sendAvatarUpdate: Received attachment ID: '%s', digest:'%s' ", attachmentRecord.getId(), attachmentRecord.getDigest().toString()));

        ufsrvCommand.getUserCommandBuilder().addAttachments(attachmentRecord);
        userPrefBuilder.setValuesStr(attachmentRecord.getId());
      }

      userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
      userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.USERAVATAR);
      ufsrvCommand.getUserCommandBuilder().addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getUserCommandBuilder().getHeader().getWhen())//command not built yet
              .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendMessage(ufsrvMessage, Optional.absent(), ufsrvCommand);

      AvatarHelper.setAvatar(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), avatar);// ufsrv
    }
    catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (NullPointerException e) {
      Log.d(TAG, e.getStackTrace().toString());
      return;
    }
  }

  //KEY_EXTRA_ARG for prefid
  private void sendUserSettablePreference ()throws IOException, UntrustedIdentityException, NetworkException
  {
    boolean isSet = false;
    SignalServiceProtos.UserPrefs prefId = SignalServiceProtos.UserPrefs.values()[Integer.valueOf(extraArg)];
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;

    if (profileCommandDescriptor.getProfileOperationDescriptor().getProfileOperationMode() == IProfileOperationDescriptor.ProfileOperationMode.SET) {
      commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);
      isSet = true;
    }
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UNSET_VALUE);


    UserCommand.Builder userCommandBuilder  = buildSettableUserPreference(context, prefId, isSet, commandArgDescriptor);

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendUserSettableMultiPreference () throws IOException, UntrustedIdentityException, NetworkException
  {
    SignalServiceProtos.UserPrefs prefId = SignalServiceProtos.UserPrefs.values()[Integer.valueOf(extraArg)];
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;

    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    UserCommand.Builder userCommandBuilder  = buildMultiSettableUserPreference(context, prefId, (Integer)profileCommandDescriptor.getValueHolder(), commandArgDescriptor);

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }


  private void sendUpdate(SignalServiceMessageSender messageSender, UfsrvCommand ufsrvCommand)
          throws IOException, UntrustedIdentityException, NetworkException
  {
    try {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
          .withTimestamp(ufsrvCommand.getUser().getHeader().getWhen())
          .build();
      messageSender.sendMessage(ufsrvMessage, Optional.absent(), ufsrvCommand);
    } catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (AssertionError ex) {
      Log.d(TAG, ex.toString()); //most likely serialisation issue with UfsrvCommand
      return;
    }

//    catch (NullPointerException e)
//    {
//      Log.d(TAG, e.toString());
//      return;
//    }
  }

 public static UserCommand.Builder
 buildProfileUpdateForNickname  (@NonNull Context         context,
                                  @Nullable String       nickname,
                                  @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
{
  long      timeSentInMillis  = System.currentTimeMillis();

  Log.d(TAG, String.format("sendProfileUpdateForNickname: Updating nickname '%s'", nickname));

  UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
  CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
  final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

  userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
  userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.NICKNAME);
  userPrefBuilder.setValuesStr(nickname);
  userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

  commandHeaderBuilder.setWhen(timeSentInMillis);
  commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
  commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
  userCommandBuilder.setProfileKey(ByteString.copyFrom(ProfileKeyUtil.getProfileKey(context)));
  userCommandBuilder.setHeader(commandHeaderBuilder.build());

  return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildProfileUpdateForAvatar  (@NonNull Context         context, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setProfileKey(ByteString.copyFrom(ProfileKeyUtil.getProfileKey(context)));
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildProfileKeyShare  (@NonNull Context         context,
                          @Nullable byte[]       profileKey,
                          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BLOB);
    userPrefBuilder.setVauesBlob(ByteString.copyFrom(profileKey));
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.PROFILE);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    userCommandBuilder.setProfileKey(ByteString.copyFrom(profileKey));

    return  userCommandBuilder;
  }

  //for boolean user pref for fences
  public static UserCommand.Builder
  buildSettableUserPreferenceForFence  (@NonNull  Context         context,
                                        @NonNull  SignalServiceProtos.FenceUserPrefs preference_id,
                                        Recipient groupReceipient,
                                        @NonNull  UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder           fenceRecordBuilder    = FenceRecord.newBuilder();
    FenceUserPreference.Builder   userPrefBuilder       = FenceUserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setPrefId(preference_id);
    userPrefBuilder.setValuesInt(commandArgDescriptor.getArg()==SignalServiceProtos.CommandArgs.SET_VALUE?1:0);
    userCommandBuilder.addAllFencePrefs(new LinkedList<FenceUserPreference>(){{add(userPrefBuilder.build());}});

    fenceRecordBuilder.setFid(groupReceipient.getUfsrvId());
    userCommandBuilder.addAllFences(new LinkedList<FenceRecord>(){{add(fenceRecordBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildShareListMessage  (@NonNull Context         context,
                          boolean  isShared,
                          SignalServiceProtos.UserPrefs userPrefId,
                          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setValuesInt(isShared?1:0);
    userPrefBuilder.setPrefId(userPrefId);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  /**
   * Build command for sharing our presence information with a given user
   * @param context
   * @param commandArgDescriptor
   * @return
   */
  public static UserCommand.Builder
  buildPresenceShare  (@NonNull Context         context,
                       boolean  isShared,
                       @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setValuesInt(isShared?1:0);
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.NETSTATE);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildReadReceiptShare  (@NonNull Context         context,
                       boolean  isShared,
                       @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setValuesInt(isShared?1:0);
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.READ_RECEIPT);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildBlockingShare  (@NonNull Context         context,
                          boolean  isShared,
                          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setValuesInt(isShared?1:0);
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.BLOCKING);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildSettableUserPreference  (@NonNull Context         context,
                                @NonNull  SignalServiceProtos.UserPrefs preference_id,
                                boolean       value,
                                @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    UserPreference.Builder        userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setPrefId(preference_id);
    userPrefBuilder.setValuesInt(value?1:0);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildMultiSettableUserPreference  (@NonNull Context         context,
                                @NonNull  SignalServiceProtos.UserPrefs preference_id,
                                Integer       value,
                                @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    UserPreference.Builder        userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setPrefId(preference_id);
    userPrefBuilder.setValuesInt(value);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  private Optional<SignalServiceAttachmentStream> getAvatar(@Nullable Uri uri) throws IOException {
    if (uri == null) {
      return Optional.absent();
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      try {
        Uri                 displayPhotoUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Photo.DISPLAY_PHOTO);
        AssetFileDescriptor fd              = context.getContentResolver().openAssetFileDescriptor(displayPhotoUri, "r");

        return Optional.of(SignalServiceAttachment.newStreamBuilder()
                .withStream(fd.createInputStream())
                .withContentType("image/*")
                .withLength(fd.getLength())
                .build());
      } catch (IOException e) {
        Log.w(TAG, e);
      }
    }

    Uri photoUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY);

    if (photoUri == null) {
      return Optional.absent();
    }

    Cursor cursor = context.getContentResolver().query(photoUri,
            new String[] {
                    ContactsContract.CommonDataKinds.Photo.PHOTO,
                    ContactsContract.CommonDataKinds.Phone.MIMETYPE
            }, null, null, null);

    try {
      if (cursor != null && cursor.moveToNext()) {
        byte[] data = cursor.getBlob(0);

        if (data != null) {
          return Optional.of(SignalServiceAttachment.newStreamBuilder()
                  .withStream(new ByteArrayInputStream(data))
                  .withContentType("image/*")
                  .withLength(data.length)
                  .build());
        }
      }

      return Optional.absent();
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  private File createTempFile(String prefix) throws IOException {
    File file = File.createTempFile(prefix, "tmp", context.getCacheDir());
    file.deleteOnExit();

    return file;
  }

  private static class NetworkException extends Exception {

    public NetworkException(Exception ioe) {
      super(ioe);
    }
  }

  public static class Factory implements Job.Factory<UfsrvUserCommandProfileJob> {
    @Override
    public @NonNull UfsrvUserCommandProfileJob create(@NonNull Parameters parameters, @NonNull Data data) {
      String extraArgs = data.getString(KEY_EXTRA_ARG);
      if (!TextUtils.isEmpty(extraArgs)) {
        return new UfsrvUserCommandProfileJob(parameters, data.getString(KEY_COMMAND_DESCRIPTOR), extraArgs);
      } else {
        return new UfsrvUserCommandProfileJob(parameters, data.getString(KEY_COMMAND_DESCRIPTOR), Address.fromSerialized(data.getString(KEY_ADDRESS)));
      }
    }
  }

}
