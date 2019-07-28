package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.fence.FenceAlreadyExistsException;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.util.InvalidNumberException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;


public class GroupManager {

  //
  // netwok_broadcas=0, network public=1, network priva=2, user [ublic=3, user_private=4 user_broadcast=5

  private static final String TAG = GroupManager.class.getSimpleName();

  public static @NonNull GroupActionResult createGroup(@NonNull  Context        context,
                                                       @NonNull  Set<Recipient> members,
                                                       @Nullable Bitmap         avatar,
                                                       @Nullable String         name,
                                                                  boolean        mms,
                                                       @Nullable String         cname,
                                                       long           fid,
                                                       int             maxMembers,
                                                       GroupDatabase.GroupType groupType,
                                                       GroupDatabase.PrivacyMode privacyMode,
                                                       GroupDatabase.DeliveryMode deliveryMode,
                                                       GroupDatabase.JoinMode joinMode,
                                                       RecipientDatabase.GroupPermission[] groupPermissions)//
          throws FenceAlreadyExistsException //
  {
    final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);
    final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    final String        groupId         = GroupUtil.getEncodedId(groupDatabase.allocateGroupId(), mms);
    final Recipient     groupRecipient  = Recipient.from(context, Address.fromSerialized(groupId), false);
    final Set<Address>  memberAddresses = getMemberAddresses(members);
    //
    final Set<Address> membersInvited  = getMemberAddresses(members);
    //

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    long id = groupDatabase.create(groupId, name, new LinkedList<>(memberAddresses), null, null, cname+name,
            0.0, 0.0,
            GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED, fid, maxMembers,
            GroupDatabase.GroupType.USER, privacyMode, deliveryMode, joinMode);

