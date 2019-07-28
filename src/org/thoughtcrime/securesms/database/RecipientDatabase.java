package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import org.thoughtcrime.securesms.logging.Log;

import com.annimon.stream.Stream;

import net.sqlcipher.database.SQLiteDatabase;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.fence.EnumFencePermissions;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;

import org.whispersystems.signalservice.api.push.ContactTokenDetails;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode.UNRESTRICTED;

public class RecipientDatabase extends Database {

  private static final String TAG = RecipientDatabase.class.getSimpleName();

          static final String TABLE_NAME              = "recipient_preferences";
  private static final String ID                      = "_id";
          static final String ADDRESS                 = "recipient_ids";
  public  static final String BLOCK                   = "block"; // public
  private static final String NOTIFICATION            = "notification";
  private static final String VIBRATE                 = "vibrate";
  private static final String MUTE_UNTIL              = "mute_until";
  private static final String COLOR                   = "color";
  private static final String SEEN_INVITE_REMINDER    = "seen_invite_reminder";
  private static final String DEFAULT_SUBSCRIPTION_ID = "default_subscription_id";
  private static final String EXPIRE_MESSAGES         = "expire_messages";
          static final String REGISTERED              = "registered";
  private static final String PROFILE_KEY             = "profile_key";
  private static final String SYSTEM_DISPLAY_NAME     = "system_display_name";
  private static final String SYSTEM_PHOTO_URI        = "system_contact_photo";
  private static final String SYSTEM_PHONE_LABEL      = "system_phone_label";
  private static final String SYSTEM_CONTACT_URI      = "system_contact_uri";
  private static final String SIGNAL_PROFILE_NAME     = "signal_profile_name";
  private static final String SIGNAL_PROFILE_AVATAR   = "signal_profile_avatar";
  private static final String PROFILE_SHARING         = "profile_sharing_approval";
  private static final String SHARED_PROFILE          = "shared_profile";// users who shared their profiles with us
  private static final String CALL_RINGTONE           = "call_ringtone";
  private static final String CALL_VIBRATE            = "call_vibrate";
  private static final String NOTIFICATION_CHANNEL    = "notification_channel";
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
  private static final String FORCE_SMS_SELECTION      = "force_sms_selection";

  //
  private static final String UFSRVID         = "ufsrvid";//multiplexes shortform fid and uid
  private static final String E164NUMBER      = "e164number";
  private static final String USERNAME        = "username";
  private static final String UFSRVUID        = "ufsrvuid";//long form encoded ufrsvid
  private static final String EVENTID         = "eid";//multiplexes eid for user and fence
  private static final String RECIPIENT_TYPE  = "recipient_type";
  private static final String NICKNAME        = "nickname";
  private static final String AVATAR_UFSRV_ID ="avatar_ufsrv_id";
  private static final String BASELOC         = "baseloc";
  private static final String BUDDY           = "buddy";
  private static final String FOLLOW          = "follow"; //we follow
  private static final String FOLLOWED        = "followed"; //following us
  private static final String STICKY          = "sticky";
  private static final String SHARE_PRESENCE  = "share_presence";//can see our presence
  private static final String SHARE_LOCATION  = "share_location";//can see our location
  private static final String SHARED_PRESENCE  = "shared_presence";//shared their presence with us
  private static final String SHARED_LOCATION  = "shared_location";
  private static final String SHARED_BLOCK     = "shared_block"; //those who're blocking this user
  private static final String SHARED_READ_RECEIPT  = "shared_read_receipt";
  private static final String SHARE_READ_RECEIPT  = "share_read_receipt";
  private static final String SHARED_ACTIVITY_STATE = "shared_activity_state";
  private static final String SHARE_ACTIVITY_STATE = "share_activity_state";
  private static final String SHARED_CONTACT  = "shared_contact"; //allwed us to share their contact info
  public static final String SHARE_CONTACT    = "share_contact"; //we allowed them to share my contact info
  private static final String PRESENCE_INFO    = "presence_info"; //status, timestamp(seconds)
  private static final String LOCATION_INFO    = "location_info"; //long,lat


  private static final String PERM_PRESENTATION = "perm_presentation";
  private static final String PREM_MEMBERSHIP   = "perm_membership";
  private static final String PREM_MESSAGING    = "perm_messaging";
  private static final String PREM_ATTACHING    = "perm_attaching";
  private static final String PREM_CALLING      = "perm_calling";

  private static final String PERM_PRESENTATION_LIST_SEMANTICS  = "perm_presentation_list_semantics";
  private static final String PREM_MEMBERSHIP_LIST_SEMANTICS    = "perm_membership_list_semantics";
  private static final String PREM_MESSAGING_LIST_SEMANTICS     = "perm_messaging_list_semantics";
  private static final String PREM_ATTACHING_LIST_SEMANTICS     = "perm_attaching_list_semantics";
  private static final String PREM_CALLING_LIST_SEMANTICS       = "perm_calling_list_semantics";

  private static final String PERM_PRESENTATION_BASELIST  = "perm_presentation_baselist";
  private static final String PREM_MEMBERSHIP_BASELIST    = "perm_membership_baselist";
  private static final String PREM_MESSAGING_BASELIST     = "perm_messaging_baselist";
  private static final String PREM_ATTACHING_BASELIST     = "perm_attaching_baselist";
  private static final String PREM_CALLING_BASELIST       = "perm_calling_baselist";

  private static final String[] RECIPIENT_PROJECTION = new String[] {
          BLOCK, NOTIFICATION, CALL_RINGTONE, VIBRATE, CALL_VIBRATE, MUTE_UNTIL, COLOR, SEEN_INVITE_REMINDER, DEFAULT_SUBSCRIPTION_ID, EXPIRE_MESSAGES, REGISTERED, PROFILE_KEY, SYSTEM_DISPLAY_NAME, SYSTEM_PHOTO_URI, SYSTEM_PHONE_LABEL, SYSTEM_CONTACT_URI,
          SIGNAL_PROFILE_NAME, SIGNAL_PROFILE_AVATAR, PROFILE_SHARING, NOTIFICATION_CHANNEL,
          UNIDENTIFIED_ACCESS_MODE,
          FORCE_SMS_SELECTION,
          SHARED_PROFILE,//
          UFSRVUID,
          UFSRVID,
          E164NUMBER,
          USERNAME,
          EVENTID,
          RECIPIENT_TYPE,
          NICKNAME,
          AVATAR_UFSRV_ID,
          BASELOC,
          BUDDY,
          FOLLOW,
          STICKY,
          SHARE_LOCATION,
          SHARED_LOCATION,
          LOCATION_INFO,
          SHARE_PRESENCE,
          SHARED_PRESENCE,
          SHARE_READ_RECEIPT,
          SHARED_READ_RECEIPT,
          SHARE_ACTIVITY_STATE,
          SHARED_ACTIVITY_STATE,
          SHARED_BLOCK,
          SHARE_CONTACT,
          SHARED_CONTACT,
          PRESENCE_INFO,
          PERM_PRESENTATION ,
          PREM_MEMBERSHIP   ,
          PREM_MESSAGING    ,
          PREM_ATTACHING    ,
          PREM_CALLING      ,
          PERM_PRESENTATION_LIST_SEMANTICS,
          PREM_MEMBERSHIP_LIST_SEMANTICS,
          PREM_MESSAGING_LIST_SEMANTICS,
          PREM_ATTACHING_LIST_SEMANTICS,
          PREM_CALLING_LIST_SEMANTICS,
          PERM_PRESENTATION_BASELIST,
          PREM_MEMBERSHIP_BASELIST,
          PREM_MESSAGING_BASELIST ,
          PREM_ATTACHING_BASELIST ,
          PREM_CALLING_BASELIST
  };

  static final List<String> TYPED_RECIPIENT_PROJECTION = Stream.of(RECIPIENT_PROJECTION)
          .map(columnName -> TABLE_NAME + "." + columnName)
          .toList();

  private static final Map<EnumFencePermissions, String> permissionsDbColumnHashMap;
  static {
    HashMap<EnumFencePermissions, String> permissionsHashmap=new HashMap<>();
    permissionsHashmap.put(EnumFencePermissions.PRESENTATION, "perm_presentation");
    permissionsHashmap.put(EnumFencePermissions.MEMBERSHIP,"perm_membership");
    permissionsHashmap.put(EnumFencePermissions.MESSAGING, "perm_messaging");
    permissionsHashmap.put(EnumFencePermissions.ATTACHING, "perm_attaching");
    permissionsHashmap.put(EnumFencePermissions.CALLING, "perm_calling");
    permissionsDbColumnHashMap= Collections.unmodifiableMap(permissionsHashmap);
  }

