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
import android.text.TextUtils;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.utils.UfsrvMessageUtils;

import net.zetetic.database.sqlcipher.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.MmsNotificationAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatchSet;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.documents.NetworkFailureSet;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.NotificationMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ParentStoryId;
import org.thoughtcrime.securesms.database.model.Quote;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.database.model.StoryViewState;
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.TrimThreadJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MessageGroupContext;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingExpirationUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.revealable.ViewOnceExpirationInfo;
import org.thoughtcrime.securesms.revealable.ViewOnceUtil;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.stories.Stories;
import org.thoughtcrime.securesms.util.Base64;
import org.signal.core.util.CursorUtil;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.signal.core.util.SqlUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import static org.thoughtcrime.securesms.contactshare.Contact.Avatar;
import static org.thoughtcrime.securesms.database.ThreadDatabase.UFSRV_FID;

public class MmsDatabase extends MessageDatabase {

    private static final String TAG = Log.tag(MmsDatabase.class);

    public  static final String TABLE_NAME         = "mms";
            static final String DATE_SENT          = "date";
            static final String DATE_RECEIVED      = "date_received";
    public  static final String MESSAGE_BOX        = "msg_box";
            static final String CONTENT_LOCATION   = "ct_l";
            static final String EXPIRY             = "exp";
            public  static final String MESSAGE_TYPE       = "m_type";
            static final String MESSAGE_SIZE       = "m_size";
            static final String STATUS             = "st";
            static final String TRANSACTION_ID     = "tr_id";
            static final String PART_COUNT         = "part_count";
            static final String NETWORK_FAILURE    = "network_failures";

            static final String QUOTE_ID         = "quote_id";
            static final String QUOTE_AUTHOR     = "quote_author";
            static final String QUOTE_BODY       = "quote_body";
            static final String QUOTE_ATTACHMENT = "quote_attachment";
            static final String QUOTE_MISSING    = "quote_missing";
            static final String QUOTE_MENTIONS   = "quote_mentions";

            static final String SHARED_CONTACTS = "shared_contacts";
            static final String LINK_PREVIEWS   = "previews";
            static final String MENTIONS_SELF   = "mentions_self";
            static final String MESSAGE_RANGES  = "ranges";

    public  static final String VIEW_ONCE       = "reveal_duration";
    public  static final String STORY_TYPE      = "is_story";
            static final String PARENT_STORY_ID = "parent_story_id";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID                     + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                                                                  THREAD_ID              + " INTEGER, " +
                                                                                  DATE_SENT              + " INTEGER, " +
                                                                                  DATE_RECEIVED          + " INTEGER, " +
                                                                                  DATE_SERVER            + " INTEGER DEFAULT -1, " +
                                                                                  MESSAGE_BOX            + " INTEGER, " +
                                                                                  READ                   + " INTEGER DEFAULT 0, " +
                                                                                  BODY                   + " TEXT, " +
                                                                                  PART_COUNT             + " INTEGER, " +
                                                                                  CONTENT_LOCATION       + " TEXT, " +
                                                                                  RECIPIENT_ID           + " INTEGER, " +
                                                                                  ADDRESS_DEVICE_ID      + " INTEGER, " +
                                                                                  EXPIRY                 + " INTEGER, " +
                                                                                  MESSAGE_TYPE           + " INTEGER, " +
                                                                                  MESSAGE_SIZE           + " INTEGER, " +
                                                                                  STATUS                 + " INTEGER, " +
                                                                                  TRANSACTION_ID         + " TEXT, " +
                                                                                  DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0, " +
                                                                                  MISMATCHED_IDENTITIES  + " TEXT DEFAULT NULL, " +
                                                                                  NETWORK_FAILURE        + " TEXT DEFAULT NULL," +
                                                                                  SUBSCRIPTION_ID        + " INTEGER DEFAULT -1, " +
                                                                                  EXPIRES_IN             + " INTEGER DEFAULT 0, " +
                                                                                  EXPIRE_STARTED         + " INTEGER DEFAULT 0, " +
                                                                                  NOTIFIED               + " INTEGER DEFAULT 0, " +
                                                                                  READ_RECEIPT_COUNT     + " INTEGER DEFAULT 0, " +
                                                                                  QUOTE_ID               + " INTEGER DEFAULT 0, " +
                                                                                  QUOTE_AUTHOR           + " TEXT, " +
                                                                                  QUOTE_BODY             + " TEXT, " +
                                                                                  QUOTE_ATTACHMENT       + " INTEGER DEFAULT -1, " +
                                                                                  QUOTE_MISSING          + " INTEGER DEFAULT 0, " +
                                                                                  QUOTE_MENTIONS         + " BLOB DEFAULT NULL," +
                                                                                  SHARED_CONTACTS        + " TEXT, " +
                                                                                  UNIDENTIFIED           + " INTEGER DEFAULT 0, " +
                                                                                  LINK_PREVIEWS          + " TEXT, " +
                                                                                  VIEW_ONCE              + " INTEGER DEFAULT 0, " +
                                                                                  REACTIONS_UNREAD       + " INTEGER DEFAULT 0, " +
                                                                                  REACTIONS_LAST_SEEN    + " INTEGER DEFAULT -1, "+
                                                                                  REMOTE_DELETED         + " INTEGER DEFAULT 0,  "+
                                                                                  MENTIONS_SELF          + " INTEGER DEFAULT 0, " +
                                                                                  NOTIFIED_TIMESTAMP     + " INTEGER DEFAULT 0, " +
                                                                                  VIEWED_RECEIPT_COUNT   + " INTEGER DEFAULT 0, " +
                                                                                  SERVER_GUID            + " TEXT DEFAULT NULL, " +
                                                                                  RECEIPT_TIMESTAMP      + " INTEGER DEFAULT -1, " +
                                                                                  MESSAGE_RANGES         + " BLOB DEFAULT NULL, " +
                                                                                  STORY_TYPE             + " INTEGER DEFAULT 0, " +
                                                                                  PARENT_STORY_ID        + " INTEGER DEFAULT 0, " +

          UFSRV_COMMAND + " TEXT, " + UFSRV_GID + " INTEGER, " + UFSRV_EID + " INTEGER, " + UFSRV_FID + " INTEGER, " + //AA+
          UFSRV_STATUS + " INTEGER DEFAULT 0, " + UFSRV_CMD_TYPE + " INTEGER, " + UFSRV_CMD_ARG + " INTEGER " +
          ");";

  public static final String[] CREATE_INDEXS = {
          "CREATE INDEX IF NOT EXISTS mms_read_and_notified_and_thread_id_index ON " + TABLE_NAME + "(" + READ + "," + NOTIFIED + "," + THREAD_ID + ");",
          "CREATE INDEX IF NOT EXISTS mms_message_box_index ON " + TABLE_NAME + " (" + MESSAGE_BOX + ");",
          "CREATE INDEX IF NOT EXISTS mms_date_sent_index ON " + TABLE_NAME + " (" + DATE_SENT + ", " + RECIPIENT_ID + ", " + THREAD_ID + ");",
          "CREATE INDEX IF NOT EXISTS mms_date_server_index ON " + TABLE_NAME + " (" + DATE_SERVER + ");",
          "CREATE INDEX IF NOT EXISTS mms_thread_date_index ON " + TABLE_NAME + " (" + THREAD_ID + ", " + DATE_RECEIVED + ");",
          "CREATE INDEX IF NOT EXISTS mms_reactions_unread_index ON " + TABLE_NAME + " (" + REACTIONS_UNREAD + ");",
          "CREATE INDEX IF NOT EXISTS mms_is_story_index ON " + TABLE_NAME + " (" + STORY_TYPE + ");",
          "CREATE INDEX IF NOT EXISTS mms_parent_story_id_index ON " + TABLE_NAME + " (" + PARENT_STORY_ID + ");",
          "CREATE INDEX IF NOT EXISTS mms_thread_story_parent_story_index ON " + TABLE_NAME + " (" + THREAD_ID + ", " + DATE_RECEIVED + "," + STORY_TYPE + "," + PARENT_STORY_ID + ");",
          "CREATE INDEX IF NOT EXISTS mms_gid_index ON " + TABLE_NAME + " (" + UFSRV_GID + ");", //AA+
  };

  private static final String[] MMS_PROJECTION = new String[] {
          MmsDatabase.TABLE_NAME + "." + ID + " AS " + ID,
          THREAD_ID, DATE_SENT + " AS " + NORMALIZED_DATE_SENT,
          DATE_RECEIVED + " AS " + NORMALIZED_DATE_RECEIVED,
          DATE_SERVER,
          MESSAGE_BOX, READ,
          CONTENT_LOCATION, EXPIRY, MESSAGE_TYPE,
          MESSAGE_SIZE, STATUS, TRANSACTION_ID,
          BODY, PART_COUNT, RECIPIENT_ID, ADDRESS_DEVICE_ID,
          DELIVERY_RECEIPT_COUNT, READ_RECEIPT_COUNT, MISMATCHED_IDENTITIES, NETWORK_FAILURE, SUBSCRIPTION_ID,
          EXPIRES_IN, EXPIRE_STARTED, NOTIFIED, QUOTE_ID, QUOTE_AUTHOR, QUOTE_BODY, QUOTE_ATTACHMENT, QUOTE_MISSING, QUOTE_MENTIONS,
          SHARED_CONTACTS, LINK_PREVIEWS, UNIDENTIFIED, VIEW_ONCE, REACTIONS_UNREAD, REACTIONS_LAST_SEEN,
          REMOTE_DELETED, MENTIONS_SELF, NOTIFIED_TIMESTAMP, VIEWED_RECEIPT_COUNT, RECEIPT_TIMESTAMP, MESSAGE_RANGES,
          STORY_TYPE, PARENT_STORY_ID,
          UFSRV_COMMAND, UFSRV_EID, UFSRV_FID, UFSRV_GID, UFSRV_STATUS, UFSRV_CMD_TYPE, UFSRV_CMD_ARG,//AA+
          "json_group_array(json_object(" +
          "'" + AttachmentDatabase.ROW_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.ROW_ID + ", " +
          "'" + AttachmentDatabase.UNIQUE_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UNIQUE_ID + ", " +
          "'" + AttachmentDatabase.MMS_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + ", " +
          "'" + AttachmentDatabase.SIZE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.SIZE + ", " +
          "'" + AttachmentDatabase.FILE_NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FILE_NAME + ", " +
          "'" + AttachmentDatabase.DATA + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DATA + ", " +
          "'" + AttachmentDatabase.CONTENT_TYPE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_TYPE + ", " +
          "'" + AttachmentDatabase.CDN_NUMBER + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CDN_NUMBER + ", " +
          "'" + AttachmentDatabase.CONTENT_LOCATION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_LOCATION + ", " +
          "'" + AttachmentDatabase.FAST_PREFLIGHT_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FAST_PREFLIGHT_ID + "," +
          "'" + AttachmentDatabase.VOICE_NOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VOICE_NOTE + "," +
          "'" + AttachmentDatabase.BORDERLESS + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.BORDERLESS + "," +
          "'" + AttachmentDatabase.VIDEO_GIF + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VIDEO_GIF + "," +
          "'" + AttachmentDatabase.WIDTH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.WIDTH + "," +
          "'" + AttachmentDatabase.HEIGHT + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.HEIGHT + "," +
          "'" + AttachmentDatabase.QUOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.QUOTE + ", " +
          "'" + AttachmentDatabase.CONTENT_DISPOSITION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_DISPOSITION + ", " +
          "'" + AttachmentDatabase.NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.NAME + ", " +
          "'" + AttachmentDatabase.TRANSFER_STATE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFER_STATE + ", " +
          "'" + AttachmentDatabase.CAPTION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CAPTION + ", " +
          "'" + AttachmentDatabase.STICKER_PACK_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_ID+ ", " +
          "'" + AttachmentDatabase.STICKER_PACK_KEY + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_KEY + ", " +
          "'" + AttachmentDatabase.STICKER_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_ID  + ", " +
          "'" + AttachmentDatabase.STICKER_EMOJI + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_EMOJI + ", " +
          "'" + AttachmentDatabase.VISUAL_HASH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VISUAL_HASH + ", " +
          "'" + AttachmentDatabase.TRANSFORM_PROPERTIES + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFORM_PROPERTIES + ", " +
          "'" + AttachmentDatabase.DISPLAY_ORDER + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DISPLAY_ORDER + ", " +
          "'" + AttachmentDatabase.UPLOAD_TIMESTAMP + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UPLOAD_TIMESTAMP + ", " +
          "'" + AttachmentDatabase.UFID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UFID +  //AA+
          ")) AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
  };

  private static final String IS_STORY_CLAUSE = STORY_TYPE + " > 0 AND " + REMOTE_DELETED + " = 0";

  private static final String RAW_ID_WHERE = TABLE_NAME + "._id = ?";

  private static final String OUTGOING_INSECURE_MESSAGES_CLAUSE = "(" + MESSAGE_BOX + " & " + Types.BASE_TYPE_MASK + ") = " + Types.BASE_SENT_TYPE + " AND NOT (" + MESSAGE_BOX + " & " + Types.SECURE_MESSAGE_BIT + ")";
  private static final String OUTGOING_SECURE_MESSAGES_CLAUSE   = "(" + MESSAGE_BOX + " & " + Types.BASE_TYPE_MASK + ") = " + Types.BASE_SENT_TYPE + " AND (" + MESSAGE_BOX + " & " + (Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT) + ")";

  private final EarlyReceiptCache earlyDeliveryReceiptCache = new EarlyReceiptCache("MmsDelivery");

  public MmsDatabase(Context context, SignalDatabase databaseHelper) {
    super(context, databaseHelper);
  }

  @Override
  protected String getTableName() {
    return TABLE_NAME;
  }

  @Override
  protected String getDateSentColumnName() {
    return DATE_SENT;
  }

  @Override
  protected String getDateReceivedColumnName() {
    return DATE_RECEIVED;
  }

  @Override
  protected String getTypeField() {
    return MESSAGE_BOX;
  }

  @Override
  public @Nullable RecipientId getOldestGroupUpdateSender(long threadId, long minimumDateReceived) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Cursor getExpirationStartedMessages() {
    String where = EXPIRE_STARTED + " > 0";
    return rawQuery(where, null);
  }

