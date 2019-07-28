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

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import com.annimon.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.unfacd.android.utils.UfsrvFenceUtils;

import net.sqlcipher.database.SQLiteDatabase;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactUtil;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.MessagingDatabase.MarkedMessageInfo;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.DelimiterUtil;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.io.IOException;
import java.util.Arrays;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_INVITE;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_GEOBASED_JOIN;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_INVITATION_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_JOIN_SYNCED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_ACCEPTED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_GEO_BASED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_LEAVE_NOT_CONFIRMED;
import static org.thoughtcrime.securesms.database.GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED;

public class ThreadDatabase extends Database {

  private static final String TAG = ThreadDatabase.class.getSimpleName();

  public  static final String TABLE_NAME             = "thread";
  public  static final String ID                     = "_id";
  public  static final String DATE                   = "date";
  public  static final String MESSAGE_COUNT          = "message_count";
  public  static final String ADDRESS                = "recipient_ids";// members as string 1d,id,...
  public  static final String SNIPPET                = "snippet";
  private static final String SNIPPET_CHARSET        = "snippet_cs";
  public  static final String READ                   = "read";
  public  static final String UNREAD_COUNT           = "unread_count";
  public  static final String TYPE                   = "type";
  private static final String ERROR                  = "error";
  public  static final String SNIPPET_TYPE           = "snippet_type";
  public  static final String SNIPPET_URI            = "snippet_uri";
  public  static final String SNIPPET_CONTENT_TYPE   = "snippet_content_type";
  public  static final String SNIPPET_EXTRAS         = "snippet_extras";
  public  static final String ARCHIVED               = "archived";
  public  static final String STATUS                 = "status";
  public  static final String DELIVERY_RECEIPT_COUNT = "delivery_receipt_count";
  public  static final String READ_RECEIPT_COUNT     = "read_receipt_count";
  public  static final String EXPIRES_IN             = "expires_in";
  public  static final String LAST_SEEN              = "last_seen";
  private static final String HAS_SENT               = "has_sent";

  // remember to add new colomuns to MatrixCursor in ConversationListLoader.java@getUnarchivedConversationList()
  public static final String UFSRV_COMMAND    = "ufsrv_command";
  public static final String UFSRV_FID        = "ufsrv_fid";
  public static final String UFSRV_EID        = "ufsrv_eid";
  //

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " ("                    +
          ID + " INTEGER PRIMARY KEY, " + DATE + " INTEGER DEFAULT 0, "                                  +
          MESSAGE_COUNT + " INTEGER DEFAULT 0, " + ADDRESS + " TEXT, " + SNIPPET + " TEXT, "       +
          SNIPPET_CHARSET + " INTEGER DEFAULT 0, " + READ + " INTEGER DEFAULT 1, "                       +
          TYPE + " INTEGER DEFAULT 0, " + ERROR + " INTEGER DEFAULT 0, "                                 +
          SNIPPET_TYPE + " INTEGER DEFAULT 0, " + SNIPPET_URI + " TEXT DEFAULT NULL, "                   +
          SNIPPET_CONTENT_TYPE + " TEXT DEFAULT NULL, " + SNIPPET_EXTRAS + " TEXT DEFAULT NULL, "      +
          ARCHIVED + " INTEGER DEFAULT 0, " + STATUS + " INTEGER DEFAULT 0, "                            +
          DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + EXPIRES_IN + " INTEGER DEFAULT 0, "          +
          LAST_SEEN + " INTEGER DEFAULT 0, " + HAS_SENT + " INTEGER DEFAULT 0, "                         +
          READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, "                                                    +
          UNREAD_COUNT + " INTEGER DEFAULT 0, "                                                          +
          UFSRV_COMMAND + " TEXT, "  +//
          UFSRV_FID + " INTEGER DEFAULT 0, "  +//
          UFSRV_EID + " INTEGER DEFAULT 0 "  +//
          ");";

  public static final String[] CREATE_INDEXS = {
          "CREATE INDEX IF NOT EXISTS thread_recipient_ids_index ON " + TABLE_NAME + " (" + ADDRESS + ");",
          "CREATE INDEX IF NOT EXISTS archived_count_index ON " + TABLE_NAME + " (" + ARCHIVED + ", " + MESSAGE_COUNT + ");",
          "CREATE INDEX IF NOT EXISTS ufsrv_fid_index ON " + TABLE_NAME + " (" + UFSRV_FID + ");",
  };

  private static final String[] THREAD_PROJECTION = {
          ID, DATE, MESSAGE_COUNT, ADDRESS, SNIPPET, SNIPPET_CHARSET, READ, UNREAD_COUNT, TYPE, ERROR, SNIPPET_TYPE,
          SNIPPET_URI, SNIPPET_CONTENT_TYPE, SNIPPET_EXTRAS, ARCHIVED, STATUS, DELIVERY_RECEIPT_COUNT, EXPIRES_IN, LAST_SEEN, READ_RECEIPT_COUNT,
          UFSRV_COMMAND, UFSRV_FID, UFSRV_EID //
  };

  private static final List<String> TYPED_THREAD_PROJECTION = Stream.of(THREAD_PROJECTION)
          .map(columnName -> TABLE_NAME + "." + columnName)
          .toList();

