package org.thoughtcrime.securesms.jobs;


import android.content.Context;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase.GroupReceiptInfo;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobLogger;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.messages.StorySendUtil;
import org.thoughtcrime.securesms.mms.MessageGroupContext;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.RecipientAccessList;
import org.thoughtcrime.securesms.util.SetUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2;
import org.whispersystems.signalservice.api.messages.SignalServicePreview;
import org.whispersystems.signalservice.api.messages.SignalServiceStoryMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContextV2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

public final class PushGroupSendJob extends PushSendJob {

  public static final String KEY = "PushGroupSendJob";

  private static final String TAG = Log.tag(PushGroupSendJob.class);

  private static final String KEY_MESSAGE_ID       = "message_id";
  private static final String KEY_FILTER_RECIPIENT = "filter_recipient";
  private static final String KEY_TRANSPORT_TYPE   = "transport_type";//AA+
  private static final String KEY_INTEGRITY_SENSITIVE   = "integrity_sensitive";//AA+

  private final long        messageId;
  private final RecipientId filterRecipient;

  private UfsrvCommand.TransportType transportType; //AA+
  private boolean                    isIntegritySensitive;

  public PushGroupSendJob(long messageId, @NonNull RecipientId destination, @Nullable RecipientId filterRecipient, boolean hasMedia, UfsrvCommand.TransportType transportType, boolean isIntegritySensitive) {
    this(new Job.Parameters.Builder()
                 .setQueue(destination.toQueueKey(hasMedia))
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .build(),
         messageId, filterRecipient, transportType, isIntegritySensitive); //A+

  }

