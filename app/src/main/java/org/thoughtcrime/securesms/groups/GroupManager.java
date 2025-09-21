  package org.thoughtcrime.securesms.groups;

  import android.content.Context;
  import android.graphics.Bitmap;
  import android.net.Uri;

  import com.annimon.stream.Stream;
  import com.google.protobuf.ByteString;
  import com.unfacd.android.ApplicationContext;
  import com.unfacd.android.fence.FencePermissions;
  import com.unfacd.android.ufsrvcmd.UfsrvCommand;
  import com.unfacd.android.utils.UfsrvCommandUtils;

  import org.signal.core.util.logging.Log;
  import org.signal.libsignal.zkgroup.VerificationFailedException;
  import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
  import org.signal.libsignal.zkgroup.groups.UuidCiphertext;
  import org.signal.storageservice.protos.groups.GroupExternalCredential;
  import org.signal.storageservice.protos.groups.local.DecryptedGroup;
  import org.signal.storageservice.protos.groups.local.DecryptedGroupJoinInfo;
  import org.thoughtcrime.securesms.attachments.Attachment;
  import org.thoughtcrime.securesms.attachments.UriAttachment;
  import org.thoughtcrime.securesms.database.Address;
  import org.thoughtcrime.securesms.database.AttachmentDatabase;
  import org.thoughtcrime.securesms.database.GroupDatabase;
  import org.thoughtcrime.securesms.database.RecipientDatabase;
  import org.thoughtcrime.securesms.database.SignalDatabase;
  import org.thoughtcrime.securesms.database.ThreadDatabase;
  import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl;
  import org.thoughtcrime.securesms.groups.v2.GroupLinkPassword;
  import org.thoughtcrime.securesms.keyvalue.SignalStore;
  import org.thoughtcrime.securesms.mms.OutgoingExpirationUpdateMessage;
  import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
  import org.thoughtcrime.securesms.profiles.AvatarHelper;
  import org.thoughtcrime.securesms.providers.BlobProvider;
  import org.thoughtcrime.securesms.recipients.Recipient;
  import org.thoughtcrime.securesms.recipients.RecipientId;
  import org.thoughtcrime.securesms.sms.MessageSender;
  import org.thoughtcrime.securesms.util.BitmapUtil;
  import org.thoughtcrime.securesms.util.MediaUtil;
  import org.thoughtcrime.securesms.util.TextSecurePreferences;
  import org.whispersystems.signalservice.api.groupsv2.GroupLinkNotActiveException;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
  import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

  import java.io.ByteArrayInputStream;
  import java.io.IOException;
  import java.util.ArrayList;
  import java.util.Collection;
  import java.util.Collections;
  import java.util.HashMap;
  import java.util.HashSet;
  import java.util.LinkedList;
  import java.util.List;
  import java.util.Locale;
  import java.util.Map;
  import java.util.Optional;
  import java.util.Set;
  import java.util.UUID;

  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.annotation.WorkerThread;
  import androidx.core.util.Pair;


public final class GroupManager
{

  private static final String TAG = Log.tag(GroupManager.class);


  //AA main entry point for creating groups via UI
  @WorkerThread
  public static @NonNull
  GroupActionResult createGroup(@NonNull Context context,
                                @NonNull Set<Recipient> members,
                                @Nullable byte[] avatar,
                                @Nullable String name,
                                @NonNull GroupDatabase.GroupControlsDescriptor groupControls)//AA+
                                throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    Set<RecipientId> memberIds = getMemberIds(members);

