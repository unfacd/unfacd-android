package org.thoughtcrime.securesms.database;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.fence.FencePermissions;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.PairedGroupName;

import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.Member;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.EnabledState;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.BadGroupIdException;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupV1MessageProcessor;
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor;
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.signal.core.util.CursorUtil;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.signal.core.util.SqlUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupChangeReconstruct;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.DistributionId;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import static org.thoughtcrime.securesms.groups.GroupV1MessageProcessor.UserRecordsToEncodedUfsrvUidList;

@SuppressLint("NewApi")
public class GroupDatabase extends Database {

  public static final String DATABASE_UPDATE_ACTION = "org.thoughtcrime.securesms.database.GroupDatabase.UPDATE";

  private static final String TAG = Log.tag(GroupDatabase.class);
  static final String TABLE_NAME                    = "groups";
  private static final String ID                    = "_id";
  static final String GROUP_ID                      = "group_id";
  public  static final String RECIPIENT_ID          = "recipient_id";
  private static final String TITLE                 = "title";
  static final String MEMBERS                       = "members";
  private static final String AVATAR_ID             = "avatar_id";
  private static final String AVATAR_KEY            = "avatar_key";
  private static final String AVATAR_CONTENT_TYPE   = "avatar_content_type";
  private static final String AVATAR_RELAY          = "avatar_relay";
  private static final String AVATAR_DIGEST         = "avatar_digest";
  private static final String TIMESTAMP             = "timestamp";
  static final String ACTIVE                        = "active";
  static final String MMS                           = "mms";
  private static final String EXPECTED_V2_ID        = "expected_v2_id";
  private static final String UNMIGRATED_V1_MEMBERS = "former_v1_members";
  private static final String DISTRIBUTION_ID       = "distribution_id";
  private static final String DISPLAY_AS_STORY      = "display_as_story";


  /* V2 Group columns */
  /** 32 bytes serialized {@link GroupMasterKey} */
  public  static final String V2_MASTER_KEY       = "master_key";
  /** Increments with every change to the group */
  private static final String V2_REVISION         = "revision";
  /** Serialized {@link DecryptedGroup} protobuf */
  public static final String V2_DECRYPTED_GROUP  = "decrypted_group";

  //AA+
  private static final String GROUP_DATA          = "group_data";
  private static final String CNAME               = "cname";
  private static final String LONGITUDE           = "longitude";
  private static final String LATITUDE            = "latitude";
  private static final String MAXMEMBERS          = "maxmembers";
  private static final String TTL                 = "ttl";
  private static final String MODE                = "mode"; //should renamned to status
  private static final String FID                 = "fid";
  private static final String INVITED_MEMBERS     = "invited_members";
  private static final String BLOCKED_MEMBERS     = "blocked_members";
  private static final String REQUESTING_MEMBERS  = "requesting_members";
  public static final String AVATAR_UFID          = "avatar_ufid";
  private static final String FENCE_TYPE          = "fence_type";
  private static final String OWNER_UID           = "owner_uid";
  private static final String PRIVACY_MODE        = "privacy_mode";
  private static final String DELIVERY_MODE       = "delivery_mode";
  private static final String JOIN_MODE           = "join_mode";
  private static final String INVITED_BY          = "invited_by";

  //these mode captures existential lifecycle events only. for example join syncs dont qualify
  public static final int    GROUP_MODE_DEVICELOCAL               = 0;  //strictly local to device. backend doesnt know of

  public static final int    GROUP_MODE_INVITATION                = 1;  //received an invitation to join. Not acknowledged
  public static final int    GROUP_MODE_GEOBASED_INVITE           = 2;  //invitation based on geo location for this user
  public static final int    GROUP_MODE_UNINVITATED               = 3;  //removed from previously invited-to group

  public static final int    GROUP_MODE_JOIN_ACCEPTED             = 10; //server accepted join request (self-initiated) for this user
  public static final int    GROUP_MODE_INVITATION_JOIN_ACCEPTED  = 11; //server accepted request to join an invitation group for this user
  public static final int    GROUP_MODE_GEOBASED_JOIN             = 12; //server sent automatic join based on Geogroup
  public static final int    GROUP_MODE_JOIN_SYNCED               = 13; //waiting for join sync info from server (having previously issued join to sync view with server)
  public static final int    GROUP_MODE_MAKE_NOT_CONFIRMED        = 14; //waiting for new group creation confirmation
  public static final int    GROUP_MODE_INVITATION_REJECTED       = 15; //server accepted invitation rejected OR this user deleted
  public static final int    GROUP_MODE_BLOCKED                   = 16; //user blocked whole group

  public static final int    GROUP_MODE_LEAVE_ACCEPTED              = 20; //this user left
  public static final int    GROUP_MODE_LEAVE_GEO_BASED             = 21; //automatic leave based on geogroup roaming mode
  public static final int    GROUP_MODE_LEAVE_NOT_CONFIRMED         = 22;
  public static final int    GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP = 23; //User requested group storage cleanup upon successful delete
  public static final int    GROUP_MODE_LEAVE_REJECTED              = 24; //leave request rejected by server. Maybe able to retrieve more specific reason by looking at args_err field of fence command's header

  public static final int    GROUP_MODE_GUARDIAN                  = 30; //User requested group storage cleanup upon successful delete

  public static final int    GROUP_MODE_LINKJOIN_REQUESTING       = 40;//user has requested t o join via link but not yet been accepted
  public static final int    GROUP_MODE_LINKJOIN_ACCEPTED         = 41; //User is authorised to join group
  public static final int    GROUP_MODE_LINKJOIN_REJECTED         = 42; //User is not authorised to join group

  public static final char DELIMITER = ',';

  //align with enum FenceType in protobuf
//  enum GroupType
//  {
//    GROUP_TYPE_GEO(1), //geobased basefence
//    public static final int GROUP_TYPE_USER = 2; //user create
//    public static final int GROUP_TYPE_USER_PRIVATE = 3; //
//    public static final int GROUP_TYPE_USER_BROADCAST = 4; //
//  }

  public enum GroupType {
    UNKNOWN(0),
    GEO(1), //geobased basefence
    USER (2),
    GUARDIAN(3); //user create

    private int value;

    GroupType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public enum PrivacyMode {
    PUBLIC(0),//default
    PRIVATE(1); //

    private int value;

    PrivacyMode(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public enum DeliveryMode {
    MANY(0),//default
    BROADCAST(1), //
    BROADCAST_ONEWAY(2); //

    private int value;

    DeliveryMode(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public String getDescriptiveName() {
      switch (this) {
        case MANY:
          return "Many-to-Many";
        case BROADCAST:
          return "Broadcast";
        case BROADCAST_ONEWAY:
          return "Oneway Broadcast";
        default:
          throw new AssertionError("Unknown type " + this);
      }
    }
  }

  public enum JoinMode {
    OPEN(0),//default
    INVITE(1), //
    OPEN_WITH_KEY(2), //
    INVITE_WITH_KEY(3);

    private int value;

    JoinMode(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  protected static final String GROUP_ID_WHERE              = "group_id = ?";
  //

  public enum MembershipUpdateMode {
    ADD_MEMBER,
    REMOVE_MEMBER
  }

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME +
          " (" + ID + " INTEGER PRIMARY KEY, " +
          GROUP_ID + " TEXT UNIQUE, " + //AA+ unique
          RECIPIENT_ID + " INTEGER, " +
          TITLE + " TEXT, " +
          MEMBERS + " TEXT, " +
          AVATAR_ID + " INTEGER, " +
          AVATAR_KEY + " BLOB, " +
          AVATAR_CONTENT_TYPE + " TEXT, " +
          AVATAR_RELAY + " TEXT, " +
          AVATAR_DIGEST + " BLOB, " +
          TIMESTAMP + " INTEGER, " +
          ACTIVE + " INTEGER DEFAULT 1, "+
          MMS + " INTEGER DEFAULT 0, " +
          V2_MASTER_KEY + " BLOB, " +
          V2_REVISION + " BLOB, " +
          V2_DECRYPTED_GROUP + " BLOB, " +
          DISTRIBUTION_ID + " TEXT DEFAULT NULL, " +
          DISPLAY_AS_STORY + " INTEGER DEFAULT 0, " +

          GROUP_DATA + " JSON, " +
          CNAME + " TEXT NOT NULL UNIQUE, " + //AA+
          LONGITUDE + " DOUBLE, " +
          LATITUDE + " DOUBLE, " +
          MAXMEMBERS + " INTEGER, " +
          TTL + " INTEGER, " +
          MODE + " INTEGER, " +
          FID + " INTEGER, " +
          INVITED_MEMBERS + " TEXT, "+
          BLOCKED_MEMBERS + " TEXT, "+
          REQUESTING_MEMBERS + " TEXT, "+
          AVATAR_UFID  + " TEXT, "  + // ufsr uses string, not integer id
          FENCE_TYPE  + " INTEGER DEFAULT 2, "+    //AA 2 is user fence
          OWNER_UID  + " INTEGER DEFAULT 0, "+
          PRIVACY_MODE  + " INTEGER DEFAULT 0, "+ //0 public
          DELIVERY_MODE  + " INTEGER DEFAULT 0, "+ //0 many
          JOIN_MODE  + " INTEGER DEFAULT 0, "+//0 OPEN
          INVITED_BY + " TEXT " +
          ");";

  public static final String[] CREATE_INDEXS = {
      "CREATE UNIQUE INDEX IF NOT EXISTS group_id_index ON " + TABLE_NAME + " (" + GROUP_ID + ");",
      "CREATE UNIQUE INDEX IF NOT EXISTS group_recipient_id_index ON " + TABLE_NAME + " (" + RECIPIENT_ID + ");",
      "CREATE UNIQUE INDEX IF NOT EXISTS group_distribution_id_index ON " + TABLE_NAME + "(" + DISTRIBUTION_ID + ");",
      "CREATE INDEX IF NOT EXISTS group_fid_index ON " + TABLE_NAME + " (" + FID + ");", //cannot be uniqueue as we can have several fid =0
      "CREATE UNIQUE INDEX IF NOT EXISTS group_cname_index ON " + TABLE_NAME + " (" + CNAME + ");",
  };

  private static final String[] GROUP_PROJECTION = {
          GROUP_ID,  RECIPIENT_ID, TITLE, MEMBERS, AVATAR_ID, AVATAR_KEY, AVATAR_CONTENT_TYPE, AVATAR_RELAY, AVATAR_DIGEST,
          TIMESTAMP, ACTIVE, MMS, V2_MASTER_KEY, V2_REVISION, V2_DECRYPTED_GROUP,
          //AA+
          GROUP_DATA,
          CNAME,
          LONGITUDE,
          LATITUDE,
          MAXMEMBERS,
          TTL,
          MODE,
          FID,
          INVITED_MEMBERS,
          BLOCKED_MEMBERS,
          REQUESTING_MEMBERS,
          AVATAR_UFID,
          FENCE_TYPE,
          OWNER_UID,
          PRIVACY_MODE,
          DELIVERY_MODE,
          JOIN_MODE,
          INVITED_BY
  };

  static final List<String> TYPED_GROUP_PROJECTION = Stream.of(GROUP_PROJECTION).map(columnName -> TABLE_NAME + "." + columnName).toList();

  public GroupDatabase(Context context, SignalDatabase databaseHelper) {
    super(context, databaseHelper);
  }

  public @Nullable GroupRecord getGroupByGroupId(@NonNull GroupId groupId) {
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                     new String[] {groupId.toString()},
                                                                     null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();
    return record;
  }

  //AA+
  public @Nullable GroupRecord getGroupByCname(String fcname)
  {
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, CNAME + " = ?",
                                                                     new String[] {fcname},
                                                                     null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();

    if (record == null) Log.d(TAG, "getGroupByCname: record was null for: "+fcname);

    return record;
  }

  //AA+
  public boolean
  cleanUpGroup(@Nullable GroupId groupId, long threadId)
  {
    databaseHelper.getSignalReadableDatabase().beginTransaction();
    try {
      if (threadId > 0) SignalDatabase.threads().deleteConversation(threadId);
      if (groupId != null)  deleteGroup(groupId, false); //AA in some instances we'd have an orphan group only reference in thread
      databaseHelper.getSignalReadableDatabase().setTransactionSuccessful();
    } catch (Exception e) {
      databaseHelper.getSignalReadableDatabase().endTransaction();
      Log.d(TAG, e.getMessage());
      return false;
    }

    //success
    databaseHelper.getSignalReadableDatabase().endTransaction();

    notifyConversationListListeners();

    return true;
  }

  public boolean
  isGroupAvailableByCname(final String fcname, boolean flag_cleanup)
  {
    boolean isGroupExists = false;
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, CNAME + " = ? COLLATE NOCASE",
                                                                     new String[] {fcname}, null, null, null);
    try {
      if (cursor == null || !cursor.moveToNext()) {
        return isGroupExists;
      }

      String  groupId   = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID));
      boolean isActive  = cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1;
      long    fid       = cursor.getLong(cursor.getColumnIndexOrThrow(FID));
      int     mode      = cursor.getInt(cursor.getColumnIndexOrThrow(MODE));

      Recipient  recipient   = Recipient.live(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID)))).get();
      long        threadId   = SignalDatabase.threads().getThreadIdIfExistsFor(recipient.getId());

      //todo:slightly heavy handed: use some of he fetchd attributes to refine what needs to be done
      if (fid == 0|| !isActive || threadId == -1) {
        if (flag_cleanup) return cleanUpGroup(GroupId.parseOrThrow(groupId), threadId);
      }

      isGroupExists = true;
    } finally {
      if (cursor != null) cursor.close();
    }

    return isGroupExists;
  }

  //AA+
  public @Nullable GroupRecord getGroupRecordByFid(long fid)
  {
    if (fid <= 0) return null;

    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, FID + " = ?",
                                                                     new String[] {String.valueOf(fid)},
                                                                     null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();

    if (record == null) Log.e(TAG, "getGroupRecordByFid: ERROR: DATA INTEGRITY: Group Record was null for: " + fid);

    return record;
  }

  public Optional<GroupRecord> getGroup(RecipientId recipientId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, RECIPIENT_ID + " = ?", new String[] {recipientId.serialize()}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return getGroup(cursor);
      }

