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
package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;
import android.util.Pair;

import com.annimon.stream.Stream;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteStatement;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UfsrvMessageUtils;

import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatchList;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.jobs.TrimThreadJob;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingGroupMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Database for storage of SMS messages.
 *
 * @author Moxie Marlinspike
 */


public class SmsDatabase extends MessagingDatabase {

  private static final String TAG = SmsDatabase.class.getSimpleName();

  public  static final String TABLE_NAME         = "sms";
  public  static final String PERSON             = "person";
  static final String DATE_RECEIVED      = "date";
  static final String DATE_SENT          = "date_sent";
  public  static final String PROTOCOL           = "protocol";
  public  static final String STATUS             = "status";
  public  static final String TYPE               = "type";
  public  static final String REPLY_PATH_PRESENT = "reply_path_present";
  public  static final String SUBJECT            = "subject";
  public  static final String SERVICE_CENTER     = "service_center";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " integer PRIMARY KEY, "                +
          THREAD_ID + " INTEGER, " + ADDRESS + " TEXT, " + ADDRESS_DEVICE_ID + " INTEGER DEFAULT 1, " + PERSON + " INTEGER, " +
          DATE_RECEIVED  + " INTEGER, " + DATE_SENT + " INTEGER, " + PROTOCOL + " INTEGER, " + READ + " INTEGER DEFAULT 0, " +
          STATUS + " INTEGER DEFAULT -1," + TYPE + " INTEGER, " + REPLY_PATH_PRESENT + " INTEGER, " +
          DELIVERY_RECEIPT_COUNT  + " INTEGER DEFAULT 0," + SUBJECT + " TEXT, " + BODY + " TEXT, " +
          MISMATCHED_IDENTITIES + " TEXT DEFAULT NULL, " + SERVICE_CENTER + " TEXT, " + SUBSCRIPTION_ID + " INTEGER DEFAULT -1, " +
          EXPIRES_IN + " INTEGER DEFAULT 0, " + EXPIRE_STARTED + " INTEGER DEFAULT 0, "+
          NOTIFIED + " DEFAULT 0, "+
          READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + UNIDENTIFIED + " INTEGER DEFAULT 0, " +
          UFSRV_COMMAND + " TEXT "+// defined in MmsSmsColumns.java
          ");";

  public static final String[] CREATE_INDEXS = {
          "CREATE INDEX IF NOT EXISTS sms_thread_id_index ON " + TABLE_NAME + " (" + THREAD_ID + ");",
          "CREATE INDEX IF NOT EXISTS sms_read_index ON " + TABLE_NAME + " (" + READ + ");",
          "CREATE INDEX IF NOT EXISTS sms_read_and_notified_and_thread_id_index ON " + TABLE_NAME + "(" + READ + "," + NOTIFIED + ","  + THREAD_ID + ");",
          "CREATE INDEX IF NOT EXISTS sms_type_index ON " + TABLE_NAME + " (" + TYPE + ");",
          "CREATE INDEX IF NOT EXISTS sms_date_sent_index ON " + TABLE_NAME + " (" + DATE_SENT + ");",
          "CREATE INDEX IF NOT EXISTS sms_thread_date_index ON " + TABLE_NAME + " (" + THREAD_ID + ", " + DATE_RECEIVED + ");"
  };

  private static final String[] MESSAGE_PROJECTION = new String[] {
          ID, THREAD_ID, ADDRESS, ADDRESS_DEVICE_ID, PERSON,
          DATE_RECEIVED + " AS " + NORMALIZED_DATE_RECEIVED,
          DATE_SENT + " AS " + NORMALIZED_DATE_SENT,
          PROTOCOL, READ, STATUS, TYPE,
          REPLY_PATH_PRESENT, SUBJECT, BODY, SERVICE_CENTER, DELIVERY_RECEIPT_COUNT,
          MISMATCHED_IDENTITIES, SUBSCRIPTION_ID, EXPIRES_IN, EXPIRE_STARTED, NOTIFIED, READ_RECEIPT_COUNT, UNIDENTIFIED,
          UFSRV_COMMAND//
  };

  private static final EarlyReceiptCache earlyDeliveryReceiptCache = new EarlyReceiptCache();
  private static final EarlyReceiptCache earlyReadReceiptCache     = new EarlyReceiptCache();

  public SmsDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  protected String getTableName() {
    return TABLE_NAME;
  }

  private void updateTypeBitmask(long id, long maskOff, long maskOn) {
    Log.w("MessageDatabase", "Updating ID: " + id + " to base type: " + maskOn);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME +
            " SET " + TYPE + " = (" + TYPE + " & " + (Types.TOTAL_MASK - maskOff) + " | " + maskOn + " )" +
            " WHERE " + ID + " = ?", new String[] {id+""});

    long threadId = getThreadIdForMessage(id);