    try (GroupManagerV2.GroupCreator groupCreator = new GroupManagerV2(context).create()) {
      return groupCreator.createGroupOnServer(memberIds, name, avatar, groupControls);//AA+
    } catch (MembershipNotSuitableForV2Exception x) {
      return new GroupManager.GroupActionResult(Recipient.UNKNOWN, -1, -1, Collections.emptyList());
    }
  }

  //AA legacy TBD once V2 is ready
  public static @NonNull
  GroupActionResult createGroup(@NonNull Context context,
                                @NonNull Set<Recipient> members,
                                @Nullable Bitmap avatar,
                                @Nullable String name,
                                boolean mms,
                                @Nullable String cname,
                                long fid,
                                int maxMembers,
                                GroupDatabase.GroupType groupType,
                                GroupDatabase.PrivacyMode privacyMode,
                                GroupDatabase.DeliveryMode deliveryMode,
                                GroupDatabase.JoinMode joinMode,
                                RecipientDatabase.GroupPermission[] groupPermissions)//AA+
  {
    final byte[] avatarBytes = BitmapUtil.toByteArray(avatar);
    final GroupDatabase groupDatabase = SignalDatabase.groups();
    final GroupId groupId = GroupDatabase.allocateGroupId(mms);
    final Set<Address> memberAddresses = getMemberAddresses(members);
    //AA+
    final Set<Address> membersInvited = getMemberAddresses(members);
    //

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    long id = groupDatabase.create(groupId, null, name, new LinkedList<>(memberAddresses), null, null, cname + name,
                                   0.0, 0.0,
                                   GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED, fid, maxMembers,
                                   GroupDatabase.GroupType.USER, privacyMode, deliveryMode, joinMode);

    final RecipientId groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);//AA should be created by now
    final Recipient groupRecipient = Recipient.live(groupRecipientId).get();

    if (!mms) {
      if (id != -1) {
        SignalDatabase.recipients().setGroupPermissions(groupRecipient, groupPermissions[FencePermissions.PRESENTATION.getValue() - 1]);
        SignalDatabase.recipients().setGroupPermissions(groupRecipient, groupPermissions[FencePermissions.MEMBERSHIP.getValue() - 1]);
        SignalDatabase.recipients().setGroupPermissions(groupRecipient, groupPermissions[FencePermissions.MESSAGING.getValue() - 1]);
        SignalDatabase.recipients().setGroupPermissions(groupRecipient, groupPermissions[FencePermissions.ATTACHING.getValue() - 1]);
        SignalDatabase.recipients().setGroupPermissions(groupRecipient, groupPermissions[FencePermissions.CALLING.getValue() - 1]);

        groupDatabase.onAvatarUpdated(groupId.requireV1(), avatarBytes != null);

        SignalDatabase.recipients().setProfileSharing(groupRecipient, true);
        SignalDatabase.recipients().setRegistered(groupRecipientId, RecipientDatabase.RegisteredState.REGISTERED);//AA+ group registered by default
        SignalDatabase.recipients().setRecipientType(groupRecipient, Recipient.RecipientType.GROUP);//AA+

        //AA+
        return sendGroupUpdateUf(context, groupId,
                                 memberAddresses, membersInvited, name,
                                 avatarBytes,
                                 Long.valueOf(0),
                                 maxMembers,
                                 0,
                                 groupType,
                                 privacyMode,
                                 deliveryMode,
                                 joinMode,
                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.JOIN_VALUE, 0),
                                 groupPermissions);
      }
      else return new GroupActionResult(null, -1, -1, null);
    }
    else {
      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
      return new GroupActionResult(groupRecipient, threadId, memberAddresses.size(), Stream.of(membersInvited).map(a -> Recipient.resolvedFromUfsrvUid(a.serialize()).getId()).toList());
    }
  }

  //AA from above for V2
  public static @NonNull
  GroupActionResult createGroupForUfsrv(@NonNull Context context,
                                        @NonNull GroupId groupId,
                                        @NonNull Collection<RecipientId> members,
                                        @Nullable byte[] avatarBytes,
                                        @Nullable String name,
                                        GroupDatabase.GroupControlsDescriptor groupDescriptor)
  {
    final Set<Address> memberAddresses = getGroupMemberAddresses(members);
    final Set<Address> membersInvited = getGroupMemberAddresses(members);

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    //AA+
    return sendGroupUpdateUf(context,
                             groupId,
                             memberAddresses, membersInvited,
                             name,
                             avatarBytes,
                             Long.valueOf(0),
                             groupDescriptor.getMembersSize(),
                             groupDescriptor.getDisappearingMessagesTimer(),
                             groupDescriptor.getGroupType(),
                             groupDescriptor.getPrivacyMode(),
                             groupDescriptor.getDeliveryMode(),
                             groupDescriptor.getJoinMode(),
                             new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.JOIN_VALUE, 0),
                             groupDescriptor.getGroupPermissions());
  }

  public static @NonNull
  GroupActionResult
  createPairedGroup(@NonNull Context context, @NonNull Set<Recipient> members, @Nullable String name, boolean mms, @Nullable String cname, double longitude, double latitude)
  {
    GroupDatabase.GroupControlsDescriptor groupControls = new GroupDatabase.GroupControlsDescriptor(GroupDatabase.GroupType.USER, 2, GroupDatabase.PrivacyMode.PRIVATE, GroupDatabase.DeliveryMode.MANY, GroupDatabase.JoinMode.INVITE, null, 0);
    try {
      return  GroupManager.createGroup(context, members, null, name, groupControls);//AA+
    } catch (GroupChangeBusyException | GroupChangeFailedException | IOException x) {
      return new GroupActionResult(null, -1, -1, null);
    }

    /*final GroupDatabase groupDatabase = SignalDatabase.groups();
    final GroupId groupId;
    try {
      groupId = GroupId.v2(new GroupMasterKey(Util.getSecretBytes(32)));
    } catch (InvalidInputException x) {
      throw new AssertionError("UNABLE TO GENERATE GroupID: MasterKey generation error");
    }
    final Set<Address> memberAddresses = getMemberAddresses(members);
    final Set<Address> memberAddressesInvited = getMemberAddresses(members);//AA+

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    if (name == null) {
      name = String.format("%d.%s", TextSecurePreferences.getUserId(context), groupId.toString());
    }
    long id = groupDatabase.create(groupId, null, name,
                                   new LinkedList<>(memberAddresses), null, null,
                                   cname + name,
                                   longitude, latitude, GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED, 0, 2,
                                   GroupDatabase.GroupType.USER,
                                   GroupDatabase.PrivacyMode.PRIVATE,
                                   GroupDatabase.DeliveryMode.MANY,
                                   GroupDatabase.JoinMode.INVITE);

    final RecipientId groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);//AA should be created by now
    final Recipient groupRecipient = Recipient.live(groupRecipientId).get();

    if (!mms) {
      //AA+
      if (id != -1) {
        SignalDatabase.recipients().setProfileSharing(groupRecipient, true);
        SignalDatabase.recipients().setRegistered(groupRecipient.getId(), RecipientDatabase.RegisteredState.REGISTERED);//AA+ group registered by default
        SignalDatabase.recipients().setRecipientType(groupRecipient, Recipient.RecipientType.GROUP);//AA+

        return sendGroupCreatePrivate(context, groupId,
                                      memberAddresses, memberAddressesInvited, name != null ? name : groupId.toString());
      }
      else return new GroupActionResult(null, -1, -1, null);
    }
    else {
      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
      return new GroupActionResult(groupRecipient, threadId, memberAddresses.size(), Stream.of(memberAddressesInvited).map(a -> Recipient.resolvedFromUfsrvUid(a.serialize()).getId()).toList());
    }*/
  }

  public static @NonNull
  GroupActionResult
  createGuardianGroup(@NonNull Context context, @NonNull Set<Recipient> members, @Nullable String name)
  {
    final GroupDatabase groupDatabase = SignalDatabase.groups();
    final GroupId groupId = groupDatabase.allocateGuardianGroupId();
    final Set<Address> memberAddresses = getMemberAddresses(members);

    //don't do that as it interfer with logic relying on 'members' only containing single originator + adds no semantical value anyway
//    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)));

    if (name == null) {
      name = String.format(Locale.getDefault(), "%d.%s", TextSecurePreferences.getUserId(context), groupId.toString());
    }

    long id = groupDatabase.create(groupId, null, name,
                                   new LinkedList<>(memberAddresses), null, null,
                                   "__guardian:" + name,
                                   0, 0, GroupDatabase.GROUP_MODE_DEVICELOCAL, 0, 2,
                                   GroupDatabase.GroupType.GUARDIAN,
                                   GroupDatabase.PrivacyMode.PRIVATE,
                                   GroupDatabase.DeliveryMode.MANY,
                                   GroupDatabase.JoinMode.INVITE);

    final RecipientId groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);//AA should be created by now
    final Recipient groupRecipient = Recipient.live(groupRecipientId).get();

    if (id != -1) {
      SignalDatabase.recipients().setProfileSharing(groupRecipient, true);
      SignalDatabase.recipients().setRegistered(groupRecipient.getId(), RecipientDatabase.RegisteredState.REGISTERED);//group registered by default
      SignalDatabase.recipients().setRecipientType(groupRecipient, Recipient.RecipientType.GROUP);

      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT);
      return new GroupActionResult(groupRecipient, threadId, 2, Collections.EMPTY_LIST);
    }
    else return new GroupActionResult(null, -1, 0, null);
  }

  //compare exiting group members with ones added via the group update ui
  public static Pair<Set<Recipient>, Set<Recipient>>
  getAddedGroupMembers(Context context, Set<Recipient> members, Set<Recipient> membersPreExisting, GroupDatabase.GroupRecord groupRecord)
  {
    HashMap<String, Recipient> membersNew = new HashMap<>();
    HashMap<String, Recipient> membersPre = new HashMap<>();
    HashSet<Recipient> membersSetAdded = new HashSet();
    HashSet<Recipient> membersSetRemoved = new HashSet();

    for (Recipient recipient : members)
      membersNew.put(recipient.requireAddress().serialize(), recipient);//AA+ serialise
    for (Recipient recipient : membersPreExisting)
      membersPre.put(recipient.requireAddress().serialize(), recipient);
    Log.d(TAG, String.format("getAddedGroupMembers: membersNew sze:'%d', membersPreExisting size: '%d'", membersNew.size(), membersPre.size()));

    for (Recipient recipient : members) {
      if (!membersPre.containsKey(recipient.requireAddress().serialize()))
        membersSetAdded.add(recipient); //AA+ serialise
    }

    for (Recipient recipient : membersPreExisting) {
      if (!membersNew.containsKey(recipient.requireAddress().serialize()))
        membersSetRemoved.add(recipient);
    }

    return new Pair(membersSetAdded, membersSetRemoved);

  }

  @WorkerThread
  public static GroupActionResult updateGroupDetails(@NonNull Context context,
                                                      @NonNull GroupId groupId,
                                                      @Nullable byte[] avatar,
                                                      boolean avatarChanged,
                                                      @NonNull String name,
                                                      boolean nameChanged,
                                                      @NonNull String description,
                                                      boolean descriptionChanged)
                                                      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    GroupDatabase groupDatabase    = SignalDatabase.groups();
    Recipient groupRecipient = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();
    long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);

    GroupActionResult groupActionResult= new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);

    if (avatarChanged) {
      AvatarHelper.setAvatar(context, Recipient.externalGroupExact(context, groupId).getId(), avatar != null ? new ByteArrayInputStream(avatar) : null);
      ByteArrayInputStream avatarBytesStored = new ByteArrayInputStream(avatar);
      groupDatabase.onAvatarUpdated(groupId, avatar != null);
      sendGroupUpdateAvatar(context, groupId, avatar, groupRecipient.getUfsrvId(),
                            new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.AVATAR_VALUE, CommandArgs.UPDATED_VALUE));
    }

    if (nameChanged) {
      sendGroupUpdateName(context, groupId, name, groupRecipient.getUfsrvId(),
                          new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.FNAME_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));
    }

    if (descriptionChanged) {
      sendGroupUpdateDescription(context, groupId, description, groupRecipient.getUfsrvId(),
                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.DESCRIPTION_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));
    }

    return groupActionResult;
  }

  //AA- see above
