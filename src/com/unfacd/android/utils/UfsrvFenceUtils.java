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
import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.data.json.JsonEntityFence;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.InvitedToGroupDialog;

import org.thoughtcrime.securesms.conversation.ConversationActivity;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceUserPreference;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;


public class UfsrvFenceUtils
{
  private static final String TAG = UfsrvFenceUtils.class.getSimpleName();


  /**
   * Invitations arriving from the server end have the command set as JOIN and Args as INVITED
   * @param fenceCommand the command context identifying current state of the fence.
   * @return
   */
  static public boolean isFenceCommandJoinInvitation (FenceCommand fenceCommand)
  {
    if (fenceCommand!=null &&  fenceCommand.getFencesCount()>0) {
      if (fenceCommand.getHeader().getCommand()== FenceCommand.CommandTypes.JOIN_VALUE &&
              ( fenceCommand.getHeader().getArgs()==CommandArgs.INVITED_VALUE||
                fenceCommand.getHeader().getArgs()==CommandArgs.INVITED_GEO_VALUE)) return true;
    }

    return false;
  }


  static public boolean isFenceCommandJoinInvitationGeo (FenceCommand fenceCommand)
  {
    if (fenceCommand!=null &&  fenceCommand.getFencesCount()>0) {
      if (fenceCommand.getHeader().getCommand() ==FenceCommand.CommandTypes.JOIN_VALUE &&
          fenceCommand.getHeader().getArgs()    ==CommandArgs.INVITED_GEO_VALUE)
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
  static public void sendStateSyncJoin (long fid, String fname, String fcname,
                                        GroupDatabase.GroupType groupType,
                                        GroupDatabase.PrivacyMode privacyMode,
                                        GroupDatabase.DeliveryMode deliveryMode,
                                        GroupDatabase.JoinMode joinMode)
  {
    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    final byte[]    groupId         = groupDatabase.allocateGroupId();

    long id=groupDatabase.create(GroupUtil.getEncodedId(groupId, false), fname, new LinkedList<>(), null, null, fcname, 0.0, 0.0, GroupDatabase.GROUP_MODE_JOIN_SYNCED, fid, 0, groupType, privacyMode, deliveryMode, joinMode);

    if (id!=-1) sendStateSyncJoinForPartiallyExistingGroup(groupId, fid);

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
  static private void sendStateSyncJoinForPartiallyExistingGroup (byte[] groupId, long fid)
  {
    long timeNowInMillis  = System.currentTimeMillis();

    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    Recipient      groupRecipient  = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(GroupUtil.getEncodedId(groupId, false)), false);

    SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), fid, timeNowInMillis, true, true);
    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId))
            .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommandBuilder.build())//
            .build();

    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandBuilder.build());

    //NOTE -1 will create a threadid
    MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null);

    groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_JOIN_SYNCED);
  }


  //This is a stateSync join request that doesn't change the exiting model data (we are not creating group or threadid as they already exist) we are sending
  //a JOIN request to sync the SERVER"S side JOIN/SYNCED
  static private void sendStateSyncJoinForExistingGroup (long fid)
  {
    long timeNowInMillis                = System.currentTimeMillis();
    GroupDatabase   groupDatabase       = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord groupRec  = groupDatabase.getGroupRecordByFid(fid);
    String groupIdEncoded               = groupRec.getEncodedId();
    Recipient      groupRecipient       = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(groupIdEncoded), false);

    FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), fid, timeNowInMillis, true, true);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupRec.getId()))
            .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommandBuilder.build())//
            .build();

    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandBuilder.build());

    //NOTE -1 will create a threadid
    MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null);
  }


  /**
   * Marshal a request to state-sync existing fence view.
   * Command: STATE
   * Arg:SYNCED
   *
   * @param fid
   */
  static public long sendStateSyncForGroup (long fid)
  {
    long                      timeNowInMillis = System.currentTimeMillis();
    ThreadDatabase            threadDatabase  = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());
    GroupDatabase             groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord groupRec        = groupDatabase.getGroupRecordByFid(fid);

    FenceCommand.Builder fenceCommandBuilder= MessageSender.buildFenceCommandStateSyncedResponse(ApplicationContext.getInstance(), groupRec.getFid(), timeNowInMillis, true, false);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext groupContext = GroupContext.newBuilder()
                                  .setId(ByteString.copyFrom(groupRec.getId()))
                                  .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                  .setFenceMessage(fenceCommandBuilder.build())//
                                  .build();

    Recipient      groupRecipient  = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(GroupUtil.getEncodedId(groupRec.getId(), false)), false);
    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, 0,null, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandBuilder.build());

    //NOTE: getThreadIdFor() DOES NOT create a new threadId where one does not exist against fid
    long threadId=threadDatabase.getThreadIdFor(groupRecipient, fid);

    return MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, threadId, false, null);
  }


  /**
   * Marshal a request to sync an invited group (essentially requiting resend of invite message). Group/thread must be setup already
   * Command: STATE
   */
  static public long sendStateSyncForInvitedGroup (JsonEntityFence jsonEntityfence)
  {
    long                      timeNowInMillis = System.currentTimeMillis();
    ThreadDatabase            threadDatabase  = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());
    GroupDatabase             groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord groupRec        = groupDatabase.getGroupRecordByFid(jsonEntityfence.getFid());

    FenceCommand.Builder fenceCommandBuilder= buildFenceCommandHeader(groupRec.getFid(), timeNowInMillis, CommandArgs.SYNCED, FenceCommand.CommandTypes.INVITE);
    UfsrvCommandWire.Builder ufsrvCommandBuilder= UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommandBuilder.build())
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);

    GroupContext groupContext = GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupRec.getId()))
            .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
            .setFenceMessage(fenceCommandBuilder.build())//
            .build();

    Recipient      groupRecipient  = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(GroupUtil.getEncodedId(groupRec.getId(), false)), false);
    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
                                                                              ufsrvCommandBuilder.build());

    //NOTE: getThreadIdFor() DOES NOT create a new threadId where one does not exist against fid
