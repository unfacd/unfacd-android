package org.thoughtcrime.securesms.database;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Pair;

import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;

import net.sqlcipher.database.SQLiteDatabase;

import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.Closeable;
import java.util.LinkedList;
import java.util.List;

public class UnfacdIntroContactsDatabase extends Database {


  private static final String TAG = UnfacdIntroContactsDatabase.class.getSimpleName();

  public static final String TABLE_NAME = "unfacd_intro_contactcs";

  private static final String ID                = "_id";
  public  static final String INTRO_DIRECTION   = "intro_direction";
  public  static final String ADDRESS           = "address";
  public  static final String AVATAR_ID         = "avatar_id";
  public  static final String RESPONSE_STATUS   = "response_status";
  public  static final String TS_SENT           = "ts_sent";
  public  static final String TS_RESPONSE       = "ts_response";
  public  static final String MSG               = "msg";
  public  static final String AVATAR            = "avatar";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME +
                                            " (" + ID + " INTEGER PRIMARY KEY, " +
                                            ADDRESS + " TEXT NO NULL, " +
                                            INTRO_DIRECTION + " INT DEFAULT 0, " +
                                            RESPONSE_STATUS + " INT DEFAULT 0, " +
                                            AVATAR_ID + " TEXT, " +
                                            MSG + " TEXT, " +
          TS_SENT + " INTEGER DEFAULT 0, " +
                                            TS_RESPONSE + " INTEGER DEFAULT 0, "+
                                            AVATAR + " BLOB " +
                                            ");";



  public UnfacdIntroContactsDatabase (Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContact (long keyId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Reader reader = null;
    Optional<Pair<Long, IntroContactDescriptor>> introContactDescriptor = Optional.absent();

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

  public @Nullable Optional<Pair<Long, IntroContactDescriptor>> getIntroContactBySender (long timestampSent) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
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

  public Optional<Pair<Long, IntroContactDescriptor>> getLastUnSentIntroContact ()
  {
    SQLiteDatabase           database = databaseHelper.getReadableDatabase();

    Cursor cursor = database.rawQuery("SELECT * FROM " + TABLE_NAME +
                                       "  WHERE "  +
                                              INTRO_DIRECTION + "=? " +
                                        "AND " + RESPONSE_STATUS + "=?" +
                                              " ORDER BY " + TS_SENT + " DESC" +
                                              " LIMIT 1",
                                      new String[] { IntroContactDescriptor.IntroDirection.OUTGOING.getValue()+"",
                                              IntroContactDescriptor.ResponseStatus.UNSENT.getValue()+""});
    Reader reader = new Reader(cursor);
    Optional<Pair<Long, IntroContactDescriptor>> result = reader.getNext();

    reader.close();

    return result;
  }

  public Cursor getLastUnSeenIntros ()
  {
    SQLiteDatabase           database = databaseHelper.getReadableDatabase();

    Cursor cursor = database.rawQuery("SELECT * FROM " + TABLE_NAME +
                                              "  WHERE "  +
                                              INTRO_DIRECTION + "=? " +
                                              "AND " + RESPONSE_STATUS + "=?" +
                                              " ORDER BY " + TS_RESPONSE + " DESC" +
                                              " LIMIT 1",
                                      new String[] { IntroContactDescriptor.IntroDirection.INCOMING.getValue()+"",
                                              IntroContactDescriptor.ResponseStatus.UNSEEN.getValue()+""});
    return cursor;
  }

  public @NonNull List<IntroContactDescriptor> getAllIntros() {
    SQLiteDatabase           database = databaseHelper.getReadableDatabase();
    List<IntroContactDescriptor> results  = new LinkedList<>();
    Reader reader = null;

    try (Cursor cursor = database.query(TABLE_NAME, null, null, null, null, null, null)) {
      Optional<Pair<Long, IntroContactDescriptor>> descriptor;
      reader = new Reader(cursor);
      while ((descriptor = reader.getNext()).isPresent()) {
        results.add(descriptor.get().second);
      }
    } finally {
      if (reader!=null) reader.close();
    }

    return results;
  }

  public long  insertIntroContact(IntroContactDescriptor contactDescriptor) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();

    ContentValues contentValues = new ContentValues();
    contentValues.put(ADDRESS, contactDescriptor.getAddress().serialize());
    contentValues.put(AVATAR_ID, contactDescriptor.getAvatarId());
    contentValues.put(MSG, contactDescriptor.getMessage());
    contentValues.put(TS_SENT, contactDescriptor.getTimestampSent());
    contentValues.put(INTRO_DIRECTION, contactDescriptor.getIntroDirection().getValue());

    return database.insert(TABLE_NAME, null, contentValues);
  }

  public void setResponseStatus (long id, IntroContactDescriptor.ResponseStatus status) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(RESPONSE_STATUS, status.getValue());
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id+""});
  }

  public void setAvatarId (long id, String avatarId) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(AVATAR_ID, avatarId);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id+""});
  }

  public void setAvatarBlob (long id, byte[] avatar) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(AVATAR, avatar);
    database.update(TABLE_NAME, values, ID + " = ?", new String[] {id+""});
  }

//  public void removeSignedPreKey(int keyId) {
//    SQLiteDatabase database = databaseHelper.getWritableDatabase();
//    database.delete(TABLE_NAME, KEY_ID + " = ? AND " + SIGNATURE + " IS NOT NULL", new String[] {String.valueOf(keyId)});
//  }

  public class Reader implements Closeable {
    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public Optional<Pair<Long, IntroContactDescriptor>> getNext() {
      if (cursor == null || !cursor.moveToNext())
        return Optional.absent();

      return getCurrent();
    }

    public Optional<Pair<Long, IntroContactDescriptor>> getCurrent() {
      IntroContactDescriptor.IntroDirection introDirection;
      IntroContactDescriptor.ResponseStatus responseStatus;

      long    id              = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      Address address         = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
      String avatarId         = cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_ID));
      String msg              = cursor.getString(cursor.getColumnIndexOrThrow(MSG));
              introDirection  = IntroContactDescriptor.IntroDirection.values()[cursor.getInt(cursor.getColumnIndexOrThrow(INTRO_DIRECTION))];
              responseStatus  = IntroContactDescriptor.ResponseStatus.values()[cursor.getInt(cursor.getColumnIndexOrThrow(RESPONSE_STATUS))];
      long  timestampReceived = cursor.getLong(cursor.getColumnIndexOrThrow(TS_SENT));
      long  timestampResponse = cursor.getLong(cursor.getColumnIndexOrThrow(TS_RESPONSE));

      IntroContactDescriptor descriptor = new IntroContactDescriptor(address, msg, avatarId, introDirection, timestampReceived, responseStatus, timestampResponse);
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