/*  @WorkerThread
  public static GroupActionResult updateGroupDetails(@NonNull Context context,
                                                     @NonNull GroupId groupId,
                                                     @Nullable byte[] avatar,
                                                     boolean avatarChanged,
                                                     @NonNull String name,
                                                     boolean nameChanged,
                                                     @NonNull String description,
                                                     boolean descriptionChanged)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        return edit.updateGroupTitleDescriptionAndAvatar(nameChanged ? name : null,
                                                         descriptionChanged ? description : null,
                                                         avatar,
                                                         avatarChanged);
      }
    } else if (groupId.isV1()) {
      List<Recipient> members = SignalDatabase.groups()
                                               .getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
      Set<RecipientId> recipientIds = getMemberIds(new HashSet<>(members));
      return GroupManagerV1.updateGroup(context, groupId.requireV1(), recipientIds, avatar, name, 0);
    } else {
      return GroupManagerV1.updateGroup(context, groupId.requireMms(), avatar, name);
    }
  }*/

  @WorkerThread
  public static void migrateGroupToServer(@NonNull Context context,
                                          @NonNull GroupId.V1 groupIdV1)
          throws IOException, GroupChangeFailedException, MembershipNotSuitableForV2Exception, GroupAlreadyExistsException
  {
   /* new GroupManagerV2(context).migrateGroupOnToServer(groupIdV1, null);*/
  }

  private static Set<RecipientId> getMemberIds(Collection<Recipient> recipients)
  {
    Set<RecipientId> results = new HashSet<>(recipients.size());

    for (Recipient recipient : recipients) {
      results.add(recipient.getId());
    }

    return results;
  }

  //AA+ ufsrv lefcy to be deleted once operations re-implemented
  //todo: ideally, we want to return list, but 'SignalGroupTask' will need to be modified to return this new type, which also affects create group
  //public static ArrayList<GroupActionResult> updateGroup(@NonNull  Context        context,
  public static GroupActionResult updateGroup (
          @NonNull Context context,
          @NonNull GroupId groupId,
          @NonNull Set<Recipient> members,
          @Nullable Bitmap avatar,
          @Nullable String name,
          @NonNull GroupDatabase.GroupRecord groupRecord,
          @NonNull Set<Recipient> preExistingMembers,
          int maxMembers,
          GroupDatabase.DeliveryMode deliveryMode)//AA+
  {
    if (!groupId.isMms()) {
      final byte[] avatarBytes = BitmapUtil.toByteArray(avatar);
      Recipient groupRecipient = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();
      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
      GroupActionResult groupActionResultName = new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
      GroupActionResult groupActionResultAvatar = new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
      ArrayList<GroupActionResult> groupActionResults = new ArrayList<>();

      if (!name.equals(groupRecord.getTitle())) {
        groupActionResultName = sendGroupUpdateName(context, groupId, name, Long.valueOf(groupRecord.getFid()),
                                                    new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.FNAME_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));
        groupActionResults.add(groupActionResultName);
      }
      else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      if (avatar != null && AvatarHelper.hasAvatar(context, groupRecipient.getId())) {
        ByteArrayInputStream avatarBytesStored = new ByteArrayInputStream(avatarBytes);
        try {
          if (!avatarBytesStored.equals(AvatarHelper.getAvatar(context, groupRecipient.getId()))) {
            groupActionResultAvatar = sendGroupUpdateAvatar(context, groupId, avatarBytes, new Long(groupRecord.getFid()),
                                                            new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.AVATAR_VALUE, CommandArgs.UPDATED_VALUE));
            groupActionResults.add(groupActionResultAvatar);
          }
          else {
            //todo:user message not necessarily as user may not want to change that it is just present as a loaded field value
          }
        }
        catch (IOException ex) {
          Log.d(TAG, ex.getMessage());
        }
      }

      if (maxMembers != groupRecord.getMaxmembers()) {
        groupActionResultName = sendGroupUpdateMaxMembers(context, groupId, maxMembers, Long.valueOf(groupRecord.getFid()),
                                                          new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.MAXMEMBERS_VALUE, CommandArgs.UPDATED_VALUE));

        groupActionResults.add(groupActionResultName);
      }
      else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      if (deliveryMode != GroupDatabase.DeliveryMode.values()[groupRecord.getDeliveryType()]) {
        groupActionResultName = sendGroupUpdateDeliveryMode(context, groupId, deliveryMode, Long.valueOf(groupRecord.getFid()),
                                                            new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.DELIVERY_MODE_VALUE, CommandArgs.UPDATED_VALUE));

        groupActionResults.add(groupActionResultName);
      }
      else {
        //todo: user message.. not necessarily as user may not want to change that it is just present as a loaded field value
      }

      Pair<Set<Recipient>, Set<Recipient>> membersAddedremoved = getAddedGroupMembers(context, members, preExistingMembers, groupRecord);
      final Set<Address> memberAddressesInvited = getMemberAddresses(membersAddedremoved.first);
      final Set<Address> memberAddressesRemoved = getMemberAddresses(membersAddedremoved.second);

      if (!membersAddedremoved.first.isEmpty()) {
        Log.d(TAG, String.format("membersAddedremoved: INVITED LIST size:'%d'...", membersAddedremoved.first.size()));
        GroupActionResult groupActionResultAdded = sendGroupUpdateInvitedMembers(context,
                                                                                 groupId,
                                                                                 new Pair<>(memberAddressesInvited, memberAddressesRemoved),
                                                                                 Long.valueOf(groupRecord.getFid()),
                                                                                 new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.INVITE_VALUE, CommandArgs.ADDED_VALUE));

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
      if (groupActionResults.size() > 0) return groupActionResults.get(0);//return the first for now
      else
        return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST); //todo: fix return as currently errors are not correctly indicated
    }
    else {//local mms group
      final GroupDatabase groupDatabase = SignalDatabase.groups();
      final Set<Address> memberAddresses = getMemberAddresses(members);
      final byte[] avatarBytes = BitmapUtil.toByteArray(avatar);

      memberAddresses.add(Address.fromSerialized(SignalStore.account().getE164()));
      groupDatabase.updateMembers(groupId, new LinkedList<>(memberAddresses));
      groupDatabase.updateTitle(groupId.requireV1(), name);
      groupDatabase.onAvatarUpdated(groupId.requireV1(), avatarBytes != null);

      Recipient groupRecipient = Recipient.live(groupRecord.getRecipientId()).get();

      long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
      return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
    }
  }

  private static Set<Address> getMemberAddresses(Collection<Recipient> recipients)
  {
    final Set<Address> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.requireAddress());
    }

    return results;
  }

  private static Set<Address> getGroupMemberAddresses(Collection<RecipientId> recipientIds)
  {
    final Set<Address> results = new HashSet<>();
    for (RecipientId recipientId : recipientIds) {
      results.add(Recipient.resolved(recipientId).requireAddress());
    }

    return results;
  }

  private static GroupActionResult sendGroupUpdateName(@NonNull Context context,
                                                        @NonNull GroupId groupId,
                                                        @Nullable String groupName,
                                                        @NonNull Long groupfId,
                                                        @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();

    //AA create a Container group Recipeintsfor each individual Recipient  For group the first entry is the decoded groupid
    //number/encoded_group_name->canonical id->Recipient
    Recipient groupRecipient = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();
    Log.d(TAG, String.format("sendGroupUpdateName: Updating groupname '%s'", groupName));

    SignalServiceProtos.FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                                         Optional.empty(),
                                                                                         Optional.empty(),
                                                                                         groupName,
                                                                                         Optional.empty(), Optional.of(groupfId),
                                                                                         Optional.empty(),
                                                                                         commandArgDescriptor,
                                                                                         Optional.empty(),
                                                                                         Optional.empty(),
                                                                                         timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setName(groupName)
            .setFenceMessage(fenceCommand);

    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateDescription(@NonNull Context context,
                                                              @NonNull GroupId groupId,
                                                              @Nullable String groupDescription,
                                                              @NonNull Long groupfId,
                                                              @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();

    //AA create a Container group Recipeintsfor each individual Recipient  For group the first entry is the decoded groupid
    //number/encoded_group_name->canonical id->Recipient
    Recipient groupRecipient = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();
    Log.d(TAG, String.format("sendGroupUpdateDescription: Updating group description '%s'", groupDescription));

    FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                     Optional.empty(),
                                                                     Optional.empty(),
                                                                     null,
                                                                     Optional.ofNullable(groupDescription),
                                                                     Optional.of(groupfId),
                                                                     Optional.empty(),
                                                                     commandArgDescriptor,
                                                                     Optional.empty(),
                                                                     Optional.empty(),
                                                                     timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
                                                                       .setFenceCommand(fenceCommand)
                                                                       .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                           .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                           .setType(GroupContext.Type.UPDATE)
                                                           .setFenceMessage(fenceCommand);

    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateMaxMembers(@NonNull Context context,
                                                              @NonNull GroupId groupId,
                                                              int maxMembers,
                                                              @NonNull Long groupfId,
                                                              @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    Recipient groupRecipient = Recipient.live(groupRecord.get().getRecipientId()).get();

    if (!groupRecord.isPresent()) {
      throw new AssertionError(String.format("sendGroupUpdateAvatar: GroupId '%s' not found", groupId.toString()));
    }

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(Optional.empty(), Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.of(groupfId), Optional.empty(), commandArgDescriptor, Optional.empty(), Optional.empty(), Optional.empty(), maxMembers, 0, Optional.empty(), context,
                                                                               null,
                                                                               timeSentInMillis);
    FenceCommand fenceCommand = fenceCommandBuilder.build();
    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand);
    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateDeliveryMode (
          @NonNull Context context,
          @NonNull GroupId groupId,
          GroupDatabase.DeliveryMode deliveryMode,
          @NonNull Long groupfId,
          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    Recipient groupRecipient = Recipient.live(groupRecord.get().getRecipientId()).get();

    if (!groupRecord.isPresent()) {
      throw new AssertionError(String.format("sendGroupUpdateDeliveryMode: GroupId '%s' not found", groupId.toString()));
    }

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(Optional.empty(), Optional.empty(), Optional.empty(), null, Optional.empty(), Optional.of(groupfId), Optional.empty(), commandArgDescriptor, Optional.empty(), Optional.of(FenceRecord.DeliveryMode.values()[deliveryMode.getValue()]), Optional.empty(), 0, 0, Optional.empty(), context,
                                                                               null,
                                                                               timeSentInMillis);
    FenceCommand fenceCommand = fenceCommandBuilder.build();
    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand);
    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateAvatar (
          @NonNull Context context,
          @NonNull GroupId groupId,
          @Nullable byte[] avatar,
          @NonNull Long groupfId,
          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    Attachment avatarAttachment = null;
    //AA+
    long timeSentInMillis = System.currentTimeMillis();

    //AA create a Container group Recipeintsfor each individual Recipient  For group the first entry is the decoded groupid
    //number/encoded_group_name->canonical id->Recipient

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    Recipient groupRecipient = Recipient.live(groupRecord.get().getRecipientId()).get();

    if (!groupRecord.isPresent()) {
      throw new AssertionError(String.format("sendGroupUpdateAvatar: GroupId '%s' not found", groupId.toString()));
    }

    Log.d(TAG, String.format("sendGroupUpdateAvatar (fid:'%d):", groupfId));

    SignalServiceProtos.FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                                         Optional.empty(),
                                                                                         Optional.empty(),
                                                                                         null,
                                                                                         Optional.empty(), Optional.of(groupfId),
                                                                                         Optional.empty(),
                                                                                         commandArgDescriptor,
                                                                                         Optional.empty(),
                                                                                         Optional.empty(),
                                                                                         timeSentInMillis);

    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand);
    GroupContext groupContext = groupContextBuilder.build();

    if (avatar != null) {
      Uri avatarUri = BlobProvider.getInstance().forData(avatar).createForSingleUseInMemory();
      avatarAttachment = new UriAttachment(avatarUri, MediaUtil.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false, false, false, false, null, null, null, null, null);
    }

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis, 0, false, null, Collections.emptyList(),Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);//AA+ mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this

    //AA 4 for user private group
    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateInvitedMembersList(
          @NonNull Context context,
          @NonNull GroupId groupId,
          @NonNull Collection<RecipientId> membersAdded,
          @NonNull Collection<RecipientId> membersRemoved,
          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    Recipient groupRecipient = Recipient.live(groupRecord.get().getRecipientId()).get();

    if (!groupRecord.isPresent()) {
      throw new AssertionError(String.format("sendGroupUpdateInvitedMembersList: GroupId '%s' not found", groupId));
    }


    List<Recipient> recipientsInvited = Stream.of(membersAdded).map((id) -> Recipient.resolved(id)).toList();
    List<Recipient> recipientsUnInvited = Stream.of(membersRemoved).map((id) -> Recipient.resolved(id)).toList();

    FenceCommand fenceCommand = MessageSender.buildFenceCommandInviteFinal(context,
                                                                           recipientsInvited,
                                                                           commandArgDescriptor,
                                                                           groupRecord.get().getFid(),
                                                                           timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand);
    GroupContext groupContext = groupContextBuilder.build();


    Log.d(TAG, String.format("sendGroupUpdateInvitedMembers: Sending '%d' new invites", recipientsInvited.size()));

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());//AA+ ufsrvcommand

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  private static GroupActionResult sendGroupUpdateInvitedMembers (
          @NonNull Context context,
          @NonNull GroupId groupId,
          @NonNull Pair<Set<Address>, Set<Address>> membresAddedRemoved,
          @NonNull Long groupfId,
          @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    long timeSentInMillis = System.currentTimeMillis();
    Set<Address> addressesInvited = membresAddedRemoved.first;
    Set<Address> addressesRemoved = membresAddedRemoved.second;

    //number/encoded_group_name->canonical id->Recipient
    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    Recipient groupRecipient = Recipient.live(groupRecord.get().getRecipientId()).get();

    if (!groupRecord.isPresent()) {
      throw new AssertionError(String.format("sendGroupUpdateInvitedMembers: GroupId '%s' not found", groupId.toString()));
    }

    List<String> addressesInvitedSerialised = new LinkedList<>();
    List<Recipient> recipientsInvited = new LinkedList<>();

    for (Address addressInvited : addressesInvited) {
      addressesInvitedSerialised.add(addressInvited.serialize());
      recipientsInvited.add(Recipient.live(addressInvited.serialize()).get());
    }

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
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommand);
//              .addAllMembers(addressesInvitedSerialised); //not sure this is necessary
    GroupContext groupContext = groupContextBuilder.build();


    Log.d(TAG, String.format("sendGroupUpdateInvitedMembers: Sending '%d' new invites", addressesInvited.size()));

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());//AA+ ufsrvcommand

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return new GroupActionResult(groupRecipient, threadId, 0, Collections.EMPTY_LIST);
  }

  //AA orig all in one group update to be phased out
  @NonNull private static GroupActionResult sendGroupUpdateUf(@NonNull Context context,
                                                              @NonNull GroupId groupId,
                                                              @NonNull Set<Address> members,
                                                              @Nullable Set<Address> membersInvited,
                                                              @Nullable String groupName,
                                                              @Nullable byte[] avatar,
                                                              @NonNull Long groupfId,
                                                              int maxMembers,
                                                              int expiryTimer,
                                                              GroupDatabase.GroupType groupType,
                                                              GroupDatabase.PrivacyMode privacyMode,
                                                              GroupDatabase.DeliveryMode deliveryMode,
                                                              GroupDatabase.JoinMode joinMode,
                                                              @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                                              @Nullable RecipientDatabase.GroupPermission[] groupPermissions)
  {
    Attachment avatarAttachment = null;
    long timeSentInMillis = System.currentTimeMillis();

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    if (!groupRecord.isPresent()) {
      Log.e(TAG, String.format("ERROR (groupId:'%s'): COULD NOT LOAD GROUP RECORD", groupId.toString()));
      return null;
    }

    RecipientId recipientIdGroup = groupRecord.get().getRecipientId();
    Recipient groupRecipient = Recipient.live(recipientIdGroup).get();

    List<String> numbers = new LinkedList<>();
    List<Recipient> recipients = new LinkedList<>();
    List<Recipient> recipientsInvited = new LinkedList<>();

    for (Address member : members) {
      numbers.add(member.serialize());
      recipients.add(Recipient.live(member.serialize()).get());
    }

    for (Address member : membersInvited) {
      recipientsInvited.add(Recipient.live(member.serialize()).get());
    }

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(Optional.ofNullable(recipients),
                                                                               recipientsInvited.size() > 0 ? Optional.ofNullable(recipientsInvited) : Optional.empty(),
                                                                               groupRecord.get().getGroupMasterKey(),
                                                                               groupName, Optional.empty(),
                                                                               Optional.of(groupfId),
                                                                               Optional.empty(),
                                                                               commandArgDescriptor,
                                                                               Optional.of(FenceRecord.PrivacyMode.values()[privacyMode.getValue()]),
                                                                               Optional.of(FenceRecord.DeliveryMode.values()[deliveryMode.getValue()]),
                                                                               Optional.of(FenceRecord.JoinMode.values()[joinMode.getValue()]),
                                                                               maxMembers, expiryTimer, Optional.empty(),
                                                                               context,
                                                                               groupPermissions,
                                                                               timeSentInMillis);

    FenceCommand fenceCommand = fenceCommandBuilder.build();
    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
                                                                       .setFenceCommand(fenceCommand)
                                                                       .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                           .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                           .setType(GroupContext.Type.UPDATE)
                                                           .setFenceMessage(fenceCommand);
                                                            //              .addAllMembers(numbers);
    if (groupName != null) groupContextBuilder.setName(groupName);
    GroupContext groupContext = groupContextBuilder.build();

    if (avatar != null) {
      Uri avatarUri = BlobProvider.getInstance().forData(avatar).createForSingleUseInMemory();
      avatarAttachment = new UriAttachment(avatarUri, MediaUtil.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false, false, false, false, null, null, null, null, null);
    }

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());//AA+ ufsrvcommand

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);//AA+ mode private as fall-through default when this method is called by original existing code without mode specifier default -1 allocate threadId for this

    //AA 4 for user private
    return new GroupActionResult(groupRecipient, threadId, recipients.size(), Stream.of(recipientsInvited).map(r -> r.getId()).toList());
  }

  private static GroupActionResult
  sendPairedGroupCreateRequest(@NonNull Context context, @NonNull GroupId groupId, @Nullable List<RecipientId> recipientsInvited, @Nullable String groupName)
  {
    Attachment avatarAttachment = null;
    //AA+
    long timeSentInMillis = System.currentTimeMillis();

    Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);
    if (!groupRecord.isPresent()) {
      Log.e(TAG, String.format("ERROR (groupId:'%s'): COULD NOT LOAD GROUP RECORD", groupId.toString()));
      return null;
    }

    RecipientId recipientId = groupRecord.get().getRecipientId();
    Recipient groupRecipient = Recipient.live(recipientId).get();

    List<Recipient> recipients = new LinkedList<Recipient>() {{add(Recipient.self());}};

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommand(Optional.ofNullable(recipients),
                                                                               Optional.of(Stream.of(recipientsInvited).map(r -> Recipient.resolved(r)).toList()), groupRecord.get().getGroupMasterKey(),
                                                                               groupName,
                                                                               Optional.empty(), Optional.empty(), Optional.empty(),
                                                                               new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.JOIN_VALUE, 0),
                                                                               Optional.of(FenceRecord.PrivacyMode.PRIVATE), Optional.of(FenceRecord.DeliveryMode.MANY),
                                                                               Optional.of(FenceRecord.JoinMode.INVITE),
                                                                               2, 0, Optional.empty(),
                                                                               context,
                                                                               null,
                                                                               timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
                                                                       .setFenceCommand(fenceCommandBuilder.build())
                                                                       .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                           .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                           .setType(GroupContext.Type.UPDATE)
                                                           .setFenceMessage(fenceCommandBuilder.build());