  public enum EnumPermissionListSemantics
  {
    NONE(0),
    WHITELIST(1),//restricted permission: List is used as white list. only those on the list are permitted
    BLACKLIST(2); //default: open permission. List is used as balacklist: users on it not permitted. except for those on blacklist

    private int value;

    EnumPermissionListSemantics (int value)
    {
      this.value = value;
    }

    public int getValue ()
    {
      return value;
    }
  }

  //each permission can have a starting list
  public enum EnumPermissionBaseList
  {
    NONE(0),
    CONTACTS(1),
    BUDDIES(2),
    CONTACTS_BUDDIES(3),
    BLOCKED(4),
    CUSTOM(5);

    private int value;

    EnumPermissionBaseList (int value)
    {
      this.value = value;
    }

    public int getValue ()
    {
      return value;
    }
  }

  public enum VibrateState {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    private final int id;

    VibrateState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static VibrateState fromId(int id) {
      return values()[id];
    }
  }

  public enum RegisteredState {
    UNKNOWN(0), REGISTERED(1), NOT_REGISTERED(2);

    private final int id;

    RegisteredState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static RegisteredState fromId(int id) {
      return values()[id];
    }
  }

  //
  public enum GeogroupStickyState {
    DEFAULT(0), ENABLED(1), DISABLED(0);

    private final int id;

    GeogroupStickyState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static GeogroupStickyState fromId(int id) {
      return values()[id];
    }
  }

  static public class GroupPermission {
    private EnumPermissionListSemantics listSemantics;
    private EnumPermissionBaseList baseList;
    private EnumFencePermissions permission;

    public GroupPermission (EnumFencePermissions permission, EnumPermissionBaseList baseList, EnumPermissionListSemantics listSemantics)
    {
      this.permission=permission;
      this.baseList=baseList;
      this.listSemantics=listSemantics;
    }

    public EnumPermissionListSemantics getListSemantics ()
    {
      return listSemantics;
    }

    public EnumPermissionBaseList getBaseList ()
    {
      return baseList;
    }

    public EnumFencePermissions getPermission ()
    {
      return permission;
    }
  }

  public enum UnidentifiedAccessMode {
    UNKNOWN(0), DISABLED(1), ENABLED(2), UNRESTRICTED(3);

    private final int mode;

    UnidentifiedAccessMode(int mode) {
      this.mode = mode;
    }

    public int getMode() {
      return mode;
    }

    public static UnidentifiedAccessMode fromMode(int mode) {
      return values()[mode];
    }
  }

  public static final String CREATE_TABLE =
          "CREATE TABLE " + TABLE_NAME +
                  " (" + ID + " INTEGER PRIMARY KEY, " +
                  ADDRESS + " TEXT UNIQUE, " +
                  BLOCK + " INTEGER DEFAULT 0, " +
                  SHARED_BLOCK + " INTEGER DEFAULT 0, " + //
                  NOTIFICATION + " TEXT DEFAULT NULL, " +
                  VIBRATE + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                  MUTE_UNTIL + " INTEGER DEFAULT 0, " +
                  COLOR + " TEXT DEFAULT NULL, " +
                  SEEN_INVITE_REMINDER + " INTEGER DEFAULT 0, " +
                  DEFAULT_SUBSCRIPTION_ID + " INTEGER DEFAULT -1, " +
                  EXPIRE_MESSAGES + " INTEGER DEFAULT 0, " +
                  REGISTERED + " INTEGER DEFAULT 0, " +
                  SYSTEM_DISPLAY_NAME + " TEXT DEFAULT NULL, " +
                  SYSTEM_PHOTO_URI + " TEXT DEFAULT NULL, " +
                  SYSTEM_PHONE_LABEL + " TEXT DEFAULT NULL, " +
                  SYSTEM_CONTACT_URI + " TEXT DEFAULT NULL, " +
                  PROFILE_KEY + " TEXT DEFAULT NULL, " +
                  SIGNAL_PROFILE_NAME + " TEXT DEFAULT NULL, " +
                  SIGNAL_PROFILE_AVATAR + " TEXT DEFAULT NULL, " +
                  PROFILE_SHARING + " INTEGER DEFAULT 0, "+
                  SHARED_PROFILE + " INTEGER DEFAULT 0, "+ //
                  CALL_RINGTONE + " TEXT DEFAULT NULL, " +
                  CALL_VIBRATE + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                  NOTIFICATION_CHANNEL + " TEXT DEFAULT NULL, " +
                  UNIDENTIFIED_ACCESS_MODE + " INTEGER DEFAULT 0, " +
                  FORCE_SMS_SELECTION + " INTEGER DEFAULT 0, " +

                  UFSRVID + " INTEGER, "  +
                  UFSRVUID + " TEXT UNIQUE, " +
                  E164NUMBER + " TEXT, " +
                  USERNAME + " TEXT UNIQUE, " +
                  EVENTID + " INTEGER, "  +
                  RECIPIENT_TYPE + " INTEGER DEFAULT 0, "  +
                  NICKNAME     + " TEXT, " +
                  AVATAR_UFSRV_ID + " TEXT DEFAULT \"0\", " +
                  BASELOC + " TEXT DEFAULT NULL, " +
                  BUDDY + " INTEGER DEFAULT 0," +
                  FOLLOW + " INTEGER DEFAULT 0, " +
                  STICKY + " INTEGER DEFAULT 0, " +

                  SHARE_LOCATION + " INTEGER DEFAULT 0, " +
                  SHARED_LOCATION + " INTEGER DEFAULT 0, " +
                  LOCATION_INFO + " TEXT DEFAULT NULL, " +
                  SHARE_PRESENCE + " INTEGER DEFAULT 0, " +
                  SHARED_PRESENCE + " INTEGER DEFAULT 0, " +
                  SHARE_READ_RECEIPT + " INTEGER DEFAULT 0, " +
                  SHARED_READ_RECEIPT + " INTEGER DEFAULT 0, " +
                  SHARE_ACTIVITY_STATE + " INTEGER DEFAULT 0, " +
                  SHARED_ACTIVITY_STATE + " INTEGER DEFAULT 0, " +
                  SHARE_CONTACT + " INTEGER DEFAULT 0, " +
                  SHARED_CONTACT + " INTEGER DEFAULT 0, " +
                  PRESENCE_INFO + " TEXT DEFAULT NULL, " +

                  PERM_PRESENTATION + " TEXT DEFAULT NULL, " +
                  PREM_MEMBERSHIP + " TEXT DEFAULT NULL, " +
                  PREM_MESSAGING + " TEXT DEFAULT NULL, " +
                  PREM_ATTACHING + " TEXT DEFAULT NULL, " +
                  PREM_CALLING + " TEXT DEFAULT NULL, " +

                  PERM_PRESENTATION_LIST_SEMANTICS + " INTEGER DEFAULT 2, " +
                  PREM_MEMBERSHIP_LIST_SEMANTICS + " INTEGER DEFAULT 2, " +
                  PREM_MESSAGING_LIST_SEMANTICS + " INTEGER DEFAULT 2, " +
                  PREM_ATTACHING_LIST_SEMANTICS + " INTEGER DEFAULT 2, " +
                  PREM_CALLING_LIST_SEMANTICS + " INTEGER DEFAULT 2, " +

                  PERM_PRESENTATION_BASELIST + " INTEGER DEFAULT 0, " +
                  PREM_MEMBERSHIP_BASELIST + " INTEGER DEFAULT 0, " +
                  PREM_MESSAGING_BASELIST + " INTEGER DEFAULT 0, " +
                  PREM_ATTACHING_BASELIST + " INTEGER DEFAULT 0, " +
                  PREM_CALLING_BASELIST + " INTEGER DEFAULT 0 " +
                  ");";