    DatabaseFactory.getThreadDatabase(context).update(threadId, false);
    notifyConversationListeners(threadId);
  }

  public long getThreadIdForMessage(long id) {
    String sql        = "SELECT " + THREAD_ID + " FROM " + TABLE_NAME + " WHERE " + ID + " = ?";
    String[] sqlArgs  = new String[] {id+""};
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    Cursor cursor = null;

    try {
      cursor = db.rawQuery(sql, sqlArgs);
      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(0);
      else
        return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public int getMessageCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, null, null, null, null, null);

      if (cursor != null && cursor.moveToFirst()) return cursor.getInt(0);
      else                                        return 0;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public int getMessageCountForThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, THREAD_ID + " = ?",
              new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getInt(0);
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return 0;
  }

  public void markAsEndSession(long id) {
    updateTypeBitmask(id, Types.KEY_EXCHANGE_MASK, Types.END_SESSION_BIT);
  }

  public void markAsPreKeyBundle(long id) {
    updateTypeBitmask(id, Types.KEY_EXCHANGE_MASK, Types.KEY_EXCHANGE_BIT | Types.KEY_EXCHANGE_BUNDLE_BIT);
  }

  public void markAsInvalidVersionKeyExchange(long id) {
    updateTypeBitmask(id, 0, Types.KEY_EXCHANGE_INVALID_VERSION_BIT);
  }

  public void markAsSecure(long id) {
    updateTypeBitmask(id, 0, Types.SECURE_MESSAGE_BIT);
  }

  public void markAsInsecure(long id) {
    updateTypeBitmask(id, Types.SECURE_MESSAGE_BIT, 0);
  }

  public void markAsPush(long id) {
    updateTypeBitmask(id, 0, Types.PUSH_MESSAGE_BIT);
  }

  public void markAsForcedSms(long id) {
    updateTypeBitmask(id, Types.PUSH_MESSAGE_BIT, Types.MESSAGE_FORCE_SMS_BIT);
  }

  public void markAsDecryptFailed(long id) {
    updateTypeBitmask(id, Types.ENCRYPTION_MASK, Types.ENCRYPTION_REMOTE_FAILED_BIT);
  }

  public void markAsDecryptDuplicate(long id) {
    updateTypeBitmask(id, Types.ENCRYPTION_MASK, Types.ENCRYPTION_REMOTE_DUPLICATE_BIT);
  }

  public void markAsNoSession(long id) {
    updateTypeBitmask(id, Types.ENCRYPTION_MASK, Types.ENCRYPTION_REMOTE_NO_SESSION_BIT);
  }

  public void markAsUnsupportedProtocolVersion(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.UNSUPPORTED_MESSAGE_TYPE);
  }

  public void markAsInvalidMessage(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.INVALID_MESSAGE_TYPE);
  }

  public void markAsLegacyVersion(long id) {
    updateTypeBitmask(id, Types.ENCRYPTION_MASK, Types.ENCRYPTION_REMOTE_LEGACY_BIT);
  }

  public void markAsOutbox(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_OUTBOX_TYPE);
  }

  public void markAsPendingInsecureSmsFallback(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_PENDING_INSECURE_SMS_FALLBACK);
  }

  @Override
  public void markAsSent(long id, boolean isSecure) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENT_TYPE | (isSecure ? Types.PUSH_MESSAGE_BIT | Types.SECURE_MESSAGE_BIT : 0));
  }

  public void markAsSending(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENDING_TYPE);
  }

  public void markAsMissedCall(long id) {
    updateTypeBitmask(id, Types.TOTAL_MASK, Types.MISSED_CALL_TYPE);
  }

  @Override
  public void markUnidentified(long id, boolean unidentified) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(UNIDENTIFIED, unidentified ? 1 : 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});
  }

  @Override
  public void markExpireStarted(long id) {
    markExpireStarted(id, System.currentTimeMillis());
  }

  @Override
  public void markExpireStarted(long id, long startedAtTimestamp) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(EXPIRE_STARTED, startedAtTimestamp);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});

    long threadId = getThreadIdForMessage(id);

    DatabaseFactory.getThreadDatabase(context).update(threadId, false);
    notifyConversationListeners(threadId);
  }

  public void markStatus(long id, int status) {
    Log.w("MessageDatabase", "Updating ID: " + id + " to status: " + status);
    ContentValues contentValues = new ContentValues();
    contentValues.put(STATUS, status);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {id+""});

    long threadId = getThreadIdForMessage(id);
    DatabaseFactory.getThreadDatabase(context).update(threadId, false);
    notifyConversationListeners(threadId);
  }

  public void markAsSentFailed(long id) {
    updateTypeBitmask(id, Types.BASE_TYPE_MASK, Types.BASE_SENT_FAILED_TYPE);
  }

  public void markAsNotified(long id) {
    SQLiteDatabase database      = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues();

       contentValues.put(NOTIFIED, 1);

       database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});
  }

  public void incrementReceiptCount(SyncMessageId messageId, boolean deliveryReceipt, boolean readReceipt) {
    SQLiteDatabase database     = databaseHelper.getWritableDatabase();
    Cursor         cursor       = null;
    boolean        foundMessage = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, THREAD_ID, ADDRESS, TYPE},
                              DATE_SENT + " = ?", new String[] {String.valueOf(messageId.getTimetamp())},
                              null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(cursor.getLong(cursor.getColumnIndexOrThrow(TYPE)))) {
          Address theirAddress = messageId.getAddress();
          Address ourAddress   = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
          String  columnName   = deliveryReceipt ? DELIVERY_RECEIPT_COUNT : READ_RECEIPT_COUNT;

          if (ourAddress.equals(theirAddress)) {
            long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));

            database.execSQL("UPDATE " + TABLE_NAME +
                                     " SET " + columnName + " = " + columnName + " + 1 WHERE " +
                                     ID + " = ?",
                             new String[] {String.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ID)))});

            DatabaseFactory.getThreadDatabase(context).update(threadId, false);
            notifyConversationListeners(threadId);
            foundMessage = true;
          }
        }
      }

      if (!foundMessage) {
        if (deliveryReceipt) earlyDeliveryReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
        if (readReceipt)     earlyReadReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
      }

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public List<MarkedMessageInfo> setMessagesRead(long threadId) {
    return setMessagesRead(THREAD_ID + " = ? AND " + READ + " = 0", new String[] {String.valueOf(threadId)});
  }

  public List<MarkedMessageInfo> setAllMessagesRead() {
    return setMessagesRead(READ + " = 0", null);
  }

  // 1)get unread message ids for a given thread, 2)mark them as read, 3)send the ids upstream to the caller
  private List<MarkedMessageInfo> setMessagesRead(String where, String[] arguments) {
    SQLiteDatabase          database  = databaseHelper.getWritableDatabase();
    List<MarkedMessageInfo> results   = new LinkedList<>();
    Cursor                  cursor    = null;

    database.beginTransaction();
    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, ADDRESS, DATE_SENT, TYPE, EXPIRES_IN, EXPIRE_STARTED, UFSRV_COMMAND}, where, arguments, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        if (Types.isSecureType(cursor.getLong(3))) {
          //
          UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier = UfsrvMessageUtils.UfsrvMessageIdentifierFromEncoded(cursor.getString(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND)), cursor.getLong(2));
          SyncMessageId  syncMessageId  = new SyncMessageId(Address.fromSerialized(cursor.getString(1)), cursor.getLong(2), messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.eid);
          //
