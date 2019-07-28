package org.thoughtcrime.securesms.database.helpers;

import com.unfacd.android.ApplicationContext;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;
import net.sqlcipher.database.SQLiteOpenHelper;

import org.thoughtcrime.securesms.database.UnfacdIntroContactsDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.database.JobDatabase;
import org.thoughtcrime.securesms.crypto.DatabaseSecret;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DraftDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.OneTimePreKeyDatabase;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SearchDatabase;
import org.thoughtcrime.securesms.database.SessionDatabase;
import org.thoughtcrime.securesms.database.SignedPreKeyDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.jobs.RefreshPreKeysJob;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

public class SQLCipherOpenHelper extends SQLiteOpenHelper {

  @SuppressWarnings("unused")
  private static final String TAG = SQLCipherOpenHelper.class.getSimpleName();

  private static final int RECIPIENT_CALL_RINGTONE_VERSION = 2;
  private static final int MIGRATE_PREKEYS_VERSION         = 3;
  private static final int MIGRATE_SESSIONS_VERSION        = 4;
  private static final int NO_MORE_IMAGE_THUMBNAILS_VERSION = 5;
  private static final int ATTACHMENT_DIMENSIONS            = 6;
  private static final int QUOTED_REPLIES                   = 7;
  private static final int SHARED_CONTACTS                  = 8;
  private static final int FULL_TEXT_SEARCH                 = 9;
  private static final int QUOTE_MISSING                    = 10;
  private static final int NOTIFICATION_CHANNELS            = 11;
  private static final int ATTACHMENT_CAPTIONS              = 14;
  private static final int PREVIEWS                         = 16;
  public static final int INTRO_CONTACTS_VERSION            = 20;
  private static final int STICKERS                         = 21;
  private static final int REVEALABLE_MESSAGES              = 22;

  private static final int    DATABASE_VERSION = 22;
  private static final String DATABASE_NAME    = "unfacd.db";

  private final Context        context;
  private final DatabaseSecret databaseSecret;