      return Optional.empty();
    }
  }

  /*public Optional<GroupRecord> getGroup(@NonNull GroupId groupId) {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    try (Cursor cursor = db.query(TABLE_NAME, null, GROUP_ID + " = ?", SqlUtil.buildArgs(groupId.toString()), null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        Optional<GroupRecord> groupRecord = getGroup(cursor);

        if (groupRecord.isPresent() && RemappedRecords.getInstance().areAnyRemapped(groupRecord.get().getMembersRecipientId())) {
          String remaps = RemappedRecords.getInstance().buildRemapDescription(groupRecord.get().getMembersRecipientId());
          Log.w(TAG, "Found a group with remapped recipients in it's membership list! Updating the list. GroupId: " + groupId + ", Remaps: " + remaps, true);

          Collection<RecipientId> remapped = RemappedRecords.getInstance().remap(groupRecord.get().getMembersRecipientId());

          ContentValues values = new ContentValues();
          values.put(MEMBERS, RecipientId.toSerializedList(remapped));

          if (db.update(TABLE_NAME, values, GROUP_ID + " = ?", SqlUtil.buildArgs(groupId)) > 0) {
            return getGroup(groupId);
          } else {
            throw new IllegalStateException("Failed to update group with remapped recipients!");
          }
        }

        return getGroup(cursor);
      }

      return Optional.empty();
    }
  }*/

  public Optional<GroupRecord> getGroup(@NonNull GroupId groupId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                          new String[] {groupId.toString()},
                                                                          null, null, null))
    {
      if (cursor != null && cursor.moveToNext()) {
        return getGroup(cursor);
      }

      return Optional.empty();
    }
  }

  public Optional<RecipientId> getGroupRecipientId(@NonNull GroupId groupId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[]{RECIPIENT_ID}, GROUP_ID + " = ?", new String[] {groupId.toString()}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        RecipientId recipientId = RecipientId.from(Long.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_ID))));
        cursor.close();

        return Optional.of(recipientId);
      }

      return Optional.empty();
    }
  }

  public Optional<Long> getGroupFid(@NonNull GroupId groupId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[]{FID}, GROUP_ID + " = ?", new String[] {groupId.toString()}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        Long groupFid = Long.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(FID)));
        cursor.close();

        return Optional.of(groupFid);
      }

      return Optional.empty();
    }
  }

  public boolean groupExists(@NonNull GroupId groupId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                          new String[] {groupId.toString()},
                                                                          null, null, null))
    {
      boolean isGroupExists = cursor.moveToNext();
      cursor.close();

      return isGroupExists;
    }
  }

  /**
   * @return A gv1 group whose expected v2 ID matches the one provided.
   */
  public Optional<GroupRecord> getGroupV1ByExpectedV2(@NonNull GroupId.V2 gv2Id) {
    return getGroup(gv2Id);//AA+

   /* SQLiteDatabase db = databaseHelper.getReadableDatabase();
    try (Cursor cursor = db.query(TABLE_NAME, GROUP_PROJECTION, EXPECTED_V2_ID + " = ?", SqlUtil.buildArgs(gv2Id), null, null, null)) {
      if (cursor.moveToFirst()) {
        return getGroup(cursor);
      } else {
        return Optional.empty();
      }
    }*/
  }

  public Optional<GroupRecord> getGroupByDistributionId(@NonNull DistributionId distributionId) {
    SQLiteDatabase db    = databaseHelper.getSignalReadableDatabase();
    String         query = DISTRIBUTION_ID + " = ?";
    String[]       args  = SqlUtil.buildArgs(distributionId);

    try (Cursor cursor = db.query(TABLE_NAME, null, query, args, null, null, null)) {
      if (cursor.moveToFirst()) {
        return getGroup(cursor);
      } else {
        return Optional.empty();
      }
    }
  }

  Optional<GroupRecord> getGroup(Cursor cursor) {
    Reader reader = new Reader(cursor);
    GroupRecord groupRecord = reader.getCurrent();
    reader.close();

    return Optional.ofNullable(groupRecord);
  }

  /**
   * @return local db group revision or -1 if not present.
   */
  public int getGroupV2Revision(@NonNull GroupId.V2 groupId) {
    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                          new String[] {groupId.toString()},
                                                                          null, null, null))
    {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(V2_REVISION));
      }

      return -1;
    }
  }

  /**
   * Call if you are sure this group should exist.
   * <p>
   * Finds group and throws if it cannot.
   */
  public @NonNull GroupRecord requireGroup(@NonNull GroupId groupId) {
    Optional<GroupRecord> group = getGroup(groupId);

    if (!group.isPresent()) {
      throw new AssertionError("Group not found");
    }

    return group.get();
  }

  public boolean isUnknownGroup(@NonNull GroupId groupId) {
//    return !getGroup(groupId).isPresent();
    Optional<GroupRecord> group = getGroup(groupId);

    if (!group.isPresent()) {
      return true;
    }

    boolean noMetadata = !group.get().hasAvatar() && TextUtils.isEmpty(group.get().getTitle());
    boolean noMembers  = group.get().getMembers().isEmpty() || (group.get().getMembers().size() == 1 && group.get().getMembers().contains(Recipient.self().getId()));

    return noMetadata && noMembers;
  }

  public Reader getGroupsFilteredByTitle(String constraint, boolean includeInactive, boolean excludeMms) {
    String   query;
    String[] queryArgs;

    if (includeInactive) {
      query     = TITLE + " LIKE ? AND (" + ACTIVE + " = ? OR " + RECIPIENT_ID + " IN (SELECT " + ThreadDatabase.RECIPIENT_ID + " FROM " + ThreadDatabase.TABLE_NAME + "))";
      queryArgs = new String[]{"%" + constraint + "%", "1"};
    } else {
      query     = TITLE + " LIKE ? AND " + ACTIVE + " = ?";
      queryArgs = new String[]{"%" + constraint + "%", "1"};
    }

    if (excludeMms) {
      query += " AND " + MMS + " = 0";
    }

    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, query, queryArgs, null, null, TITLE + " COLLATE NOCASE ASC");

    return new Reader(cursor);
  }

  public @NonNull DistributionId getOrCreateDistributionId(@NonNull GroupId.V2 groupId) {
    SQLiteDatabase db    = databaseHelper.getSignalReadableDatabase();
    String         query = GROUP_ID + " = ?";
    String[]       args  = SqlUtil.buildArgs(groupId);

    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] { DISTRIBUTION_ID }, query, args, null, null, null)) {
      if (cursor.moveToFirst()) {
        Optional<String> serialized = CursorUtil.getString(cursor, DISTRIBUTION_ID);

        if (serialized.isPresent()) {
          return DistributionId.from(serialized.get());
        } else {
          Log.w(TAG, "Missing distributionId! Creating one.");

          DistributionId distributionId = DistributionId.create();

          ContentValues values = new ContentValues(1);
          values.put(DISTRIBUTION_ID, distributionId.toString());

          int count = db.update(TABLE_NAME, values, query, args);
          if (count < 1) {
            throw new IllegalStateException("Tried to create a distributionId for " + groupId + ", but it doesn't exist!");
          }

          return distributionId;
        }
      } else {
        throw new IllegalStateException("Group " + groupId + " doesn't exist!");
      }
    }
  }

  public GroupId.Mms getOrCreateMmsGroupForMembers(List<RecipientId> members) {
    Collections.sort(members);

    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {GROUP_ID},
                                                                     MEMBERS + " = ? AND " + MMS + " = ?",
                                                                     new String[] {RecipientId.toSerializedList(members), "1"},
                                                                     null, null, null);
    try {
      if (cursor != null && cursor.moveToNext()) {
        return GroupId.parseOrThrow(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)))
                .requireMms();
      } else {
        GroupId.Mms groupId = GroupId.createMms(new SecureRandom());
        create(groupId, null, members);
        return groupId;
      }
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  //AA- dont use ; see ufsrvuid version below
/*  @WorkerThread
  public List<String> getPushGroupNamesContainingMember(@NonNull RecipientId recipientId) {
    return Stream.of(getPushGroupsContainingMember(recipientId))
            .map(groupRecord -> Recipient.resolved(groupRecord.getRecipientId()).getDisplayName(context))
            .toList();
  }

  @WorkerThread
  public @NonNull List<GroupRecord> getPushGroupsContainingMember(@NonNull RecipientId recipientId) {
    SQLiteDatabase database   = databaseHelper.getReadableDatabase();
    String         table      = TABLE_NAME + " INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + RECIPIENT_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID;
    String         query      = MEMBERS + " LIKE ? AND " + MMS + " = ?";
    String[]       args       = new String[]{"%" + recipientId.serialize() + "%", "0"};
    String         orderBy    = ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " DESC";

    List<GroupRecord> groups = new LinkedList<>();

    try (Cursor cursor = database.query(table, null, query, args, null, null, orderBy)) {
      while (cursor != null && cursor.moveToNext()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));

        if (RecipientId.serializedListContains(serializedMembers, recipientId)) {
          groups.add(new Reader(cursor).getCurrent());
        }
      }
    }

    return groups;
  }*/

  //AA+
  @WorkerThread
  public List<String> getPushGroupNamesContainingMember(String ufsrvUid) {
    return Stream.of(getPushGroupsContainingMember(ufsrvUid))
            .map(groupRecord -> Recipient.resolved(groupRecord.getRecipientId()).getDisplayName(context))
            .toList();
  }

  @WorkerThread
  public List<GroupRecord> getPushGroupsContainingMember(String ufsrvUid) {
    return getPushGroupsContainingMember(ufsrvUid, true);
  }

  //AA+ replaces implementation using RecientId in members
  public @NonNull List<GroupRecord> getPushGroupsContainingMember(String ufsrvUid , boolean pushOnly) {
    return getGroupsContainingMember(ufsrvUid, pushOnly, false);
  }

  @WorkerThread
  public @NonNull List<GroupRecord> getGroupsContainingMember(String ufsrvUid , boolean pushOnly, boolean includeInactive) {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String         table      = TABLE_NAME + " INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + RECIPIENT_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID;
    String         query      = MEMBERS + " LIKE ?";
    String[]       args       = SqlUtil.buildArgs("%" + ufsrvUid + "%");
    String         orderBy    = ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " DESC";

    if (pushOnly) {
      query += " AND " + MMS + " = ?";
      args = SqlUtil.appendArg(args, "0");
    }

    if (!includeInactive) {
      query += " AND " + ACTIVE + " = ?";
      args = SqlUtil.appendArg(args, "1");
    }

    List<GroupRecord> groups = new LinkedList<>();

    try (Cursor cursor = database.query(table, null, query, args, null, null, orderBy)) {
      while (cursor != null && cursor.moveToNext()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));

        if (UfsrvUid.serializedListContains(serializedMembers, ufsrvUid)) {
          groups.add(new Reader(cursor).getCurrent());
        }
      }
    }

    return groups;
  }

  //AA- use Recipient
 /* @WorkerThread
  public @NonNull List<GroupRecord> getPushGroupsContainingMember(@NonNull RecipientId recipientId) {
    return getGroupsContainingMember(recipientId, true);
  }

  public @NonNull List<GroupRecord> getGroupsContainingMember(@NonNull RecipientId recipientId, boolean pushOnly) {
    return getGroupsContainingMember(recipientId, pushOnly, false);
  }*/

  //AA see reimplementation above with ufsrvuid
  @WorkerThread
  public @NonNull List<GroupRecord> getGroupsContainingMember(@NonNull RecipientId recipientId, boolean pushOnly, boolean includeInactive) {
    SQLiteDatabase database   = databaseHelper.getSignalReadableDatabase();
    String         table      = TABLE_NAME + " INNER JOIN " + ThreadDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + RECIPIENT_ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID;
    String         query      = MEMBERS + " LIKE ?";
    String[]       args       = SqlUtil.buildArgs("%" + recipientId.serialize() + "%");
    String         orderBy    = ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " DESC";

    if (pushOnly) {
      query += " AND " + MMS + " = ?";
      args = SqlUtil.appendArg(args, "0");
    }

    if (!includeInactive) {
      query += " AND " + ACTIVE + " = ?";
      args = SqlUtil.appendArg(args, "1");
    }

    List<GroupRecord> groups = new LinkedList<>();

    try (Cursor cursor = database.query(table, null, query, args, null, null, orderBy)) {
      while (cursor != null && cursor.moveToNext()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));

        if (RecipientId.serializedListContains(serializedMembers, recipientId)) {
          groups.add(new Reader(cursor).getCurrent());
        }
      }
    }

    return groups;
  }

  public Reader getGroups() {
    @SuppressLint("Recycle")
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, null, null, null, null, null);
    return new Reader(cursor);
  }

  //AA+
  public Reader getActiveGroups() {
    @SuppressLint("Recycle")
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, ACTIVE + " = 1", null, null, null, null);
    return new Reader(cursor);
  }

  public int getActiveGroupCount() {
    SQLiteDatabase db    = databaseHelper.getSignalReadableDatabase();
    String[]       cols  = { "COUNT(*)" };
    String         query = ACTIVE + " = 1";

    try (Cursor cursor = db.query(TABLE_NAME, cols, query, null, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int groupCount = cursor.getInt(0);
        cursor.close();

        return groupCount;
      }
    }

    return 0;
  }

  //todo to be deleted see below