//          SyncMessageId  syncMessageId  = new SyncMessageId(Address.fromSerialized(cursor.getString(1)), cursor.getLong(2));
          ExpirationInfo expirationInfo = new ExpirationInfo(cursor.getLong(0), cursor.getLong(4), cursor.getLong(5), false);

          results.add(new MarkedMessageInfo(syncMessageId, expirationInfo));
        }
      }

      ContentValues contentValues = new ContentValues();
      contentValues.put(READ, 1);

      database.update(TABLE_NAME, contentValues, where, arguments);
      database.setTransactionSuccessful();
    } finally {
      if (cursor != null) cursor.close();
      database.endTransaction();
    }

    return results;
  }


  public List<Pair<Long, Long>> setTimestampRead(SyncMessageId messageId, long proposedExpireStarted) {
    SQLiteDatabase         database = databaseHelper.getWritableDatabase();
    List<Pair<Long, Long>> expiring = new LinkedList<>();
    Cursor                 cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, THREAD_ID, ADDRESS, TYPE, EXPIRES_IN, EXPIRE_STARTED},
                              DATE_SENT + " = ?", new String[] {String.valueOf(messageId.getTimetamp())},
                              null, null, null, null);

      while (cursor.moveToNext()) {
        Address theirAddress = messageId.getAddress();
        Address ourAddress   = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));

        if (ourAddress.equals(theirAddress)) {
          long id            = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
          long threadId      = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));
          long expiresIn     = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_IN));
          long expireStarted = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRE_STARTED));

          expireStarted = expireStarted > 0 ? Math.min(proposedExpireStarted, expireStarted) : proposedExpireStarted;

          ContentValues contentValues = new ContentValues();
          contentValues.put(READ, 1);

          if (expiresIn > 0) {
            contentValues.put(EXPIRE_STARTED, expireStarted);
            expiring.add(new Pair<>(id, expiresIn));
          }

          database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {cursor.getLong(cursor.getColumnIndexOrThrow(ID)) + ""});

          DatabaseFactory.getThreadDatabase(context).updateReadState(threadId);
          DatabaseFactory.getThreadDatabase(context).setLastSeen(threadId);
          notifyConversationListeners(threadId);
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return expiring;
  }

  public Pair<Long, Long> updateBundleMessageBody(long messageId, String body) {
    long type = Types.BASE_INBOX_TYPE | Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT;
    return updateMessageBodyAndType(messageId, body, Types.TOTAL_MASK, type);
  }

  public void updateMessageBody(long messageId, String body) {
    long type = 0;
    updateMessageBodyAndType(messageId, body, Types.ENCRYPTION_MASK, type);
  }

  private Pair<Long, Long> updateMessageBodyAndType(long messageId, String body, long maskOff, long maskOn) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET " + BODY + " = ?, " +
                       TYPE + " = (" + TYPE + " & " + (Types.TOTAL_MASK - maskOff) + " | " + maskOn + ") " +
                       "WHERE " + ID + " = ?",
               new String[] {body, messageId + ""});

    long threadId = getThreadIdForMessage(messageId);

    DatabaseFactory.getThreadDatabase(context).update(threadId, true);
    notifyConversationListeners(threadId);
    notifyConversationListListeners();

    return new Pair<>(messageId, threadId);
  }

  public Pair<Long, Long> copyMessageInbox(long messageId) {
    try {
      SmsMessageRecord record = getMessage(messageId);

      ContentValues contentValues = new ContentValues();
      contentValues.put(TYPE, (record.getType() & ~Types.BASE_TYPE_MASK) | Types.BASE_INBOX_TYPE);
      contentValues.put(ADDRESS, record.getIndividualRecipient().getAddress().serialize());
      contentValues.put(ADDRESS_DEVICE_ID, record.getRecipientDeviceId());
      contentValues.put(DATE_RECEIVED, System.currentTimeMillis());
      contentValues.put(DATE_SENT, record.getDateSent());
      contentValues.put(PROTOCOL, 31337);
      contentValues.put(READ, 0);
      contentValues.put(BODY, record.getBody());
      contentValues.put(THREAD_ID, record.getThreadId());
      contentValues.put(EXPIRES_IN, record.getExpiresIn());

      SQLiteDatabase db           = databaseHelper.getWritableDatabase();
      long           newMessageId = db.insert(TABLE_NAME, null, contentValues);

      DatabaseFactory.getThreadDatabase(context).update(record.getThreadId(), true);
      notifyConversationListeners(record.getThreadId());

      ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(record.getThreadId()));

      return new Pair<>(newMessageId, record.getThreadId());
    } catch (NoSuchMessageException e) {
      throw new AssertionError(e);
    }
  }

  public @NonNull Pair<Long, Long> insertReceivedCall(long fid, @NonNull Address address) {
    return insertCallLog(fid, address, Types.INCOMING_CALL_TYPE, false);
  }

  public @NonNull Pair<Long, Long> insertOutgoingCall(long fid, @NonNull Address address) {
    return insertCallLog(fid, address, Types.OUTGOING_CALL_TYPE, false);
  }

  //  fid
  public @NonNull Pair<Long, Long> insertMissedCall(long fid, @NonNull Address address) {
    return insertCallLog(fid, address, Types.MISSED_CALL_TYPE, true);
  }

  private @NonNull Pair<Long, Long> insertCallLog(long fid, @NonNull Address address, long type, boolean unread) {
    Recipient recipient = Recipient.from(context, address, true);
    long       threadId   = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, fid);//

    ContentValues values = new ContentValues(6);
    values.put(ADDRESS, address.serialize());
    values.put(ADDRESS_DEVICE_ID,  1);
    values.put(DATE_RECEIVED, System.currentTimeMillis());
    values.put(DATE_SENT, System.currentTimeMillis());
    values.put(READ, unread ? 0 : 1);
    values.put(TYPE, type);
    values.put(THREAD_ID, threadId);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    long messageId    = db.insert(TABLE_NAME, null, values);

    DatabaseFactory.getThreadDatabase(context).update(threadId, true);
    notifyConversationListeners(threadId);
    ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));

    if (unread) {
      DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
    }

    return new Pair<>(messageId, threadId);
  }

  //
  public @NonNull Pair<Long, Long> insertProfileLog(long fid, @NonNull Address address, String encodedUfsrvMsg, boolean unread) {
    return insertProfileTypeLog(fid, address,encodedUfsrvMsg, Types.PROFILE_TYPE, unread);
//    long       threadId   = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, fid);//
//
//    ContentValues values = new ContentValues(6);
//    values.put(ADDRESS, address.serialize());
//    values.put(ADDRESS_DEVICE_ID,  1);
//    values.put(DATE_RECEIVED, System.currentTimeMillis());
//    values.put(DATE_SENT, System.currentTimeMillis());
//    values.put(READ, unread ? 0 : 1);
//    values.put(TYPE, Types.PROFILE_TYPE);
//    values.put(THREAD_ID, threadId);
//    values.put(UFSRV_COMMAND, encodedUfsrvMsg);
//
//    SQLiteDatabase db = databaseHelper.getWritableDatabase();
//    long messageId    = db.insert(TABLE_NAME, null, values);
//
//    DatabaseFactory.getThreadDatabase(context).update(threadId, true);
//    notifyConversationListeners(threadId);
//    ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));
//
//    if (unread) {
//      DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
//    }
//
//    return new Pair<>(messageId, threadId);
  }

  public @NonNull Pair<Long, Long> insertGroupProfileLog(long fid, @NonNull Address address, String encodedUfsrvMsg, boolean unread)
  {
    return insertProfileTypeLog(fid, address, encodedUfsrvMsg, Types.GROUP_PROFILE_TYPE, unread);
  }

  public @NonNull Pair<Long, Long> insertProfileTypeLog(long fid, @NonNull Address address, String encodedUfsrvMsg, long profileType, boolean unread) {
    long       threadId   = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(null, fid);//

    ContentValues values = new ContentValues(6);
    values.put(ADDRESS, address.serialize());
    values.put(ADDRESS_DEVICE_ID,  1);
    values.put(DATE_RECEIVED, System.currentTimeMillis());
    values.put(DATE_SENT, System.currentTimeMillis());
    values.put(READ, unread ? 0 : 1);
    values.put(TYPE, profileType);
    values.put(THREAD_ID, threadId);
    values.put(UFSRV_COMMAND, encodedUfsrvMsg);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    long messageId    = db.insert(TABLE_NAME, null, values);

    DatabaseFactory.getThreadDatabase(context).update(threadId, true);
    notifyConversationListeners(threadId);
    ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));

    if (unread) {
      DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
    }

    return new Pair<>(messageId, threadId);
  }

  protected Optional<InsertResult> insertMessageInbox(IncomingTextMessage message, long type) {
    //
    SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire=message.isUfsrvCommandType()?message.getUfsrvCommand():null;
    SignalServiceProtos.FenceRecord fenceRecordTarget= UfsrvFenceUtils.getTargetFence(ufsrvCommandWire);
    //

    if (message.isJoined()) {
      type = (type & (Types.TOTAL_MASK - Types.BASE_TYPE_MASK)) | Types.JOINED_TYPE;
    } else if (message.isPreKeyBundle()) {
      type |= Types.KEY_EXCHANGE_BIT | Types.KEY_EXCHANGE_BUNDLE_BIT;
    } else if (message.isSecureMessage()) {
      type |= Types.SECURE_MESSAGE_BIT;
    } else if (message.isGroup()) {
      type |= Types.SECURE_MESSAGE_BIT;
      if      (((IncomingGroupMessage)message).isUpdate()) type |= Types.GROUP_UPDATE_BIT;
      else if (((IncomingGroupMessage)message).isQuit())   type |= Types.GROUP_QUIT_BIT;
    } else if (message.isEndSession()) {
      type |= Types.SECURE_MESSAGE_BIT;
      type |= Types.END_SESSION_BIT;
    }

    if (message.isPush())                type |= Types.PUSH_MESSAGE_BIT;
    if (message.isIdentityUpdate())      type |= Types.KEY_EXCHANGE_IDENTITY_UPDATE_BIT;

    if      (message.isIdentityVerified())    type |= Types.KEY_EXCHANGE_IDENTITY_VERIFIED_BIT;
    else if (message.isIdentityDefault())     type |= Types.KEY_EXCHANGE_IDENTITY_DEFAULT_BIT;

    Recipient recipient = Recipient.from(context, message.getSender(), true);

    Recipient groupRecipient;

    if (message.getGroupId() == null) {
      Log.w(TAG, "insertMessageInbox: Message did not have internal grouId defined... maynot be Group Message... trying to retrieve groupid");

      // for fence statesync messages from the serverthey'll arrive without GroupMessage.\/Group context we assemble/simulate this locally
      // For invitation messages from the server we'll have setup a group in the preceding steps which we intercept here. This group doesnnot have threadid yet
      //
      if (ufsrvCommandWire!=null && fenceRecordTarget!=null)
      {
        String                    groupId;
        GroupDatabase             database    = DatabaseFactory.getGroupDatabase(context);
        GroupDatabase.GroupRecord groupRecord = database.getGroupRecordByFid(fenceRecordTarget.getFid());

        groupId= groupRecord.getEncodedId();
        Log.d(TAG, String.format("insertMessageInbox: Retrieved GroupId: '%s' for fid:'%d',  fcname:'%s'", groupId, groupRecord.getFid(), groupRecord.getCname()));
        groupRecipient = Recipient.from(context, Address.fromSerialized(groupRecord.getEncodedId()), true);
      }
      else
      //
      groupRecipient = null;
    } else {
      groupRecipient = Recipient.from(context, message.getGroupId(), true);// for new group this will have been stored already
    }

    boolean    unread     = (org.thoughtcrime.securesms.util.Util.isDefaultSmsProvider(context) ||
            message.isSecureMessage() || message.isGroup() || message.isPreKeyBundle()) &&
            !message.isIdentityUpdate() && !message.isIdentityDefault() && !message.isIdentityVerified();

    long       threadId;

    if (groupRecipient == null) threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    //else                         threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipients); //-
    else
    {//  support for retrieving and storing the fid , corresponding with new thread.
      if (fenceRecordTarget!=null)
      {
        //threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipients, ufsrvCommandWire.getFenceCommand().getFences(0).getFid());getThreadIdOrCreateFor
        threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdOrCreateFor(groupRecipient, ThreadDatabase.DistributionTypes.DEFAULT, fenceRecordTarget.getFid());

        // this IMPORTANT insertion is now done by the respective InsertxxxMessage() for each Database.
        //DatabaseFactory.getThreadDatabase(context).updateUfsrvCommandWire(threadId, System.currentTimeMillis(), message.getUfsrvCommandEncoded(), null);
      }
      else
      {
        Log.e(TAG, "insertMessageInbox: Incoming message doesn not contain valid TARGET FENCE: Falling back to original path");
        threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
      }
    }

    ContentValues values = new ContentValues(6);
    values.put(ADDRESS, message.getSender().serialize());
    values.put(ADDRESS_DEVICE_ID,  message.getSenderDeviceId());
    values.put(DATE_RECEIVED, System.currentTimeMillis());
    values.put(DATE_SENT, message.getSentTimestampMillis());
    values.put(PROTOCOL, message.getProtocol());
    values.put(READ, unread ? 0 : 1);
    values.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    values.put(EXPIRES_IN, message.getExpiresIn());
    values.put(UNIDENTIFIED, message.isUnidentified());

    if (!TextUtils.isEmpty(message.getPseudoSubject()))
      values.put(SUBJECT, message.getPseudoSubject());

    values.put(REPLY_PATH_PRESENT, message.isReplyPathPresent());
    values.put(SERVICE_CENTER, message.getServiceCenterAddress());
    values.put(BODY, message.getMessageBody());
    values.put(TYPE, type);
    values.put(THREAD_ID, threadId);

    // get it back with getUfsrvCommand(long messageId)
    values.put(UFSRV_COMMAND, message.getUfsrvCommandEncoded());

    if (message.isPush() && isDuplicate(message, threadId)) {
      Log.w(TAG, "Duplicate message (" + message.getSentTimestampMillis() + "), ignoring...");
      return Optional.absent();
    } else {
      SQLiteDatabase db        = databaseHelper.getWritableDatabase();
      long           messageId = db.insert(TABLE_NAME, null, values);

      if (unread) {
        DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
      }

      if (!message.isIdentityUpdate() && !message.isIdentityVerified() && !message.isIdentityDefault()) {
        DatabaseFactory.getThreadDatabase(context).update(threadId, true);
      }

      if (message.getSubscriptionId() != -1) {
         DatabaseFactory.getRecipientDatabase(context).setDefaultSubscriptionId(recipient, message.getSubscriptionId());
      }

      notifyConversationListeners(threadId);
      if (!message.isIdentityUpdate() && !message.isIdentityVerified() && !message.isIdentityDefault()) {
        ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));
      }

      Log.d(TAG, String.format("insertMessageInbox: Inserted new message (messageId:'%d' into threadId:'%d' fid:'%d'", messageId, threadId, fenceRecordTarget!=null?fenceRecordTarget.getFid():-1));
      return Optional.of(new InsertResult(messageId, threadId));
    }
