/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.sms;

import android.app.Application;
import android.content.Context;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.jobmanager.Job;
import android.text.TextUtils;
import org.thoughtcrime.securesms.jobs.AttachmentCopyJob;
import org.thoughtcrime.securesms.jobs.AttachmentUploadJob;
import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import org.thoughtcrime.securesms.attachments.Attachment;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.jobs.MessageCommandEndSessionJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvCommandUtils;
import com.unfacd.android.utils.UfsrvMessageUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.jobs.MmsSendJob;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.jobs.PushGroupSendJob;
import org.thoughtcrime.securesms.jobs.PushMediaSendJob;
import org.thoughtcrime.securesms.jobs.PushTextSendJob;
import org.thoughtcrime.securesms.jobs.SmsSendJob;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class MessageSender {

  private static final String TAG = MessageSender.class.getSimpleName();

  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    SmsDatabase database    = DatabaseFactory.getSmsDatabase(context);
    Recipient            recipient  = message.getRecipient();
    boolean               keyExchange = message.isKeyExchange();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(allocatedThreadId, message, forceSms, System.currentTimeMillis(), insertListener);

    sendTextMessage(context, recipient, forceSms, keyExchange, messageId);

    return allocatedThreadId;
  }

  public static long send(final Context context,
                          final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    try {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
      MmsDatabase    database       = DatabaseFactory.getMmsDatabase(context);

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipient(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipient recipient = message.getRecipient();
      long      messageId = database.insertMessageOutbox(message, allocatedThreadId, forceSms, insertListener);

      sendMediaMessage(context, recipient, forceSms, messageId, message.getExpiresIn());

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }


  // see send above
  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long fid,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    SmsDatabase database    = DatabaseFactory.getSmsDatabase(context);
    Recipient            recipient  = message.getRecipient();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(allocatedThreadId, message, forceSms, System.currentTimeMillis(), insertListener);

//    sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new MessageCommandEndSessionJob(fid, messageId));

    return allocatedThreadId;
  }
  //

  public static void sendMediaBroadcast(@NonNull Context context, @NonNull List<OutgoingSecureMediaMessage> messages) {
    if (messages.isEmpty()) {
      Log.w(TAG, "sendMediaBroadcast() - No messages!");
      return;
    }

    if (!isValidBroadcastList(messages)) {
      Log.w(TAG, "sendMediaBroadcast() - Invalid message list!");
      return;
    }

    ThreadDatabase           threadDatabase     = DatabaseFactory.getThreadDatabase(context);
    MmsDatabase              mmsDatabase        = DatabaseFactory.getMmsDatabase(context);
    AttachmentDatabase       attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
    List<List<AttachmentId>> attachmentIds      = new ArrayList<>(messages.get(0).getAttachments().size());
    List<Long>               messageIds         = new ArrayList<>(messages.size());

    for (int i = 0; i < messages.get(0).getAttachments().size(); i++) {
      attachmentIds.add(new ArrayList<>(messages.size()));
    }

    try {
      try {
        mmsDatabase.beginTransaction();

        for (OutgoingSecureMediaMessage message : messages) {
          long                     allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipient(), message.getDistributionType());
          long                     messageId         = mmsDatabase.insertMessageOutbox(message, allocatedThreadId, false, null);
          List<DatabaseAttachment> attachments       = attachmentDatabase.getAttachmentsForMessage(messageId);

          if (attachments.size() != attachmentIds.size()) {
            Log.w(TAG, "Got back an attachment list that was a different size than expected. Expected: " + attachmentIds.size() + "  Actual: "+ attachments.size());
            return;
          }

          for (int i = 0; i < attachments.size(); i++) {
            attachmentIds.get(i).add(attachments.get(i).getAttachmentId());
          }

          messageIds.add(messageId);
        }

        mmsDatabase.setTransactionSuccessful();
      } finally {
        mmsDatabase.endTransaction();
      }

      List<AttachmentUploadJob> uploadJobs  = new ArrayList<>(attachmentIds.size());
      List<AttachmentCopyJob>   copyJobs    = new ArrayList<>(attachmentIds.size());
      List<Job>                 messageJobs = new ArrayList<>(attachmentIds.get(0).size());

      for (List<AttachmentId> idList : attachmentIds) {
        uploadJobs.add(new AttachmentUploadJob(idList.get(0)));

        if (idList.size() > 1) {
          AttachmentId       sourceId       = idList.get(0);
          List<AttachmentId> destinationIds = idList.subList(1, idList.size());

          copyJobs.add(new AttachmentCopyJob(sourceId, destinationIds));
        }
      }

      for (int i = 0; i < messageIds.size(); i++) {
        long                       messageId = messageIds.get(i);
        OutgoingSecureMediaMessage message   = messages.get(i);
        Recipient                  recipient = message.getRecipient();

        if (isLocalSelfSend(context, recipient, false)) {
          sendLocalMediaSelf(context, messageId);
        } else if (isGroupPushSend(recipient)) {
          messageJobs.add(new PushGroupSendJob(messageId, recipient.getAddress(), null));
        } else {
          messageJobs.add(new PushMediaSendJob(messageId, recipient.getAddress()));
        }
      }

      Log.i(TAG, String.format(Locale.ENGLISH, "sendMediaBroadcast() - Uploading %d attachment(s), copying %d of them, then sending %d messages.",
                               uploadJobs.size(),
                               copyJobs.size(),
                               messageJobs.size()));

      JobManager.Chain chain = ApplicationContext.getInstance(context).getJobManager().startChain(uploadJobs);

      if (copyJobs.size() > 0) {
        chain = chain.then(copyJobs);
      }

      chain = chain.then(messageJobs);
      chain.enqueue();
    } catch (MmsException e) {
      Log.w(TAG, "sendMediaBroadcast() - Failed to send messages!", e);
    }
  }

  public static void resendGroupMessage(Context context, MessageRecord messageRecord, Address filterAddress) {
    if (!messageRecord.isMms()) throw new AssertionError("Not Group");
    sendGroupPush(context, messageRecord.getRecipient(), messageRecord.getId(), filterAddress);
  }

  public static void resend(Context context, MessageRecord messageRecord) {
    long       messageId   = messageRecord.getId();
    boolean    forceSms    = messageRecord.isForcedSms();
    boolean    keyExchange = messageRecord.isKeyExchange();
    long       expiresIn   = messageRecord.getExpiresIn();
    Recipient  recipient   = messageRecord.getRecipient();

    if (messageRecord.isMms()) {
      sendMediaMessage(context, recipient, forceSms, messageId, expiresIn);
    } else {
      sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    }
  }

  private static void sendMediaMessage(Context context, Recipient recipient, boolean forceSms, long messageId, long expiresIn)
  {

    if (isLocalSelfSend(context, recipient, forceSms)) {
      Log.d(TAG, String.format("sendMediaMessage: selfSend message..."));
      sendLocalMediaSelf(context, messageId);
    } else if (isGroupPushSend(recipient)) {// checks if the encoded group name
      Log.d(TAG, String.format("sendMediaMessage: isGroupPushSend() true..."));
      sendGroupPush(context, recipient, messageId, null);
    } else if (!forceSms && isPushMediaSend(context, recipient)) {
      Log.d(TAG, String.format("sendMediaMessage: isPushMediaSend() true..."));//mms
      sendMediaPush(context, recipient, messageId);
    } else {
      Log.d(TAG, String.format("sendMediaMessage: Sending sms on MsgId: "+messageId+"'"));//mms
      sendMms(context, messageId);
    }
  }

  private static void sendTextMessage(Context context, Recipient recipient,
                                      boolean forceSms, boolean keyExchange,
                                      long messageId)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      Log.d(TAG, String.format("sendTextMessage: Invoking sendSelfText()... MsgId: "+messageId+"'"));//mms
      sendLocalTextSelf(context, messageId);
    } else if (!forceSms && isPushTextSend(context, recipient, keyExchange)) {
      Log.d(TAG, String.format("sendTextMessage: isPushTextSend... MsgId: "+messageId+"'"));//mms
      sendTextPush(context, recipient, messageId);
    } else {
      Log.d(TAG, String.format("sendTextMessage: Invoking sendSms()... MsgId: "+messageId+"'"));//mms
      sendSms(context, recipient, messageId);
    }
  }

  private static void sendTextPush(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new PushTextSendJob(messageId, recipient.getAddress()));
  }

  private static void sendMediaPush(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    PushMediaSendJob.enqueue(context, jobManager, messageId, recipient.getAddress());
  }

  private static void sendGroupPush(Context context, Recipient recipient, long messageId, Address filterAddress) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    PushGroupSendJob.enqueue(context, jobManager, messageId, recipient.getAddress(), filterAddress);
  }

  public static
  FenceCommand.Builder buildFenceCommandJoinInvitationResponse(Context context, long fid, long timeNowInMillis, boolean accepted)
  {
    SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder    = SignalServiceProtos.FenceCommand.newBuilder();
    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();

    SignalServiceProtos.FenceRecord.Builder fenceBuilder  = SignalServiceProtos.FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);


    //build/link header
    commandHeaderBuilder.setCommand(SignalServiceProtos.FenceCommand.CommandTypes.JOIN_VALUE);
    commandHeaderBuilder.setWhen(timeNowInMillis);

    if (accepted) commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.INVITED_VALUE);
    else  commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.REJECTED_VALUE);//SHOULD BE REJECTED

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }
  //

  //
  public static
  FenceCommand.Builder buildFenceCommandGeoInvitationResponse(Context context, long fid, boolean accepted)
  {
    SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder    = SignalServiceProtos.FenceCommand.newBuilder();
    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();
    String fenceCname;

    SignalServiceProtos.FenceRecord.Builder fenceBuilder  = SignalServiceProtos.FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(System.currentTimeMillis());

    //build/link header
    commandHeaderBuilder.setCommand(SignalServiceProtos.FenceCommand.CommandTypes.JOIN_VALUE);

    if (accepted) commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.GEO_BASED_VALUE);
    else  commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.DENIED_VALUE);//SHOULD BE REJECTED

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }
  //

  //
  /**
   * Preassmble an outgoing FenceCommand for a request for group syncing. JOIN-syncing type request is typically when a fence does not have internal state recorded in the
   * client. STATE-syncing is for an alredy existing fence for which we are syncing its internal state, for example we were disconnected.
   *
   * @param context
   * @param fid
   * @param accepted
   * @param join_sync
   * @return
   */
  public static FenceCommand.Builder
  buildFenceCommandStateSyncedResponse (Context context, long fid, long timeNowInMillis, boolean accepted, boolean join_sync)
  {
    FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);

    if (accepted) commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.SYNCED_VALUE);
    else          commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.REJECTED_VALUE);

    if (join_sync)  commandHeaderBuilder.setCommand(SignalServiceProtos.FenceCommand.CommandTypes.JOIN_VALUE);
    else            commandHeaderBuilder.setCommand(SignalServiceProtos.FenceCommand.CommandTypes.STATE_VALUE);

    commandHeaderBuilder.setWhen(timeNowInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  //

  //
  /**
   * This is unbuilt command, although it has most of its sub components built
   * @param context
   * @param recipientsInvited
   * @param fname
   * @param fid
   * @param avatar
   * @param privacyMode
   * @param location
   * @return
   */
  public static FenceCommand.Builder
  buildFenceCommand(Context context,
                             Optional<List<Recipient>> recipient,
                             Optional<List<Recipient>> recipientsInvited,
                             @Nullable String fname,
                             Optional<Long> fid,
                             Optional<byte[]> avatar,
                             @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                             Optional<FenceRecord.PrivacyMode> privacyMode,
                              Optional<FenceRecord.DeliveryMode> deliveryMode,
                              Optional<FenceRecord.JoinMode> joinMode,
                              int memberLimit,
                             Optional<ufLocation> location,
                              @Nullable RecipientDatabase.GroupPermission[] groupPermissions,
                             long timeSentInMillis)
{
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();
    String                fenceCname            = null;

    if (!fid.isPresent() || fid.get()==0) {
      //only do this when new group is being created
      if (location.isPresent()) fenceCname = location.get().getBaseLocationPrefix() + fname;
      else {
        fenceCname = ufLocation.getInstance().getBaseLocationPrefix() + fname;
      }
      fenceBuilder.setCreated(System.currentTimeMillis());
    }

    if (fenceCname!=null) fenceBuilder.setCname(fenceCname);
    if (fname!=null)      fenceBuilder.setFname(fname);
    if (privacyMode.isPresent())  fenceBuilder.setPrivacyMode(privacyMode.get());
    if (deliveryMode.isPresent())  fenceBuilder.setDeliveryMode(deliveryMode.get());
    if (joinMode.isPresent())  fenceBuilder.setJoinMode(joinMode.get());

    if (memberLimit!=-1)  fenceBuilder.setMaxmembers(memberLimit);

    fenceBuilder.setFid(fid.or(Long.valueOf(0)));

    if (groupPermissions!=null) {
      RecipientDatabase.GroupPermission groupPermission;

      groupPermission=groupPermissions[EnumFencePermissions.PRESENTATION.getValue()-1];
      fenceBuilder.setPresentation(FenceRecord.Permission.newBuilder()
        .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
        .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission=groupPermissions[EnumFencePermissions.MEMBERSHIP.getValue()-1];
      fenceBuilder.setMembership(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission=groupPermissions[EnumFencePermissions.MESSAGING.getValue()-1];
      fenceBuilder.setMessaging(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission=groupPermissions[EnumFencePermissions.ATTACHING.getValue()-1];
      fenceBuilder.setAttaching(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission=groupPermissions[EnumFencePermissions.CALLING.getValue()-1];
      fenceBuilder.setCalling(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

    }

    if(recipient.isPresent()) {
      fenceBuilder.addAllMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipient.get()));
    }

    if(recipientsInvited.isPresent()) {
      fenceBuilder.addAllInvitedMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipientsInvited.get()));
    }

    //only do it for new fences
    if (!fid.isPresent() || fid.get()==0) {//location bloc
      Location myLoc;
      android.location.Address myAddr;
      String baseloc;
      if (location.isPresent()) {
        myLoc = location.get().getMyLocation();
        myAddr = location.get().getMyAddress();
        baseloc = location.get().getBaseLocationPrefix();
      }else {
        ufLocation uflocation=ufLocation.getInstance();
        myLoc = ufLocation.getInstance().getMyLocation();
        myAddr = ufLocation.getInstance().getMyAddress();
        baseloc = ufLocation.getInstance().getBaseLocationPrefix();
      }

      if (myLoc!=null) {
        SignalServiceProtos.LocationRecord.Builder locationBuilder = SignalServiceProtos.LocationRecord.newBuilder();
        locationBuilder.setSource(SignalServiceProtos.LocationRecord.Source.USER);

        locationBuilder.setLatitude(myLoc.getLatitude());
        locationBuilder.setLongitude(myLoc.getLongitude());

        locationBuilder.setBaseloc(baseloc);

        Log.d(TAG, String.format("buildFenceCommand: Baseloc: '%s'. fid:'%d'", baseloc, fid.isPresent() ? fid.get() : 0));

        if (myAddr != null) {
          locationBuilder.setAdminArea(myAddr.getAdminArea());
          locationBuilder.setCountry(myAddr.getCountryName());
          locationBuilder.setLocality(myAddr.getLocality());
        }

        fenceBuilder.setLocation(locationBuilder.build());
      }
    }

    //build/link header
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }


  public static FenceCommand
  buildFenceCommandInviteFinal  (Context context,
                                 @NonNull List<Recipient> recipientInvited,
                                 @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                 long fid,
                                 long timeSentInMillis)
{
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);

    fenceBuilder.addAllInvitedMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipientInvited));

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }

  static {

  }
  public static FenceCommand
  buildFenceCommandPermissionFinal  (@NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                     @NonNull Recipient recipient,
                                     long fid,
                                     EnumFencePermissions fencePermission,
                                     long timeSentInMillis)
  {
    FenceCommand.Builder            fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder           commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder             fenceBuilder          = FenceRecord.newBuilder();
    FenceRecord.Permission.Builder  permissionBuilder     = FenceRecord.Permission.newBuilder();
    UserRecord.Builder              userRecordBuilder     = UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername("0");

    permissionBuilder.addUsers(userRecordBuilder.build());

    fenceBuilder.setFid(fid);
    switch (fencePermission)
    {
      case PRESENTATION:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.PRESENTATION);
        permissionBuilder.setType(FenceRecord.Permission.Type.PRESENTATION);
        fenceBuilder.setPresentation(permissionBuilder.build());
        break;
      case MEMBERSHIP:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.MEMBERSHIP);
        permissionBuilder.setType(FenceRecord.Permission.Type.MEMBERSHIP);
        fenceBuilder.setMembership(permissionBuilder.build());
        break;
      case MESSAGING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.MESSAGING);
        permissionBuilder.setType(FenceRecord.Permission.Type.MESSAGING);
        fenceBuilder.setMessaging(permissionBuilder.build());
        break;
      case ATTACHING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.ATTACHING);
        permissionBuilder.setType(FenceRecord.Permission.Type.ATTACHING);
        fenceBuilder.setAttaching(permissionBuilder.build());
        break;
      case CALLING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.CALLING);
        permissionBuilder.setType(FenceRecord.Permission.Type.CALLING);
        fenceBuilder.setCalling(permissionBuilder.build());
        break;
      default:
        return null;
    }

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());

    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());
    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }

  public static FenceCommand
  buildFenceCommandMessageExpirayFinal  (Context context,
                                         @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                         long expiry_timer,
                                         long fid,
                                         long timeSentInMillis)
  {
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setExpireTimer(expiry_timer);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }


  /**
   *  Primary interface for invoking fence join by client.
   * @param context
   * @param recipient user creating a new grou[ and specifying starting invite list
   * @param fname
   * @param fid -1 server to allocate
   */
  public static FenceCommand
  buildFenceCommandFinal (Context context,
                           Optional<List<Recipient>> recipient,
                           Optional<List<Recipient>> recipientsInvited,
                           @NonNull String fname,
                           Optional<Long> fid,
                           Optional<byte[]> avatar,
                           @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                           Optional<FenceRecord.PrivacyMode> privacyMode,
                           Optional<ufLocation> location,
                           long timeSentInMillis)
{
  return buildFenceCommand(context, recipient, recipientsInvited, fname, fid, avatar,
                           commandArgDescriptor, privacyMode, Optional.of(FenceRecord.DeliveryMode.MANY),
                           Optional.of(FenceRecord.JoinMode.OPEN), -1,
                           location, null, timeSentInMillis).build();

  }


  // singular msg
  public static ReceiptCommand.Builder
  buildReceiptCommand(Context context,
                             Optional<List<Recipient>> recipient,
                             long timeSentInMillis,
                             UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier,
                             ReceiptCommand.CommandTypes receiptType)
  {

    CommandHeader.Builder commandHeaderBuilder      = CommandHeader.newBuilder();
    ReceiptCommand.Builder receiptCommandBuilder    = ReceiptCommand.newBuilder();

    commandHeaderBuilder.setCommand(receiptType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvReceiptCommand());//todo: this has to be defined somewhere else
    receiptCommandBuilder.setHeader(commandHeaderBuilder.build());

    receiptCommandBuilder.setType(receiptType);
    receiptCommandBuilder.setFid(messageIdentifier.fid);
    receiptCommandBuilder.setUidOriginator(ByteString.copyFrom(Recipient.fromUfsrvUid(context, new UfsrvUid(messageIdentifier.uidOriginator), false).getUfrsvUidRaw()));

    receiptCommandBuilder.addAllEid(Arrays.asList(messageIdentifier.eid));
    receiptCommandBuilder.addAllTimestamp(Arrays.asList(messageIdentifier.timestamp));

    return receiptCommandBuilder;
  }

  // multi msg from the same originator
  public static ReceiptCommand.Builder
  buildReceiptCommand(Context context,
                      Optional<List<Recipient>> recipient,
                      long timeSentInMillis,
                      List<UfsrvMessageUtils.UfsrvMessageIdentifier> messageIdentifiers,
                      ReceiptCommand.CommandTypes receiptType)
  {

    if (messageIdentifiers==null) {
      Log.e(TAG, String.format("buildReceiptCommand(multi): messageIdentifiers null: NOT SENDING RECEIPT"));

      return null;
    }

    CommandHeader.Builder commandHeaderBuilder      = CommandHeader.newBuilder();
    ReceiptCommand.Builder receiptCommandBuilder    = ReceiptCommand.newBuilder();

    //build/link header
    commandHeaderBuilder.setCommand(receiptType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvReceiptCommand());//todo: this has to be defined somewhere else
    receiptCommandBuilder.setHeader(commandHeaderBuilder.build());
//
    receiptCommandBuilder.setType(receiptType);
    receiptCommandBuilder.setFid(messageIdentifiers.get(0).fid);
    receiptCommandBuilder.setUidOriginator(ByteString.copyFrom(Recipient.fromUfsrvUid(context, new UfsrvUid(messageIdentifiers.get(0).uidOriginator), false).getUfrsvUidRaw()));

    List<Long> eids = new LinkedList<>();
    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier: messageIdentifiers) {
      eids.add(messageIdentifier.eid);
    }
    receiptCommandBuilder.addAllEid(eids);

    List<Long> msgids = new LinkedList<>();
    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier: messageIdentifiers) {
      msgids.add(messageIdentifier.timestamp);
    }
    receiptCommandBuilder.addAllTimestamp(msgids);

    return receiptCommandBuilder;
  }

  // returns a completely encapusalted type (e.g Message) command structure, which can be sents along as is or incorporated into a UfsrCommandWire
  public static MessageCommand
  buildMessageCommandFinal(Context context,
                           Optional<List<Recipient>> recipient,
                           String body,
                           Optional<List<SignalServiceAttachment>> attachments,
                           Optional<SignalServiceDataMessage.Quote> quote,
                           long timeSentInMillis,
                           int command,
                           long fid)
{
    return buildMessageCommand(context, recipient, body, attachments, quote, timeSentInMillis, command, fid, false).build();
  }

  // returns a completely encapusalted type (e.g Message) command structure, which can be sents along as is or incorporated into a UfsrCommandWire
  public static MessageCommand.Builder
  buildMessageCommand(Context context,
                       Optional<List<Recipient>> recipient,
                       String body,
                       Optional<List<SignalServiceAttachment>> attachments,
                       Optional<SignalServiceDataMessage.Quote> quote,
                       long timeSentInMillis,
                       int command,
                       long fid,
                       boolean isE2ee)
  {

    CommandHeader.Builder commandHeaderBuilder                = SignalServiceProtos.CommandHeader.newBuilder();
    MessageCommand.Builder messageCommandBuilder              = SignalServiceProtos.MessageCommand.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    if (!isE2ee) {
      SignalServiceProtos.MessageRecord.Builder messageRecordBuilder  = SignalServiceProtos.MessageRecord.newBuilder();
      if (!TextUtils.isEmpty(body)) messageRecordBuilder.setMessage(ByteString.copyFromUtf8(body));
      messageCommandBuilder.addMessages(messageRecordBuilder.build());
    }
    //do it further downstream
//    if (quote.isPresent()) {
//      messageRecordBuilder.setQuotedMessage(UfsrvMessageUtils.adaptForQuotedMessage(quote.get()));
//    }

    //build/link fence record
    if (fid > 0) {
      SignalServiceProtos.FenceRecord.Builder fenceBuilder = SignalServiceProtos.FenceRecord.newBuilder();
      fenceBuilder.setFid(fid);
      messageCommandBuilder.addFences(fenceBuilder.build());
    }

    SignalServiceProtos.UserRecord.Builder fromBuilder = SignalServiceProtos.UserRecord.newBuilder();
    fromBuilder.setUsername(TextSecurePreferences.getLocalNumber(context));
    fromBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    messageCommandBuilder.setOriginator(fromBuilder.build());

    //build/link to members
    if(recipient.isPresent()) {
      //this will be done for direct e2e encryption
     // messageCommandBuilder.addAllTo(recipientsToUserRecordList(recipient.get()));
    }

    if(attachments.isPresent()) {
      //we cannot process attachements from here, as we need to upload them first and obtain ids. so that gets done further downstream at
      //createMessageContent(SignalServiceDataMessage message, @NonNull  UfsrvCommand ufCommand) and included in DataMessage proto
    }

    return messageCommandBuilder;
  }

  //
  //for established group
  public static CallCommand.Builder
  buildCallCommand(Context context, long timeSentInMillis, CallCommand.CommandTypes commandType, Recipient recipientCalled, long fid)
  {
    CommandHeader.Builder commandHeaderBuilder        = CommandHeader.newBuilder();
    CallCommand.Builder   callCommandBuilder          = CallCommand.newBuilder();
    FenceRecord.Builder   fenceRecordBuilder          = FenceRecord.newBuilder();
    UserRecord.Builder    userRecordBuilder           = UserRecord.newBuilder();
    UserRecord.Builder    userRecordToBuilder         = UserRecord.newBuilder();

    commandHeaderBuilder.setCommand(commandType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setUname(TextSecurePreferences.getUfsrvUsername(context));
    commandHeaderBuilder.setCid(Long.valueOf(TextSecurePreferences.getUfsrvCid(context)));
    commandHeaderBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    callCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceRecordBuilder.setFid(fid);
    callCommandBuilder.setFence(fenceRecordBuilder.build());
    Log.d(TAG, String.format("buildCallCommand (type:'%d'): Building for fid:'%d'", commandType.getNumber(), fid));

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    userRecordBuilder.setUsername(TextSecurePreferences.getLocalNumber(context));
    callCommandBuilder.setOriginator(userRecordBuilder.build());

    userRecordToBuilder.setUfsrvuid(ByteString.copyFrom(recipientCalled.getUfrsvUidRaw()));
    userRecordToBuilder.setUsername(recipientCalled.getAddress().toPhoneString());
    callCommandBuilder.addTo(userRecordToBuilder);

    return callCommandBuilder;
  }

  //not suported yet, similar to new group create with an invite
  public static CallCommand.Builder
  buildCallCommand(Context context, Recipient recipient, long timeSentInMillis, int command)
  {
    CommandHeader.Builder commandHeaderBuilder        = CommandHeader.newBuilder();
    CallCommand.Builder   callCommandBuilder          = CallCommand.newBuilder();
    FenceRecord.Builder   fenceRecordBuilder          = FenceRecord.newBuilder();
    UserRecord.Builder    userRecordOriginatorBuilder = UserRecord.newBuilder();
    UserRecord.Builder    userRecordToBuilder         = UserRecord.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setWhen(timeSentInMillis);
//    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    callCommandBuilder.setHeader(commandHeaderBuilder.build());

    callCommandBuilder.setFence(fenceRecordBuilder.build());

    userRecordOriginatorBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    callCommandBuilder.setOriginator(userRecordOriginatorBuilder.build());

    userRecordToBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    callCommandBuilder.addTo(userRecordToBuilder.build());

    return callCommandBuilder;
  }
//

  private static void sendSms(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new SmsSendJob(context, messageId, recipient.getAddress()));
  }

  private static void sendMms(Context context, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new MmsSendJob(messageId));
  }

  private static boolean isPushTextSend(Context context, Recipient recipient, boolean keyExchange) {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      return false;
    }

    if (keyExchange) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isPushMediaSend(Context context, Recipient recipient) {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      return false;
    }

    if (recipient.isGroupRecipient()) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isGroupPushSend(Recipient recipient) {
    return recipient.getAddress().isGroup() &&
            !recipient.getAddress().isMmsGroup();
  }

  private static boolean isPushDestination(Context context, Recipient destination) {
    if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
      return true;
    } else if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.NOT_REGISTERED) {
      return false;
    } else {
      try {
        SignalServiceAccountManager   accountManager = AccountManagerFactory.createManager(context);
        Optional<ContactTokenDetails> registeredUser = accountManager.getContact(destination.getAddress().serialize());

        if (!registeredUser.isPresent()) {
          DatabaseFactory.getRecipientDatabase(context).setRegistered(destination, RecipientDatabase.RegisteredState.NOT_REGISTERED, registeredUser.orNull());
          return false;
        } else {
          DatabaseFactory.getRecipientDatabase(context).setRegistered(destination, RecipientDatabase.RegisteredState.REGISTERED, registeredUser.get());
          return true;
        }
      } catch (IOException e1) {
        Log.w(TAG, e1);
        return false;
      }
    }
  }

  private static boolean isLocalSelfSend(@NonNull Context context, @NonNull Recipient recipient, boolean forceSms) {
    return recipient.isLocalNumber()                       &&
            !forceSms                                       &&
            TextSecurePreferences.isPushRegistered(context) &&
            !TextSecurePreferences.isMultiDevice(context);
  }

  private static void sendLocalMediaSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager  = ApplicationContext.getInstance(context).getExpiringMessageManager();
      AttachmentDatabase     attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
      MmsDatabase            mmsDatabase        = DatabaseFactory.getMmsDatabase(context);
      MmsSmsDatabase         mmsSmsDatabase     = DatabaseFactory.getMmsSmsDatabase(context);
      OutgoingMediaMessage   message            = mmsDatabase.getOutgoingMessage(messageId);
      SyncMessageId          syncId             = new SyncMessageId(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), message.getSentTimeMillis());// ufsrv

      for (Attachment attachment : message.getAttachments()) {
        attachmentDatabase.markAttachmentUploaded(messageId, attachment);
      }

      mmsDatabase.markAsSent(messageId, true);
      mmsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        mmsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }
    } catch (NoSuchMessageException | MmsException e) {
      Log.w("Failed to update self-sent message.", e);
    }
  }

  private static void sendLocalTextSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager = ApplicationContext.getInstance(context).getExpiringMessageManager();
      SmsDatabase            smsDatabase       = DatabaseFactory.getSmsDatabase(context);
      MmsSmsDatabase         mmsSmsDatabase    = DatabaseFactory.getMmsSmsDatabase(context);
      SmsMessageRecord       message           = smsDatabase.getMessage(messageId);
      SyncMessageId          syncId            = new SyncMessageId(Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), message.getDateSent());// ufsrv

      smsDatabase.markAsSent(messageId, true);
      smsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0) {
        smsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(message.getId(), message.isMms(), message.getExpiresIn());
      }
    } catch (NoSuchMessageException e) {
      Log.w("Failed to update self-sent message.", e);
    }
  }

  private static boolean isValidBroadcastList(@NonNull List<OutgoingSecureMediaMessage> messages) {
    if (messages.isEmpty()) {
      return false;
    }

    int attachmentSize = messages.get(0).getAttachments().size();

    for (OutgoingSecureMediaMessage message : messages) {
      if (message.getAttachments().size() != attachmentSize) {
        return false;
      }
    }

    return true;
  }

}