//  public @NonNull List<Recipient> getGroupMembers(@NonNull GroupId groupId, boolean ignoreSelf) {
//    return getGroupMembers(getCurrentMembersByAddress(groupId), ignoreSelf);
//  }

  //AA+ modified to use locally addressable
  @WorkerThread
  public @NonNull List<Recipient> getGroupMembers(@NonNull GroupId groupId, @NonNull MemberSet memberSet) {
    if (false) {//groupId.isV2()) {//AA-
      return getGroup(groupId).map(g -> g.requireV2GroupProperties().getMemberRecipients(memberSet))
              .orElse(Collections.emptyList());
    } else {
      List<LocallyAddressableUfsrvUid> currentMembers = getCurrentMembersByUfsrvuid(groupId);
      List<Recipient>   recipients     = new ArrayList<>(currentMembers.size());

      for (LocallyAddressableUfsrvUid member : currentMembers) {
        Recipient resolved = Recipient.resolved(member);
        if (memberSet.includeSelf || !resolved.isSelf()) {
          recipients.add(resolved);
        }
      }

      return recipients;
    }
  }

  public @NonNull List<Recipient> getGroupMembers(long fid, boolean ignoreSelf) {
    return getGroupMembers(getCurrentMembers(fid), ignoreSelf);
  }

  public @NonNull List<Recipient> getGroupMembers(List<Address> members, boolean ignoreSelf) {
    List<Recipient> recipients  = new LinkedList<>();

    for (Address member : members) {
      if (!TextUtils.isEmpty(member.toString()) && ignoreSelf && Util.isOwnNumber(context, member.serialize())) continue; //AA+ isEmpty
      recipients.add(Recipient.live(member.serialize()).get());
    }

    return recipients;
  }
  //

  //AA+
  public Recipient getGroupRecipient(long fid)
  {
    return getGroupRecipient(fid, false);
  }

  public Recipient getGroupRecipient(long fid, boolean isSynchronous)
  {
    GroupId groupId = getGroupId(fid, null, false);
    if (groupId != null) {
      Recipient groupRecipient = Recipient.live(RecipientUfsrvId.from(fid), isSynchronous).get();

      return groupRecipient;
    }

    Log.e(TAG, String.format(Locale.getDefault(), "getGroupRecipient: fid:'%d' RETURNED NULL RECIPIENT", fid));
    return null;
  }

  public @NonNull boolean
  amOnGroupList (List<Address> members) {
    String          localNumber = TextSecurePreferences.getUfsrvUserId(context);
    return Stream.of(members)
                 .filter(number -> number.toString().equals(localNumber))
                 .count() > 0;
  }

  //AA+
  public @NonNull List<Recipient> getGroupInvitedRecipientsByNumber(List<Address> members, boolean ignoreSelf) {
    String          localNumber = TextSecurePreferences.getUfsrvUserId(context);
    List<Recipient> recipients  = new LinkedList<>();

    for (Address member : members) {
      if (member.equals(Address.fromSerialized(localNumber)) && ignoreSelf) continue;
      recipients.add(Recipient.live(member.serialize()).get());
    }

    return recipients;
  }

  public @NonNull List<Recipient> getGroupInvitedMembers(@NonNull GroupId groupId, boolean ignoreSelf) {
    return (getGroupInvitedRecipientsByNumber(getInvitedMembers(groupId), ignoreSelf));
  }
  //


  //TBD
  public void create(@NonNull GroupId.V1 groupId,
                     @Nullable String title,
                     @NonNull Collection<RecipientId> members,
                     @Nullable SignalServiceAttachmentPointer avatar,
                     @Nullable String relay)
  {
    create(groupId, title, members, Collections.EMPTY_LIST, avatar, relay, null, null, null);
  }

  //TBD
  public void create(@NonNull GroupId.Mms groupId,
                     @Nullable String title,
                     @NonNull Collection<RecipientId> members)
  {
    create(groupId, Util.isEmpty(title) ? null : title, members, null, null, null, null, null, null);
  }

  public GroupId.V2 create(@NonNull GroupMasterKey groupMasterKey,
                           @NonNull DecryptedGroup groupState,
                           @NonNull GroupDatabase.GroupControlsDescriptor groupControls)//AA+
  {
    return create(groupMasterKey, groupState, false, groupControls);
  }

  public GroupId.V2 create(@NonNull GroupMasterKey groupMasterKey,
                           @NonNull DecryptedGroup groupState,
                           boolean force,
                           @NonNull GroupDatabase.GroupControlsDescriptor groupControls)//AA+
  {
    GroupId.V2 groupId = GroupId.v2(groupMasterKey);

    create(groupId, groupState.getTitle(), Collections.emptyList(), Collections.EMPTY_LIST, null, null, groupMasterKey, groupState, groupControls);//AA+

    return groupId;
  }

  /**
   * There was a point in time where we weren't properly responding to group creates on linked devices. This would result in us having a Recipient entry for the
   * group, but we'd either be missing the group entry, or that entry would be missing a master key. This method fixes this scenario.
   */
  public void fixMissingMasterKey(@NonNull GroupMasterKey groupMasterKey) {
    GroupId.V2 groupId = GroupId.v2(groupMasterKey);

//    if (getGroupV1ByExpectedV2(groupId).isPresent()) {//AA-
//      throw new MissedGroupMigrationInsertException(groupId);
//    }

    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();

    db.beginTransaction();
    try {
      String        query  = GROUP_ID + " = ?";
      String[]      args   = SqlUtil.buildArgs(groupId);
      ContentValues values = new ContentValues();

      values.put(V2_MASTER_KEY, groupMasterKey.serialize());

      int updated = db.update(TABLE_NAME, values, query, args);

      if (updated < 1) {
        Log.w(TAG, "No group entry. Creating restore placeholder for " + groupId);
        create(groupMasterKey, DecryptedGroup.newBuilder()
                                             .setRevision(GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION)
                                             .build(),
               true,
               GroupControlsDescriptor.getDefaultGroupControls());//AA+
      } else {
        Log.w(TAG, "Had a group entry, but it was missing a master key. Updated.");
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    Log.w(TAG, "Scheduling request for latest group info for " + groupId);
    ApplicationDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId));
  }


  /**
   * @param groupMasterKey null for V1, must be non-null for V2 (presence dictates group version).
   */
  private void create(@NonNull GroupId groupId,
                      @Nullable String title,
                      @NonNull Collection<RecipientId> memberCollection,
                      @NonNull Collection<RecipientId> memberCollectionInvited,//AA+
                      @Nullable SignalServiceAttachmentPointer avatar,
                      @Nullable String relay,
                      @Nullable GroupMasterKey groupMasterKey,
                      @Nullable DecryptedGroup groupState,
                      @NonNull GroupDatabase.GroupControlsDescriptor groupControls)//AA+
  {
    RecipientDatabase recipientDatabase = SignalDatabase.recipients();
    RecipientId       groupRecipientId  = recipientDatabase.getOrInsertFromGroupId(groupId);
    List<RecipientId> members           = new ArrayList<>(new HashSet<>(memberCollection));

    Collections.sort(members);

    List<Recipient> membersRecipients = new LinkedList<>(Stream.of(members).map(Recipient::resolved).toList());//AA+

    ContentValues contentValues = new ContentValues();
    contentValues.put(RECIPIENT_ID, groupRecipientId.serialize());
    contentValues.put(GROUP_ID, groupId.toString());
    contentValues.put(TITLE, title);
    contentValues.put(MEMBERS, Recipient.toSerializedUfsrvUidList(membersRecipients));//AA++
    if(memberCollectionInvited != null && memberCollectionInvited.size() > 0) {//AA+
      contentValues.put(INVITED_MEMBERS, Recipient.toSerializedUfsrvUidList(new LinkedList<>(Stream.of(new ArrayList<>(new HashSet<>(memberCollectionInvited))).map(Recipient::resolved).toList())));
    }

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getRemoteId().getV2().get());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orElse(null));
    } else {
      contentValues.put(AVATAR_ID, 0);
    }

    contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, System.currentTimeMillis());
    contentValues.put(DISTRIBUTION_ID, DistributionId.create().toString());
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, groupId.isMms());

    if (groupMasterKey != null) {
      if (groupState == null) {
        throw new AssertionError("V2 master key but no group state");
      }
      groupId.requireV2();
      contentValues.put(V2_MASTER_KEY, groupMasterKey.serialize());
      if (groupState != null) {//AA+ conditional
        contentValues.put(V2_REVISION, groupState.getRevision());
        contentValues.put(V2_DECRYPTED_GROUP, groupState.toByteArray());
        contentValues.put(INVITED_MEMBERS, serializeV2GroupMembersAsRecipients(groupState, true));//AA++
      }
      contentValues.put(MEMBERS, Recipient.self().getUfsrvUid());//AA+
    } else {
      if (groupId.isV2()) {
        throw new AssertionError("V2 group id but no master key");
      }
    }

    //AA+
    if (TextUtils.isEmpty(title)) {
      String cname = String.format(Locale.getDefault(), "%s%d.%s", ufLocation.getInstance().getBaseLocationPrefix(), TextSecurePreferences.getUserId(context), groupId.toString());
      contentValues.put(CNAME, cname);
    } else contentValues.put(CNAME, ufLocation.getInstance().getBaseLocationPrefix() + title);
    contentValues.put(LONGITUDE, 0.0);
    contentValues.put(LATITUDE, 0.0);
    contentValues.put(MAXMEMBERS, groupControls.membersSize);
    contentValues.put(TTL, 0);
    contentValues.put(MODE, GroupDatabase.GROUP_MODE_MAKE_NOT_CONFIRMED);
    contentValues.put(FID, 0);
    contentValues.put(PRIVACY_MODE, groupControls.privacyMode.getValue());
    contentValues.put(DELIVERY_MODE, groupControls.deliveryMode.getValue());
    contentValues.put(JOIN_MODE, groupControls.joinMode.getValue());
    contentValues.put(FENCE_TYPE, groupControls.groupType.getValue());
    //

    long rowId = databaseHelper.getSignalWritableDatabase().insert(TABLE_NAME, null, contentValues);

    if (groupState != null && groupState.hasDisappearingMessagesTimer()) {
      recipientDatabase.setExpireMessages(groupRecipientId, groupState.getDisappearingMessagesTimer().getDuration());
    }

    if (members != null && (groupId.isMms() || Recipient.resolved(groupRecipientId).isProfileSharing())) {//AA+ members
      recipientDatabase.setHasGroupsInCommon(members);
    }

    Recipient.live(groupRecipientId).refresh();

    notifyConversationListListeners();
  }

  public long create(@NonNull GroupId groupId, GroupMasterKey groupMasterKey, String title, long fid, int mode,  @NonNull GroupDatabase.GroupControlsDescriptor groupControls)
  {
    //AA not temporary formation of cname
    return create(groupId, groupMasterKey, title,  Collections.EMPTY_LIST,  Collections.EMPTY_LIST, null, null, groupId + title, 0.0, 0.0, mode, fid, 0, groupControls.groupType, groupControls.privacyMode, groupControls.deliveryMode, groupControls.joinMode);
  }

  //AA+ V1
  public long create(@NonNull GroupId groupId, GroupMasterKey groupMasterKey, String title, @NonNull List<Address> members, @Nullable List<Address> invitedMembers,
                     SignalServiceAttachmentPointer avatar, String relay, String cname, double longitude,
                     double latitude, int mode, long fid, int maxMembers,
                     GroupType groupType, PrivacyMode privacyMode, DeliveryMode deliveryMode, JoinMode joinMode)//AA+ modes
  {
    Collections.sort(members);

    ContentValues contentValues = new ContentValues();
    contentValues.put(RECIPIENT_ID, SignalDatabase.recipients().getOrInsertFromGroupId(groupId).serialize());
    contentValues.put(GROUP_ID, groupId.toString());
    if (groupMasterKey != null) contentValues.put(V2_MASTER_KEY, groupMasterKey.serialize());
    contentValues.put(TITLE, title);
    contentValues.put(MEMBERS, Address.toSerializedList(members, DELIMITER));
    //AA+
    if (invitedMembers != null) {
      contentValues.put(INVITED_MEMBERS, Address.toSerializedList(invitedMembers, DELIMITER));
      Log.d(TAG, String.format(Locale.getDefault(), "create: Creating group id: '%s', group title:'%s', users count:'%d', INVITED:'%s'", groupId, title, members.size(), Address.toSerializedList(invitedMembers, DELIMITER)));
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "create: Creating group id (NO INVITEES): '%s', group title:'%s', users count:'%d'", groupId, title, members.size()));
    }
    //

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getRemoteId().getV2().get());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orElse(null));
    } else {
      contentValues.put(AVATAR_ID, 0);
    }

    contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, System.currentTimeMillis());
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, groupId.isMms());

    //AA+
    contentValues.put(CNAME, cname);
    contentValues.put(LONGITUDE, longitude);
    contentValues.put(LATITUDE, latitude);
    contentValues.put(MAXMEMBERS, maxMembers);
    contentValues.put(TTL, 0);
    contentValues.put(MODE, mode);
    contentValues.put(FID, fid);
    contentValues.put(PRIVACY_MODE, privacyMode.getValue());
    contentValues.put(DELIVERY_MODE, deliveryMode.getValue());
    contentValues.put(JOIN_MODE, joinMode.getValue());
    contentValues.put(FENCE_TYPE, groupType.getValue());
    //

    long id = databaseHelper.getSignalWritableDatabase().insertOrThrow(TABLE_NAME, null, contentValues);//AA+ orThrow

    //replaces above
    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();

    if (id != -1) notifyConversationListListeners();
    return id;
  }


  //AA+
  public long  create(@NonNull GroupId groupId, GroupMasterKey groupMasterKey, String title, List<Address> members,
                     SignalServiceAttachmentPointer avatar, String relay, String cname,
                     double longitude, double latitude, int mode, long fid, int maxMembers,
                     GroupType groupType, PrivacyMode privacyMode, DeliveryMode deliveryMode, JoinMode joinMode)
  {
    return create(groupId, groupMasterKey, title, members, null,
            avatar, relay, cname, longitude, latitude, mode, fid, maxMembers, groupType, privacyMode, deliveryMode, joinMode);
  }

  //AA+ utilises the stable protobuf container for FenceRecord
  public void create(@NonNull GroupId groupId, GroupMasterKey groupMasterKey, FenceRecord fenceRecord, SignalServiceAttachmentPointer avatar, int mode, long timestamp)
  {

    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, groupId.toString());
    contentValues.put(V2_MASTER_KEY, groupMasterKey.serialize());

    RecipientId       groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    contentValues.put(RECIPIENT_ID, groupRecipientId.serialize());
    contentValues.put(TITLE, fenceRecord.getFname());

    List<Address> members = GroupV1MessageProcessor.UserRecordsToAddressList(fenceRecord.getMembersList(), Optional.empty());
    contentValues.put(MEMBERS, Address.toSerializedList(members, DELIMITER));

    List<String>membersInvited  = UserRecordsToEncodedUfsrvUidList(fenceRecord.getInvitedMembersList(), Optional.empty());
    if (membersInvited != null) {
      contentValues.put(INVITED_MEMBERS, Util.join(membersInvited, ","));
      Log.d(TAG, String.format(Locale.getDefault(), "create: Creating group id: '%s', group title:'%s', users count:'%d', INVITED:'%s'", groupId, fenceRecord.getFname(), members.size(), Util.join(membersInvited, ",")));
    } else {
      Log.d(TAG, String.format(Locale.getDefault(), "create: Creating group id (NO INVITEES): '%s', group title:'%s', users count:'%d'", groupId, fenceRecord.getFname(), members.size()));
    }
    //

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getRemoteId().getV2().get());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orElse(null));
    } else {
      contentValues.put(AVATAR_ID, 0);
    }

    contentValues.put(TIMESTAMP, timestamp);
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, groupId.isMms());
    contentValues.put(DISTRIBUTION_ID, DistributionId.create().toString());

    contentValues.put(CNAME, fenceRecord.getCname());
    contentValues.put(LONGITUDE, fenceRecord.getLocation().getLongitude());
    contentValues.put(LATITUDE, fenceRecord.getLocation().getLatitude());
    contentValues.put(MAXMEMBERS, fenceRecord.getMaxmembers());
    contentValues.put(TTL, fenceRecord.getTtl());
    contentValues.put(MODE, mode);
    contentValues.put(FID, fenceRecord.getFid());
    contentValues.put(OWNER_UID, UfsrvUid.DecodeUfsrvSequenceId(fenceRecord.getOwnerUid().toByteArray()));
    contentValues.put(PRIVACY_MODE, fenceRecord.getPrivacyMode().getNumber());
    contentValues.put(DELIVERY_MODE, fenceRecord.getDeliveryMode().getNumber());
    contentValues.put(FENCE_TYPE, fenceRecord.getFenceType().getNumber());
    contentValues.put(JOIN_MODE, fenceRecord.getJoinMode().getNumber());

    databaseHelper.getSignalWritableDatabase().insertOrThrow(TABLE_NAME, null, contentValues);

    //AA note RecipientsDatabase only updated when confirmation is received from server