  private static final List<String> COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION = Stream.concat(Stream.concat(Stream.of(TYPED_THREAD_PROJECTION),
                                                                                                             Stream.of(RecipientDatabase.TYPED_RECIPIENT_PROJECTION)),
                                                                                               Stream.of(GroupDatabase.TYPED_GROUP_PROJECTION))
          .toList();

  public ThreadDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  private long createThreadForRecipient(Address address, boolean group, int distributionType) {
    ContentValues contentValues = new ContentValues(4);
    long date                   = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(ADDRESS, address.serialize());

    if (group)
      contentValues.put(TYPE, distributionType);

    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }


  //
  //overloads one above with fid
  private long createThreadForRecipients(Address address, int recipientCount, int distributionType, long fid) {
    ContentValues contentValues = new ContentValues(5);
    long date                   = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(ADDRESS, Address.toSerializedList(Arrays.asList(address), ' '));

    if (recipientCount > 1)
      contentValues.put(TYPE, distributionType);

    contentValues.put(MESSAGE_COUNT, 0);

    contentValues.put(UFSRV_FID, fid);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

// inserint of ufsrvcomand from the most recently inserted message. Not all message have ufrsvcommand, it may be null
private void updateThread(long threadId, long count, String body, @Nullable Uri attachment,
                          @Nullable String contentType, @Nullable Extra extra,
                          long date, int status, int deliveryReceiptCount, long type, boolean unarchive,
                          long expiresIn, int readReceiptCount,  String ufsrvCommandWire)
{
  String extraSerialized = null;

  if (extra != null) {
    try {
      extraSerialized = JsonUtils.toJson(extra);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  ContentValues contentValues = new ContentValues(9);
  contentValues.put(DATE, date - date % 1000);
  contentValues.put(MESSAGE_COUNT, count);
  contentValues.put(SNIPPET, body);
  contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());
  contentValues.put(SNIPPET_TYPE, type);
  contentValues.put(SNIPPET_CONTENT_TYPE, contentType);
  contentValues.put(SNIPPET_EXTRAS, extraSerialized);
  contentValues.put(STATUS, status);
  contentValues.put(DELIVERY_RECEIPT_COUNT, deliveryReceiptCount);
  contentValues.put(READ_RECEIPT_COUNT, readReceiptCount);
  contentValues.put(EXPIRES_IN, expiresIn);

  //
  contentValues.put(UFSRV_COMMAND, TextUtils.isEmpty(ufsrvCommandWire)?null:ufsrvCommandWire);
  //

  if (unarchive) {
    contentValues.put(ARCHIVED, 0);
  }

  SQLiteDatabase db = databaseHelper.getWritableDatabase();
  db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
  notifyConversationListListeners();
}

  public void updateSnippet(long threadId, String snippet, @Nullable Uri attachment, long date, long type, boolean unarchive) {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(SNIPPET, snippet);
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }


  private void deleteThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = "";

    for (long threadId : threadIds) {
      where += ID + " = '" + threadId + "' OR ";
    }

    where = where.substring(0, where.length() - 4);

    db.delete(TABLE_NAME, where, null);
    notifyConversationListListeners();
  }

  private void deleteAllThreads() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
    notifyConversationListListeners();
  }

  public void trimAllThreads(int length, ProgressListener listener) {
    Cursor cursor   = null;
    int threadCount = 0;
    int complete    = 0;

    try {
      cursor = this.getConversationList();

      if (cursor != null)
        threadCount = cursor.getCount();

      while (cursor != null && cursor.moveToNext()) {
        long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        trimThread(threadId, length);

        listener.onProgress(++complete, threadCount);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void trimThread(long threadId, int length) {
    Log.w("ThreadDatabase", "Trimming thread: " + threadId + " to: " + length);
    Cursor cursor = null;

    try {
      cursor = DatabaseFactory.getMmsSmsDatabase(context).getConversation(threadId);

      if (cursor != null && length > 0 && cursor.getCount() > length) {
        Log.w("ThreadDatabase", "Cursor count is greater than length!");
        cursor.moveToPosition(length - 1);

        long lastTweetDate = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.NORMALIZED_DATE_RECEIVED));

        Log.w("ThreadDatabase", "Cut off tweet date: " + lastTweetDate);

        DatabaseFactory.getSmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);
        DatabaseFactory.getMmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);

        update(threadId, false);
        notifyConversationListeners(threadId);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public List<MarkedMessageInfo> setAllThreadsRead() {
    SQLiteDatabase db           = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);
    contentValues.put(UNREAD_COUNT, 0);

    db.update(TABLE_NAME, contentValues, null, null);

    final List<MarkedMessageInfo> smsRecords = DatabaseFactory.getSmsDatabase(context).setAllMessagesRead();
    final List<MarkedMessageInfo> mmsRecords = DatabaseFactory.getMmsDatabase(context).setAllMessagesRead();

    notifyConversationListListeners();

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }


  // returns (previously) unread msgids for the thread, which can be empty
  public List<MarkedMessageInfo> setRead(long threadId, boolean lastSeen) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);
    contentValues.put(UNREAD_COUNT, 0);

