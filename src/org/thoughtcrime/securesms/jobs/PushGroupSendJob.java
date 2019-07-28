package org.thoughtcrime.securesms.jobs;


import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import com.annimon.stream.Collectors;
import org.thoughtcrime.securesms.logging.Log;
import com.annimon.stream.Stream;

import com.unfacd.android.ApplicationContext;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase.GroupReceiptInfo;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Preview;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Quote;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.api.push.exceptions.NetworkFailureException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


public class PushGroupSendJob extends PushSendJob {

  public static final String KEY = "PushGroupSendJob";

  private static final String TAG = PushGroupSendJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_FILTER_ADDRESS = "filter_address";

  private long   messageId;
  private String filterAddress;

  public PushGroupSendJob(long messageId, @NonNull Address destination, @Nullable Address filterAddress) {
    this(new Job.Parameters.Builder()
                 .setQueue(destination.toGroupString())
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .build(),
         messageId, filterAddress);

  }

  private PushGroupSendJob(@NonNull Job.Parameters parameters, long messageId, @Nullable Address filterAddress) {
    super(parameters);

    this.messageId     = messageId;
    this.filterAddress = filterAddress == null ? null :filterAddress.toPhoneString();
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Address destination, @Nullable Address filterAddress) {
    try {
      MmsDatabase          database    = DatabaseFactory.getMmsDatabase(context);
      OutgoingMediaMessage message     = database.getOutgoingMessage(messageId);
      List<Attachment>     attachments = new LinkedList<>();

      attachments.addAll(message.getAttachments());
      attachments.addAll(Stream.of(message.getLinkPreviews()).filter(p -> p.getThumbnail().isPresent()).map(p -> p.getThumbnail().get()).toList());
      attachments.addAll(Stream.of(message.getSharedContacts()).filter(c -> c.getAvatar() != null).map(c -> c.getAvatar().getAttachment()).withoutNulls().toList());

      List<AttachmentUploadJob> attachmentJobs = Stream.of(attachments).map(a -> new AttachmentUploadJob(((DatabaseAttachment) a).getAttachmentId())).toList();

      if (attachmentJobs.isEmpty()) {
        jobManager.add(new PushGroupSendJob(messageId, destination, filterAddress));
      } else {
        jobManager.startChain(attachmentJobs)
                .then(new PushGroupSendJob(messageId, destination, filterAddress))
                .enqueue();
      }

    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
            .putString(KEY_FILTER_ADDRESS, filterAddress)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    DatabaseFactory.getMmsDatabase(context).markAsSending(messageId);
  }

  @Override
  public void onPushSend()
          throws IOException, MmsException, NoSuchMessageException,  RetryLaterException, UntrustedIdentityException
  {
    MmsDatabase               database                   = DatabaseFactory.getMmsDatabase(context);
    OutgoingMediaMessage      message                    = database.getOutgoingMessage(messageId);//restores the serialised GroupContext/FenceCommand
    List<NetworkFailure>      existingNetworkFailures    = message.getNetworkFailures();
    List<IdentityKeyMismatch> existingIdentityMismatches = message.getIdentityKeyMismatches();

    if (database.isSent(messageId)) {
      Log.w(TAG, "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    try {
      Log.i(TAG, "Sending message: " + messageId);
      List<Address> target;

      if      (filterAddress != null)              target = Collections.singletonList(Address.fromSerialized(filterAddress));
      else if (!existingNetworkFailures.isEmpty()) target = Stream.of(existingNetworkFailures).map(NetworkFailure::getAddress).toList();
      else                                         target = getGroupMessageRecipients(message.getRecipient().getAddress().toGroupString(), messageId);

      List<SendMessageResult>   results                  = deliver(message, target);
      List<NetworkFailure>      networkFailures          = Stream.of(results).filter(SendMessageResult::isNetworkFailure).map(result -> new NetworkFailure(Address.fromSerialized(result.getAddress().getNumber()))).toList();
      List<IdentityKeyMismatch> identityMismatches       = Stream.of(results).filter(result -> result.getIdentityFailure() != null).map(result -> new IdentityKeyMismatch(Address.fromSerialized(result.getAddress().getNumber()), result.getIdentityFailure().getIdentityKey())).toList();
      Set<Address>              successAddresses         = Stream.of(results).filter(result -> result.getSuccess() != null).map(result -> Address.fromSerialized(result.getAddress().getNumber())).collect(Collectors.toSet());
      List<NetworkFailure>      resolvedNetworkFailures  = Stream.of(existingNetworkFailures).filter(failure -> successAddresses.contains(failure.getAddress())).toList();
      List<IdentityKeyMismatch> resolvedIdentityFailures = Stream.of(existingIdentityMismatches).filter(failure -> successAddresses.contains(failure.getAddress())).toList();
      List<SendMessageResult>   successes                = Stream.of(results).filter(result -> result.getSuccess() != null).toList();

      for (NetworkFailure resolvedFailure : resolvedNetworkFailures) {
        database.removeFailure(messageId, resolvedFailure);
        existingNetworkFailures.remove(resolvedFailure);
      }

      for (IdentityKeyMismatch resolvedIdentity : resolvedIdentityFailures) {
        database.removeMismatchedIdentity(messageId, resolvedIdentity.getAddress(), resolvedIdentity.getIdentityKey());
        existingIdentityMismatches.remove(resolvedIdentity);
      }

      if (!networkFailures.isEmpty()) {
        database.addFailures(messageId, networkFailures);
      }

      for (IdentityKeyMismatch mismatch : identityMismatches) {
        database.addMismatchedIdentity(messageId, mismatch.getAddress(), mismatch.getIdentityKey());
      }

      for (SendMessageResult success : successes) {
        DatabaseFactory.getGroupReceiptDatabase(context).setUnidentified(Address.fromSerialized(success.getAddress().getNumber()),
                                                                         messageId,
                                                                         success.getSuccess().isUnidentified());
      }

      if (existingNetworkFailures.isEmpty() && networkFailures.isEmpty() && identityMismatches.isEmpty() && existingIdentityMismatches.isEmpty()) {
        database.markAsSent(messageId, true);

        markAttachmentsUploaded(messageId, message.getAttachments());

        if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
          database.markExpireStarted(messageId);
          ApplicationContext.getInstance(context)
                  .getExpiringMessageManager()
                  .scheduleDeletion(messageId, true, message.getExpiresIn());
        }

        if (message.getRevealDuration() > 0) {
          database.markRevealStarted(messageId);
          ApplicationContext.getInstance(context)
                  .getRevealableMessageManager()
                  .scheduleIfNecessary();
        }
      } else if (!networkFailures.isEmpty()) {
        throw new RetryLaterException();
      } else if (!identityMismatches.isEmpty()) {
        database.markAsSentFailed(messageId);
        notifyMediaMessageDeliveryFailed(context, messageId);
      }
    } catch (EncapsulatedExceptions e) {
      Log.w(TAG, "Exception 2: "+e);
      List<NetworkFailure> failures = new LinkedList<>();

      for (NetworkFailureException nfe : e.getNetworkExceptions()) {
        failures.add(new NetworkFailure(Address.fromSerialized(nfe.getE164number())));
      }

      for (UntrustedIdentityException uie : e.getUntrustedIdentityExceptions()) {
        database.addMismatchedIdentity(messageId, Address.fromSerialized(uie.getE164Number()), uie.getIdentityKey());
      }

      database.addFailures(messageId, failures);

      if (e.getNetworkExceptions().isEmpty() && e.getUntrustedIdentityExceptions().isEmpty()) {
        database.markAsSent(messageId, true);
        markAttachmentsUploaded(messageId, message.getAttachments());

        if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
          database.markExpireStarted(messageId);
          ApplicationContext.getInstance(context)
                  .getExpiringMessageManager()
                  .scheduleDeletion(messageId, true, message.getExpiresIn());
        }
      } else {
        database.markAsSentFailed(messageId);
        notifyMediaMessageDeliveryFailed(context, messageId);
      }
    } catch (InvalidKeyException  | UndeliverableMessageException e) {
      Log.w(TAG, e);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof IOException)         return true;
    if (exception instanceof RetryLaterException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    DatabaseFactory.getMmsDatabase(context).markAsSentFailed(messageId);
  }

  private List<SendMessageResult>  deliver(OutgoingMediaMessage message, @NonNull List<Address> destinations)
          throws IOException, UntrustedIdentityException, UndeliverableMessageException, InvalidKeyException, EncapsulatedExceptions {
    rotateSenderCertificateIfNecessary();

    SignalServiceMessageSender                 messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
    String                                     groupId            = message.getRecipient().getAddress().toGroupString();
    Optional<byte[]>                           profileKey         = getProfileKey(message.getRecipient());
    Optional<Quote>                            quote              = getQuoteFor(message);
    Optional<SignalServiceDataMessage.Sticker> sticker            = getStickerFor(message);
    List<SharedContact>                        sharedContacts     = getSharedContactsFor(message);
    List<Preview>                              previews           = getPreviewsFor(message);
    List<SignalServiceAddress>                 addresses          = Stream.of(destinations).map(this::getPushAddress).toList();
    List<Attachment>                           attachments        = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
    List<SignalServiceAttachment>              attachmentPointers = getAttachmentPointersFor(attachments);
    boolean                                    isRecipientUpdate  = destinations.size() != DatabaseFactory.getGroupReceiptDatabase(context).getGroupReceiptInfo(messageId).size();

    List<Recipient>               recipientsList     = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupId, true); // turned to true to support self initiated groups with self only
    Recipient                     thisRecipient      = UfsrvUserUtils.myOwnRecipient(false);

    // generate a mock list for this client sender, as in non e2ee only single message command is generated and sent to the server for the group
    List<Address> meUfsrv=new LinkedList<>();
    meUfsrv.add(UfsrvUserUtils.myOwnAddress());
    List<SignalServiceAddress>    ufsrvAddresses=getPushAddresses(meUfsrv);
    //

    // refer to the block above as we build own single-destination, as opposed to every single user on the group (which is needed for e2e encryption)
//    if (filterAddress != null) addresses = getPushAddresses(filterAddress);
//    else                       addresses = getPushAddresses(recipients);

    List<Optional<UnidentifiedAccessPair>> unidentifiedAccess = Stream.of(addresses)
            .map(address -> Address.fromSerialized(address.getNumber()))
            .map(address -> Recipient.from(context, address, false))
            .map(recipient -> UnidentifiedAccessUtil.getAccessFor(context, recipient))
            .toList();

    // group update message (as opposed to comms message)
    if (message.isGroup()) {
      OutgoingGroupMediaMessage groupMessage     = (OutgoingGroupMediaMessage) message;
      GroupContext              groupContext     = groupMessage.getGroupContext();// this gets rebuilt

      UfsrvCommandWire ufsrvCommandWireStored=message.getUfsrvCommandWire();//

      Log.d(TAG, String.format("deliver: Delivering for group {name: '%s', fid: '%d'}", groupContext.getName(), ufsrvCommandWireStored.getFenceCommand().getFences(0).getFid()));

      SignalServiceAttachment   avatar           = attachmentPointers.isEmpty() ? null : attachmentPointers.get(0);

      SignalServiceGroup.Type   type             = groupMessage.isGroupQuit() ? SignalServiceGroup.Type.QUIT : SignalServiceGroup.Type.UPDATE;

      // for FenceCommand
      SignalServiceGroup        group            = new SignalServiceGroup(type, GroupUtil.getDecodedId(groupId), groupContext.getName(), groupContext.getMembersList(), avatar, groupContext.getFenceMessage());

      SignalServiceDataMessage  groupDataMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(message.getSentTimeMillis())
              .withExpiration(message.getRecipient().getExpireMessages())
              .asGroupMessage(group)
              .withUfsrvCommand(null)//
              .build();

      // for ufsrcommand object
      //we need to retain a local copy (against thes ame messageId) so we can update views (eg. thread conversation). this mirrors the semantics for incoming where ufsrCommand is saved into incoming messages
      UfsrvCommand                  ufsrvCommandLocalCopy = new UfsrvCommand(groupContext.getFenceMessage(), false);
      MmsDatabase                   database              = DatabaseFactory.getMmsDatabase(context);
      database.updateMessageUfsrvCommand(messageId, ufsrvCommandLocalCopy.buildToSerialise());

      return messageSender.sendMessage(ufsrvAddresses, unidentifiedAccess, false, groupDataMessage, new UfsrvCommand(ufsrvCommandWireStored.getFenceCommand(), false));
      //
    } else if (message.isExpirationUpdate()) {// message.isExpirationUpdate() conditional
      SignalServiceGroup       group        = new SignalServiceGroup(GroupUtil.getDecodedId(groupId));
      SignalServiceDataMessage groupMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(message.getSentTimeMillis())
              .asGroupMessage(group)
              .withAttachments(attachmentPointers)
              .withBody(message.getBody())
              .withExpiration((int)(message.getExpiresIn() / 1000))
              .withMessageTimer((int)(message.getRevealDuration() / 1000))
              .asExpirationUpdate(message.isExpirationUpdate())
              .withProfileKey(profileKey.orNull())
              .withQuote(quote.orNull())
              .withSticker(sticker.orNull())
              .withSharedContacts(sharedContacts)
              .withPreviews(previews)
              .withUfsrvCommand(null)//
              .build();
      return messageSender.sendMessage(ufsrvAddresses, unidentifiedAccess, false, groupMessage, new UfsrvCommand(message.getUfsrvCommandWire().getFenceCommand(), false));
    } else {
      // message to group with Type.DELIVER and groupId set only
      SignalServiceGroup       group        = new SignalServiceGroup(GroupUtil.getDecodedId(groupId));

      //
      GroupDatabase            groupDatabase = DatabaseFactory.getGroupDatabase(context);
      boolean                   isE2ee = false;
       if (groupDatabase.isPrivateGroupForTwo(groupId)) {
         //this returns true even if the other user was on invite list, so we check for other user if present and add them as recipient for e2e
         if (recipientsList.size()>0) {
           isE2ee = true;
           meUfsrv=new LinkedList<>();

           Address addressOther = Stream.of(recipientsList).filterNot(r -> r.equals(thisRecipient)).collect(() -> new LinkedList<Address>(), (l,r) -> l.add(r.getAddress())).getFirst();
           meUfsrv.add(addressOther);

           ufsrvAddresses=getPushAddresses(meUfsrv);
         }
       }
      //

      Log.d(TAG, "deliver(): Delivering message for group (TYPE DELIVER): groupid:  "+groupId);

      SignalServiceDataMessage groupMessage = SignalServiceDataMessage.newBuilder()
              .withTimestamp(message.getSentTimeMillis())
              .asGroupMessage(group)
              .withAttachments(attachmentPointers)
              .withBody(message.getBody())
              .withExpiration((int)(message.getExpiresIn() / 1000))
              .withMessageTimer((int)(message.getRevealDuration() / 1000))
              .asExpirationUpdate(message.isExpirationUpdate())
              .withProfileKey(profileKey.orNull())
              .withQuote(quote.orNull())
              .withSticker(sticker.orNull())
              .withSharedContacts(sharedContacts)
              .withPreviews(previews)
              .withUfsrvCommand(null)//
              .build();

      //
      GroupDatabase.GroupRecord groupRecord=DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).getGroupByGroupId(groupId);
      MessageCommand.Builder messageCommandBuilder=MessageSender.buildMessageCommand(ApplicationContext.getInstance(),
              Optional.fromNullable(recipientsList),
              message.getBody(),
              Optional.fromNullable(attachmentPointers),
              quote,
              message.getSentTimeMillis(),
              SignalServiceProtos.MessageCommand.CommandTypes.SAY_VALUE,
              groupRecord.getFid(),
               isE2ee); //message record not built

      //NOTE: we have not saved a local copy of ufrsvcommand against the message id as did above
      return messageSender.sendMessage(ufsrvAddresses, unidentifiedAccess, false, groupMessage, new UfsrvCommand(messageCommandBuilder, isE2ee));
    }
  }

  private List<SignalServiceAddress> getPushAddresses(List<Address> addresses) {
    return Stream.of(addresses).map(this::getPushAddress).toList();
  }

  private @NonNull List<Address> getGroupMessageRecipients(String groupId, long messageId) {
    List<GroupReceiptInfo> destinations = DatabaseFactory.getGroupReceiptDatabase(context).getGroupReceiptInfo(messageId);
    if (!destinations.isEmpty()) return Stream.of(destinations).map(GroupReceiptInfo::getAddress).toList();

    List<Recipient> members = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupId, false);
    return Stream.of(members).map(Recipient::getAddress).toList();
  }

  public static class Factory implements Job.Factory<PushGroupSendJob> {
    @Override
    public @NonNull PushGroupSendJob create(@NonNull Parameters parameters, @NonNull org.thoughtcrime.securesms.jobmanager.Data data) {
      String  address = data.getString(KEY_FILTER_ADDRESS);
      Address filter  = address != null ? Address.fromSerialized(data.getString(KEY_FILTER_ADDRESS)) : null;

      return new PushGroupSendJob(parameters, data.getLong(KEY_MESSAGE_ID), filter);
    }
  }
}