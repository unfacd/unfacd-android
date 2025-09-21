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

import android.content.Context;

import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.PushProcessEarlyMessagesJob;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.android.mms.LogTag.warn;


public class UfsrvReceiptUtils
{
  private static final String TAG = Log.tag(UfsrvReceiptUtils.class);


  //mimics
  static private SignalServiceReceiptMessage createReceiptMessage(@NonNull SignalServiceEnvelope envelope,
          @NonNull SignalServiceDataMessage message)
  {

    ReceiptCommand receiptCommand = message.getUfsrvCommand().getReceiptCommand();
    SignalServiceReceiptMessage.Type type;

    if  (receiptCommand.getType() == ReceiptCommand.CommandTypes.DELIVERY)  type = SignalServiceReceiptMessage.Type.DELIVERY;
    else if (receiptCommand.getType() == ReceiptCommand.CommandTypes.READ)  type = SignalServiceReceiptMessage.Type.READ;
    else if (receiptCommand.getType() == ReceiptCommand.CommandTypes.VIEWED)type = SignalServiceReceiptMessage.Type.VIEWED;
    else                                                                    type = SignalServiceReceiptMessage.Type.UNKNOWN;

    return new SignalServiceReceiptMessage(type, receiptCommand.getTimestampList(), envelope.getTimestamp(),
                                            UfsrvMessageUtils.UfsrvMessageIdentifierFromReceiptCommand(message.getUfsrvCommand().getReceiptCommand()));//AA+ last arg
}


  /**
   * Handle incoming receipt command message.

   * @throws MmsException
   */
  public static @Nullable
  Long processUfsrvReceiptCommand(@NonNull Context context,
                                  @NonNull SignalServiceContent content,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId,
                                  boolean outgoing) throws MmsException
  {
    ReceiptCommand   receiptCommand = message.getUfsrvCommand().getReceiptCommand();

    createReceiptMessage(envelope, message);

    if (receiptCommand == null) {
      Log.e(TAG, String.format("processUfsrvReceiptCommand (%d): ReceiptCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!receiptCommand.hasUidOriginator()) {
      Log.e(TAG, String.format("processUfsrvReceiptCommand: ERROR: ReceiptCommand doesn't have key fields provided (fence:'%b', originator:'%b')", receiptCommand.hasFid(), receiptCommand.hasUidOriginator()));
      return Long.valueOf(-1L);
    }

    long        groupId               = receiptCommand.getFid();
    Recipient   recipientOriginator   = Recipient.live(new UfsrvUid(receiptCommand.getUidOriginator().toByteArray()).toString()).get();
    Log.d(TAG, String.format("processUfsrvReceiptCommand: ReceiptCommand Received (fence:'%d', originatorUid:'%s')", groupId, receiptCommand.getUidOriginator()));

    switch (receiptCommand.getHeader().getCommand())
    {
      case ReceiptCommand.CommandTypes.READ_VALUE:
        return processReceiptCommandRead(context, content, envelope, message, recipientOriginator);

      case ReceiptCommand.CommandTypes.DELIVERY_VALUE:
        return processReceiptCommandDelivery(context, envelope, message, recipientOriginator);

      case ReceiptCommand.CommandTypes.VIEWED_VALUE:
        return processReceiptCommandViewed(context, content, envelope, message, recipientOriginator);

      default:
        Log.d(TAG, String.format("processUfsrvReceiptCommand (eid:'%d', type:'%d'): Received UNKNOWN RECEIPT COMMAND TYPE: fid:'%d'", receiptCommand.getEidList().get(0), receiptCommand.getHeader().getCommand(), receiptCommand.getFid()));
    }

    return (long) -1;
  }

  private static @Nullable
  Long processReceiptCommandRead(@NonNull Context context,
                                 @NonNull SignalServiceContent content,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Recipient recipientOriginator)
  {
    ReceiptCommand   receiptCommand = message.getUfsrvCommand().getReceiptCommand();

    if (!receiptCommand.hasFid()) {
      Log.e(TAG, String.format("processReceiptCommandRead: ERROR: ReceiptCommand doesn't have key fields provided"));
      return Long.valueOf(-1L);
    }

    SignalServiceReceiptMessage receiptMessage  = createReceiptMessage(envelope, message);

    handleReadReceipt(context, content, envelope, receiptMessage);

    return (long) -1;
  }

  //AA mirrored from MessageContentProcessor.handleReadReceipt
  static private void handleReadReceipt(@NonNull Context context,
                                        @NonNull SignalServiceContent content,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      List<MessageDatabase.SyncMessageId>msgIds = new LinkedList<>();

      for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier : message.getUfsrvMessageIdentifiers()) {
        Recipient recipient = Recipient.live(new UfsrvUid(messageIdentifier.uidOriginator).toString()).get();
        Log.w(TAG, String.format("Received encrypted read receipt: (XXXXX, %d, eid:'%d', fid:'%d')", messageIdentifier.timestamp, messageIdentifier.eid, messageIdentifier.fid));
        msgIds.add(new MessageDatabase.SyncMessageId(recipient.getId(), messageIdentifier.timestamp, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid));
        Collection<MessageDatabase.SyncMessageId> unhandled = SignalDatabase.mmsSms().incrementReadReceiptCounts(msgIds, envelope.getTimestamp());

        for (MessageDatabase.SyncMessageId id : unhandled) {
          warn(TAG, String.valueOf(content.getTimestamp()), "[handleReadReceipt] Could not find matching message! timestamp: " + id.getTimetamp() + "  author: " + id.getRecipientId());
          ApplicationDependencies.getEarlyMessageCache().store(id.getRecipientId(), id.getTimetamp(), content);
        }

        if (unhandled.size() > 0) {
          PushProcessEarlyMessagesJob.enqueue();
        }
      }
    }
  }