  @Override
  public SmsMessageRecord getSmsMessage(long messageId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Cursor getMessageCursor(long messageId) {
    return internalGetMessage(messageId);
  }

  @Override
  public boolean hasReceivedAnyCallsSince(long threadId, long timestamp) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsEndSession(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsInvalidVersionKeyExchange(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsSecure(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsPush(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsDecryptFailed(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsNoSession(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsUnsupportedProtocolVersion(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsInvalidMessage(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsLegacyVersion(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsMissedCall(long id, boolean isVideoOffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markSmsStatus(long id, int status) {
    throw new UnsupportedOperationException();
  }

  @Override
  public InsertResult updateBundleMessageBody(long messageId, String body) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NonNull List<MarkedMessageInfo> getViewedIncomingMessages(long threadId) {
    SQLiteDatabase db      = databaseHelper.getSignalReadableDatabase();
    String[]       columns = new String[]{ID, RECIPIENT_ID, DATE_SENT, MESSAGE_BOX, THREAD_ID, UFSRV_COMMAND};//AA+
    String         where   = THREAD_ID + " = ? AND " + VIEWED_RECEIPT_COUNT + " > 0 AND " + MESSAGE_BOX + " & " + Types.BASE_INBOX_TYPE + " = " + Types.BASE_INBOX_TYPE;
    String[]       args    = SqlUtil.buildArgs(threadId);


    try (Cursor cursor = db.query(getTableName(), columns, where, args, null, null, null, null)) {
      if (cursor == null) {
        return Collections.emptyList();
      }

      List<MarkedMessageInfo> results = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        long           messageId     = CursorUtil.requireLong(cursor, ID);
        RecipientId    recipientId   = RecipientId.from(CursorUtil.requireLong(cursor, RECIPIENT_ID));
        long           dateSent      = CursorUtil.requireLong(cursor, DATE_SENT);

        //AA+
        UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier = UfsrvMessageUtils.UfsrvMessageIdentifierFromEncoded(cursor.getString(Math.abs(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND))), dateSent);
        SyncMessageId  syncMessageId  = new SyncMessageId(recipientId, dateSent, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid);
        //

        results.add(new MarkedMessageInfo(threadId, syncMessageId, new MessageId(messageId, true), null));
      }

      return results;
    }
  }

  @Override
  public @Nullable MarkedMessageInfo setIncomingMessageViewed(long messageId) {
    List<MarkedMessageInfo> results = setIncomingMessagesViewed(Collections.singletonList(messageId));

    if (results.isEmpty()) {
      return null;
    } else {
      return results.get(0);
    }
  }

  @Override
  public @NonNull List<MarkedMessageInfo> setIncomingMessagesViewed(@NonNull List<Long> messageIds) {
    if (messageIds.isEmpty()) {
      return Collections.emptyList();
    }

    SQLiteDatabase          database    = databaseHelper.getSignalWritableDatabase();
    String[]                columns     = new String[]{ID, RECIPIENT_ID, DATE_SENT, MESSAGE_BOX, THREAD_ID, UFSRV_COMMAND};//AA+
    String                  where       = ID + " IN (" + Util.join(messageIds, ",") + ") AND " + VIEWED_RECEIPT_COUNT + " = 0";
    List<MarkedMessageInfo> results     = new LinkedList<>();

    database.beginTransaction();
    try (Cursor cursor = database.query(TABLE_NAME, columns, where, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        long type = CursorUtil.requireLong(cursor, MESSAGE_BOX);
        if (Types.isSecureType(type) && Types.isInboxType(type)) {
          long          messageId     = CursorUtil.requireLong(cursor, ID);
          long          threadId      = CursorUtil.requireLong(cursor, THREAD_ID);
          RecipientId   recipientId   = RecipientId.from(CursorUtil.requireLong(cursor, RECIPIENT_ID));
          long          dateSent      = CursorUtil.requireLong(cursor, DATE_SENT);
//          SyncMessageId syncMessageId = new SyncMessageId(recipientId, dateSent);//AA-
          //AA+
          UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier = UfsrvMessageUtils.UfsrvMessageIdentifierFromEncoded(cursor.getString(Math.abs(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND))), dateSent);
          SyncMessageId  syncMessageId  = new SyncMessageId(recipientId, dateSent, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid);
          //

          results.add(new MarkedMessageInfo(threadId, syncMessageId, new MessageId(messageId, true), null));

          ContentValues contentValues = new ContentValues();
          contentValues.put(VIEWED_RECEIPT_COUNT, 1);

          database.update(TABLE_NAME, contentValues, ID_WHERE, SqlUtil.buildArgs(CursorUtil.requireLong(cursor, ID)));
        }
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }

    Set<Long> threadsUpdated = Stream.of(results)
            .map(MarkedMessageInfo::getThreadId)
            .collect(Collectors.toSet());

    notifyConversationListeners(threadsUpdated);

    return results;
  }

  @Override
  public @NonNull Pair<Long, Long> insertReceivedCall(long fid, @NonNull RecipientId address, boolean isVideoOffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NonNull Pair<Long, Long> insertOutgoingCall(long fid, @NonNull RecipientId address, boolean isVideoOffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NonNull Pair<Long, Long> insertMissedCall(long fid, @NonNull RecipientId address, long timestamp, boolean isVideoOffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertOrUpdateGroupCall(@NonNull RecipientId groupRecipientId,
                                      @NonNull RecipientId sender,
                                      long timestamp,
                                      @Nullable String peekGroupCallEraId,
                                      @NonNull Collection<UUID> peekJoinedUuids,
                                      boolean isCallFull)
  {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertOrUpdateGroupCall(@NonNull RecipientId groupRecipientId,
                                      @NonNull RecipientId sender,
                                      long timestamp,
                                      @Nullable String messageGroupCallEraId)
  {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean updatePreviousGroupCall(long threadId, @Nullable String peekGroupCallEraId, @NonNull Collection<UUID> peekJoinedUuids, boolean isCallFull) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<InsertResult> insertMessageInbox(IncomingTextMessage message, long type) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<InsertResult> insertMessageInbox(IncomingTextMessage message) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long insertMessageOutbox(long threadId, OutgoingTextMessage message, boolean forceSms, long date, InsertListener insertListener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertProfileNameChangeMessages(@NonNull Recipient recipient, @NonNull String newProfileName, @NonNull String previousProfileName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertGroupV1MigrationEvents(@NonNull RecipientId recipientId, long threadId, List<RecipientId> pendingRecipients) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertNumberChangeMessages(@NonNull Recipient recipient) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertBoostRequestMessage(@NonNull RecipientId recipientId, long threadId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void endTransaction(SQLiteDatabase database) {
    database.endTransaction();
  }

  @Override
  public SQLiteStatement createInsertStatement(SQLiteDatabase database) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void ensureMigration() {
    databaseHelper.getSignalWritableDatabase();
  }

  @Override
  public boolean isStory(long messageId) {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String[]       projection = new String[]{"1"};
    String         where      = IS_STORY_CLAUSE + " AND " + ID + " = ?";
    String[]       whereArgs  = SqlUtil.buildArgs(messageId);
    try (Cursor cursor = database.query(TABLE_NAME, projection, where, whereArgs, null, null, null, "1")) {
      return cursor != null && cursor.moveToFirst();
    }
  }

  @Override
  public @NonNull MessageDatabase.Reader getOutgoingStoriesTo(@NonNull RecipientId recipientId) {
    Recipient recipient = Recipient.resolved(recipientId);
    Long      threadId  = null;
    if (recipient.isGroup()) {
      threadId = SignalDatabase.threads().getThreadIdFor(recipientId);
    }
    String where = IS_STORY_CLAUSE + " AND (" + getOutgoingTypeClause() + ")";
    final String[] whereArgs;
    if (threadId == null) {
      where += " AND " + RECIPIENT_ID + " = ?";
      whereArgs = SqlUtil.buildArgs(recipientId);
    } else {
      where += " AND " + THREAD_ID_WHERE;
      whereArgs = SqlUtil.buildArgs(1, 0, threadId);
    }
    return new Reader(rawQuery(where, whereArgs));
  }

  @Override
  public @NonNull MessageDatabase.Reader getAllOutgoingStories(boolean reverse) {
    String where = IS_STORY_CLAUSE + " AND (" + getOutgoingTypeClause() + ")";

    return new Reader(rawQuery(where, null, reverse, -1L));
  }

  @Override
  public @NonNull MessageDatabase.Reader getAllStories() {
    return new Reader(rawQuery(IS_STORY_CLAUSE, null, true, -1L));
  }

  @Override
  public @NonNull MessageDatabase.Reader getAllStoriesFor(@NonNull RecipientId recipientId) {
    long     threadId  = SignalDatabase.threads().getThreadIdIfExistsFor(recipientId);
    String   where     = IS_STORY_CLAUSE + " AND " + THREAD_ID_WHERE;
    String[] whereArgs = SqlUtil.buildArgs(threadId);
    Cursor   cursor    = rawQuery(where, whereArgs, false, -1L);

    return new Reader(cursor);
  }

  @Override
  public @NonNull StoryViewState getStoryViewState(@NonNull RecipientId recipientId) {
    if (!Stories.isFeatureEnabled()) {
      return StoryViewState.NONE;
    }

    long threadId = SignalDatabase.threads().getThreadIdIfExistsFor(recipientId);

    return getStoryViewState(threadId);
  }

  @VisibleForTesting
  @NonNull StoryViewState getStoryViewState(long threadId) {
    final String   hasStoryQuery = "SELECT EXISTS(SELECT 1 FROM " + TABLE_NAME + " WHERE " + IS_STORY_CLAUSE + " AND " + THREAD_ID_WHERE + " LIMIT 1)";
    final String[] hasStoryArgs  = SqlUtil.buildArgs(threadId);
    final boolean  hasStories;

    try (Cursor cursor = getReadableDatabase().rawQuery(hasStoryQuery, hasStoryArgs)) {
      hasStories = cursor != null && cursor.moveToFirst() && !cursor.isNull(0) && cursor.getInt(0) == 1;
    }

    if (!hasStories) {
      return StoryViewState.NONE;
    }

    final String   hasUnviewedStoriesQuery = "SELECT EXISTS(SELECT 1 FROM " + TABLE_NAME + " WHERE " + IS_STORY_CLAUSE + " AND " + THREAD_ID_WHERE + " AND " + VIEWED_RECEIPT_COUNT + " = ? " + "AND NOT (" + getOutgoingTypeClause() + ") LIMIT 1)";
    final String[] hasUnviewedStoriesArgs  = SqlUtil.buildArgs(threadId, 0);
    final boolean  hasUnviewedStories;

    try (Cursor cursor = getReadableDatabase().rawQuery(hasUnviewedStoriesQuery, hasUnviewedStoriesArgs)) {
      hasUnviewedStories = cursor != null && cursor.moveToFirst() && !cursor.isNull(0) && cursor.getInt(0) == 1;
    }

    if (hasUnviewedStories) {
      return StoryViewState.UNVIEWED;
    } else {
      return StoryViewState.VIEWED;
    }
  }

  @Override
  public @NonNull MessageId getStoryId(@NonNull RecipientId authorId, long sentTimestamp) throws NoSuchMessageException {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String[]       projection = new String[]{ID, RECIPIENT_ID};
    String         where      = IS_STORY_CLAUSE + " AND " + DATE_SENT + " = ?";
    String[]       whereArgs  = SqlUtil.buildArgs(sentTimestamp);
    try (Cursor cursor = database.query(TABLE_NAME, projection, where, whereArgs, null, null, null, "1")) {
      if (cursor != null && cursor.moveToFirst()) {
        RecipientId rowRecipientId = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID)));
        if (Recipient.self().getId().equals(authorId) || rowRecipientId.equals(authorId)) {
          return new MessageId(CursorUtil.requireLong(cursor, ID), true);
        }
      }
    }
    throw new NoSuchMessageException("No story sent at " + sentTimestamp);
  }

  @Override
  public @NonNull List<RecipientId> getAllStoriesRecipientsList() {
    SQLiteDatabase    db    = databaseHelper.getSignalReadableDatabase();
    String            query = "SELECT " +
                              "DISTINCT " + ThreadDatabase.RECIPIENT_ID + " " +
                              "FROM " + TABLE_NAME + " JOIN " + ThreadDatabase.TABLE_NAME + " " +
                              "ON " + TABLE_NAME + "." + THREAD_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.ID + " " +
                              "WHERE " + IS_STORY_CLAUSE + " " +
                              "ORDER BY " + TABLE_NAME + "." + DATE_SENT + " DESC";
    List<RecipientId> recipientIds;
    try (Cursor cursor = db.rawQuery(query, null)) {
      if (cursor != null) {
        recipientIds = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
          recipientIds.add(RecipientId.from(CursorUtil.requireLong(cursor, ThreadDatabase.RECIPIENT_ID)));
        }
        return recipientIds;
      }
    }
    return Collections.emptyList();
  }

  @Override
  public @NonNull Cursor getStoryReplies(long parentStoryId) {
    String   where     = PARENT_STORY_ID + " = ?";
    String[] whereArgs = SqlUtil.buildArgs(parentStoryId);
    return rawQuery(where, whereArgs, true, 0);
  }

  @Override
  public long getUnreadStoryCount() {
    String[] columns   = new String[]{"COUNT(*)"};
    String   where     = IS_STORY_CLAUSE + " AND NOT (" + getOutgoingTypeClause() + ") AND " + VIEWED_RECEIPT_COUNT + " = 0";

    try (Cursor cursor = getReadableDatabase().query(TABLE_NAME, columns, where, null, null, null, null, null)) {
      return cursor != null && cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  @Override
  public int getNumberOfStoryReplies(long parentStoryId) {
    SQLiteDatabase db        = databaseHelper.getSignalReadableDatabase();
    String[]       columns   = new String[]{"COUNT(*)"};
    String         where     = PARENT_STORY_ID + " = ?";
    String[]       whereArgs = SqlUtil.buildArgs(parentStoryId);

    try (Cursor cursor = db.query(TABLE_NAME, columns, where, whereArgs, null, null, null, null)) {
      return cursor != null && cursor.moveToNext() ? cursor.getInt(0) : 0;
    }
  }

  @Override
  public boolean containsStories(long threadId) {
    SQLiteDatabase db        = databaseHelper.getSignalReadableDatabase();
    String[]       columns   = new String[]{"1"};
    String         where     = THREAD_ID_WHERE + " AND " + STORY_TYPE + " > 0";
    String[]       whereArgs = SqlUtil.buildArgs(threadId);

    try (Cursor cursor = db.query(TABLE_NAME, columns, where, whereArgs, null, null, null, "1")) {
      return cursor != null && cursor.moveToNext();
    }
  }

  @Override
  public boolean hasSelfReplyInStory(long parentStoryId) {
    SQLiteDatabase db        = databaseHelper.getSignalReadableDatabase();
    String[]       columns   = new String[]{"COUNT(*)"};
    String         where     = PARENT_STORY_ID + " = ? AND " + RECIPIENT_ID + " = ?";
    String[]       whereArgs = SqlUtil.buildArgs(parentStoryId, Recipient.self().getId());

    try (Cursor cursor = db.query(TABLE_NAME, columns, where, whereArgs, null, null, null, null)) {
      return cursor != null && cursor.moveToNext() && cursor.getInt(0) > 0;
    }
  }

  @Override
  public @Nullable Long getOldestStorySendTimestamp() {
    SQLiteDatabase db        = databaseHelper.getSignalReadableDatabase();
    String[]       columns   = new String[]{DATE_SENT};
    String         where     = IS_STORY_CLAUSE;
    String         orderBy   = DATE_SENT + " ASC";
    String         limit     = "1";

    try (Cursor cursor = db.query(TABLE_NAME, columns, where, null, null, null, orderBy, limit)) {
      return cursor != null && cursor.moveToNext() ? cursor.getLong(0) : null;
    }
  }

  @Override
  public int deleteStoriesOlderThan(long timestamp) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    db.beginTransaction();
    try {
      String   storiesBeforeTimestampWhere = IS_STORY_CLAUSE + " AND " + DATE_SENT + " < ?";
      String[] sharedArgs                  = SqlUtil.buildArgs(timestamp);
      String   deleteStoryRepliesQuery     = "DELETE FROM " + TABLE_NAME + " " +
              "WHERE " + PARENT_STORY_ID + " > 0 AND " + PARENT_STORY_ID + " IN (" +
              "SELECT " + ID + " " +
              "FROM " + TABLE_NAME + " " +
              "WHERE " + storiesBeforeTimestampWhere +
              ")";
      String   disassociateQuoteQuery      = "UPDATE " + TABLE_NAME + " " +
              "SET " + QUOTE_MISSING + " = 1, " + QUOTE_BODY + " = '' " +
              "WHERE " + PARENT_STORY_ID + " < 0 AND ABS(" + PARENT_STORY_ID + ") IN (" +
              "SELECT " + ID + " " +
              "FROM " + TABLE_NAME + " " +
              "WHERE " + storiesBeforeTimestampWhere +
              ")";

      db.execSQL(deleteStoryRepliesQuery, sharedArgs);
      db.execSQL(disassociateQuoteQuery, sharedArgs);

      try (Cursor cursor = db.query(TABLE_NAME, new String[]{RECIPIENT_ID}, storiesBeforeTimestampWhere, sharedArgs, null, null, null)) {
        while (cursor != null && cursor.moveToNext()) {
          RecipientId recipientId = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID)));
          ApplicationDependencies.getDatabaseObserver().notifyStoryObservers(recipientId);
        }
      }

      int deletedStoryCount;
      try (Cursor cursor = db.query(TABLE_NAME, new String[]{ID}, storiesBeforeTimestampWhere, sharedArgs, null, null, null)) {
        deletedStoryCount = cursor.getCount();

        while (cursor.moveToNext()) {
          long id = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
          deleteMessage(id);
        }
      }

      db.setTransactionSuccessful();
      return deletedStoryCount;
    } finally {
      db.endTransaction();
    }
  }

  @Override
  public boolean isGroupQuitMessage(long messageId) {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

    String[] columns = new String[]{ID};
    long     type    = Types.getOutgoingEncryptedMessageType() | Types.GROUP_LEAVE_BIT;
    String   query   = ID + " = ? AND " + MESSAGE_BOX + " & " + type + " = " + type + " AND " + MESSAGE_BOX + " & " + Types.GROUP_V2_BIT + " = 0";
    String[] args    = SqlUtil.buildArgs(messageId);

    try (Cursor cursor = db.query(TABLE_NAME, columns, query, args, null, null, null, null)) {
      if (cursor.getCount() == 1) {
        return true;
      }
    }
    return false;
  }
  @Override
  public long getLatestGroupQuitTimestamp(long threadId, long quitTimeBarrier) {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

    String[] columns = new String[]{DATE_SENT};
    long     type    = Types.getOutgoingEncryptedMessageType() | Types.GROUP_LEAVE_BIT;
    String   query   = THREAD_ID + " = ? AND " + MESSAGE_BOX + " & " + type + " = " + type + " AND " + MESSAGE_BOX + " & " + Types.GROUP_V2_BIT + " = 0 AND " + DATE_SENT + " < ?";
    String[] args    = new String[]{String.valueOf(threadId), String.valueOf(quitTimeBarrier)};
    String   orderBy = DATE_SENT + " DESC";
    String   limit   = "1";

    try (Cursor cursor = db.query(TABLE_NAME, columns, query, args, null, null, orderBy, limit)) {
      if (cursor.moveToFirst()) {
        return CursorUtil.requireLong(cursor, DATE_SENT);
      }
    }
    return -1;
  }

  @Override
  public int getMessageCountForThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

    String   query = THREAD_ID + " = ? AND " + STORY_TYPE + " = ? AND " + PARENT_STORY_ID + " <= ?";
    String[] args  = SqlUtil.buildArgs(threadId, 0, 0);

    try (Cursor cursor = db.query(TABLE_NAME, COUNT, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }
    }

    return 0;
  }

  @Override
  public int getMessageCountForThread(long threadId, long beforeTime) {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

    String   query = THREAD_ID + " = ? AND " + DATE_RECEIVED + " < ? AND " + STORY_TYPE + " = ? AND " + PARENT_STORY_ID + " <= ?";
    String[] args  = SqlUtil.buildArgs(threadId, beforeTime, 0, 0);

    try (Cursor cursor = db.query(TABLE_NAME, COUNT, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }
    }

    return 0;
  }

  @Override
  public boolean hasMeaningfulMessage(long threadId) {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { "1" }, THREAD_ID_WHERE, SqlUtil.buildArgs(threadId), null, null, null, "1")) {
      return cursor != null && cursor.moveToFirst();
    }
  }

  @Override
  public void addFailures(long messageId, List<NetworkFailure> failure) {
    try {
      addToDocument(messageId, NETWORK_FAILURE, failure, NetworkFailureSet.class);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  public void setNetworkFailures(long messageId, Set<NetworkFailure> failures) {
    try {
      setDocument(databaseHelper.getSignalWritableDatabase(), messageId, NETWORK_FAILURE, new NetworkFailureSet(failures));
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  @Override
  public Set<MessageUpdate> incrementReceiptCount(SyncMessageId messageId, long timestamp, @NonNull ReceiptType receiptType) {
    SQLiteDatabase     database       = databaseHelper.getSignalWritableDatabase();
    Set<MessageUpdate> messageUpdates = new HashSet<>();

    try (Cursor cursor = database.query(TABLE_NAME, new String[] {ID, THREAD_ID, MESSAGE_BOX, RECIPIENT_ID, receiptType.getColumnName(), RECEIPT_TIMESTAMP},
                                        DATE_SENT + " = ?", new String[] {String.valueOf(messageId.getTimetamp())},
                                        null, null, null, null))
    {
      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(CursorUtil.requireLong(cursor, MESSAGE_BOX))) {
          RecipientId theirRecipientId = RecipientId.from(CursorUtil.requireLong(cursor, RECIPIENT_ID));
          RecipientId ourRecipientId   = messageId.getRecipientId();
          String      columnName       = receiptType.getColumnName();
          if (ourRecipientId.equals(theirRecipientId) || Recipient.resolved(theirRecipientId).isGroup()) {
            long    id               = CursorUtil.requireLong(cursor, ID);
            long    threadId         = CursorUtil.requireLong(cursor, THREAD_ID);
            int     status           = receiptType.getGroupStatus();
            boolean isFirstIncrement = CursorUtil.requireLong(cursor, columnName) == 0;
            long    savedTimestamp   = CursorUtil.requireLong(cursor, RECEIPT_TIMESTAMP);
            long    updatedTimestamp = isFirstIncrement ? Math.max(savedTimestamp, timestamp) : savedTimestamp;
            database.execSQL("UPDATE " + TABLE_NAME + " SET " +
                                     columnName + " = " + columnName + " + 1, " +
                                     RECEIPT_TIMESTAMP + " = ? WHERE " +
                                     ID + " = ?",
                             SqlUtil.buildArgs(updatedTimestamp, id));

            SignalDatabase.groupReceipts().update(ourRecipientId, id, status, timestamp);

            messageUpdates.add(new MessageUpdate(threadId, new MessageId(id, true)));
          }
        }
      }

      if (messageUpdates.size() > 0 && receiptType == ReceiptType.DELIVERY) {
        earlyDeliveryReceiptCache.increment(messageId.getTimetamp(), messageId.getRecipientId(), timestamp);
      }
    }

    String columnName = receiptType.getColumnName();
    for (MessageId storyMessageId : SignalDatabase.storySends().getStoryMessagesFor(messageId)) {
      database.execSQL("UPDATE " + TABLE_NAME + " SET " +
                               columnName + " = " + columnName + " + 1, " +
                               RECEIPT_TIMESTAMP + " = CASE " +
                               "WHEN " + columnName + " = 0 THEN MAX(" + RECEIPT_TIMESTAMP + ", ?) " +
                               "ELSE " + RECEIPT_TIMESTAMP + " " +
                               "END " +
                               "WHERE " + ID + " = ?",
                       SqlUtil.buildArgs(timestamp, storyMessageId.getId()));

      SignalDatabase.groupReceipts().update(messageId.getRecipientId(), storyMessageId.getId(), receiptType.getGroupStatus(), timestamp);

      messageUpdates.add(new MessageUpdate(-1, storyMessageId));
    }

    return messageUpdates;
  }

  @Override
  public long getThreadIdForMessage(long id) {
    String sql        = "SELECT " + THREAD_ID + " FROM " + TABLE_NAME + " WHERE " + ID + " = ?";
    String[] sqlArgs  = new String[] {id+""};
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();

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

  private long getThreadIdFor(@NonNull IncomingMediaMessage retrieved) {
    if (retrieved.getGroupId() != null) {
      RecipientId groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(retrieved.getGroupId());
      Recipient   groupRecipients  = Recipient.resolved(groupRecipientId);
      return org.thoughtcrime.securesms.database.SignalDatabase.threads().getOrCreateThreadIdFor(groupRecipients);
    } else {
      Recipient sender = Recipient.resolved(retrieved.getFrom());
      return org.thoughtcrime.securesms.database.SignalDatabase.threads().getOrCreateThreadIdFor(sender);
    }
  }

  private long getThreadIdFor(@NonNull NotificationInd notification) {
    String fromString = notification.getFrom() != null && notification.getFrom().getTextString() != null
                        ? Util.toIsoString(notification.getFrom().getTextString())
                        : "";
    Recipient recipient = Recipient.external(context, fromString);
    return org.thoughtcrime.securesms.database.SignalDatabase.threads().getOrCreateThreadIdFor(recipient);
  }

  private Cursor rawQuery(@NonNull String where, @Nullable String[] arguments) {
    return rawQuery(where, arguments, false, 0);
  }

  private Cursor rawQuery(@NonNull String where, @Nullable String[] arguments, boolean reverse, long limit) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    String rawQueryString   = "SELECT " + Util.join(MMS_PROJECTION, ",") +
            " FROM " + MmsDatabase.TABLE_NAME +  " LEFT OUTER JOIN " + AttachmentDatabase.TABLE_NAME +
            " ON (" + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " = " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + ")" +
            " WHERE " + where + " GROUP BY " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID;

    if (reverse) {
      rawQueryString += " ORDER BY " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " DESC";
    }

    if (limit > 0) {
      rawQueryString += " LIMIT " + limit;
    }

    return database.rawQuery(rawQueryString, arguments);
  }

  private Cursor internalGetMessage(long messageId) {
    return rawQuery(RAW_ID_WHERE, new String[] {messageId + ""});
  }

  @Override
  public MessageRecord getMessageRecord(long messageId) throws NoSuchMessageException {
    try (Cursor cursor = rawQuery(RAW_ID_WHERE, new String[] {messageId + ""})) {
      MessageRecord record = new Reader(cursor).getNext();

      if (record == null) {
        throw new NoSuchMessageException("No message for ID: " + messageId);
      }

      return record;
    }
  }

  @Override
  public @Nullable MessageRecord getMessageRecordOrNull(long messageId) {
    try (Cursor cursor = rawQuery(RAW_ID_WHERE, new String[] {messageId + ""})) {
      return new Reader(cursor).getNext();
    }
  }

  @Override
  public Reader getMessages(Collection<Long> messageIds) {
    String ids = TextUtils.join(",", messageIds);
    return readerFor(rawQuery(MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " IN (" + ids + ")", null));
  }

//AA+
  @Override
  @NonNull public Reader getGroupUpdateMessages() {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    String where = MESSAGE_BOX + " & " + (Types.GROUP_UPDATE_BIT) + " != 0";

    Cursor c = database.rawQuery("SELECT _id, date, msg_box, " + MESSAGE_TYPE +
            " FROM " + MmsDatabase.TABLE_NAME +
            " WHERE " + where +
            " ORDER BY date DESC  LIMIT 1", null);
    return readerFor(c);
  }

  @Override
  @NonNull public Reader getMessageByTimestamp(long timestamp, boolean received) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    String where = DATE_SENT + " = " + String.valueOf(timestamp);

    Cursor c=database.rawQuery("SELECT _id, date, " + UFSRV_COMMAND +", " + MESSAGE_TYPE +
            " FROM " + MmsDatabase.TABLE_NAME +
            " WHERE " + where +
            " ORDER BY date DESC  LIMIT 1", null);
    return readerFor(c);
  }

  //todo update columns
  public Reader getMessageByGid(long gid) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    String where = UFSRV_GID + " = " + String.valueOf(gid);

    Cursor c=database.rawQuery("SELECT _id, date, " + UFSRV_COMMAND +", " + MESSAGE_TYPE +
                                       " FROM " + MmsDatabase.TABLE_NAME +
                                       " WHERE " + where +
                                       " ORDER BY date DESC  LIMIT 1", null);
    return readerFor(c);
  }
//

  private void updateMailboxBitmask(long id, long maskOff, long maskOn, Optional<Long> threadId) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    db.beginTransaction();
    try {
      db.execSQL("UPDATE " + TABLE_NAME +
                     " SET " + MESSAGE_BOX + " = (" + MESSAGE_BOX + " & " + (Types.TOTAL_MASK - maskOff) + " | " + maskOn + " )" +
                     " WHERE " + ID + " = ?", new String[] { id + "" });

      if (threadId.isPresent()) {
        org.thoughtcrime.securesms.database.SignalDatabase.threads().updateSnippetTypeSilently(threadId.get());
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  @Override
  public void markAsOutbox(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_OUTBOX_TYPE, Optional.of(threadId));
  }

  @Override
  public void markAsForcedSms(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.PUSH_MESSAGE_BIT, Types.MESSAGE_FORCE_SMS_BIT, Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  @Override
  public void markAsRateLimited(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, 0, Types.MESSAGE_RATE_LIMITED_BIT, Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  @Override
  public void clearRateLimitStatus(@NonNull Collection<Long> ids) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    db.beginTransaction();
    try {
      for (long id : ids) {
        long threadId = getThreadIdForMessage(id);
        updateMailboxBitmask(id, Types.MESSAGE_RATE_LIMITED_BIT, 0, Optional.of(threadId));
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  @Override
  public void markAsPendingInsecureSmsFallback(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_PENDING_INSECURE_SMS_FALLBACK, Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  @Override
  public void markAsSending(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENDING_TYPE, Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  @Override
  public void markUfsrvStatus(long messageId, int status) {
    long threadId = getThreadIdForMessage(messageId);
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    db.execSQL("UPDATE " + TABLE_NAME +
                       " SET " + UFSRV_STATUS + " = ?" +
                       " WHERE " + ID + " = ?", new String[] {status+"", messageId + ""});

    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  //AA+
  @Override
  public @NonNull Pair<Long, Long> insertMessageReportedTypeLog(SmsMessageRecord messageRecord, long threadId, boolean unread) {
    throw new UnsupportedOperationException();
  }

  //AA+
  @Override
  public @NonNull Pair<Long, Long> insertGuardianTypeLog(SmsMessageRecord messageRecord, long threadId, boolean unread) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @NonNull Pair<Long, Long> insertProfileLog (long fid, @NonNull RecipientId recipientId, long timestamp, String encodedUfsrvMsg, boolean unread) {
    throw new UnsupportedOperationException();

  }

  @Override
  public @NonNull Pair<Long, Long> insertGroupProfileLog(long fid, @NonNull RecipientId recipientId, long timestamp, String encodedUfsrvMsg, boolean unread) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void markAsSentFailed(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENT_FAILED_TYPE, Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
    ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners();
  }

  @Override
  public void markAsSent(long messageId, boolean secure) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENT_TYPE | (secure ? Types.PUSH_MESSAGE_BIT | Types.SECURE_MESSAGE_BIT : 0), Optional.of(threadId));
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
    ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners();
  }

  @Override
  public void markAsRemoteDelete(long messageId) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    long threadId;

    boolean deletedAttachments = false;

    db.beginTransaction();
    try {
      ContentValues values = new ContentValues();
      values.put(REMOTE_DELETED, 1);
      values.putNull(BODY);
      values.putNull(QUOTE_BODY);
      values.putNull(QUOTE_AUTHOR);
      values.putNull(QUOTE_ATTACHMENT);
      values.putNull(QUOTE_ID);
      values.putNull(LINK_PREVIEWS);
      values.putNull(SHARED_CONTACTS);
      db.update(TABLE_NAME, values, ID_WHERE, new String[] { String.valueOf(messageId) });

      deletedAttachments = SignalDatabase.attachments().deleteAttachmentsForMessage(messageId);
      SignalDatabase.mentions().deleteMentionsForMessage(messageId);
      SignalDatabase.messageLog().deleteAllRelatedToMessage(messageId, true);
      SignalDatabase.reactions().deleteReactions(new MessageId(messageId, true));

      threadId = getThreadIdForMessage(messageId);
      SignalDatabase.threads().update(threadId, false);

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
    ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners();

    if (deletedAttachments) {
      ApplicationDependencies.getDatabaseObserver().notifyAttachmentObservers();
    }
  }

  @Override
  public void markDownloadState(long messageId, long state) {
    SQLiteDatabase database     = databaseHelper.getSignalWritableDatabase();
    ContentValues contentValues = new ContentValues();
    contentValues.put(STATUS, state);

    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {messageId + ""});
    ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
  }

  @Override
  public void markAsInsecure(long messageId) {
    updateMailboxBitmask(messageId, Types.SECURE_MESSAGE_BIT, 0, Optional.empty());
  }

  @Override
  public void markUnidentified(long messageId, boolean unidentified) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(UNIDENTIFIED, unidentified ? 1 : 0);

    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});
  }

  @Override
  public void markExpireStarted(long id) {
    markExpireStarted(id, System.currentTimeMillis());
  }

  @Override
  public void markExpireStarted(long id, long startedTimestamp) {
    markExpireStarted(Collections.singleton(id), startedTimestamp);
  }

  @Override
  public void markExpireStarted(Collection<Long> ids, long startedAtTimestamp) {
    SQLiteDatabase db       = databaseHelper.getSignalWritableDatabase();
    long           threadId = -1;

    db.beginTransaction();
    try {
      String query = ID + " = ? AND (" + EXPIRE_STARTED + " = 0 OR " + EXPIRE_STARTED + " > ?)";

      for (long id : ids) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(EXPIRE_STARTED, startedAtTimestamp);

        db.update(TABLE_NAME, contentValues, query, new String[]{String.valueOf(id), String.valueOf(startedAtTimestamp)});

        if (threadId < 0) {
          threadId = getThreadIdForMessage(id);
        }
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    org.thoughtcrime.securesms.database.SignalDatabase.threads().update(threadId, false);
    notifyConversationListeners(threadId);
  }

  @Override
  public void markAsNotified(long id) {
    SQLiteDatabase database      = databaseHelper.getSignalWritableDatabase();
    ContentValues  contentValues = new ContentValues();

    contentValues.put(NOTIFIED, 1);
    contentValues.put(REACTIONS_LAST_SEEN, System.currentTimeMillis());

    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});
  }

  @Override
  public List<MarkedMessageInfo> setMessagesReadSince(long threadId, long sinceTimestamp) {
    if (sinceTimestamp == -1) {
      return setMessagesRead(THREAD_ID + " = ? AND " + STORY_TYPE + " = 0 AND " + PARENT_STORY_ID + " <= 0 AND (" + READ + " = 0 OR (" + REACTIONS_UNREAD + " = 1 AND (" + getOutgoingTypeClause() + ")))", new String[] { String.valueOf(threadId)});
    } else {
      return setMessagesRead(THREAD_ID + " = ? AND " + STORY_TYPE + " = 0 AND " + PARENT_STORY_ID + " <= 0 AND (" + READ + " = 0 OR (" + REACTIONS_UNREAD + " = 1 AND ( " + getOutgoingTypeClause() + " ))) AND " + DATE_RECEIVED + " <= ?", new String[]{ String.valueOf(threadId), String.valueOf(sinceTimestamp)});
    }
  }

  @Override
  public List<MarkedMessageInfo> setEntireThreadRead(long threadId) {
    return setMessagesRead(THREAD_ID + " = ? AND " + STORY_TYPE + " = 0 AND " + PARENT_STORY_ID + " <= 0", new String[] { String.valueOf(threadId)});
  }

  @Override
  public List<MarkedMessageInfo> setAllMessagesRead() {
    return setMessagesRead(STORY_TYPE + " = 0 AND " + PARENT_STORY_ID + " <= 0 AND (" + READ + " = 0 OR (" + REACTIONS_UNREAD + " = 1 AND (" + getOutgoingTypeClause() + ")))", null);
  }

  private List<MarkedMessageInfo> setMessagesRead(String where, String[] arguments) {
    SQLiteDatabase          database         = databaseHelper.getSignalWritableDatabase();
    List<MarkedMessageInfo> result           = new LinkedList<>();
    Cursor                  cursor           = null;
    RecipientId             releaseChannelId = SignalStore.releaseChannelValues().getReleaseChannelRecipientId();

    database.beginTransaction();

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, RECIPIENT_ID, DATE_SENT, MESSAGE_BOX, EXPIRES_IN, EXPIRE_STARTED, THREAD_ID, UFSRV_COMMAND }, where, arguments, null, null, null);//AA+ ufsrvcmd

      while(cursor != null && cursor.moveToNext()) {
        if (Types.isSecureType(CursorUtil.requireLong(cursor, MESSAGE_BOX))) {
          long           threadId       = CursorUtil.requireLong(cursor, THREAD_ID);
          RecipientId    recipientId    = RecipientId.from(CursorUtil.requireLong(cursor, RECIPIENT_ID));
          long           dateSent       = CursorUtil.requireLong(cursor, DATE_SENT);
          long           messageId      = CursorUtil.requireLong(cursor, ID);
          long           expiresIn      = CursorUtil.requireLong(cursor, EXPIRES_IN);
          long           expireStarted  = CursorUtil.requireLong(cursor, EXPIRE_STARTED);
          //AA+
          UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier = UfsrvMessageUtils.UfsrvMessageIdentifierFromEncoded(cursor.getString(Math.abs(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND))), dateSent);
          if (messageIdentifier != null) {//added because of https://github.com/signalapp/Signal-Android/commit/133a7d2576e9a0bb58e7df8e2488c9d8584371c7
            SyncMessageId syncMessageId = new SyncMessageId(recipientId, dateSent, messageIdentifier.uidOriginator, messageIdentifier.fid, messageIdentifier.gid, messageIdentifier.eid);
            //
            ExpirationInfo expirationInfo = new ExpirationInfo(messageId, expiresIn, expireStarted, true);

            if (!recipientId.equals(releaseChannelId)) {
              result.add(new MarkedMessageInfo(threadId, syncMessageId, new MessageId(messageId, true), expirationInfo));
            }
          }
        }
      }

      ContentValues contentValues = new ContentValues();
      contentValues.put(READ, 1);
      contentValues.put(REACTIONS_UNREAD, 0);
      contentValues.put(REACTIONS_LAST_SEEN, System.currentTimeMillis());

      database.update(TABLE_NAME, contentValues, where, arguments);
      database.setTransactionSuccessful();
    } finally {
      if (cursor != null) cursor.close();
      database.endTransaction();
    }

    return result;
  }

  @Override
  @NonNull MmsSmsDatabase.TimestampReadResult setTimestampRead(SyncMessageId messageId, long proposedExpireStarted, @NonNull Map<Long, Long> threadToLatestRead) {
    SQLiteDatabase         database   = databaseHelper.getSignalWritableDatabase();
    List<Pair<Long, Long>> expiring   = new LinkedList<>();
    String[]               projection = new String[] { ID, THREAD_ID, MESSAGE_BOX, EXPIRES_IN, EXPIRE_STARTED, RECIPIENT_ID };
    String                 query      = DATE_SENT + " = ?";
    String[]               args       = SqlUtil.buildArgs(messageId.getTimetamp());
    List<Long>             threads    = new LinkedList<>();

    try (Cursor cursor = database.query(TABLE_NAME, projection, query, args, null, null, null)) {
      while (cursor.moveToNext()) {
        RecipientId theirRecipientId = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID)));
        RecipientId ourRecipientId   = messageId.getRecipientId();
        if (ourRecipientId.equals(theirRecipientId) || Recipient.resolved(theirRecipientId).isGroup() || ourRecipientId.equals(Recipient.self().getId())) {
          long id            = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
          long threadId      = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));
          long expiresIn     = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_IN));
          long expireStarted = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRE_STARTED));
          expireStarted = expireStarted > 0 ? Math.min(proposedExpireStarted, expireStarted) : proposedExpireStarted;
          ContentValues values = new ContentValues();
          values.put(READ, 1);
          values.put(REACTIONS_UNREAD, 0);
          values.put(REACTIONS_LAST_SEEN, System.currentTimeMillis());
          if (expiresIn > 0) {
            values.put(EXPIRE_STARTED, expireStarted);
            expiring.add(new Pair<>(id, expiresIn));
          }

