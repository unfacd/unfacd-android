package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import com.annimon.stream.Stream;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.util.SetUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.RevokedMessageRecord;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

public class RemoteDeleteSendJob extends BaseJob {

  public static final String KEY = "RemoteDeleteSendJob";

  private static final String TAG = Log.tag(RemoteDeleteSendJob.class);

  private static final String KEY_MESSAGE_ID              = "message_id";
  private static final String KEY_IS_MMS                  = "is_mms";
  private static final String KEY_RECIPIENTS              = "recipients";
  private static final String KEY_INITIAL_RECIPIENT_COUNT = "initial_recipient_count";

  private final long              messageId;
  private final boolean           isMms;
  private final List<RecipientId> recipients;
  private final int               initialRecipientCount;


  @WorkerThread
  public static @NonNull RemoteDeleteSendJob create(@NonNull Context context,
                                                    long messageId,
                                                    boolean isMms)
          throws NoSuchMessageException
  {
    MessageRecord message = isMms ? SignalDatabase.mms().getMessageRecord(messageId)
                                  : SignalDatabase.sms().getSmsMessage(messageId);

    Recipient conversationRecipient = SignalDatabase.threads().getRecipientForThreadId(message.getThreadId());

    if (conversationRecipient == null) {
      throw new AssertionError("We have a message, but couldn't find the thread!");
    }

    List<RecipientId> recipients;
    if (conversationRecipient.isDistributionList()) {
      recipients = SignalDatabase.storySends().getRemoteDeleteRecipients(message.getId(), message.getTimestamp());





    } else {
      recipients = conversationRecipient.isGroup() ? Stream.of(conversationRecipient.getParticipants()).map(Recipient::getId).toList()
                                                   : Stream.of(conversationRecipient.getId()).toList();
    }

    recipients.remove(Recipient.self().getId());

    return new RemoteDeleteSendJob(messageId,
                                   isMms,
                                   recipients,
                                   recipients.size(),
                                   new Parameters.Builder()
                                                 .setQueue(conversationRecipient.getId().toQueueKey())
                                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                                 .setMaxAttempts(Parameters.UNLIMITED)
                                                 .build());
  }

  private RemoteDeleteSendJob(long messageId,
                              boolean isMms,
                              @NonNull List<RecipientId> recipients,
                              int initialRecipientCount,
                              @NonNull Parameters parameters)
  {
    super(parameters);

    this.messageId             = messageId;
    this.isMms                 = isMms;
    this.recipients            = recipients;
    this.initialRecipientCount = initialRecipientCount;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
                             .putBoolean(KEY_IS_MMS, isMms)
                             .putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                             .putInt(KEY_INITIAL_RECIPIENT_COUNT, initialRecipientCount)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    MessageDatabase db;
    MessageRecord     message;

    if (isMms) {
      db      = SignalDatabase.mms();
      message = SignalDatabase.mms().getMessageRecord(messageId);
    } else {
      db      = SignalDatabase.sms();
      message = SignalDatabase.sms().getSmsMessage(messageId);
    }

    long       targetSentTimestamp  = message.getDateSent();
    Recipient conversationRecipient = SignalDatabase.threads().getRecipientForThreadId(message.getThreadId());

    if (conversationRecipient == null) {
      throw new AssertionError("We have a message, but couldn't find the thread!");
    }

    if (!message.isOutgoing()) {
      throw new IllegalStateException("Cannot delete a message that isn't yours!");
    }

    List<Recipient>   possible = Stream.of(recipients).map(Recipient::resolved).toList();
    List<Recipient>   eligible = RecipientUtil.getEligibleForSending(Stream.of(recipients).map(Recipient::resolved).toList());
    List<RecipientId> skipped  = Stream.of(SetUtil.difference(possible, eligible)).map(Recipient::getId).toList();

    GroupSendJobHelper.SendResult sendResult   = deliver(conversationRecipient, eligible, targetSentTimestamp, message.getUfsrvGid());//AA+

    for (Recipient completion : sendResult.completed) {
      recipients.remove(completion.getId());
    }

    for (RecipientId skip : skipped) {
      recipients.remove(skip);
    }

    List<RecipientId> totalSkips = Util.join(skipped, sendResult.skipped);

    Log.i(TAG, "Completed now: " + sendResult.completed.size() + ", Skipped: " + totalSkips.size() + ", Remaining: " + recipients.size());

    if (totalSkips.size() > 0 && isMms && message.getRecipient().isGroup()) {
      SignalDatabase.groupReceipts().setSkipped(totalSkips, messageId);
    }

    if (recipients.isEmpty()) {
      db.markAsSent(messageId, true);
    }
    else {
      Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
      throw new RetryLaterException();
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof NotPushRegisteredException) return false;
    return e instanceof IOException ||
            e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to send the reaction to all recipients! (" + (initialRecipientCount - recipients.size() + "/" + initialRecipientCount + ")") );
  }