//    long threadId=threadDatabase.getThreadIdFor(groupRecipient, jsonEntityfence.getFid());

    return MessageSender.send(ApplicationContext.getInstance(), outgoingMessage, -1, false, null);
  }

  /**
   * Resolve the FencesList provided by the server in its StateSync message. Epic.
   * @param fences Json list
   */
  static public void synchroniseFenceList (List<JsonEntityFence> fences)
  {
    if (fences == null)  fences=new LinkedList<>();

    Cursor                          cursor,
                                    cursorInvited;
    HashMap<Long, JsonEntityFence>  missing;
    HashMap<Long, JsonEntityFence>  processed;
    Pair<HashMap<Long, JsonEntityFence>, HashMap<Long, JsonEntityFence>>           done          = null;

    cursor=DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getConversationList();
    cursorInvited=DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getInvitedConversationList();
    HashMap<Long, ThreadRecord> fidToInvitedMap=invitedCursorToFidMap(cursorInvited);

    Log.d(TAG, String.format("synchroniseFenceList: Processing Internal ThreadList size:%d'. Internal InvitedList: '%d', FenceList size: '%d'.", cursor.getCount(), cursorInvited.getCount(), fences.size()));

    try {
      done=resolveServerFenceList(cursor, fences);
      processed = done.first;
      missing   = done.second;

      Log.d(TAG, String.format("synchroniseFenceList:  FOUND %d locally referenced fences. Found: '%d' StateSyenc fences that were NOT referenced in ThreadList...", processed.size(), missing.size()));

      resolveProcessedServerFenceList (processed, fidToInvitedMap);
      resolveMissingServerFenceList (processed, missing);
      resolveUnreferencedThreadListEntries (cursor, processed);
    } finally {
      if (cursor != null)       cursor.close();
      if (cursorInvited!=null)  cursorInvited.close();
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
  static private Pair<HashMap<Long, JsonEntityFence>, HashMap<Long, JsonEntityFence>>
  resolveServerFenceList(Cursor cursor, List<JsonEntityFence> fences)
  {
    final GroupDatabase             groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    HashMap<Long, JsonEntityFence>  missing       = new HashMap<>();
    HashMap<Long, JsonEntityFence>  processed     = new HashMap<>();

    for (JsonEntityFence f : fences) {
      Log.d(TAG, String.format("ResolveServerFenceList: Server View -> FenceList: CLIENT IS MEMBER OF '%s'. fid:'%d'", f.getFcname(), f.getFid()));

      //check presence of fence in our internal records
      if (cursor != null && cursor.getCount() > 0) {
        cursor.moveToFirst();

        while (cursor != null && !cursor.isAfterLast()) {
          ThreadRecord rec = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).readerFor(cursor).getCurrent();

          if (f.getFid()==rec.getFid()) {
            GroupDatabase.GroupRecord localCopyFence = groupDatabase.getGroupRecordByFid(f.getFid());

            if (localCopyFence != null) {
              Log.d(TAG, String.format("ResolveServerFenceList:  Client View -> FOUND reference for FENCE ID: '%d' fcname:'%s' in ThreadList: requesting statesync ", f.getFid(), localCopyFence.getCname()));
              if (processed.get(f.getFid())!=null) {
                Log.d(TAG, String.format("ResolveServerFenceList:  ERROR: DATA INTEGRITY: MULTIPLE REFERENCES OF id:'%d' when processing threadId:'%d' ", f.getFid(), rec.getThreadId()));
                cursor.moveToNext();
                continue;
              }

              //success case
              processed.put(f.getFid(), f);
              missing.remove(f.getFid());//it might have been added when fences did not match, id-wise
            } else {
              Log.e(TAG, String.format("ResolveServerFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in ThreadRecord threadId:'%d', but HAS NO CORRESPONDING GROUP RECORD", rec.getFid(), rec.getThreadId()));
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
  resolveProcessedServerFenceList (@NonNull HashMap<Long, JsonEntityFence> processed, @NonNull HashMap<Long, ThreadRecord> fidToThreadRecordMap)
  {
    final GroupDatabase         groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    final ThreadDatabase        threadDatabase  = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord   groupRecord     = null;

    if (processed.size()>0) {
      Log.d(TAG, String.format("resolveProcessedServerFenceList:  PROCESSing %d fences that were referenced in ThreadList...", processed.size()));

      ThreadRecord threadRecordInvited;
      Set<Long> keySet = processed.keySet();
      Iterator<Long> keySetIterator = keySet.iterator();
      while (keySetIterator.hasNext()) {
        Long fid = keySetIterator.next();
        Long eid = threadDatabase.getEidForFid(fid);

        if ((threadRecordInvited=fidToThreadRecordMap.get(Long.valueOf(fid)))!=null) {
          groupRecord=groupDatabase.getGroupRecordByFid(fid);
          Log.e(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', mode:'%d'): FOUND INVITED FENCE IN LIST: SHIFTING TO OPEN...", fid, groupRecord.getMode(), groupRecord.getCname()));

          //reassign group mode to indicate open/active
          if      (groupRecord.getMode()==GroupDatabase.GROUP_MODE_INVITATION)        groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED);
          else if (groupRecord.getMode()==GroupDatabase.GROUP_MODE_GEOBASED_INVITE)   groupDatabase.markGroupMode(fid, GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED);//we may assign diferent mode for geo based invites
          fidToThreadRecordMap.remove(Long.valueOf(fid));
          //continue through
        }

        if (groupRecord==null) groupRecord = groupDatabase.getGroupRecordByFid(fid);

        if (processed.get(fid).getEid()>eid) {
          Log.d(TAG, String.format("resolveProcessedServerFenceList (internal_eid:'%d', server_eid:'%d'):  PROCESSing fid:'%d', cname:'%s. Active status: '%b'...", eid, processed.get(fid).getEid(), fid, groupRecord.getCname(), groupRecord.isActive()));

          if (!groupRecord.isActive()) groupDatabase.setActive(groupRecord.getEncodedId(), true);

          sendStateSyncForGroup(fid);
        } else  Log.d(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', internal_eid:'%d', server_eid:'%d':  NOT PROCESSing EID uptodate ", fid, eid, processed.get(fid).getEid()));

        //self-healing...
        if (groupRecord.getMode()<0) {
          Log.e(TAG, String.format("resolveProcessedServerFenceList (fid:'%d', GroupDatabase.cname:'%s'): ERROR: GROUP MODE HAD INVALID VALUE: Reassigning mode to default JOIN_ACCEPTED (it could have been INVITED)", groupRecord.getFid(), groupRecord.getCname()));
          groupDatabase.markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_JOIN_ACCEPTED);
        }

        groupRecord=null;
      }
    }
  }


  /**
   * Process the fences which were sent by the server that did not have corresponding reference in the local ThreadDatabase List.
   *
   * @param processed
   * @param missing
   */
  static private void resolveMissingServerFenceList (@NonNull HashMap<Long, JsonEntityFence> processed, @NonNull HashMap<Long, JsonEntityFence> missing)
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
          resolveMissingServerFence (missing.get(fid));
        }
        //add it to completed list
        processed.put(fid, missing.get(fid));
      }
    }
  }


  /*
   * This reflects inernal statesync issues: the server thinks we are member of this fence,but we DON"T HAVE IT on the
    * ThreadList, so we have to resolve its internal state representation, especially with respect to
    * whether a corresponding Group entry exist for it or not.
   * and handle accordingly
   */
  static private void resolveMissingServerFence (JsonEntityFence jsonEntityfence)
  {
    GroupDatabase             groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupRecordByFid(jsonEntityfence.getFid());

    if (groupRecord != null) {
      if (!groupDatabase.isActive(groupRecord.getEncodedId())) {
        Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') FOUND INACTIVE GROUP: INSTRUCTING SERVER TO DELETE", jsonEntityfence.getFid(), jsonEntityfence.getFcname()));
        sendServerCommandFenceLeave (groupRecord.getFid());
      } else {
        Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') FOUND ACTIVE GROUP: SENDING A JOIN REQUEST FOR RESYNC", jsonEntityfence.getFid(), jsonEntityfence.getFcname()));
        sendStateSyncJoinForPartiallyExistingGroup(groupRecord.getId(), jsonEntityfence.getFid());
      }
    } else if (jsonEntityfence.getFid()>0) {
      Log.w(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: SENDING A JOIN REQUEST FOR RESYNC", jsonEntityfence.getFid(), jsonEntityfence.getFcname()));

      groupDatabase.isGroupAvailableByCname (jsonEntityfence.getFcname(), true);//nuke stale group record if already exists

      if (!TextUtils.isEmpty(jsonEntityfence.getFname()) && !TextUtils.isEmpty(jsonEntityfence.getFcname())) {
        sendStateSyncJoin(jsonEntityfence.getFid(), jsonEntityfence.getFname(),
                          jsonEntityfence.getFcname(),
                          GroupDatabase.GroupType.values()[jsonEntityfence.getType()],
                          GroupDatabase.PrivacyMode.values()[jsonEntityfence.getPrivacy_mode()],
                          GroupDatabase.DeliveryMode.values()[jsonEntityfence.getDelivery_mode()],
                          GroupDatabase.JoinMode.values()[jsonEntityfence.getJoin_mode()]);
      } else {
        Log.e(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: NO VALID JSONENTITY CNAME AND FNAME PRESENT: ASKING SERVER TO DELETE", jsonEntityfence.getFid(), jsonEntityfence.getFcname()));
        sendServerCommandFenceLeave (jsonEntityfence.getFid());
      }
    } else {
      Log.e(TAG, String.format("resolveMissingServerFence (json.fid:'%d', json.cname'%s') GROUP HAS NO INTERNAL RECORD: NO VALID JSONENTITY DATA: ABORTING", jsonEntityfence.getFid(), jsonEntityfence.getFcname()));
    }

  }


  /**
   * Process Fences which are internally referenced in ThreadList but the server doesn't appear to know of. Default action is to sync join from client side, unless the
   * thread belongs to inactive group
   *
   * @param cursor ThreadDatabase sourced result set
   * @param processed List of fences, reflecting server's view obtained via StetSync
   */
  static private void resolveUnreferencedThreadListEntries (Cursor cursor, @NonNull HashMap<Long, JsonEntityFence> processed)
  {
    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    ThreadDatabase  threadDatabase  = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());

    if (cursor != null && cursor.getCount() > 0) {
      cursor.moveToFirst();

      while (cursor != null &&  !cursor.isAfterLast()) {
        ThreadRecord rec = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).readerFor(cursor).getCurrent();
        GroupDatabase.GroupRecord groupRecord=groupDatabase.getGroupRecordByFid(rec.getFid());

        //record not in the processed list
        if (processed.get(rec.getFid())==null) {
          if (groupRecord!=null) {
            boolean isGroupActive=groupDatabase.isActive(groupRecord.getEncodedId());
            Log.d(TAG, String.format("resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync...", rec.getFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
            if (!groupDatabase.isActive(groupRecord.getEncodedId())) {
              Log.w(TAG, String.format("resolveUnreferencedThreadListEntries:  FOUND INACTIVE Fence {fid:'%d', cname:'%s' threadid:'%d'}: (NOT)INSTRUCTING SERVER TO DELETE...", rec.getFid(), groupRecord.getCname(), rec.getThreadId()));
              //sendServerCommandFenceLeave (masterSecret, groupRecord.getFid());//this would be confusing
              //fallthrough below and advance cursor
            } else if (threadDatabase.isThreadForJoinInvitation(rec.getThreadId())) {
              Log.w(TAG, String.format("resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Invitation Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync...", rec.getFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
            } else {
              //fence in threadlist, not invitation, has corresponsing group.. process based on active status
              if (isGroupActive) {
                Log.w(TAG, String.format("resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Server-absent Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync: ACTIVE GROUP: SEND JOIN/SYNCED REQUEST...", rec.getFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
                sendStateSyncJoinForExistingGroup(rec.getFid());
              } else {
                Log.e(TAG, String.format("resolveUnreferencedThreadListEntries:  PROCESSing ThreadList Server-absent Fence {fid:'%d', cname:'%s', threadid:'%d', active:'%b'} that wasn't in StateSync: GROUP NOT ACTIVE: DELETING THRED...", rec.getFid(), groupRecord.getCname(), rec.getThreadId(), isGroupActive));
                DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).deleteConversation(rec.getThreadId());
              }
            }
          } else {
            //todo: perhaps prompt the user
            Log.e(TAG, String.format("resolveUnreferencedThreadListEntries:  ERROR: DATA INTEGRITY: Fence {fid:'%d', threadid:'%d'} doesn't have a corresponding Group: DELETING...", rec.getFid(), rec.getThreadId()));
            DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).deleteConversation(rec.getThreadId());
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
  static public void synchroniseInvitedFenceList (List<JsonEntityFence> fences)
  {
    if (fences==null)  fences=new LinkedList<>();

    Cursor                          cursor,
    cursorInvited;
    HashMap<Long, JsonEntityFence>  missing;
    HashMap<Long, JsonEntityFence>  processed;
    Pair<HashMap<Long, JsonEntityFence>, HashMap<Long, JsonEntityFence>>           done;

    cursorInvited=DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getInvitedConversationList();
    HashMap<Long, ThreadRecord> fidToInvitedMap=invitedCursorToFidMap(cursorInvited);

    Log.d(TAG, String.format("synchroniseInvitedFenceList: Processing Internal InvitedList: '%d', FenceList size: '%d'.", cursorInvited.getCount(), fences.size()));

    try {
      done=resolveServerInvitedFenceList(cursorInvited, fences);
      processed = done.first;
      missing   = done.second;

      Log.d(TAG, String.format("synchroniseInvitedFenceList:  FOUND %d locally referenced invited fences. Found: '%d' StateSyenc invited fences that were NOT referenced in ThreadList...", processed.size(), missing.size()));

      resolveProcessedServerInvitedFenceList (processed, fidToInvitedMap);
      resolveMissingServerInvitedFenceList (processed, missing);
      resolveUnreferencedInvitedThreadListEntries (cursorInvited, processed);
    } finally {
      if (cursorInvited!=null)  cursorInvited.close();
    }

  }


  /**
   * Using the provided invited FenceList by the server, mark which entries which have a corresponding match in theinternalThreadsList (processed)
   * Missing list will contain the list of fence entries which are not present in the internal ThreadsList.
   *
   * @param fences List of fences originating from servers StateSync in its original raw json format
   *
   * @return  Pair of HashMaps keyed on fid with values from the Server's FenceList
   */
  static private Pair<HashMap<Long, JsonEntityFence>, HashMap<Long, JsonEntityFence>>
  resolveServerInvitedFenceList(Cursor cursor, List<JsonEntityFence> fences)
  {
    final GroupDatabase             groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    HashMap<Long, JsonEntityFence>  missing       = new HashMap<>();
    HashMap<Long, JsonEntityFence>  processed     = new HashMap<>();

    for (JsonEntityFence f : fences) {
      Log.d(TAG, String.format("resolveServerInvitedFenceList: Server View: FenceList: CLIENT IS INVITED TO '%s'. fid:'%d'", f.getFcname(), f.getFid()));

      //check presence of fence in our internal records
      if (cursor != null && cursor.getCount() > 0) {
        cursor.moveToFirst();

        while (cursor != null && !cursor.isAfterLast()) {
          ThreadRecord rec = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).readerFor(cursor).getCurrent();

          if (f.getFid()==rec.getFid()) {
            GroupDatabase.GroupRecord localCopyFence = groupDatabase.getGroupRecordByFid(f.getFid());

            if (localCopyFence != null) {
              Log.d(TAG, String.format("resolveServerInvitedFenceList:  Client View: FOUND reference for FENCE ID: '%d' fcname:'%s' in ThreadList: requesting statesync ", f.getFid(), localCopyFence.getCname()));
              if (processed.get(f.getFid())!=null) {
                Log.e(TAG, String.format("resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: MULTIPLE REFERENCES OF id:'%d' when processing threadId:'%d' ", f.getFid(), rec.getThreadId()));
                cursor.moveToNext();
                continue;
              }

              //success case
              processed.put(f.getFid(), f);
              missing.remove(f.getFid());//it might have been added when fences did not match, id-wise
            } else {
              Log.e(TAG, String.format("resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in ThreadRecord threadId:'%d', but HAS NO CORRESPONDING GROUP RECORD", rec.getFid(), rec.getThreadId()));
              //todo: heal yourself remove threadId from system
            }
            break;//dont traverse the list further
          } else {
            missing.put(f.getFid(), f);
          }

          cursor.moveToNext();
        }
      } else {
        Log.e(TAG, String.format("resolveServerInvitedFenceList:  ERROR: DATA INTEGRITY: fid:'%d is referenced in Sever's List, but local ThreadList is empty", f.getFid()));
        missing.put(f.getFid(), f);
      }
    }

    return new Pair(processed, missing);
  }


  static private void
  resolveProcessedServerInvitedFenceList (@NonNull HashMap<Long, JsonEntityFence> processed, @NonNull HashMap<Long, ThreadRecord> fidToThreadRecordMap)
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
  resolveMissingServerInvitedFenceList (@NonNull HashMap<Long, JsonEntityFence> processed, @NonNull HashMap<Long, JsonEntityFence> missing)
  {
    if (missing.size() > 0) {
      Log.d(TAG, String.format("resolveMissingServerInvitedFenceList:  PROCESSing %d fences that were not referenced in ThreadList...", missing.size()));
      Set<Long>       keySet          = missing.keySet();
      Iterator<Long>  keySetIterator  = keySet.iterator();

      while (keySetIterator.hasNext()) {
        Long fid = keySetIterator.next();
        Log.d(TAG, String.format("resolveMissingServerInvitedFenceList:  PROCESSing missing {fid:'%d', cname:'%s'}...", fid, missing.get(fid).getFcname()));

        resolveMissingServerInvitedFence (missing.get(fid));
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
  static private void resolveMissingServerInvitedFence (JsonEntityFence jsonEntityfence)
  {
    GroupDatabase             groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    GroupDatabase.GroupRecord groupRecord   = groupDatabase.getGroupRecordByFid(jsonEntityfence.getFid());

    if (groupRecord==null) {
      final byte[]    groupId         = groupDatabase.allocateGroupId();
      String groupIdEncoded           = null;
      groupIdEncoded = GroupUtil.getEncodedId(groupId, false);
      long id=groupDatabase.create(groupIdEncoded, jsonEntityfence.getFname(),
                                   new LinkedList<>(),
                                   null,
                                   null,
                                   jsonEntityfence.getFcname(), 0.0, 0.0, GroupDatabase.GROUP_MODE_INVITATION,
                                   jsonEntityfence.getFid(), 0,
                                   GroupDatabase.GroupType.values()[jsonEntityfence.getType()],
                                   GroupDatabase.PrivacyMode.values()[jsonEntityfence.getPrivacy_mode()],
                                   GroupDatabase.DeliveryMode.values()[jsonEntityfence.getDelivery_mode()],
                                   GroupDatabase.JoinMode.values()[jsonEntityfence.getJoin_mode()]);

      if (id!=-1) {
        long threadId=sendStateSyncForInvitedGroup(jsonEntityfence);
        DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).updateFid(threadId, jsonEntityfence.getFid());
        return;
      }
    }

    //this is very unlikely, but it means a group already exists that doesnt have the invite mode set for it
    Log.w(TAG, String.format("resolveMissingServerInvitedFence {fid:'%d', cname:'%s': INVESTIGATE: ISSUING A INVITE SYNC FOR AN EXISTING GROUP", groupRecord.getFid(), groupRecord.getCname()));
    long threadId=sendStateSyncForInvitedGroup(jsonEntityfence);
    DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).updateFid(threadId, jsonEntityfence.getFid());//probably not necessary
  }


  /**
   * Process invited Fences which are internally referenced in ThreadList but the server doesn't appear to know of. Default action remove locally.
   *
   * @param cursor ThreadDatabase sourced result set
   * @param processed List of fences, reflecting server's view obtained via StetSync
   */
  static private void resolveUnreferencedInvitedThreadListEntries (Cursor cursor, @NonNull HashMap<Long, JsonEntityFence> processed)
  {
    GroupDatabase   groupDatabase   = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    ThreadDatabase  threadDatabase  = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());

    if (cursor != null && cursor.getCount() > 0) {
      cursor.moveToFirst();

      while (cursor != null &&  !cursor.isAfterLast()) {
        ThreadRecord rec = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).readerFor(cursor).getCurrent();
        GroupDatabase.GroupRecord groupRecord=groupDatabase.getGroupRecordByFid(rec.getFid());

        //record not in the processed list
        if (processed.get(rec.getFid())==null) {
          if (groupRecord!=null) {
            Log.d(TAG, String.format("resolveUnreferencedInvitedThreadListEntries:  DELETING ThreadList Invited Fence {fid:'%d', cname:'%s', threadid:'%d'} that wasn't in StateSync...", rec.getFid(), groupRecord.getCname(), rec.getThreadId()));
            groupDatabase.cleanUpGroup(GroupUtil.getEncodedId(groupRecord.getId(), false), rec.getThreadId());
          } else {
            //todo: perhaps prompt the user
            Log.e(TAG, String.format("resolveUnreferencedInvitedThreadListEntries:  ERROR: DATA INTEGRITY: Fence {fid:'%d', threadid:'%d'} doesn't have a corresponding Group: DELETING...", rec.getFid(), rec.getThreadId()));
            DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).deleteConversation(rec.getThreadId());
          }
        }

        cursor.moveToNext();
      }
    }
  }


  /*
    * This is pur protocol semantics comms and doesn't change any model data, except for markeing the group inactive
    * chances it was set inactive prior.
   */
  static public void sendServerCommandFenceLeave (long fid)
  {
    Context                   context         = ApplicationContext.getInstance();
    GroupDatabase             groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase            threadDatabase  = DatabaseFactory.getThreadDatabase(context);
    GroupDatabase.GroupRecord groupRecord     = groupDatabase.getGroupRecordByFid(fid);
    byte[]                    groupId         = groupRecord.getId();
    Recipient                 groupRecipient  = Recipient.from(context, Address.fromSerialized(groupRecord.getEncodedId()), false);
    long                      threadId        = threadDatabase.getThreadIdFor(null, fid);
    long                      timeSentInMillis= System.currentTimeMillis();//

    // adde Fencecommand context
    SignalServiceProtos.FenceCommand fenceCommand=MessageSender.buildFenceCommandFinal(ApplicationContext.getInstance(),
                                                                                       Optional.absent(),
            Optional.absent(),//invited
            groupRecord.getTitle(),
            Optional.of(groupRecord.getFid()),
            Optional.absent(),
            new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.LEAVE_VALUE, 0),
            Optional.absent(),
            Optional.absent(),
            timeSentInMillis);

    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //

    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
            .setId(ByteString.copyFrom(groupId))
            .setType(SignalServiceProtos.GroupContext.Type.QUIT)
            .setFenceMessage(fenceCommand)
            .build();

    OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0,null, Collections.emptyList(), Collections.emptyList(),
            ufsrvCommandBuilder.build());
    MessageSender.send(context, outgoingMessage, threadId, false, null);

    DatabaseFactory.getGroupDatabase(context).markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED);

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
  static public void sendServerCommandFenceThreadDeleted (Context context, Set<Long> threadIds)
  {
    for (long threadId : threadIds) {
      GroupDatabase.GroupRecord groupRecord;
      if ((groupRecord=DatabaseFactory.getGroupDatabase(context).getGroupRecordfromThreadId(threadId))==null) {
        continue;
      }

      byte[] groupId = groupRecord.getId();
      Recipient groupRecipient   = Recipient.from(context, Address.fromSerialized(groupRecord.getEncodedId()), false);
      long                      timeSentInMillis  = System.currentTimeMillis();//

      // adde Fencecommand context
      SignalServiceProtos.FenceCommand fenceCommand=MessageSender.buildFenceCommandFinal(ApplicationContext.getInstance(),
             Optional.absent(),
              Optional.absent(),//invited
              groupRecord.getTitle(),
              Optional.of(groupRecord.getFid()),
              Optional.absent(),
              new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.LEAVE_VALUE, 0),
              Optional.absent(),
              Optional.absent(),
              timeSentInMillis);

      SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder= SignalServiceProtos.UfsrvCommandWire.newBuilder()
              .setFenceCommand(fenceCommand)
              .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
      //

      SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
              .setId(ByteString.copyFrom(groupId))
              .setType(SignalServiceProtos.GroupContext.Type.QUIT)
              .setFenceMessage(fenceCommand)
              .build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, null, timeSentInMillis, 0, 0, null, Collections.emptyList(), Collections.emptyList(),
              ufsrvCommandBuilder.build());
      MessageSender.send(context, outgoingMessage, threadId, false, null);

      DatabaseFactory.getGroupDatabase(context).markGroupMode(groupRecord.getFid(), GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED);
    }
  }


  static public FenceRecord.Permission getFenceCommandPermission (FenceRecord fenceRecord, FenceRecord.Permission.Type permissionType)
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


  static public long sendFencePermissionCommand (Context context, Recipient recipientsGroup, long fid, long userid, EnumFencePermissions fencePermission, int commandArg)
  {
    long      timeSentInMillis      = System.currentTimeMillis();

    FenceCommand fenceCommand = MessageSender.buildFenceCommandPermissionFinal(new UfsrvCommandUtils.CommandArgDescriptor(FenceCommand.CommandTypes.PERMISSION_VALUE, commandArg),
                                                                               Recipient.fromUfsrvId(context, userid, Optional.absent(), false),
                                                                               fid,
                                                                               fencePermission,
                                                                               timeSentInMillis);

    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandWireBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
            .setFenceCommand(fenceCommand)
            .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    //

    try {
      SignalServiceProtos.GroupContext.Builder groupContextBuilder = SignalServiceProtos.GroupContext.newBuilder()
              .setId(ByteString.copyFrom(GroupUtil.getDecodedId(recipientsGroup.getAddress().toGroupString())))
              .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
              .setFenceMessage(fenceCommand);
      SignalServiceProtos.GroupContext groupContext = groupContextBuilder.build();

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipientsGroup, groupContext, null, timeSentInMillis, 0, 0,null, Collections.emptyList(), Collections.emptyList(),
                                                                                ufsrvCommandWireBuilder.build());

      long threadId = MessageSender.send(context, outgoingMessage, -1, false, null);

      return threadId;
    }
    catch (IOException ex) {
      Log.d(TAG, String.format("sendFencePermissionCommand: %s", ex.getMessage()));
    }

    return -1;
  }

  //Determine the target fence from message type
  static public FenceRecord getTargetFence(SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire)
  {
    if (ufsrvCommandWire==null) return null;

    switch (ufsrvCommandWire.getUfsrvtype())
    {
      case UFSRV_FENCE:
        if (ufsrvCommandWire.hasFenceCommand() && ufsrvCommandWire.getFenceCommand().getFencesCount()>0)  return ufsrvCommandWire.getFenceCommand().getFences(0);
      case UFSRV_MESSAGE:
        if (ufsrvCommandWire.hasMsgCommand() && ufsrvCommandWire.getMsgCommand().getFencesCount()>0) return ufsrvCommandWire.getMsgCommand().getFences(0);
      case UFSRV_USER:
        if (ufsrvCommandWire.hasUserCommand() && ufsrvCommandWire.getUserCommand().getFencesCount()>0) return ufsrvCommandWire.getUserCommand().getFences(0);
    }
    return null;
  }


  static public long fidFromGroupRecipients (Context context, Recipient recipient)
  {
    long fid=0;

    if (recipient.isGroupRecipient()) {
      String                      decodedGroupId  = recipient.getAddress().toGroupString();
      GroupDatabase               groupDatabase   = DatabaseFactory.getGroupDatabase(context);
      GroupDatabase.GroupRecord   record          = groupDatabase.getGroup(decodedGroupId).get();

      if (record!=null) {
        Log.d (TAG, String.format("fidFromGroupRecipients: decodedGroupId:'%s', fid:'%d'", decodedGroupId, record.getFid()));
        fid=record.getFid();
      } else {
        Log.d (TAG, String.format("fidFromGroupRecipients: ERROR FETCHING GROUP RECORD FOR: decodedGroupId:'%s', fid:'0'", decodedGroupId));
      }
    }

    return fid;
  }

  static public String updateCnameWithNewFname (long fid, String cnameOld, String fnameOld, String fnameNew)
  {
    String cnameNew = cnameOld;

    try {
      cnameNew = (cnameOld.substring(0, cnameOld.lastIndexOf(':')) + ":" + fnameNew);
      Log.d(TAG, String.format("updateCnameWithNewFname: cnameOld:'%s'. cnamenew:'%s'", cnameOld, cnameNew));
    } catch (StringIndexOutOfBoundsException x) {
      Log.w(TAG, String.format("updateCnameWithNewFname: cnameOld:'%s'. cnamenew:'%s': original cname is malformatted... rebuilding (not implemented)", cnameOld, cnameNew));
      //todo: fetch cname from server
    }

    return cnameNew;
  }


  static public boolean isAvatarUfsrvIdLoaded (@NonNull String avatarUfsrvId)
  {
    return !TextUtils.isEmpty(avatarUfsrvId) && avatarUfsrvId.length()>1;
  }

  private static HashMap<Long, ThreadRecord>invitedCursorToFidMap (Cursor cursor)
  {
    HashMap fidToThreadRecord=new HashMap();
    cursor.moveToFirst();

    while (cursor != null && !cursor.isAfterLast()) {
      ThreadRecord rec = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadRecord (cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID)));
      fidToThreadRecord.put(cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_FID)), rec);
      cursor.moveToNext();
    }

    return fidToThreadRecord;
  }

  static void updateAndDownloadAvatarForUser (@NonNull Context context, Recipient recipient, SignalServiceProtos.UserRecord userRecord)
  {
    DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient, true);
    DatabaseFactory.getRecipientDatabase(context).setProfileKey(recipient, userRecord.getProfileKey().toByteArray());

    if (!TextUtils.isEmpty(userRecord.getAvatar().getId())) {
      if (!TextUtils.equals(userRecord.getAvatar().getId(), recipient.getGroupAvatarUfsrvId())) {
        DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(recipient, userRecord.getAvatar().getId());
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new ProfileAvatarDownloadJob(recipient.getUfsrvUid(), userRecord.getAvatar()));
      }
      else {
        Log.d(TAG, String.format("updateAndDownloadAvatarForUser (%d, uid:'%d', nick:'%s'): NOT UPDATING AVATAR: IDENTICAL", Thread.currentThread().getId(), recipient.getUfsrvId(), userRecord.getAvatar().getId()));
      }
    } else {
      Log.d(TAG, String.format("updateAndDownloadAvatarForUser (uid:'%d'): NO avatar ufid defined", recipient.getUfsrvId()));
    }
  }

  public static void updateProfileKeyForFenceMembers (@NonNull Context context, @NonNull  List<SignalServiceProtos.UserRecord> userRecords, Optional<List<String>>excludedUsers)
  {
    Recipient recipient;

    if (excludedUsers.isPresent()) {
      String ufsrvUid;

      for (SignalServiceProtos.UserRecord record : userRecords) {
        ufsrvUid = UfsrvUid.EncodedfromSerialisedBytes(record.getUfsrvuid().toByteArray());
        if (!excludedUsers.get().contains(ufsrvUid) && record.hasProfileKey()) {
          recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(record.getUfsrvuid().toByteArray()), false);
          updateAndDownloadAvatarForUser(context, recipient, record);
        }
        else {
          Log.d(TAG, String.format("UserRecordsToNumbersList: Found excluded member :'%s'", ufsrvUid));
        }
      }
    }
    else {
      for (SignalServiceProtos.UserRecord record : userRecords) {
        if (record.hasProfileKey()) {
          recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(record.getUfsrvuid().toByteArray()), false);
          updateAndDownloadAvatarForUser(context, recipient, record);
        }
      }
    }
  }

  //build a generic fence command header
  public static FenceCommand.Builder
  buildFenceCommandHeader (long fid, long timeNowInMillis, CommandArgs commandArg, FenceCommand.CommandTypes commandType)
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
  updateFencePreferences (@NonNull Context context, @NonNull GroupDatabase.GroupRecord groupRecord, @NonNull FenceRecord fenceRecord)
  {
    if (fenceRecord.getFencePreferencesList().isEmpty())  return;

    RecipientDatabase preferenceDatabase  = DatabaseFactory.getRecipientDatabase(context);
    Recipient         recipient           =  Recipient.fromFid(context, fenceRecord.getFid(), false);

    for (FenceUserPreference userPreference : fenceRecord.getFencePreferencesList()) {
      switch (userPreference.getPrefId()) {
        case PROFILE_SHARING:
          if (userPreference.hasValuesInt()) preferenceDatabase.setProfileSharing(recipient, userPreference.getValuesInt()==0?false:true);
          break;
        case STICKY_GEOGROUP:
          if (userPreference.hasValuesInt()) {
            RecipientDatabase.GeogroupStickyState stickyState = RecipientDatabase.GeogroupStickyState.values()[(int) userPreference.getValuesInt()];
            preferenceDatabase.setGeogroupSticky(recipient, stickyState);
          }
          break;
        default:
          Log.e(TAG, String.format("updateFencePreferences (fid:'%d', prefId:'%d'): Uknown FenceUserPreference id", fenceRecord.getFid(), userPreference.getPrefId()));
      }

    }
  }

  public static Optional<GroupManager.GroupActionResult> createGroupForTwo (@NonNull Context context, Recipient recipient)
  {
    GroupManager.GroupActionResult result;
    Set<Recipient> members = new HashSet<>();
    members.add(recipient);
    result = GroupManager.createPrivateGroup(context,
                                             members,
                                             null,
                                             false,
                                             ufLocation.getInstance().getBaseLocationPrefix(),
                                             0.0, 0.0);

    return Optional.fromNullable(result);
  }

  public static List<Long> getForTwoGroups (@NonNull Recipient recipient, @NonNull Recipient recipientOther) {
    return DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).getForTwoGroups(recipient, recipientOther);
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
  public static void openConversation(@NonNull Context context, long threadId, @NonNull Recipient recipient, int distributionType, Optional<Long> lastSeen, Optional<Integer> startingPosition)
  {
    //
    long fid=DatabaseFactory.getThreadDatabase(context).getFidForThreadId(threadId);
    if (DatabaseFactory.getGroupDatabase(context).isGroupInvitationPending(fid)) {
      //check the interface callback for when a dialog button is pressed
      InvitedToGroupDialog invitedToGroupDialog= new InvitedToGroupDialog(context, recipient, threadId);
      invitedToGroupDialog.display();

      if (invitedToGroupDialog.isJoinAccepted())
      {
        //switch to threadid as per rge code conversationListFragment below
      }
      else
      {
        //nothing
        Log.d(TAG, String.format("onCreateConversation: thread '%d' invitation to joing was not accepted yet", threadId));
        return;
      }
    }
    //

    Intent intent = new Intent(context, ConversationActivity.class);
    intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.getAddress());
    intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId);
    intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, distributionType);
    intent.putExtra(ConversationActivity.TIMING_EXTRA, System.currentTimeMillis());
    if (lastSeen.isPresent()) intent.putExtra(ConversationActivity.LAST_SEEN_EXTRA, lastSeen.get().longValue());
    if (startingPosition.isPresent()) intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, startingPosition.get().intValue());

    intent.putExtra(ConversationActivity.GROUP_FID_EXTRA, fid); //

    context.startActivity(intent);
    ((Activity)context).overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }
}
