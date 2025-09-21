//package org.thoughtcrime.securesms.database;
//
//import android.content.ContentValues;
//import android.content.Context;
//import android.database.Cursor;
//import android.database.sqlite.SQLiteDatabase;
//import android.database.sqlite.SQLiteOpenHelper;
//import android.graphics.Bitmap;
//import android.net.Uri;
//import android.provider.ContactsContract.CommonDataKinds.Phone;
//import androidx.annotation.NonNull;
//import androidx.annotation.Nullable;
//import android.text.TextUtils;
//import org.signal.core.util.logging.Log;
//
//import org.thoughtcrime.securesms.crypto.MasterCipher;
//import org.thoughtcrime.securesms.recipients.Recipient;
//import org.thoughtcrime.securesms.util.BitmapUtil;
//import org.whispersystems.signalservice.api.push.ContactTokenDetails;
//import org.whispersystems.signalservice.api.util.InvalidNumberException;
//import org.whispersystems.signalservice.api.util.PhoneNumberFormatter;
//
//import java.util.ArrayList;
//import java.util.Collection;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//public class TextSecureDirectory {
//
//  //AA+  extra column. Same column is added to canonical db, ultimately, we need to move away from e164 based ids to ufsrv stable ids
//  //one ufsr user id can map to multiple e164 based ids
//  private static final int INTRODUCED_CHANGE_FROM_TOKEN_TO_E164_NUMBER = 2;
//  private static final int INTRODUCED_VOICE_COLUMN                     = 4;
//  private static final int INTRODUCED_VIDEO_COLUMN                     = 5;
//  private static final int INTRODUCED_USERID_COLUMN                    = 6;//AA+
//  private static final int INTRODUCED_NICKNAME_COLUMN                  = 7;//AA+
//  private static final int INTRODUCED_AVATAR_COLUMN                  = 9;//AA+
//
//  private static final String DATABASE_NAME    = "whisper_directory.db";
//  private static final int    DATABASE_VERSION = 9;
//
//  public static final String DIRECTORY_URI      = "content://unfacd/directory/";//AA+
//
//  private static final String TABLE_NAME   = "directory";
//  private static final String ID           = "_id";
//  private static final String NUMBER       = "number";
//  private static final String REGISTERED   = "registered";
//  private static final String RELAY        = "relay";
//  private static final String TIMESTAMP    = "timestamp";
//  private static final String VOICE        = "voice";
//  private static final String VIDEO        = "video";
//  private static final String USERID       = "userid";
//  private static final String NICKNAME     = "nickname";
//  private static final String AVATAR        = "avatar";
//  private static final String AVATAR_KEY    = "avatar_key";
//  private static final String AVATAR_CONTENT_TYPE_= "avatar_content_type";
//  private static final String AVATAR_UFSRV_ID  ="avatar_ufsrv_id";
//
//  private static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + "(" + ID + " INTEGER PRIMARY KEY, " +
//                              NUMBER       + " TEXT UNIQUE, " +
//                              REGISTERED   + " INTEGER, " +
//                              RELAY        + " TEXT, "    +
//                              TIMESTAMP    + " INTEGER, " +
//                              VOICE        + " INTEGER, " +
//                              VIDEO        + " INTEGER, " +
//                              USERID       + " INTEGER, "  +
//                              NICKNAME     + " TEXT, " +
//                              AVATAR       + " BLOB, " +
//                              AVATAR_KEY   + " BLOB, " +
//                              AVATAR_CONTENT_TYPE_ + " TEXT, " +
//                              AVATAR_UFSRV_ID + " TEXT " +
//                            ");";
//
//  private static final Object instanceLock = new Object();
//  private static volatile TextSecureDirectory instance;
//
//  private static final String TAG = Log.tag(TextSecureDirectory.class);
//
//
//  public static TextSecureDirectory getInstance(Context context) {
//    if (instance == null) {
//      synchronized (instanceLock) {
//        if (instance == null) {
//          instance = new TextSecureDirectory(context.getApplicationContext());
//        }
//      }
//    }
//
//    return instance;
//  }
//
//  private final DatabaseHelper databaseHelper;
//  private final Context        context;
//
//  private TextSecureDirectory(Context context) {
//    this.context = context;
//    this.databaseHelper = new DatabaseHelper(context, DATABASE_NAME, null, DATABASE_VERSION);
//  }
//
//  @Nullable public DirectoryRecord getDirectoryRecord  (String number) {
//    SQLiteDatabase db       = databaseHelper.getReadableDatabase();
//    Cursor         cursor;
//
//    cursor = db.query(TABLE_NAME, null, NUMBER + " = ?", new String[]{number}, null, null, null);
//
//    Reader          reader = new TextSecureDirectory.Reader(cursor, null);
//    DirectoryRecord record = reader.getNext();
//    reader.close();
//
//    return record;
//  }
//
//  public DirectoryRecord getDirectoryRecord  (long id) {
//    SQLiteDatabase db       = databaseHelper.getReadableDatabase();
//    Cursor         cursor;
//
//    cursor = db.query(TABLE_NAME, null, ID + " = ?", new String[]{String.valueOf(id)}, null, null, null);
//    Reader          reader = new TextSecureDirectory.Reader(cursor, null);
//    DirectoryRecord record = reader.getNext();
//    reader.close();
//
//    return record;
//  }
//
//  public DirectoryRecord getDirectoryRecordByUserId  (long userId) {
//    SQLiteDatabase db       = databaseHelper.getReadableDatabase();
//    Cursor         cursor;
//
//    cursor = db.query(TABLE_NAME, null, USERID + " = ?", new String[]{String.valueOf(userId)}, null, null, null);
//    Reader          reader = new TextSecureDirectory.Reader(cursor, null);
//    DirectoryRecord record = reader.getNext();
//    reader.close();
//
//    return record;
//  }
//
//  public boolean isSecureTextSupported(@NonNull Address address) throws NotInDirectoryException {
//    if (address.isEmail()) return false;
//    if (address.isGroup()) return true;
//
//    SQLiteDatabase db = databaseHelper.getReadableDatabase();
//    Cursor cursor = null;
//
//    try {
//      cursor = db.query(TABLE_NAME,
//                        new String[]{REGISTERED}, NUMBER + " = ?",
//                        new String[] {address.serialize()}, null, null, null);
//
//      if (cursor != null && cursor.moveToFirst()) {
//        return cursor.getInt(0) == 1;
//      } else {
//        throw new NotInDirectoryException();
//      }
//
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
////  public boolean isSecureVoiceSupported(String e164number) throws NotInDirectoryException {
////    if (TextUtils.isEmpty(e164number)) {
////      return false;
////    }
////
////    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
////    Cursor         cursor = null;
////
////    try {
////      cursor = db.query(TABLE_NAME,
////                        new String[]{VOICE}, NUMBER + " = ?",
////                        new String[] {e164number}, null, null, null);
////
////      if (cursor != null && cursor.moveToFirst()) {
////        return cursor.getInt(0) == 1;
////      } else {
////        throw new NotInDirectoryException();
////      }
////
////    } finally {
////      if (cursor != null)
////        cursor.close();
////    }
////  }
//
////  public boolean isSecureVideoSupported(String e164number) throws NotInDirectoryException {
////    if (TextUtils.isEmpty(e164number)) {
////      return false;
////    }
////
////    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
////    Cursor         cursor = null;
////
////    try {
////      cursor = db.query(TABLE_NAME,
////                        new String[]{VIDEO}, NUMBER + " = ?",
////                        new String[] {e164number}, null, null, null);
////
////      if (cursor != null && cursor.moveToFirst()) {
////        return cursor.getInt(0) == 1;
////      } else {
////        throw new NotInDirectoryException();
////      }
////
////    } finally {
////      if (cursor != null)
////        cursor.close();
////    }
////  }
//
//  public String getRelay(String e164number) {
//    SQLiteDatabase database = databaseHelper.getReadableDatabase();
//    Cursor         cursor   = null;
//
//    try {
//      cursor = database.query(TABLE_NAME, null, NUMBER + " = ?", new String[]{e164number}, null, null, null);
//
//      if (cursor != null && cursor.moveToFirst()) {
//        return cursor.getString(cursor.getColumnIndexOrThrow(RELAY));
//      }
//
//      return null;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
//  //AA+
//  public String getNickname(String e164number) {
//    SQLiteDatabase database = databaseHelper.getReadableDatabase();
//    Cursor         cursor   = null;
//
//    try {
//      cursor = database.query(TABLE_NAME, null, NUMBER + " = ?", new String[]{e164number}, null, null, null);
//
//      if (cursor != null && cursor.moveToFirst()) {
//        if (!cursor.isNull(cursor.getColumnIndex(NICKNAME)))  return cursor.getString(cursor.getColumnIndex(NICKNAME));
//        else  return String.format("ufuser_%d_%d", cursor.getLong(cursor.getColumnIndex(ID)), cursor.getLong(cursor.getColumnIndex(USERID)));
//      }
//
//      return null;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
//  public void updateNickname (String number, String nickname) {
//    ContentValues contentValues = new ContentValues();
//    contentValues.put(NICKNAME, nickname);
//
//    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, NUMBER +  " = ?",
//            new String[] {number});
//
//    Recipient.clearCache(context);
//    notifyDirectoryListeners(number);
//  }
//
//  public byte[] getAvatar(String e164number) {
//    SQLiteDatabase database = databaseHelper.getReadableDatabase();
//    Cursor         cursor   = null;
//
//    try {
//      cursor = database.query(TABLE_NAME, null, NUMBER + " = ?", new String[]{e164number}, null, null, null);
//
//      if (cursor != null && cursor.moveToFirst()) {
//        return cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR));
//      }
//
//      return null;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
//
//  public void updateAvatar(String number, Bitmap avatar) {
//    updateAvatar(number, BitmapUtil.toByteArray(avatar));
//  }
//
//  public void updateAvatar(String number, byte[] avatar) {
//    ContentValues contentValues = new ContentValues();
//    contentValues.put(AVATAR, avatar);
//
//    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, NUMBER +  " = ?",
//            new String[] {number});
//
//    Recipient.clearCache(context);
//    //notifyDatabaseListeners();
//  }
//
//  public long getUserId(String e164number) {
//    SQLiteDatabase database = databaseHelper.getReadableDatabase();
//    Cursor         cursor   = null;
//
//    try {
//      cursor = database.query(TABLE_NAME, new String[]{USERID}, NUMBER + " = ?", new String[]{e164number}, null, null, null);
//
//      if (cursor != null && cursor.moveToFirst()) {
//        if (!cursor.isNull(cursor.getColumnIndex(USERID)))  return cursor.getLong(cursor.getColumnIndex(USERID));
//        else  return -1;
//      }
//
//      return -1;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//  //
//
//  public void setNumber(ContactTokenDetails token, boolean active) {
//    Log.w(TAG, String.format(">> setNumber: '%s' for nick:'%s'. Adding active token: ", token.getNumber(), token.getNickname()));
//    SQLiteDatabase db     = databaseHelper.getSignalWritableDatabase();
//    ContentValues  values = new ContentValues();
//    values.put(NUMBER, token.getNumber());
//    values.put(REGISTERED, active ? 1 : 0);
//    values.put(TIMESTAMP, System.currentTimeMillis());
//    values.put(RELAY, token.getRelay());
//    values.put(VOICE, token.isVoice());
//    values.put(VIDEO, token.isVideo());
//    //AA+
//    values.put(USERID, token.getUfsrvUid());
//    values.put(NICKNAME, token.getNickname());
//    //
//    db.replace(TABLE_NAME, null, values);
//  }
//
//  public void setNumbers(List<ContactTokenDetails> activeTokens, Collection<Address> inactiveAddresses) {
//    long timestamp    = System.currentTimeMillis();
//    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
//    db.beginTransaction();
//
//    try {
//      for (ContactTokenDetails token : activeTokens) {
//        Log.w("Directory", "Adding active token: " + token.getNumber() + ", " + token.getToken() + ", video: " + token.isVideo());
//        ContentValues values = new ContentValues();
//        values.put(NUMBER, token.getNumber());
//        values.put(REGISTERED, 1);
//        values.put(TIMESTAMP, timestamp);
//        values.put(RELAY, token.getRelay());
//        values.put(VOICE, token.isVoice());
//        //AA+
//        values.put(USERID, token.getUfsrvUid());
//        values.put(NICKNAME, token.getNickname());
//        //
//        db.replace(TABLE_NAME, null, values);
//      }
//
//      for (Address address : inactiveAddresses) {
//        ContentValues values = new ContentValues();
//        values.put(NUMBER, address.serialize());
//        values.put(REGISTERED, 0);
//        values.put(TIMESTAMP, timestamp);
//        db.replace(TABLE_NAME, null, values);
//      }
//
//      db.setTransactionSuccessful();
//    } finally {
//      db.endTransaction();
//    }
//  }
//
//  public Set<Address> getPushEligibleContactNumbers() {
//    final Uri          uri     = Phone.CONTENT_URI;
//    final Set<Address> results = new HashSet<>();
//    Cursor       cursor  = null;
//
//    try {
//      cursor = context.getContentResolver().query(uri, new String[] {Phone.NUMBER}, null, null, null);
//
//      while (cursor != null && cursor.moveToNext()) {
//        final String rawNumber = cursor.getString(0);
//        if (!TextUtils.isEmpty(rawNumber)) {
//          results.add(Address.fromExternal(context, rawNumber));
//        }
//      }
//
//      if (cursor != null)
//        cursor.close();
//
//      final SQLiteDatabase readableDb = databaseHelper.getReadableDatabase();
//      if (readableDb != null) {
//        cursor = readableDb.query(TABLE_NAME, new String[]{NUMBER},
//                                  null, null, null, null, null);
//
//        while (cursor != null && cursor.moveToNext()) {
//          results.add(Address.fromSerialized(cursor.getString(0)));
//        }
//      }
//
//      return results;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
//  public List<String> getActiveNumbers() {
//
//    final List<String> results = new ArrayList<>();
//    Cursor cursor = null;
//    try {
//      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[]{NUMBER},
//          REGISTERED + " = 1", null, null, null, null);
//
//      while (cursor != null && cursor.moveToNext()) {
//        results.add(cursor.getString(0));
//      }
//      Log.d(TAG, ">> getActiveNumbers(): FOUND: "+results.size()+" ACTIVE (REGISTERED) members in directory");
//      return results;
//    } finally {
//      if (cursor != null)
//        cursor.close();
//    }
//  }
//
//  //A+
//  protected void notifyDirectoryListeners(Set<Long> userIds) {
//    for (long userId : userIds)
//      notifyDirectoryListeners(userId);
//  }
//
//  protected void notifyDirectoryListeners(long userId) {
//    context.getContentResolver().notifyChange(Uri.parse(DIRECTORY_URI + userId), null);
//  }
//
//  protected void notifyDirectoryListeners(String number) {
//    context.getContentResolver().notifyChange(Uri.parse(DIRECTORY_URI + number), null);
//  }
//
//  protected void setNotifyDirectoryListeners(Cursor cursor, long userId) {
//    cursor.setNotificationUri(context.getContentResolver(), Uri.parse(DIRECTORY_URI + userId));
//  }
//
//  protected void setNotifyDirectoryListeners(Cursor cursor, String number) {
//    cursor.setNotificationUri(context.getContentResolver(), Uri.parse(DIRECTORY_URI + number));
//  }
//
//  public TextSecureDirectory.Reader readerFor(Cursor cursor, MasterCipher masterCipher) {
//    return new TextSecureDirectory.Reader(cursor, masterCipher);
//  }
//
//
//  public class DirectoryRecord
//  {
//
//    private final Context context;
//
//    private final long    id;
//    private final String  number;
//    private final int     isRegistered;
//    private final String  relay;
//    private final long    timestamp;
//    private final int     isVoice;
//    private final int     isVideo;
//    private final long    userId;
//    private final String  nickname;
//    private final byte[]  avatar;
//    private final byte[]  avatarKey;
//    private final String  avatarContentType;
//    private final String  avatarUfsrvId;
//
//    public DirectoryRecord (
//            @NonNull Context context, long id, long userId, @NonNull String number, @Nullable String nickname,
//            @Nullable byte[] avatar, @Nullable byte[] avatarKey, @Nullable String avatarContentType,
//            @Nullable String avatarUfsrvId, int isRegistered, int isVideo, int isVoice,
//            long timestamp, @Nullable String relay)
//    {
//      this.context            = context.getApplicationContext();
//      this.id                 = id;
//      this.userId             = userId;
//      this.number             = number;
//      this.nickname           = nickname;
//      this.avatar             = avatar;
//      this.avatarKey          = avatarKey;
//      this.avatarContentType  = avatarContentType;
//      this.avatarUfsrvId      = avatarUfsrvId;
//      this.isRegistered       = isRegistered;
//      this.isVideo            = isVideo;
//      this.isVoice            = isVoice;
//      this.timestamp          = timestamp;
//      this.relay              = relay;
//    }
//
//    public long getId ()
//    {
//      return id;
//    }
//
//    public String getNumber ()
//    {
//      return number;
//    }
//
//    public long getUserId ()
//    {
//      return userId;
//    }
//
//    public String getNickname ()
//    {
//      return nickname;
//    }
//
//    public byte[] getAvatar ()
//    {
//      return avatar;
//    }
//
//    public byte[] getAvatarKey ()
//    {
//      return avatarKey;
//    }
//
//    public String getAvatarUfsrvId ()
//    {
//      return avatarUfsrvId;
//    }
//
//    public String getAvatarContentType ()
//    {
//      return avatarContentType;
//    }
//
//    public int getIsRegistered ()
//    {
//      return isRegistered;
//    }
//
//    public long getTimestamp ()
//    {
//      return timestamp;
//    }
//
//    public int getIsVideo ()
//    {
//      return isVideo;
//    }
//  }
//
//  public class Reader
//  {
//
//    private final Cursor cursor;
//    private final MasterCipher masterCipher;
//
//    public Reader (Cursor cursor, MasterCipher masterCipher)
//    {
//      this.cursor = cursor;
//      this.masterCipher = masterCipher;
//    }
//
//    public DirectoryRecord getNext ()
//    {
//      if (cursor == null || !cursor.moveToNext())
//        return null;
//
//      return getCurrent();
//    }
//
//    public DirectoryRecord getCurrent ()
//    {
//     long    id             = cursor.getLong(cursor.getColumnIndexOrThrow(TextSecureDirectory.ID));
//     String  number         = cursor.getString(cursor.getColumnIndexOrThrow(TextSecureDirectory.NUMBER));
//     long    userId         = cursor.getLong(cursor.getColumnIndexOrThrow(TextSecureDirectory.USERID));
//     String  nickname       = cursor.getString(cursor.getColumnIndexOrThrow(TextSecureDirectory.NICKNAME));
//     int     isRegistered   = cursor.getInt(cursor.getColumnIndexOrThrow(TextSecureDirectory.REGISTERED));
//     String  relay          = cursor.getString(cursor.getColumnIndexOrThrow(TextSecureDirectory.RELAY));
//     long    timestamp      = cursor.getLong(cursor.getColumnIndexOrThrow(TextSecureDirectory.TIMESTAMP));
//     int     isVoice        = cursor.getInt(cursor.getColumnIndexOrThrow(TextSecureDirectory.VOICE));
//     int     isVideo        = cursor.getInt(cursor.getColumnIndexOrThrow(TextSecureDirectory.VIDEO));
//     byte[]  avatar         = cursor.getBlob(cursor.getColumnIndexOrThrow(TextSecureDirectory.AVATAR));
//     byte[]  avatarKey      = cursor.getBlob(cursor.getColumnIndexOrThrow(TextSecureDirectory.AVATAR_KEY));
//     String  avatarContentType  = cursor.getString(cursor.getColumnIndexOrThrow(TextSecureDirectory.AVATAR_CONTENT_TYPE_));
//     String  avatarUfsrvId  = cursor.getString(cursor.getColumnIndexOrThrow(TextSecureDirectory.AVATAR_UFSRV_ID));
//
//
//      return new DirectoryRecord(context, id, userId, number, nickname, avatar, avatarKey,
//              avatarContentType, avatarUfsrvId, isRegistered, isVideo, isVoice, timestamp, relay);//AA+  ufsrvcommand
//    }
//
//    public void close ()
//    {
//      if (cursor!=null) cursor.close();
//    }
//  }
//  //
//
//  private static class DatabaseHelper extends SQLiteOpenHelper {
//
//    public DatabaseHelper(Context context, String name,
//                          SQLiteDatabase.CursorFactory factory,
//                          int version)
//    {
//      super(context, name, factory, version);
//    }
//
//    @Override
//    public void onCreate(SQLiteDatabase db) {
//      db.execSQL(CREATE_TABLE);
//    }
//
//    @Override
//    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
//      if (oldVersion < INTRODUCED_CHANGE_FROM_TOKEN_TO_E164_NUMBER) {
//        db.execSQL("DROP TABLE directory;");
//        db.execSQL("CREATE TABLE directory ( _id INTEGER PRIMARY KEY, " +
//                   "number TEXT UNIQUE, " +
//                   "registered INTEGER, " +
//                   "relay TEXT, " +
//                   "supports_sms INTEGER, " +
//                   "timestamp INTEGER);");
//      }
//
//      if (oldVersion < INTRODUCED_VOICE_COLUMN) {
//        db.execSQL("ALTER TABLE directory ADD COLUMN voice INTEGER;");
//      }
//
//      if (oldVersion < INTRODUCED_VIDEO_COLUMN) {
//        db.execSQL("ALTER TABLE directory ADD COLUMN video INTEGER;");
//      }
//
//      //AA+
//      if (oldVersion < INTRODUCED_USERID_COLUMN) {
//        db.execSQL("ALTER TABLE directory ADD COLUMN userid INTEGER DEFAULT 0;");
//      }
//
//      if (oldVersion < INTRODUCED_NICKNAME_COLUMN) {
//        db.execSQL("ALTER TABLE directory ADD COLUMN nickname TEXT;");
//      }
//
//      if (oldVersion < INTRODUCED_AVATAR_COLUMN) {
//        db.execSQL("ALTER TABLE directory ADD COLUMN avatar BLOB;");
//        db.execSQL("ALTER TABLE directory ADD COLUMN avatar_type TEXT;");
//        db.execSQL("ALTER TABLE directory ADD COLUMN avatar_ufsrv_id TEXT;");
//        db.execSQL("ALTER TABLE directory ADD COLUMN avatar_key BLOB;");
//      }
//      //
//    }
//  }
//
//}