    if (lastSeen) {
       contentValues.put(LAST_SEEN, System.currentTimeMillis());
     }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    final List<MarkedMessageInfo> smsRecords = DatabaseFactory.getSmsDatabase(context).setMessagesRead(threadId);
    final List<MarkedMessageInfo> mmsRecords = DatabaseFactory.getMmsDatabase(context).setMessagesRead(threadId);

    notifyConversationListListeners();

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }

//  public void setUnread(long threadId) {
//    ContentValues contentValues = new ContentValues(1);
//    contentValues.put(READ, 0);
//
//    SQLiteDatabase db = databaseHelper.getWritableDatabase();
//    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
//    notifyConversationListListeners();
//  }

  public void incrementUnread(long threadId, int amount) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET " + READ + " = 0, " +
                       UNREAD_COUNT + " = " + UNREAD_COUNT + " + ? WHERE " + ID + " = ?",
               new String[] {String.valueOf(amount),
                       String.valueOf(threadId)});
  }

  public void setDistributionType(long threadId, int distributionType) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(TYPE, distributionType);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public int getDistributionType(long threadId) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{TYPE}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(TYPE));
      }

      return DistributionTypes.DEFAULT;
    } finally {
      if (cursor != null) cursor.close();
    }

  }

//  public Cursor getFilteredConversationList(@Nullable List<Address> filter) {
//    if (filter == null || filter.size() == 0)
//      return null;
//
//    SQLiteDatabase      db                   = databaseHelper.getReadableDatabase();
//    List<List<Address>> partitionedAddresses = Util.partition(filter, 900);
//    List<Cursor>        cursors              = new LinkedList<>();
//
//    for (List<Address> addresses : partitionedAddresses) {
//      String   selection      = ADDRESS + " = ?";
//      String[] selectionArgs  = new String[addresses.size()];
//
//      for (int i=0;i<addresses.size()-1;i++)
//        selection += (" OR " + ADDRESS + " = ?");
//
//      int i= 0;
//      for (Address address : addresses) {
//        selectionArgs[i++] = DelimiterUtil.escape(address.serialize(), ' ');
//      }
//
//      cursors.add(db.query(TABLE_NAME, null, selection, selectionArgs, null, null, DATE + " DESC"));
//    }
//
//    Cursor cursor = cursors.size() > 1 ? new MergeCursor(cursors.toArray(new Cursor[cursors.size()])) : cursors.get(0);
//    setNotifyConverationListListeners(cursor);
//    return cursor;
//  }

  public Cursor getFilteredConversationList(@Nullable List<Address> filter) {
    if (filter == null || filter.size() == 0)
      return null;

    SQLiteDatabase      db                   = databaseHelper.getReadableDatabase();
    List<List<Address>> partitionedAddresses = Util.partition(filter, 900);
    List<Cursor>        cursors              = new LinkedList<>();

    for (List<Address> addresses : partitionedAddresses) {
      String   selection      = TABLE_NAME + "." + ADDRESS + " = ?";
      String[] selectionArgs  = new String[addresses.size()];

      for (int i=0;i<addresses.size()-1;i++)
        selection += (" OR " + TABLE_NAME + "." + ADDRESS + " = ?");

      int i= 0;
      for (Address address : addresses) {
        selectionArgs[i++] = DelimiterUtil.escape(address.serialize(), ' ');
      }

      String query = createQuery(selection, 0);

      cursors.add(db.rawQuery(query, selectionArgs));
    }

    Cursor cursor = cursors.size() > 1 ? new MergeCursor(cursors.toArray(new Cursor[cursors.size()])) : cursors.get(0);
    setNotifyConverationListListeners(cursor);
    return cursor;
  }

  public Cursor getRecentConversationList(int limit) {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = createQuery(MESSAGE_COUNT + " != 0 " + " AND groups."+GroupDatabase.ACTIVE+" = 1" , limit); //  ACTIVE

    return db.rawQuery(query, null);
  }

  public Cursor getRecentPushConversationList(int limit) {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         where = MESSAGE_COUNT + " != 0 AND (" + RecipientDatabase.REGISTERED + " = " + RecipientDatabase.RegisteredState.REGISTERED.getId() + " OR " + GroupDatabase.GROUP_ID + " NOT NULL)" + " AND groups."+GroupDatabase.ACTIVE+" = 1"; // ACTIVE
    String         query = createQuery(where, limit);

    return db.rawQuery(query, null);
  }

  public Cursor getConversationList() {
    return getConversationList("0");
  }


  //todo: consider deleting or join on preferences as per getConversationList()