          database.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(id));

          threads.add(threadId);

          Long latest = threadToLatestRead.get(threadId);
          threadToLatestRead.put(threadId, (latest != null) ? Math.max(latest, messageId.getTimetamp()) : messageId.getTimetamp());
        }
      }
    }

    return new MmsSmsDatabase.TimestampReadResult(expiring, threads);
  }

  @Override
  public @Nullable Pair<RecipientId, Long> getOldestUnreadMentionDetails(long threadId) {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String[]       projection = new String[]{RECIPIENT_ID,DATE_RECEIVED};
    String         selection  = THREAD_ID + " = ? AND " + READ + " = 0 AND " + MENTIONS_SELF + " = 1";
    String[]       args       = SqlUtil.buildArgs(threadId);

    try (Cursor cursor = database.query(TABLE_NAME, projection, selection, args, null, null, DATE_RECEIVED + " ASC", "1")) {
      if (cursor != null && cursor.moveToFirst()) {
        return new Pair<>(RecipientId.from(CursorUtil.requireString(cursor, RECIPIENT_ID)), CursorUtil.requireLong(cursor, DATE_RECEIVED));
      }
    }

    return null;
  }

  @Override
  public int getUnreadMentionCount(long threadId) {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String[]       projection = new String[]{"COUNT(*)"};
    String         selection  = THREAD_ID + " = ? AND " + READ + " = 0 AND " + MENTIONS_SELF + " = 1";
    String[]       args       = SqlUtil.buildArgs(threadId);

    try (Cursor cursor = database.query(TABLE_NAME, projection, selection, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }
    }

    return 0;
  }

  /**
   * Trims data related to expired messages. Only intended to be run after a backup restore.
   */
  void trimEntriesForExpiredMessages() {
    SQLiteDatabase database         = databaseHelper.getSignalWritableDatabase();
    String         trimmedCondition = " NOT IN (SELECT " + MmsDatabase.ID + " FROM " + MmsDatabase.TABLE_NAME + ")";

    database.delete(GroupReceiptDatabase.TABLE_NAME, GroupReceiptDatabase.MMS_ID + trimmedCondition, null);

    String[] columns = new String[] { AttachmentDatabase.ROW_ID, AttachmentDatabase.UNIQUE_ID, AttachmentDatabase.UFID }; //AA+ ufid
    String   where   = AttachmentDatabase.MMS_ID + trimmedCondition;

    try (Cursor cursor = database.query(AttachmentDatabase.TABLE_NAME, columns, where, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        org.thoughtcrime.securesms.database.SignalDatabase.attachments().deleteAttachment(new AttachmentId(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)));//AA+ 3rd arg
      }
    }

    org.thoughtcrime.securesms.database.SignalDatabase.mentions().deleteAbandonedMentions();

    try (Cursor cursor = database.query(ThreadDatabase.TABLE_NAME, new String[] { ThreadDatabase.ID }, ThreadDatabase.EXPIRES_IN + " > 0", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        org.thoughtcrime.securesms.database.SignalDatabase.threads().setLastScrolled(cursor.getLong(0), 0);
        org.thoughtcrime.securesms.database.SignalDatabase.threads().update(cursor.getLong(0), false);
      }
    }
  }

  //AA+ update ufsrv body
  @Override
  @NonNull public Pair<Long, Long> updateMessageUfsrvCommand(long messageId, String ufsrvCommand) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET " + UFSRV_COMMAND + " = ? " +
                    "WHERE " + ID + " = ?",
            new String[] {ufsrvCommand, messageId + ""});

    long threadId = getThreadIdForMessage(messageId);

    Log.d(TAG, "updateMessageUfsrvCommand: USING MSGID: '"+messageId+"' UPDATED UFSRVCOMMAND with:'"+ufsrvCommand+"'");
    //todo: is this necessary for our context?
    //SignalDatabase.threads().update(threadId, true);
    //ApplicationDependencies.getDatabaseObserver().notifyMessageUpdateObservers(new MessageId(messageId, true));
    //notifyConversationListListeners();

    return new Pair<>(messageId, threadId);
  }

  @Override
  @NonNull public Pair<Long, Long> updateMessageIdentifiersForUfsrv (long timestamp, long ufsrvGid, long ufsrvEid) {
    MmsDatabase.Reader msg = getMessageByTimestamp(timestamp, false);
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET " + UFSRV_EID + " = ?, " + UFSRV_GID + " = ? " +
                       "WHERE " + ID + " = ?",
               new String[] {ufsrvEid + "", ufsrvGid+"", msg.getId() + ""});

    long threadId = getThreadIdForMessage(msg.getId());
    long msgId = msg.getId();

    Log.d(TAG, "updateMessageIdentifiersForUfsrv: USING MSGID: '"+msgId+"' UPDATED UFSRVCOMMAND with:'"+ufsrvEid+"'");

    msg.close();

    return new Pair<>(msgId, threadId);
  }

  @Override
  public Optional<MmsNotificationInfo> getNotification(long messageId) {
    Cursor cursor = null;

    try {
      cursor = rawQuery(RAW_ID_WHERE, new String[] {String.valueOf(messageId)});

      if (cursor != null && cursor.moveToNext()) {
        return Optional.of(new MmsNotificationInfo(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID))),
                                                   cursor.getString(cursor.getColumnIndexOrThrow(CONTENT_LOCATION)),
                                                   cursor.getString(cursor.getColumnIndexOrThrow(TRANSACTION_ID)),
                                                   cursor.getInt(cursor.getColumnIndexOrThrow(SUBSCRIPTION_ID))));
      } else {
        return Optional.empty();
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  @Override
  public OutgoingMediaMessage getOutgoingMessage(long messageId)
          throws MmsException, NoSuchMessageException
  {
    AttachmentDatabase attachmentDatabase = org.thoughtcrime.securesms.database.SignalDatabase.attachments();
    MentionDatabase    mentionDatabase    = org.thoughtcrime.securesms.database.SignalDatabase.mentions();
    Cursor             cursor             = null;

    try {
      cursor = rawQuery(RAW_ID_WHERE, new String[] {String.valueOf(messageId)});

      if (cursor != null && cursor.moveToNext()) {
        List<DatabaseAttachment> associatedAttachments = attachmentDatabase.getAttachmentsForMessage(messageId);
        List<Mention>            mentions              = mentionDatabase.getMentionsForMessage(messageId);

        long             outboxType         = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX));
        String           body               = cursor.getString(cursor.getColumnIndexOrThrow(BODY));
        long             timestamp          = cursor.getLong(cursor.getColumnIndexOrThrow(NORMALIZED_DATE_SENT));
        int              subscriptionId     = cursor.getInt(cursor.getColumnIndexOrThrow(SUBSCRIPTION_ID));
        long             expiresIn          = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_IN));
        boolean          viewOnce           = cursor.getLong(cursor.getColumnIndexOrThrow(VIEW_ONCE)) == 1;
        long             recipientId        = cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID));
        long             threadId           = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));
        int              distributionType   = org.thoughtcrime.securesms.database.SignalDatabase.threads().getDistributionType(threadId);
        String           mismatchDocument   = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.MISMATCHED_IDENTITIES));
        String           networkDocument    = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.NETWORK_FAILURE));
        StoryType        storyType          = StoryType.fromCode(CursorUtil.requireInt(cursor, STORY_TYPE));
        ParentStoryId parentStoryId      = ParentStoryId.deserialize(CursorUtil.requireLong(cursor, PARENT_STORY_ID));

        long              quoteId            = cursor.getLong(cursor.getColumnIndexOrThrow(QUOTE_ID));
        long              quoteAuthor        = cursor.getLong(cursor.getColumnIndexOrThrow(QUOTE_AUTHOR));
        String            quoteText          = cursor.getString(cursor.getColumnIndexOrThrow(QUOTE_BODY));
        boolean           quoteMissing       = cursor.getInt(cursor.getColumnIndexOrThrow(QUOTE_MISSING)) == 1;
        List<Attachment>  quoteAttachments   = Stream.of(associatedAttachments).filter(Attachment::isQuote).map(a -> (Attachment)a).toList();
        List<Mention>     quoteMentions      = parseQuoteMentions(context, cursor);
        List<Contact>     contacts           = getSharedContacts(cursor, associatedAttachments);
        Set<Attachment>   contactAttachments = new HashSet<>(Stream.of(contacts).map(Contact::getAvatarAttachment).filter(a -> a != null).toList());
        List<LinkPreview> previews           = getLinkPreviews(cursor, associatedAttachments);
        Set<Attachment>   previewAttachments = Stream.of(previews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).collect(Collectors.toSet());
        List<Attachment>  attachments        = Stream.of(associatedAttachments).filterNot(Attachment::isQuote)
                .filterNot(contactAttachments::contains)
                .filterNot(previewAttachments::contains)
                .sorted(new DatabaseAttachment.DisplayOrderComparator())
                .map(a -> (Attachment)a).toList();

        //AA+
        String            ufsrvCommandBodyEncoded  = cursor.getString(cursor.getColumnIndexOrThrow(UFSRV_COMMAND));
        //

        Recipient                recipient       = Recipient.resolved(RecipientId.from(recipientId));
        Set<NetworkFailure>      networkFailures = new HashSet<>();
        Set<IdentityKeyMismatch> mismatches      = new HashSet<>();
        QuoteModel               quote           = null;

        if (quoteId > 0 && quoteAuthor > 0 && (!TextUtils.isEmpty(quoteText) || !quoteAttachments.isEmpty())) {
          quote = new QuoteModel(quoteId, RecipientId.from(quoteAuthor), quoteText, quoteMissing, quoteAttachments, quoteMentions);
        }

        if (!TextUtils.isEmpty(mismatchDocument)) {
          try {
            mismatches = JsonUtils.fromJson(mismatchDocument, IdentityKeyMismatchSet.class).getItems();
          } catch (IOException e) {
            Log.w(TAG, e);
          }
        }

        if (!TextUtils.isEmpty(networkDocument)) {
          try {
            networkFailures = JsonUtils.fromJson(networkDocument, NetworkFailureSet.class).getItems();
          } catch (IOException e) {
            Log.w(TAG, e);
          }
        }

        if (body != null && (Types.isGroupQuit(outboxType) || Types.isGroupUpdate(outboxType))) {
          return new OutgoingGroupUpdateMessage(recipient, new MessageGroupContext(body, Types.isGroupV2(outboxType)), attachments, timestamp, 0, false, quote, contacts, previews, mentions, ufsrvCommandBodyEncoded); //AA+ ufsrvbody
        } else if (Types.isExpirationTimerUpdate(outboxType)) {
          return new OutgoingExpirationUpdateMessage(recipient, timestamp, expiresIn, ufsrvCommandBodyEncoded); //AA+ ufsrvbody
        }

        OutgoingMediaMessage message = new OutgoingMediaMessage(recipient,
                                                                body,
                                                                attachments,
                                                                timestamp,
                                                                subscriptionId,
                                                                expiresIn,
                                                                viewOnce,
                                                                distributionType,
                                                                storyType,
                                                                parentStoryId,
                                                                Types.isStoryReaction(outboxType),
                                                                quote,
                                                                contacts,
                                                                previews,
                                                                mentions,
                                                                networkFailures,
                                                                mismatches,
                                                                ufsrvCommandBodyEncoded);//AA+ ufsrv

        if (Types.isSecureType(outboxType)) {
          return new OutgoingSecureMediaMessage(message);
        }

        return message;
      }

      throw new NoSuchMessageException("No record found for id: " + messageId);
    } catch (IOException e) {
      throw new MmsException(e);
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }


  private static List<Contact> getSharedContacts(@NonNull Cursor cursor, @NonNull List<DatabaseAttachment> attachments) {
    String serializedContacts = cursor.getString(cursor.getColumnIndexOrThrow(SHARED_CONTACTS));

    if (TextUtils.isEmpty(serializedContacts)) {
      return Collections.emptyList();
    }

    Map<AttachmentId, DatabaseAttachment> attachmentIdMap = new HashMap<>();
    for (DatabaseAttachment attachment : attachments) {
      attachmentIdMap.put(attachment.getAttachmentId(), attachment);
    }

    try {
      List<Contact> contacts     = new LinkedList<>();
      JSONArray     jsonContacts = new JSONArray(serializedContacts);

      for (int i = 0; i < jsonContacts.length(); i++) {
        Contact contact = Contact.deserialize(jsonContacts.getJSONObject(i).toString());

        if (contact.getAvatar() != null && contact.getAvatar().getAttachmentId() != null) {
          DatabaseAttachment attachment    = attachmentIdMap.get(contact.getAvatar().getAttachmentId());
          Avatar             updatedAvatar = new Avatar(contact.getAvatar().getAttachmentId(),
                                                        attachment,
                                                        contact.getAvatar().isProfile());
          contacts.add(new Contact(contact, updatedAvatar));
        } else {
          contacts.add(contact);
        }
      }

      return contacts;
    } catch (JSONException | IOException e) {
      Log.w(TAG, "Failed to parse shared contacts.", e);
    }

    return Collections.emptyList();
  }

  private static List<LinkPreview> getLinkPreviews(@NonNull Cursor cursor, @NonNull List<DatabaseAttachment> attachments) {
    String serializedPreviews = cursor.getString(cursor.getColumnIndexOrThrow(LINK_PREVIEWS));

    if (TextUtils.isEmpty(serializedPreviews)) {
      return Collections.emptyList();
    }

    Map<AttachmentId, DatabaseAttachment> attachmentIdMap = new HashMap<>();
    for (DatabaseAttachment attachment : attachments) {
      attachmentIdMap.put(attachment.getAttachmentId(), attachment);
    }

    try {
      List<LinkPreview> previews     = new LinkedList<>();
      JSONArray         jsonPreviews = new JSONArray(serializedPreviews);

      for (int i = 0; i < jsonPreviews.length(); i++) {
        LinkPreview preview = LinkPreview.deserialize(jsonPreviews.getJSONObject(i).toString());

        if (preview.getAttachmentId() != null) {
          DatabaseAttachment attachment = attachmentIdMap.get(preview.getAttachmentId());
          if (attachment != null) {
            previews.add(new LinkPreview(preview.getUrl(), preview.getTitle(), preview.getDescription(), preview.getDate(), attachment));
          } else {
            previews.add(preview);
          }
        } else {
          previews.add(preview);
        }
      }

      return previews;
    } catch (JSONException | IOException e) {
      Log.w(TAG, "Failed to parse shared contacts.", e);
    }

    return Collections.emptyList();
  }

  private Optional<InsertResult> insertMessageInbox(IncomingMediaMessage retrieved,
                                                    String contentLocation,
                                                    long threadId, long mailbox)
          throws MmsException
  {
    if (threadId == -1 || retrieved.isGroupMessage()) {
      threadId = getThreadIdFor(retrieved);
    }

    ContentValues contentValues = new ContentValues();

    contentValues.put(DATE_SENT, retrieved.getSentTimeMillis());
    contentValues.put(DATE_SERVER, retrieved.getServerTimeMillis());
    contentValues.put(RECIPIENT_ID, retrieved.getFrom().serialize());

    contentValues.put(MESSAGE_BOX, mailbox);
    contentValues.put(MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF);
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(CONTENT_LOCATION, contentLocation);
    contentValues.put(STATUS, Status.DOWNLOAD_INITIALIZED);
    contentValues.put(DATE_RECEIVED, retrieved.isPushMessage() ? retrieved.getReceivedTimeMillis() : generatePduCompatTimestamp(retrieved.getReceivedTimeMillis()));
    contentValues.put(PART_COUNT, retrieved.getAttachments().size());
    contentValues.put(SUBSCRIPTION_ID, retrieved.getSubscriptionId());
    contentValues.put(EXPIRES_IN, retrieved.getExpiresIn());
    contentValues.put(VIEW_ONCE, retrieved.isViewOnce() ? 1 : 0);
    contentValues.put(STORY_TYPE, retrieved.getStoryType().getCode());
    contentValues.put(PARENT_STORY_ID, retrieved.getParentStoryId() != null ? retrieved.getParentStoryId().serialize() : 0);
    contentValues.put(READ, retrieved.isExpirationUpdate() ? 1 : 0);//AA doent display expiration messages as unread notification
    contentValues.put(UNIDENTIFIED, retrieved.isUnidentified());
    contentValues.put(SERVER_GUID, retrieved.getServerGuid());

    if (!contentValues.containsKey(DATE_SENT)) {
      contentValues.put(DATE_SENT, contentValues.getAsLong(DATE_RECEIVED));
    }

    List<Attachment> quoteAttachments = new LinkedList<>();

    if (retrieved.getQuote() != null) {
      contentValues.put(QUOTE_ID, retrieved.getQuote().getId());
      contentValues.put(QUOTE_BODY, retrieved.getQuote().getText().toString());
      contentValues.put(QUOTE_AUTHOR, retrieved.getQuote().getAuthor().serialize());
      contentValues.put(QUOTE_MISSING, retrieved.getQuote().isOriginalMissing() ? 1 : 0);

      BodyRangeList mentionsList = MentionUtil.mentionsToBodyRangeList(retrieved.getQuote().getMentions());
      if (mentionsList != null) {
        contentValues.put(QUOTE_MENTIONS, mentionsList.toByteArray());
      }

      quoteAttachments = retrieved.getQuote().getAttachments();
    }

    contentValues.put(UFSRV_COMMAND, retrieved.getUfsrvCommandEncoded());
    contentValues.put(UFSRV_EID, retrieved.getEid());//AA+
    contentValues.put(UFSRV_GID, retrieved.getGid());
    contentValues.put(UFSRV_FID, retrieved.getFid());
    contentValues.put(UFSRV_CMD_TYPE, retrieved.getCommandType());
    contentValues.put(UFSRV_CMD_ARG, retrieved.getCommandArg());

    if (retrieved.isPushMessage() && isDuplicate(retrieved, threadId)) {
      Log.w(TAG, "Ignoring duplicate media message (" + retrieved.getSentTimeMillis() + ")");
      return Optional.empty();
    }

    long messageId = insertMediaMessage(threadId, retrieved.getBody(), retrieved.getAttachments(), quoteAttachments, retrieved.getSharedContacts(), retrieved.getLinkPreviews(), retrieved.getMentions(), retrieved.getMessageRanges(), contentValues, null, true);

    if (!Types.isExpirationTimerUpdate(mailbox) && !retrieved.getStoryType().isStory() && retrieved.getParentStoryId() == null) {
      SignalDatabase.threads().incrementUnread(threadId, 1);
      SignalDatabase.threads().update(threadId, true);
    }

    Log.d(TAG, String.format("insertMessageInbox (MasterSecretUnion): Inserted new IncomingMediaMessage (messageId:'%d', timestamp:'%d',  into threadId:'%d')", messageId, retrieved.getSentTimeMillis(), threadId));

    notifyConversationListeners(threadId);

    return Optional.of(new InsertResult(messageId, threadId));
  }

  public Optional<InsertResult> insertMessageInbox(IncomingMediaMessage retrieved,
                                                   String contentLocation, long threadId)
          throws MmsException
  {
    long type = Types.BASE_INBOX_TYPE;

    if (retrieved.isPushMessage()) {
      type |= Types.PUSH_MESSAGE_BIT;
    }

    if (retrieved.isExpirationUpdate()) {
      type |= Types.EXPIRATION_TIMER_UPDATE_BIT;
    }

    return insertMessageInbox(retrieved, contentLocation, threadId, type);
  }

  public Optional<InsertResult> insertSecureDecryptedMessageInbox(IncomingMediaMessage retrieved,
                                                                  long threadId)
          throws MmsException
  {
    long type = Types.BASE_INBOX_TYPE | Types.SECURE_MESSAGE_BIT;

    if (retrieved.isPushMessage()) {
      type |= Types.PUSH_MESSAGE_BIT;
    }

    if (retrieved.isExpirationUpdate()) {
      type |= Types.EXPIRATION_TIMER_UPDATE_BIT;
    }

    if (retrieved.isStoryReaction()) {
      type |= Types.SPECIAL_TYPE_STORY_REACTION;
    }

    return insertMessageInbox(retrieved, "", threadId, type);
  }

  public Pair<Long, Long> insertMessageInbox(@NonNull NotificationInd notification, int subscriptionId) {
    SQLiteDatabase       db             = databaseHelper.getSignalWritableDatabase();
    long                 threadId       = getThreadIdFor(notification);
    ContentValues        contentValues  = new ContentValues();
    ContentValuesBuilder contentBuilder = new ContentValuesBuilder(contentValues);

    Log.w(TAG, "Message received type: " + notification.getMessageType());


    contentBuilder.add(CONTENT_LOCATION, notification.getContentLocation());
    contentBuilder.add(DATE_SENT, System.currentTimeMillis());
    contentBuilder.add(EXPIRY, notification.getExpiry());
    contentBuilder.add(MESSAGE_SIZE, notification.getMessageSize());
    contentBuilder.add(TRANSACTION_ID, notification.getTransactionId());
    contentBuilder.add(MESSAGE_TYPE, notification.getMessageType());

    if (notification.getFrom() != null) {
      Recipient recipient = Recipient.external(context, Util.toIsoString(notification.getFrom().getTextString()));
      contentValues.put(RECIPIENT_ID, recipient.getId().serialize());
    } else {
      contentValues.put(RECIPIENT_ID, RecipientId.UNKNOWN.serialize());
    }

    contentValues.put(MESSAGE_BOX, Types.BASE_INBOX_TYPE);
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(STATUS, Status.DOWNLOAD_INITIALIZED);
    contentValues.put(DATE_RECEIVED, generatePduCompatTimestamp(System.currentTimeMillis()));
    contentValues.put(READ, Util.isDefaultSmsProvider(context) ? 0 : 1);
    contentValues.put(SUBSCRIPTION_ID, subscriptionId);

    if (!contentValues.containsKey(DATE_SENT))
      contentValues.put(DATE_SENT, contentValues.getAsLong(DATE_RECEIVED));

    long messageId = db.insert(TABLE_NAME, null, contentValues);

    return new Pair<>(messageId, threadId);
  }

  @Override
  public @NonNull InsertResult insertChatSessionRefreshedMessage(@NonNull RecipientId recipientId, long senderDeviceId, long sentTimestamp) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertBadDecryptMessage(@NonNull RecipientId recipientId, int senderDevice, long sentTimestamp, long receivedTimestamp, long threadId) {
    throw new UnsupportedOperationException();
  }

  public void markIncomingNotificationReceived(long threadId) {
    notifyConversationListeners(threadId);

    if (org.thoughtcrime.securesms.util.Util.isDefaultSmsProvider(context)) {
      org.thoughtcrime.securesms.database.SignalDatabase.threads().incrementUnread(threadId, 1);
    }

    org.thoughtcrime.securesms.database.SignalDatabase.threads().update(threadId, true);

    TrimThreadJob.enqueueAsync(threadId);
  }

  @Override
  public long insertMessageOutbox(@NonNull OutgoingMediaMessage message,
                                  long threadId,
                                  boolean forceSms,
                                  @Nullable SmsDatabase.InsertListener insertListener)
          throws MmsException
  {
    return insertMessageOutbox(message, threadId, forceSms, GroupReceiptDatabase.STATUS_UNDELIVERED, insertListener);
  }

  @Override
  public long insertMessageOutbox(@NonNull OutgoingMediaMessage message,
                                  long threadId, boolean forceSms, int defaultReceiptStatus,
                                  @Nullable SmsDatabase.InsertListener insertListener)
          throws MmsException
  {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    long type = Types.BASE_SENDING_TYPE;

    if (message.isSecure()) type |= (Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT);
    if (forceSms)           type |= Types.MESSAGE_FORCE_SMS_BIT;

    if (message.isGroup()) {
      OutgoingGroupUpdateMessage outgoingGroupUpdateMessage = (OutgoingGroupUpdateMessage) message;
      if (outgoingGroupUpdateMessage.isV2Group()) {
        type |= Types.GROUP_V2_BIT | Types.GROUP_UPDATE_BIT;
        if (outgoingGroupUpdateMessage.isJustAGroupLeave()) {
          type |= Types.GROUP_LEAVE_BIT;
        }
      } else {
        MessageGroupContext.GroupV1Properties properties = outgoingGroupUpdateMessage.requireGroupV1Properties();
        if      (properties.isUpdate()) type |= Types.GROUP_UPDATE_BIT;
        else if (properties.isQuit())   type |= Types.GROUP_LEAVE_BIT;
      }
    }

    if (message.isExpirationUpdate()) {
      type |= Types.EXPIRATION_TIMER_UPDATE_BIT;
    }

    if (message.isStoryReaction()) {
      type |= Types.SPECIAL_TYPE_STORY_REACTION;
    }

    Map<RecipientId, EarlyReceiptCache.Receipt> earlyDeliveryReceipts = earlyDeliveryReceiptCache.remove(message.getSentTimeMillis());

    ContentValues contentValues = new ContentValues();
    contentValues.put(DATE_SENT, message.getSentTimeMillis());

    contentValues.put(MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ);

    contentValues.put(MESSAGE_BOX, type);
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(READ, 1);
    contentValues.put(DATE_RECEIVED, System.currentTimeMillis());
    contentValues.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    contentValues.put(EXPIRES_IN, message.getExpiresIn());
    contentValues.put(VIEW_ONCE, message.isViewOnce());
    contentValues.put(RECIPIENT_ID, message.getRecipient().getId().serialize());
    contentValues.put(DELIVERY_RECEIPT_COUNT, Stream.of(earlyDeliveryReceipts.values()).mapToLong(EarlyReceiptCache.Receipt::getCount).sum());
    contentValues.put(RECEIPT_TIMESTAMP, Stream.of(earlyDeliveryReceipts.values()).mapToLong(EarlyReceiptCache.Receipt::getTimestamp).max().orElse(-1));
    contentValues.put(STORY_TYPE, message.getStoryType().getCode());
    contentValues.put(PARENT_STORY_ID, message.getParentStoryId() != null ? message.getParentStoryId().serialize() : 0);

    if (message.getRecipient().isSelf() && hasAudioAttachment(message.getAttachments())) {
      contentValues.put(VIEWED_RECEIPT_COUNT, 1L);
    }

    //AA+ serialised
    if (TextUtils.isEmpty(message.getUfsrvCommandBody())) Log.w(TAG, "insertMessageOutbox: Serialised ufsrvCommand was empty");
    contentValues.put(UFSRV_COMMAND, message.getUfsrvCommandBody());

    //AA+ this IMPORTANT insertion is now done by the respective InsertxxxMessage() for each Database.
    //ThreadDatabase threadDatabase=SignalDatabase.threads;
    //threadDatabase.updateUfsrvCommandWire(threadId, System.currentTimeMillis(), message.getUfsrvCommandBody(), masterSecret.getMasterSecret().get());
    //

    List<Attachment> quoteAttachments = new LinkedList<>();

    if (message.getOutgoingQuote() != null) {
      MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithPlaceholders(message.getOutgoingQuote().getText(), message.getOutgoingQuote().getMentions());

      contentValues.put(QUOTE_ID, message.getOutgoingQuote().getId());
      contentValues.put(QUOTE_AUTHOR, message.getOutgoingQuote().getAuthor().serialize());
      contentValues.put(QUOTE_BODY, updated.getBodyAsString());
      contentValues.put(QUOTE_MISSING, message.getOutgoingQuote().isOriginalMissing() ? 1 : 0);

      BodyRangeList mentionsList = MentionUtil.mentionsToBodyRangeList(updated.getMentions());
      if (mentionsList != null) {
        contentValues.put(QUOTE_MENTIONS, mentionsList.toByteArray());
      }

      quoteAttachments.addAll(message.getOutgoingQuote().getAttachments());
    }

    MentionUtil.UpdatedBodyAndMentions updatedBodyAndMentions = MentionUtil.updateBodyAndMentionsWithPlaceholders(message.getBody(), message.getMentions());

    long messageId = insertMediaMessage(threadId, updatedBodyAndMentions.getBodyAsString(), message.getAttachments(), quoteAttachments, message.getSharedContacts(), message.getLinkPreviews(), updatedBodyAndMentions.getMentions(), null, contentValues, insertListener, false);

    if (message.getRecipient().isGroup()) {
      OutgoingGroupUpdateMessage outgoingGroupUpdateMessage = (message instanceof OutgoingGroupUpdateMessage) ? (OutgoingGroupUpdateMessage) message : null;

      GroupReceiptDatabase receiptDatabase = SignalDatabase.groupReceipts();
      Set<RecipientId>     members         = new HashSet<>();

      if (outgoingGroupUpdateMessage != null && outgoingGroupUpdateMessage.isV2Group()) {
        MessageGroupContext.GroupV2Properties groupV2Properties = outgoingGroupUpdateMessage.requireGroupV2Properties();
        members.addAll(Stream.of(groupV2Properties.getAllActivePendingAndRemovedMembers())
                                                  .distinct()
                                                  .map(uuid -> RecipientId.from(ServiceId.from(uuid), null))
                                                  .toList());
        members.remove(Recipient.self().getId());
      } else {
        members.addAll(Stream.of(SignalDatabase.groups().getGroupMembers(message.getRecipient().requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF)).map(Recipient::getId).toList());
      }

      receiptDatabase.insert(members, messageId, defaultReceiptStatus, message.getSentTimeMillis());

      for (RecipientId recipientId : earlyDeliveryReceipts.keySet()) {
        receiptDatabase.update(recipientId, messageId, GroupReceiptDatabase.STATUS_DELIVERED, -1);
      }
    } else if (message.getRecipient().isDistributionList()) {
      GroupReceiptDatabase receiptDatabase = SignalDatabase.groupReceipts();
      List<RecipientId>    members         = SignalDatabase.distributionLists().getMembers(message.getRecipient().requireDistributionListId());

      receiptDatabase.insert(members, messageId, defaultReceiptStatus, message.getSentTimeMillis());

      for (RecipientId recipientId : earlyDeliveryReceipts.keySet()) {
        receiptDatabase.update(recipientId, messageId, GroupReceiptDatabase.STATUS_DELIVERED, -1);
      }
    }

    SignalDatabase.threads().updateLastSeenAndMarkSentAndLastScrolledSilenty(threadId);

    if (!message.getStoryType().isStory()) {
      ApplicationDependencies.getDatabaseObserver().notifyMessageInsertObservers(threadId, new MessageId(messageId, true));
    } else {
      ApplicationDependencies.getDatabaseObserver().notifyStoryObservers(message.getRecipient().getId());
    }

    notifyConversationListListeners();

    TrimThreadJob.enqueueAsync(threadId);

    return messageId;
  }

  private boolean hasAudioAttachment(@NonNull List<Attachment> attachments) {
    for (Attachment attachment : attachments) {
      if (MediaUtil.isAudio(attachment)) {
        return true;
      }
    }

    return false;
  }

  private long insertMediaMessage(long threadId,
                                  @Nullable String body,
                                  @NonNull List<Attachment> attachments,
                                  @NonNull List<Attachment> quoteAttachments,
                                  @NonNull List<Contact> sharedContacts,
                                  @NonNull List<LinkPreview> linkPreviews,
                                  @NonNull List<Mention> mentions,
                                  @Nullable BodyRangeList messageRanges,
                                  @NonNull ContentValues contentValues,
                                  @Nullable InsertListener insertListener,
                                  boolean updateThread)
          throws MmsException
  {
    SQLiteDatabase     db              = databaseHelper.getSignalWritableDatabase();
    AttachmentDatabase partsDatabase   = org.thoughtcrime.securesms.database.SignalDatabase.attachments();
    MentionDatabase    mentionDatabase = org.thoughtcrime.securesms.database.SignalDatabase.mentions();

    boolean mentionsSelf = Stream.of(mentions).filter(m -> Recipient.resolved(m.getRecipientId()).isSelf()).findFirst().isPresent();

    List<Attachment> allAttachments     = new LinkedList<>();
    List<Attachment> contactAttachments = Stream.of(sharedContacts).map(Contact::getAvatarAttachment).filter(a -> a != null).toList();
    List<Attachment> previewAttachments = Stream.of(linkPreviews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).toList();

    allAttachments.addAll(attachments);
    allAttachments.addAll(contactAttachments);
    allAttachments.addAll(previewAttachments);

    contentValues.put(BODY, body);
    contentValues.put(PART_COUNT, allAttachments.size());
    contentValues.put(MENTIONS_SELF, mentionsSelf ? 1 : 0);

    if (messageRanges != null) {
      contentValues.put(MESSAGE_RANGES, messageRanges.toByteArray());
    }

    db.beginTransaction();
    try {
      long messageId = db.insert(TABLE_NAME, null, contentValues);

      mentionDatabase.insert(threadId, messageId, mentions);

      Map<Attachment, AttachmentId> insertedAttachments = partsDatabase.insertAttachmentsForMessage(messageId, allAttachments, quoteAttachments);
      String                        serializedContacts  = getSerializedSharedContacts(insertedAttachments, sharedContacts);
      String                        serializedPreviews  = getSerializedLinkPreviews(insertedAttachments, linkPreviews);

      if (!TextUtils.isEmpty(serializedContacts)) {
        ContentValues contactValues = new ContentValues();
        contactValues.put(SHARED_CONTACTS, serializedContacts);

        SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
        int rows = database.update(TABLE_NAME, contactValues, ID + " = ?", new String[]{ String.valueOf(messageId) });

        if (rows <= 0) {
          Log.w(TAG, "Failed to update message with shared contact data.");
        }
      }

      if (!TextUtils.isEmpty(serializedPreviews)) {
        ContentValues contactValues = new ContentValues();
        contactValues.put(LINK_PREVIEWS, serializedPreviews);

        SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
        int rows = database.update(TABLE_NAME, contactValues, ID + " = ?", new String[]{ String.valueOf(messageId) });

        if (rows <= 0) {
          Log.w(TAG, "Failed to update message with link preview data.");
        }
      }

      db.setTransactionSuccessful();
      return messageId;
    } finally {
      db.endTransaction();

      if (insertListener != null) {
        insertListener.onComplete();
      }

      long contentValuesThreadId = contentValues.getAsLong(THREAD_ID);

      if (updateThread) {
        org.thoughtcrime.securesms.database.SignalDatabase.threads().setLastScrolled(contentValuesThreadId, 0);
        org.thoughtcrime.securesms.database.SignalDatabase.threads().update(threadId, true);
      }
    }
  }

  @Override
  public boolean deleteMessage(long messageId) {
    Log.d(TAG, "deleteMessage(" + messageId + ")");

    long               threadId           = getThreadIdForMessage(messageId);
    AttachmentDatabase attachmentDatabase = SignalDatabase.attachments();
    attachmentDatabase.deleteAttachmentsForMessage(messageId);

    GroupReceiptDatabase groupReceiptDatabase = SignalDatabase.groupReceipts();
    groupReceiptDatabase.deleteRowsForMessage(messageId);

    MentionDatabase mentionDatabase = SignalDatabase.mentions();
    mentionDatabase.deleteMentionsForMessage(messageId);

    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    database.delete(TABLE_NAME, ID_WHERE, new String[] {messageId+""});

    SignalDatabase.threads().setLastScrolled(threadId, 0);

    boolean threadDeleted = SignalDatabase.threads().update(threadId, false);
    notifyConversationListeners(threadId);
    notifyStickerListeners();
    notifyStickerPackListeners();
    return threadDeleted;
  }

  @Override
  public void deleteThread(long threadId) {
    Log.d(TAG, "deleteThread(" + threadId + ")");

    Set<Long> singleThreadSet = new HashSet<>();
    singleThreadSet.add(threadId);
    deleteThreads(singleThreadSet);
  }

  private @Nullable String getSerializedSharedContacts(@NonNull Map<Attachment, AttachmentId> insertedAttachmentIds, @NonNull List<Contact> contacts) {
    if (contacts.isEmpty()) return null;

    JSONArray sharedContactJson = new JSONArray();

    for (Contact contact : contacts) {
      try {
        AttachmentId attachmentId = null;

        if (contact.getAvatarAttachment() != null) {
          attachmentId = insertedAttachmentIds.get(contact.getAvatarAttachment());
        }

        Avatar  updatedAvatar  = new Avatar(attachmentId,
                                            contact.getAvatarAttachment(),
                                            contact.getAvatar() != null && contact.getAvatar().isProfile());
        Contact updatedContact = new Contact(contact, updatedAvatar);

        sharedContactJson.put(new JSONObject(updatedContact.serialize()));
      } catch (JSONException | IOException e) {
        Log.w(TAG, "Failed to serialize shared contact. Skipping it.", e);
      }
    }
    return sharedContactJson.toString();
  }

  private @Nullable String getSerializedLinkPreviews(@NonNull Map<Attachment, AttachmentId> insertedAttachmentIds, @NonNull List<LinkPreview> previews) {
    if (previews.isEmpty()) return null;

    JSONArray linkPreviewJson = new JSONArray();

    for (LinkPreview preview : previews) {
      try {
        AttachmentId attachmentId = null;

        if (preview.getThumbnail().isPresent()) {
          attachmentId = insertedAttachmentIds.get(preview.getThumbnail().get());
        }

        LinkPreview updatedPreview = new LinkPreview(preview.getUrl(), preview.getTitle(), preview.getDescription(), preview.getDate(), attachmentId);
        linkPreviewJson.put(new JSONObject(updatedPreview.serialize()));
      } catch (JSONException | IOException e) {
        Log.w(TAG, "Failed to serialize shared contact. Skipping it.", e);
      }
    }
    return linkPreviewJson.toString();
  }

  private boolean isDuplicate(IncomingMediaMessage message, long threadId) {
    SQLiteDatabase db    = databaseHelper.getSignalReadableDatabase();
    String         query = DATE_SENT + " = ? AND " + RECIPIENT_ID + " = ? AND " + THREAD_ID + " = ?";
    String[]       args  = SqlUtil.buildArgs(message.getSentTimeMillis(), message.getFrom().serialize(), threadId);

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { "1" }, query, args, null, null, null, "1")) {
      return cursor.moveToFirst();
    }
  }

  @Override
  public boolean isSent(long messageId) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    try (Cursor cursor = database.query(TABLE_NAME, new String[] {  MESSAGE_BOX }, ID + " = ?", new String[] { String.valueOf(messageId)}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        long type = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX));
        return Types.isSentType(type);
      }
    }
    return false;
  }

  @Override
  public List<MessageRecord> getProfileChangeDetailsRecords(long threadId, long afterTimestamp) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<Long> getAllRateLimitedMessageIds() {
    SQLiteDatabase db    = databaseHelper.getSignalReadableDatabase();
    String         where = "(" + MESSAGE_BOX + " & " + Types.TOTAL_MASK + " & " + Types.MESSAGE_RATE_LIMITED_BIT + ") > 0";

    Set<Long> ids = new HashSet<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { ID }, where, null, null, null, null)) {
      while (cursor.moveToNext()) {
        ids.add(CursorUtil.requireLong(cursor, ID));
      }
    }

    return ids;
  }

  @Override
  void deleteThreads(@NonNull Set<Long> threadIds) {
    Log.d(TAG, "deleteThreads(count: " + threadIds.size() + ")");

    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    String where      = "";

    for (long threadId : threadIds) {
      where += THREAD_ID + " = '" + threadId + "' OR ";
    }

    where = where.substring(0, where.length() - 4);

    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ID}, where, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        deleteMessage(cursor.getLong(0));
      }
    }
  }

  @Override
  int deleteMessagesInThreadBeforeDate(long threadId, long date) {
    SQLiteDatabase db    = databaseHelper.getSignalWritableDatabase();
    String         where = THREAD_ID + " = ? AND " + DATE_RECEIVED + " < " + date;

    return db.delete(TABLE_NAME, where, SqlUtil.buildArgs(threadId));
  }

  @Override
  void deleteAbandonedMessages() {
    SQLiteDatabase db    = databaseHelper.getSignalWritableDatabase();
    String         where = THREAD_ID + " NOT IN (SELECT _id FROM " + ThreadDatabase.TABLE_NAME + ")";

    int deletes = db.delete(TABLE_NAME, where, null);
    if (deletes > 0) {
      Log.i(TAG, "Deleted " + deletes + " abandoned messages");
    }
  }

  @Override
  public List<MessageRecord> getMessagesInThreadAfterInclusive(long threadId, long timestamp, long limit) {
    String   where = TABLE_NAME + "." + MmsSmsColumns.THREAD_ID + " = ? AND " +
            TABLE_NAME + "." + getDateReceivedColumnName() + " >= ?";
    String[] args  = SqlUtil.buildArgs(threadId, timestamp);

    try (Reader reader = readerFor(rawQuery(where, args, false, limit))) {
      List<MessageRecord> results = new ArrayList<>(reader.cursor.getCount());

      while (reader.getNext() != null) {
        results.add(reader.getCurrent());
      }

      return results;
    }
  }

  @Override
  public void deleteAllThreads() {
    Log.d(TAG, "deleteAllThreads()");

    org.thoughtcrime.securesms.database.SignalDatabase.attachments().deleteAllAttachments();
    SignalDatabase.groupReceipts().deleteAllRows();
    org.thoughtcrime.securesms.database.SignalDatabase.mentions().deleteAllMentions();

    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    database.delete(TABLE_NAME, null, null);
  }

  @Override
  public @Nullable ViewOnceExpirationInfo getNearestExpiringViewOnceMessage() {
    SQLiteDatabase       db                = databaseHelper.getSignalReadableDatabase();
    ViewOnceExpirationInfo info              = null;
    long                 nearestExpiration = Long.MAX_VALUE;

    String   query = "SELECT " +
            TABLE_NAME + "." + ID + ", " +
            VIEW_ONCE + ", " +
            DATE_RECEIVED + " " +
            "FROM " + TABLE_NAME + " INNER JOIN " + AttachmentDatabase.TABLE_NAME + " " +
            "ON " + TABLE_NAME + "." + ID + " = " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + " " +
            "WHERE " +
            VIEW_ONCE + " > 0 AND " +
            "(" + AttachmentDatabase.DATA + " NOT NULL OR " + AttachmentDatabase.TRANSFER_STATE + " != ?)";
    String[] args = new String[] { String.valueOf(AttachmentDatabase.TRANSFER_PROGRESS_DONE) };

    try (Cursor cursor = db.rawQuery(query, args)) {
      while (cursor != null && cursor.moveToNext()) {
        long id              = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        long dateReceived    = cursor.getLong(cursor.getColumnIndexOrThrow(DATE_RECEIVED));
        long expiresAt       = dateReceived + ViewOnceUtil.MAX_LIFESPAN;

        if (info == null || expiresAt < nearestExpiration) {
          info              = new ViewOnceExpirationInfo(id, dateReceived);
          nearestExpiration = expiresAt;
        }
      }
    }

    return info;
  }

  private static @NonNull List<Mention> parseQuoteMentions(@NonNull Context context, Cursor cursor) {
    byte[] raw = cursor.getBlob(cursor.getColumnIndexOrThrow(QUOTE_MENTIONS));

    return MentionUtil.bodyRangeListToMentions(context, raw);
  }

  @Override
  public SQLiteDatabase beginTransaction() {
    databaseHelper.getSignalWritableDatabase().beginTransaction();
    return databaseHelper.getSignalWritableDatabase();
  }

  @Override
  public void setTransactionSuccessful() {
    databaseHelper.getSignalWritableDatabase().setTransactionSuccessful();
  }

  @Override
  public void endTransaction() {
    databaseHelper.getSignalWritableDatabase().endTransaction();
  }

  public static Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public static OutgoingMessageReader readerFor(OutgoingMediaMessage message, long threadId) {
    return new OutgoingMessageReader(message, threadId);
  }

  public static class Status {
    public static final int DOWNLOAD_INITIALIZED     = 1;
    public static final int DOWNLOAD_NO_CONNECTIVITY = 2;
    public static final int DOWNLOAD_CONNECTING      = 3;
    public static final int DOWNLOAD_SOFT_FAILURE    = 4;
    public static final int DOWNLOAD_HARD_FAILURE    = 5;
    public static final int DOWNLOAD_APN_UNAVAILABLE = 6;
  }

  public static class OutgoingMessageReader {

    private final Context              context;
    private final OutgoingMediaMessage message;
    private final long                 id;
    private final long                 threadId;

    public OutgoingMessageReader(OutgoingMediaMessage message, long threadId) {
      this.context  = ApplicationDependencies.getApplication();
      this.message  = message;
      this.id       = new SecureRandom().nextLong();
      this.threadId = threadId;
    }

    public MessageRecord getCurrent() {
      SlideDeck slideDeck = new SlideDeck(context, message.getAttachments());

      CharSequence  quoteText     = message.getOutgoingQuote() != null ? message.getOutgoingQuote().getText() : null;
      List<Mention> quoteMentions = message.getOutgoingQuote() != null ? message.getOutgoingQuote().getMentions() : Collections.emptyList();

      if (quoteText != null && !quoteMentions.isEmpty()) {
        MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, quoteText, quoteMentions);

        quoteText     = updated.getBody();
        quoteMentions = updated.getMentions();
      }

      return new MediaMmsMessageRecord(id,
                                       message.getRecipient(),
                                       message.getRecipient(),
                                       1,
                                       System.currentTimeMillis(),
                                       System.currentTimeMillis(),
                                       -1,
                                       0,
                                       threadId, message.getBody(),
                                       slideDeck,
                                       slideDeck.getSlides().size(),
                                       message.isSecure() ? MmsSmsColumns.Types.getOutgoingEncryptedMessageType() : MmsSmsColumns.Types.getOutgoingSmsMessageType(),
                                       Collections.emptySet(),
                                       Collections.emptySet(),
                                       message.getSubscriptionId(),
                                       message.getExpiresIn(),
                                       System.currentTimeMillis(),
                                       message.isViewOnce(),
                                       0,
                                       message.getOutgoingQuote() != null ?
                                       new Quote(message.getOutgoingQuote().getId(),
                                                 message.getOutgoingQuote().getAuthor(),
                                                 quoteText,
                                                 message.getOutgoingQuote().isOriginalMissing(),
                                                 new SlideDeck(context, message.getOutgoingQuote().getAttachments()),
                                                 quoteMentions) :
                                       null,
                                       message.getSharedContacts(),
                                       message.getLinkPreviews(),
                                       false,
                                       Collections.emptyList(),
                                       false,
                                       false,
                                       0,
                                       0,
                                       -1,
                                       null,
                                       message.getStoryType(),
                                       message.getParentStoryId(),
                                       message.getUfsrvCommandWire(), 0, 0, 0, 0, 0, 0);
    }
  }

  public static class Reader implements MessageDatabase.Reader {

    private final Cursor  cursor;
    private final Context context;

    public Reader(Cursor cursor) {
      this.cursor  = cursor;
      this.context = ApplicationDependencies.getApplication();
    }

    @Override
    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    @Override
    public MessageRecord getCurrent() {
      long mmsType = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_TYPE));

      if (mmsType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
        return getNotificationMmsMessageRecord(cursor);
      } else {
        return getMediaMmsMessageRecord(cursor);
      }
    }

    //AA+
    public long getId() {
      if (cursor.moveToFirst())
       return cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.ID));
      else return 0;
    }
    //

    private NotificationMmsMessageRecord getNotificationMmsMessageRecord(Cursor cursor) {
      long      id                   = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.ID));
      long      dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_SENT));
      long      dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_RECEIVED));
      long      threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.THREAD_ID));
      long      mailbox              = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_BOX));
      long      recipientId          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.RECIPIENT_ID));
      int       addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS_DEVICE_ID));
      Recipient recipient            = Recipient.live(RecipientId.from(recipientId)).get();

      String        contentLocation      = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.CONTENT_LOCATION));
      String        transactionId        = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.TRANSACTION_ID));
      long          messageSize          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_SIZE));
      long          expiry               = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRY));
      int           status               = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.STATUS));
      int           deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.DELIVERY_RECEIPT_COUNT));
      int           readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.READ_RECEIPT_COUNT));
      int           subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.SUBSCRIPTION_ID));
      int           viewedReceiptCount   = cursor.getInt(cursor.getColumnIndexOrThrow(MmsSmsColumns.VIEWED_RECEIPT_COUNT));
      long          receiptTimestamp     = CursorUtil.requireLong(cursor, MmsSmsColumns.RECEIPT_TIMESTAMP);
      StoryType     storyType            = StoryType.fromCode(CursorUtil.requireInt(cursor, STORY_TYPE));
      ParentStoryId parentStoryId        = ParentStoryId.deserialize(CursorUtil.requireLong(cursor, PARENT_STORY_ID));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      //AA+ inflate the serialised/stored ufsrvCommand
      SignalServiceProtos.UfsrvCommandWire ufsrvCommand = null;

      String ufsrvCommandEncoded = cursor.getString(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND));
      if (ufsrvCommandEncoded != null) {
        try {
          ufsrvCommand = SignalServiceProtos.UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
          Log.d(TAG, "getNotificationMmsMessageRecord: Inflated UfsrvCommand.. type:'" + ufsrvCommand.getUfsrvtype().name() + "'. Has fence: " + ufsrvCommand.hasFenceCommand());
        } catch (IOException e) {
          Log.d(TAG, e.getMessage());
        }
      }
      //

      byte[]contentLocationBytes = null;
      byte[]transactionIdBytes   = null;

      if (!TextUtils.isEmpty(contentLocation))
        contentLocationBytes = org.thoughtcrime.securesms.util.Util.toIsoBytes(contentLocation);

      if (!TextUtils.isEmpty(transactionId))
        transactionIdBytes = org.thoughtcrime.securesms.util.Util.toIsoBytes(transactionId);

      SlideDeck slideDeck = new SlideDeck(context, new MmsNotificationAttachment(status, messageSize));


      return new NotificationMmsMessageRecord(id, recipient, recipient,
                                              addressDeviceId, dateSent, dateReceived, deliveryReceiptCount, threadId,
                                              contentLocationBytes, messageSize, expiry, status,
                                              transactionIdBytes, mailbox, subscriptionId, slideDeck,
                                              readReceiptCount, viewedReceiptCount, receiptTimestamp, storyType,
                                              parentStoryId,
                                              ufsrvCommand, 0);//AA+ ufsrv
    }

    private MediaMmsMessageRecord getMediaMmsMessageRecord(Cursor cursor) {
      long                 id                   = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.ID));
      long                 dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_SENT));
      long                 dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_RECEIVED));
      long                 dateServer           = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.DATE_SERVER));
      long                 box                  = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_BOX));
      long                 threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.THREAD_ID));
      long                 recipientId          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.RECIPIENT_ID));
      int                  addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS_DEVICE_ID));
      int                  deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.DELIVERY_RECEIPT_COUNT));
      int                  readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.READ_RECEIPT_COUNT));
      String               body                 = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.BODY));
      int                  partCount            = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.PART_COUNT));
      String               mismatchDocument     = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.MISMATCHED_IDENTITIES));
      String               networkDocument      = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.NETWORK_FAILURE));
      int                  subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.SUBSCRIPTION_ID));
      long                 expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRES_IN));
      long                 expireStarted        = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRE_STARTED));
      boolean              unidentified         = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.UNIDENTIFIED)) == 1;
      boolean              isViewOnce           = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.VIEW_ONCE))   == 1;
      boolean              remoteDelete         = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.REMOTE_DELETED))   == 1;
      boolean              mentionsSelf         = CursorUtil.requireBoolean(cursor, MENTIONS_SELF);
      long                 notifiedTimestamp    = CursorUtil.requireLong(cursor, NOTIFIED_TIMESTAMP);
      int                  viewedReceiptCount   = cursor.getInt(cursor.getColumnIndexOrThrow(MmsSmsColumns.VIEWED_RECEIPT_COUNT));
      long                 receiptTimestamp     = CursorUtil.requireLong(cursor, MmsSmsColumns.RECEIPT_TIMESTAMP);
      byte[]               messageRangesData    = CursorUtil.requireBlob(cursor, MESSAGE_RANGES);
      StoryType            storyType            = StoryType.fromCode(CursorUtil.requireInt(cursor, STORY_TYPE));
      ParentStoryId        parentStoryId        = ParentStoryId.deserialize(CursorUtil.requireLong(cursor, PARENT_STORY_ID));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;

        if (MmsSmsColumns.Types.isOutgoingMessageType(box)) {
          viewedReceiptCount = 0;
        }
      }

      //AA+ inflate the serialised/stored ufsrvCommand
      SignalServiceProtos.UfsrvCommandWire ufsrvCommand = null;

      String ufsrvCommandEncoded = cursor.getString(cursor.getColumnIndex(SmsDatabase.UFSRV_COMMAND));
      if (ufsrvCommandEncoded != null) {
        try {
          ufsrvCommand = UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
          //Log.d(TAG, "getMediaMmsMessageRecord: Inflated UfsrvCommand.. type:'" + ufsrvCommand.getUfsrvtype().name() + "'. Has fence: " + ufsrvCommand.hasFenceCommand());
        } catch (IOException e) {
          Log.d(TAG, e.getMessage());
        }
      }

      long eid = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.UFSRV_EID));
      long gid = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.UFSRV_GID));
      int status = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.UFSRV_STATUS));
      long fid = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.UFSRV_FID));
      int commandType = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.UFSRV_CMD_TYPE));
      int commandArg = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.UFSRV_CMD_ARG));
      //

      Recipient                recipient          = Recipient.live(RecipientId.from(recipientId)).get();
      Set<IdentityKeyMismatch> mismatches         = getMismatchedIdentities(mismatchDocument);
      Set<NetworkFailure>      networkFailures    = getFailures(networkDocument);
      List<DatabaseAttachment> attachments        = org.thoughtcrime.securesms.database.SignalDatabase.attachments().getAttachments(cursor);
      List<Contact>            contacts           = getSharedContacts(cursor, attachments);
      Set<Attachment>          contactAttachments = Stream.of(contacts).map(Contact::getAvatarAttachment).withoutNulls().collect(Collectors.toSet());
      List<LinkPreview>        previews           = getLinkPreviews(cursor, attachments);
      Set<Attachment>          previewAttachments = Stream.of(previews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).collect(Collectors.toSet());
      SlideDeck                slideDeck          = buildSlideDeck(context, Stream.of(attachments).filterNot(contactAttachments::contains).filterNot(previewAttachments::contains).toList());
      Quote                    quote              = getQuote(cursor);
      BodyRangeList            messageRanges      = null;

      try {
        if (messageRangesData != null) {
          messageRanges = BodyRangeList.parseFrom(messageRangesData);
        }
      } catch (InvalidProtocolBufferException e) {
        Log.w(TAG, "Error parsing message ranges", e);
      }

      return new MediaMmsMessageRecord(id, recipient, recipient,
                                       addressDeviceId, dateSent, dateReceived, dateServer, deliveryReceiptCount,
                                       threadId, body, slideDeck, partCount, box, mismatches,
                                       networkFailures, subscriptionId, expiresIn, expireStarted,
                                       isViewOnce, readReceiptCount, quote, contacts, previews, unidentified, Collections.emptyList(),
                                       remoteDelete, mentionsSelf, notifiedTimestamp, viewedReceiptCount, receiptTimestamp, messageRanges,
                                       storyType, parentStoryId,
                                       ufsrvCommand, gid, eid, fid, status, commandType, commandArg);//AA+  ufsrv
    }

    private Set<IdentityKeyMismatch> getMismatchedIdentities(String document) {
      if (!TextUtils.isEmpty(document)) {
        try {
          return JsonUtils.fromJson(document, IdentityKeyMismatchSet.class).getItems();
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      return Collections.emptySet();
    }

    private Set<NetworkFailure> getFailures(String document) {
      if (!TextUtils.isEmpty(document)) {
        try {
          return JsonUtils.fromJson(document, NetworkFailureSet.class).getItems();
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
        }
      }

      return Collections.emptySet();
    }

    public static SlideDeck buildSlideDeck(@NonNull Context context, @NonNull List<DatabaseAttachment> attachments) {
      List<DatabaseAttachment> messageAttachments = Stream.of(attachments)
              .filterNot(Attachment::isQuote)
              .sorted(new DatabaseAttachment.DisplayOrderComparator())
              .toList();
      return new SlideDeck(context, messageAttachments);
    }

    private @Nullable Quote getQuote(@NonNull Cursor cursor) {
      long                       quoteId          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_ID));
      long                       quoteAuthor      = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_AUTHOR));
      CharSequence               quoteText        = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_BODY));
      boolean                    quoteMissing     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_MISSING)) == 1;
      List<Mention>              quoteMentions    = parseQuoteMentions(context, cursor);
      List<DatabaseAttachment>   attachments      = org.thoughtcrime.securesms.database.SignalDatabase.attachments().getAttachments(cursor);
      List<? extends Attachment> quoteAttachments = Stream.of(attachments).filter(Attachment::isQuote).toList();
      SlideDeck                  quoteDeck        = new SlideDeck(context, quoteAttachments);

      if (quoteId > 0 && quoteAuthor > 0) {
        if (quoteText != null && !quoteMentions.isEmpty()) {
          MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, quoteText, quoteMentions);

          quoteText     = updated.getBody();
          quoteMentions = updated.getMentions();
        }

        return new Quote(quoteId, RecipientId.from(quoteAuthor), quoteText, quoteMissing, quoteDeck, quoteMentions);
      } else {
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

  private long generatePduCompatTimestamp(long time) {
    return time - (time % 1000);
  }

  //AA+
  @Override
  public int getUnreadThreadsCount() {
//    Cursor cursor = SignalDatabase.threads().getRecentConversationList(0, false, false);
    Cursor cursor = org.thoughtcrime.securesms.database.SignalDatabase.threads().getConversationList();
    int countUnread = 0;
    if (cursor != null) {
      if (cursor.moveToFirst()) {
        do {
          if(cursor.getInt(6) == 0) {
            countUnread++;
          }
        } while (cursor.moveToNext());
      }
    }
    return countUnread;
  }
}