//                                                             .addAllMembers(addressesSerliased);
    if (groupName != null) groupContextBuilder.setName(groupName);
    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, avatarAttachment, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());//AA+

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    //AA 4 for user private group
    return new GroupActionResult(groupRecipient, threadId, recipients.size(), recipientsInvited);
  }

  List<SignalServiceProtos.UserRecord> GetUserRecordsfromSet(Set<String> numbers)
  {
    SignalServiceProtos.UserRecord.Builder userRecordBuilder = SignalServiceProtos.UserRecord.newBuilder();
    return null;
  }

  @WorkerThread
  public static void leaveGroup(@NonNull Context context, @NonNull GroupId.Push groupId)
          throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        edit.leaveGroup();
        Log.i(TAG, "Left group " + groupId);
      }
      catch (GroupInsufficientRightsException e) {
        Log.w(TAG, "Unexpected prevention from leaving " + groupId + " due to rights", e);
        throw new GroupChangeFailedException(e);
      }
      catch (GroupNotAMemberException e) {
        Log.w(TAG, "Already left group " + groupId, e);
      }
    }
    else {
      if (false)//!GroupManagerV1.leaveGroup(context, groupId.requireV1())) {//AA-
        Log.w(TAG, "GV1 group leave failed" + groupId);
      throw new GroupChangeFailedException();
    }
  }


  @WorkerThread
  public static void leaveGroupFromBlockOrMessageRequest(@NonNull Context context, @NonNull GroupId.Push groupId)
          throws IOException, GroupChangeBusyException, GroupChangeFailedException
  {
    if (groupId.isV2()) {
      leaveGroup(context, groupId.requireV2());
    }
    else {
      if (!GroupManagerV1.silentLeaveGroup(context, groupId.requireV1())) {
        throw new GroupChangeFailedException();
      }
    }
  }


  @WorkerThread
  public static void addMemberAdminsAndLeaveGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Collection<RecipientId> newAdmins)
          throws GroupChangeBusyException, GroupChangeFailedException, IOException, GroupInsufficientRightsException, GroupNotAMemberException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
      edit.addMemberAdminsAndLeaveGroup(newAdmins);
      Log.i(TAG, "Left group " + groupId);
    }
  }

  @WorkerThread
  public static void ejectAndBanFromGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Recipient recipient)
          throws GroupChangeBusyException, GroupChangeFailedException, GroupInsufficientRightsException, GroupNotAMemberException, IOException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
      edit.ejectMember(recipient.requireServiceId(), false, true);
      Log.i(TAG, "Member removed from group " + groupId);
    }
  }

  /**
   * @throws GroupNotAMemberException When Self is not a member of the group.
   *                                  The exception to this is when Self is a requesting member and
   *                                  there is a supplied signedGroupChange. This allows for
   *                                  processing deny messages.
   */
  @WorkerThread
  public static void updateGroupFromServer(@NonNull Context context,
                                           @NonNull GroupMasterKey groupMasterKey,
                                           int revision,
                                           long timestamp,
                                           @Nullable byte[] signedGroupChange)
                                           throws GroupChangeBusyException, IOException, GroupNotAMemberException
  {
    try (GroupManagerV2.GroupUpdater updater = new GroupManagerV2(context).updater(groupMasterKey)) {
      updater.updateLocalToServerRevision(revision, timestamp, signedGroupChange);
    }
  }

  @WorkerThread
  public static V2GroupServerStatus v2GroupStatus(@NonNull Context context,
                                                  @NonNull GroupMasterKey groupMasterKey)
          throws IOException
  {
    try {
      new GroupManagerV2(context).groupServerQuery(groupMasterKey);
      return V2GroupServerStatus.FULL_OR_PENDING_MEMBER;
    } catch (GroupNotAMemberException e) {
      return V2GroupServerStatus.NOT_A_MEMBER;
    } catch (GroupDoesNotExistException e) {
      return V2GroupServerStatus.DOES_NOT_EXIST;
    }
  }

  /**
   * Tries to gets the exact version of the group at the time you joined.
   * <p>
   * If it fails to get the exact version, it will give the latest.
   */
  @WorkerThread
  public static DecryptedGroup addedGroupVersion(@NonNull Context context,
                                                 @NonNull GroupMasterKey groupMasterKey)
          throws IOException, GroupDoesNotExistException, GroupNotAMemberException
  {
    return new GroupManagerV2(context).addedGroupVersion(groupMasterKey);
  }

  @WorkerThread
  public static void setMemberAdmin(@NonNull Context context,
                                    @NonNull GroupId.V2 groupId,
                                    @NonNull RecipientId recipientId,
                                    boolean admin)
                                    throws GroupChangeBusyException, GroupChangeFailedException, GroupInsufficientRightsException, GroupNotAMemberException, IOException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.setMemberAdmin(recipientId, admin);
    }
  }

  @WorkerThread
  public static void updateSelfProfileKeyInGroup(@NonNull Context context, @NonNull GroupId.V2 groupId)
          throws IOException, GroupChangeBusyException, GroupInsufficientRightsException, GroupNotAMemberException, GroupChangeFailedException
  {
    if (!SignalDatabase.groups().groupExists(groupId)) {
      Log.i(TAG, "Group is not available locally " + groupId);
      return;
    }

    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateSelfProfileKeyInGroup();
    }
  }

  @WorkerThread
  public static void acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
          throws GroupChangeBusyException, GroupChangeFailedException, GroupNotAMemberException, GroupInsufficientRightsException, IOException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.acceptInvite();
      SignalDatabase.groups()
              .setActive(groupId, true);
    }
  }

  @WorkerThread
  public static void updateGroupTimer (@NonNull Context context, @NonNull Recipient recipientGroup, int expirationTime)//AA+ recipient
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    //AA+
    GroupId groupId = recipientGroup.requireGroupId();
    long thread = recipientGroup.getCorrespondingThread(context);
    long timeSentInMillis = System.currentTimeMillis();
    FenceCommand fenceCommand = MessageSender.buildFenceCommandMessageExpirayFinal(ApplicationContext.getInstance(),
                                                                                   new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.EXPIRY_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE),
                                                                                   expirationTime * 1000,
                                                                                   recipientGroup.getUfsrvId(),
                                                                                   timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder().setFenceCommand(fenceCommand)
                                                                                    .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    OutgoingExpirationUpdateMessage outgoingMessage = new OutgoingExpirationUpdateMessage(recipientGroup, System.currentTimeMillis(), expirationTime * 1000, ufsrvCommandWireBuilder.build());
    MessageSender.send(context, outgoingMessage, thread, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);
    //

    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        editor.updateGroupTimer(expirationTime);
      }
    }
    else {
//      GroupManagerV1.updateGroupTimer(context, groupId.requireV1(), expirationTime);//AA-
    }
  }


  @WorkerThread
  public static void revokeInvites(@NonNull Context context,
          @NonNull GroupId groupId,//AA+
          @NonNull Collection<UuidCiphertext> uuidCipherTexts)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