//  public Cursor getConversationListByFid () {
//    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
//    Cursor         cursor =  db.query(TABLE_NAME, null, ARCHIVED + " = ?", new String[] {"0"}, null, null, UFSRV_FID + " ASC");
//
//    setNotifyConverationListListeners(cursor);
//
//    return cursor;
//  }

  public Cursor getArchivedConversationList() {
    return getConversationList("1");
//    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
//    Cursor         cursor = db.query(TABLE_NAME, null, ARCHIVED + " = ? AND " + MESSAGE_COUNT + " != 0", new String[] {"1"}, null, null, DATE + " DESC");
//
//    setNotifyConverationListListeners(cursor);
//
//    return cursor;
  }

  //replaces below
  private Cursor getConversationList(String archived) {
    String         projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    SQLiteDatabase db         = databaseHelper.getReadableDatabase();
    Cursor         cursor     = db.rawQuery("SELECT " + projection + " FROM " + TABLE_NAME +
                                                    " LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
                                                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.ADDRESS +
                                                    " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
                                                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GroupDatabase.GROUP_ID +
                                                    " WHERE " + ARCHIVED + " = ? AND " + MESSAGE_COUNT + " != 0" +
                                                    " ORDER BY " + TABLE_NAME + "." + DATE + " DESC",
                                            new String[] {archived});

    setNotifyConverationListListeners(cursor);

    return cursor;
  }

  public Cursor getDirectShareList() {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = createQuery(MESSAGE_COUNT + " != 0", 0);

    return db.rawQuery(query, null);
  }

  public int getArchivedConversationListCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, ARCHIVED + " = ?",
              new String[] {"1"}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

    } finally {
      if (cursor != null) cursor.close();
    }

    return 0;
  }

  public void archiveConversation(long threadId) {
    SQLiteDatabase db            = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 1);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void unarchiveConversation(long threadId) {
    SQLiteDatabase db            = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 0);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void setLastSeen(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(LAST_SEEN, System.currentTimeMillis());

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(threadId)});
    notifyConversationListListeners();
  }

  public Pair<Long, Boolean> getLastSeenAndHasSent(long threadId) {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, new String[]{LAST_SEEN, HAS_SENT}, ID_WHERE, new String[]{String.valueOf(threadId)}, null, null, null);

    try {
      if (cursor != null && cursor.moveToFirst()) {
        return new Pair<>(cursor.getLong(0), cursor.getLong(1) == 1);
      }

      return new Pair<>(-1L, false);
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public void deleteConversation(long threadId) {
    DatabaseFactory.getSmsDatabase(context).deleteThread(threadId);
    DatabaseFactory.getMmsDatabase(context).deleteThread(threadId);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(threadId);
    deleteThread(threadId);
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public void setHasSent(long threadId, boolean hasSent) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(HAS_SENT, hasSent ? 1 : 0);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, ID_WHERE,
                                                new String[] {String.valueOf(threadId)});

    notifyConversationListeners(threadId);
  }


  public void deleteConversations(Set<Long> selectedConversations) {
    DatabaseFactory.getSmsDatabase(context).deleteThreads(selectedConversations);
    DatabaseFactory.getMmsDatabase(context).deleteThreads(selectedConversations);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(selectedConversations);
    deleteThreads(selectedConversations);
    notifyConversationListeners(selectedConversations);
    notifyConversationListListeners();
  }

  public void deleteAllConversations() {
    DatabaseFactory.getSmsDatabase(context).deleteAllThreads();
    DatabaseFactory.getMmsDatabase(context).deleteAllThreads();
    DatabaseFactory.getDraftDatabase(context).clearAllDrafts();
    deleteAllThreads();
  }

  public long getThreadIdIfExistsFor(Recipient recipient) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = ADDRESS + " = ?";
    String[] recipientsArg = new String[] {recipient.getAddress().serialize()};
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return -1L;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public long getThreadIdFor(Recipient recipient) {
    return getThreadIdFor(recipient, DistributionTypes.DEFAULT);
  }

  //
  //This only looks up the record and doesnt not create any new thread.
  //It uses fid first if set,
  public long getThreadIdFor(Recipient recipients, long fid) {
    long threadId;
    if (fid>0)
    {
      threadId = getThreadIdFor(null, DistributionTypes.DEFAULT, fid);//only fid is used
      if (threadId == -1 && recipients != null)
      {
        return getThreadIdIfExistsFor(recipients);
      }
      else return threadId;
    }
    else
    if (recipients!=null)  return getThreadIdIfExistsFor(recipients);

    return -1;

  }
  //

  //
  //this is flawedufsrvcommand as under current implementation ufsrvcommand is not updated
  //plus the invitation is property of the grouprecord not
  //We received an invitationto join a group
  public boolean isThreadForJoinInvitation(long threadId)
  {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = ID + " = ?";
    String[] threadIdArg = new String[] {Long.valueOf(threadId).toString()};
    Cursor cursor          = null;
    String ufsrvCommandEncoded  =null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{UFSRV_COMMAND}, where, threadIdArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        ufsrvCommandEncoded=cursor.getString(cursor.getColumnIndexOrThrow(UFSRV_COMMAND));
      else
        return false;

      if (!TextUtils.isEmpty(ufsrvCommandEncoded)) {
        try {
          UfsrvCommandWire ufsrvCommandWire = UfsrvCommandWire.parseFrom(org.whispersystems.signalservice.internal.util.Base64.decode(ufsrvCommandEncoded));
          return UfsrvFenceUtils.isFenceCommandJoinInvitation(ufsrvCommandWire.getFenceCommand());
        } catch (IOException x) {
          Log.d(TAG, x.getMessage());
        }
      }else {
        Log.d(TAG, "isThreadForJoinInvitation: Threaddoes not contain UfsrvCommand object... threadID: "+threadId);
      }

      return false;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  // this has the side effect of creating a thread if one isnt found
  public long getThreadIdFor(Recipient recipient, int distributionType) {
    SQLiteDatabase db            = databaseHelper.getReadableDatabase();
    String         where         = ADDRESS + " = ?";
    String[]       recipientsArg = new String[]{recipient.getAddress().serialize()};
    Cursor         cursor        = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      } else {
        return createThreadForRecipient(recipient.getAddress(), recipient.isGroupRecipient(), distributionType);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  // this mirrors the one above which  has the side effect of creating a thread if one isnt found
  public long getThreadIdOrCreateFor(Recipient recipient, int distributionType, long fid) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String         where         = ADDRESS + " = ?";
//    String[]       recipientsArg = new String[]{Address.toSerializedList(Arrays.asList(addresses), ' ')};
    String[]       recipientsArg = new String[]{recipient.getAddress().serialize()};
    Cursor cursor          = null;

//    Log.d(TAG, String.format("getThreadIdOrCreateFor: Getting threadId for recipient(%s) with fid:'%d': ", recipientsList, fid));

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
      {
        //
        Log.w(TAG, String.format("getThreadIdOrCreateFor: Could not find threadid: creating new one for recipeint(s), using fid:'%d'...", fid));
        return createThreadForRecipients(recipient.getAddress(), recipient.getParticipants().size(), distributionType, fid);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
//

  //
  public @Nullable long getFidForThreadId(long threadId)
  {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{UFSRV_FID}, ID_WHERE, new String[] {Long.valueOf(threadId).toString()}, null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        Log.d(TAG, String.format("getFidForThreadId: Fetched fid:'%d' from threadId:'%d'", cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_FID)), threadId));
        return cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_FID));
      }
      else  return -1;//does not exist

    } finally {
      if (cursor != null)
        cursor.close();
    }

  }
  //


  //
  public @Nullable long getEidForThreadId(long threadId)
  {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{UFSRV_EID}, ID_WHERE, new String[] {Long.valueOf(threadId).toString()}, null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        Log.d(TAG, String.format("getFidForThreadId: Fetched eid:'%d' from threadId:'%d'", cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_EID)), threadId));
        return cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_EID));
      }
      else  return -1;//does not exist

    } finally {
      if (cursor != null)
        cursor.close();
    }

  }
  //

  //
  public @Nullable long getEidForFid(long fid)
  {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{UFSRV_EID}, UFSRV_FID+" = ?", new String[] {Long.valueOf(fid).toString()}, null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        Log.d(TAG, String.format("getFidForThreadId: Fetched eid:'%d' from fid:'%d'", cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_EID)), fid));
        return cursor.getLong(cursor.getColumnIndexOrThrow(UFSRV_EID));
      }
      else  return -1;//does not exist

    } finally {
      if (cursor != null)
        cursor.close();
    }

  }
  //

  //
  //Added extra argument for groupId: which could be ufsrv fid or internal groups_id
  //if group id does not exit, a new one  is created
  public long getThreadIdFor(Recipient recipient, int distributionType, long fid){//String groupId) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = UFSRV_FID + " = ?";
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, new String[]{Long.valueOf(fid).toString()}, null, null, null);

      if (cursor != null && cursor.moveToFirst())
      {
        Log.d(TAG, ">>> getThreadIdFor: group/fid: "+fid+" FOUND EXISTING THREAD: "+cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      }
      else
      {
       return -1;
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //
  public void updateFid(long threadId, long fid)
  {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(UFSRV_FID, fid);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{String.valueOf(threadId) + ""});

    notifyConversationListListeners();
  }
  //

  //
  public void updateEidByThreadId(long threadId, long eid)
  {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(UFSRV_EID, eid);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{String.valueOf(threadId) + ""});

    Log.d(TAG, String.format("updateFid: Updated threadId:'%d' with eid:'%d'", threadId, getEidForThreadId(threadId)));

    notifyConversationListListeners();
  }
  //

  //
  public void updateEidByFid(long fid, long eid)
  {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(UFSRV_EID, eid);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, UFSRV_FID + " = ?", new String[]{fid + ""});

    Log.d(TAG, String.format("updateFid: Updated fid:'%d' with eid:'%d'", fid, getEidForFid(fid)));

    notifyConversationListListeners();
  }
  //


  //
  public void updateFid(long threadId, long fid, long date)
  {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(UFSRV_FID, fid);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});

    Log.d(TAG, String.format("updateFid: Updated threadId:'%d' with fid:'%d'", threadId, getFidForThreadId(threadId)));

    notifyConversationListListeners();
  }
  //


  //
  public void updateUfsrvCommandWire(long threadId, long date, String ufsrvCommandEncoded, MasterSecret masterSecret)
  {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(UFSRV_COMMAND, ufsrvCommandEncoded);
    contentValues.put(DATE, date - date % 1000);
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{String.valueOf(threadId) + ""});
    notifyConversationListListeners();