  public RecipientDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public Cursor getBlocked() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, new String[] {ID, ADDRESS, UFSRVUID}, BLOCK + " = 1",
                          null, null, null, null, null);
  }

  public RecipientReader readerForBlocked(Cursor cursor) {
    return new RecipientReader(context, cursor);
  }

  public RecipientReader getRecipientsWithNotificationChannels() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, NOTIFICATION_CHANNEL  + " NOT NULL",
                                             null, null, null, null, null);

    return new RecipientReader(context, cursor);
   }

  //
  public Cursor getPresenceSharing() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, new String[] {ID, ADDRESS, UFSRVID}, SHARE_PRESENCE + " = 1",
                          null, null, null, null, null);
  }

  public Cursor getPresenceShared() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, new String[] {ID, ADDRESS, UFSRVID}, SHARED_PRESENCE + " = 1",
                          null, null, null, null, null);
  }

  public PresenceSharedReader readerForPresenceShared (Cursor cursor) {
    return new PresenceSharedReader(context, cursor);
  }

  public Cursor getLocationSharing() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, new String[] {ID, ADDRESS, UFSRVID}, SHARE_LOCATION + " = 1",
                          null, null, null, null, null);
  }

  public LocationSharedReader readerForLocationShared(Cursor cursor) {
    return new LocationSharedReader(context, cursor);
  }

  public Cursor getStickyGeogroups() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    Cursor cursor = database.query(TABLE_NAME, new String[] {ID, ADDRESS}, STICKY + " = 1",
            null, null, null, null, null);

    return cursor;
  }

  public StickyGeogroupsReader readerForStickyGeogroups(Cursor cursor) {
    return new StickyGeogroupsReader(context, cursor);
  }
  //

  public Optional<RecipientSettings> getRecipientSettings (@NonNull Address address) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, null, ADDRESS + " = ?", new String[] {address.serialize()}, null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      }

      return Optional.absent();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  // emphasis on e164
  public Optional<RecipientSettings> getContactRecipientSettings (@NonNull String e164NUMBER) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, null,  E164NUMBER + " = ?", new String[] {e164NUMBER}, null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      }

      return Optional.absent();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

//
  public Optional<RecipientSettings> getRecipientSettings (String useridEncoded) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, null, UFSRVUID + " = ?", new String[] {useridEncoded}, null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      }

      return Optional.absent();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public Optional<RecipientSettings> getRecipientSettings (long ufsrvId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, null, UFSRVID + " = ?", new String[] {String.valueOf(ufsrvId)}, null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      }

      return Optional.absent();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public Optional<RecipientSettings> getRecipientSettings (ContactTokenDetails tokenDetails) {
    //
    List<Address> addresses = null;
    if (!TextUtils.isEmpty(tokenDetails.getUsername())) {
      Address address = Address.fromExternal(ApplicationContext.getInstance(), tokenDetails.getUfsrvUid());
      if (!address.toString().contains("Unknown")) {
        addresses = new LinkedList<>();
        addresses.add(address);
      }
    }
    //
    return Optional.of(new RecipientSettings(false, 0,
                                             VibrateState.DEFAULT,
                                             VibrateState.DEFAULT,
                                             null, null,
                                             MaterialColor.BLUE, false,
                                             0, 0,
                                             RegisteredState.UNKNOWN,
                                             null, null, null,
                                             null, null, null,
                                             null, false, null,
                                             UNRESTRICTED, false,
                                             false,
                                             addresses, //
                                             tokenDetails.getUfsrvUid(), UfsrvUid.DecodeUfsrvSequenceId(UfsrvUid.DecodeUfsrvUid(tokenDetails.getUfsrvUid())),
                                             tokenDetails.getUsername(), null,
                                             tokenDetails.getEid(),
                                             Recipient.RecipientType.USER, tokenDetails.getNickname(), null, GeogroupStickyState.DEFAULT,
                                             false, false, null,
                                             false, false, false, false, false, false, false,
                                             false, false, null,
                                             null, null, null, null, null,
                                             EnumPermissionListSemantics.NONE, EnumPermissionListSemantics.NONE, EnumPermissionListSemantics.NONE,
                                             EnumPermissionListSemantics.NONE, EnumPermissionListSemantics.NONE));
  }

  Optional<RecipientSettings> getRecipientSettings(@NonNull Cursor cursor) {
//    Log.e(TAG, android.database.DatabaseUtils.dumpCursorToString(cursor));
    boolean blocked               = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCK))                == 1;
    String  messageRingtone       = cursor.getString(cursor.getColumnIndexOrThrow(NOTIFICATION));
    String  callRingtone          = cursor.getString(cursor.getColumnIndexOrThrow(CALL_RINGTONE));
    int     messageVibrateState   = cursor.getInt(cursor.getColumnIndexOrThrow(VIBRATE));
    int     callVibrateState      = cursor.getInt(cursor.getColumnIndexOrThrow(CALL_VIBRATE));
    long    muteUntil             = cursor.getLong(cursor.getColumnIndexOrThrow(MUTE_UNTIL));
    String  serializedColor       = cursor.getString(cursor.getColumnIndexOrThrow(COLOR));
    boolean seenInviteReminder    = cursor.getInt(cursor.getColumnIndexOrThrow(SEEN_INVITE_REMINDER)) == 1;
    int     defaultSubscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow(DEFAULT_SUBSCRIPTION_ID));
    int     expireMessages        = cursor.getInt(cursor.getColumnIndexOrThrow(EXPIRE_MESSAGES));
    int     registeredState       = cursor.getInt(cursor.getColumnIndexOrThrow(REGISTERED));
    String  profileKeyString      = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_KEY));
    String  systemDisplayName     = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME));
    String  systemContactPhoto    = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHOTO_URI));
    String  systemPhoneLabel      = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHONE_LABEL));
    String  systemContactUri      = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_CONTACT_URI));
    String  signalProfileName     = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_NAME));
    String  signalProfileAvatar   = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_AVATAR));
    boolean profileSharing        = cursor.getInt(cursor.getColumnIndexOrThrow(PROFILE_SHARING))      == 1;
    String  notificationChannel   = cursor.getString(cursor.getColumnIndexOrThrow(NOTIFICATION_CHANNEL));
    int     unidentifiedAccessMode = cursor.getInt(cursor.getColumnIndexOrThrow(UNIDENTIFIED_ACCESS_MODE));
    boolean forceSmsSelection      = cursor.getInt(cursor.getColumnIndexOrThrow(FORCE_SMS_SELECTION))  == 1;
    boolean profileShared         = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_PROFILE))       == 1;//

    List<Address> address                 =  Address.fromSerializedList(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)), ',');
    String    ufsrvUidEncoded             = cursor.getString(cursor.getColumnIndexOrThrow(UFSRVUID));
    long      ufsrvId                     = cursor.getLong(cursor.getColumnIndexOrThrow(UFSRVID));
    String    e164Number                  = cursor.getString(cursor.getColumnIndexOrThrow(E164NUMBER));
    String    username                    = cursor.getString(cursor.getColumnIndexOrThrow(USERNAME));
    long      eId                         = cursor.getLong(cursor.getColumnIndexOrThrow(EVENTID));

    Recipient.RecipientType recipientType = Recipient.RecipientType.fromId(cursor.getInt(cursor.getColumnIndexOrThrow(RECIPIENT_TYPE)));
    String  nickname                      = cursor.getString(cursor.getColumnIndexOrThrow(NICKNAME));
    //todo: the join query doesn't seem to load values from RecipinetDatabase
    String  avatarUfsrvId;
    try {
      avatarUfsrvId                 = cursor.getString(cursor.getColumnIndexOrThrow(GroupDatabase.AVATAR_UFID));
    } catch (IllegalArgumentException x) {
      avatarUfsrvId                 = cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_UFSRV_ID));
    }
    int     geogroupStickyState           = cursor.getInt(cursor.getColumnIndexOrThrow(STICKY));
    List<String> permissionPresentation   = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(PERM_PRESENTATION)), ",");
    List<String> permissionMembership     = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(PREM_MEMBERSHIP)), ",");
    List<String> permissionMessaging      = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(PREM_MESSAGING)), ",");
    List<String> permissionAttaching      = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(PREM_ATTACHING)), ",");
    List<String> permissionCalling        = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(PREM_CALLING)), ",");
    boolean presenceSharing               = cursor.getInt(cursor.getColumnIndexOrThrow(SHARE_PRESENCE))      == 1;
    boolean presenceShared                = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_PRESENCE))      == 1;
    String presenceInformation            = cursor.getString(cursor.getColumnIndexOrThrow(PRESENCE_INFO));
    boolean readReceiptSharing            = cursor.getInt(cursor.getColumnIndexOrThrow(SHARE_READ_RECEIPT))      == 1;
    boolean readReceiptShared             = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_READ_RECEIPT))      == 1;
    boolean typingIndicatorSharing        = cursor.getInt(cursor.getColumnIndexOrThrow(SHARE_ACTIVITY_STATE))      == 1;
    boolean typingIndicatorShared         = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_ACTIVITY_STATE))      == 1;
    boolean locationSharing               = cursor.getInt(cursor.getColumnIndexOrThrow(SHARE_LOCATION))      == 1;
    boolean locationShared                = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_LOCATION))      == 1;
    boolean blockShared                   = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_BLOCK))      == 1;
    boolean contactSharing               = cursor.getInt(cursor.getColumnIndexOrThrow(SHARE_CONTACT))      == 1;
    boolean contactShared                = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_CONTACT))      == 1;
    String locationInformation            = cursor.getString(cursor.getColumnIndexOrThrow(LOCATION_INFO));
    EnumPermissionListSemantics permSemanticsPresentation = EnumPermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PERM_PRESENTATION_LIST_SEMANTICS))];
    EnumPermissionListSemantics permSemanticsMembership   = EnumPermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PREM_MEMBERSHIP_LIST_SEMANTICS))];
    EnumPermissionListSemantics permSemanticsMessaging    = EnumPermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PREM_MESSAGING_LIST_SEMANTICS))];
    EnumPermissionListSemantics permSemanticsAttaching    = EnumPermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PREM_ATTACHING_LIST_SEMANTICS))];
    EnumPermissionListSemantics permSemanticsCalling      = EnumPermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PREM_CALLING_LIST_SEMANTICS))];

    MaterialColor color;
    byte[]        profileKey = null;

    try {
      color = serializedColor == null ? null : MaterialColor.fromSerialized(serializedColor);
    } catch (MaterialColor.UnknownColorException e) {
      Log.w(TAG, e);
      color = null;
    }

    if (profileKeyString != null) {
      try {
          profileKey = Base64.decode(profileKeyString);
        } catch (IOException e) {
          Log.w(TAG, e);
          profileKey = null;
        }
    }