//    SQLiteDatabase db = databaseHelper.getWritableDatabase();
//    long messageId    = db.insert(TABLE_NAME, null, values);
//
//    if (unread) {
//      DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
//    }
//
//    if (!message.isIdentityUpdate())
//    {
//      DatabaseFactory.getThreadDatabase(context).update(threadId, true);
//    }
//    notifyConversationListeners(threadId);
//    ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));
//
//    Log.d(TAG, String.format("insertMessageInbox: Inserted new message (messageId:'%d' into threadId:'%d' fid:'%d'", messageId, threadId, fenceRecordTarget!=null?fenceRecordTarget.getFid():-1));
//
//    return new Pair<>(messageId, threadId);
  }

  public Optional<InsertResult> insertMessageInbox(IncomingTextMessage message) {
    return insertMessageInbox(message, Types.BASE_INBOX_TYPE);
  }

  public long insertMessageOutbox(long threadId, OutgoingTextMessage message,
                                  boolean forceSms, long date, InsertListener insertListener)
  {
    long type = Types.BASE_SENDING_TYPE;

    if      (message.isKeyExchange())   type |= Types.KEY_EXCHANGE_BIT;
    else if (message.isSecureMessage()) type |= (Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT);
    else if (message.isEndSession())    type |= Types.END_SESSION_BIT;
    if      (forceSms)                  type |= Types.MESSAGE_FORCE_SMS_BIT;

    if      (message.isIdentityVerified()) type |= Types.KEY_EXCHANGE_IDENTITY_VERIFIED_BIT;
    else if (message.isIdentityDefault())  type |= Types.KEY_EXCHANGE_IDENTITY_DEFAULT_BIT;

    Address            address               = message.getRecipient().getAddress();
    Map<Address, Long> earlyDeliveryReceipts = earlyDeliveryReceiptCache.remove(date);
    Map<Address, Long> earlyReadReceipts     = earlyReadReceiptCache.remove(date);

    ContentValues contentValues = new ContentValues(6);
    contentValues.put(ADDRESS, address.serialize());
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(BODY, message.getMessageBody());
    contentValues.put(DATE_RECEIVED, System.currentTimeMillis());
    contentValues.put(DATE_SENT, date);
    contentValues.put(READ, 1);
    contentValues.put(TYPE, type);
    contentValues.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    contentValues.put(EXPIRES_IN, message.getExpiresIn());
    contentValues.put(DELIVERY_RECEIPT_COUNT, Stream.of(earlyDeliveryReceipts.values()).mapToLong(Long::longValue).sum());
    contentValues.put(READ_RECEIPT_COUNT, Stream.of(earlyReadReceipts.values()).mapToLong(Long::longValue).sum());

    SQLiteDatabase db        = databaseHelper.getWritableDatabase();
    long           messageId = db.insert(TABLE_NAME, ADDRESS, contentValues);

    if (insertListener != null) {
      insertListener.onComplete();
    }

    Log.d(TAG, String.format("insertMessageOutbox: Inserted new message (messageId:'%d', date_sent:'%d', into threadId:'%d' user:'%s'", messageId, date, threadId, address));

    if (!message.isIdentityVerified() && !message.isIdentityDefault()) {
      DatabaseFactory.getThreadDatabase(context).update(threadId, true);
      DatabaseFactory.getThreadDatabase(context).setLastSeen(threadId);
    }

    // todo: update the ufsrvCommand in the outgoing message in ThreadDatabase
    ThreadDatabase threadDatabase=DatabaseFactory.getThreadDatabase(context);
    //threadDatabase.updateUfsrvCommandWire(threadId, -1/*fid not used*/, message.);
    //

    DatabaseFactory.getThreadDatabase(context).setHasSent(threadId, true);

    notifyConversationListeners(threadId);

    if (!message.isIdentityVerified() && !message.isIdentityDefault()) {
      ApplicationContext.getInstance(context).getJobManager().add(new TrimThreadJob(threadId));
    }

    return messageId;
  }

  Cursor getMessages(int skip, int limit) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    return db.query(TABLE_NAME, MESSAGE_PROJECTION, null, null, null, null, ID, skip + "," + limit);
  }

  Cursor getOutgoingMessages() {
    String outgoingSelection = TYPE + " & "  + Types.BASE_TYPE_MASK + " = " + Types.BASE_OUTBOX_TYPE;
    SQLiteDatabase db        = databaseHelper.getReadableDatabase();
    return db.query(TABLE_NAME, MESSAGE_PROJECTION, outgoingSelection, null, null, null, null);
  }