    if (!mms) {
      if (id != -1) {
        DatabaseFactory.getRecipientDatabase(context).setGroupPermissions(groupRecipient, groupPermissions[EnumFencePermissions.PRESENTATION.getValue() - 1]);
        DatabaseFactory.getRecipientDatabase(context).setGroupPermissions(groupRecipient, groupPermissions[EnumFencePermissions.MEMBERSHIP.getValue() - 1]);
        DatabaseFactory.getRecipientDatabase(context).setGroupPermissions(groupRecipient, groupPermissions[EnumFencePermissions.MESSAGING.getValue() - 1]);
        DatabaseFactory.getRecipientDatabase(context).setGroupPermissions(groupRecipient, groupPermissions[EnumFencePermissions.ATTACHING.getValue() - 1]);
        DatabaseFactory.getRecipientDatabase(context).setGroupPermissions(groupRecipient, groupPermissions[EnumFencePermissions.CALLING.getValue() - 1]);

        groupDatabase.updateAvatar(groupId, avatarBytes, null);

        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(groupRecipient, true);
        DatabaseFactory.getRecipientDatabase(context).setRegistered(groupRecipient, RecipientDatabase.RegisteredState.REGISTERED);// group registered by default
        DatabaseFactory.getRecipientDatabase(context).setRecipientType(groupRecipient, Recipient.RecipientType.GROUP);//

        //
        return sendGroupUpdateUf(context, groupId,
                                 memberAddresses, membersInvited, name,
                                 avatarBytes,
                                 Long.valueOf(0),
                                 maxMembers,
                                 groupType,
                                 privacyMode,
                                 deliveryMode,
                                 joinMode,
                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.JOIN_VALUE, 0),
                                 groupPermissions);
      }
      else return new GroupActionResult(null, -1);
    } else {
//      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
      long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
      return new GroupActionResult(groupRecipient, threadId);
    }
  }

  public static @NonNull GroupActionResult
  createPrivateGroup(@NonNull  Context        context,
                     @NonNull  Set<Recipient> members,
                     @Nullable String         name,
                     boolean                  mms,
                     @Nullable String         cname,
                     double                   longitude,
                     double                   latitude) {
    final GroupDatabase groupDatabase     = DatabaseFactory.getGroupDatabase(context);
    final byte[]        groupIdDecoded    = groupDatabase.allocateGroupId();
    final String        groupId           = GroupUtil.getEncodedId(groupDatabase.allocateGroupId(), false);
    final Set<Address>  memberAddresses   = getMemberAddresses(members);
    final Recipient     groupRecipient    = Recipient.from(context, Address.fromSerialized(groupId), false);
    final Set<Address>  memberAddressesInvited  = getMemberAddresses(members);//

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    if (name == null) {
      name = String.format("%d.%s", TextSecurePreferences.getUserId(context), Hex.toStringCondensed(groupIdDecoded));
    }
    long id = groupDatabase.create(groupId, name,
                                 new LinkedList<>(memberAddresses), null, null,
                                  cname+name,
                                  longitude, latitude, GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED, 0, 2,
                                  GroupDatabase.GroupType.USER,
                                 GroupDatabase.PrivacyMode.PRIVATE,
                                 GroupDatabase.DeliveryMode.MANY,
                                 GroupDatabase.JoinMode.INVITE);

    if (!mms) {
      //
      if (id != -1) {
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(groupRecipient, true);
        DatabaseFactory.getRecipientDatabase(context).setRegistered(groupRecipient, RecipientDatabase.RegisteredState.REGISTERED);// group registered by default
        DatabaseFactory.getRecipientDatabase(context).setRecipientType(groupRecipient, Recipient.RecipientType.GROUP);//

        return sendGroupCreatePrivate(context, groupId,
                                      memberAddresses, memberAddressesInvited, name != null ? name : Hex.toStringCondensed(groupIdDecoded));
      }
      else return new GroupActionResult(null, -1);
    } else {
      long      threadId       = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
      return new GroupActionResult(groupRecipient, threadId);
    }
  }


  //compare exiting group members with ones added via the group update ui
  public static Pair<Set<Recipient>, Set<Recipient>> getAddedGroupMembers (Context context, Set<Recipient> members, Set<Recipient>membersPreExisting, GroupDatabase.GroupRecord groupRecord)
  {
    HashMap<String, Recipient> membersNew = new HashMap<>();
    HashMap<String, Recipient> membersPre = new HashMap<>();
    HashSet<Recipient> membersSetAdded     = new HashSet();
    HashSet<Recipient> membersSetRemoved   = new HashSet();

    for (Recipient recipient : members)                   membersNew.put(recipient.getAddress().serialize(), recipient);// serialise
    for (Recipient recipient : membersPreExisting)        membersPre.put(recipient.getAddress().serialize(), recipient);
    Log.d (TAG, String.format("getAddedGroupMembers: membersNew sze:'%d', membersPreExisting size: '%d'", membersNew.size(), membersPre.size()));

    for (Recipient recipient : members) {
      if (!membersPre.containsKey(recipient.getAddress().serialize())) membersSetAdded.add(recipient); // serialise
    }

    for (Recipient recipient : membersPreExisting) {
      if (!membersNew.containsKey(recipient.getAddress().serialize())) membersSetRemoved.add(recipient);
    }

    return new Pair (membersSetAdded, membersSetRemoved);

  }

  //todo: ideally, we want to return list, but 'SignalGroupTask' will need to be modified to return this new type, which also affects create group
  //public static ArrayList<GroupActionResult> updateGroup(@NonNull  Context        context,
  public static GroupActionResult updateGroup(@NonNull  Context        context,
                                              @NonNull  String         groupId,
                                              @NonNull  Set<Recipient> members,
                                              @Nullable Bitmap         avatar,
                                              @Nullable String         name,
                                              @NonNull  GroupDatabase.GroupRecord groupRecord,
                                              @NonNull   Set<Recipient> preExistingMembers,
                                              int         maxMembers,
                                              GroupDatabase.DeliveryMode deliveryMode)//
      throws InvalidNumberException
  {
    if (!GroupUtil.isMmsGroup(groupId)) {
      final GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
      final Set<Address> memberAddresses = getMemberAddresses(members);
      final byte[] avatarBytes = BitmapUtil.toByteArray(avatar);
      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
      GroupActionResult groupActionResultName =new GroupActionResult(groupRecipient, threadId);
      GroupActionResult groupActionResultAvatar = new GroupActionResult(groupRecipient, threadId);
      ArrayList<GroupActionResult> groupActionResults = new ArrayList<>();

      if (!name.equals(groupRecord.getTitle())) {
        groupActionResultName = sendGroupUpdateName(context, groupId, name, Long.valueOf(groupRecord.getFid()),
                                                    new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.FNAME_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));
        groupActionResults.add(groupActionResultName);
      } else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      if (avatar != null) {
        if (!Arrays.equals(avatarBytes, groupRecord.getAvatar())) {
          groupActionResultAvatar = sendGroupUpdateAvatar(context, groupId, avatarBytes, new Long(groupRecord.getFid()),
                                                          new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.AVATAR_VALUE, CommandArgs.UPDATED_VALUE));
//          groupDatabase.updateAvatar(groupId, avatarBytes); // do it on server confirmation
          groupActionResults.add(groupActionResultAvatar);
        } else {
          //todo:user message not necessarily as user may not want to change that it is just present as a loaded field value
        }
      }

      if (maxMembers!=groupRecord.getMaxmembers()) {
        groupActionResultName = sendGroupUpdateMaxMembers(context, groupId, maxMembers, Long.valueOf(groupRecord.getFid()),
                                                    new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.MAXMEMBERS_VALUE, CommandArgs.UPDATED_VALUE));

        groupActionResults.add(groupActionResultName);
      } else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      if (deliveryMode!=GroupDatabase.DeliveryMode.values()[groupRecord.getDeliveryType()]) {
        groupActionResultName = sendGroupUpdateDeliveryMode(context, groupId, deliveryMode, Long.valueOf(groupRecord.getFid()),
                                                          new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.DELIVERY_MODE_VALUE, CommandArgs.UPDATED_VALUE));

        groupActionResults.add(groupActionResultName);
      } else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      Pair<Set<Recipient>, Set<Recipient>> membersAddedremoved = getAddedGroupMembers(context, members, preExistingMembers, groupRecord);
      //final Set<String> memberE164NumbersInvited  = getE164Numbers(context, membersAddedremoved.first);
      final Set<Address> memberAddressesInvited = getMemberAddresses(membersAddedremoved.first);
      //final Set<String> memberE164NumbersRemoved  = getE164Numbers(context, membersAddedremoved.second);
      final Set<Address> memberAddressesRemoved = getMemberAddresses(membersAddedremoved.second);

      if (!membersAddedremoved.first.isEmpty()) {
        Log.d(TAG, String.format("membersAddedremoved: INVITED LIST size:'%d'...", membersAddedremoved.first.size()));
        GroupActionResult groupActionResultAdded = sendGroupUpdateInvitedMembers(context,
                                                                                 groupId,
                                                                                 new Pair<>(memberAddressesInvited, memberAddressesRemoved),
                                                                                 Long.valueOf(groupRecord.getFid()),
                                                                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.INVITE_VALUE, CommandArgs.ADDED_VALUE));

