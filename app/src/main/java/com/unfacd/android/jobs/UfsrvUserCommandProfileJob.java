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
import android.text.TextUtils;

import com.google.protobuf.ByteString;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
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
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserPrefs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;
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
import java.util.Collections;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


public class UfsrvUserCommandProfileJob extends BaseJob {
  public static final String KEY = "UfsrvUserCommandProfileJob";

  private static final String KEY_COMMAND_DESCRIPTOR        = "profile_command_descriptor";
  private static final String KEY_ADDRESS                   = "address";
  private static final String KEY_EXTRA_ARG                 = "extra_arg";

  private static final String TAG = Log.tag(UfsrvUserCommandProfileJob.class);

  private byte[]                    avatar = null;
  private ProfileCommandDescriptor  profileCommandDescriptor;

  private String    extraArg;
  private Recipient recipient;

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
      CONTACTS(9),
      REGO_PIN(10),
      BLOCKED_FENCE(11),
      GEOLOC_TRIGGER(12),
      BASELOC_ANCHOR_ZONE(13),
      HOMEBASE_GEOLOC(14);

      private int value;

      ProfileType(int value) {
        this.value = value;
      }

      public int getValue() {
        return value;
      }
    }

    enum OperationMode
    {
      SET(0),
      UNSET(1);

      private int value;

      OperationMode(int value) {
        this.value = value;
      }

      public int getValue() {
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

    public ProfileCommandDescriptor(ProfileOperationDescriptor profileOperationDescriptor) {
      this.profileOperationDescriptor = profileOperationDescriptor;
    }

    public ProfileCommandDescriptor(ProfileOperationDescriptor profileOperationDescriptor, Object valueHolder) {
      this.profileOperationDescriptor = profileOperationDescriptor;
      this.valueHolder                = valueHolder;
    }

    public ProfileOperationDescriptor getOperationDescriptor() {
      return profileOperationDescriptor;
    }

    public Object getValueHolder() {
      return valueHolder;
    }

    public void setProfileOperationDescriptor (ProfileOperationDescriptor profileOperationDescriptor) {
      this.profileOperationDescriptor = profileOperationDescriptor;
    }

    static public class ProfileOperationDescriptor implements IProfileOperationDescriptor, Serializable {
      IProfileOperationDescriptor.ProfileType profileType;
      OperationMode profileOperationMode;
      IProfileOperationDescriptor.ProfileOperationScope profileOperationScope;
      String auxiliary;

      public ProfileType getProfileType() {
        return profileType;
      }

      public OperationMode getMode() {
        return profileOperationMode;
      }

      public ProfileOperationScope getProfileOperationScope() {
        return profileOperationScope;
      }

      public void setProfileType(ProfileType profileType) {
        this.profileType = profileType;
      }

      public void setOperationMode(OperationMode profileOperationMode) {
        this.profileOperationMode = profileOperationMode;
      }

      public void setOperationScope(ProfileOperationScope profileOperationScope) {
        this.profileOperationScope = profileOperationScope;
      }

      public void setAuxiliary(String auxiliary) {
        this.auxiliary = auxiliary;
      }
    }
  }

  static public class ProfileCommandHelper {
    static public  String serialise(ProfileCommandDescriptor profileCommandDescriptor)
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

    static public ProfileCommandDescriptor deserialise(String profileCommandDescriptor)
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
      if (address != null)  {
        if (this.profileCommandDescriptor.getOperationDescriptor().profileOperationScope == IProfileOperationDescriptor.ProfileOperationScope.GROUP) {
          recipient = Recipient.live(SignalDatabase.groups().getGroupRecipientId(GroupId.parse(address.toGroupString())).get()).get();
        } else {
          this.recipient = Recipient.live(address.serialize()).get();
        }
      } else                  this.recipient  = null;
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

  public UfsrvUserCommandProfileJob(String profileCommandDescriptor, String extraArg) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         profileCommandDescriptor,
         extraArg);

  }

  public UfsrvUserCommandProfileJob(String profileCommandDescriptor, Address address) {
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
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    switch (profileCommandDescriptor.getOperationDescriptor().profileType) {
      case PROFILE:
        sendProfileSharing();
        break;
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
      case REGO_PIN:
        sendProfileRegoPin();
        break;

      case BLOCKED_FENCE:
        sendBlockedFence();
        break;

      case GEOLOC_TRIGGER:
        sendGeolocTriggerZone();
        break;

      case BASELOC_ANCHOR_ZONE:
        sendBaselocAnchorZone();
        break;

      case HOMEBASE_GEOLOC:
        sendHomebaseGeoLoc();
        break;

      default:
        Log.w(TAG, "Unknown profile type received: "+profileCommandDescriptor.getOperationDescriptor().profileType);

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

    if (avatar != null && avatar.length > 0)    builder.putString(KEY_EXTRA_ARG, Base64.encodeBytes(avatar));
    else                                    builder.putString(KEY_EXTRA_ARG, extraArg);

    if (profileCommandDescriptor != null) builder.putString(KEY_COMMAND_DESCRIPTOR, ProfileCommandHelper.serialise(profileCommandDescriptor));
    else                                 builder.putString(KEY_COMMAND_DESCRIPTOR, "");

    if (recipient != null)  builder.putString(KEY_ADDRESS, recipient.requireAddress().serialize());
    else                  builder.putString(KEY_ADDRESS, "");

    if (!TextUtils.isEmpty(extraArg)) builder.putString(KEY_EXTRA_ARG, extraArg);
    else                              builder.putString(KEY_EXTRA_ARG, "");

    return builder.build();
  }

  @Override
  public void onFailure() {

  }

  //KEY_EXTRA_ARG for profilename
  private void sendProfileNameUpdate() throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = UfsrvUserCommandProfileJob.buildProfileUpdateForNickname(context, extraArg, commandArgDescriptor);
    if (userCommandBuilder != null) {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

      sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
    }
  }

  //KEY_EXTRA_ARG for profilename
  private void sendProfileRegoPin() throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = buildProfileUpdateForRegoPin(context, extraArg, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  //KEY_ADDRESS for group id
  private void sendProfileSharing()  throws UntrustedIdentityException, NetworkException
  {
    byte[] myProfileKey = ProfileKeyUtil.getSelfProfileKey().serialize();

    UfsrvCommandUtils.CommandArgDescriptor  commandArgDescriptor;
    UserCommand.Builder                     userCommandBuilder;

    if (recipient.isGroup()) {
      if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);
        userCommandBuilder  = buildSettableUserPreferenceForFence(context, SignalServiceProtos.FenceUserPrefs.PROFILE_SHARING, recipient, commandArgDescriptor);
      } else {
        commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.FENCE_PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UNSET_VALUE);
        userCommandBuilder  = buildSettableUserPreferenceForFence(context, SignalServiceProtos.FenceUserPrefs.PROFILE_SHARING, recipient, commandArgDescriptor);
      }