//  public Cursor getDecryptInProgressMessages() {
//    String where       = TYPE + " & " + (Types.ENCRYPTION_ASYMMETRIC_BIT) + " != 0";
//    SQLiteDatabase db  = databaseHelper.getReadableDatabase();
//    return db.query(TABLE_NAME, MESSAGE_PROJECTION, where, null, null, null, null);
//  }

//  new String[]{String.valueOf(message.getSentTimestampMillis()), message.getSender(), String.valueOf(threadId)},

  public Cursor getExpirationStartedMessages() {
    String         where = EXPIRE_STARTED + " > 0";
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    return db.query(TABLE_NAME, MESSAGE_PROJECTION, where, null, null, null, null);
  }

  public SmsMessageRecord getMessage(long messageId) throws NoSuchMessageException {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, MESSAGE_PROJECTION, ID_WHERE, new String[]{messageId + ""}, null, null, null);
    Reader         reader = new Reader(cursor);
    SmsMessageRecord record = reader.getNext();

    reader.close();

    if (record == null) throw new NoSuchMessageException("No message for ID: " + messageId);
    else                return record;
  }

  public Cursor getMessageCursor(long messageId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor = db.query(TABLE_NAME, MESSAGE_PROJECTION, ID_WHERE, new String[] {messageId + ""}, null, null, null);
    setNotifyConverationListeners(cursor, getThreadIdForMessage(messageId));
    return cursor;
  }