//      memberE164Numbers.add(TextSecurePreferences.getLocalNumber(context));
//      groupDatabase.updateMembers(groupId, new LinkedList<>(memberE164Numbers));

        groupActionResults.add(groupActionResultAdded);
      }

      if (!membersAddedremoved.second.isEmpty()) {
        Log.d(TAG, String.format("membersAddedremoved: Removed list size:'%d'...", membersAddedremoved.second.size()));
        GroupActionResult groupActionResultAdded = sendGroupUpdateInvitedMembers(context,
                                                                                 groupId,
                                                                                 new Pair<>(memberAddressesInvited, memberAddressesRemoved),
                                                                                 Long.valueOf(groupRecord.getFid()),
                                                                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.INVITE_VALUE, CommandArgs.DELETED_VALUE));

//      memberE164Numbers.add(TextSecurePreferences.getLocalNumber(context));
//      groupDatabase.updateMembers(groupId, new LinkedList<>(memberE164Numbers));

        groupActionResults.add(groupActionResultAdded);
      }


//    return groupActionResults;
      if (groupActionResults.size()>0) return groupActionResults.get(0);//return the first for now
      else return new GroupActionResult(groupRecipient, threadId); //todo: fix return as currently errors are not correctly indicated
    }
    else {//local mms group
      final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
      final Set<Address>  memberAddresses = getMemberAddresses(members);
      final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);

      memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
      groupDatabase.updateMembers(groupId, new LinkedList<>(memberAddresses));
      groupDatabase.updateTitle(groupId, name);
      groupDatabase.updateAvatar(groupId, avatarBytes, null);

      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
      return new GroupActionResult(groupRecipient, threadId);
    }

  }


