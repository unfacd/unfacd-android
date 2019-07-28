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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;

import java.util.List;


public class UfsrvReceiptUtils
{
  private static final String TAG = UfsrvReceiptUtils.class.getSimpleName();


  //mimics
  static private SignalServiceReceiptMessage createReceiptMessage (@NonNull SignalServiceEnvelope envelope,
          @NonNull SignalServiceDataMessage message)
  {

    ReceiptCommand receiptCommand = message.getUfsrvCommand().getReceiptCommand();
    SignalServiceReceiptMessage.Type type;

    if  (receiptCommand.getType()== ReceiptCommand.CommandTypes.DELIVERY)  type =SignalServiceReceiptMessage.Type.DELIVERY;
    else if(receiptCommand.getType()== ReceiptCommand.CommandTypes.READ)type =SignalServiceReceiptMessage.Type.READ;
    else type =SignalServiceReceiptMessage.Type.UNKNOWN;

    return new SignalServiceReceiptMessage (type, receiptCommand.getTimestampList(), envelope.getTimestamp(),
                                            UfsrvMessageUtils.UfsrvMessageIdentifierFromReceiptCommand(message.getUfsrvCommand().getReceiptCommand()));// last arg
}

  public static @Nullable
  Long processUfsrvReceiptCommand(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Optional<Long> smsMessageId,
                               boolean outgoing) throws MmsException
  {
    ReceiptCommand   receiptCommand = message.getUfsrvCommand().getReceiptCommand();

    createReceiptMessage(envelope, message);

    if (receiptCommand==null) {
      Log.e(TAG, String.format("processUfsrvReceiptCommand (%d): ReceiptCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    if (!receiptCommand.hasUidOriginator()) {
      Log.e(TAG, String.format("processUfsrvReceiptCommand: ERROR: ReceiptCommand doesn't have key fields provided (fence:'%b', originator:'%b')", receiptCommand.hasFid(), receiptCommand.hasUidOriginator()));
      return Long.valueOf(-1L);
    }

    long        groupId               = receiptCommand.getFid();
    Recipient   recipientOriginator   = Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(receiptCommand.getUidOriginator().toByteArray()), false);
    Log.d(TAG, String.format("processUfsrvReceiptCommand: ReceiptCommand Received (fence:'%d', originatorUid:'%s')", groupId, receiptCommand.getUidOriginator()));

    switch (receiptCommand.getHeader().getCommand())
    {
      case ReceiptCommand.CommandTypes.READ_VALUE:
        return processReceiptCommandRead(context, envelope, message, recipientOriginator);

      case ReceiptCommand.CommandTypes.DELIVERY_VALUE:
        return processReceiptCommandDelivery(context, envelope, message, recipientOriginator);

      default:
        Log.d(TAG, String.format("processUfsrvReceiptCommand (eid:'%d', type:'%d'): Received UNKNOWN RECEIPT COMMAND TYPE: fid:'%d'", receiptCommand.getEidList().get(0), receiptCommand.getHeader().getCommand(), receiptCommand.getFid()));
    }

    return Long.valueOf(-1);
  }

  private static @Nullable
  Long processReceiptCommandRead(@NonNull Context context,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Recipient recipientOriginator)
  {
    ReceiptCommand   receiptCommand = message.getUfsrvCommand().getReceiptCommand();

    if (!receiptCommand.hasFid()) {
      Log.e(TAG, String.format("processUfsrvReceiptCommand: ERROR: ReceiptCommand doesn't have key fields provided"));
      return Long.valueOf(-1L);
    }

    SignalServiceReceiptMessage receiptMessage  = createReceiptMessage (envelope, message);

    handleReadReceipt(context, envelope, receiptMessage);

    return Long.valueOf(-1);
  }

  // mirrored from PushDecryptJob.handleReadReceipt
  static private void handleReadReceipt(@NonNull Context context,
                                        @NonNull SignalServiceEnvelope envelope,
                                        @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier : message.getUfsrvMessageIdentifiers()) {
        Recipient recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(messageIdentifier.uidOriginator), false);
        Log.w(TAG, String.format("Received encrypted read receipt: (XXXXX, %d, eid:'%d', fid:'%d')", messageIdentifier.timestamp, messageIdentifier.eid, messageIdentifier.fid));

        DatabaseFactory.getMmsSmsDatabase(context)
                .incrementReadReceiptCount(new MessagingDatabase.SyncMessageId(recipient.getAddress(), messageIdentifier.timestamp, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.eid), envelope.getTimestamp());
      }
    }
  }

  // mirrored from PushDecryptJob.handleDeliveryReceipt
  static private void handleDeliveryReceipt (@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull SignalServiceReceiptMessage message) {
    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier : message.getUfsrvMessageIdentifiers()) {
      Recipient recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(messageIdentifier.uidOriginator), false);
      Log.w(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d, eid:'%d', fid:'%d')", messageIdentifier.timestamp, messageIdentifier.eid, messageIdentifier.fid));

      DatabaseFactory.getMmsSmsDatabase(context)
              .incrementDeliveryReceiptCount(new MessagingDatabase.SyncMessageId(recipient.getAddress(), messageIdentifier.timestamp, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.eid), envelope.getTimestamp());
    }

  }

  //: this is a stub: not implemented
  private static @Nullable
  Long processReceiptCommandDelivery(@NonNull Context context,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Recipient recipientOriginator)
  {
    SignalServiceReceiptMessage receiptMessage  = createReceiptMessage (envelope, message);

    handleDeliveryReceipt(context, envelope, receiptMessage);

    return Long.valueOf(-1);
  }

  public static UfsrvCommand
  buildReadReceipt (Context context,
                    long timeSentInMillis,
                    UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier,
                    ReceiptCommand.CommandTypes receiptType)
  {
    ReceiptCommand.Builder receiptBuilder = MessageSender.buildReceiptCommand(context,
                                                                              null,
                                                                              timeSentInMillis,
                                                                              messageIdentifier,
                                                                              receiptType);
    return (new UfsrvCommand(receiptBuilder.build(), false));

  }

  public static UfsrvCommand
  buildReadReceipt (Context context,
                    long timeSentInMillis,
                    List<UfsrvMessageUtils.UfsrvMessageIdentifier> messageIdentifiers,
                    ReceiptCommand.CommandTypes receiptType)
  {
    ReceiptCommand.Builder receiptBuilder = MessageSender.buildReceiptCommand(context,
                                                                              null,
                                                                              timeSentInMillis,
                                                                              messageIdentifiers,
                                                                              receiptType);

    if (receiptBuilder!=null) return (new UfsrvCommand(receiptBuilder.build(), false));

    return null;
  }
}