//
  public SignalServiceProtos.UfsrvCommandWire getUfsrvCommand(long messageId) {
    SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire=null;
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{SmsDatabase.UFSRV_COMMAND + ""}, ID_WHERE, new String[]{messageId + ""},
            null, null, null);

    setNotifyConverationListeners(cursor, getThreadIdForMessage(messageId));

    String ufsrvCommandEncoded = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.UFSRV_COMMAND));
    if (cursor != null && cursor.moveToFirst())
    {
      try
      {
        ufsrvCommandWire = SignalServiceProtos.UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
      }
      catch (IOException e)
      {
        Log.d(TAG, e.getMessage());
      }
      finally {
        if (cursor != null)
          cursor.close();
      }
    }
    else
    {
      Log.d(TAG, "getUfsrvCommand: cursor was empty...");
    }
    return ufsrvCommandWire;
  }

  public SignalServiceProtos.UfsrvCommandWire getUfsrvCommand(Cursor cursor) {
    SignalServiceProtos.UfsrvCommandWire ufsrvCommandWire=null;

    String ufsrvCommandEncoded = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.UFSRV_COMMAND));
    if (cursor != null && cursor.moveToFirst())
    {
      try
      {
        ufsrvCommandWire = SignalServiceProtos.UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
      }
      catch (IOException e)
      {
        Log.d(TAG, e.getMessage());
      }
    }
    else
    {
      Log.d(TAG, "getUfsrvCommand: cursor was empty...");
    }

    return ufsrvCommandWire;
  }