//    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> {
//      recipient.setName(fenceRecord.getFname());
//      recipient.setGroupAvatarId(avatar != null ? avatar.getId() : null, avatar!=null?avatar.getUfId():"");//AA+ second ufsrvid
//      recipient.setParticipants(Stream.of(members).map(memberAddress -> Recipient.from(context, memberAddress, true)).toList());
//    });

    //replaces above
    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);

    RecipientDatabase recipientDatabase = SignalDatabase.recipients();
    List<RecipientId> groupMembers = GroupV1MessageProcessor.UserRecordsToRecipientIdsList(fenceRecord.getMembersList(), Optional.empty());
    if (groupMembers != null && (groupId.isMms() || Recipient.resolved(groupRecipientId).isProfileSharing())) {
      recipientDatabase.setHasGroupsInCommon(groupMembers);
    }

    Recipient.live(groupRecipient).refresh();

    notifyConversationListListeners();
  }
  //

  public void update(@NonNull GroupId groupId,
                     @Nullable String title,
                     @Nullable SignalServiceAttachmentPointer avatar)
  {
    ContentValues contentValues = new ContentValues();
    if (title != null) contentValues.put(TITLE, title);

    if (avatar != null) {
      Log.e(TAG, String.format(Locale.getDefault(), "update (id:'%s): Updating avatar and notifying listeners...", avatar.getUfId()));
      contentValues.put(AVATAR_ID, avatar.getRemoteId().getV2().get());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orElse(null));
      contentValues.put(AVATAR_UFID, avatar.getUfId()); //AA+
    } else {
      contentValues.put(AVATAR_ID, 0);
    }

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues,
                                                      GROUP_ID + " = ?",
                                                      new String[] {groupId.toString()});

    //AA+
    RecipientId groupRecipientId = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    SignalDatabase.recipients().setAvatarUfId(Recipient.live(groupRecipientId).get(), avatar.getUfId());
    notifyConversationListListeners();
  }

  public void update(@NonNull GroupMasterKey groupMasterKey, @NonNull DecryptedGroup decryptedGroup) {
    update(GroupId.v2(groupMasterKey), decryptedGroup);
  }

  public void update(@NonNull GroupId.V2 groupId, @NonNull DecryptedGroup decryptedGroup) {
    RecipientDatabase     recipientDatabase   =SignalDatabase.recipients();
    RecipientId           groupRecipientId    = recipientDatabase.getOrInsertFromGroupId(groupId);
    Optional<GroupRecord> existingGroup       = getGroup(groupId);
    String                title               = decryptedGroup.getTitle();
    ContentValues         contentValues       = new ContentValues();

   /* if (existingGroup.isPresent() && existingGroup.get().getUnmigratedV1Members().size() > 0 && existingGroup.get().isV2Group()) {
      Set<RecipientId> unmigratedV1Members = new HashSet<>(existingGroup.get().getUnmigratedV1Members());

      DecryptedGroupChange change = GroupChangeReconstruct.reconstructGroupChange(existingGroup.get().requireV2GroupProperties().getDecryptedGroup(), decryptedGroup);

      List<RecipientId> addedMembers    = uuidsToRecipientIds(DecryptedGroupUtil.membersToUuidList(change.getNewMembersList()));
      List<RecipientId> removedMembers  = uuidsToRecipientIds(DecryptedGroupUtil.removedMembersUuidList(change));
      List<RecipientId> addedInvites    = uuidsToRecipientIds(DecryptedGroupUtil.pendingToUuidList(change.getNewPendingMembersList()));
      List<RecipientId> removedInvites  = uuidsToRecipientIds(DecryptedGroupUtil.removedPendingMembersUuidList(change));
      List<RecipientId> acceptedInvites = uuidsToRecipientIds(DecryptedGroupUtil.membersToUuidList(change.getPromotePendingMembersList()));

      unmigratedV1Members.removeAll(addedMembers);
      unmigratedV1Members.removeAll(removedMembers);
      unmigratedV1Members.removeAll(addedInvites);
      unmigratedV1Members.removeAll(removedInvites);
      unmigratedV1Members.removeAll(acceptedInvites);

      contentValues.put(UNMIGRATED_V1_MEMBERS, unmigratedV1Members.isEmpty() ? null : RecipientId.toSerializedList(unmigratedV1Members));
    }*/

    List<RecipientId> groupMembers = getV2GroupMembers(decryptedGroup, true);
    contentValues.put(TITLE, title);
    contentValues.put(V2_REVISION, decryptedGroup.getRevision());
    contentValues.put(V2_DECRYPTED_GROUP, decryptedGroup.toByteArray());
    contentValues.put(MEMBERS, RecipientId.toSerializedList(groupMembers));
    contentValues.put(ACTIVE, gv2GroupActive(decryptedGroup) ? 1 : 0);

    DistributionId distributionId = Objects.requireNonNull(existingGroup.get().getDistributionId());

    if (existingGroup.isPresent() && existingGroup.get().isV2Group()) {
      DecryptedGroupChange change  = GroupChangeReconstruct.reconstructGroupChange(existingGroup.get().requireV2GroupProperties().getDecryptedGroup(), decryptedGroup);
      List<UUID>           removed = DecryptedGroupUtil.removedMembersUuidList(change);

      if (removed.size() > 0) {
        Log.i(TAG, removed.size() + " members were removed from group " + groupId + ". Rotating the DistributionId " + distributionId);
        SenderKeyUtil.rotateOurKey(distributionId);
      }
    }

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues,
                                                      GROUP_ID + " = ?",
                                                      new String[]{ groupId.toString() });

    if (decryptedGroup.hasDisappearingMessagesTimer()) {
      recipientDatabase.setExpireMessages(groupRecipientId, decryptedGroup.getDisappearingMessagesTimer().getDuration());
    }

    if (groupId.isMms() || Recipient.resolved(groupRecipientId).isProfileSharing()) {
      recipientDatabase.setHasGroupsInCommon(groupMembers);
    }

    Recipient.live(groupRecipientId).refresh();

    notifyConversationListListeners();
  }

  public void updateTitle(@NonNull GroupId.V1 groupId, String title) {
    updateTitle((GroupId) groupId, title);
  }

  public void updateTitle(@NonNull GroupId.Mms groupId, @Nullable String title) {
    updateTitle((GroupId) groupId, Util.isEmpty(title) ? null : title);
  }

  private void updateTitle(@NonNull GroupId groupId, String title) {
    if (!groupId.isV1() && !groupId.isMms()) {
      throw new AssertionError();
    }

    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                      new String[] {groupId.toString()});

    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  //AA+
  public void updateTitle(long fid, String title) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {fid+""});

    Recipient.live(RecipientUfsrvId.from(fid)).refresh();
  }

  public void updateGroupId(long fid, byte[] id) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, GroupId.v2orThrow(id).toString());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});
  }
//

//AA+
  public void updateCname(long fid, String cname) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(CNAME, cname);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

//    notifyDatabaseListeners();

  }

  public void updateCname(@NonNull GroupId groupId, String cname) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(CNAME, cname);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID_WHERE,
                                                      new String[] {groupId.toString()});

//    notifyDatabaseListeners();

  }

  //AA+
  //Given the unique constraint on cname, it saves to be judicious with updates to this field
  public void updateAndResolveCname(long fid, String cname)
  {
    reindexCname();

    GroupRecord groupRecord = getGroupByCname(cname);

    if (groupRecord != null) {
      //found existing record with the same cname...
      Log.e(TAG, String.format(Locale.getDefault(), "updateAndresolveCname (fid:'%d', cname:'%s', groupRecord.fid:'%d'): ERROR: DATA INTEGRITY: FOUND EXISTING RECORD WITH THE GIVEN CNAME...", fid, cname, groupRecord.getFid()));

      if (!groupRecord.isActive()) {
        //todo: we may need to be selective about what key we use as per below: fid, groupId...
        Log.e(TAG, String.format(Locale.getDefault(), "updateAndresolveCname (fid:'%d', cname:'%s', groupRecord.fid:'%d' grouRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP INACTIVE: DELETING...", fid, cname, groupRecord.getFid(), groupRecord.getId().toString()));
        deleteGroup(groupRecord.getId());
      } else {
        //todo: check if group is valid eg. fid >0 also do a synchronous call with the server
        String newCname = cname + System.currentTimeMillis();

        if (groupRecord.getFid() > 0) {
          Log.e(TAG, String.format(Locale.getDefault(), "updateAndresolveCname (cname:'%s', newCname:'%s', groupRecord.fid:'%d' groupRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP ACTIVE: UPDATING USING _FID_ AS KEY...", cname, newCname, groupRecord.getFid(), groupRecord.getId().toString()));
          updateCname(groupRecord.getFid(), newCname);
        } else if (groupRecord.getId() != null) {
          Log.e(TAG, String.format(Locale.getDefault(), "updateAndresolveCname (cname:'%s', newCname:'%s', groupRecord.fid:'%d' groupRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP ACTIVE: UPDATING USING _GROUPID_ AS KEY...", cname, newCname, groupRecord.getFid(), groupRecord.getId().toString()));
          updateCname(groupRecord.getId(), newCname);
        } else {
          Log.e(TAG, String.format(Locale.getDefault(), "updateAndresolveCname (fid:'%d', cname:'%s', newCname:'%s', groupRecord.fid:'%d' grouRecord.groupId:'%s'): ERROR: DATA INTEGRITY: UNABLE TO DELETE GROUP: NO VALID KEY FOUND...", fid, cname, newCname, groupRecord.getFid(), groupRecord.getId().toString()));
        }
      }
    }
    else
    {
      //no duplicate entry with the same cname...
      updateCname(fid, cname);
    }

  }

  public void deleteGroup(@NonNull GroupId groupId)
  {
    deleteGroup(groupId, true);
  }

  //AA+
  private void deleteGroup(@NonNull GroupId groupId, boolean invalidate_cache)
  {
    Log.e(TAG, String.format(Locale.getDefault(), "deleteGroup {groupId:'%s'}: !! DELETING GROUP...", groupId));
    
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    db.delete(TABLE_NAME, GROUP_ID_WHERE, new String[] {groupId.toString()});

    if (invalidate_cache) {
      notifyConversationListListeners();
    }
  }

  //this is necessary sometimes when the index gets corrupted we just rebuild it
  private void reindexCname()
  {
    SQLiteDatabase db = databaseHelper.getSignalWritableDatabase();
    db.execSQL(String.format(Locale.getDefault(), "DROP INDEX group_cname_index;"));
    db.execSQL(String.format(Locale.getDefault(), "CREATE UNIQUE INDEX IF NOT EXISTS group_cname_index ON %s ( %s );", TABLE_NAME, CNAME));
    db.execSQL(String.format(Locale.getDefault(), "REINDEX group_cname_index;"));
  }
  //