////    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
////      editor.revokeInvites(uuidCipherTexts);
//    } //AA- not supported yet
  }

  @WorkerThread
  public static void ban(@NonNull Context context,
                         @NonNull GroupId.V2 groupId,
                         @NonNull RecipientId recipientId)
          throws GroupChangeBusyException, IOException, GroupChangeFailedException, GroupNotAMemberException, GroupInsufficientRightsException
  {
    GroupDatabase.V2GroupProperties groupProperties = SignalDatabase.groups().requireGroup(groupId).requireV2GroupProperties();
    Recipient                       recipient       = Recipient.resolved(recipientId);

    if (groupProperties.getBannedMembers().contains(recipient.requireServiceId().uuid())) {
      Log.i(TAG, "Attempt to ban already banned recipient: " + recipientId);
      return;
    }

    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.ban(recipient.requireServiceId().uuid());
    }
  }

  @WorkerThread
  public static void unban(@NonNull Context context,
                           @NonNull GroupId.V2 groupId,
                           @NonNull RecipientId recipientId)
          throws GroupChangeBusyException, IOException, GroupChangeFailedException, GroupNotAMemberException, GroupInsufficientRightsException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.unban(Collections.singleton(Recipient.resolved(recipientId).requireServiceId().uuid()));
    }
  }

  @WorkerThread
  public static void applyMembershipAdditionRightsChange(@NonNull Context context,
                                                         @NonNull GroupId.V2 groupId,
                                                         @NonNull GroupAccessControl newRights)
                                                         throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateMembershipRights(newRights);
    }
  }

  @WorkerThread
  public static void applyAnnouncementGroupChange(@NonNull Context context,
                                                  @NonNull GroupId.V2 groupId,
                                                  @NonNull boolean isAnnouncementGroup)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateAnnouncementGroup(isAnnouncementGroup);
    }
  }

  @WorkerThread
  public static void cycleGroupLinkPassword(@NonNull Context context,
                                            @NonNull GroupId.V2 groupId)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.cycleGroupLinkPassword();
    }
  }

  @WorkerThread
  public static GroupInviteLinkUrl setGroupLinkEnabledState(@NonNull Context context,
                                                            @NonNull GroupId.V2 groupId,
                                                            @NonNull GroupLinkState state)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      return editor.setJoinByGroupLinkState(state);
    }
  }

  @WorkerThread
  public static void approveRequests(@NonNull Context context,
                                     @NonNull GroupId.V2 groupId,
                                     @NonNull Collection<RecipientId> recipientIds)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.approveRequests(recipientIds);
    }
  }

  @WorkerThread
  public static void denyRequests(@NonNull Context context,
                                  @NonNull GroupId.V2 groupId,
                                  @NonNull Collection<RecipientId> recipientIds)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.denyRequests(recipientIds);
    }
  }

  @WorkerThread
  public static void applyAttributesRightsChange(
          @NonNull Context context,
          @NonNull GroupId.V2 groupId,
          @NonNull GroupAccessControl newRights)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateAttributesRights(newRights);
    }
  }

  //AA+
  @WorkerThread
  public static @NonNull
  GroupActionResult addMembers(@NonNull Context context,
                               @NonNull GroupId.Push groupId,
                               @NonNull Collection<RecipientId> newMembers)
                               throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException, MembershipNotSuitableForV2Exception
  {
      return sendGroupUpdateInvitedMembersList(context, groupId, newMembers, Collections.EMPTY_LIST, new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.INVITE_VALUE, CommandArgs.ADDED_VALUE));
  }

 /* @WorkerThread
  public static @NonNull GroupActionResult addMembers(@NonNull Context context,
                                                      @NonNull GroupId.Push groupId,
                                                      @NonNull Collection<RecipientId> newMembers)
          throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException, MembershipNotSuitableForV2Exception
  {
    if (groupId.isV2()) {
      GroupDatabase.GroupRecord groupRecord  = SignalDatabase.groups().requireGroup(groupId);

      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        return editor.addMembers(newMembers, groupRecord.requireV2GroupProperties().getBannedMembers());
      }
    } else {
      GroupDatabase.GroupRecord groupRecord  = SignalDatabase.groups().requireGroup(groupId);
      List<RecipientId>         members      = groupRecord.getMembers();
      byte[]                    avatar       = groupRecord.hasAvatar() ? AvatarHelper.getAvatarBytes(context, groupRecord.getRecipientId()) : null;
      Set<RecipientId>          recipientIds = new HashSet<>(members);
      int                       originalSize = recipientIds.size();
      recipientIds.addAll(newMembers);
      return GroupManagerV1.updateGroup(context, groupId, recipientIds, avatar, groupRecord.getTitle(), recipientIds.size() - originalSize);
    }
  }*/

  /**
   * Use to get a group's details direct from server bypassing the database.
   * <p>
   * Useful when you don't yet have the group in the database locally.
   */
 /* @WorkerThread
  public static @NonNull DecryptedGroupJoinInfo getGroupJoinInfoFromServer(@NonNull Context context,
                                                                           @NonNull GroupMasterKey groupMasterKey,
                                                                           @Nullable GroupLinkPassword groupLinkPassword)
          throws IOException, VerificationFailedException, GroupLinkNotActiveException
  {
    return new GroupManagerV2(context).getGroupJoinInfoFromServer(groupMasterKey, groupLinkPassword, -1L);//AA+
  }*/

  //AA+
  @WorkerThread
  public static @NonNull DecryptedGroupJoinInfo getGroupJoinInfoFromServer(@NonNull Context context,
                                                                           @NonNull GroupMasterKey groupMasterKey,
                                                                           @Nullable GroupLinkPassword groupLinkPassword,
                                                                           Long fid)
          throws IOException, VerificationFailedException, GroupLinkNotActiveException
  {
    return new GroupManagerV2(context).getGroupJoinInfoFromServer(groupMasterKey, groupLinkPassword, fid);
  }


  @WorkerThread
  public static GroupActionResult joinGroup(@NonNull Context context,
                                            @NonNull GroupMasterKey groupMasterKey,
                                            @NonNull GroupLinkPassword groupLinkPassword,
                                            @NonNull DecryptedGroupJoinInfo decryptedGroupJoinInfo,
                                            @Nullable byte[] avatar)
          throws IOException, GroupChangeBusyException, GroupChangeFailedException, MembershipNotSuitableForV2Exception, GroupLinkNotActiveException
  {
    try (GroupManagerV2.GroupJoiner join = new GroupManagerV2(context).join(groupMasterKey, groupLinkPassword)) {
      return join.joinGroup(decryptedGroupJoinInfo, avatar);
    }
  }

  @WorkerThread
  public static void cancelJoinRequest(@NonNull Context context,
                                       @NonNull GroupId.V2 groupId)
          throws GroupChangeFailedException, IOException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupJoiner editor = new GroupManagerV2(context).cancelRequest(groupId.requireV2())) {
      editor.cancelJoinRequest();
    }
  }

  public static void sendNoopUpdate(@NonNull Context context, @NonNull GroupMasterKey groupMasterKey, @NonNull DecryptedGroup currentState) {
    new GroupManagerV2(context).sendNoopGroupUpdate(groupMasterKey, currentState);
  }

  @WorkerThread
  public static @NonNull
  GroupExternalCredential getGroupExternalCredential(@NonNull Context context,
                                                     @NonNull GroupId.V2 groupId)
          throws IOException, VerificationFailedException
  {
    return new GroupManagerV2(context).getGroupExternalCredential(groupId);
  }

  @WorkerThread
  public static @NonNull
  Map<UUID, UuidCiphertext> getUuidCipherTexts(@NonNull Context context, @NonNull GroupId.V2 groupId) {
    return new GroupManagerV2(context).getUuidCipherTexts(groupId);
  }

  public static class GroupActionResult {
    private final Recipient groupRecipient;
    private final long threadId;
    private final int addedMemberCount;
    private final List<RecipientId> invitedMembers;


    public GroupActionResult (
            @NonNull Recipient groupRecipient,
            long threadId,
            int addedMemberCount,
            @NonNull List<RecipientId> invitedMembers)
    {
      this.groupRecipient = groupRecipient;
      this.threadId = threadId;
      this.addedMemberCount = addedMemberCount;
      this.invitedMembers = invitedMembers;
    }


    public @NonNull
    Recipient getGroupRecipient ()
    {
      return groupRecipient;
    }


    public long getThreadId ()
    {
      return threadId;
    }


    public int getAddedMemberCount ()
    {
      return addedMemberCount;
    }


    public @NonNull
    List<RecipientId> getInvitedMembers ()
    {
      return invitedMembers;
    }
  }

  public enum GroupLinkState {
    DISABLED,
    ENABLED,
    ENABLED_WITH_APPROVAL
  }

  public enum V2GroupServerStatus {
    /** The group does not exist. The expected pre-migration state for V1 groups. */
    DOES_NOT_EXIST,
    /** Group exists but self is not in the group. */
    NOT_A_MEMBER,
    /** Self is a full or pending member of the group. */
    FULL_OR_PENDING_MEMBER
  }
}