//just additional debugging
//    ThreadRecord threadRecord=getThreadRecord(threadId, masterSecret);
//    if (threadRecord!=null)
//    Log.e(TAG, String.format("updateUfsrvCommandWire(fid:'%d'): Date INSERTED '%d': for threadId:'%d'", threadRecord.getFid(), threadRecord.getDate(), threadId));
//    else Log.e(TAG, String.format("updateUfsrvCommandWire: ERROR: THREAD RECRD WAS NULL for threadId:'%d'", threadId));
  }


  public Cursor getInvitedConversationList () {
//    final String MY_QUERY = "SELECT t.*, g.mode FROM thread t INNER JOIN groups g ON t.ufsrv_fid = g.fid where (g.mode = ? OR g.mode = ? ) AND t.archived = 0 ORDER BY DATE DESC";
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    final String MY_QUERY =
            "SELECT " + projection + " FROM thread " +
                    "LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + ADDRESS +
                    " INNER JOIN groups ON thread.ufsrv_fid = groups.fid " +
                    "WHERE (groups.mode = ? OR groups.mode = ?) AND thread.archived = 0 ORDER BY thread.DATE DESC"
            ;
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_INVITATION), String.valueOf(GROUP_MODE_GEOBASED_INVITE)});

    setNotifyConverationListListeners(m);

    return m;
  }

  public long getInvitedConversationListUnreadCount () {
    final String MY_QUERY = "SELECT t.*, g.mode FROM thread t INNER JOIN groups g ON t.ufsrv_fid = g.fid where (g.mode = ? OR  g.mode = ? ) AND t.archived = 0 AND t.read = 0 ORDER BY DATE DESC";
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_INVITATION), String.valueOf(GROUP_MODE_GEOBASED_INVITE)});

    long count=m.getCount();

    setNotifyConverationListListeners(m);
    m.close();
    return count;
  }
  //


  //

  public Cursor getLeftConversationList() {
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    final String MY_QUERY =
            "SELECT " + projection +  " FROM thread " +
                    "LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
                    " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + ADDRESS +
                    " INNER JOIN groups ON thread.ufsrv_fid = groups.fid " +
                    "WHERE (groups.mode = ? OR groups.mode = ?) AND thread.archived = 0 ORDER BY thread.DATE DESC"
            ;
      SQLiteDatabase db     = databaseHelper.getReadableDatabase();
      Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_LEAVE_ACCEPTED),  String.valueOf(GROUP_MODE_LEAVE_GEO_BASED)});

      setNotifyConverationListListeners(m);

      if (m.getCount() > 0) {
        m.moveToFirst();
        Log.d(TAG, String.format("getLeftConversationList: Set contains '%d' elements and '%d' columns, (threadId :'%d')", m.getCount(), m.getColumnCount(), m.getLong(m.getColumnIndexOrThrow(ThreadDatabase.ID))));
      }

      return m;
  }

  public long getLeftConversationListUnreadCount () {
    final String MY_QUERY = "SELECT t.*, g.mode FROM thread t INNER JOIN groups g ON t.ufsrv_fid = g.fid where (g.mode = ? OR g.mode = ?) AND read = 0 AND t.archived = 0 ORDER BY DATE DESC";
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_LEAVE_ACCEPTED),  String.valueOf(GROUP_MODE_LEAVE_GEO_BASED)});

    long count=m.getCount();

    setNotifyConverationListListeners(m);
    m.close();
    return count;
  }
  //

  // conversations that are open, active (not left), non-invite
  //http://weblogs.sqlteam.com/jeffs/archive/2007/10/11/mixing-inner-outer-joins-sql.aspx
  //TODO: add archived msg coind as per private Cursor getUnarchivedConversationList()
  public Cursor getUnarchivedOpenConversationList() {
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    final String MY_QUERY =
//            "SELECT " + projection + ", groups.mode " + " FROM thread " +
            "SELECT " + projection +  " FROM thread " +
            "LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
            " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + ADDRESS +
            " INNER JOIN groups ON thread.ufsrv_fid = groups.fid " +
            "WHERE (groups.mode = ? OR groups.mode = ? OR groups.mode = ? OR groups.mode = ? OR groups.mode = ? OR groups.mode = ?) AND thread.archived = 0 ORDER BY thread.DATE DESC"
            ;

    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_JOIN_ACCEPTED), String.valueOf(GROUP_MODE_GEOBASED_JOIN), String.valueOf(GROUP_MODE_JOIN_SYNCED),  String.valueOf(GROUP_MODE_INVITATION_JOIN_ACCEPTED), String.valueOf(GROUP_MODE_MAKE_NOT_CONFIRMED), String.valueOf(GROUP_MODE_LEAVE_NOT_CONFIRMED)});

    setNotifyConverationListListeners(m);

    if (m.getCount() > 0) {
      m.moveToFirst();
      Log.d(TAG, String.format("getUnarchivedOpenConversationList: Set contains '%d' elements and '%d' columns, (threadId :'%d')", m.getCount(), m.getColumnCount(), m.getLong(m.getColumnIndexOrThrow(ThreadDatabase.ID))));
    }

    return m;
  }

  public long getOpenConversationListUnreadCount () {
    final String MY_QUERY = "SELECT t.*, g.mode FROM thread t INNER JOIN groups g ON t.ufsrv_fid = g.fid where (g.mode = ? OR g.mode = ? OR g.mode = ? OR g.mode = ? OR g.mode = ? OR g.mode = ?) AND read = 0 AND t.archived = 0 ORDER BY DATE DESC";
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor m = db.rawQuery(MY_QUERY, new String[]{String.valueOf(GROUP_MODE_JOIN_ACCEPTED), String.valueOf(GROUP_MODE_GEOBASED_JOIN), String.valueOf(GROUP_MODE_JOIN_SYNCED), String.valueOf(GROUP_MODE_INVITATION_JOIN_ACCEPTED), String.valueOf(GROUP_MODE_MAKE_NOT_CONFIRMED), String.valueOf(GROUP_MODE_LEAVE_NOT_CONFIRMED)});

    long count=m.getCount();

    setNotifyConverationListListeners(m);
    m.close();
    return count;
  }

  @SuppressLint("Recycle")
  public @Nullable
  ThreadRecord getThreadRecord (long threadId)
  {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = createQuery("thread."+ID + " = " + threadId, 0);

    Cursor cursor = db.rawQuery(query, null);

    Reader reader = new ThreadDatabase.Reader(cursor);
    ThreadRecord record = reader.getNext();

    reader.close();

    if (record==null) Log.e(TAG, "getThreadRecord: ERROR: DATA INTEGRITY: Thread Record was null for: "+threadId);

    return record;
  }
  //

  public @Nullable Recipient getRecipientForThreadId(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, null, ID + " = ?", new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        Address address = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
        return Recipient.from(context, address, true);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return null;
  }


  void updateReadState(long threadId) {
    int unreadCount = DatabaseFactory.getMmsSmsDatabase(context).getUnreadCount(threadId);

    ContentValues contentValues = new ContentValues();
    contentValues.put(READ, unreadCount == 0);
    contentValues.put(UNREAD_COUNT, unreadCount);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues,ID_WHERE,
            new String[] {String.valueOf(threadId)});

    notifyConversationListListeners();
  }

  // IMPORTANT support the update of ufsrvcomand
  // for a given threadId retrieve the latest message that was inserted for the that threadId and update the thread display info
  public boolean update(long threadId, boolean unarchive) {
    MmsSmsDatabase mmsSmsDatabase = DatabaseFactory.getMmsSmsDatabase(context);
    long count                    = mmsSmsDatabase.getConversationCount(threadId);

    if (count == 0) {
      return true;
      // disabled  hiding of empty threads. Maybe set an advanced option?
//      deleteThread(threadId);
//      notifyConversationListListeners();
//      return true;
    }

    MmsSmsDatabase.Reader reader = null;

    try {
      // the limit on the query is set to 1: get the most recently inserted message for the threadId
      reader = mmsSmsDatabase.readerFor(mmsSmsDatabase.getConversationSnippet(threadId));
      MessageRecord record;

      if (reader != null && (record = reader.getNext()) != null) {

        String ufsrvCommandEncoded=record.getUfsrvCommand()!=null?(Base64.encodeBytes(record.getUfsrvCommand().toByteArray())):null;

        updateThread(threadId, count, getFormattedBodyFor(record), getAttachmentUriFor(record),
                     getContentTypeFor(record), getExtrasFor(record),
                     record.getTimestamp(), record.getDeliveryStatus(), record.getDeliveryReceiptCount(),
                     record.getType(), unarchive, record.getExpiresIn(), record.getReadReceiptCount(),
                     ufsrvCommandEncoded);//

        notifyConversationListListeners();
        return false;
      } else {
        deleteThread(threadId);
        notifyConversationListListeners();
        return true;
      }
    } finally {
      if (reader != null)
        reader.close();
    }
  }

  private @NonNull String getFormattedBodyFor(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms() && ((MmsMessageRecord) messageRecord).getSharedContacts().size() > 0) {
      Contact contact = ((MmsMessageRecord) messageRecord).getSharedContacts().get(0);
      return ContactUtil.getStringSummary(context, contact).toString();
    }

    return messageRecord.getBody();
  }

  private @Nullable Uri getAttachmentUriFor(MessageRecord record) {
    if (!record.isMms() || record.isMmsNotification() || record.isGroupAction()) return null;

    SlideDeck slideDeck = ((MediaMmsMessageRecord)record).getSlideDeck();
    Slide     thumbnail = slideDeck.getThumbnailSlide();

    if (thumbnail != null && ((MmsMessageRecord) record).getRevealDuration() == 0) {
      return thumbnail.getThumbnailUri();
    }

    return null;
  }

  private @Nullable String getContentTypeFor(MessageRecord record) {
    if (record.isMms()) {
      SlideDeck slideDeck = ((MmsMessageRecord) record).getSlideDeck();

      if (slideDeck.getSlides().size() > 0) {
        return slideDeck.getSlides().get(0).getContentType();
      }
    }

    return null;
  }

  private @Nullable Extra getExtrasFor(MessageRecord record) {
    if (record.isMms() && ((MmsMessageRecord) record).getRevealDuration() > 0) {
      return Extra.forRevealableMessage();
    } else if (record.isMms() && ((MmsMessageRecord) record).getSlideDeck().getStickerSlide() != null) {
      return Extra.forSticker();
    } else if (record.isMms() && ((MmsMessageRecord) record).getSlideDeck().getSlides().size() > 1) {
      return Extra.forAlbum();
    }
    return null;
  }

  private @NonNull String createQuery(@NonNull String where, int limit) {
    String projection = Util.join(COMBINED_THREAD_RECIPIENT_GROUP_PROJECTION, ",");
    String query =
            "SELECT " + projection + " FROM " + TABLE_NAME +
            " LEFT OUTER JOIN " + RecipientDatabase.TABLE_NAME +
            " ON " + TABLE_NAME + "." + ADDRESS + " = " + RecipientDatabase.TABLE_NAME + "." + RecipientDatabase.ADDRESS +
            " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME +
            " ON " + TABLE_NAME + "." + ADDRESS + " = " + GroupDatabase.TABLE_NAME + "." + GroupDatabase.GROUP_ID +
            " WHERE " + where +
            " ORDER BY " + TABLE_NAME + "." + DATE + " DESC";

    if (limit >  0) {
      query += " LIMIT " + limit;
    }

    return query;
  }


  public interface ProgressListener {
    void onProgress(int complete, int total);
  }

  public Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public static class DistributionTypes {
    public static final int DEFAULT      = 2;
    public static final int BROADCAST    = 1;
    public static final int CONVERSATION = 2;
    public static final int ARCHIVE      = 3;
    public static final int INBOX_ZERO   = 4;
  }

  public class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public ThreadRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public ThreadRecord getCurrent() {
      long    threadId         = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
      int     distributionType = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.TYPE));
      Address address          = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.ADDRESS)));

      Optional<RecipientDatabase.RecipientSettings> settings;
      Optional<GroupRecord>           groupRecord;

      if (distributionType != DistributionTypes.ARCHIVE && distributionType != DistributionTypes.INBOX_ZERO) {
        settings    = DatabaseFactory.getRecipientDatabase(context).getRecipientSettings(cursor);
        groupRecord = DatabaseFactory.getGroupDatabase(context).getGroup(cursor);
      } else {
        settings = Optional.absent();
        groupRecord = Optional.absent();
      }

      Recipient          recipient            = Recipient.from(context, address, settings, groupRecord, true);
      String             body                 = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET));
      long               date                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE));
      long               count                = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT));
      int                unreadCount          = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.UNREAD_COUNT));
      long               type                 = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
      boolean            archived             = cursor.getInt(cursor.getColumnIndex(ThreadDatabase.ARCHIVED)) != 0;
      int                status               = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS));
      int                deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.DELIVERY_RECEIPT_COUNT));
      int                readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.READ_RECEIPT_COUNT));
      long               expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.EXPIRES_IN));
      long               lastSeen             = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.LAST_SEEN));
      Uri                snippetUri           = getSnippetUri(cursor);
      String             contentType          = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_CONTENT_TYPE));
      String             extraString          = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_EXTRAS));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      //
      long fid                = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_FID));
      long eid                = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_EID));
      //

      // inflate the serialised/stored ufsrvCommand
      UfsrvCommandWire ufsrvCommand=null;

      String ufsrvCommandEncoded=cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_COMMAND));
      if (ufsrvCommandEncoded!=null)
      {
        try
        {
          ufsrvCommand = UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
          //Log.d(TAG, "getCurrent: Inflated UfsrvCommand.. type:'" + ufsrvCommand.getUfsrvtype().name() + "'. Has fence: " + ufsrvCommand.hasFenceCommand());
        }
        catch (IOException e)
        {
          Log.d(TAG, e.getMessage());
        }
      }
      //

      Extra extra = null;

      if (extraString != null) {
        try {
          extra = JsonUtils.fromJson(extraString, Extra.class);
        } catch (IOException e) {
          Log.w(TAG, "Failed to decode extras!");
        }
      }

      return new ThreadRecord(body, snippetUri, contentType, extra, recipient, date, count,
                              unreadCount, threadId, deliveryReceiptCount, status, type,
                              distributionType, archived, expiresIn, lastSeen, readReceiptCount,
                              fid, eid, ufsrvCommand);//
    }


    private @Nullable Uri getSnippetUri(Cursor cursor) {
      if (cursor.isNull(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI))) {
        return null;
      }

      try {
        return Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI)));
      } catch (IllegalArgumentException e) {
        Log.w(TAG, e);
        return null;
      }
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  public static final class Extra {

    @JsonProperty private final boolean isRevealable;
    @JsonProperty private final boolean isSticker;
    @JsonProperty private final boolean isAlbum;

    public Extra(@JsonProperty("isRevealable") boolean isRevealable,
                 @JsonProperty("isSticker") boolean isSticker,
                 @JsonProperty("isAlbum") boolean isAlbum)
    {
      this.isRevealable = isRevealable;
      this.isSticker    = isSticker;
      this.isAlbum      = isAlbum;
    }

    public static @NonNull Extra forRevealableMessage() {
      return new Extra(true, false, false);
    }

    public static @NonNull Extra forSticker() {
      return new Extra(false, true, false);
    }

    public static @NonNull Extra forAlbum() {
      return new Extra(false, false, true);
    }


    public boolean isRevealable() {
      return isRevealable;
    }

    public boolean isSticker() {
      return isSticker;
    }

    public boolean isAlbum() {
      return isAlbum;
    }
  }
}