  private PushGroupSendJob(@NonNull Parameters parameters, long messageId, @Nullable RecipientId filterRecipient, UfsrvCommand.TransportType transportType, boolean isIntegritySensitive) {
    super(parameters);

    this.messageId       = messageId;
    this.filterRecipient = filterRecipient;

    //AA+
    this.transportType  = transportType;
    this.isIntegritySensitive = isIntegritySensitive;
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context,
                             @NonNull JobManager jobManager,
                             long messageId,
                             @NonNull RecipientId destination,
                             @Nullable RecipientId filterAddress,
                             UfsrvCommand.TransportType transportType, boolean isIntegritySensitive)
  {
    try {
      Recipient group = Recipient.resolved(destination);
      if (!group.isPushGroup()) {
        throw new AssertionError("Not a group!");
      }

      MessageDatabase        database                    = SignalDatabase.mms();
      OutgoingMediaMessage   message                     = database.getOutgoingMessage(messageId);
      Set<String>            attachmentUploadIds         = enqueueCompressingAndUploadAttachmentsChains(jobManager, message);

      //AA- some group are created inactive, such as invited to groups
//      if (!SignalDatabase.groups().isActive(group.requireGroupId()) && !isGv2UpdateMessage(message)) {
//        throw new MmsException("Inactive group!");
//      }

      jobManager.add(new PushGroupSendJob(messageId, destination, filterAddress, !attachmentUploadIds.isEmpty(), transportType, isIntegritySensitive), attachmentUploadIds, attachmentUploadIds.isEmpty() ? null : destination.toQueueKey()); //AA+ transportType

    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      SignalDatabase.mms().markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
                             .putString(KEY_FILTER_RECIPIENT, filterRecipient != null ? filterRecipient.serialize() : null)
                             .putInt(KEY_TRANSPORT_TYPE, transportType.getValue())
                             .putBoolean(KEY_INTEGRITY_SENSITIVE, isIntegritySensitive)
                             .build();
  }

  private static boolean isGv2UpdateMessage(@NonNull OutgoingMediaMessage message) {
    return (message instanceof OutgoingGroupUpdateMessage && ((OutgoingGroupUpdateMessage) message).isV2Group());
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    SignalDatabase.mms().markAsSending(messageId);
  }

  @Override
  public void onPushSend()
          throws IOException, MmsException, NoSuchMessageException,  RetryLaterException, InvalidKeyException, UndeliverableMessageException, UntrustedIdentityException
  {
    SignalLocalMetrics.GroupMessageSend.onJobStarted(messageId);

    long startTime = System.currentTimeMillis();

    MessageDatabase          database                   = SignalDatabase.mms();
    OutgoingMediaMessage     message                    = database.getOutgoingMessage(messageId);
    long                     threadId                   = database.getMessageRecord(messageId).getThreadId();
    Set<NetworkFailure>      existingNetworkFailures    = message.getNetworkFailures();
    Set<IdentityKeyMismatch> existingIdentityMismatches = message.getIdentityKeyMismatches();

    ApplicationDependencies.getJobManager().cancelAllInQueue(TypingSendJob.getQueue(threadId));

    if (database.isSent(messageId)) {
      log(TAG, String.valueOf(message.getSentTimeMillis()),  "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    Recipient groupRecipient = message.getRecipient().resolve();

    if (!groupRecipient.isPushGroup()) {
      throw new MmsException("Message recipient isn't a group!");
    }

    try {
      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sending message: " + messageId + ", Recipient: " + message.getRecipient().getId() + ", Thread: " + threadId + ", Attachments: " + buildAttachmentString(message.getAttachments()));

      if (!groupRecipient.resolve().isProfileSharing() && !database.isGroupQuitMessage(messageId)) {
//        RecipientUtil.shareProfileIfFirstSecureMessage(context, groupRecipient);//AA- not supported in this context
      }

      List<Recipient>   target;
      List<RecipientId> skipped = new ArrayList<>();

      if (filterRecipient != null) {
        target = Collections.singletonList(Recipient.resolved(filterRecipient));
      } else if (!existingNetworkFailures.isEmpty()) {
        target = Stream.of(existingNetworkFailures).map(nf -> nf.getRecipientId(context)).distinct().map(Recipient::resolved).toList();
      } else {
        GroupRecipientResult result = getGroupMessageRecipients(groupRecipient.requireGroupId(), messageId);

        target  = result.target;
        skipped = result.skipped;
      }

      List<SendMessageResult>   results1 = deliver(message, groupRecipient, target);
      //AA+ IMPORTANT reconstruct the list of recipients with SendMessageResult as per native signal behaviour
      //todo: revisit to figure out send failures inherint in list returned by 'deliver()'
      List<SendMessageResult> results = Stream.of(target).map(result -> SendMessageResult.success(new SignalServiceAddress(result.getServiceId().orElse(null), Optional.of(result.requireUfsrvUid())), Collections.EMPTY_LIST, false, false, System.currentTimeMillis() - startTime, Optional.empty())).toList();
      processGroupMessageResults(context, messageId, threadId, groupRecipient, message, results, target, skipped, existingNetworkFailures, existingIdentityMismatches);

      Log.i(TAG, JobLogger.format(this, "Finished send."));
    } catch (EncapsulatedExceptions e) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), e);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }

    SignalLocalMetrics.GroupMessageSend.onJobFinished(messageId);
  }

  @Override
  public void onRetry() {
    SignalLocalMetrics.GroupMessageSend.cancel(messageId);
    super.onRetry();
  }

  @Override
  public void onFailure() {
    SignalDatabase.mms().markAsSentFailed(messageId);
  }