//      UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildProfileKeyShare(context, myProfileKey, commandArgDescriptor);

//TBD we let the backend collate users if necessary
//      List<Address> groupMembers= SignalDatabase.groups().getCurrentMembers(recipient.getAddress().toGroupString());
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
      if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
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
  private void sendPresenceSharing() throws UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildPresenceShare(context,
                                                                                            isShare,
                                                                                            commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.requireAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendReadReceiptSharing() throws UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildReadReceiptShare(context, isShare, commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.requireAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendBlockingSharing() throws UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildShareListMessage(context, isShare, UserPrefs.BLOCKING, commandArgDescriptor);

    UserRecord.Builder  userRecordBuilder   = UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.requireAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendBlockedFence() throws UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    Long fid = Long.valueOf(extraArg);
    Recipient recipientGroup = Recipient.resolved(RecipientUfsrvId.from(fid));

    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = buildSettableUserPreferenceForBlockedFence(context, fid, UserPrefs.BLOCKED_FENCE, commandArgDescriptor);
    UserCommand userCommand = userCommandBuilder.build();
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommand, false);
    SignalDatabase.sms().insertProfileLog(fid,recipientGroup.getId(), userCommand.getHeader().getWhen(), ufsrvCommand.buildToSerialise(), true);

//    long messageId = insertBlockedFenceOutgoingMessage(context, recipientGroup, userCommand);

    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  //TBD
  private long insertBlockedFenceOutgoingMessage(Context context, Recipient recipientGroup, UserCommand userCommand)
  {
    try {
      SignalServiceProtos.GroupContext.Builder groupContextBuilder = SignalServiceProtos.GroupContext.newBuilder()
              .setId(ByteString.copyFrom(recipientGroup.getGroupId().get().getDecodedId()))
              .setType(SignalServiceProtos.GroupContext.Type.UPDATE);
      SignalServiceProtos.GroupContext groupContext = groupContextBuilder.build();

      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder().setUserCommand(userCommand).setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_USER);

      OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipientGroup, groupContext, null, userCommand.getHeader().getWhen(), 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandWireBuilder.build());
      ThreadDatabase threadDatabase = SignalDatabase.threads();
      MessageDatabase database       = SignalDatabase.mms();

      long      allocatedThreadId = threadDatabase.getOrCreateValidThreadId(outgoingMessage.getRecipient(), -1, outgoingMessage.getDistributionType());
      long      messageId         = database.insertMessageOutbox(outgoingMessage, allocatedThreadId, false, null);

      return messageId;
    } catch (MmsException x) {
      return -1;
    }

  }

  private void sendContactSharing() throws  UntrustedIdentityException, NetworkException
  {
    boolean isShare = false;
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.ADDED_VALUE);
      isShare = true;
    }
    else commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder  = UfsrvUserCommandProfileJob.buildShareListMessage(context, isShare, SignalServiceProtos.UserPrefs.CONTACTS, commandArgDescriptor);

    SignalServiceProtos.UserRecord.Builder  userRecordBuilder   = SignalServiceProtos.UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername(recipient.requireAddress().serialize());
    userCommandBuilder.addTargetList(userRecordBuilder.build());

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  //KEY_EXTRA_ARG for b64 encoded avatar
  private void sendAvatarUpdate() throws IOException, UntrustedIdentityException, NetworkException
  {
    if (this.avatar == null) {
      if (!TextUtils.isEmpty(extraArg)) this.avatar = Base64.decode(extraArg);
    }

    Recipient self       = Recipient.self();
    byte[]    profileKey = self.getProfileKey();
    if (profileKey == null) throw new AssertionError (String.format("profile key not set for this user"));

    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = UfsrvUserCommandProfileJob.buildProfileUpdateForAvatar  (context, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder, false, false);

    try {
      final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

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

        AttachmentRecord attachmentRecord = ApplicationDependencies.getSignalServiceMessageSender().createAttachmentPointerProfileAvatar(profileAvatarData, ProfileKeyUtil.getSelfProfileKey().serialize());
        org.signal.libsignal.protocol.logging.Log.d(TAG, String.format("sendAvatarUpdate: Received attachment ID: '%s', digest:'%s' ", attachmentRecord.getId(), attachmentRecord.getDigest().toString()));

        ufsrvCommand.getUserCommandBuilder().addAttachments(attachmentRecord);
        userPrefBuilder.setValuesStr(attachmentRecord.getId());
      }

      userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
      userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.USERAVATAR);
      ufsrvCommand.getUserCommandBuilder().addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(ufsrvCommand.getUserCommandBuilder().getHeader().getWhen())//command not built yet
              .build();

      ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);

      try {
        AvatarHelper.setAvatar(context, Recipient.self().getId(), new ByteArrayInputStream(avatar));
      } catch (AssertionError e) {
        throw new IOException("Failed to copy stream. Likely a Conscrypt issue.", e);
      }
    }
    catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (NullPointerException e) {
      Log.d(TAG, e.getStackTrace().toString());
      return;
    }
  }

  //KEY_EXTRA_ARG for prefid
  private void sendUserSettablePreference() throws  UntrustedIdentityException, NetworkException
  {
    boolean isSet = false;
    SignalServiceProtos.UserPrefs prefId = SignalServiceProtos.UserPrefs.values()[Integer.valueOf(extraArg)];
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;

    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) {
      commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);
      isSet = true;
    }
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UNSET_VALUE);


    UserCommand.Builder userCommandBuilder  = buildSettableUserPreference(context, prefId, isSet, commandArgDescriptor);

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendUserSettableMultiPreference() throws UntrustedIdentityException, NetworkException
  {
    SignalServiceProtos.UserPrefs prefId = SignalServiceProtos.UserPrefs.values()[Integer.valueOf(extraArg)];
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;

    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    UserCommand.Builder userCommandBuilder  = buildMultiSettableUserPreference(context, prefId, (Integer)profileCommandDescriptor.getValueHolder(), commandArgDescriptor);

    UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);
    sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
  }

  private void sendGeolocTriggerZone() throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = UfsrvUserCommandProfileJob.buildProfileUpdateForIntegerValue(UserPrefs.GEOLOC_TRIGGER, Integer.parseInt(extraArg), commandArgDescriptor);
    if (userCommandBuilder != null) {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

      sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
    }
  }

  private void sendBaselocAnchorZone() throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = UfsrvUserCommandProfileJob.buildProfileUpdateForIntegerValue(UserPrefs.BASELOC_ANCHOR_ZONE, Integer.parseInt(extraArg), commandArgDescriptor);
    if (userCommandBuilder != null) {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

      sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
    }
  }

  private void sendHomebaseGeoLoc() throws UntrustedIdentityException, NetworkException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    if (profileCommandDescriptor.getOperationDescriptor().getMode() == IProfileOperationDescriptor.OperationMode.SET) commandArgDescriptor= new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE);
    else commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE);

    UserCommand.Builder userCommandBuilder = UfsrvUserCommandProfileJob.buildProfileUpdateForStringValue(UserPrefs.HOMEBASE_GEOLOC, extraArg, commandArgDescriptor);
    if (userCommandBuilder != null) {
      UfsrvCommand ufsrvCommand = new UfsrvCommand(userCommandBuilder.build(), false);

      sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), ufsrvCommand);
    }
  }

  private void sendUpdate(SignalServiceMessageSender messageSender, UfsrvCommand ufsrvCommand)
          throws UntrustedIdentityException, NetworkException
  {
    try {
      SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
          .withTimestamp(ufsrvCommand.getUser().getHeader().getWhen())
          .build();
      messageSender.sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
    } catch (IOException | InvalidKeyException ioe) {
      throw new NetworkException(ioe);
    } catch (AssertionError ex) {
      Log.d(TAG, ex.toString()); //most likely serialisation issue with UfsrvCommand
      return;
    }
  }

   private static UserCommand.Builder
   buildProfileUpdateForNickname(@NonNull Context context, @Nullable String nickname, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
   {
    long      timeSentInMillis  = System.currentTimeMillis();

    if (TextUtils.isEmpty(nickname) && commandArgDescriptor.getArg() != CommandArgs.DELETED_VALUE) {
      Log.e(TAG, String.format("sendProfileUpdateForNickname: NICKNAME WAS NULL"));
      return null;
    }

    Log.d(TAG, String.format("sendProfileUpdateForNickname: Updating nickname '%s'", nickname));

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.NICKNAME);
    if (!TextUtils.isEmpty(nickname)) userPrefBuilder.setValuesStr(nickname);
    userCommandBuilder.addAllPrefs(new LinkedList<>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setProfileKey(ByteString.copyFrom(ProfileKeyUtil.getSelfProfileKey().serialize()));
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  //Generalised for single integer values
  private static UserCommand.Builder
  buildProfileUpdateForIntegerValue(SignalServiceProtos.UserPrefs userPref, int prefValue, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    Log.d(TAG, String.format(Locale.getDefault(), "buildProfileUpdateForIntegerValue: Updating userPref '%d' with value:'%d'", userPref.getNumber(), prefValue));

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.INT);
    userPrefBuilder.setPrefId(userPref);
    userPrefBuilder.setValuesInt(prefValue);
    userCommandBuilder.addAllPrefs(new LinkedList<>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
   }

  //Generalised for single string values
  private static UserCommand.Builder
  buildProfileUpdateForStringValue(SignalServiceProtos.UserPrefs userPref, String prefValue, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    Log.d(TAG, String.format(Locale.getDefault(), "buildProfileUpdateForStringValue: Updating userPref '%d' with value:'%s'", userPref.getNumber(), prefValue));

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
    userPrefBuilder.setPrefId(userPref);
    userPrefBuilder.setValuesStr(prefValue);
    userCommandBuilder.addAllPrefs(new LinkedList<>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  private static UserCommand.Builder
  buildProfileUpdateForRegoPin(@NonNull Context context, @Nullable String regopin, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    Log.d(TAG, String.format("buildProfileUpdateForRegoPin: Updating regopin '%s'", regopin));

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.STR);
    userPrefBuilder.setPrefId(SignalServiceProtos.UserPrefs.REGO_PIN);
    userPrefBuilder.setValuesStr(regopin);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setProfileKey(ByteString.copyFrom(ProfileKeyUtil.getSelfProfileKey().serialize()));
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildProfileUpdateForAvatar(@NonNull Context context, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(UserCommand.CommandTypes.PREFERENCE_VALUE);
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    userCommandBuilder.setProfileKey(ByteString.copyFrom(ProfileKeyUtil.getSelfProfileKey().serialize()));
    userCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  userCommandBuilder;
  }

  public static UserCommand.Builder
  buildProfileKeyShare(@NonNull Context context, @Nullable byte[] profileKey, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
  buildSettableUserPreferenceForFence(@NonNull Context context, @NonNull SignalServiceProtos.FenceUserPrefs preference_id, Recipient groupReceipient, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder           fenceRecordBuilder    = FenceRecord.newBuilder();
    FenceUserPreference.Builder   userPrefBuilder       = FenceUserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.BOOL);
    userPrefBuilder.setPrefId(preference_id);
    userPrefBuilder.setValuesInt(commandArgDescriptor.getArg() == SignalServiceProtos.CommandArgs.SET_VALUE? 1 : 0);
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
  buildShareListMessage(@NonNull Context context, boolean isShared, UserPrefs userPrefId, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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

  //for boolean user pref for fences
  public static UserCommand.Builder
  buildSettableUserPreferenceForBlockedFence(@NonNull Context context, Long fid, UserPrefs userPrefId, @NonNull  UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long      timeSentInMillis  = System.currentTimeMillis();

    UserCommand.Builder           userCommandBuilder    = UserCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder           fenceRecordBuilder    = FenceRecord.newBuilder();

    final UserPreference.Builder  userPrefBuilder       = UserPreference.newBuilder();

    userPrefBuilder.setType(SignalServiceProtos.PreferenceType.MULTI);
    userPrefBuilder.setPrefId(userPrefId);
    userCommandBuilder.addAllPrefs(new LinkedList<UserPreference>(){{add(userPrefBuilder.build());}});

    fenceRecordBuilder.setFid(fid);
    userCommandBuilder.addAllFencesBlocked(new LinkedList<FenceRecord>(){{add(fenceRecordBuilder.build());}});

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
  buildPresenceShare(@NonNull Context context, boolean isShared, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
  buildReadReceiptShare(@NonNull Context context, boolean isShared, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
  buildBlockingShare(@NonNull Context context, boolean isShared, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
  buildSettableUserPreference(@NonNull Context context, @NonNull SignalServiceProtos.UserPrefs preference_id, boolean value, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
  buildMultiSettableUserPreference(@NonNull Context context, @NonNull SignalServiceProtos.UserPrefs preference_id, Integer value, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
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
      return Optional.empty();
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
      return Optional.empty();
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

      return Optional.empty();
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
