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

package com.unfacd.android.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.text.TextUtils;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.fence.FenceDescriptor;
import com.unfacd.android.fence.FencePermissions;
import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.InvitedToGroupDialog;

import net.zetetic.database.sqlcipher.SQLiteConstraintException;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.conversation.ConversationIntents;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.util.Base64;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UfsrvFenceUtils
{
  private static final String TAG = Log.tag(UfsrvFenceUtils.class);


  /**
   * Invitations arriving from the server end have the command set as JOIN and Args as INVITED
   * @param fenceCommand the command context identifying current state of the fence.
   * @return
   */
  static public boolean isFenceCommandJoinInvitation(FenceCommand fenceCommand)
  {
    if (fenceCommand != null &&  fenceCommand.getFencesCount() > 0) {
      if (fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.JOIN_VALUE &&
              ( fenceCommand.getHeader().getArgs() == CommandArgs.INVITED_VALUE ||
                fenceCommand.getHeader().getArgs() == CommandArgs.INVITED_GEO_VALUE)) return true;
    }

    return false;
  }

  static public boolean isFenceCommandJoinInvitationGeo(FenceCommand fenceCommand)
  {
    if (fenceCommand != null &&  fenceCommand.getFencesCount() > 0) {
      if (fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.JOIN_VALUE &&
          fenceCommand.getHeader().getArgs()    == CommandArgs.INVITED_GEO_VALUE)

      return true;
    }

    return false;
  }

  static public boolean isFenceCommandLeave(FenceCommand fenceCommand)
  {
    if (fenceCommand != null &&  fenceCommand.getFencesCount() > 0) {
      if (fenceCommand.getHeader().getCommand() == FenceCommand.CommandTypes.LEAVE_VALUE ||
              fenceCommand.getHeader().getCommand()  == FenceCommand.CommandTypes.INVITE_DELETED_VALUE)

        return true;
    }

    return false;
  }

  /**
   * Marshal a request to join a fence. Typically in response to a StateSync message from the server, where server's state indicate that
   * client is member of such group, yet client's internal state is not inline with that. So, we go ahead and setup the group and request full sync.
   * Command: JOIN
   * Arg: SYNCED
   *
   * @param fid
   */
  static public void sendStateSyncJoin(GroupMasterKey groupMasterKey, long fid, String fname, String fcname,
                                       GroupDatabase.GroupType groupType,
                                       GroupDatabase.PrivacyMode privacyMode,
                                       GroupDatabase.DeliveryMode deliveryMode,
                                       GroupDatabase.JoinMode joinMode)
  {
    GroupDatabase   groupDatabase   =SignalDatabase.groups();
    GroupId         groupId         = GroupId.v2(groupMasterKey);

    GroupDatabase.GroupControlsDescriptor groupControls = new GroupDatabase.GroupControlsDescriptor(groupType, 0, privacyMode, deliveryMode, joinMode, null, 0);
    try {
      long id = groupDatabase.create(groupId, groupMasterKey, fname, new LinkedList<>(), null, null, fcname, 0.0, 0.0, GroupDatabase.GROUP_MODE_JOIN_SYNCED, fid, 0, groupType, privacyMode, deliveryMode, joinMode);
//use below when ready
      //    long id = groupDatabase.create(groupId, fname, new LinkedList<>(), null, null, null, groupMasterKey, null, GroupDatabase.GROUP_MODE_JOIN_SYNCED, fid, 0, groupControls);

      if (id != -1) sendStateSyncJoinForPartiallyExistingGroup(groupId, fid);
    }  catch (SQLiteConstraintException x) {
       Log.e(TAG, String.format("sendStateSyncJoin {fid:'%d', cname:'%s': !! POSSIBLE UNIQUE CONSTRAINT VIOLATION: INSTRUCTING SERVER TO REMOVE", fid, fcname));
       sendServerCommandFenceLeaveForRogueFence(fid);
    }

  }

  /**
   * This is a join-request message for the local user joining via QR or join url link.
   */
  static public GroupManager.GroupActionResult sendFenceLinkJoinRequest(GroupMasterKey groupMasterKey, long fid, String fname)
  {
    GroupDatabase   groupDatabase   = SignalDatabase.groups();
    GroupId         groupId         = GroupId.v2(groupMasterKey);

    try {
      RecipientId groupRecipientId;
      GroupDatabase.GroupRecord group = SignalDatabase.groups().getGroupRecordByFid(fid);
      if (group == null) {
        long id = groupDatabase.create(groupId, groupMasterKey, fname, fid, GroupDatabase.GROUP_MODE_LINKJOIN_REQUESTING, GroupDatabase.GroupControlsDescriptor.getDefaultGroupControls());
        groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
      } else {
        groupRecipientId = group.getRecipientId();
      }
      Recipient   groupRecipient   = Recipient.resolved(groupRecipientId);
      long timeNowInMillis  = System.currentTimeMillis();
      FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandForType(Collections.emptyList(), fid, timeNowInMillis, FenceCommand.CommandTypes.LINKJOIN, CommandArgs.ADDED_VALUE);
      UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
                                                                    .setFenceCommand(fenceCommandBuilder.build())
                                                                    .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      SignalServiceProtos.GroupContext groupContext = GroupContext.newBuilder()
                                                                  .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                  .setType(GroupContext.Type.UPDATE)
                                                                  .setFenceMessage(fenceCommandBuilder.build())
                                                                  .build();
      OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());

      //NOTE -1 will create a threadid
      long thread_id = MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

      groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_LINKJOIN_REQUESTING);
      return new GroupManager.GroupActionResult(groupRecipient, thread_id, 0, Collections.emptyList());
    }  catch (SQLiteConstraintException x) {
      Log.e(TAG, String.format("sendStateSyncJoin {fid:'%d', cname:'%s': !! POSSIBLE UNIQUE CONSTRAINT VIOLATION: INSTRUCTING SERVER TO REMOVE", fid, fname));
    }

    return new GroupManager.GroupActionResult(Recipient.UNKNOWN, -1, 0, Collections.emptyList());
  }

  /**
   * This is a linkjoin message by a group admin, authorising a group join request
   */
  static public GroupManager.GroupActionResult sendFenceLinkJoinAdminAction(@NonNull GroupId groupId, @NonNull Collection<RecipientId> recipientIds, boolean isApproving)
  {
    GroupDatabase.GroupRecord groupRecord     = SignalDatabase.groups().getGroupByGroupId(groupId);
    Recipient                 groupRecipient  = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();

    long timeNowInMillis  = System.currentTimeMillis();
    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandForType(Collections.emptyList(), groupRecord.getFid(), timeNowInMillis, FenceCommand.CommandTypes.LINKJOIN, isApproving? CommandArgs.ACCEPTED_VALUE : CommandArgs.REJECTED_VALUE);
    SignalServiceProtos.UserRecord.Builder userRecordBuilder = SignalServiceProtos.UserRecord.newBuilder();
    userRecordBuilder.setUsername("*");
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
                                                                  .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    SignalServiceProtos.GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                                               .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                               .setType(GroupContext.Type.UPDATE);
    Set<String> ufsrvUids = Stream.of(recipientIds)
                                  .map(r -> Recipient.resolved(r).requireUfsrvUid())
                                  .collect(Collectors.toSet());
    long thread_id = -1;
     for (String ufsrvUid : ufsrvUids) {
       fenceCommandBuilder.setOriginator(userRecordBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(ufsrvUid))).build());
       ufsrvCommandBuilder.setFenceCommand(fenceCommandBuilder.build());
       groupContextBuilder.setFenceMessage(ufsrvCommandBuilder.getFenceCommand());

       OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContextBuilder.build(), null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());

       thread_id = MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);
     }

      return new GroupManager.GroupActionResult(groupRecipient, thread_id, 0, Collections.emptyList());

  }

  /**
   * This is a helper method, which shouldn't be called directly without a driver (eg. sendStateSyncJoin).
   * Initiate a new thread for a partially created group (Only group exists). This is designed to resolve instances where
   * a StateSync message from the server indicates that we are member of fid, but client's internalstate IS NOT consistent with that.
   *
   * Key point is that the server has the fence already on, hesend JOIN/SYNCED to server indicating that we joining based on state sysnc as
   * opposed to invitation for example.
   * Command: JOIN
   * Arg:SYNCED
   *
   * @param fid
   */
  static private void sendStateSyncJoinForPartiallyExistingGroup(GroupId groupId, long fid)
  {
    long timeNowInMillis  = System.currentTimeMillis();

    GroupDatabase   groupDatabase   =SignalDatabase.groups();
    Optional<GroupDatabase.GroupRecord>  groupRecord = groupDatabase.getGroup(groupId);
    Recipient      groupRecipient  = Recipient.live(groupRecord.get().getRecipientId()).get();

    //todo: this is temporary fix. We should never find ourselves in this kind of situation
    if (groupRecipient.equals(Recipient.UNKNOWN)) {
      Log.e(TAG, String.format("sendStateSyncJoinForPartiallyExistingGroup (fid:'%d', groupId:'%s', recpientId:'%s'): DATA ERROR: NO RECIPIENTID FOUND IN GROUP RECORD: QUERYING RECIPIENT TABLE...", fid, groupRecord.get().getId().toString(), groupRecord.get().getRecipientId()));
      Optional<RecipientId> recipientId =SignalDatabase.recipients().getByGroupId(groupId);
      if (recipientId.isPresent()) {
        groupDatabase.updateRecipientId(groupId, recipientId.get());
        groupRecipient  = Recipient.live(recipientId.get()).get();
      } else {
        Log.e(TAG, String.format("sendStateSyncJoinForPartiallyExistingGroup (fid:'%d', groupId:'%s', recpientId:'%s'): DATA ERROR: NO RECIPIENTID FOUND IN GROUP RECORD: DELETING RECORD AND SENDING LEAVE MESSAGE", fid, groupRecord.get().getId().toString(), groupRecord.get().getRecipientId()));
        groupDatabase.cleanUpGroup(groupId, 0); //todo: check thread
        UfsrvFenceUtils.sendServerCommandFenceLeave(fid);
        return;
      }
    }

    FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), fid, timeNowInMillis, true, true);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
                                                                  .setFenceCommand(fenceCommandBuilder.build())
                                                                  .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    SignalServiceProtos.GroupContext groupContext =GroupContext.newBuilder()
                                                               .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                               .setType(GroupContext.Type.UPDATE)
                                                               .setFenceMessage(fenceCommandBuilder.build())//AA+
                                                               .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());

    //NOTE -1 will create a threadid
    MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.LOCAL_PIPE, false);

    groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_JOIN_SYNCED);
  }

  //This is a stateSync join request that doesn't change the exiting model data (we are not creating group or threadid as they already exist) we are sending
  //a JOIN request to sync the SERVER"S side JOIN/SYNCED
  static private void sendStateSyncJoinForExistingGroup(long fid)
  {
    long timeNowInMillis                = System.currentTimeMillis();
    GroupDatabase   groupDatabase       = SignalDatabase.groups();
    GroupDatabase.GroupRecord groupRec  = groupDatabase.getGroupRecordByFid(fid);
    GroupId        groupId              = groupRec.getId();
    Recipient      groupRecipient       = Recipient.live(groupRec.getRecipientId()).get();

    FenceCommand.Builder fenceCommandBuilder    = MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), fid, timeNowInMillis, true, true);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
                                                                  .setFenceCommand(fenceCommandBuilder.build())
                                                                  .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext groupContext = GroupContext.newBuilder()
                                            .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                            .setType(GroupContext.Type.UPDATE)
                                            .setFenceMessage(fenceCommandBuilder.build())//AA+
                                            .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandBuilder.build());

    //NOTE -1 will create a threadid
    MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.LOCAL_PIPE, false);
  }

  /**
   * Marshal a request to state-sync existing fence.
   * Command: STATE
   * Arg:SYNCED
   *
   * @param fid
   */
  static public long sendStateSyncForGroup(long fid)
  {
    long                      timeNowInMillis = System.currentTimeMillis();
    ThreadDatabase            threadDatabase  = SignalDatabase.threads();
    GroupDatabase             groupDatabase   = SignalDatabase.groups();
    GroupDatabase.GroupRecord groupRec        = groupDatabase.getGroupRecordByFid(fid);

    FenceCommand.Builder fenceCommandBuilder      = MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), groupRec.getFid(), timeNowInMillis, true, false);
    UfsrvCommandWire.Builder ufsrvCommandBuilder  = UfsrvCommandWire.newBuilder()
                                                                    .setFenceCommand(fenceCommandBuilder.build())
                                                                    .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext groupContext = GroupContext.newBuilder()
                                            .setId(ByteString.copyFrom(groupRec.getId().getDecodedId()))
                                            .setType(GroupContext.Type.UPDATE)
                                            .setFenceMessage(fenceCommandBuilder.build())//AA+
                                            .build();

    Recipient      groupRecipient  = Recipient.live(groupRec.getRecipientId()).get();
    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandBuilder.build());

    //NOTE: getOrCreateThreadIdFor() DOES NOT create a new threadId where one does not exist against fid
    long threadId = threadDatabase.getThreadIdFor(groupRecipient, fid);

    return MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.LOCAL_PIPE, false);
  }

  /**
   * Marshal a request to sync an invited group (essentially requesting resend of invite message). Group/thread must be setup already
   * Command: STATE
   */
  static public long sendStateSyncForInvitedGroup(FenceDescriptor jsonEntityfenceDescriptor)
  {
    long                      timeNowInMillis = System.currentTimeMillis();
    ThreadDatabase            threadDatabase  = SignalDatabase.threads();
    GroupDatabase             groupDatabase   =SignalDatabase.groups();
    GroupDatabase.GroupRecord groupRec        = groupDatabase.getGroupRecordByFid(jsonEntityfenceDescriptor.getFid());

    FenceCommand.Builder fenceCommandBuilder= buildFenceCommandHeader(groupRec.getFid(), timeNowInMillis, CommandArgs.SYNCED, FenceCommand.CommandTypes.INVITE);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext groupContext = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupRec.getId().getDecodedId()))
            .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommandBuilder.build())//AA+
            .build();

    Recipient      groupRecipient  = Recipient.live(groupRec.getRecipientId()).get();
    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandBuilder.build());

    //NOTE: getOrCreateThreadIdFor() DOES NOT create a new threadId where one does not exist against fid