  private @NonNull GroupSendJobHelper.SendResult deliver(@NonNull Recipient conversationRecipient, @NonNull List<Recipient> destinations, long targetSentTimestamp, long ufsrvGid)
          throws IOException, UntrustedIdentityException, InvalidKeyException
  {
    //AA+
   sendRevokedMessage(conversationRecipient, ufsrvGid, targetSentTimestamp);

    /*SignalServiceDataMessage.Builder dataMessageBuilder = SignalServiceDataMessage.newBuilder()
                                                                                  .withTimestamp(System.currentTimeMillis())
                                                                                  .withRemoteDelete(new SignalServiceDataMessage.RemoteDelete(targetSentTimestamp));

    if (conversationRecipient.isGroup()) {
      GroupUtil.setDataMessageGroupContext(context, dataMessageBuilder, conversationRecipient.requireGroupId().requirePush());
    }

    SignalServiceDataMessage dataMessage = dataMessageBuilder.build();
    List<SendMessageResult>  results     = GroupSendUtil.sendResendableDataMessage(context,
                                                                                   conversationRecipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                                                                   destinations,
                                                                                   false,
                                                                                   ContentHint.RESENDABLE,
                                                                                   new MessageId(messageId, isMms),
                                                                                   dataMessage);*/

    return new GroupSendJobHelper.SendResult(destinations, Collections.emptyList());
  }

  //AA+
  private List<SendMessageResult>  sendRevokedMessage(Recipient recipientGroup, long ufsrvGid, long targetSentTimestamp) throws UntrustedIdentityException, InvalidKeyException, IOException
  {
    UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor;
    commandArgDescriptor = new UfsrvCommandUtils.CommandArgDescriptor(MessageCommand.CommandTypes.REVOKE_VALUE, SignalServiceProtos.CommandArgs.SET_VALUE);

    MessageCommand.Builder messageCommandBuilder = buildUfsrvMessageCommand(recipientGroup, System.currentTimeMillis(), ufsrvGid,  targetSentTimestamp, commandArgDescriptor);
    UfsrvCommand ufsrvCommand = new UfsrvCommand(messageCommandBuilder, false, UfsrvCommand.TransportType.API_SERVICE, false);


    SignalServiceDataMessage ufsrvMessage = SignalServiceDataMessage.newBuilder()
                                                                    .withTimestamp(ufsrvCommand.getMessageCommandBuilder().getHeader().getWhen())//command not built yet
                                                                    .build();

    SendMessageResult result = ApplicationDependencies.getSignalServiceMessageSender().sendDataMessage(ufsrvMessage, Optional.empty(), SignalServiceMessageSender.IndividualSendEvents.EMPTY, ufsrvCommand);
    return new LinkedList<SendMessageResult>(){{add(result);}};
  }

  MessageCommand.Builder
  buildUfsrvMessageCommand(Recipient recipient, long timestamp, long gid, long targetSentTimestamp, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    MessageCommand.Builder  messageCommandBuilder = MessageCommand.newBuilder();
    CommandHeader.Builder   commandHeaderBuilder  = CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timestamp);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    RevokedMessageRecord.Builder revokedContentBuilder = SignalServiceProtos.RevokedMessageRecord.newBuilder();
    revokedContentBuilder.setGid(gid);
    revokedContentBuilder.setWhenSent(targetSentTimestamp);
    revokedContentBuilder.setFid(recipient.getUfsrvId());
    messageCommandBuilder.addRevoked(revokedContentBuilder.build());

    return  messageCommandBuilder;
  }
  //

  public static class Factory implements Job.Factory<RemoteDeleteSendJob> {

    @Override
    public @NonNull RemoteDeleteSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
      long              messageId             = data.getLong(KEY_MESSAGE_ID);
      boolean           isMms                 = data.getBoolean(KEY_IS_MMS);
      List<RecipientId> recipients            = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));
      int               initialRecipientCount = data.getInt(KEY_INITIAL_RECIPIENT_COUNT);

      return new RemoteDeleteSendJob(messageId, isMms, recipients, initialRecipientCount, parameters);
    }
  }
}