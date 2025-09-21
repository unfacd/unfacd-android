package org.thoughtcrime.securesms.database;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import com.unfacd.android.locallyaddressable.LocallyAddressable;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.locallyaddressable.LocallyAddressableUndefined;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.ui.components.intro_contact.IntroDirection;
import com.unfacd.android.ui.components.intro_contact.ResponseStatus;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;

import java.io.Closeable;
import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UnfacdIntroContactsDatabase extends Database {


  private static final String TAG = Log.tag(UnfacdIntroContactsDatabase.class);

  public static final String TABLE_NAME = "unfacd_intro_contacts";//todo fix intro db name

  private static final String ID                = "_id";
  public  static final String INTRO_DIRECTION   = "intro_direction";
  public  static final String ADDRESS           = "address"; //ufsrvuid address either provided by sender, or provided by server to the receiving end
  public  static final String AVATAR_ID         = "avatar_id";
  public  static final String RESPONSE_STATUS   = "response_status";
  public  static final String TS_SENT           = "ts_sent";
  public  static final String TS_RESPONSE       = "ts_response";
  public  static final String MSG               = "msg";
  public  static final String AVATAR            = "avatar";
  public  static final String NICKNAME          = "nickname"; //handle used by sender, where ufsrvuid wouldn't have been known to sender
  public  static final String EID               = "eid";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME +
                                            " (" + ID + " INTEGER PRIMARY KEY, " +
                                            ADDRESS + " TEXT NO NULL, " +
                                            NICKNAME + " TEXT, " +
                                            INTRO_DIRECTION + " INT DEFAULT 0, " +
                                            RESPONSE_STATUS + " INT DEFAULT 0, " +
                                            AVATAR_ID + " TEXT, " +
                                            MSG + " TEXT, " +
                                            TS_SENT + " INTEGER DEFAULT 0, " +
                                            TS_RESPONSE + " INTEGER DEFAULT 0, "+
                                            EID + " INTEGER DEFAULT 0, " +
                                            AVATAR + " BLOB " +
                                            ");";

  public static final String[] CREATE_INDEXS = new String[] {
         //"CREATE UNIQUE INDEX IF NOT EXISTS contact_intro_from_index ON " + TABLE_NAME + " (" + ADDRESS + ");",
  };

  public UnfacdIntroContactsDatabase(Context context, SignalDatabase databaseHelper) {
    super(context, databaseHelper);
  }

  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContact(long keyId) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    Reader reader = null;
    Optional<Pair<Long, IntroContactDescriptor>> introContactDescriptor;

    try (Cursor cursor = database.query(TABLE_NAME, null, ID + " = ?",
                                        new String[] {String.valueOf(keyId)},
                                        null, null, null)) {
      reader = new Reader(cursor);
      introContactDescriptor = reader.getNext();

    } finally {
      if (reader != null) reader.close();
    }

    return introContactDescriptor;
  }

  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContactBySender(long timestampSent) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    Reader reader = null;
    Optional<Pair<Long, IntroContactDescriptor>> introContactDescriptor;

    try (Cursor cursor = database.query(TABLE_NAME, null, TS_SENT + " = ?",
                                        new String[] {String.valueOf(timestampSent)},
                                        null, null, null)) {
      reader = new Reader(cursor);
      introContactDescriptor = reader.getNext();

    } finally {
      if (reader != null) reader.close();
    }

    return introContactDescriptor;
  }


  /**
   *  Fetch the IntroRequest associated with a given eid/timestamp sent combination. This is mostly
   *  oriented toward sender intro sender semantics (as opposed to response)
   * @param when timestamp of intro request
   * @param eid the event id generated at request time
   * @return
   */
  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContactByEid(long when, long eid) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    Reader reader = null;
    Optional<Pair<Long, IntroContactDescriptor>> introContactDescriptor;

    try (Cursor cursor = database.query(TABLE_NAME, null,  "EID = ? AND TS_SENT = ?",
                                        new String[] {String.valueOf(eid), String.valueOf(when)},
                                        null, null, null)) {
      reader = new Reader(cursor);
      introContactDescriptor = reader.getNext();

    } finally {
      if (reader != null) reader.close();
    }

    return introContactDescriptor;
  }

  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContactBySender(String ufsrvUidEncoded) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();
    Reader reader = null;
    Optional<Pair<Long, IntroContactDescriptor>> introContactDescriptor;

    try (Cursor cursor = database.query(TABLE_NAME, null, ADDRESS + " = ?",
                                        new String[] {String.valueOf(ufsrvUidEncoded)},
                                        null, null, null)) {
      reader = new Reader(cursor);
      introContactDescriptor = reader.getNext();

    } finally {
      if (reader != null) reader.close();
    }

    return introContactDescriptor;
  }

  public Optional<Pair<Long, IntroContactDescriptor>> getLastUnSentIntroContact()
  {
    SQLiteDatabase           database = databaseHelper.getSignalReadableDatabase();

    Cursor cursor = database.rawQuery("SELECT * FROM " + TABLE_NAME +
                                       "  WHERE "  +
                                              INTRO_DIRECTION + " = ? " +
                                        "AND " + RESPONSE_STATUS + "= ?" +
                                              " ORDER BY " + TS_SENT + " DESC" +
                                              " LIMIT 1",
                                      new String[] {  IntroDirection.OUTGOING.getValue()  + "",
                                                      ResponseStatus.UNSENT.getValue()    + ""});
    Reader reader = new Reader(cursor);
    Optional<Pair<Long, IntroContactDescriptor>> result = reader.getNext();

    reader.close();

    return result;
  }

  public Cursor getLastUnSeenIntros()
  {
    SQLiteDatabase           database = databaseHelper.getSignalReadableDatabase();

    Cursor cursor = database.rawQuery("SELECT * FROM " + TABLE_NAME +
                                              "  WHERE "  +
                                              INTRO_DIRECTION + "=? " +
                                              "AND " + RESPONSE_STATUS + "=?" +
                                              " ORDER BY " + TS_RESPONSE + " DESC" +
                                              " LIMIT 1",
                                      new String[] {  IntroDirection.INCOMING.getValue()  + "",
                                                      ResponseStatus.UNSEEN.getValue()    + ""});
    return cursor;
  }

  public @NonNull List<IntroContactDescriptor> getAllIntros() {
    SQLiteDatabase           database = databaseHelper.getSignalReadableDatabase();
    List<IntroContactDescriptor> results  = new LinkedList<>();
    Reader reader = null;

    try (Cursor cursor = database.query(TABLE_NAME, null, null, null, null, null, null)) {
      Optional<Pair<Long, IntroContactDescriptor>> descriptor;
      reader = new Reader(cursor);
      while ((descriptor = reader.getNext()).isPresent()) {
        results.add(descriptor.get().second());
      }
    } finally {
      if (reader != null) reader.close();
    }

    return results;
  }

  public long  insertIntroContact(IntroContactDescriptor contactDescriptor) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();

    ContentValues contentValues = new ContentValues();
    if (contactDescriptor.getAddressable() != null)  contentValues.put(ADDRESS, contactDescriptor.getAddressable().toString());
    if (!TextUtils.isEmpty(contactDescriptor.getHandle()))  contentValues.put(NICKNAME, contactDescriptor.getHandle());
    contentValues.put(AVATAR_ID, contactDescriptor.getAvatarId());
    contentValues.put(MSG, contactDescriptor.getMessage());
    contentValues.put(TS_SENT, contactDescriptor.getTimestampSent());
    contentValues.put(TS_RESPONSE, contactDescriptor.getTimestampResponse());
    contentValues.put(EID, contactDescriptor.getEid());
    contentValues.put(INTRO_DIRECTION, contactDescriptor.getIntroDirection().getValue());

    return database.replace(TABLE_NAME, null, contentValues);
  }

  public void setResponseStatus(long id, ResponseStatus status, long timestampResponse) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(RESPONSE_STATUS, status.getValue());
    values.put(TS_RESPONSE, timestampResponse);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id + ""});
  }


  /**
   * Update the IntroRequest recipient's resolved address once intro request accepted
   * @param id intro request local id
   * @param addresable ufsrvuid of accepting recipient
   */
  public void setAddressable(long id, String addresable) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(ADDRESS, addresable);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id + ""});
  }

  public void setEid(long id, long eid) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(EID, eid);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id + ""});
  }

  public void setAvatarId(long id, String avatarId) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(AVATAR_ID, avatarId);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id+""});
  }

  public void setAvatarBlob(long id, byte[] avatar) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(AVATAR, avatar);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id+""});
  }

  public class Reader implements Closeable {
    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public Optional<Pair<Long, IntroContactDescriptor>> getNext() {
      if (cursor == null || !cursor.moveToNext())
        return Optional.empty();

      return getCurrent();
    }

    public Optional<Pair<Long, IntroContactDescriptor>> getCurrent() {
      IntroDirection introDirection;
      ResponseStatus responseStatus;

      long    id              = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      LocallyAddressable locallyAddressable;
      String addressableSerialised = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      if (TextUtils.isEmpty(addressableSerialised) || addressableSerialised.compareTo(LocallyAddressableUndefined.require().serialize()) == 0) {
        locallyAddressable = LocallyAddressableUndefined.require();
      } else {
        locallyAddressable = LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
      }
      String  nickname        = cursor.getString(cursor.getColumnIndexOrThrow(NICKNAME));//handle other than ufsrvuid
      String avatarId         = cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_ID));
      String msg              = cursor.getString(cursor.getColumnIndexOrThrow(MSG));
              introDirection  = IntroDirection.values()[cursor.getInt(cursor.getColumnIndexOrThrow(INTRO_DIRECTION))];
              responseStatus  = ResponseStatus.values()[cursor.getInt(cursor.getColumnIndexOrThrow(RESPONSE_STATUS))];
      long  timestampReceived = cursor.getLong(cursor.getColumnIndexOrThrow(TS_SENT));
      long  timestampResponse = cursor.getLong(cursor.getColumnIndexOrThrow(TS_RESPONSE));
      long  eid = cursor.getLong(cursor.getColumnIndexOrThrow(EID));

      IntroContactDescriptor descriptor = new IntroContactDescriptor(locallyAddressable, nickname, msg, avatarId, introDirection, timestampReceived, responseStatus, timestampResponse, eid);
      descriptor.setAvatarBlob(cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR)));

      return Optional.of(new Pair(Long.valueOf(id), descriptor));
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