//

  /**
   * Update the fid field based using a given cname
   * @param fcname
   * @param fidConfirmed
   */
  public void updateFid(String fcname, long fidConfirmed) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(FID, fidConfirmed);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, CNAME +  " = ?",
                                                      new String[] {fcname});
  }

  public void updateRecipientId(@NonNull GroupId groupId, RecipientId recipientId) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(RECIPIENT_ID, recipientId.serialize());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID_WHERE, new String[] {groupId.toString()});
  }

  //AA+
  public void markGroupMode(long fid, int mode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(MODE, mode);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    GroupRecord record = getGroupRecordByFid(fid);

    notifyGroupsListeners(record.getId());
  }

  //AA+
  protected void notifyGroupsListeners(@NonNull GroupId groupId) {
    ApplicationDependencies.getDatabaseObserver().notifyGroupsObservers(groupId);
  }

  public int getGroupMode(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MODE},
                                                                FID + " = ?",
                                                                new String[] {fid + ""},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(MODE));
      }

      return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public int getGroupMode(GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MODE},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString() + ""},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(MODE));
      }

      return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void updateGroupMaxMembers (long fid, int maxMembers) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(MAXMEMBERS, maxMembers);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupOwnerUid(long fid, long ownerUid) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(OWNER_UID, ownerUid);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupPrivacyMode(long fid, PrivacyMode privacyMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(PRIVACY_MODE, privacyMode.getValue());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupType(long fid, GroupType groupType) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(FENCE_TYPE, groupType.getValue());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupDeliveryMode(long fid, DeliveryMode deliveryModeMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(DELIVERY_MODE, deliveryModeMode.getValue());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupJoinMode(long fid, JoinMode joinMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(JOIN_MODE, joinMode.getValue());
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
                                                      new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }
  //

  //AA+
  //AA has this local user received invitation to join this group
  public boolean isGroupInvitationPending(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MODE},
                                                                FID + " = ?",
                                                                new String[] {Long.valueOf(fid).toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return (cursor.getInt(cursor.getColumnIndexOrThrow(MODE)) == GROUP_MODE_INVITATION);
      }

      return false;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //AA+
  public void updateFenceLocation(String fcname, long fid, double longitude, double latitude) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(LONGITUDE, longitude);
    contentValues.put(LATITUDE, latitude);
    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, CNAME +  " = ?",
                                                      new String[] {fcname});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  /**
   * Used to bust the Glide cache when an avatar changes.
   */
  public void onAvatarUpdated(@NonNull GroupId groupId, boolean hasAvatar) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(AVATAR_ID, hasAvatar ? Math.abs(new SecureRandom().nextLong()) : 0);

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                      new String[] {groupId.toString()});

    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  public void updateMembers(@NonNull GroupId groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Address.toSerializedList(members, DELIMITER));
    contents.put(ACTIVE, 1);

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?", new String[] {groupId.toString()});

    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  //AA+ Handles one member at a time for now
  public void updateMembers(long fid, List<Address> affectedMembers, MembershipUpdateMode updateMode) {
    List<Address> existingMembers = getCurrentMembers(fid);
    if (existingMembers.contains(affectedMembers.get(0))) {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.e(TAG, String.format(Locale.getDefault(), "updateMembers: DATA ERROR: CURRENT LIST (size:'%d') ALREADY HAS '%s' in it", existingMembers.size(), affectedMembers.get(0).serialize()));
        return;
      }

      existingMembers.remove(affectedMembers.get(0));
    } else {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.d(TAG, String.format(Locale.getDefault(), "updateMembers (size:'%d'): Adding member  '%s'", existingMembers.size(), affectedMembers.get(0).serialize()));
        existingMembers.add(affectedMembers.get(0));
      } else {
        Log.e(TAG, String.format(Locale.getDefault(), "updateMembers (size;'%d'): DATA ERROR: MEMBER NOT IN LIST: CANNOT REMOVE  '%s' FROM INVITED LIST", existingMembers.size(), affectedMembers.get(0)));
        return;
      }
    }

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Address.toSerializedList(existingMembers, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, FID + " = ?", new String[] {String.valueOf(fid)});

    notifyConversationListListeners();
  }

  //AA+ //whole-of-list update at once
  public void updateInvitedMembers(@NonNull GroupId groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(INVITED_MEMBERS, Address.toSerializedList(members, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?", new String[] {groupId.toString()});
  }

  //whole-of-list update at once
  public void updateLinkJoinMembers(@NonNull GroupId groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(REQUESTING_MEMBERS, Address.toSerializedList(members, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?", new String[] {groupId.toString()});
  }

  //whole-of-list update at once
  public void updateBannedMembers(@NonNull GroupId groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(BLOCKED_MEMBERS, Address.toSerializedList(members, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?", new String[] {groupId.toString()});
  }

  //AA+ Handles one member at a time for now
  public void updateInvitedMembers(long fid, List<Address> affectedMembers, MembershipUpdateMode updateMode) {
    List<Address> existingMembers = getInvitedMembers(fid);
    if (existingMembers.contains(affectedMembers.get(0))) {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.e(TAG, String.format(Locale.getDefault(), "updateInvitedMembers: DATA ERROR: CURRENT INVITED LIST (size:'%d') ALREADY HAS '%s' in it", existingMembers.size(), affectedMembers.get(0).serialize()));
        return;
      }

      existingMembers.remove(affectedMembers.get(0));
    } else {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.d(TAG, String.format(Locale.getDefault(), "updateInvitedMembers (size:'%d'): Adding invited member  '%s'", existingMembers.size(), affectedMembers.get(0).serialize()));
        existingMembers.add(affectedMembers.get(0));
      } else {
        Log.e(TAG, String.format(Locale.getDefault(), "updateInvitedMembers (size;'%d'): DATA ERROR: MEMBER NOT IN LIST: CANNOT REMOVE  '%s' FROM INVITED LIST", existingMembers.size(), affectedMembers.get(0)));
        return;
      }
    }

    ContentValues contents = new ContentValues();
    contents.put(INVITED_MEMBERS, Address.toSerializedList(existingMembers, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, FID + " = ?", new String[] {String.valueOf(fid)});

    notifyConversationListListeners();
  }

  //AA+ Handles one member at a time for now
  public void updateLinkJoinMembers(long fid, List<Address> affectedMembers, MembershipUpdateMode updateMode) {
    List<Address> existingMembers = getLinkJoiningMembers(fid);
    if (existingMembers.contains(affectedMembers.get(0))) {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.e(TAG, String.format(Locale.getDefault(), "updateLinkJoinMembers: DATA ERROR: CURRENT LIST (size:'%d') ALREADY HAS '%s' in it", existingMembers.size(), affectedMembers.get(0).serialize()));
        return;
      }

      existingMembers.remove(affectedMembers.get(0));
    } else {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.d(TAG, String.format(Locale.getDefault(), "updateLinkJoinMembers (size:'%d'): Adding member  '%s'", existingMembers.size(), affectedMembers.get(0).serialize()));
        existingMembers.add(affectedMembers.get(0));
      }else {
        Log.e(TAG, String.format(Locale.getDefault(), "updateLinkJoinMembers (size;'%d'): DATA ERROR: MEMBER NOT IN LIST: CANNOT REMOVE  '%s' FROM LIST", existingMembers.size(), affectedMembers.get(0)));
        return;
      }
    }

    ContentValues contents = new ContentValues();
    contents.put(REQUESTING_MEMBERS, Address.toSerializedList(existingMembers, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, FID + " = ?", new String[] {String.valueOf(fid)});

    notifyConversationListListeners();
  }

  public void remove(@NonNull GroupId groupId, Address source) {
    List<Address> currentMembers = getCurrentMembersByAddress(groupId);
    currentMembers.remove(source);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Address.toSerializedList(currentMembers, DELIMITER));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                      new String[] {groupId.toString()});

    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

  public void remove(@NonNull GroupId groupId, RecipientId source) {
    List<RecipientId> currentMembers = getCurrentMembers(groupId);
    currentMembers.remove(source);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, RecipientId.toSerializedList(currentMembers));

    databaseHelper.getSignalWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                      new String[] {groupId.toString()});

    RecipientId groupRecipient = SignalDatabase.recipients().getOrInsertFromGroupId(groupId);
    Recipient.live(groupRecipient).refresh();
  }

 /* //AA+
  //move earth and heavens and dont comeback without the damned id
  public GroupId getGroupId (FenceRecord fenceRecord, boolean allocate_if_none)
  {
    return getGroupId(fenceRecord.getFid(), fenceRecord.getCname(), allocate_if_none);
  }*/

  //AA+
  //move earth and heavens and dont comeback without the damned id
  //todo: pass 'boolean mms' instead of default false: GroupUtil.getEncodedId(allocateGroupId(), false)
  public GroupId getGroupId(long fid, String cname, boolean allocate_if_none)
  {
    GroupId     id          = null;
    GroupRecord groupRecord = null;

    if (fid > 0 && (groupRecord = getGroupRecordByFid(fid)) != null)
      id = groupRecord.getId();
    else if (!TextUtils.isEmpty(cname) && ((groupRecord = getGroupByCname(cname)) != null))
      id = groupRecord.getId();
    else if (allocate_if_none) {
      try {
        id = GroupId.v2(new GroupMasterKey(Util.getSecretBytes(32)));
      } catch (InvalidInputException x) {
        throw new AssertionError("UNABLE TO GENERATE GroupID: MasterKey generation error");
      }
    }

    return id;

  }
//

  //AA+
  //move earth and heavens and dont comeback without the damned id
  public String getEncodedGroupId(long fid, String cname, boolean allocate_if_none)
  {
    String id = null;
    boolean allocated = false;

    if (fid > 0)
      id = getGroupRecordByFid(fid).getId().toString();
    else if (!TextUtils.isEmpty(cname))
      id = getGroupByCname(cname).getId().toString();
    else if (allocate_if_none) {
      try {
        GroupId groupId = GroupId.v2(new GroupMasterKey(Util.getSecretBytes(32)));
        id = groupId.toString();
      } catch (InvalidInputException x) {
        throw new AssertionError("UNABLE TO GENERATE GroupID: MasterKey generation error");
      }
      allocated = true;
    }

    //Log.d(TAG, String.format(Locale.getDefault(), ">> getEncodedGroupId: groupId: '%s'. Allocated:'%b'", id, allocated));

    return id;
  }
  //

  public GroupMasterKey getGroupMasterKey(long fid, String cname)
  {
    GroupId     id          = null;
    GroupRecord groupRecord = null;
    byte[]      groupMasterKeyBytes = null;

    if (fid > 0 && (groupRecord = getGroupRecordByFid(fid)) != null)
      groupMasterKeyBytes = groupRecord.getGroupMasterKeyBytes();
    else if (!TextUtils.isEmpty(cname) && ((groupRecord = getGroupByCname(cname)) != null))
      groupMasterKeyBytes = groupRecord.getGroupMasterKeyBytes();


    try {
      if (groupMasterKeyBytes != null && groupMasterKeyBytes.length > 0) return new GroupMasterKey(groupMasterKeyBytes);
    } catch (InvalidInputException x) {
      Log.e(TAG, x.getMessage());
    }

    return null;
  }

  //AA+
  public Optional<GroupRecord> getGroupByMasterKey(@NonNull GroupMasterKey groupMasterKey) {
    GroupId.V2 groupId = GroupId.v2(groupMasterKey);

    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?", new String[] {groupId.toString()},
                                                                     null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();

    if (record == null) {
      Log.e(TAG, "getGroupByMasterKey: ERROR: DATA INTEGRITY: Group Record was null for: " + groupId);
      return Optional.empty();
    }

    return Optional.of(record);

  }

  //AA to be phased out
  public  List<Address> getCurrentMembersByAddress(@NonNull GroupId groupId) { //AA+ public
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return Address.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //AA++
  public  List<LocallyAddressableUfsrvUid> getCurrentMembersByUfsrvuid(@NonNull GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return LocallyAddressableUfsrvUid.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  private static boolean gv2GroupActive(@NonNull DecryptedGroup decryptedGroup) {
    ACI aci = SignalStore.account().requireAci();

    return DecryptedGroupUtil.findMemberByUuid(decryptedGroup.getMembersList(), aci.uuid()).isPresent() ||
            DecryptedGroupUtil.findPendingByUuid(decryptedGroup.getPendingMembersList(), aci.uuid()).isPresent();
  }

  //AA this wrong, as members store ufsrvuid, not recipient id
  private List<RecipientId> getCurrentMembers(@NonNull GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return RecipientId.fromSerializedList(serializedMembers);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //AA+ to be adapted to return RecipientId
  public  List<Address> getCurrentMembers(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                                FID + " = ?",
                                                                new String[] {fid+""},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return Address.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  public long getOwnerUserId(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {OWNER_UID},
                                                                FID + " = ?",
                                                                new String[] {fid + ""},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(OWNER_UID));
      }

      return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public boolean amIGroupOwner(GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {OWNER_UID},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        long myId = UfsrvUid.fromEncoded(Recipient.self().getUfsrvUid()).getUfsrvSequenceId();
        return cursor.getLong(cursor.getColumnIndexOrThrow(OWNER_UID)) == myId;
      }

      return false;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //AA+
  //AA group members(using their numbers) saved as one string, ',' seperated
  public long getFid(byte[] id) {
    return getFid(GroupId.v2orThrow(id));
  }

  public long getFid(@NonNull GroupId groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {FID},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(FID));
      }

      return 0;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  public GroupType getGroupType(@NonNull GroupId groupId)
  {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {FENCE_TYPE},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        return GroupType.values()[cursor.getInt(cursor.getColumnIndexOrThrow(FENCE_TYPE))];
      }

      return null;
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  //AA+

  static class PairedGroupQueryDescriptor
  {
    GroupId      groupId;
    List<String> members;
    List<String> membersInvited;
    PrivacyMode  privacyMode;
    int          maxMembers;

    public PairedGroupQueryDescriptor (@NonNull GroupId groupId, List<String> members, List<String> membersInvited, PrivacyMode  privacyMode, int maxMembers)
    {
      this.groupId         = groupId;
      this.members         = members;
      this.membersInvited  = membersInvited;
      this.privacyMode     = privacyMode;
      this.maxMembers      = maxMembers;
    }

    public GroupId getGroupId ()
    {
      return groupId;
    }

    public List<String> getMembers ()
    {
      return members;
    }

    public List<String> getMembersInvited ()
    {
      return membersInvited;
    }

    public PrivacyMode getPrivacyMode ()
    {
      return privacyMode;
    }

    public int getMaxMembers ()
    {
      return maxMembers;
    }

    public boolean isPairedGroup()
    {
      return (privacyMode.equals(PrivacyMode.PRIVATE) && maxMembers == 2 &&
              (members.size() == 2 || (members.size() == 1 && membersInvited.size() == 1)));
    }
  }

  private Optional<PairedGroupQueryDescriptor> getPairedGroupDescriptor(@NonNull GroupId groupId)
  {
    Cursor cursor = null;

    try {
    cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {FID, PRIVACY_MODE, INVITED_MEMBERS, MEMBERS, MAXMEMBERS},
                                                              GROUP_ID + " = ?",
                                                              new String[] {groupId.toString()},
                                                              null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        return  Optional.of(new PairedGroupQueryDescriptor(groupId,
                            Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ","),
                            Util.split(cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS)), ","),
                            PrivacyMode.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PRIVACY_MODE))],
                            cursor.getInt(cursor.getColumnIndexOrThrow(MAXMEMBERS))));
      }

      return Optional.empty();
    } finally {
      if (cursor != null) cursor.close();
    }

  }

  public boolean
  isPairedGroup(@NonNull GroupId groupId)
  {
    Optional<PairedGroupQueryDescriptor> result = getPairedGroupDescriptor(groupId);
//    return result.isPairedGroup();
    if (!result.isEmpty()) {//todo use above
      return (result.get().getPrivacyMode().equals(PrivacyMode.PRIVATE) && result.get().getMaxMembers() == 2 &&
              (result.get().getMembers().size() == 2 || (result.get().getMembers().size() == 1 && result.get().getMembersInvited().size() == 1))
      );
    }

    return false;

  }

  public boolean
  isPairedGroupAndAmOnIt(@NonNull GroupId groupId)
  {
    Optional<PairedGroupQueryDescriptor> result = getPairedGroupDescriptor(groupId);
    return !result.isEmpty() && result.get().isPairedGroup() && result.get().getMembers().contains(Recipient.self().requireUfsrvUid());

  }

  public Optional <Object>
  isPairedGroupWithStyliser(@NonNull GroupId groupId, PairedGroupName.PairedGroupTitleStyliser titleStyliser)
  {
    Optional<PairedGroupQueryDescriptor> result = getPairedGroupDescriptor(groupId);
    if (result.isPresent()) {
      if (result.get().getPrivacyMode().equals(PrivacyMode.PRIVATE) && result.get().getMaxMembers() == 2 &&
          (result.get().getMembers().size() == 2 || (result.get().getMembers().size() == 1 && result.get().getMembersInvited().size() == 1))) {
        return Optional.ofNullable(titleStyliser.formatTitle(groupId.toString(), result.get().getMembers(), result.get().getMembersInvited()));
      }
    }

    return Optional.empty();
  }

  public boolean
  isPairedGroupReady(@NonNull GroupId groupId)
  {
    Optional<PairedGroupQueryDescriptor> result = getPairedGroupDescriptor(groupId);
    if (result.isPresent()) {
      return  (result.get().getPrivacyMode().equals(PrivacyMode.PRIVATE) && result.get().getMaxMembers() == 2 && result.get().getMembers().size() == 2);
    }

    return false;

  }

  public List<Long> getPairedGroups(@NonNull Recipient recipient, @NonNull Recipient recipientOther)
  {
    Reader reader        = getGroups();
    LinkedList<Long>    groups = new LinkedList<>();

    GroupDatabase.GroupRecord groupRecord;

    while ((groupRecord = reader.getNext()) != null) {
      if (groupRecord.active &&
          groupRecord.getMaxmembers()== 2 &&
          groupRecord.getPrivacyMode()== PrivacyMode.PRIVATE.getValue()) {
        List<Address> members          = groupRecord.getMembers();
        List<Address> membersInvited   = groupRecord.getMembersInvited();
        if ((members.contains(recipient.requireAddress()) || membersInvited.contains(recipient.requireAddress())) &&
            (members.contains(recipientOther.requireAddress()) || membersInvited.contains(recipientOther.requireAddress()))) groups.add(groupRecord.fid);
      }
    }

    reader.close();

    return groups;
  }

  /**
   * Only retrieve the group if the PairedGroup is composed of self and the other user
   * @return
   */
  public Optional<GroupRecord> getPairedGroupWith(@NonNull Recipient recipientOther)
  {
    Reader reader        = getGroups();

    GroupRecord groupRecord;
    Optional<GroupRecord> groupRecordOptional = Optional.empty();

    while ((groupRecord = reader.getNext()) != null) {
      if (groupRecord.active &&
              groupRecord.getMaxmembers() == 2 &&
              groupRecord.getPrivacyMode()== PrivacyMode.PRIVATE.getValue() &&
              groupRecord.getMembers().size() == 2) {
        List<Address> members          = groupRecord.getMembers();
        if (members.contains(Recipient.self().requireAddress()) && members.contains(recipientOther.requireAddress())) groupRecordOptional = Optional.of(groupRecord);
      }
    }

    reader.close();

    return groupRecordOptional;
  }

  public Optional<Address> PairedGroupGetOther(@NonNull GroupId groupId, Address thisAddress, boolean isReadyOnly)
  {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {FID, PRIVACY_MODE, INVITED_MEMBERS, MEMBERS, MAXMEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        List<String> members          = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ",");
        List<String> membersInvited   = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS)), ",");
        PrivacyMode  privacyMode      = PrivacyMode.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PRIVACY_MODE))];
        int          maxMembers       = cursor.getInt(cursor.getColumnIndexOrThrow(MAXMEMBERS));

        if (privacyMode.equals(PrivacyMode.PRIVATE) && maxMembers == 2) {
           if (members.size() == 2) {
             for (String address : members) {
               Address otherAddress = Address.fromSerialized(address);
               if (!otherAddress.equals(thisAddress)) return Optional.of(otherAddress);
             }

             if (!isReadyOnly) { //allow group with unaccepted invitee
               if (members.size() == 1 && membersInvited.size() == 1) {
                 for (String address : membersInvited) {
                   Address otherAddress = Address.fromSerialized(address);
                   if (!otherAddress.equals(thisAddress)) return Optional.of(otherAddress);
                 }
               }
             }
          }
        }
      }

    } finally {
      if (cursor != null) cursor.close();
    }

    return Optional.empty();
  }

  public boolean
  isGuardian(@NonNull GroupId groupId)
  {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {FENCE_TYPE},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
          return GroupType.values()[cursor.getInt(cursor.getColumnIndexOrThrow(FENCE_TYPE))] == GroupType.GUARDIAN;
      }

    } finally {
      if (cursor != null) cursor.close();
    }

    return false;

  }

  public Optional<Recipient> getOriginatorForGuardianGroup(Context context, @NonNull GroupId groupId)
  {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        List<String> members          = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ",");

        for (String address : members) {
          return Optional.of(Recipient.live(address).get());
        }
      }
    } finally {
      if (cursor != null) cursor.close();
    }

    return Optional.empty();
  }

  public boolean isInvitation(long fid) {
    GroupRecord record = getGroupRecordByFid(fid);
    return record != null && record.mode == GROUP_MODE_INVITATION;
  }

  public Cursor getInvitedGroupsList()
  {
    //SELECT * FROM threads t INNER JOIN groups g ON threads.ufsrv_fid = groups.fid where g.mode=?"
    Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, null, MODE + " = ?",
                                                                     new String[] {String.valueOf(GROUP_MODE_INVITATION)},
                                                                     null, null, FID + " ASC");

    return cursor;
  }

  public int getInvitationListCount() {
    SQLiteDatabase db = databaseHelper.getSignalReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, MODE + " = ?",
              new String[] {String.valueOf(GROUP_MODE_INVITATION)}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

    } finally {
      if (cursor != null) cursor.close();
    }

    return 0;
  }

  //AA group members(using their ufsrvuid's) saved as one string, ',' seperated
  private List<Address> getInvitedMembers(@NonNull GroupId groupId) {
    Cursor cursor = null;

    Log.d(TAG, ">> getInvitedMembers: getting invited members for GROUPID: " + groupId.toString());
    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {INVITED_MEMBERS},
                                                                GROUP_ID + " = ?",
                                                                new String[] {groupId.toString()},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS));
        if (TextUtils.isEmpty(serializedMembers)) return new  LinkedList<>();
        return Address.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //AA+
  public List<Address> getInvitedMembers(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {INVITED_MEMBERS},
                                                                FID + " = ?",
                                                                new String[] {String.valueOf(fid)},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS));
        if (TextUtils.isEmpty(serializedMembers)) return new  LinkedList<>();
        return Address.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public List<Address> getLinkJoiningMembers(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[] {REQUESTING_MEMBERS},
                                                                FID + " = ?",
                                                                new String[] {String.valueOf(fid)},
                                                                null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(REQUESTING_MEMBERS));
        if (TextUtils.isEmpty(serializedMembers)) return new  LinkedList<>();
        return Address.fromSerializedList(serializedMembers, DELIMITER);
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //

  //AA+
  public Optional<GroupRecord> getGroupRecordfromThreadId(long threadId)
  {
    ThreadDatabase    threadDatabase    = SignalDatabase.threads();
    long              fid               = threadDatabase.getFidForThreadId(threadId);

    if (fid > 0) {
      return Optional.ofNullable(getGroupRecordByFid(fid));
    }

    Log.e(TAG, String.format(Locale.getDefault(), "getGroupRecordfromThreadId: COULD NOT LOCATE THREAD RECORD FOR threadid :'%d'. NO VALID FID FOUND", threadId));
    return Optional.empty();
  }

  public Optional<GroupId> getGroupIdFromThreadId(long threadId)
  {
    Optional<GroupRecord> groupRecord = getGroupRecordfromThreadId(threadId);
    if (groupRecord.isPresent()) {
      return Optional.of(groupRecord.get().getId());
    }

    return Optional.empty();
  }
  //

  public boolean isActive(@NonNull GroupId groupId) {
    Optional<GroupRecord> record = getGroup(groupId);
    return record.isPresent() && record.get().isActive();
  }

  public void setActive(@NonNull GroupId groupId, boolean active) {
    SQLiteDatabase database = databaseHelper.getSignalWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(ACTIVE, active ? 1 : 0);
    database.update(TABLE_NAME, values, GROUP_ID + " = ?", new String[] {groupId.toString()});
  }

  @WorkerThread
  public boolean isCurrentMember(@NonNull GroupId.Push groupId, @NonNull RecipientId recipientId) {
    SQLiteDatabase database = databaseHelper.getSignalReadableDatabase();

    try (Cursor cursor = database.query(TABLE_NAME, new String[] {MEMBERS},
                                        GROUP_ID + " = ?", new String[] {groupId.toString()},
                                        null, null, null))
    {
      if (cursor.moveToNext()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        cursor.close();

        return RecipientId.serializedListContains(serializedMembers, recipientId);
      } else {
        return false;
      }
    }
  }

  private static @NonNull List<RecipientId> uuidsToRecipientIds(@NonNull List<UUID> uuids) {
    List<RecipientId> groupMembers = new ArrayList<>(uuids.size());

    for (UUID uuid : uuids) {
      if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
        Log.w(TAG, "Seen unknown UUID in members list");
      } else {
        RecipientId           id       = RecipientId.from(ServiceId.from(uuid), null);
        Optional<RecipientId> remapped = RemappedRecords.getInstance().getRecipient(id);

        if (remapped.isPresent()) {
          Log.w(TAG, "Saw that " + id + " remapped to " + remapped + ". Using the mapping.");
          groupMembers.add(remapped.get());
        } else {
          groupMembers.add(id);
        }
      }
    }

    Collections.sort(groupMembers);

    return groupMembers;
  }

  private static @NonNull List<RecipientId> getV2GroupMembers(@NonNull DecryptedGroup decryptedGroup, boolean shouldRetry) {
    List<UUID>        uuids = DecryptedGroupUtil.membersToUuidList(decryptedGroup.getMembersList());
    List<RecipientId> ids   = uuidsToRecipientIds(uuids);

    if (false && RemappedRecords.getInstance().areAnyRemapped(ids)) {//AA + false
      if (shouldRetry) {
        Log.w(TAG, "Found remapped records where we shouldn't. Clearing cache and trying again.");
        RecipientId.clearCache();
        RemappedRecords.getInstance().resetCache();
        return getV2GroupMembers(decryptedGroup, false);
      } else {
        throw new IllegalStateException("Remapped records in group membership!");
      }
    } else {
      return ids;
    }
  }