//    Log.w(TAG, "Muted until: " + muteUntil);

    return Optional.of(new RecipientSettings(blocked, muteUntil,
                                             VibrateState.fromId(messageVibrateState),
                                             VibrateState.fromId(callVibrateState),
                                             Util.uri(messageRingtone), Util.uri(callRingtone),
                                             color, seenInviteReminder,
                                             defaultSubscriptionId, expireMessages,
                                             RegisteredState.fromId(registeredState),
                                             profileKey, systemDisplayName, systemContactPhoto,
                                             systemPhoneLabel, systemContactUri,
                                             signalProfileName, signalProfileAvatar, profileSharing,
                                             notificationChannel,
                                             UnidentifiedAccessMode.fromMode(unidentifiedAccessMode),
                                             forceSmsSelection,
                                             profileShared,// profileShared
                                             address, ufsrvUidEncoded, ufsrvId,
                                             e164Number, username,
                                             eId, recipientType, nickname, avatarUfsrvId, GeogroupStickyState.fromId(geogroupStickyState),
                                             presenceSharing, presenceShared, presenceInformation,
                                             readReceiptSharing, readReceiptShared, typingIndicatorSharing, typingIndicatorShared,
                                             blockShared,
                                             contactSharing, contactShared,
                                             locationSharing, locationShared, locationInformation,
                                             permissionPresentation, permissionMembership, permissionMessaging, permissionAttaching, permissionCalling,
                                             permSemanticsPresentation, permSemanticsMembership, permSemanticsMessaging, permSemanticsAttaching, permSemanticsCalling)); // sticky

  }

  public Set<Address> getAllAddresses(Recipient.RecipientType recipientType) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    Set<Address>   results = new HashSet<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ADDRESS}, RECIPIENT_TYPE + " = ?", new String[] {recipientType.toString()}, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(Address.fromExternal(context, cursor.getString(0)));
      }
    }

    return results;
  }

  public void setNotificationChannel(@NonNull Recipient recipient, @Nullable String notificationChannel) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(NOTIFICATION_CHANNEL, notificationChannel);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.setNotificationChannel(notificationChannel);
  }

  //
  public void setRegistered(@NonNull Recipient recipient, RegisteredState registeredState) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setRegistered(registeredState);
  }
  //

  public void setRegistered(@NonNull Recipient recipient, RegisteredState registeredState, ContactTokenDetails contactTokenDetails) {
    Log.d(TAG, String.format("setRegistered: Registering user '%s'", recipient.getAddress()));
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setRegistered(registeredState);
    setContactTokenDetails (recipient.getAddress(), contactTokenDetails);//
  }

  //
  public void setRegistered(@NonNull Address address, RegisteredState registeredState, ContactTokenDetails contactTokenDetails) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());
    updateOrInsert(address, contentValues);
    setContactTokenDetails (address, contactTokenDetails);//
    Recipient.applyCached(address, recipient -> recipient.setRegistered(RegisteredState.REGISTERED));
  }
  //

  public void setRegistered(@NonNull List<Address> activeAddresses,
                            @NonNull List<Address> inactiveAddresses,
                            @NonNull List<ContactTokenDetails> activeTokens)//A++ activeTokens)
  {
    for (Address activeAddress : activeAddresses) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(REGISTERED, RegisteredState.REGISTERED.getId());

      updateOrInsert(activeAddress, contentValues);
      Recipient.applyCached(activeAddress, recipient -> recipient.setRegistered(RegisteredState.REGISTERED));
    }

    //
    for (ContactTokenDetails contactTokenDetails : activeTokens) {
      setContactTokenDetails (Address.fromExternal(context, contactTokenDetails.getUfsrvUid()), contactTokenDetails);//should use ufsrvid as this comes from device contacts that are registered
    }
    //

    for (Address inactiveAddress : inactiveAddresses) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());

      updateOrInsert(inactiveAddress, contentValues);
      Recipient.applyCached(inactiveAddress, recipient -> recipient.resolve().setRegistered(RegisteredState.NOT_REGISTERED));
    }
  }


  public void setContactTokenDetails(Address address, ContactTokenDetails token) {
    UfsrvUid ufsrvUid = new UfsrvUid(token.getUfsrvUid());

    ContentValues  contentValues = new ContentValues(4);
    contentValues.put(UFSRVUID, token.getUfsrvUid());
    contentValues.put(UFSRVID, ufsrvUid.getUfsrvSequenceId());
    contentValues.put(NICKNAME, token.getNickname());
    contentValues.put(E164NUMBER, token.getE164number());
    contentValues.put(USERNAME, token.getUsername());
    contentValues.put(RECIPIENT_TYPE, Recipient.RecipientType.USER.getValue());
    updateOrInsert(address, contentValues);

    Recipient.applyCached(address, (recipient) -> {
        recipient.setUfsrvUId(token.getUfsrvUid(), ufsrvUid.getUfsrvSequenceId());
        recipient.setNickname(token.getNickname());
        recipient.setE164Number(token.getE164number());
        recipient.setUsername(token.getUsername());
        recipient.setRecipientType(Recipient.RecipientType.USER);
    });
  }

  public List<Address> getRegistered() {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    List<Address>  results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ADDRESS}, REGISTERED + " = ?", new String[] {"1"}, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(Address.fromSerialized(cursor.getString(0)));
      }
    }

    return results;
  }

  public BulkOperationsHandle resetAllSystemContactInfo() {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SYSTEM_DISPLAY_NAME, (String)null);
    contentValues.put(SYSTEM_PHOTO_URI, (String)null);
    contentValues.put(SYSTEM_PHONE_LABEL, (String)null);
    contentValues.put(SYSTEM_CONTACT_URI, (String)null);

    database.update(TABLE_NAME, contentValues, null, null);

    return new BulkOperationsHandle(database);
  }

  public void setColor(@NonNull Recipient recipient, @NonNull MaterialColor color) {
    ContentValues values = new ContentValues();
    values.put(COLOR, color.serialize());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setColor(color);
  }

  public void setDefaultSubscriptionId(@NonNull Recipient recipient, int defaultSubscriptionId) {
    ContentValues values = new ContentValues();
    values.put(DEFAULT_SUBSCRIPTION_ID, defaultSubscriptionId);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setDefaultSubscriptionId(Optional.of(defaultSubscriptionId));
  }

  public void setForceSmsSelection(@NonNull Recipient recipient, boolean forceSmsSelection) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(FORCE_SMS_SELECTION, forceSmsSelection ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setForceSmsSelection(forceSmsSelection);
  }

  public void setBlocked(@NonNull Recipient recipient, boolean blocked) {
    setBlockSharing(recipient, blocked);
    return;
//    ContentValues values = new ContentValues();
//    values.put(BLOCK, blocked ? 1 : 0);
//    updateOrInsert(recipient.getAddress(), values);
//    recipient.resolve().setBlocked(blocked);
  }

  // default is 0, which is disabled (
  public void setGeogroupSticky(@NonNull Recipient recipient, @NonNull GeogroupStickyState enabled) {
    ContentValues values = new ContentValues();
    values.put(STICKY, enabled.getId());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setGeogroupSticky(GeogroupStickyState.ENABLED);
  }

  //
  public void setUfsrvUid (@NonNull Recipient recipient, String ufsrvUidEncoded) {
    UfsrvUid uid = new UfsrvUid(ufsrvUidEncoded);
    long ufsrvid  =  uid.getUfsrvSequenceId();

    ContentValues values = new ContentValues();
    values.put(UFSRVUID, ufsrvUidEncoded);
    values.put(UFSRVID, uid.getUfsrvSequenceId());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setUfsrvUId (ufsrvUidEncoded, ufsrvid);

  }

  public void setUfsrvId (@NonNull Recipient recipient, long id) {
    ContentValues values = new ContentValues();
    values.put(UFSRVID, id);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setUfsrvId(id);
  }

  public void setEid(@NonNull Recipient recipient, long id) {
    ContentValues values = new ContentValues();
    values.put(EVENTID, id);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setEid(id);
  }

  public void setE164Number(@NonNull Recipient recipient, @Nullable String e164Number) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(E164NUMBER, e164Number);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setE164Number(e164Number);
  }

  //
  public void setRecipientType(@NonNull Recipient recipient, Recipient.RecipientType recipientType) {
    ContentValues values = new ContentValues(1);
    values.put(RECIPIENT_TYPE, recipientType.getValue());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setRecipientType(recipientType);
  }

  public void setNickname(@NonNull Recipient recipient, @NonNull String nickname) {
    ContentValues values = new ContentValues(1);
    values.put(NICKNAME, nickname);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setNickname(nickname);
  }

  public void setAvatarUfId(@NonNull Recipient recipient, @Nullable String avatarUfId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(AVATAR_UFSRV_ID, avatarUfId);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setGroupAvatarUfsrvId(avatarUfId);
  }

  public void setRecipientId(@NonNull Recipient recipient, long recipientId, Recipient.RecipientType recipientType) {
    ContentValues values = new ContentValues(2);
    values.put(RECIPIENT_TYPE, recipientType.getValue());
    if (recipientType== Recipient.RecipientType.USER) values.put(RECIPIENT_TYPE, recipientType.getValue());
    else if(recipientType== Recipient.RecipientType.GROUP) values.put(RECIPIENT_TYPE, recipientType.getValue());

    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setRecipientType(recipientType);
  }

  public void setMessageRingtone(@NonNull Recipient recipient, @Nullable Uri notification) {
    ContentValues values = new ContentValues();
    values.put(NOTIFICATION, notification == null ? null : notification.toString());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setMessageRingtone(notification);
  }

  public void setCallRingtone(@NonNull Recipient recipient, @Nullable Uri ringtone) {
    ContentValues values = new ContentValues();
    values.put(CALL_RINGTONE, ringtone == null ? null : ringtone.toString());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setCallRingtone(ringtone);
  }

  public void setMessageVibrate(@NonNull Recipient recipient, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(VIBRATE, enabled.getId());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setMessageVibrate(enabled);
  }

  public void setCallVibrate(@NonNull Recipient recipient, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(CALL_VIBRATE, enabled.getId());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setCallVibrate(enabled);
  }

  public void setMuted(@NonNull Recipient recipient, long until) {
    Log.w(TAG, "Setting muted until: " + until);
    ContentValues values = new ContentValues();
    values.put(MUTE_UNTIL, until);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setMuted(until);
  }

  public void setSeenInviteReminder(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean seen) {
    ContentValues values = new ContentValues(1);
    values.put(SEEN_INVITE_REMINDER, seen ? 1 : 0);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setHasSeenInviteReminder(seen);
  }

  public void setExpireMessages(@NonNull Recipient recipient, int expiration) {
    recipient.setExpireMessages(expiration);

    ContentValues values = new ContentValues(1);
    values.put(EXPIRE_MESSAGES, expiration);
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setExpireMessages(expiration);
  }

  public void setUnidentifiedAccessMode(@NonNull Recipient recipient, @NonNull UnidentifiedAccessMode unidentifiedAccessMode) {
    ContentValues values = new ContentValues(1);
    values.put(UNIDENTIFIED_ACCESS_MODE, unidentifiedAccessMode.getMode());
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setUnidentifiedAccessMode(unidentifiedAccessMode);
  }

  public void setProfileKey(@NonNull Recipient recipient, @Nullable byte[] profileKey) {
    ContentValues values = new ContentValues(1);
    values.put(PROFILE_KEY, profileKey == null ? null : Base64.encodeBytes(profileKey));
    updateOrInsert(recipient.getAddress(), values);
    recipient.resolve().setProfileKey(profileKey);
  }

  public void setProfileName(@NonNull Recipient recipient, @Nullable String profileName) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SIGNAL_PROFILE_NAME, profileName);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setProfileName(profileName);
  }

  public void setProfileAvatar(@NonNull Recipient recipient, @Nullable String profileAvatar) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SIGNAL_PROFILE_AVATAR, profileAvatar);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setProfileAvatar(profileAvatar);
  }

  public void setProfileSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PROFILE_SHARING, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setProfileSharing(enabled);
  }

  public void setProfileShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_PROFILE, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setProfileShared(enabled);
  }

  public HashMap<Long, String>GetAllProfileSharing (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(PROFILE_SHARING + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        results.put(cursor.getLong(0), cursor.getString(1));
      }

      return results;
    }
    finally {
      if (cursor != null) cursor.close();
    }
  }

  public HashMap<Long, String>GetAllProfileShared (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(SHARED_PROFILE + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        results.put(cursor.getLong(0), cursor.getString(1));
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public int ClearAllProfileSharing (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllProfileSharing(recipientType);
    int totalCleared = tobeCleared.size();

    for (String ufsrvUidValue : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(ufsrvUidValue), false);
      setProfileSharing(recipient, false);
    }

    return totalCleared;
  }

  public int ClearAllProfileShared (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllProfileShared(recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setProfileShared(recipient, false);
    }

    return totalCleared;
  }

  // we are sharing (user on share list)
  public void setPresenceSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARE_PRESENCE, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceSharing(enabled);
  }

  //user shared their presence with us
  public void setPresenceShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_PRESENCE, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceShared(enabled);
  }

  public HashMap<Long, String>GetAllPresenceSharing (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(SHARE_PRESENCE + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        try {
          results.put(cursor.getLong(0), cursor.getString(1));
        } catch (NumberFormatException x) {
          continue;
        }
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public HashMap<Long, String>GetAllPresenceShared (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(SHARED_PRESENCE + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        results.put(cursor.getLong(0), cursor.getString(1));
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public int ClearAllPresenceSharing (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllPresenceSharing(recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setPresenceSharing(recipient, false);
    }

    return totalCleared;
  }

  public int ClearAllPresenceShared (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllPresenceShared(recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setPresenceShared(recipient, false);
    }

    return totalCleared;
  }

  //
  public void setReadReceiptSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARE_READ_RECEIPT, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceSharing(enabled);
  }

  public void setReadReceiptShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_READ_RECEIPT, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceShared(enabled);
  }

  public HashMap<Long, String>GetAllReadReceiptSharing (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(SHARE_READ_RECEIPT + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        try {
          results.put(cursor.getLong(0), cursor.getString(1));
        } catch (NumberFormatException x) {
          continue;
        }
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public HashMap<Long, String>GetAllReadReceiptShared (Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(SHARED_READ_RECEIPT + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        results.put(cursor.getLong(0), cursor.getString(1));
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public int ClearAllReadReceiptSharing (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllReadReceiptSharing(recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setReadReceiptSharing(recipient, false);
    }

    return totalCleared;
  }

  public int ClearAllReadReceiptShared (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetAllReadReceiptShared(recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setReadReceiptShared(recipient, false);
    }

    return totalCleared;
  }
  //

  //
  public void setContactSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARE_CONTACT, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceSharing(enabled);
  }

  public void setContactShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_CONTACT, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceShared(enabled);
  }

  public int ClearAllContactSharing (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetShareListFor(SHARE_CONTACT, recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setReadReceiptSharing(recipient, false);
    }

    return totalCleared;
  }

  public int ClearAllContactShared (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetShareListFor(SHARED_CONTACT, recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setReadReceiptShared(recipient, false);
    }

    return totalCleared;
  }
  //

  //
  public void setBlockSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(BLOCK, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setBlocked(enabled);

  }

  public void setBlockShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_BLOCK, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setBlockShared(enabled);
  }

  public int ClearAllBlockSharing (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetShareListFor(BLOCK, recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setBlockSharing(recipient, false);
    }

    return totalCleared;
  }

  public int ClearAllBlockShared (Context context, Recipient.RecipientType recipientType)
  {
    HashMap<Long, String> tobeCleared = GetShareListFor(SHARED_BLOCK, recipientType);
    int totalCleared = tobeCleared.size();

    for (String value : tobeCleared.values()) {
      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      setBlockShared(recipient, false);
    }

    return totalCleared;
  }
  //

  public void setPresenceInformation(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") String presenceInfo) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PRESENCE_INFO, presenceInfo);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceInformation(presenceInfo);
  }

  public void setLocationSharing(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARE_LOCATION, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setLocationSharing(enabled);
  }

  public void setLocationShared(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SHARED_LOCATION, enabled ? 1 : 0);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setLocationSharing(enabled);
  }

  public void setLocationInformation(@NonNull Recipient recipient, @SuppressWarnings("SameParameterValue") String locationInfo) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(LOCATION_INFO, locationInfo);
    updateOrInsert(recipient.getAddress(), contentValues);
    recipient.resolve().setPresenceInformation(locationInfo);
  }
  //

  public HashMap<Long, String> GetShareListFor (String shareListName, Recipient.RecipientType recipientType)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;
    HashMap<Long, String>  results = new HashMap<>();

    StringBuilder selectionBuilder = new StringBuilder();
    selectionBuilder.append(shareListName + " = 1");

    if (recipientType != Recipient.RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.getValue());
    }
    //else return all

    try {
      cursor = database.query(TABLE_NAME, new String[] {UFSRVID, ADDRESS}, selectionBuilder.toString(),
                              null, null, null, null);
      while (cursor != null && cursor.moveToNext()) {
        try {
          results.put(cursor.getLong(0), cursor.getString(1));
        } catch (NumberFormatException x) {
          continue;
        }
      }

      return results;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public List<Address> getSystemContacts() {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    List<Address>  results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ADDRESS}, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(Address.fromSerialized(cursor.getString(0)));
      }
    }

    return results;
  }

  public void updateSystemContactColors(@NonNull ColorUpdater updater) {
    SQLiteDatabase              db      = databaseHelper.getReadableDatabase();
    Map<Address, MaterialColor> updates = new HashMap<>();

    db.beginTransaction();
    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ADDRESS, COLOR, SYSTEM_DISPLAY_NAME}, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        Address address = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
        MaterialColor newColor = updater.update(cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME)),
                                                cursor.getString(cursor.getColumnIndexOrThrow(COLOR)));

        ContentValues contentValues = new ContentValues(1);
        contentValues.put(COLOR, newColor.serialize());
        db.update(TABLE_NAME, contentValues, ADDRESS + " = ?", new String[]{address.serialize()});

        updates.put(address, newColor);
      }
    } finally {
      db.setTransactionSuccessful();
      db.endTransaction();

      Stream.of(updates.entrySet()).forEach(entry -> {
        Recipient.applyCached(entry.getKey(), recipient -> {
          recipient.setColor(entry.getValue());
        });
      });
    }
  }

  // XXX This shouldn't be here, and is just a temporary workaround https://github.com/signalapp/Signal-Android/commit/7eb089c9def88279e1012a246e3de20d8d3a133f
  public RegisteredState isRegistered(@NonNull Address address) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] {REGISTERED}, ADDRESS + " = ?", new String[] {address.serialize()}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) return RegisteredState.fromId(cursor.getInt(0));
      else                                        return RegisteredState.UNKNOWN;
    }
  }

  private void updateOrInsert(Address address, ContentValues contentValues) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();

    database.beginTransaction();

    int updated = database.update(TABLE_NAME, contentValues, ADDRESS + " = ?",
                                  new String[] {address.serialize()});

    if (updated < 1) {
      contentValues.put(ADDRESS, address.serialize());
      database.insert(TABLE_NAME, null, contentValues);
    }

    database.setTransactionSuccessful();
    database.endTransaction();
  }

  public class BulkOperationsHandle {

    private final SQLiteDatabase database;

    private final Map<Address, PendingContactInfo> pendingContactInfoMap = new HashMap<>();

    BulkOperationsHandle(SQLiteDatabase database) {
      this.database = database;
    }

    public void setSystemContactInfo(@NonNull Address address, @Nullable String displayName, @Nullable String photoUri, @Nullable String systemPhoneLabel, @Nullable String systemContactUri) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(SYSTEM_DISPLAY_NAME, displayName);
      contentValues.put(SYSTEM_PHOTO_URI, photoUri);
      contentValues.put(SYSTEM_PHONE_LABEL, systemPhoneLabel);
      contentValues.put(SYSTEM_CONTACT_URI, systemContactUri);

      updateOrInsert(address, contentValues);
      pendingContactInfoMap.put(address, new PendingContactInfo(displayName, photoUri, systemPhoneLabel, systemContactUri));
    }

    public void finish() {
      database.setTransactionSuccessful();
      database.endTransaction();

      Stream.of(pendingContactInfoMap.entrySet())
              .forEach(entry -> Recipient.applyCached(entry.getKey(), recipient -> {
                recipient.setName(entry.getValue().displayName);
                recipient.setSystemContactPhoto(Util.uri(entry.getValue().photoUri));
                recipient.setCustomLabel(entry.getValue().phoneLabel);
                recipient.setContactUri(Util.uri(entry.getValue().contactUri));
              }));
    }
  }

  public interface ColorUpdater {
    MaterialColor update(@NonNull String name, @Nullable String color);
  }

  public static class RecipientSettings {
    private final boolean         blocked;
    private final long            muteUntil;
    private final VibrateState    messageVibrateState;
    private final VibrateState    callVibrateState;
    private final Uri             messageRingtone;
    private final Uri             callRingtone;
    private final MaterialColor   color;
    private final boolean         seenInviteReminder;
    private final int             defaultSubscriptionId;
    private final int             expireMessages;
    private final RegisteredState registered;
    private final byte[]          profileKey;
    private final String          systemDisplayName;
    private final String          systemContactPhoto;
    private final String          systemPhoneLabel;
    private final String          systemContactUri;
    private final String          signalProfileName;
    private final String          signalProfileAvatar;
    private final boolean         profileSharing;
    private final String          notificationChannel;
    private final UnidentifiedAccessMode unidentifiedAccessMode;
    private final boolean                forceSmsSelection;
    private final boolean         profileShared;//

    //
    private final List<Address>   address;
    private final String          ufsrvUidEncoded;
    private final long            ufsrvId;
    private final String          e164Number;
    private final String          username;
    private final long            eId;
    private final Recipient.RecipientType recipientType;
    private final String          nickname;
    private final String          avatarUfsrvId;
    private final GeogroupStickyState       sticky;
    private final boolean         presenceSharing;
    private final boolean         presenceShared;
    private final String          presenceInformation;
    private final boolean         readReceiptSharing;
    private final boolean         readReceiptShared;
    private final boolean         typingIndicatorSharing;
    private final boolean         typingIndicatorShared;
    private final boolean         locationSharing;
    private final boolean         locationShared;
    private final boolean         blockShared;
    private final boolean         contactSharing;
    private final boolean         contactShared;
    private final String          locationInformation;
    private final List<String>    permissionPresentation;
    private final List<String>    permissionMembership;
    private final List<String>    permissionMessaging;
    private final List<String>    permissionAttaching;
    private final List<String>    permissionCalling;
    private final EnumPermissionListSemantics permSemanticsPresentation;
    private final EnumPermissionListSemantics permSemanticsMembership;
    private final EnumPermissionListSemantics permSemanticsMessaging;
    private final EnumPermissionListSemantics permSemanticsAttaching;
    private final EnumPermissionListSemantics permSemanticsCalling;

    RecipientSettings (boolean blocked, long muteUntil,
                       @NonNull VibrateState messageVibrateState,
                       @NonNull VibrateState callVibrateState,
                       @Nullable Uri messageRingtone,
                       @Nullable Uri callRingtone,
                       @Nullable MaterialColor color,
                       boolean seenInviteReminder,
                       int defaultSubscriptionId,
                       int expireMessages,
                       @NonNull  RegisteredState registered,
                       @Nullable byte[] profileKey,
                       @Nullable String systemDisplayName,
                       @Nullable String systemContactPhoto,
                       @Nullable String systemPhoneLabel,
                       @Nullable String systemContactUri,
                       @Nullable String signalProfileName,
                       @Nullable String signalProfileAvatar,
                       boolean profileSharing,
                       @Nullable String notificationChannel,
                       @NonNull UnidentifiedAccessMode unidentifiedAccessMode,
                       boolean forceSmsSelection,
                       boolean profileShared,//

                       List<Address> address,
                       String ufsrvUidEncoded,
                       long ufsrvId,
                       String e164Number,
                       String username,
                       long eid,
                       Recipient.RecipientType recipientType,
                       String nickname,
                       String avatarUfsrvId,
                       GeogroupStickyState sticky,
                       boolean presenceSharing,
                       boolean presenceShared,
                       String  presenceInformation,
                       boolean readReceiptSharing,
                       boolean readReceiptShared,
                       boolean typingIndicatorSharing,
                       boolean typingIndicatorShared,
                       boolean blockShared,
                       boolean contactSharing,
                       boolean contactShared,
                       boolean locationSharing,
                       boolean locationShared,
                       String  locationInformation,
                       List<String>permissionPresentation,
                       List<String>permissionMembership,
                       List<String>permissionMessaging,
                       List<String>permissionAttaching,
                       List<String>permissionCalling,
                       EnumPermissionListSemantics permSemanticsPresentation,
                       EnumPermissionListSemantics permSemanticsMembership,
                       EnumPermissionListSemantics permSemanticsMessaging,
                       EnumPermissionListSemantics permSemanticsAttaching,
                       EnumPermissionListSemantics permSemanticsCalling)// sticky
    {
      this.blocked               = blocked;
      this.muteUntil             = muteUntil;
      this.messageVibrateState   = messageVibrateState;
      this.callVibrateState      = callVibrateState;
      this.messageRingtone       = messageRingtone;
      this.callRingtone          = callRingtone;
      this.color                 = color;
      this.seenInviteReminder    = seenInviteReminder;
      this.defaultSubscriptionId = defaultSubscriptionId;
      this.expireMessages        = expireMessages;
      this.registered            = registered;
      this.profileKey            = profileKey;
      this.systemDisplayName     = systemDisplayName;
      this.systemContactPhoto    = systemContactPhoto;
      this.systemPhoneLabel      = systemPhoneLabel;
      this.systemContactUri      = systemContactUri;
      this.signalProfileName     = signalProfileName;
      this.signalProfileAvatar   = signalProfileAvatar;
      this.profileSharing        = profileSharing;
      this.notificationChannel   = notificationChannel;
      this.unidentifiedAccessMode = unidentifiedAccessMode;
      this.forceSmsSelection      = forceSmsSelection;

      this.profileShared         = profileShared;//

      this.address                = address;
      this.ufsrvUidEncoded        = ufsrvUidEncoded;
      this.e164Number             = e164Number;
      this.username               = username;
      this.ufsrvId                = ufsrvId;
      this.eId                    = eid;
      this.recipientType          = recipientType;
      this.nickname               = nickname;
      this.avatarUfsrvId          = avatarUfsrvId;
      this.sticky                 = sticky; //
      this.presenceSharing        = presenceSharing;
      this.presenceShared         = presenceShared;
      this.presenceInformation    = presenceInformation;
      this.readReceiptSharing     = readReceiptSharing;
      this.readReceiptShared      = readReceiptShared;
      this.typingIndicatorSharing = typingIndicatorSharing;
      this.typingIndicatorShared  = typingIndicatorShared;
      this.blockShared            = blockShared;
      this.contactSharing         = contactSharing;
      this.contactShared          = contactShared;
      this.locationSharing        = locationSharing;
      this.locationShared         = locationShared;
      this.locationInformation    = locationInformation;
      this.permissionPresentation = permissionPresentation;
      this.permissionMembership   = permissionMembership;
      this.permissionMessaging    = permissionMessaging;
      this.permissionAttaching    = permissionAttaching;
      this.permissionCalling      = permissionCalling;
      this.permSemanticsPresentation  = permSemanticsPresentation;
      this.permSemanticsMembership    = permSemanticsMembership;
      this.permSemanticsMessaging     = permSemanticsMessaging;
      this.permSemanticsAttaching     = permSemanticsAttaching;
      this.permSemanticsCalling       = permSemanticsCalling;
    }

    public @Nullable MaterialColor getColor() {
      return color;
    }

    public boolean isBlocked() {
      return blocked;
    }

    public long getMuteUntil() {
      return muteUntil;
    }

    public @NonNull VibrateState getMessageVibrateState() {
      return messageVibrateState;
    }

    public @NonNull VibrateState getCallVibrateState() {
      return callVibrateState;
    }

    public @Nullable Uri getMessageRingtone() {
      return messageRingtone;
    }

    public @Nullable Uri getCallRingtone() {
      return callRingtone;
    }

    public boolean hasSeenInviteReminder() {
      return seenInviteReminder;
    }

    public Optional<Integer> getDefaultSubscriptionId() {
      return defaultSubscriptionId != -1 ? Optional.of(defaultSubscriptionId) : Optional.absent();
    }

    public int getExpireMessages() {
      return expireMessages;
    }

    public RegisteredState getRegistered() {
      return registered;
    }

    public @Nullable byte[] getProfileKey() {
      return profileKey;
    }

    public @Nullable String getSystemDisplayName() {
      return systemDisplayName;
    }

    public @Nullable String getSystemContactPhotoUri() {
      return systemContactPhoto;
    }

    public @Nullable String getSystemPhoneLabel() {
      return systemPhoneLabel;
    }

    public @Nullable String getSystemContactUri() {
      return systemContactUri;
    }

    public @Nullable String getProfileName() {
      return signalProfileName;
    }

    public @Nullable String getProfileAvatar() {
      return signalProfileAvatar;
    }

    public boolean isProfileSharing() {
      return profileSharing;
    }

    //
    public boolean isProfileShared() {
      return profileShared;
    }

    public @Nullable String getNotificationChannel() {
      return notificationChannel;
    }

    public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
      return unidentifiedAccessMode;
    }

    public boolean isForceSmsSelection() {
      return forceSmsSelection;
    }

    public String getUfsrvUid () {
      return ufsrvUidEncoded;
    }

    public long getUfsrvId () {
      return ufsrvId;
    }

    public String getE164Number () {
      return e164Number;
    }

    public String getUsername () {
      return username;
    }

    public long getEid () {
      return eId;
    }

    public List<Address> getAddress () {
      return this.address;
    }

    //
    public String getRecipientId () {
      return ufsrvUidEncoded;
    }

    public boolean isPresenceSharing () {
      return presenceSharing;
    }

    public boolean isPresenceShared () {
      return presenceShared;
    }

    public String getPresenceInformation () {
      return presenceInformation;
    }

    public boolean isReadReceiptSharing () {
      return readReceiptSharing;
    }

    public boolean isReadReceiptShared () {
      return readReceiptShared;
    }

    public boolean isTypingIndicatorSharing () {
      return typingIndicatorSharing;
    }

    public boolean isTypingIndicatorShared () {
      return typingIndicatorShared;
    }

    public boolean isBlockShared () {
      return blockShared;
    }

    public boolean isContactSharing () {
      return contactSharing;
    }

    public boolean isContactShared () {
      return contactShared;
    }

    public boolean isLocationSharing () {
      return locationSharing;
    }

    public boolean isLocationShared () {
      return locationShared;
    }

    public String getLocationInformation () {
      return locationInformation;
    }

    public Recipient.RecipientType getRecipientType () {
      return recipientType;
    }

    public String getNickname () {
      return nickname;
    }

    public String getAvatarUfsrvId () {
      return avatarUfsrvId;
    }
    //
    public @NonNull GeogroupStickyState getGeogroupStickyState() {
      return sticky;
    }

    public List<String> getPermissionPresentation () {
      return permissionPresentation;
    }

    public List<String> getPermissionMembership () {
      return permissionMembership;
    }

    public List<String> getPermissionMessaging () {
      return permissionMessaging;
    }

    public List<String> getPermissionAttaching () {
      return permissionAttaching;
    }

    public List<String> getPermissionCalling () {
      return permissionCalling;
    }

    public EnumPermissionListSemantics getPermSemanticsPresentation () {
      return permSemanticsPresentation;
    }

    public EnumPermissionListSemantics getPermSemanticsMembership () {
      return permSemanticsMembership;
    }

    public EnumPermissionListSemantics getPermSemanticsMessaging () {
      return permSemanticsMessaging;
    }

    public EnumPermissionListSemantics getPermSemanticsAttaching () {
      return permSemanticsAttaching;
    }

    public EnumPermissionListSemantics getPermSemanticsCalling () {
      return permSemanticsCalling;
    }
  }


  public static class RecipientReader implements Closeable {

    private final Context context;
    private final Cursor  cursor;

    RecipientReader(Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      return Recipient.from(context, Address.fromSerialized(serialized), false);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close() {
      cursor.close();
    }
  }

  private static class PendingContactInfo {

    private final String displayName;
    private final String photoUri;
    private final String phoneLabel;
    private final String contactUri;

    private PendingContactInfo(String displayName, String photoUri, String phoneLabel, String contactUri) {
      this.displayName = displayName;
      this.photoUri    = photoUri;
      this.phoneLabel  = phoneLabel;
      this.contactUri  = contactUri;
    }
  }

  //
  public static class StickyGeogroupsReader {

    private final Context context;
    private final Cursor cursor;

    StickyGeogroupsReader(Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      return Recipient.from(context, Address.fromSerialized(serialized), false);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }
  }

  public static class PresenceSharedReader
  {
    private final Context context;
    private final Cursor cursor;

    PresenceSharedReader (Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      return Recipient.from(context, Address.fromSerialized(serialized), false);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close ()
    {
      if (cursor!=null) {
        cursor.close();
      }
    }
  }

  public static class LocationSharedReader
  {
    private final Context context;
    private final Cursor cursor;

    LocationSharedReader (Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      String serialized = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
      return Recipient.from(context, Address.fromSerialized(serialized), false);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close ()
    {
      if (cursor!=null) {
        cursor.close();
      }
    }
  }

  private List<String>GetCurrentMembers (@NonNull Recipient groupRecipient, EnumFencePermissions permission)
  {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, new String[] {permissionsDbColumnHashMap.get(permission)}, ADDRESS + " = ?",
                              new String[]{groupRecipient.getAddress().serialize()},
                              null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        return Util.split(cursor.getString(cursor.getColumnIndexOrThrow(permissionsDbColumnHashMap.get(permission))), ",");
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //default is blacklisting (open permission except for blacklisted), this's referred to as whitelisting semantic
  //logic is little inverted...
  public boolean isUserPermitted (List<String>members, EnumPermissionListSemantics semantics, long userid)
  {
    switch (semantics)
    {
      //user has to be on the list to be permitted
      case WHITELIST:
        if (members.contains(String.valueOf(userid))) return true;
        else return false;

      //default: this semantic is open except for those on the (black)list
      case BLACKLIST:
        if (members.contains(String.valueOf(userid))) return false;
        break;
    }

    return true;
  }

  public @NonNull List<Recipient>
  getMembersWithPermission(@NonNull Context context, @NonNull Recipient groupRecipient, EnumFencePermissions permission, boolean ignoreSelf) {
    long          myUid = TextSecurePreferences.getUserId(context);
    List<String> members     = GetCurrentMembers(groupRecipient, permission);//members saved in one column  'uid,uid,uid...'
    List<Recipient> recipientsMembers  = new LinkedList<>();

    for (String member : members) {
      if (member.equals(String.valueOf(myUid)) && ignoreSelf) {
        Log.d(TAG, String.format("getMembersWithPermission: found myself in the recipients list and 'ignoreSelf' flag is 'true'. Size:'%d'", members.size()));
        continue;
      }
      recipientsMembers.addAll(Recipient.listFromUfsrvIds(context, member, false));
    }

    return recipientsMembers;
  }


  public boolean isUserOnGroupPermissionsList (@NonNull Recipient recipient, EnumFencePermissions permission, long uid)
  {
    List<String> members     = GetCurrentMembers(recipient, permission);//members saved in one column  'uid,uid,uid...'

    boolean alreadyIncluded=false;

    for (String member : members) {
      if (member.equals(String.valueOf(uid))) {
        Log.d(TAG, String.format("SetGroupPermissionForMember (uid:'%d': User already in permissions list. Size:'%d'", uid, members.size()));
        alreadyIncluded=true;
        break;
      }
    }

    return alreadyIncluded;
  }

  public void setGroupPermissions (@NonNull Recipient recipient, GroupPermission groupPermission) {
    ContentValues values = new ContentValues(2);
    values.put(permissionsDbColumnHashMap.get(groupPermission.getPermission())+"_list_semantics", String.valueOf(groupPermission.getListSemantics().getValue()));
    values.put(permissionsDbColumnHashMap.get(groupPermission.getPermission())+"_baselist", String.valueOf(groupPermission.getBaseList().getValue()));
    updateOrInsert(recipient.getAddress(), values);
  }

  public void setListSemantics (@NonNull Recipient recipient, GroupPermission groupPermission) {
    ContentValues values = new ContentValues(1);
    values.put(permissionsDbColumnHashMap.get(groupPermission.getPermission())+"_list_semantics", String.valueOf(groupPermission.getListSemantics().getValue()));
    updateOrInsert(recipient.getAddress(), values);
  }

  public void
  SetGroupPermissionForMembers (@NonNull Context context, @NonNull Recipient recipient, EnumFencePermissions permission, List<Address>members)
  {
    ContentValues values = new ContentValues();
    values.put(permissionsDbColumnHashMap.get(permission), Address.toSerializedList(members, ','));
    updateOrInsert(recipient.getAddress(), values);
  }

  public long
  SetGroupPermissionForMember (@NonNull Context context, @NonNull Recipient recipient, EnumFencePermissions permission, long uid)
  {
//    if (isUserInGroupPermissionsList(recipient, permission, uid))  return 0;
    List<String> members     = GetCurrentMembers(recipient, permission);//members saved in one column  'uid,uid,uid...'

    boolean alreadyIncluded=false;

    for (String member : members) {
      if (member.equals(String.valueOf(uid))) {
        Log.d(TAG, String.format("SetGroupPermissionForMember (uid:'%d': User already in permissions list. Size:'%d'", uid, members.size()));
        alreadyIncluded=true;
        break;
      }
    }

    if (alreadyIncluded)  return 0;

    members.add(String.valueOf(uid));

    ContentValues values = new ContentValues();
    values.put(permissionsDbColumnHashMap.get(permission), Util.join(members, ","));
    updateOrInsert(recipient.getAddress(), values);

    return uid;
  }

  public long
  DeleteGroupPermissionForMember (@NonNull Context context, @NonNull Recipient recipient, EnumFencePermissions permission, long uid)
  {
    List<String> membersExisting      = GetCurrentMembers(recipient, permission);//members saved in one column  'uid,uid,uid...'
    List<String> membersNew           = new LinkedList<>();

    boolean alreadyIncluded=false;

    for (String member : membersExisting) {
      if (member.equals(String.valueOf(uid))) {
        alreadyIncluded=true;
        continue;
      }
      else  membersNew.add(member);
    }

    if (!alreadyIncluded) {
      Log.d(TAG, String.format("DeleteGroupPermissionForMember (uid:'%d': User NOT in permissions list. Size:'%d'", uid, membersExisting.size()));
      membersNew.clear();
      return 0;
    }

    ContentValues values = new ContentValues();
    values.put(permissionsDbColumnHashMap.get(permission), Util.join(membersNew, ","));
    updateOrInsert(recipient.getAddress(), values);

    return uid;
  }
  //
}