  public SQLCipherOpenHelper(@NonNull Context context, @NonNull DatabaseSecret databaseSecret) {
    super(context, DATABASE_NAME, null, DATABASE_VERSION, new SQLiteDatabaseHook() {
      @Override
      public void preKey(SQLiteDatabase db) {
        db.rawExecSQL("PRAGMA cipher_default_kdf_iter = 1;");
        db.rawExecSQL("PRAGMA cipher_default_page_size = 4096;");
      }

      @Override
      public void postKey(SQLiteDatabase db) {
        db.rawExecSQL("PRAGMA kdf_iter = '1';");
        db.rawExecSQL("PRAGMA cipher_page_size = 4096;");
      }
    });

    this.context        = context.getApplicationContext();
    this.databaseSecret = databaseSecret;
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(SmsDatabase.CREATE_TABLE);
    db.execSQL(MmsDatabase.CREATE_TABLE);
    db.execSQL(AttachmentDatabase.CREATE_TABLE);
    db.execSQL(ThreadDatabase.CREATE_TABLE);
    db.execSQL(IdentityDatabase.CREATE_TABLE);
    db.execSQL(DraftDatabase.CREATE_TABLE);
    db.execSQL(PushDatabase.CREATE_TABLE);
    db.execSQL(GroupDatabase.CREATE_TABLE);
    db.execSQL(RecipientDatabase.CREATE_TABLE);
    db.execSQL(GroupReceiptDatabase.CREATE_TABLE);
    db.execSQL(OneTimePreKeyDatabase.CREATE_TABLE);
    db.execSQL(SignedPreKeyDatabase.CREATE_TABLE);
    db.execSQL(SessionDatabase.CREATE_TABLE);
    db.execSQL(StickerDatabase.CREATE_TABLE);
    executeStatements(db, SearchDatabase.CREATE_TABLE);
    executeStatements(db, JobDatabase.CREATE_TABLE);

    //
    db.execSQL(UnfacdIntroContactsDatabase.CREATE_TABLE);

    executeStatements(db, SmsDatabase.CREATE_INDEXS);
    executeStatements(db, MmsDatabase.CREATE_INDEXS);
    executeStatements(db, AttachmentDatabase.CREATE_INDEXS);
    executeStatements(db, ThreadDatabase.CREATE_INDEXS);
    executeStatements(db, DraftDatabase.CREATE_INDEXS);
    executeStatements(db, GroupDatabase.CREATE_INDEXS);
    executeStatements(db, GroupReceiptDatabase.CREATE_INDEXES);
    executeStatements(db, StickerDatabase.CREATE_INDEXES);

    if (context.getDatabasePath(ClassicOpenHelper.NAME).exists()) {
      ClassicOpenHelper                      legacyHelper = new ClassicOpenHelper(context);
      android.database.sqlite.SQLiteDatabase legacyDb     = legacyHelper.getWritableDatabase();

      SQLCipherMigrationHelper.migratePlaintext(context, legacyDb, db);

      MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);

      if (masterSecret != null) SQLCipherMigrationHelper.migrateCiphertext(context, masterSecret, legacyDb, db, null);
      else                      TextSecurePreferences.setNeedsSqlCipherMigration(context, true);

      if (!PreKeyMigrationHelper.migratePreKeys(context, db)) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob());
      }

      SessionStoreMigrationHelper.migrateSessions(context, db);
      PreKeyMigrationHelper.cleanUpPreKeys(context);
    }
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    Log.w(TAG, "Upgrading database: " + oldVersion + ", " + newVersion);

    db.beginTransaction();

    try {
      if (oldVersion < INTRO_CONTACTS_VERSION) {
        db.execSQL("CREATE TABLE unfacd_intro_contactcs (_id INTEGER PRIMARY KEY, address TEXT NO NULL, intro_direction INT DEFAULT 0, response_status INT DEFAULT 0, avatar_id TEXT, msg TEXT, ts_sent TEXT, ts_response TEXT, avatar BLOB)");

      }

      if (oldVersion < STICKERS) {
        db.execSQL("CREATE TABLE sticker (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                           "pack_id TEXT NOT NULL, " +
                           "pack_key TEXT NOT NULL, " +
                           "pack_title TEXT NOT NULL, " +
                           "pack_author TEXT NOT NULL, " +
                           "sticker_id INTEGER, " +
                           "cover INTEGER, " +
                           "emoji TEXT NOT NULL, " +
                           "last_used INTEGER, " +
                           "installed INTEGER," +
                           "file_path TEXT NOT NULL, " +
                           "file_length INTEGER, " +
                           "file_random BLOB, " +
                           "UNIQUE(pack_id, sticker_id, cover) ON CONFLICT IGNORE)");

        db.execSQL("CREATE INDEX IF NOT EXISTS sticker_pack_id_index ON sticker (pack_id);");
        db.execSQL("CREATE INDEX IF NOT EXISTS sticker_sticker_id_index ON sticker (sticker_id);");

        db.execSQL("ALTER TABLE part ADD COLUMN sticker_pack_id TEXT");
        db.execSQL("ALTER TABLE part ADD COLUMN sticker_pack_key TEXT");
        db.execSQL("ALTER TABLE part ADD COLUMN sticker_id INTEGER DEFAULT -1");
        db.execSQL("CREATE INDEX IF NOT EXISTS part_sticker_pack_id_index ON part (sticker_pack_id)");
      }

      if (oldVersion < REVEALABLE_MESSAGES) {
        db.execSQL("ALTER TABLE mms ADD COLUMN reveal_duration INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE mms ADD COLUMN reveal_start_time INTEGER DEFAULT 0");

        db.execSQL("ALTER TABLE thread ADD COLUMN snippet_content_type TEXT DEFAULT NULL");
        db.execSQL("ALTER TABLE thread ADD COLUMN snippet_extras TEXT DEFAULT NULL");
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public SQLiteDatabase getReadableDatabase() {
    return getReadableDatabase(databaseSecret.asString());
  }

  public SQLiteDatabase getWritableDatabase() {
    return getWritableDatabase(databaseSecret.asString());
  }

  public void markCurrent(SQLiteDatabase db) {
    db.setVersion(DATABASE_VERSION);
  }

  private void executeStatements(SQLiteDatabase db, String[] statements) {
    for (String statement : statements)
      db.execSQL(statement);
  }

  private static boolean columnExists(@NonNull SQLiteDatabase db, @NonNull String table, @NonNull String column) {
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      int nameColumnIndex = cursor.getColumnIndexOrThrow("name");
      while (cursor.moveToNext()) {
        String name = cursor.getString(nameColumnIndex);
        if (name.equals(column)) {
          return true;
        }
      }
    }
    return false;
  }

}