  private List<SendMessageResult> deliver(OutgoingMediaMessage message, @NonNull Recipient groupRecipient, @NonNull List<Recipient> destinations)
          throws IOException, InvalidKeyException, EncapsulatedExceptions, UndeliverableMessageException, UntrustedIdentityException {
    try {
      rotateSenderCertificateIfNecessary();

      GroupDatabase                              groupDatabase      = SignalDatabase.groups();
      SignalServiceMessageSender                 messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
      GroupId.Push                               groupId            = groupRecipient.requireGroupId().requirePush();
      Optional<byte[]>                           profileKey         = getProfileKey(groupRecipient);
      Optional<SignalServiceDataMessage.Sticker> sticker            = getStickerFor(message);
      List<SharedContact>                        sharedContacts     = getSharedContactsFor(message);
      List<SignalServicePreview>                 previews           = getPreviewsFor(message);
      List<SignalServiceDataMessage.Mention>     mentions           = getMentionsFor(message.getMentions());
      List<SignalServiceAddress>                 addresses          = RecipientUtil.toSignalServiceAddressesFromResolved(context, destinations);
      List<Attachment>                           attachments        = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
      List<SignalServiceAttachment>              attachmentPointers = getAttachmentPointersFor(attachments);
      boolean                                    isRecipientUpdate = Stream.of(SignalDatabase.groupReceipts().getGroupReceiptInfo(messageId))
                                                                                                             .anyMatch(info -> info.getStatus() > GroupReceiptDatabase.STATUS_UNDELIVERED);

      List<Recipient>                            recipientsList = SignalDatabase.groups().getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF); //AA+ turned to true to support self initiated groups with self only
      Recipient                                  thisRecipient = UfsrvUserUtils.myOwnRecipient(false);

      //AA+ generate a mock list for this client sender, as in non e2ee only single message command is generated and sent to the server for the group
      List<Pair<Address, ServiceId>> meUfsrv = new LinkedList<>();
      meUfsrv.add(new Pair<>(UfsrvUserUtils.myOwnAddress(), thisRecipient.getServiceId().get()));
      List<SignalServiceAddress> ufsrvAddresses = getPushAddresses(meUfsrv);
      //

      List<Optional<UnidentifiedAccessPair>>    unidentifiedAccess = UnidentifiedAccessUtil.getAccessFor(context, destinations);

      if (message.getStoryType().isStory()) {
        Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupId);

        if (groupRecord.isPresent()) {
          GroupDatabase.V2GroupProperties v2GroupProperties = groupRecord.get().requireV2GroupProperties();
          SignalServiceGroupV2            groupContext      = SignalServiceGroupV2.newBuilder(v2GroupProperties.getGroupMasterKey())
                                                                                  .withRevision(v2GroupProperties.getGroupRevision())
                                                                                  .build();

          final SignalServiceStoryMessage storyMessage;
          if (message.getStoryType().isTextStory()) {
            storyMessage = SignalServiceStoryMessage.forTextAttachment(Recipient.self().getProfileKey(), groupContext, StorySendUtil.deserializeBodyToStoryTextAttachment(message, this::getPreviewsFor), message.getStoryType().isStoryWithReplies());
          } else if (!attachmentPointers.isEmpty()) {
            storyMessage = SignalServiceStoryMessage.forFileAttachment(Recipient.self().getProfileKey(), groupContext, attachmentPointers.get(0), message.getStoryType().isStoryWithReplies());
          } else {
            throw new UndeliverableMessageException("No attachment on non-text story.");
          }

          return GroupSendUtil.sendGroupStoryMessage(context, groupId.requireV2(), destinations, isRecipientUpdate, new MessageId(messageId, true), message.getSentTimeMillis(), storyMessage);
        } else {
          throw new UndeliverableMessageException("No group found! " + groupId);
        }
      } else if (message.isGroup()) { //AA group update message (as opposed to comms message)
        OutgoingGroupUpdateMessage groupMessage = (OutgoingGroupUpdateMessage) message;

        if (false && groupMessage.isV2Group()) {//AA+ false
          MessageGroupContext.GroupV2Properties properties = groupMessage.requireGroupV2Properties();
          GroupContextV2 groupContext = properties.getGroupContext();
          SignalServiceGroupV2.Builder builder = SignalServiceGroupV2.newBuilder(properties.getGroupMasterKey())
                                                                     .withRevision(groupContext.getRevision());

          ByteString groupChange = groupContext.getGroupChange();
          if (groupChange != null) {
            builder.withSignedGroupChange(groupChange.toByteArray());
          }

          SignalServiceGroupV2 group = builder.build();
          SignalServiceDataMessage groupDataMessage = SignalServiceDataMessage.newBuilder()
                                                                              .withTimestamp(message.getSentTimeMillis())
                                                                              .withExpiration(groupRecipient.getExpiresInSeconds())
                                                                              .asGroupMessage(group)
                                                                              .build();

          Log.i(TAG, JobLogger.format(this, "Beginning update send."));

        //AA todo: PORT for SenderKey resendable https://github.com/signalapp/Signal-Android/commit/f19033a7a2073c09b11d6c613cb0fcff00bfef36
         return GroupSendUtil.sendResendableDataMessage(context, groupRecipient.requireGroupId().requireV2(), destinations, isRecipientUpdate, ContentHint.IMPLICIT, new MessageId(messageId, true), groupDataMessage);//AA+ SenderKey pre alpha
        } else {
          MessageGroupContext.GroupV1Properties properties = groupMessage.requireGroupV1Properties();

          GroupContext groupContext = properties.getGroupContext();
          SignalServiceAttachment avatar = attachmentPointers.isEmpty() ? null : attachmentPointers.get(0);
          SignalServiceGroup.Type type = properties.isQuit() ? SignalServiceGroup.Type.QUIT : SignalServiceGroup.Type.UPDATE;
          List<SignalServiceAddress> members = Stream.of(groupContext.getMembersE164List())
                                                     .map(e164 -> new SignalServiceAddress(null, e164))
                                                     .toList();
          SignalServiceGroup group = new SignalServiceGroup(type, groupId.getDecodedId(), groupContext.getName(), members, avatar, 0, groupContext.getFenceMessage(), null);//AA+ last args
          SignalServiceDataMessage groupDataMessage = SignalServiceDataMessage.newBuilder()
                                                                              .withTimestamp(message.getSentTimeMillis())
                                                                              .withExpiration(groupRecipient.getExpiresInSeconds())
                                                                              .asGroupMessage(group)
                                                                              .withUfsrvCommand(null)//AA+
                                                                              .build();

          UfsrvCommandWire ufsrvCommandWireStored = message.getUfsrvCommandWire();//AA+
          Log.d(TAG, String.format("deliver: Delivering for group {name: '%s', fid: '%d'}", groupContext.getName(), ufsrvCommandWireStored.getFenceCommand().getFences(0).getFid()));
          //AA+ for ufsrcommand object
          //we need to retain a local copy (against the same messageId) so we can update views (eg. thread conversation). this mirrors the semantics for incoming where ufsrCommand is saved into incoming messages
          UfsrvCommand ufsrvCommandLocalCopy = new UfsrvCommand(groupContext.getFenceMessage(), false, isIntegritySensitive);
          MessageDatabase database = SignalDatabase.mms();
          database.updateMessageUfsrvCommand(messageId, ufsrvCommandLocalCopy.buildToSerialise());

          return messageSender.sendDataMessage(unidentifiedAccess, isRecipientUpdate, ContentHint.IMPLICIT, groupDataMessage, ufsrvAddresses, SignalServiceMessageSender.IndividualSendEvents.EMPTY, new UfsrvCommand(ufsrvCommandWireStored.getFenceCommand(), false, transportType, isIntegritySensitive));//AA+
        }
      } else if (message.isExpirationUpdate()) {//AA+ message.isExpirationUpdate() conditional
          SignalServiceGroup group = new SignalServiceGroup(groupId.getDecodedId(), 0, null);//AA++
          SignalServiceDataMessage groupMessage = SignalServiceDataMessage.newBuilder()
                                                                          .withTimestamp(message.getSentTimeMillis())
                                                                          .asGroupMessage(group)
                                                                          .withAttachments(attachmentPointers)
                                                                          .withBody(message.getBody())
                                                                          .withExpiration((int) (message.getExpiresIn() / 1000))
                                                                          .withViewOnce(message.isViewOnce())
                                                                          .asExpirationUpdate(message.isExpirationUpdate())
                                                                          .withProfileKey(profileKey.orElse(null))
                                                                          .withQuote(getQuoteFor(message).orElse(null))
                                                                          .withSticker(sticker.orElse(null))
                                                                          .withSharedContacts(sharedContacts)
                                                                          .withPreviews(previews)
                                                                          .withMentions(mentions)
                                                                          .withUfsrvCommand(null)//AA+
                                                                          .build();

          return messageSender.sendDataMessage(unidentifiedAccess, false, ContentHint.DEFAULT, groupMessage, ufsrvAddresses, SignalServiceMessageSender.IndividualSendEvents.EMPTY, new UfsrvCommand(message.getUfsrvCommandWire().getFenceCommand(), false, transportType, isIntegritySensitive));
          //          return GroupSendUtil.sendResendableDataMessage(context, groupRecipient.requireGroupId().requireV2(), destinations, isRecipientUpdate, ContentHint.RESENDABLE, messageId, true, groupMessage, null); //AA+ SenderKey pre alpha
        } else {
          //AA message to group with Type.DELIVER and groupId set only

          if (false && groupId.isV2()) {//AA+ false
            SignalServiceDataMessage.Builder builder = SignalServiceDataMessage.newBuilder()
                    .withTimestamp(message.getSentTimeMillis()); //AA+ factored into this block from above to isolate V2 stuff

            GroupUtil.setDataMessageGroupContext(context, builder, groupId, 0);//AA+ 0

            //AA+ factored into this block from below
            SignalServiceDataMessage.Builder groupMessageBuilder = builder.withAttachments(attachmentPointers)
                                                                          .withBody(message.getBody())
                                                                          .withExpiration((int)(message.getExpiresIn() / 1000))
                                                                          .withViewOnce(message.isViewOnce())
                                                                          .asExpirationUpdate(message.isExpirationUpdate())
                                                                          .withProfileKey(profileKey.orElse(null))
                                                                          .withQuote(getQuoteFor(message).orElse(null))
                                                                          .withSticker(sticker.orElse(null))
                                                                          .withSharedContacts(sharedContacts)
                                                                          .withPreviews(previews)
                                                                          .withMentions(mentions);

            if (message.getParentStoryId() != null) {
              try {
                MessageRecord storyRecord = SignalDatabase.mms().getMessageRecord(message.getParentStoryId().asMessageId().getId());
                Recipient     recipient   = storyRecord.isOutgoing() ? Recipient.self() : storyRecord.getIndividualRecipient();

                destinations = destinations.stream()
                                           .filter(r -> r.getStoriesCapability() == Recipient.Capability.SUPPORTED)
                                           .collect(java.util.stream.Collectors.toList());

                SignalServiceDataMessage.StoryContext storyContext = new SignalServiceDataMessage.StoryContext(recipient.requireServiceId(), storyRecord.getDateSent());
                groupMessageBuilder.withStoryContext(storyContext);

                Optional<SignalServiceDataMessage.Reaction> reaction = getStoryReactionFor(message, storyContext);
                if (reaction.isPresent()) {
                  groupMessageBuilder.withReaction(reaction.get());
                  groupMessageBuilder.withBody(null);
                }
              } catch (NoSuchMessageException e) {
                // The story has probably expired
                // TODO [stories] check what should happen in this case
                throw new UndeliverableMessageException(e);
              }
            } else {
              groupMessageBuilder.withQuote(getQuoteFor(message).orElse(null));
            }

            Log.i(TAG, JobLogger.format(this, "Beginning message send."));
            return messageSender.sendDataMessage(unidentifiedAccess, isRecipientUpdate, ContentHint.DEFAULT, groupMessageBuilder.build(), addresses, SignalServiceMessageSender.IndividualSendEvents.EMPTY, null);
  //          return GroupSendUtil.sendResendableDataMessage(context, groupRecipient.requireGroupId().requireV2(), destinations, isRecipientUpdate, ContentHint.RESENDABLE, messageId, true, groupMessage, null); //AA+ SenderKey pre alpha
          } else {
            ///////////////////
            Optional<GroupDatabase.GroupRecord> groupRecord = SignalDatabase.groups().getGroup(groupRecipient.requireGroupId());

            if (groupRecord.isPresent() && groupRecord.get().isAnnouncementGroup() && !groupRecord.get().isAdmin(Recipient.self())) {
              throw new UndeliverableMessageException("Non-admins cannot send messages in announcement groups!");
            }

            SignalServiceGroup group = new SignalServiceGroup(groupId.getDecodedId(), 0, null);

            //AA+
            boolean isE2ee = false;
            if (groupDatabase.isPairedGroup(groupId)) {
              //this returns true even if the other user was on invite list, so we check for other user if present and add them as recipient for e2e
              if (recipientsList.size() > 0) {
                isE2ee = true;
                meUfsrv = new LinkedList<>();

                Address addressOther = Stream.of(recipientsList).filterNot(r -> r.equals(thisRecipient)).collect(() -> new LinkedList<Address>(), (l, r) -> l.add(r.requireAddress())).getFirst();
                meUfsrv.add(new Pair<>(addressOther, Recipient.resolvedFromUfsrvUid(addressOther.serialize()).getServiceId().get()));

                ufsrvAddresses = getPushAddresses(meUfsrv);
              }
            }
            //

            Log.d(TAG, "deliver(): Delivering message for group (TYPE DELIVER): groupid:  " + groupId);

            SignalServiceDataMessage groupMessage = SignalServiceDataMessage.newBuilder()
                                                                            .withTimestamp(message.getSentTimeMillis())
                                                                            .asGroupMessage(group)
                                                                            .withAttachments(attachmentPointers)
                                                                            .withBody(message.getBody())
                                                                            .withExpiration((int) (message.getExpiresIn() / 1000))
                                                                            .withViewOnce(message.isViewOnce())
                                                                            .asExpirationUpdate(message.isExpirationUpdate())
                                                                            .withProfileKey(profileKey.orElse(null))
                                                                            .withQuote(getQuoteFor(message).orElse(null))
                                                                            .withSticker(sticker.orElse(null))
                                                                            .withSharedContacts(sharedContacts)
                                                                            .withPreviews(previews)
                                                                            .withMentions(mentions)
                                                                            .withUfsrvCommand(null)//AA+
                                                                            .build();

            //AA to do we need story context as per V2 above?
            //AA+
            GroupDatabase.GroupRecord groupRecordx = SignalDatabase.groups().getGroupByGroupId(groupId);
            MessageCommand.Builder messageCommandBuilder = MessageSender.buildMessageCommand(ApplicationContext.getInstance(),
                                                                                             Optional.ofNullable(recipientsList),
                                                                                             message.getBody(),
                                                                                             Optional.ofNullable(attachmentPointers),
                                                                                             getQuoteFor(message),
                                                                                             message.getSentTimeMillis(),
                                                                                             SignalServiceProtos.MessageCommand.CommandTypes.SAY_VALUE,
                                                                                             groupRecordx.getFid(),
                                                                                             isE2ee); //message record not built

            //NOTE: we have not saved a local copy of ufrsvcommand against the message id as did above
            List<SendMessageResult> results = messageSender.sendDataMessage(unidentifiedAccess, false, ContentHint.RESENDABLE, groupMessage, ufsrvAddresses, SignalServiceMessageSender.IndividualSendEvents.EMPTY, new UfsrvCommand(messageCommandBuilder, isE2ee, transportType, isIntegritySensitive));
  //          SignalDatabase.messageLog().insertIfPossible(groupMessage.getTimestamp(), destinations, results, ContentHint.RESENDABLE, messageId, true); //AA todo SenderKey not enabled https://github.com/signalapp/Signal-Android/commit/f19033a7a2073c09b11d6c613cb0fcff00bfef36
            return results;
          }
      }
    } catch (ServerRejectedException e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private List<SignalServiceAddress> getPushAddresses(List<Pair<Address, ServiceId>> addresses) {
    List<SignalServiceAddress> addressList = Stream.of(addresses).collect(()-> new LinkedList<>(), (l, p) -> l.add(new SignalServiceAddress(p.second(), p.first().serialize())));
    return addressList;
  }

  public static long getMessageId(@NonNull Data data) {
    return data.getLong(KEY_MESSAGE_ID);
  }

  static void processGroupMessageResults(@NonNull Context context,
                                         long messageId,
                                         long threadId,
                                         @Nullable Recipient groupRecipient,
                                         @NonNull OutgoingMediaMessage message,
                                         @NonNull List<SendMessageResult> results,
                                         @NonNull List<Recipient> target,
                                         @NonNull List<RecipientId> skipped,
                                         @NonNull Set<NetworkFailure> existingNetworkFailures,
                                         @NonNull Set<IdentityKeyMismatch> existingIdentityMismatches)
          throws RetryLaterException, ProofRequiredException
  {
    MmsDatabase database   = SignalDatabase.mms();
    RecipientAccessList accessList = new RecipientAccessList(target);

    List<NetworkFailure>             networkFailures           = Stream.of(results).filter(SendMessageResult::isNetworkFailure).map(result -> new NetworkFailure(accessList.requireIdByAddress(result.getAddress()))).toList();
    List<IdentityKeyMismatch>        identityMismatches        = Stream.of(results).filter(result -> result.getIdentityFailure() != null).map(result -> new IdentityKeyMismatch(accessList.requireIdByAddress(result.getAddress()), result.getIdentityFailure().getIdentityKey())).toList();
    ProofRequiredException           proofRequired             = Stream.of(results).filter(r -> r.getProofRequiredFailure() != null).findLast().map(SendMessageResult::getProofRequiredFailure).orElse(null);
    List<SendMessageResult>          successes                 = Stream.of(results).filter(result -> result.getSuccess() != null).toList();
    List<Pair<RecipientId, Boolean>> successUnidentifiedStatus = Stream.of(successes).map(result -> new Pair<>(accessList.requireIdByAddress(result.getAddress()), result.getSuccess().isUnidentified())).toList();
    Set<RecipientId>                 successIds                = Stream.of(successUnidentifiedStatus).map(Pair::first).collect(Collectors.toSet());
    List<NetworkFailure>             resolvedNetworkFailures   = Stream.of(existingNetworkFailures).filter(failure -> successIds.contains(failure.getRecipientId(context))).toList();
    List<IdentityKeyMismatch>        resolvedIdentityFailures  = Stream.of(existingIdentityMismatches).filter(failure -> successIds.contains(failure.getRecipientId(context))).toList();
    List<RecipientId>                unregisteredRecipients    = Stream.of(results).filter(SendMessageResult::isUnregisteredFailure).map(result -> RecipientId.from(result.getAddress())).toList();
    List<RecipientId>                skippedRecipients         = new ArrayList<>(unregisteredRecipients);

    skippedRecipients.addAll(skipped);

    if (networkFailures.size() > 0 || identityMismatches.size() > 0 || proofRequired != null || unregisteredRecipients.size() > 0) {
      Log.w(TAG,  String.format(Locale.US, "Failed to send to some recipients. Network: %d, Identity: %d, ProofRequired: %s, Unregistered: %d",
                                networkFailures.size(), identityMismatches.size(), proofRequired != null, unregisteredRecipients.size()));
    }

    RecipientDatabase recipientDatabase = SignalDatabase.recipients();
    for (RecipientId unregistered : unregisteredRecipients) {
      recipientDatabase.markUnregistered(unregistered);
    }

    existingNetworkFailures.removeAll(resolvedNetworkFailures);
    existingNetworkFailures.addAll(networkFailures);
    database.setNetworkFailures(messageId, existingNetworkFailures);

    existingIdentityMismatches.removeAll(resolvedIdentityFailures);
    existingIdentityMismatches.addAll(identityMismatches);
    database.setMismatchedIdentities(messageId, existingIdentityMismatches);

    SignalDatabase.groupReceipts().setUnidentified(successUnidentifiedStatus, messageId);

    if (proofRequired != null) {
      handleProofRequiredException(context, proofRequired, groupRecipient, threadId, messageId, true);
    }

    if (existingNetworkFailures.isEmpty() && networkFailures.isEmpty() && identityMismatches.isEmpty() && existingIdentityMismatches.isEmpty()) {
      database.markAsSent(messageId, true);

      markAttachmentsUploaded(messageId, message);

      if (skippedRecipients.size() > 0) {
        SignalDatabase.groupReceipts().setSkipped(skippedRecipients, messageId);
      }

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        database.markExpireStarted(messageId);
        ApplicationDependencies.getExpiringMessageManager()
                               .scheduleDeletion(messageId, true, message.getExpiresIn());
      }

      if (message.isViewOnce()) {
        SignalDatabase.attachments().deleteAttachmentFilesForViewOnceMessage(messageId);
      }

      if (message.getStoryType().isStory()) {
        ApplicationDependencies.getExpireStoriesManager().scheduleIfNecessary();
      }
    } else if (!identityMismatches.isEmpty()) {
      Log.w(TAG, "Failing because there were " + identityMismatches.size() + " identity mismatches.");
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);

      Set<RecipientId> mismatchRecipientIds = Stream.of(identityMismatches)
                                                    .map(mismatch -> mismatch.getRecipientId(context))
                                                    .collect(Collectors.toSet());

      RetrieveProfileJob.enqueue(mismatchRecipientIds);
    } else if (!networkFailures.isEmpty()) {
      long retryAfter = results.stream()
                               .filter(r -> r.getRateLimitFailure() != null)
                               .map(r -> r.getRateLimitFailure().getRetryAfterMilliseconds().orElse(-1L))
                               .max(Long::compare)
                               .orElse(-1L);
      Log.w(TAG, "Retrying because there were " + networkFailures.size() + " network failures. retryAfter: " + retryAfter);
      throw new RetryLaterException(retryAfter);
    }
  }

  private static @NonNull GroupRecipientResult getGroupMessageRecipients(@NonNull GroupId groupId, long messageId) {
    List<GroupReceiptInfo> destinations = SignalDatabase.groupReceipts().getGroupReceiptInfo(messageId);

    List<Recipient>   possible;

    if (!destinations.isEmpty()) {
      possible = Stream.of(destinations)
                       .map(GroupReceiptInfo::getRecipientId)
                       .map(Recipient::resolved)
                       .distinctBy(Recipient::getId)
                       .toList();
    } else {
      Log.w(TAG, "No destinations found for group message " + groupId + " using current group membership");
      possible = Stream.of(SignalDatabase.groups()
                                         .getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF))
                       .map(Recipient::resolve)
                       .distinctBy(Recipient::getId)
                       .toList();
    }

    List<Recipient>   eligible = RecipientUtil.getEligibleForSending(possible);
    List<RecipientId> skipped  = Stream.of(SetUtil.difference(possible, eligible)).map(Recipient::getId).toList();

    return new GroupRecipientResult(eligible, skipped);
  }

  private static class GroupRecipientResult {
    private final List<Recipient>   target;
    private final List<RecipientId> skipped;

    private GroupRecipientResult(@NonNull List<Recipient> target, @NonNull List<RecipientId> skipped) {
      this.target  = target;
      this.skipped = skipped;
    }
  }

  public static class Factory implements Job.Factory<PushGroupSendJob> {
    @Override
    public @NonNull PushGroupSendJob create(@NonNull Parameters parameters, @NonNull org.thoughtcrime.securesms.jobmanager.Data data) {
      String      raw    = data.getString(KEY_FILTER_RECIPIENT);
      RecipientId filter = raw != null ? RecipientId.from(raw) : null;

      return new PushGroupSendJob(parameters, data.getLong(KEY_MESSAGE_ID), filter, UfsrvCommand.TransportType.values()[data.getInt(KEY_TRANSPORT_TYPE)], data.getBoolean(KEY_INTEGRITY_SENSITIVE));//AA+
    }
  }
}