//AA+
private static String serializeV2GroupMembersAsRecipients(@NonNull DecryptedGroup decryptedGroup, boolean excludeSelf) {
  List<Recipient> groupMembersRecipients  = new ArrayList<>(decryptedGroup.getMembersCount());

  for (DecryptedMember member : decryptedGroup.getMembersList()) {
    UUID uuid = UuidUtil.fromByteString(member.getUuid());
    if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
      Log.w(TAG, "Seen unknown UUID in members list");
    } else {
      if (excludeSelf) if (uuid.equals(Recipient.self().getServiceId().get().uuid())) continue;
      groupMembersRecipients.add(Recipient.resolved(RecipientId.from(ACI.from(uuid), null)));
    }
  }

  return Recipient.toSerializedUfsrvUidList(groupMembersRecipients);
}

  public static GroupId allocateGroupId(boolean mms) {
    byte[] groupId = new byte[16];
    new SecureRandom().nextBytes(groupId);
    return mms ? GroupId.mms(groupId) : GroupId.v2orThrow(groupId);
  }

  //AA+
  public static GroupId allocateGuardianGroupId() {
    byte[] groupId = new byte[16];
    new SecureRandom().nextBytes(groupId);
    return  GroupId.guradian(groupId);
  }
  //

  public List<GroupId.V2> getAllGroupV2Ids() {
    List<GroupId.V2> result = new LinkedList<>();

    try (Cursor cursor = databaseHelper.getSignalReadableDatabase().query(TABLE_NAME, new String[]{ GROUP_ID }, null, null, null, null, null)) {
      while (cursor.moveToNext()) {
        GroupId groupId = GroupId.parseOrThrow(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)));
        if (groupId.isV2()) {
          result.add(groupId.requireV2());
        }
      }
    }

    return result;
  }

  /**
   * Key: The 'expected' V2 ID (i.e. what a V1 ID would map to when migrated)
   * Value: The matching V1 ID
   */
  public @NonNull
  Map<GroupId.V2, GroupId.V1> getAllExpectedV2Ids() {
    Map<GroupId.V2, GroupId.V1> result = new HashMap<>();

    /*String[] projection = new String[]{ GROUP_ID, EXPECTED_V2_ID };
    String   query      = EXPECTED_V2_ID + " NOT NULL";

    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, projection, query, null, null, null, null)) {
      while (cursor.moveToNext()) {
        GroupId.V1 groupId    = GroupId.parseOrThrow(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))).requireV1();
        GroupId.V2 expectedId = GroupId.parseOrThrow(cursor.getString(cursor.getColumnIndexOrThrow(EXPECTED_V2_ID))).requireV2();

        result.put(expectedId, groupId);
      }
    }*/

    return result;
  }

  public static class Reader implements Closeable {

    public final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public @Nullable GroupRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public int getCount() {
      if (cursor == null) {
        return 0;
      } else {
        return cursor.getCount();
      }
    }

    public @Nullable GroupRecord getCurrent() {
        if (cursor == null || cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)) == null || cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID)) == 0) {
          return null;
        }

        return new GroupRecord(GroupId.parseOrThrow(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))),
                             RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(RECIPIENT_ID))),
                             cursor.getString(cursor.getColumnIndexOrThrow(TITLE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)),
                             cursor.getLong(cursor.getColumnIndexOrThrow(AVATAR_ID)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_KEY)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_CONTENT_TYPE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_RELAY)),
                       cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1,
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_DIGEST)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(MMS)) == 1,
                               cursor.getBlob(cursor.getColumnIndexOrThrow(V2_MASTER_KEY)),
                               CursorUtil.getString(cursor, DISTRIBUTION_ID).map(DistributionId::from).orElse(null),
                               0, null,
              //AA+

                               cursor.getString(cursor.getColumnIndexOrThrow(GROUP_DATA)),
                              cursor.getString(cursor.getColumnIndexOrThrow(CNAME)),
                              cursor.getDouble(cursor.getColumnIndexOrThrow(LONGITUDE)),
                                             cursor.getDouble(cursor.getColumnIndexOrThrow(LATITUDE)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(MAXMEMBERS)),
                              cursor.getLong(cursor.getColumnIndexOrThrow(TTL)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(MODE)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(FENCE_TYPE)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(PRIVACY_MODE)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(DELIVERY_MODE)),
                              cursor.getInt(cursor.getColumnIndexOrThrow(JOIN_MODE)),
                              cursor.getLong(cursor.getColumnIndexOrThrow(FID)),
                              cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_UFID)),
                              cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS)),
                             cursor.getString(cursor.getColumnIndexOrThrow(REQUESTING_MEMBERS)),
                             cursor.getString(cursor.getColumnIndexOrThrow(BLOCKED_MEMBERS)),
                              cursor.getLong(cursor.getColumnIndexOrThrow(OWNER_UID)),
                              Optional.ofNullable(UfsrvUid.fromEncoded(cursor.getString(cursor.getColumnIndexOrThrow(INVITED_BY))))
                              //AA
                            );
    }

    @Override
    public void close() {
      if (this.cursor != null)
        this.cursor.close();
    }
  }

  public static class GroupRecord {

    private final GroupId           id;
    private final RecipientId       recipientId;
    private final String            title;
//    private final List<RecipientId> members;
    private final List<Address>     members; //AA+ Address cast
    private final long              avatarId;
    private final byte[]            avatarKey;
    private final byte[]            avatarDigest;
    private final String            avatarContentType;
    private final String            relay;
    private final boolean           active;
    private final boolean           mms;
    @Nullable private final         V2GroupProperties v2GroupProperties;
    private final                   DistributionId distributionId;
    private final byte[]            groupMasterKeyBytes;//AA+

    //AA+
    @Nullable private final         GroupDataDescriptor groupData;
    private final String            cname;
    private final double            longitude;
    private final double            latitude;
    private final int               maxmembers;
    private final long              ttl;
    private final int               mode;
    private final long              fid;
    private final String            avatarUfId;
    private final int               type;
    private final int               privacyMode;
    private final int               deliveryType;
    private final int               joinMode;
    private final List<Address>     membersInvited;
    private final List<Address>     membersLinkJoining;
    private final List<Address>     membersBlocked;
    private  long                   eid;//this is currently not saved in this db
    private long                    ownerUserId;
    private final Optional<UfsrvUid> invitedBy;

    public GroupRecord(@NonNull GroupId id, @NonNull RecipientId recipientId, String title, String members,
                       long avatarId, byte[] avatarKey, String avatarContentType,
                       String relay, boolean active, byte[] avatarDigest, boolean mms,
                       @Nullable byte[] groupMasterKeyBytes, @Nullable DistributionId distributionId, int groupRevision, @Nullable byte[] decryptedGroupBytes,
                       String groupData,
                       String cname, double longitude, double latitude, int maxmembers,
                       long ttl, int mode, int type, int privacy_mode, int deliveryType,
                       int joinMode, long fid, String avatarUfId, String membersInvited, String membersLinkJoining, String membersBlocked, long ownerUserId,
                       Optional<UfsrvUid> invitedBy)
    {
      this.id                = id;
      this.recipientId       = recipientId;
      this.title             = title;
      if (!TextUtils.isEmpty(members)) this.members = Address.fromSerializedList(members, DELIMITER);
      else                             this.members = new LinkedList<>();
      this.avatarId          = avatarId;
      this.avatarKey         = avatarKey;
      this.avatarContentType = avatarContentType;
      this.avatarDigest      = avatarDigest;
      this.relay             = relay;
      this.active            = active;
      this.mms               = mms;
      this.distributionId    = distributionId;

      this.groupMasterKeyBytes   = groupMasterKeyBytes; //AA+

      V2GroupProperties v2GroupProperties = null;
      if (groupMasterKeyBytes != null && decryptedGroupBytes != null) {
        GroupMasterKey groupMasterKey;
        try {
          groupMasterKey = new GroupMasterKey(groupMasterKeyBytes);
        } catch (InvalidInputException e) {
          throw new AssertionError(e);
        }
        v2GroupProperties = new V2GroupProperties(groupMasterKey, groupRevision, decryptedGroupBytes);
      }
      this.v2GroupProperties = v2GroupProperties;

      //AA+
      if (!TextUtils.isEmpty(groupData)) {
        this.groupData = GroupDataDescriptor.GroupDataDescriptorFrom(groupData).get();
      } else {
        this.groupData = GroupDataDescriptor.GroupDataDescriptorFrom(GroupDataDescriptor.EMPTY_SERIALISER).get();
      }
      this.cname              = cname;
      this.longitude          = longitude;
      this.latitude           = latitude;
      this.maxmembers         = maxmembers;
      this.ttl                = ttl;
      this.mode               = mode;
      this.type               = type;
      this.privacyMode        = privacy_mode;
      this.deliveryType       = deliveryType;
      this.joinMode           = joinMode;
      this.fid                = fid;
      this.avatarUfId         = avatarUfId;
      if (membersInvited != null) this.membersInvited = Address.fromSerializedList(membersInvited, DELIMITER);
      else                      this.membersInvited   = new LinkedList<>();
      if (membersLinkJoining != null) this.membersLinkJoining = Address.fromSerializedList(membersLinkJoining, DELIMITER);
      else                      this.membersLinkJoining = new LinkedList<>();
      if (membersBlocked != null) this.membersBlocked = Address.fromSerializedList(membersBlocked, DELIMITER);
      else                      this.membersBlocked   = new LinkedList<>();
      this.ownerUserId        = ownerUserId;
      this.invitedBy          = invitedBy;
      //
    }

    public GroupId getId() {
      return id;
    }

    public @NonNull RecipientId getRecipientId() {
      return recipientId;
    }

    public String getTitle() {
      return title;
    }

    public @NonNull String getDescription() {
      /*if (v2GroupProperties != null) {
        return v2GroupProperties.getDecryptedGroup().getDescription();
      } else {
        return "";
      }*/

      return groupData.getDescription();
    }

    public boolean isAnnouncementGroup() {
      if (v2GroupProperties != null) {
        return v2GroupProperties.getDecryptedGroup().getIsAnnouncementGroup() == EnabledState.ENABLED;
      } else {
        return false;
      }
    }

  /*  public @NonNull List<RecipientId> getMembers() {
      return members;
    }*/

    public List<Address> getMembers() {
      return members;
    }

    @WorkerThread
    public @NonNull List<Recipient> getAdmins() {
      return getAdmins(getMembersAsRecipient());//AA+
      /*if (v2GroupProperties != null) {
        return v2GroupProperties.getAdmins(members.stream().map(Recipient::resolved).collect(Collectors.toList()));
      } else {
        return Collections.emptyList();
      }*/
    }

    //AA+
    public List<RecipientId> getMembersRecipientId() {
      return Stream.of(members).map(member -> Recipient.live(member.serialize()).get().getId()).toList();
    }

    //AA+
    public List<Recipient> getMembersAsRecipient() {
      return Stream.of(members).map(member -> Recipient.live(member.serialize()).get()).toList();
    }

    public boolean hasAvatar() {
//      return avatarId != 0;
      return !TextUtils.isEmpty(avatarUfId) && !avatarUfId.equals("0");
    }

    public long getAvatarId() {
      return avatarId;
    }

    public byte[] getAvatarKey() {
      return avatarKey;
    }

    //AA+
    public byte[] getGroupMasterKeyBytes () {
      return groupMasterKeyBytes;
    }

    public Optional<GroupMasterKey> getGroupMasterKey() {

      try {
        GroupMasterKey groupMasterKey = new GroupMasterKey(this.groupMasterKeyBytes);
        return Optional.of(groupMasterKey);
      } catch (InvalidInputException x) {
        Log.e(TAG, x.toString());
      }

      return Optional.empty();
    }

    public String getAvatarContentType() {
      return avatarContentType;
    }

    public byte[] getAvatarDigest() {
      return avatarDigest;
    }

    public String getRelay() {
      return relay;
    }

    public boolean isActive() {
      return active;
    }

    public boolean isMms() {
      return mms;
    }

    public @Nullable DistributionId getDistributionId() {
      return distributionId;
    }

    public long getFid ()
    {
      return fid;
    }

    public String getAvatarUfId ()
    {
      return avatarUfId;
    }

    public double getLongitude ()
    {
      return longitude;
    }

    public double getLatitude ()
    {
      return latitude;
    }

    public String getCname ()
    {
      return cname;
    }

    public List<Address> getMembersInvited() {
      return membersInvited;
    }

    //AA+
    public List<RecipientId> getMembersInvitedRecipientId() {
      return Stream.of(membersInvited).map(member -> Recipient.live(member.serialize()).get().getId()).toList();
    }

    public List<RecipientId> getMembersLinkJoiningRecipientId() {
      return Stream.of(membersLinkJoining).map(member -> Recipient.live(member.serialize()).get().getId()).toList();
    }

    public List<RecipientId> getMembersBlockedRecipientId() {
      return Stream.of(membersBlocked).map(member -> Recipient.live(member.serialize()).get().getId()).toList();
    }

    public List<Address> getMembersLinkJoining() {
      return membersLinkJoining;
    }

    public int getMode() {return mode;}

    public int getType()
    {
      return type;
    }

    public int getDeliveryType()
    {
      return deliveryType;
    }

    public int getPrivacyMode()
    {
      return privacyMode;
    }

    public int getJoinMode()
    {
      return joinMode;
    }

    public Optional<UfsrvUid> getInvitedBy() {
      return invitedBy;
    }

    public int getMaxmembers()
    {
      return maxmembers;
    }

    public long getTtl()
    {
      return ttl;
    }

    public  long getOwnerUserId()
    {
      return ownerUserId;
    }

    //todo: this is temporary treatment
    public void setEid(long eid)
    {
      this.eid = eid;
    }

    public long getEid()
    {
      return eid;
    }

    public boolean isGuardian()
    {
      return type == GroupType.GUARDIAN.getValue();
    }

    public boolean isV1Group() {
      return !mms && !isV2Group();
    }

    public boolean isV2Group() {
      return true;//return v2GroupProperties != null;//AA+
    }

    public @NonNull V2GroupProperties requireV2GroupProperties() {
      if (v2GroupProperties == null) {
        //throw new AssertionError(); //AA-
      }

      return v2GroupProperties;
    }

    public boolean isAdmin(@NonNull Recipient recipient) {
//      return isV2Group() && requireV2GroupProperties().isAdmin(recipient);
      long derivedOwnerid = UfsrvUid.fromEncoded(recipient.getUfsrvUid()).getUfsrvSequenceId();//AA+
      return getOwnerUserId() == derivedOwnerid;
    }

    public @NonNull List<Recipient> getAdmins(@NonNull List<Recipient> members) {
      return members.stream().filter(this::isAdmin).collect(Collectors.toList());
    }

    public MemberLevel memberLevel(@NonNull Recipient recipient) {
      return members.contains(Address.fromSerialized(recipient.getUfsrvUid())) ? MemberLevel.FULL_MEMBER :
                                                                                 membersInvited.contains(Address.fromSerialized(recipient.getUfsrvUid())) ?
                                                                                  MemberLevel.PENDING_MEMBER : membersLinkJoining.contains(Address.fromSerialized(recipient.getUfsrvUid())) ?
                                                                                                                MemberLevel.LINKJOINING_MEMBER : MemberLevel.NOT_A_MEMBER;
     /* if (isV2Group()) {
        return requireV2GroupProperties().memberLevel(recipient);
      } else if (isMms() && recipient.isSelf()) {
        return MemberLevel.FULL_MEMBER;
      } else {
        return members.contains(recipient.getId()) ? MemberLevel.FULL_MEMBER
                                                   : MemberLevel.NOT_A_MEMBER;
      }*/
    }

    /**
     * Who is allowed to add to the membership of this group.
     */
    public GroupAccessControl getMembershipAdditionAccessControl() {
      return getMembershipAdditionAccessControl(Recipient.self());//AA+

      /*if (isV2Group()) {
        if (requireV2GroupProperties().getDecryptedGroup().getAccessControl().getMembers() == AccessControl.AccessRequired.MEMBER) {
          return GroupAccessControl.ALL_MEMBERS;
        }
        return GroupAccessControl.ONLY_ADMINS;
      } else {
        return id.isV1() ? GroupAccessControl.ALL_MEMBERS : GroupAccessControl.ONLY_ADMINS;
      }*/
    }

    //AA+ currently recipient argument is ignored and check is only performed for self
    //see reference code in TBDGroupCreateActivity:FillExistingGroupInfoAsyncTask()
    public GroupAccessControl getMembershipAdditionAccessControl(Recipient recipient)
    {
      Context context = ApplicationContext.getInstance();

      if (getOwnerUserId() != TextSecurePreferences.getUserId(context)) {
        final LiveRecipient groupRecipient = Recipient.live(getRecipientId());
        RecipientRecord groupPreferences = SignalDatabase.recipients().getRecord(groupRecipient.getId());

        boolean isUserMembershipPermitted = SignalDatabase.recipients().isUserPermitted(groupPreferences.getPermissionMembership(), groupPreferences.getPermSemanticsMembership(), TextSecurePreferences.getUserId(context));
        if (!isUserMembershipPermitted) {
          return GroupAccessControl.ONLY_ADMINS;
        }
      }

      return GroupAccessControl.ALLOWED;
    }

    /**
     * Who is allowed to modify the attributes of this group, name/avatar/timer etc.
     */
    public GroupAccessControl getAttributesAccessControl() {
      return getAttributesAccessControlFor(Recipient.self());//AA+

      /*if (isV2Group()) {
        if (requireV2GroupProperties().getDecryptedGroup().getAccessControl().getAttributes() == AccessControl.AccessRequired.MEMBER) {
          return GroupAccessControl.ALL_MEMBERS;
        }
        return GroupAccessControl.ONLY_ADMINS;
      } else {
        return GroupAccessControl.ALL_MEMBERS;
      }*/
    }

    //AA+
    public GroupAccessControl getAttributesAccessControlFor(Recipient recipient)
    {
      Context context = ApplicationContext.getInstance();

      if (getOwnerUserId() != TextSecurePreferences.getUserId(context)) {
        final LiveRecipient groupRecipient = Recipient.live(getRecipientId());
        RecipientRecord groupPreferences = SignalDatabase.recipients().getRecord(groupRecipient.getId());

        boolean isUserPresentationPermitted = SignalDatabase.recipients().isUserPermitted(groupPreferences.getPermissionPresentation(), groupPreferences.getPermSemanticsPresentation(), TextSecurePreferences.getUserId(context));
        if (!isUserPresentationPermitted) {
          return GroupAccessControl.ONLY_ADMINS;
        }
      }

      return GroupAccessControl.ALLOWED;

    }

    public boolean isPairedGroup() {
      PairedGroupQueryDescriptor pairedGroup = new PairedGroupQueryDescriptor(getId(), Stream.of(getMembers()).map(Address::toString).toList(), Stream.of(getMembersInvited()).map(mi -> mi.toString()).toList(), PrivacyMode.values()[getPrivacyMode()], getMaxmembers());
      return pairedGroup.isPairedGroup();
    }

    /**
     * Whether or not the recipient is a pending member.
     */
    public boolean isPendingMember(@NonNull Recipient recipient) {
      return membersInvited.contains(recipient.getId());//AA+
      /* if (isV2Group()) {
        Optional<ServiceId> serviceId = recipient.getServiceId();
        if (serviceId.isPresent()) {
          return DecryptedGroupUtil.findPendingByUuid(requireV2GroupProperties().getDecryptedGroup().getPendingMembersList(), serviceId.get().uuid())
                                   .isPresent();
        }
      }
      return false;
    }*/
    }
  }

  public static class V2GroupProperties {

    @NonNull private final GroupMasterKey groupMasterKey;
    private final int            groupRevision;
    @NonNull private final byte[]         decryptedGroupBytes;
    private       DecryptedGroup decryptedGroup;

    private V2GroupProperties(@NonNull GroupMasterKey groupMasterKey, int groupRevision, @NonNull byte[] decryptedGroup) {
      this.groupMasterKey      = groupMasterKey;
      this.groupRevision       = groupRevision;
      this.decryptedGroupBytes = decryptedGroup;
    }

    public @NonNull GroupMasterKey getGroupMasterKey() {
      return groupMasterKey;
    }

    public int getGroupRevision() {
      return groupRevision;
    }

    public @NonNull DecryptedGroup getDecryptedGroup() {
      try {
        if (decryptedGroup == null) {
          decryptedGroup = DecryptedGroup.parseFrom(decryptedGroupBytes);
        }
        return decryptedGroup;
      } catch (InvalidProtocolBufferException e) {
        throw new AssertionError(e);
      }
    }

    public boolean isAdmin(@NonNull Recipient recipient) {
      Optional<ServiceId> serviceId = recipient.getServiceId();

      if (!serviceId.isPresent()) {
        return false;
      }

      return DecryptedGroupUtil.findMemberByUuid(getDecryptedGroup().getMembersList(), serviceId.get().uuid())
              .map(t -> t.getRole() == Member.Role.ADMINISTRATOR)
              .orElse(false);
    }

    public MemberLevel memberLevel(@NonNull Recipient recipient) {
      Optional<ServiceId> serviceId = recipient.getServiceId();

      if (!serviceId.isPresent()) {
        return MemberLevel.NOT_A_MEMBER;
      }

      DecryptedGroup decryptedGroup = getDecryptedGroup();

      return DecryptedGroupUtil.findMemberByUuid(decryptedGroup.getMembersList(), serviceId.get().uuid())
              .map(member -> member.getRole() == Member.Role.ADMINISTRATOR
                                   ? MemberLevel.ADMINISTRATOR
                                   : MemberLevel.FULL_MEMBER)
              .orElseGet(() -> DecryptedGroupUtil.findPendingByUuid(decryptedGroup.getPendingMembersList(), serviceId.get().uuid())
                      .map(m -> MemberLevel.PENDING_MEMBER)
                      .orElseGet(() -> DecryptedGroupUtil.findRequestingByUuid(decryptedGroup.getRequestingMembersList(), serviceId.get().uuid())
                              .map(m -> MemberLevel.REQUESTING_MEMBER)
                              .orElse(MemberLevel.NOT_A_MEMBER)));
    }

    public List<Recipient> getMemberRecipients(@NonNull MemberSet memberSet) {
      return Recipient.resolvedList(getMemberRecipientIds(memberSet));
    }

    public List<RecipientId> getMemberRecipientIds(@NonNull MemberSet memberSet) {
      boolean           includeSelf    = memberSet.includeSelf;
      DecryptedGroup    groupV2        = getDecryptedGroup();
      UUID              selfUuid       = SignalStore.account().requireAci().uuid();
      List<RecipientId> recipients     = new ArrayList<>(groupV2.getMembersCount() + groupV2.getPendingMembersCount());
      int               unknownMembers = 0;
      int               unknownPending = 0;

      for (UUID uuid : DecryptedGroupUtil.toUuidList(groupV2.getMembersList())) {
        if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
          unknownMembers++;
        } else if (includeSelf || !selfUuid.equals(uuid)) {
          recipients.add(RecipientId.from(ServiceId.from(uuid), null));
        }
      }
      if (memberSet.includePending) {
        for (UUID uuid : DecryptedGroupUtil.pendingToUuidList(groupV2.getPendingMembersList())) {
          if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
            unknownPending++;
          } else if (includeSelf || !selfUuid.equals(uuid)) {
            recipients.add(RecipientId.from(ServiceId.from(uuid), null));
          }
        }
      }

      if ((unknownMembers + unknownPending) > 0) {
        Log.w(TAG, String.format(Locale.US, "Group contains %d + %d unknown pending and full members", unknownPending, unknownMembers));
      }

      return recipients;
    }

    public @NonNull Set<UUID> getBannedMembers() {
      return Collections.emptySet();
//      return DecryptedGroupUtil.bannedMembersToUuidSet(getDecryptedGroup().getBannedMembersList());//AA- not supported
    }
  }

  public @NonNull List<GroupId> getGroupsToDisplayAsStories() throws BadGroupIdException {
    String[] selection = SqlUtil.buildArgs(GROUP_ID);
    String   where     = DISPLAY_AS_STORY + " = ? AND " + ACTIVE + " = ?";
    String[] whereArgs = SqlUtil.buildArgs(1, 1);

    try (Cursor cursor = getReadableDatabase().query(TABLE_NAME, selection, where, whereArgs, null, null, null, null)) {
      if (cursor == null || cursor.getCount() == 0) {
        return Collections.emptyList();
      }

      List<GroupId> results = new ArrayList<>(cursor.getCount());
      while (cursor.moveToNext()) {
        results.add(GroupId.parse(CursorUtil.requireString(cursor, GROUP_ID)));
      }

      return results;
    }
  }

  public void markDisplayAsStory(@NonNull GroupId groupId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(DISPLAY_AS_STORY, true);

    getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID + " = ?", SqlUtil.buildArgs(groupId.toString()));
  }

  public enum MemberSet {
    FULL_MEMBERS_INCLUDING_SELF(true, false),
    FULL_MEMBERS_EXCLUDING_SELF(false, false),
    FULL_MEMBERS_AND_PENDING_INCLUDING_SELF(true, true),
    FULL_MEMBERS_AND_PENDING_EXCLUDING_SELF(false, true);

    private final boolean includeSelf;
    private final boolean includePending;

    MemberSet(boolean includeSelf, boolean includePending) {
      this.includeSelf    = includeSelf;
      this.includePending = includePending;
    }
  }

  public enum MemberLevel {
    NOT_A_MEMBER(false),
    PENDING_MEMBER(false),
    REQUESTING_MEMBER(false),
    FULL_MEMBER(true),
    ADMINISTRATOR(true),
    LINKJOINING_MEMBER(false);//AA+

    private final boolean inGroup;

    MemberLevel(boolean inGroup){
      this.inGroup = inGroup;
    }

    public boolean isInGroup() {
      return inGroup;
    }
  }

  //AA+
  public static class GroupDataDescriptor {
    @JsonProperty
    String description;

    static public class AttributesNames
    {
      public final static String DESCRIPTION = "description";
    }

   static public final String EMPTY_SERIALISER = "{\"description\":\"\"}";

    public String getDescription()
    {
      return description;
    }

    static public Optional<GroupDataDescriptor> GroupDataDescriptorFrom(String jsonSerialised)
    {
      try {
        return Optional.of(JsonUtils.fromJson(jsonSerialised, GroupDataDescriptor.class));
      } catch (IOException ioException) {
        Log.e(TAG, ioException);
        return Optional.empty();
      }
    }

    public static void setGroupDescriptorProperty(@NonNull GroupId groupId, @NonNull String propertyName, @NonNull String propertyValue)
    {
      SQLiteDatabase db = SignalDatabase.groups().getWritableDatabase();
      //"UPDATE fences SET data = JSON_REPLACE(data, '$.%s', '%s') WHERE fid=%lu"
      String queryTemplate = "INSERT INTO groups (%s, %s) VALUES ('%s', " +
              " json_encode('{ \"%s\": \"%s\" " +
              "}'))";

      db.execSQL(String.format(Locale.getDefault(), queryTemplate, ID, GROUP_DATA, groupId, propertyName, propertyValue));
    }

    public static Optional<String> getGroupDescriptorProperty(@NonNull GroupId groupId, @NonNull String propertyName)
    {
      SQLiteDatabase db = SignalDatabase.groups().getReadableDatabase();
      String queryTemplate = "SELECT json_extract(group_data, '$.%s') FROM groups WHERE groupId = %s";

      Cursor cursor = null;

      try {
        cursor = db.query(TABLE_NAME, new String[] {String.format(Locale.getDefault(), "json_extract(group_data, '$.%s') AS %s", propertyName, propertyName)},
                          GROUP_ID + " = ?",
                          new String[] {groupId + ""},
                          null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
          return Optional.of(cursor.getString(cursor.getColumnIndexOrThrow(propertyName)));
        }

        return Optional.empty();
      } finally {
        if (cursor != null)
          cursor.close();
      }
    }

  }

  public static class GroupControlsDescriptor {
    private final int membersSize;
    private final GroupDatabase.PrivacyMode   privacyMode;
    private final GroupDatabase.DeliveryMode  deliveryMode;
    private final GroupDatabase.JoinMode      joinMode;
    private final RecipientDatabase.GroupPermission[] groupPermissions;
    private final GroupType groupType;
    private final int disappearingMessagesTimer;

    public GroupControlsDescriptor(GroupType groupType, int membersSize, PrivacyMode privacyMode, DeliveryMode deliveryMode, JoinMode joinMode, RecipientDatabase.GroupPermission[] groupPermissions, int disappearingMessagesTimer)
    {
      this.groupType    = groupType;
      this.membersSize  = membersSize;
      this.deliveryMode = deliveryMode;
      this.privacyMode  = privacyMode;
      this.joinMode     = joinMode;
      this.groupPermissions = groupPermissions;
      this.disappearingMessagesTimer = disappearingMessagesTimer;
    }

    public GroupType getGroupType() {
      return groupType;
    }

    public int getMembersSize()
    {
      return membersSize;
    }

    public PrivacyMode getPrivacyMode()
    {
      return privacyMode;
    }

    public DeliveryMode getDeliveryMode()
    {
      return deliveryMode;
    }

    public JoinMode getJoinMode()
    {
      return joinMode;
    }

    public RecipientDatabase.GroupPermission[] getGroupPermissions()
    {
      return groupPermissions;
    }

    public int getDisappearingMessagesTimer()
    {
      return disappearingMessagesTimer;
    }



    public static GroupControlsDescriptor getDefaultGroupControls()
    {
      RecipientDatabase.GroupPermission[] groupPermissions = new RecipientDatabase.GroupPermission[FencePermissions.INVALID.getValue()-1];
      groupPermissions[FencePermissions.PRESENTATION.getValue()-1]  = new RecipientDatabase.GroupPermission(FencePermissions.PRESENTATION, RecipientDatabase.PermissionBaseList.NONE, RecipientDatabase.PermissionListSemantics.values()[0]);
      groupPermissions[FencePermissions.MEMBERSHIP.getValue()-1]    = new RecipientDatabase.GroupPermission(FencePermissions.MEMBERSHIP, RecipientDatabase.PermissionBaseList.NONE, RecipientDatabase.PermissionListSemantics.values()[0]);
      groupPermissions[FencePermissions.MESSAGING.getValue()-1]  = new RecipientDatabase.GroupPermission(FencePermissions.MESSAGING, RecipientDatabase.PermissionBaseList.NONE, RecipientDatabase.PermissionListSemantics.values()[0]);
      groupPermissions[FencePermissions.ATTACHING.getValue()-1]    = new RecipientDatabase.GroupPermission(FencePermissions.ATTACHING, RecipientDatabase.PermissionBaseList.NONE, RecipientDatabase.PermissionListSemantics.values()[0]);
      groupPermissions[FencePermissions.CALLING.getValue()-1]       = new RecipientDatabase.GroupPermission(FencePermissions.CALLING, RecipientDatabase.PermissionBaseList.NONE, RecipientDatabase.PermissionListSemantics.values()[0]);
      return new GroupControlsDescriptor(GroupType.USER, 0, PrivacyMode.PUBLIC, DeliveryMode.MANY, JoinMode.OPEN, groupPermissions, 0);
    }
  }
  //
}