//    long threadId=threadDatabase.getOrCreateThreadIdFor(groupRecipient, jsonEntityfenceDescriptor.getFid());

    return MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.LOCAL_PIPE, false);
  }

  /**
   * Resolve the FencesList provided by the server in its StateSync message. Epic.
   * @param fences Json list
   */
  static public void synchroniseFenceList(List<FenceDescriptor> fences)
  {
    if (fences == null)  fences = new LinkedList<>();

    Cursor                          cursor,
                                    cursorInvited;
    HashMap<Long, FenceDescriptor>  missing;
    HashMap<Long, FenceDescriptor>  processed;
    Pair<HashMap<Long, FenceDescriptor>, HashMap<Long, FenceDescriptor>>           done          = null;

    cursor = SignalDatabase.threads().getConversationList();
    cursorInvited = SignalDatabase.threads().getInvitedConversationListAll(0, 0);
    HashMap<Long, ThreadRecord> fidToInvitedMap = invitedCursorToFidMap(cursorInvited);

    Log.d(TAG, String.format("synchroniseFenceList: Processing Internal ThreadList size:%d'. Internal InvitedList: '%d', FenceList size: '%d'.", cursor.getCount(), cursorInvited.getCount(), fences.size()));

    try {
      done = resolveServerFenceList(cursor, fences);
      processed = done.first();
      missing   = done.second();

      Log.d(TAG, String.format("synchroniseFenceList:  FOUND %d locally referenced fences. Found: '%d' StateSyenc fences that were NOT referenced in ThreadList...", processed.size(), missing.size()));

      resolveProcessedServerFenceList(processed, fidToInvitedMap);
      resolveMissingServerFenceList(processed, missing);
      resolveUnreferencedThreadListEntries(cursor, processed);
    } finally {
      if (cursor != null)       cursor.close();
      if (cursorInvited != null)  cursorInvited.close();
    }

  }

  /**
   * Using the provided FenceList by the server, mark entries which have a corresponding match in the internal ThreadsList (processed)
   * Missing list will contain the list of fence entries which are not present in the internal ThreadsList.
   *
   * @param fences List of fences originating from servers StateSync in its original raw json format
   *
   * @return  Pair of HashMaps keyed on fid with values from the Server's FenceList
   */
  static private Pair<HashMap<Long, FenceDescriptor>, HashMap<Long, FenceDescriptor>>
  resolveServerFenceList(Cursor cursor, List<FenceDescriptor> fences)
  {
    final GroupDatabase             groupDatabase =SignalDatabase.groups();
    HashMap<Long, FenceDescriptor>  missing       = new HashMap<>();
    HashMap<Long, FenceDescriptor>  processed     = new HashMap<>();

    for (FenceDescriptor f : fences) {
      Log.d(TAG, String.format("ResolveServerFenceList: Server View -> FenceList: CLIENT IS MEMBER OF '%s'. fid:'%d'", f.getFcname(), f.getFid()));

      //check presence of fence in our internal records
      if (cursor != null && cursor.getCount() > 0) {
        cursor.moveToFirst();

        while (cursor != null && !cursor.isAfterLast()) {
          ThreadRecord rec = SignalDatabase.threads().readerFor(cursor).getCurrent();

          if (f.getFid() == rec.getUfsrvFid()) {
            GroupDatabase.GroupRecord localCopyFence = groupDatabase.getGroupRecordByFid(f.getFid());

            if (localCopyFence != null) {
              Log.d(TAG, String.format("ResolveServerFenceList:  Client View -> FOUND reference for FENCE ID: '%d' fcname:'%s' in ThreadList ", f.getFid(), localCopyFence.getCname()));
              if (processed.get(f.getFid()) != null) {
                Log.d(TAG, String.format("ResolveServerFenceList:  ERROR: DATA INTEGRITY: MULTIPLE REFERENCES OF id:'%d' when processing threadId:'%d' ", f.getFid(), rec.getThreadId()));
                cursor.moveToNext();
                continue;
              }

              //success case
              processed.put(f.getFid(), f);
              missing.remove(f.getFid());//it might have been added when fences did not match, id-wise
            } else {
              Log.e(TAG, String.format("ResolveServerFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in ThreadRecord threadId:'%d', but HAS NO CORRESPONDING GROUP RECORD", rec.getUfsrvFid(), rec.getThreadId()));
              //todo: heal yourself remove threadId from system
            }
            break;//dont traverse the list further
          } else {
            missing.put(f.getFid(), f);
          }

          cursor.moveToNext();
        }
      } else {
        Log.e(TAG, String.format("ResolveServerFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in Sever's List, but local ThreadList is empty", f.getFid()));
        missing.put(f.getFid(), f);
      }
    }

    return new Pair(processed, missing);
  }

  /**
   * Process the fences which were present in both lists: one supplied by server and one seen internal in ThreadList.
   * Some threads maybe inactive. The default action is to request a StateSync message from the server and where group is inactive to activate it.
   *
   * @param processed
   */

  static private void
  resolveProcessedServerFenceList(@NonNull HashMap<Long, FenceDescriptor> processed, @NonNull HashMap<Long, ThreadRecord> fidToThreadRecordMap)
  {
    final GroupDatabase         groupDatabase   =SignalDatabase.groups();
    GroupDatabase.GroupRecord   groupRecord     = null;

    if (processed.size() > 0) {
      Log.d(TAG, String.format("resolveProcessedServerFenceList:  PROCESSing %d fences that were referenced in ThreadList...", processed.size()));

      ThreadRecord threadRecordInvited;
      Set<Long> keySet = processed.keySet();
      Iterator<Long> keySetIterator = keySet.iterator();
      while (keySetIterator.hasNext()) {
        Long fid = keySetIterator.next();
        Recipient groupRecipient = Recipient.live(fid).get();
        Long eid = groupRecipient.getEid();

        if ((threadRecordInvited = fidToThreadRecordMap.get(Long.valueOf(fid))) != null) {
          groupRecord = groupDatabase.getGroupRecordByFid(fid);
          Log.e(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', mode:'%d'): FOUND INVITED FENCE IN LIST: SHIFTING TO OPEN...", fid, groupRecord.getMode(), groupRecord.getCname()));

          //reassign group mode to indicate open/active
          if      (groupRecord.getMode() == GroupDatabase.GROUP_MODE_INVITATION)        groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED);
          else if (groupRecord.getMode() == GroupDatabase.GROUP_MODE_GEOBASED_INVITE)   groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED);//we may assign diferent mode for geo based invites
          fidToThreadRecordMap.remove(Long.valueOf(fid));
          //continue through
        }

        if (groupRecord == null) groupRecord = groupDatabase.getGroupRecordByFid(fid);

        if (processed.get(fid).getEid() != eid) {
          Log.d(TAG, String.format("resolveProcessedServerFenceList (internal_eid:'%d', server_eid:'%d'):  PROCESSing fid:'%d', cname:'%s. Active status: '%b'...", eid, processed.get(fid).getEid(), fid, groupRecord.getCname(), groupRecord.isActive()));

          if (!groupRecord.isActive()) groupDatabase.setActive(groupRecord.getId(), true);

          sendStateSyncForGroup(fid);
        } else  Log.d(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', internal_eid:'%d', server_eid:'%d':  NOT PROCESSing EID uptodate ", fid, eid, processed.get(fid).getEid()));

        //self-healing...
        if (groupRecord.getMode() < 0) {
          Log.e(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', GroupDatabase.cname:'%s'): ERROR: GROUP MODE HAD INVALID VALUE: Reassigning mode to default JOIN_ACCEPTED (it could have been INVITED)", groupRecord.getFid(), groupRecord.getCname()));
          groupDatabase.markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_JOIN_ACCEPTED);
        }

        groupRecord = null;
      }
    }
  }

  /**
   * Process the fences which were sent by the server that did not have corresponding reference in the local ThreadDatabase List.
   *
   * @param processed
   * @param missing
   */
  static private void resolveMissingServerFenceList(@NonNull HashMap<Long, FenceDescriptor> processed, @NonNull HashMap<Long, FenceDescriptor> missing)
  {
    if (missing.size() > 0) {
      Log.d(TAG, String.format("resolveMissingServerFenceList:  PROCESSing %d fences that were not referenced in ThreadList...", missing.size()));
      Set<Long> keySet = missing.keySet();
      Iterator<Long> keySetIterator = keySet.iterator();

      while (keySetIterator.hasNext()) {
        Long fid = keySetIterator.next();
        Log.d(TAG, String.format("resolveMissingServerFenceList:  PROCESSing missing {fid:'%d', cname:'%s', invited:'%b'}...", fid, missing.get(fid).getFcname(), missing.get(fid).isInvited()));

        if (missing.get(fid).isInvited()) {
          //reissue invite
          Log.d(TAG, String.format("resolveMissingServerFenceList:  REQUESTING INVITE/SYNC {fid:'%d', cname:'%s', invited:'%b'}...", fid, missing.get(fid).getFcname(), missing.get(fid).isInvited()));
        } else {
          resolveMissingServerFence(missing.get(fid));
        }
        //add it to completed list
        processed.put(fid, missing.get(fid));
      }
    }
  }

  /*
   * This reflects internal statesync issues: the server thinks we are member of this fence,but we DON"T HAVE IT on the
    * ThreadList, so we have to resolve its internal state representation, especially with respect to
    * whether a corresponding Group entry exist for it or not.
   * and handle accordingly
   */
  static private void resolveMissingServerFence(FenceDescriptor jsonEntityfenceDescriptor)
  {
    GroupDatabase             groupDatabase =SignalDatabase.groups();
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupRecordByFid(jsonEntityfenceDescriptor.getFid());

    if (groupRecord != null) {
      if (!groupDatabase.isActive(groupRecord.getId())) {
        Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') FOUND INACTIVE GROUP: INSTRUCTING SERVER TO DELETE", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));
        sendServerCommandFenceLeave(groupRecord.getFid());
      } else {
        Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') FOUND ACTIVE GROUP: SENDING A JOIN REQUEST FOR RESYNC", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));
        sendStateSyncJoinForPartiallyExistingGroup(groupRecord.getId(), jsonEntityfenceDescriptor.getFid());
      }
    } else if (jsonEntityfenceDescriptor.getFid() > 0) {
      Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: SENDING A JOIN REQUEST FOR RESYNC", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));

      groupDatabase.isGroupAvailableByCname(jsonEntityfenceDescriptor.getFcname(), true);//nuke stale group record if already exists

      if (!TextUtils.isEmpty(jsonEntityfenceDescriptor.getFname()) && !TextUtils.isEmpty(jsonEntityfenceDescriptor.getFcname())) {
        GroupMasterKey groupMasterKey;
        try {
          groupMasterKey = new GroupMasterKey(Base64.decode(jsonEntityfenceDescriptor.getFenceKey()));
        } catch (IOException | InvalidInputException x) {
          throw new AssertionError("ERROR: BAD GROUP KEY");
        }
        sendStateSyncJoin(groupMasterKey, jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFname(),
                          jsonEntityfenceDescriptor.getFcname(),
                          GroupDatabase.GroupType.values()[jsonEntityfenceDescriptor.getType()],
                          GroupDatabase.PrivacyMode.values()[jsonEntityfenceDescriptor.getPrivacy_mode()],
                          GroupDatabase.DeliveryMode.values()[jsonEntityfenceDescriptor.getDelivery_mode()],
                          GroupDatabase.JoinMode.values()[jsonEntityfenceDescriptor.getJoin_mode()]);
      } else {
        Log.e(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: NO VALID JSONENTITY CNAME AND FNAME PRESENT: ASKING SERVER TO DELETE", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));
        sendServerCommandFenceLeave(jsonEntityfenceDescriptor.getFid());
      }
    } else {
      Log.e(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: NO VALID JSONENTITY DATA: ABORTING", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));
    }

  }

  /**
   * Process Fences which are internally referenced in ThreadList but the server doesn't appear to know of. Default action is to sync join from client side, unless the
   * thread belongs to inactive group
   *
   * @param cursor ThreadDatabase sourced result set
   * @param processed List of fences, reflecting server's view obtained via StetSync
   */
  static private void resolveUnreferencedThreadListEntries(Cursor cursor, @NonNull HashMap<Long, FenceDescriptor> processed)
  {
    GroupDatabase   groupDatabase   =SignalDatabase.groups();
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();

    if (cursor != null && cursor.getCount() > 0) {
      cursor.moveToFirst();

      while (cursor != null &&  !cursor.isAfterLast()) {
        ThreadRecord rec = SignalDatabase.threads().readerFor(cursor).getCurrent();
//        GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroupRecordByFid(rec.getUfsrvFid());

        //record not in the processed list
        if (rec != null && processed.get(rec.getUfsrvFid()) == null) {
          GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroupRecordByFid(rec.getUfsrvFid());
          if (groupRecord != null) {
            boolean isGroupActive = groupDatabase.isActive(groupRecord.getId());
            Log.d(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
            if (!groupDatabase.isActive(groupRecord.getId())) {
              Log.w(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  FOUND INACTIVE Fence {fid:'%d', cname:'%s' threadid:'%d'}: (NOT)INSTRUCTING SERVER TO DELETE...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId()));
              //sendServerCommandFenceLeave (masterSecret, groupRecord.getFid());//this would be confusing
              //fallthrough below and advance cursor
            } else if (threadDatabase.isThreadForJoinInvitation(rec.getThreadId())) {
              Log.w(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Invitation Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
            } else {
              //fence in threadlist, not invitation, has corresponsing group.. process based on active status
              if (isGroupActive) {
                Log.w(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Server-absent Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync: ACTIVE GROUP: SEND JOIN/SYNCED REQUEST...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
                sendStateSyncJoinForExistingGroup(rec.getUfsrvFid());
              } else {
                Log.e(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Server-absent Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync: GROUP NOT ACTIVE: DELETING THRED...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
                SignalDatabase.threads().deleteConversation(rec.getThreadId());
              }
            }
          } else {
            Recipient recipient = threadDatabase.getRecipientForThreadId(rec.getThreadId());
            if (!recipient.isReleaseNotes()) {
              if (rec.getUfsrvFid() == 0 || !groupDatabase.isGuardian(rec.getRecipient().getGroupId().get())) {
                Log.e(TAG, String.format(Locale.getDefault(), "resolveUnreferencedThreadListEntries:  ERROR: DATA INTEGRITY: Fence {fid:'%d', threadid:'%d'} doesn't have a corresponding Group: DELETING...", rec.getUfsrvFid(), rec.getThreadId()));
                SignalDatabase.threads().deleteConversation(rec.getThreadId());
              }
            }
          }
        }

        cursor.moveToNext();
      }
    }
  }

  /**
   * Resolve the FencesList provided by the server in its StateSync message. Epic.
   * @param fences Json list
   */
  static public void synchroniseInvitedFenceList(List<FenceDescriptor> fences)
  {
    if (fences == null)  fences = new LinkedList<>();

    Cursor                          cursor,
    cursorInvited;
    HashMap<Long, FenceDescriptor>  missing;
    HashMap<Long, FenceDescriptor>  processed;
    Pair<HashMap<Long, FenceDescriptor>, HashMap<Long, FenceDescriptor>>           done;

    cursorInvited = SignalDatabase.threads().getInvitedConversationListAll(0, 0);
    HashMap<Long, ThreadRecord> fidToInvitedMap = invitedCursorToFidMap(cursorInvited);

    Log.d(TAG, String.format(Locale.getDefault(), "synchroniseInvitedFenceList: Processing Internal InvitedList: '%d', FenceList size: '%d'.", cursorInvited.getCount(), fences.size()));

      done = resolveServerInvitedFenceList(cursorInvited, fences);
      processed = done.first();
      missing   = done.second();

      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseInvitedFenceList:  FOUND %d locally referenced invited fences. Found: '%d' StateSyenc invited fences that were NOT referenced in ThreadList...", processed.size(), missing.size()));

      resolveProcessedServerInvitedFenceList(processed, fidToInvitedMap);
      resolveMissingServerInvitedFenceList(processed, missing);
      resolveUnreferencedInvitedThreadListEntries(cursorInvited, processed);

      if (cursorInvited != null)  cursorInvited.close();

  }

  /**
   * Using the provided invited FenceList by the server, mark entries which have a corresponding match in the internalThreadsList (processed)
   * Missing list will contain the list of fence entries which are not present in the internal ThreadsList.
   *
   * @param fences List of fences originating from servers StateSync in its original raw json format
   *
   * @return  Pair of HashMaps keyed on fid with values from the Server's FenceList
   */
  static private Pair<HashMap<Long, FenceDescriptor>, HashMap<Long, FenceDescriptor>>
  resolveServerInvitedFenceList(Cursor cursor, List<FenceDescriptor> fences)
  {
    final GroupDatabase             groupDatabase =SignalDatabase.groups();
    HashMap<Long, FenceDescriptor>  missing       = new HashMap<>();
    HashMap<Long, FenceDescriptor>  processed     = new HashMap<>();

    for (FenceDescriptor f : fences) {
      Log.d(TAG, String.format(Locale.getDefault(), "resolveServerInvitedFenceList: Server View: FenceList: CLIENT IS INVITED TO '%s'. fid:'%d'", f.getFcname(), f.getFid()));

      //check presence of fence in our internal records
      if (cursor != null && cursor.getCount() > 0) {
        cursor.moveToFirst();

        while (cursor != null && !cursor.isAfterLast()) {
          ThreadRecord rec = SignalDatabase.threads().readerFor(cursor).getCurrent();

          if (f.getFid() == rec.getUfsrvFid()) {
            GroupDatabase.GroupRecord localCopyFence = groupDatabase.getGroupRecordByFid(f.getFid());

            if (localCopyFence != null) {
              Log.d(TAG, String.format(Locale.getDefault(), "resolveServerInvitedFenceList:  Client View: FOUND reference for FENCE ID: '%d' fcname:'%s' in ThreadList ", f.getFid(), localCopyFence.getCname()));
              if (processed.get(f.getFid()) != null) {
                Log.e(TAG, String.format(Locale.getDefault(), "resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: MULTIPLE REFERENCES OF id:'%d' when processing threadId:'%d' ", f.getFid(), rec.getThreadId()));
                cursor.moveToNext();
                continue;
              }

              //success case
              processed.put(f.getFid(), f);
              missing.remove(f.getFid());//it might have been added when fences did not match, id-wise
            } else {
              Log.e(TAG, String.format(Locale.getDefault(), "resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in ThreadRecord threadId:'%d', but HAS NO CORRESPONDING GROUP RECORD", rec.getUfsrvFid(), rec.getThreadId()));
              //todo: heal yourself remove threadId from system
            }
            break;//dont traverse the list further
          } else {
            missing.put(f.getFid(), f);
          }

          cursor.moveToNext();
        }
      } else {
        Log.e(TAG, String.format(Locale.getDefault(), "resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in Sever's List, but local ThreadList is empty", f.getFid()));
        missing.put(f.getFid(), f);
      }
    }

    return new Pair(processed, missing);
  }

  static private void
  resolveProcessedServerInvitedFenceList(@NonNull HashMap<Long, FenceDescriptor> processed, @NonNull HashMap<Long, ThreadRecord> fidToThreadRecordMap)
  {
    //NOOP
  }

  /**
   * Process the invited fences which were sent by the server that did not have corresponding reference in the local ThreadDatabase List.
   *
   * @param processed
   * @param missing
   */
  static private void
  resolveMissingServerInvitedFenceList(@NonNull HashMap<Long, FenceDescriptor> processed, @NonNull HashMap<Long, FenceDescriptor> missing)
  {
    if (missing.size() > 0) {
      Log.d(TAG, String.format(Locale.getDefault(), "resolveMissingServerInvitedFenceList:  PROCESSing %d fences that were not referenced in ThreadList...", missing.size()));
      Set<Long>       keySet          = missing.keySet();
      Iterator<Long>  keySetIterator  = keySet.iterator();

      while (keySetIterator.hasNext()) {
        Long fid = keySetIterator.next();
        Log.d(TAG, String.format(Locale.getDefault(), "resolveMissingServerInvitedFenceList:  PROCESSing missing {fid:'%d', cname:'%s'}...", fid, missing.get(fid).getFcname()));

        resolveMissingServerInvitedFence(missing.get(fid));
        //add it to completed list
        processed.put(fid, missing.get(fid));
      }
    }
  }

  /*
   * This reflects inernal statesync issues: the server indicates we are invited to this group, yet we DON"T HAVE IT on the
    * ThreadList, so we have to resolve its internal state representation, especially with respect to
    * whether a corresponding Group entry exist for it or not.
   */
  static private void resolveMissingServerInvitedFence(FenceDescriptor jsonEntityfenceDescriptor)
  {
    GroupDatabase             groupDatabase =SignalDatabase.groups();
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupRecordByFid(jsonEntityfenceDescriptor.getFid());

    if (groupRecord == null) {
      GroupMasterKey groupMasterKey;
      try {
        groupMasterKey = new GroupMasterKey(Base64.decode(jsonEntityfenceDescriptor.getFenceKey()));
      } catch (IOException | InvalidInputException x) {
        throw new AssertionError("ERROR: BAD GROUP KEY");
      }

      final GroupId    groupId         = GroupId.v2(groupMasterKey);
      try {
        long id = groupDatabase.create(groupId,
                                       groupMasterKey,
                                       jsonEntityfenceDescriptor.getFname(),
                                       new LinkedList<>(),
                                       null,
                                       null,
                                       jsonEntityfenceDescriptor.getFcname(), 0.0, 0.0, GroupDatabase.GROUP_MODE_INVITATION,
                                       jsonEntityfenceDescriptor.getFid(), 0,
                                       GroupDatabase.GroupType.values()[jsonEntityfenceDescriptor.getType()],
                                       GroupDatabase.PrivacyMode.values()[jsonEntityfenceDescriptor.getPrivacy_mode()],
                                       GroupDatabase.DeliveryMode.values()[jsonEntityfenceDescriptor.getDelivery_mode()],
                                       GroupDatabase.JoinMode.values()[jsonEntityfenceDescriptor.getJoin_mode()]);

        if (id != -1) {
          long threadId = sendStateSyncForInvitedGroup(jsonEntityfenceDescriptor);
          SignalDatabase.threads().updateFid(threadId, jsonEntityfenceDescriptor.getFid());
          return;
        }
      } catch (SQLiteConstraintException x) {
        Log.e(TAG, String.format(Locale.getDefault(), "resolveMissingServerInvitedFence {fid:'%d', cname:'%s': !! POSSIBLE UNIQUE CONSTRAINT VIOLATION: INSTRUCTING SERVER TO REMOVE", jsonEntityfenceDescriptor.getFid(), jsonEntityfenceDescriptor.getFcname()));
        sendServerCommandFenceLeaveForRogueFence(jsonEntityfenceDescriptor.getFid());
        return;
      }
    }

    //this is very unlikely, but it means a group already exists that doesnt have the invite mode set for it
    Log.w(TAG, String.format(Locale.getDefault(), "resolveMissingServerInvitedFence {fid:'%d', cname:'%s': INVESTIGATE: ISSUING A INVITE SYNC FOR AN EXISTING GROUP", groupRecord.getFid(), groupRecord.getCname()));
    long threadId = sendStateSyncForInvitedGroup(jsonEntityfenceDescriptor);
    SignalDatabase.threads().updateFid(threadId, jsonEntityfenceDescriptor.getFid());//probably not necessary
  }

  /**
   * Process invited Fences which are internally referenced in ThreadList but the server doesn't appear to know of. Default action remove locally.
   *
   * @param cursor ThreadDatabase sourced result set
   * @param processed List of fences, reflecting server's view obtained via StetSync
   */
  static private void resolveUnreferencedInvitedThreadListEntries(Cursor cursor, @NonNull HashMap<Long, FenceDescriptor> processed)
  {
    GroupDatabase   groupDatabase   =SignalDatabase.groups();
    ThreadDatabase  threadDatabase  = SignalDatabase.threads();

    if (cursor != null && cursor.getCount() > 0) {
      cursor.moveToFirst();

      while (cursor != null &&  !cursor.isAfterLast()) {
        ThreadRecord rec = SignalDatabase.threads().readerFor(cursor).getCurrent();
        GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroupRecordByFid(rec.getUfsrvFid());

        //record not in the processed list
        if (processed.get(rec.getUfsrvFid()) == null) {
          if (groupRecord != null) {
            Log.d(TAG, String.format(Locale.getDefault(), "resolveUnreferencedInvitedThreadListEntries:  DELETING ThreadList Invited Fence {fid:'%d', cname:'%s', threadid:'%d'} that wasn't in StateSync...", rec.getUfsrvFid(), groupRecord.getCname(), rec.getThreadId()));
            groupDatabase.cleanUpGroup(groupRecord.getId(), rec.getThreadId());
          } else {
            //todo: perhaps prompt the user
            Log.e(TAG, String.format(Locale.getDefault(), "resolveUnreferencedInvitedThreadListEntries:  ERROR: DATA INTEGRITY: Fence {fid:'%d', threadid:'%d'} doesn't have a corresponding Group: DELETING...", rec.getUfsrvFid(), rec.getThreadId()));
            SignalDatabase.threads().deleteConversation(rec.getThreadId());
          }
        }

        cursor.moveToNext();
      }
    }
  }

  /*
    * This is ufsrv's protocol semantics comms and doesn't change any model data, except for marking the group inactive
    * chances it was set inactive prior.
   */
  static public void sendServerCommandFenceLeave(long fid)
  {
    Context                   context         = ApplicationContext.getInstance();
    GroupDatabase             groupDatabase   = SignalDatabase.groups();
    ThreadDatabase            threadDatabase  = SignalDatabase.threads();
    GroupDatabase.GroupRecord groupRecord     = groupDatabase.getGroupRecordByFid(fid);
    GroupId                   groupId         = groupRecord.getId();
    Recipient                 groupRecipient  = Recipient.live(fid).get();
    long                      threadId        = threadDatabase.getThreadIdFor(null, fid);
    long                      timeSentInMillis= System.currentTimeMillis();//AA+

    //AA+ adde Fencecommand context
    FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(ApplicationContext.getInstance(),
                                                                     Optional.empty(),
                                                                     Optional.empty(),/*invited*/
                                                                     groupRecord.getTitle(),
                                                                     Optional.empty(),
                                                                     Optional.of(groupRecord.getFid()),
                                                                     Optional.empty(),
                                                                     new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.LEAVE_VALUE, 0),
                                                                     Optional.empty(),
                                                                     Optional.empty(),
                                                                     timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandBuilder = UfsrvCommandWire.newBuilder()
                                                                   .setFenceCommand(fenceCommand)
                                                                   .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //
    GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                .setType(GroupContext.Type.QUIT)
                                                                .setFenceMessage(fenceCommand)
                                                                .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
    MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, true);

    SignalDatabase.groups().markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED);

  }

  static public void sendServerCommandFenceLeaveForRogueFence(long fid)
  {
    long                        timeSentInMillis= System.currentTimeMillis();
    Context                     context         = ApplicationDependencies.getApplication();
    SignalServiceMessageSender  messageSender   = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAddress        remoteAddress   = new SignalServiceAddress(SignalStore.account().getAci(), UfsrvUserUtils.myOwnAddress().serialize());

   FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(context,
                                                                    Optional.empty(),
                                                                    Optional.empty(),
                                                                    "groupRecord.getTitle()",
                                                                    Optional.empty(), Optional.of(fid),
                                                                    Optional.empty(),
                                                                    new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.LEAVE_VALUE, 0),
                                                                    Optional.empty(),
                                                                    Optional.empty(),
                                                                    timeSentInMillis);

    UfsrvCommand ufCommand = new UfsrvCommand(fenceCommand, false, UfsrvCommand.TransportType.LOCAL_PIPE, false);

    SignalServiceProtos.DataMessage.Builder builder = SignalServiceProtos.DataMessage.newBuilder();
    builder.setUfsrvCommand(ufCommand.buildIfNecessary());
    byte[] content = builder.build().toByteArray();

    try {
      messageSender.sendUfsrvMessage(remoteAddress, Optional.empty(), ufCommand.getFence().getHeader().getWhen(), content, ufCommand);
    } catch (IOException | UntrustedIdentityException x) {
      Log.e(TAG, x.getMessage());
    }
  }

  /**
   *  Marshal a request to leave one or more fences based on threadIds set.
   *  IMPORTANT: This DOES NOT change the model (other than chnage group mode) and issues the protocol semantics needed to change the server's view.
   *  It does not delete the thread, or the group itself. Upon confirmation from server group  will be marked inactive only marks the group as inactive and this user
   *  removed the local user from its member list.
   *
   * @param context
   * @param threadIds
   */
  static public void sendServerCommandFenceThreadDeleted(Context context, Set<Long> threadIds)
  {
    for (long threadId : threadIds) {
      Optional<GroupDatabase.GroupRecord> groupRecordOptional = SignalDatabase.groups().getGroupRecordfromThreadId(threadId);
      if (groupRecordOptional.isEmpty()) {
        continue;
      }

      GroupDatabase.GroupRecord groupRecord = groupRecordOptional.get();
      GroupId   groupId           = groupRecord.getId();
      Recipient groupRecipient    = Recipient.live(groupRecord.getRecipientId()).get();
      long      timeSentInMillis  = System.currentTimeMillis();//AA+

      FenceCommand fenceCommand = MessageSender.buildFenceCommandFinal(ApplicationContext.getInstance(),
                                                                       Optional.empty(),
                                                                       Optional.empty(),//invited
                                                                       groupRecord.getTitle(),
                                                                       Optional.empty(), Optional.of(groupRecord.getFid()),
                                                                       Optional.empty(),
                                                                       new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.LEAVE_VALUE, 0),
                                                                       Optional.empty(),
                                                                       Optional.empty(),
                                                                       timeSentInMillis);

      UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
                                                                    .setFenceCommand(fenceCommand)
                                                                    .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

      SignalServiceProtos.GroupContext groupContext = GroupContext.newBuilder()
                                                                  .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                  .setType(SignalServiceProtos.GroupContext.Type.QUIT)
                                                                  .setFenceMessage(fenceCommand)
                                                                  .build();

      OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
      MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

      SignalDatabase.groups().markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED);
    }
  }

  static public FenceRecord.Permission getFenceCommandPermission(FenceRecord fenceRecord, FenceRecord.Permission.Type permissionType)
  {
    switch (permissionType)
    {
      case PRESENTATION:
        return fenceRecord.getPresentation();
      case MEMBERSHIP:
        return fenceRecord.getMembership();
      case MESSAGING:
        fenceRecord.getMessaging();
      case ATTACHING:
        fenceRecord.getAttaching();
      case CALLING:
        fenceRecord.getCalling();
    }

    return null;
  }

  static public long sendFencePermissionCommand(Context context, Recipient recipientsGroup, long fid, long userid, FencePermissions fencePermission, int commandArg)
  {
    long      timeSentInMillis      = System.currentTimeMillis();

    FenceCommand fenceCommand = MessageSender.buildFenceCommandPermissionFinal(new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.PERMISSION_VALUE, commandArg),
                                                                               Recipient.live(RecipientUfsrvId.from(userid)).get(),
                                                                               fid,
                                                                               fencePermission,
                                                                               timeSentInMillis);

    UfsrvCommandWire.Builder ufsrvCommandWireBuilder = UfsrvCommandWire.newBuilder()
                                                                       .setFenceCommand(fenceCommand)
                                                                       .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //

    GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                           .setId(ByteString.copyFrom(recipientsGroup.getGroupId().get().getDecodedId()))
                                                           .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                           .setFenceMessage(fenceCommand);
    GroupContext groupContext = groupContextBuilder.build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipientsGroup, groupContext, null, timeSentInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

    long threadId = MessageSender.send(context, outgoingMessage, -1, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return threadId;
  }

  static public long
  deleteInvitationFor(@NonNull Context context, @NonNull GroupId groupId, @NonNull Collection<Recipient> recipients)
  {
    long timeNowInMillis = System.currentTimeMillis();
    GroupDatabase.GroupRecord groupRec = SignalDatabase.groups().getGroupByGroupId(groupId);
    Recipient groupRecipient = Recipient.resolved(groupRec.getRecipientId());
    long threadId = SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipient);

    FenceCommand.Builder fenceCommandBuilder = MessageSender.buildFenceCommandForType(recipients, groupRec.getFid(), timeNowInMillis, FenceCommand.CommandTypes.INVITE_DELETED, CommandArgs.SET_VALUE);

    UfsrvCommandWire.Builder ufsrvCommandBuilder = UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId.getDecodedId()))
            .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommandBuilder.build())
            .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
    MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);

    return threadId;
  }

  //Determine the target fence from message type
  static public FenceRecord getTargetFence(SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire)
  {
    if (ufsrvCommandWire == null) return null;

    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        if (ufsrvCommandWire.hasFenceCommand() && ufsrvCommandWire.getFenceCommand().getFencesCount() > 0)  return ufsrvCommandWire.getFenceCommand().getFences(0);
      case UFSRV_MESSAGE:
        if (ufsrvCommandWire.hasMsgCommand() && ufsrvCommandWire.getMsgCommand().getFencesCount() > 0) return ufsrvCommandWire.getMsgCommand().getFences(0);
      case UFSRV_USER:
        if (ufsrvCommandWire.hasUserCommand() && ufsrvCommandWire.getUserCommand().getFencesCount() > 0) return ufsrvCommandWire.getUserCommand().getFences(0);
    }
    return null;
  }

  static public long fidFromGroupRecipients(Context context, Recipient recipient)
  {
    long fid = 0;

    if (recipient.isGroup()) {
      GroupId                     groupId         = recipient.getGroupId().get();
      GroupDatabase               groupDatabase   = SignalDatabase.groups();
      GroupDatabase.GroupRecord   record          = groupDatabase.getGroup(groupId).get();

      if (record != null) {
        Log.d (TAG, String.format(Locale.getDefault(), "fidFromGroupRecipients: decodedGroupId:'%s', fid:'%d'", groupId.toString(), record.getFid()));
        fid = record.getFid();
      } else {
        Log.d (TAG, String.format(Locale.getDefault(), "fidFromGroupRecipients: ERROR FETCHING GROUP RECORD FOR: decodedGroupId:'%s', fid:'0'", groupId.toString()));
      }
    }

    return fid;
  }

  static public String updateCnameWithNewFname(long fid, String cnameOld, String fnameOld, String fnameNew)
  {
    String cnameNew = cnameOld;

    try {
      cnameNew = (cnameOld.substring(0, cnameOld.lastIndexOf(':')) + ":" + fnameNew);
      Log.d(TAG, String.format(Locale.getDefault(), "updateCnameWithNewFname: cnameOld:'%s'. cnamenew:'%s'", cnameOld, cnameNew));
    } catch (StringIndexOutOfBoundsException x) {
      Log.w(TAG, String.format(Locale.getDefault(), "updateCnameWithNewFname: cnameOld:'%s'. cnamenew:'%s': original cname is malformatted... rebuilding (not implemented)", cnameOld, cnameNew));
      //todo: fetch cname from server
    }

    return cnameNew;
  }

  static public boolean isAvatarUfsrvIdLoaded(@NonNull String avatarUfsrvId)
  {
    return !TextUtils.isEmpty(avatarUfsrvId) && avatarUfsrvId.length() > 1;
  }

  private static HashMap<Long, ThreadRecord>invitedCursorToFidMap(Cursor cursor)
  {
    HashMap fidToThreadRecord = new HashMap();
    cursor.moveToFirst();

    while (cursor != null && !cursor.isAfterLast()) {
      ThreadRecord rec = SignalDatabase.threads().getThreadRecord (cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID)));
      fidToThreadRecord.put(cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_FID)), rec);
      cursor.moveToNext();
    }

    return fidToThreadRecord;
  }

  static void updateAndDownloadAvatarForUser(@NonNull Context context, Recipient recipient, SignalServiceProtos.UserRecord userRecord)
  {
    SignalDatabase.recipients().setProfileSharing(recipient, true);
    try {
      SignalDatabase.recipients().setProfileKey(recipient.getId(), new ProfileKey(userRecord.getProfileKey().toByteArray()));
    } catch (InvalidInputException x) {
      Log.d(TAG, x);
      return;
    }

    if (!TextUtils.isEmpty(userRecord.getAvatar().getId())) {
      if (!TextUtils.equals(userRecord.getAvatar().getId(), recipient.getAvatarUfsrvId()) || !AvatarHelper.avatarExists(context, recipient.getId())) {
        SignalDatabase.recipients().setAvatarUfId(recipient, userRecord.getAvatar().getId());
        ApplicationDependencies.getJobManager().add(new ProfileAvatarDownloadJob(recipient.getUfsrvUid(), userRecord.getAvatar().getId(), userRecord.getAvatar().getKey().toByteArray()));
      }
      else {
        Log.d(TAG, String.format(Locale.getDefault(), "updateAndDownloadAvatarForUser (%d, uid:'%d', nick:'%s'): NOT UPDATING AVATAR: IDENTICAL", Thread.currentThread().getId(), recipient.getUfsrvId(), userRecord.getAvatar().getId()));
      }
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "updateAndDownloadAvatarForUser (uid:'%d'): NO avatar ufid defined", recipient.getUfsrvId()));
    }
  }

  static void updateEidForUserIfNecessary(Recipient recipient, long eidProvided)
  {
    if (eidProvided != recipient.getEid()) {
      Log.d(TAG, String.format(Locale.getDefault(), "updateEidForUserIfNecessary (eidProvided:'%d', current:'%d'): Updating user '%s'", eidProvided, recipient.getEid(), recipient.getUfsrvUid()));
      ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(recipient, false, false));
    }

  }

  public static void updateEidForFenceMembers(@NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    Recipient recipient;

    if (excludedUsers.isPresent()) {
      String ufsrvUidEncoded;

      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUidEncoded = UfsrvUid.EncodedfromSerialisedBytes(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUidEncoded)) {
          recipient = Recipient.live(new UfsrvUid(record.getUfsrvuid().toByteArray()).toString()).get();
          updateEidForUserIfNecessary(recipient, record.getEid());
        }
        else {
          Log.d(TAG, String.format(Locale.getDefault(), "updateEidForFenceMembers: Found excluded member :'%s'", ufsrvUidEncoded));
        }
      }
    } else {
        for (SignalServiceProtos.UserRecord record : userRecords) {
          if (record.hasProfileKey()) {
            recipient = Recipient.live(new UfsrvUid(record.getUfsrvuid().toByteArray()).toString()).get();
            updateEidForUserIfNecessary(recipient, record.getEid());
          }
        }
    }
  }

  public static void updateProfileKeyForFenceMembers(@NonNull Context context, @NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    Recipient recipient;

    if (excludedUsers.isPresent()) {
      String ufsrvUid;

      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = UfsrvUid.EncodedfromSerialisedBytes(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid) && record.hasProfileKey()) {
          recipient = Recipient.live(new UfsrvUid(record.getUfsrvuid().toByteArray()).toString()).get();
          updateAndDownloadAvatarForUser(context, recipient, record);
        }
        else {
          Log.d(TAG, String.format(Locale.getDefault(), "UserRecordsToNumbersList: Found excluded member :'%s'", ufsrvUid));
        }
      }
    }
    else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        if (record.hasProfileKey()) {
          recipient = Recipient.live(new UfsrvUid(record.getUfsrvuid().toByteArray()).toString()).get();
          updateAndDownloadAvatarForUser(context, recipient, record);
        }
      }
    }
  }

  //build a generic fence command header
  public static FenceCommand.Builder
  buildFenceCommandHeader(long fid, long timeNowInMillis, CommandArgs commandArg, FenceCommand.CommandTypes commandType)
  {
    FenceCommand.Builder                      fenceCommandBuilder   = FenceCommand.newBuilder();
    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder            = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);

    //build/link header
    commandHeaderBuilder.setArgs(commandArg.getNumber());
    commandHeaderBuilder.setCommand(commandType.getNumber());

    commandHeaderBuilder.setWhen(timeNowInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  //fence user preferences
  public static void
  updateFencePreferences(@NonNull Context context, @NonNull GroupDatabase.GroupRecord groupRecord, @NonNull FenceRecord fenceRecord)
  {
    if (fenceRecord.getFencePreferencesList().isEmpty())  return;

    RecipientDatabase preferenceDatabase  = SignalDatabase.recipients();
    Recipient         recipient           =  Recipient.live(fenceRecord.getFid()).get();

    for (FenceUserPreference userPreference : fenceRecord.getFencePreferencesList()) {
      switch (userPreference.getPrefId()) {
        case PROFILE_SHARING:
          if (userPreference.hasValuesInt()) preferenceDatabase.setProfileSharing(recipient, userPreference.getValuesInt() == 0? false:true);
          break;
        case STICKY_GEOGROUP:
          if (userPreference.hasValuesInt()) {
            RecipientDatabase.GeogroupStickyState stickyState = RecipientDatabase.GeogroupStickyState.values()[(int) userPreference.getValuesInt()];
            preferenceDatabase.setGeogroupSticky(recipient, stickyState);
          }
          break;
        default:
          Log.e(TAG, String.format(Locale.getDefault(), "updateFencePreferences (fid:'%d', prefId:'%d'): Uknown FenceUserPreference id", fenceRecord.getFid(), userPreference.getPrefId()));
      }

    }
  }

  public static String extractFenceName(String fcname)
  {
    return  fcname.substring(fcname.lastIndexOf(":") + 1);
  }

  //TBD
  public static Optional<GroupManager.GroupActionResult> createPairedGroup(@NonNull Context context, Recipient recipient)
  {
    GroupManager.GroupActionResult result;
    Set<Recipient> members = new HashSet<>();
    members.add(recipient);
    result = GroupManager.createPairedGroup(context,
                                            members,
                                            null,
                                            false,
                                            ufLocation.getInstance().getBaseLocationPrefix(),
                                            0.0, 0.0);

    return Optional.ofNullable(result);
  }

  public static List<Long> getPairedGroups(@NonNull Recipient recipient, @NonNull Recipient recipientOther) {
    return SignalDatabase.groups().getPairedGroups(recipient, recipientOther);
  }

  public static Pair<Long, Recipient> GetThreadIdPairedGroup(@NonNull Recipient recipient, @NonNull Recipient recipientOther)
  {
    Context context = ApplicationContext.getInstance();
    List<Long> privateGroup = UfsrvFenceUtils.getPairedGroups(recipient, recipientOther);
    if (privateGroup.size() > 0) {
      Recipient recipientGroup = Recipient.live(privateGroup.get(0)).get();
      long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());

      return new Pair<>(threadId, recipientGroup);
    }

    return new Pair<>(Long.valueOf(-1), null);
  }

  /**
   *
   * @param context must be Activity
   * @param threadId
   * @param recipient
   * @param distributionType
   * @param lastSeen
   * @param startingPosition
   */
  public static void openConversation(@NonNull Context context, long threadId, @NonNull Recipient recipient, int distributionType, Optional<Long> lastSeen, Optional<Integer> startingPosition, long fid)
  {
    //AA+
    if (SignalDatabase.groups().isGroupInvitationPending(fid)) {
      //check the interface callback for when a dialog button is pressed
      InvitedToGroupDialog invitedToGroupDialog = new InvitedToGroupDialog(context, recipient, null, threadId);
      invitedToGroupDialog.display();

      if (invitedToGroupDialog.isJoinAccepted()) {
        //switch to threadid as per rge code conversationListFragment below
      } else {
        //nothing
        Log.d(TAG, String.format(Locale.getDefault(), "onCreateConversation: thread '%d' invitation to joing was not accepted yet", threadId));
        return;
      }
    }
    //
    int startPosition = startingPosition.isPresent()? startingPosition.get().intValue() : -1;
    Intent intent = ConversationIntents.createBuilder(context, recipient.getId(), threadId)
                                       .withDistributionType(distributionType)
                                       .withStartingPosition(startPosition)
                                       .withFid(fid)
                                       .build();
    context.startActivity(intent);
    ((Activity)context).overridePendingTransition(R.anim.slide_from_end, R.anim.fade_scale_out);
  }

  /**
   * @deprecated use {@link #recipientFromUserRecord(SignalServiceProtos.UserRecord)}
   */
  static public Recipient recipientFromFenceCommandOriginator(FenceCommand fenceCommand, boolean async)
  {
    return Recipient.live(new UfsrvUid(fenceCommand.getOriginator().getUfsrvuid().toByteArray()).toString()).get();
  }


  /**
   * Generalised implementation of recipientFromFenceCommandOriginator
   */
  static public Recipient recipientFromUserRecord(@Nullable SignalServiceProtos.UserRecord userRecord)
  {
    if (userRecord != null)
      return Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(userRecord.getUfsrvuid().toByteArray())).serialize()).get();

    return Recipient.UNKNOWN;
  }

  static public Recipient recipientFromUfsrvId(byte[] rawUfrsvId)
  {
    if (rawUfrsvId != null)
      return Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(rawUfrsvId)).serialize()).get();

    return Recipient.UNKNOWN;
  }
}