//- orig
//public static GroupActionResult updateGroup(@NonNull  Context        context,
//                                            @NonNull  MasterSecret   masterSecret,
//                                            @NonNull  String         groupId,
//                                            @NonNull  Set<Recipient> members,
//                                            @Nullable Bitmap         avatar,
//                                            @Nullable String         name)
//        throws InvalidNumberException
//{
//  final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
//  final Set<Address>  memberAddresses = getMemberAddresses(members);
//  final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);
//
//  memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
//  groupDatabase.updateMembers(groupId, new LinkedList<>(memberAddresses));
//  groupDatabase.updateTitle(groupId, name);
//  groupDatabase.updateAvatar(groupId, avatarBytes);
//
//  if (!GroupUtil.isMmsGroup(groupId)) {
//    return sendGroupUpdate(context, masterSecret, groupId, memberAddresses, name, avatarBytes);
//  } else {
//    Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
//    long      threadId       = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
//    return new GroupActionResult(groupRecipient, threadId);
//  }
//}

  private static Set<Address> getMemberAddresses(Collection<Recipient> recipients) {
    final Set<Address> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.getAddress());
    }

    return results;
  }

  private static GroupActionResult sendGroupUpdateName(@NonNull  Context      context,
                                                     @NonNull  String       groupId,
                                                     @Nullable String       groupName,
                                                     @NonNull  Long         groupfId,
                                                     @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    try
    {
      Address groupAddress = Address.fromSerialized(groupId);
      long timeSentInMillis = System.currentTimeMillis();

      // create a Container group Recipeintsfor each individual Recipient  For group the first entry is the decoded groupid
      //number/encoded_group_name->canonical id->Recipient
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);
      Log.d(TAG, String.format("sendGroupUpdateName: Updating groupname '%s'", groupName));

      SignalServiceProtos.FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                                           Optional.<List<Recipient>>absent(),
                                                                                           Optional.<List<Recipient>>absent(),
                                                                                           groupName,
                                                                                           Optional.of(groupfId),
                                                                                           Optional.<byte[]>absent(),
                                                                                           commandArgDescriptor,
                                                                                           Optional.<FenceRecord.PrivacyMode>absent(),
                                                                                           Optional.<ufLocation>absent(),
                                                                                           timeSentInMillis);

      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setName(groupName)
              .setFenceMessage(fenceCommand);
                                                /*.addAllMembers(e164numbers);*/
      GroupContext groupContext = groupContextBuilder.build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(), ufsrvCommandWireBuilder.build());

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);

      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static GroupActionResult sendGroupUpdateMaxMembers(@NonNull  Context      context,
                                                       @NonNull  String       groupId,
                                                                 int          maxMembers,
                                                       @NonNull  Long         groupfId,
                                                       @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    try
    {
      Address groupAddress  = Address.fromSerialized(groupId);
      long timeSentInMillis = System.currentTimeMillis();

      Recipient groupRecipient = Recipient.from(context, groupAddress, false);
      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(context,
                                                                                 Optional.absent(),
                                                                                 Optional.absent(),
                                                                                  null,
                                                                                 Optional.of(groupfId),
                                                                                 Optional.absent(),
                                                                                 commandArgDescriptor,
                                                                                 Optional.absent(),
                                                                                 Optional.absent(),
                                                                                 Optional.absent(),
                                                                                 maxMembers,
                                                                                 Optional.absent(),
                                                                                 null,
                                                                                 timeSentInMillis);
      FenceCommand fenceCommand = fenceCommandBuilder.build();
      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand);
      GroupContext groupContext = groupContextBuilder.build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);

      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static GroupActionResult sendGroupUpdateDeliveryMode(@NonNull   Context      context,
                                                               @NonNull   String       groupId,
                                                               GroupDatabase.DeliveryMode deliveryMode,
                                                               @NonNull   Long            groupfId,
                                                               @NonNull   UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    try
    {
      Address groupAddress  = Address.fromSerialized(groupId);
      long timeSentInMillis = System.currentTimeMillis();

      Recipient groupRecipient = Recipient.from(context, groupAddress, false);
      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(context,
                                                                                 Optional.absent(),
                                                                                 Optional.absent(),
                                                                                 null,
                                                                                 Optional.of(groupfId),
                                                                                 Optional.absent(),
                                                                                 commandArgDescriptor,
                                                                                 Optional.absent(),
                                                                                 Optional.of(FenceRecord.DeliveryMode.values()[deliveryMode.getValue()]),
                                                                                 Optional.absent(),
                                                                                 0,
                                                                                 Optional.absent(),
                                                                                 null,
                                                                                 timeSentInMillis);
      FenceCommand fenceCommand = fenceCommandBuilder.build();
      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand);
      GroupContext groupContext = groupContextBuilder.build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);

      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static GroupActionResult sendGroupUpdateAvatar(@NonNull  Context      context,
                                                         @NonNull  String       groupId,
                                                         @Nullable byte[]       avatar,
                                                         @NonNull  Long         groupfId,
                                                         @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    try {
      Attachment avatarAttachment = null;
      Address groupAddress = Address.fromSerialized(groupId);
      //
      long timeSentInMillis = System.currentTimeMillis();

      // create a Container group Recipeintsfor each individual Recipient  For group the first entry is the decoded groupid
      //number/encoded_group_name->canonical id->Recipient
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);
      Log.d(TAG, String.format("sendGroupUpdateAvatar (fid:'%d):", groupfId));

      SignalServiceProtos.FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                                           Optional.<List<Recipient>>absent(),
                                                                                           Optional.<List<Recipient>>absent(),
                                                                                           null,
                                                                                           Optional.of(groupfId),
                                                                                           Optional.<byte[]>absent(),
                                                                                           commandArgDescriptor,
                                                                                           Optional.<FenceRecord.PrivacyMode>absent(),
                                                                                           Optional.<ufLocation>absent(),
                                                                                           timeSentInMillis);

      SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand);
      GroupContext groupContext = groupContextBuilder.build();

      if (avatar != null) {
        Uri avatarUri = BlobProvider.getInstance().forData(avatar).createForSingleUseInMemory();
        avatarAttachment = new UriAttachment(avatarUri, MediaUtil.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false, false, null, null);
      }

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);// mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this
      //long threadId = MessageSender.send(context, masterSecret, outgoingMessageUf, -1, false, 4);// mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this

      // 4 for user private group
      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }


  private static GroupActionResult sendGroupUpdateInvitedMembers(@NonNull   Context      context,
                                                                 @NonNull   String       groupId,
                                                                 @NonNull   Pair<Set<Address>, Set<Address>> membresAddedRemoved,
                                                                 @NonNull   Long         groupfId,
                                                                 @NonNull   UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    try {
      Address groupAddress = Address.fromSerialized(groupId);
      long timeSentInMillis = System.currentTimeMillis();
      Set<Address> addressesInvited = membresAddedRemoved.first;
      Set<Address> addressesRemoved = membresAddedRemoved.second;

      //number/encoded_group_name->canonical id->Recipient
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      List<String> addressesInvitedSerialised = new LinkedList<>();
      List<Recipient>     recipientsInvited   = new LinkedList<>();

      for (Address addressInvited : addressesInvited) {
        addressesInvitedSerialised.add(addressInvited.serialize());
        recipientsInvited.add(Recipient.from(context, addressInvited, false));
      }

      //
//      Recipient groupRecipientsInvited = Recipient.from(context, new ArrayList<>(addressesInvited), false);
//      Recipient groupRecipientsRemoved = Recipient.from(context, new ArrayList<>(addressesRemoved), false);

      FenceCommand fenceCommand = MessageSender.buildFenceCommandInviteFinal(context,
                                                                             recipientsInvited,
                                                                             commandArgDescriptor,
                                                                             groupfId,
                                                                             timeSentInMillis);

      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      //

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand)
              .addAllMembers(addressesInvitedSerialised); //not sure this is necessary
      GroupContext groupContext = groupContextBuilder.build();


      Log.d(TAG, String.format("sendGroupUpdateInvitedMembers: Sending '%d' new invites", addressesInvited.size()));

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());// ufsrvcommand

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);

      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // orig allin one group update to be phased out
  private static GroupActionResult sendGroupUpdateUf(@NonNull  Context      context,
                                                     @NonNull  String       groupId,
                                                     @NonNull  Set<Address>  members,
                                                     @Nullable Set<Address>  membersInvited,
                                                     @Nullable String       groupName,
                                                     @Nullable byte[]       avatar,
                                                     @NonNull  Long         groupfId,
                                                                int maxMembers,
                                                                GroupDatabase.GroupType groupType,
                                                                GroupDatabase.PrivacyMode privacyMode,
                                                                GroupDatabase.DeliveryMode deliveryMode,
                                                                GroupDatabase.JoinMode joinMode,
                                                     @NonNull   UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                                     @Nullable  RecipientDatabase.GroupPermission[] groupPermissions)
  {
    try {
      Attachment avatarAttachment = null;
      Address groupAddress = Address.fromSerialized(groupId);
      //
      long timeSentInMillis = System.currentTimeMillis();

      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      List<String> numbers              = new LinkedList<>();
      List<Recipient> recipients        = new LinkedList<>();
      List<Recipient> recipientsInvited = new LinkedList<>();

      for (Address member : members) {
        numbers.add(member.serialize());
        recipients.add(Recipient.from(context, member, false));
      }

      for (Address member : membersInvited) {
        recipientsInvited.add(Recipient.from(context, member, false));
      }

      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(context,
                                                                                 Optional.fromNullable(recipients),
                                                                                 recipientsInvited.size() > 0 ? Optional.fromNullable(recipientsInvited) : Optional.<List<Recipient>>absent(),
                                                                                 groupName,
                                                                                 Optional.of(groupfId),
                                                                                 Optional.<byte[]>absent(),
                                                                                 commandArgDescriptor,
                                                                                 Optional.of(FenceRecord.PrivacyMode.values()[privacyMode.getValue()]),
                                                                                 Optional.of(FenceRecord.DeliveryMode.values()[deliveryMode.getValue()]),
                                                                                 Optional.of(FenceRecord.JoinMode.values()[joinMode.getValue()]),
                                                                                 maxMembers,
                                                                                 Optional.<ufLocation>absent(),
                                                                                 groupPermissions,
                                                                                 timeSentInMillis);

      FenceCommand fenceCommand = fenceCommandBuilder.build();
      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      //

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand)
              .addAllMembers(numbers);
      if (groupName != null) groupContextBuilder.setName(groupName);
      GroupContext groupContext = groupContextBuilder.build();

      if (avatar != null) {
        Uri avatarUri = BlobProvider.getInstance().forData(avatar).createForSingleUseInMemory();
        avatarAttachment = new UriAttachment(avatarUri, MediaUtil.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false, false, null, null);
      }

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis/*System.currentTimeMillis()*/, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());// ufsrvcommand

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);// mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this
      //long threadId = MessageSender.send(context, masterSecret, outgoingMessageUf, -1, false, 4);// mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this

      // 4 for user private group
      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }


  private static GroupActionResult
  sendGroupCreatePrivate(@NonNull  Context      context,
                         @NonNull  String       groupId,
                         @NonNull  Set<Address>  addresses,
                         @Nullable Set<Address>  e164numbersInvited,
                         @Nullable String       groupName)
  {
    try
    {
      Address groupAddress = Address.fromSerialized(groupId);
      Attachment avatarAttachment = null;
      //
      long timeSentInMillis = System.currentTimeMillis();

      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      List<String> addressesSerliased   = new LinkedList<>();
      List<Recipient> recipientsInvited = new LinkedList<>();
      List<Recipient> recipients        = new LinkedList<>();

      for (Address member : addresses)
      {
        addressesSerliased.add(member.serialize());
        recipients.add(Recipient.from(context, member, false));
      }

      for (Address member: e164numbersInvited) {
        recipientsInvited.add(Recipient.from(context, member, false));
      }

      //
//      Recipient groupRecipients = RecipientFactory.getRecipientsFromStrings(context, new ArrayList<>(addresses), false);
//      Recipient groupRecipientsInvited = RecipientFactory.getRecipientsFromStrings(context, new ArrayList<>(e164numbersInvited), false);

      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(context,
                                                                                 Optional.fromNullable(recipients),
                                                                                 recipientsInvited.size() > 0 ? Optional.fromNullable(recipientsInvited) : Optional.<List<Recipient>>absent(),
                                                                                 groupName,
                                                                                 Optional.<Long>absent(),
                                                                                 Optional.<byte[]>absent(),
                                                                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.JOIN_VALUE, 0),
                                                                                 Optional.of(FenceRecord.PrivacyMode.PRIVATE),
                                                                                 Optional.of(FenceRecord.DeliveryMode.MANY),
                                                                                 Optional.of(FenceRecord.JoinMode.INVITE),
                                                                                 2,
                                                                                 Optional.<ufLocation>absent(),
                                                                                 null,
                                                                                 timeSentInMillis);

      UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommandBuilder.build())
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      //

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
              .setType(GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommandBuilder.build())
              .addAllMembers(addressesSerliased);
      if (groupName != null) groupContextBuilder.setName(groupName);
      GroupContext groupContext = groupContextBuilder.build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());// ufsrvcommand

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);
      //long threadId = MessageSender.send(context, masterSecret, outgoingMessageUf, -1, false, 4);// mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this

      // 4 for user private group
      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  List<SignalServiceProtos.UserRecord> GetUserRecordsfromSet (Set<String> numbers)
  {
    SignalServiceProtos.UserRecord.Builder userRecordBuilder = SignalServiceProtos.UserRecord.newBuilder();
    return null;
  }


  public static class GroupActionResult {
    private Recipient groupRecipient;
    private long       threadId;

    public GroupActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