//

  public boolean deleteMessage(long messageId) {
    Log.w("MessageDatabase", "Deleting: " + messageId);
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    long threadId     = getThreadIdForMessage(messageId);
    db.delete(TABLE_NAME, ID_WHERE, new String[] {messageId+""});
    boolean threadDeleted = DatabaseFactory.getThreadDatabase(context).update(threadId, false);
    notifyConversationListeners(threadId);
    return threadDeleted;
  }

  public void ensureMigration() {
    databaseHelper.getWritableDatabase();
  }

  private boolean isDuplicate(IncomingTextMessage message, long threadId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, null, DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
                                             new String[]{String.valueOf(message.getSentTimestampMillis()), message.getSender().serialize(), String.valueOf(threadId)},
            null, null, null, "1");

    try {
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  /*package */void deleteThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, THREAD_ID + " = ?", new String[] {threadId+""});
  }

  /*package*/void deleteMessagesInThreadBeforeDate(long threadId, long date) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = THREAD_ID + " = ? AND (CASE " + TYPE;

    for (long outgoingType : Types.OUTGOING_MESSAGE_TYPES) {
      where += " WHEN " + outgoingType + " THEN " + DATE_SENT + " < " + date;
    }

    where += (" ELSE " + DATE_RECEIVED + " < " + date + " END)");

    db.delete(TABLE_NAME, where, new String[] {threadId + ""});
  }

  /*package*/ void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = "";

    for (long threadId : threadIds) {
      where += THREAD_ID + " = '" + threadId + "' OR ";
    }

    where = where.substring(0, where.length() - 4);

    db.delete(TABLE_NAME, where, null);
  }

  /*package */ void deleteAllThreads() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
  }

  /*package*/ SQLiteDatabase beginTransaction() {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();
    return database;
  }

  /*package*/ void endTransaction(SQLiteDatabase database) {
    database.setTransactionSuccessful();
    database.endTransaction();
  }

  /*package*/ SQLiteStatement createInsertStatement(SQLiteDatabase database) {
    return database.compileStatement("INSERT INTO " + TABLE_NAME + " (" + ADDRESS + ", " +
            PERSON + ", " +
            DATE_SENT + ", " +
            DATE_RECEIVED  + ", " +
            PROTOCOL + ", " +
            READ + ", " +
            STATUS + ", " +
            TYPE + ", " +
            REPLY_PATH_PRESENT + ", " +
            SUBJECT + ", " +
            BODY + ", " +
            SERVICE_CENTER +
            ", " + THREAD_ID + ") " +
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
  }

  public static class Status {
    public static final int STATUS_NONE     = -1;
    public static final int STATUS_COMPLETE  = 0;
    public static final int STATUS_PENDING   = 0x20;
    public static final int STATUS_FAILED    = 0x40;
  }

  public Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public OutgoingMessageReader readerFor(OutgoingTextMessage message, long threadId) {
    return new OutgoingMessageReader(message, threadId);
  }

  public class OutgoingMessageReader {

    private final OutgoingTextMessage message;
    private final long                id;
    private final long                threadId;

    public OutgoingMessageReader(OutgoingTextMessage message, long threadId) {
      this.message  = message;
      this.threadId = threadId;
      this.id       = new SecureRandom().nextLong();
    }

    public MessageRecord getCurrent() {
      return new SmsMessageRecord(id, message.getMessageBody(),
                                  message.getRecipient(), message.getRecipient(),
                                  1, System.currentTimeMillis(), System.currentTimeMillis(),
                                  0, message.isSecureMessage() ? MmsSmsColumns.Types.getOutgoingEncryptedMessageType() : MmsSmsColumns.Types.getOutgoingSmsMessageType(),
                                  threadId, 0, new LinkedList<IdentityKeyMismatch>(),
                                  message.getSubscriptionId(), message.getExpiresIn(),
                                  System.currentTimeMillis(), 0, false,
                                  message.getUfsrvCommandWire());//
    }
  }

  public class Reader {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public SmsMessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public int getCount() {
      if (cursor == null) return 0;
      else                return cursor.getCount();
    }

    public SmsMessageRecord getCurrent() {
      long    messageId            = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.ID));
      Address address              = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS)));
      int     addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.ADDRESS_DEVICE_ID));
      long    type                 = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.TYPE));
      long    dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.NORMALIZED_DATE_RECEIVED));
      long    dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.NORMALIZED_DATE_SENT));
      long    threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.THREAD_ID));
      int     status               = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.STATUS));
      int     deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.DELIVERY_RECEIPT_COUNT));
      int     readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.READ_RECEIPT_COUNT));
      String  mismatchDocument     = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.MISMATCHED_IDENTITIES));
      int     subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.SUBSCRIPTION_ID));
      long    expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.EXPIRES_IN));
      long    expireStarted        = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.EXPIRE_STARTED));
      String  body                 = cursor.getString(cursor.getColumnIndexOrThrow(SmsDatabase.BODY));
      boolean unidentified         = cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.UNIDENTIFIED)) == 1;

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }


      // inflate the serialised/stored ufsrvCommand
      SignalServiceProtos.UfsrvCommandWire ufsrvCommand=null;

      String ufsrvCommandEncoded=cursor.getString(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND));
      if (!TextUtils.isEmpty(ufsrvCommandEncoded))
      {
        try
        {
          ufsrvCommand = SignalServiceProtos.UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
          //Log.d(TAG, "getCurrent: Inflated UfsrvCommand.. type:'"+ufsrvCommand.getUfsrvtype().name()+"'. Has fence: "+ufsrvCommand.hasFenceCommand());
        }
        catch (IOException e)
        {
          Log.d(TAG, e.getMessage());
        }
      }
      //

      List<IdentityKeyMismatch> mismatches = getMismatches(mismatchDocument);
      Recipient                 recipient  = Recipient.from(context, address, true);

      return new SmsMessageRecord(messageId, body, recipient,
                                  recipient,
                                  addressDeviceId,
                                  dateSent, dateReceived, deliveryReceiptCount, type,
                                  threadId, status, mismatches, subscriptionId,
                                  expiresIn, expireStarted,
                                  readReceiptCount, unidentified,
                                                      ufsrvCommand);//
    }

    private List<IdentityKeyMismatch> getMismatches(String document) {
      try {
        if (!TextUtils.isEmpty(document)) {
          return JsonUtils.fromJson(document, IdentityKeyMismatchList.class).getList();
        }
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      return new LinkedList<>();
    }

    public void close() {
      cursor.close();
    }
  }

  public interface InsertListener {
    public void onComplete();
  }
}