  private static @Nullable
  Long processReceiptCommandViewed(@NonNull Context context,
                                   @NonNull SignalServiceContent content,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   @NonNull Recipient recipientOriginator)
  {
    ReceiptCommand   receiptCommand = message.getUfsrvCommand().getReceiptCommand();

    if (!receiptCommand.hasFid()) {
      Log.e(TAG, String.format("processReceiptCommandViewed: ERROR: ReceiptCommand doesn't have key fields provided"));
      return Long.valueOf(-1L);
    }

    SignalServiceReceiptMessage receiptMessage  = createReceiptMessage(envelope, message);

    handleViewedReceipt(context, content, envelope, receiptMessage);

    return (long) -1;
  }

  //AA mirrored from MessageContentProcessor.handleViewedReceipt
  static private void handleViewedReceipt(@NonNull Context context,
                                          @NonNull SignalServiceContent content,
                                          @NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      List<MessageDatabase.SyncMessageId>msgIds = new LinkedList<>();

      for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier : message.getUfsrvMessageIdentifiers()) {
        Recipient recipient = Recipient.live(new UfsrvUid(messageIdentifier.uidOriginator).toString()).get();
        Log.w(TAG, String.format("Received encrypted viewed receipt: (XXXXX, %d, eid:'%d', fid:'%d')", messageIdentifier.timestamp, messageIdentifier.eid, messageIdentifier.fid));
        msgIds.add(new MessageDatabase.SyncMessageId(recipient.getId(), messageIdentifier.timestamp, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid));
        Collection<MessageDatabase.SyncMessageId> unhandled = SignalDatabase.mmsSms()
                                                                            .incrementViewedReceiptCounts(msgIds, envelope.getTimestamp());

        for (MessageDatabase.SyncMessageId id : unhandled) {
          warn(TAG, String.valueOf(content.getTimestamp()), "[handleViewedReceipt] Could not find matching message! timestamp: " + id.getTimetamp() + "  author: " + id.getRecipientId());
          ApplicationDependencies.getEarlyMessageCache().store(id.getRecipientId(), id.getTimetamp(), content);
        }

        if (unhandled.size() > 0) {
          PushProcessEarlyMessagesJob.enqueue();
        }
      }
    }
  }

  //AA+ PORTING NOTE mirrored from MessageContentProcessor.handleDeliveryReceipt
  static private void handleDeliveryReceipt(@NonNull Context context,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SignalServiceReceiptMessage message) {
    List<MessageDatabase.SyncMessageId>msgIds = new LinkedList<>();
    Collection<MessageDatabase.SyncMessageId> unhandled = Collections.emptyList();

    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier : message.getUfsrvMessageIdentifiers()) {
      Recipient recipient = Recipient.live(new UfsrvUid(messageIdentifier.uidOriginator).toString()).get();
      Log.w(TAG, String.format(Locale.getDefault(), "Received encrypted delivery receipt: (XXXXX, %d, eid:'%d', fid:'%d')", messageIdentifier.timestamp, messageIdentifier.eid, messageIdentifier.fid));
      msgIds.add(new MessageDatabase.SyncMessageId(recipient.getId(), messageIdentifier.timestamp, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid));
      unhandled = SignalDatabase.mmsSms().incrementDeliveryReceiptCounts(msgIds, System.currentTimeMillis());
      SignalDatabase.messageLog().deleteEntriesForRecipient(message.getTimestamps(), recipient.getId(), 1);//getSenderDevice()); //todo AA get device
    }

    if (unhandled.size() > 0) {// TODO: 24/07/2024 return is overwriten in the for loop --> refactor
      PushProcessEarlyMessagesJob.enqueue();
    }
    
//    SignalDatabase.messageLog().deleteEntriesForRecipient(message.getTimestamps(), senderRecipient.getId(), content.getSenderDevice()); // TODO: 24/07/2024  to be ported
  }

  //AA: this is a stub: not implemented
  private static @Nullable
  Long processReceiptCommandDelivery(@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @NonNull Recipient recipientOriginator)
  {
    SignalServiceReceiptMessage receiptMessage  = createReceiptMessage(envelope, message);

    handleDeliveryReceipt(context, envelope, receiptMessage);

    return (long) -1;
  }

  public static UfsrvCommand
  buildReadReceipt(Context context,
                   long timeSentInMillis,
                   UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier,
                   ReceiptCommand.CommandTypes receiptType)
  {
    ReceiptCommand.Builder receiptBuilder = MessageSender.buildReceiptCommand(context,
                                                                              null,
                                                                              timeSentInMillis,
                                                                              messageIdentifier,
                                                                              receiptType);
    return (new UfsrvCommand(receiptBuilder.build(), false, false));

  }

  public static UfsrvCommand
  buildReadReceipt(Context context,
                   long timeSentInMillis,
                   List<UfsrvMessageUtils.UfsrvMessageIdentifier> messageIdentifiers,
                   ReceiptCommand.CommandTypes receiptType)
  {
    ReceiptCommand.Builder receiptBuilder = MessageSender.buildReceiptCommand(context,
                                                                              null,
                                                                              timeSentInMillis,
                                                                              messageIdentifiers,
                                                                              receiptType);

    if (receiptBuilder != null) return (new UfsrvCommand(receiptBuilder.build(), false, false));

    return null;
  }
}
