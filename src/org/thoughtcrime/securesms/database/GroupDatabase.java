package org.thoughtcrime.securesms.database;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import com.annimon.stream.Stream;

import net.sqlcipher.database.SQLiteDatabase;

import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.PrivateGroupForTwoName;

import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.thoughtcrime.securesms.groups.GroupMessageProcessor.UserRecordsToEncodedUfsrvUidList;

public class GroupDatabase extends Database {

  public static final String DATABASE_UPDATE_ACTION = "org.thoughtcrime.securesms.database.GroupDatabase.UPDATE";

  @SuppressWarnings("unused")
  private static final String TAG = GroupDatabase.class.getSimpleName();

          static final String TABLE_NAME          = "groups";
  private static final String ID                  = "_id";
          static final String GROUP_ID            = "group_id";
  private static final String TITLE               = "title";
  private static final String MEMBERS             = "members";
  private static final String AVATAR              = "avatar";
  private static final String AVATAR_ID           = "avatar_id";
  private static final String AVATAR_KEY          = "avatar_key";
  private static final String AVATAR_CONTENT_TYPE = "avatar_content_type";
  private static final String AVATAR_RELAY        = "avatar_relay";
  private static final String AVATAR_DIGEST       = "avatar_digest";
  private static final String TIMESTAMP           = "timestamp";
  static final String ACTIVE                      = "active"; //- private
  private static final String MMS                 = "mms";

  //
  private static final String CNAME               = "cname";
  private static final String LONGITUDE           = "longitude";
  private static final String LATITUDE            = "latitude";
  private static final String MAXMEMBERS          = "maxmembers";
  private static final String TTL                 = "ttl";
  private static final String MODE                = "mode"; //should renamned to status
  private static final String FID                 = "fid";
  private static final String INVITED_MEMBERS     = "invited_members";
  private static final String BLOCKED_MEMBERS     = "blocked_members";
  public static final String AVATAR_UFID          = "avatar_ufid";
  private static final String FENCE_TYPE          = "fence_type";
  private static final String OWNER_UID           = "owner_uid";
  private static final String PRIVACY_MODE        = "privacy_mode";
  private static final String DELIVERY_MODE       = "delivery_mode";
  private static final String JOIN_MODE           = "join_mode";

  //these mode captures existential lifecycle events only. for example join syncs dont qualify
  public static final int    GROUP_MODE_DEVICELOCAL               = 0;  //strictly local to device. backend doesnt know of

  public static final int    GROUP_MODE_INVITATION                = 1;  //received an invitation to join. Not acknowledged
  public static final int    GROUP_MODE_GEOBASED_INVITE           = 2;  //invitation based on geo location for this user
  public static final int    GROUP_MODE_UNINVITATED               = 3;  //removed from peviously invited-to group

  public static final int    GROUP_MODE_JOIN_ACCEPTED             = 10; //server accepted join request (self-initiated) for this user
  public static final int    GROUP_MODE_INVITATION_JOIN_ACCEPTED  = 11; //server accepted request to join an invitation group for this user
  public static final int    GROUP_MODE_GEOBASED_JOIN             = 12; //server sent automatic join based on Geogroup
  public static final int    GROUP_MODE_JOIN_SYNCED               = 13; //waiting for join sync info from server (having previously issued join to sync view with server)
  public static final int    GROUP_MODE_MAKE_NOT_CONFIRMED        = 14; //waiting for new group creation confirmation

  public static final int    GROUP_MODE_LEAVE_ACCEPTED            = 20; //this user left
  public static final int    GROUP_MODE_LEAVE_GEO_BASED           = 21; //automatic leave based on geogroup roaming mode
  public static final int    GROUP_MODE_LEAVE_NOT_CONFIRMED       = 22;
  public static final int    GROUP_MODE_LEAVE_NOT_CONFIRMED_CLEANUP       = 23; //User requested group storage cleanup upon successful delee


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
    USER (2); //user create

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
          GROUP_ID + " TEXT UNIQUE, " + // unique
          TITLE + " TEXT, " +
          MEMBERS + " TEXT, " +
          AVATAR + " BLOB, " +
          AVATAR_ID + " INTEGER, " +
          AVATAR_KEY + " BLOB, " +
          AVATAR_CONTENT_TYPE + " TEXT, " +
          AVATAR_RELAY + " TEXT, " +
          AVATAR_DIGEST + " BLOB, " +
          TIMESTAMP + " INTEGER, " +
          ACTIVE + " INTEGER DEFAULT 1, "+
          MMS + " INTEGER DEFAULT 0, "+
          CNAME + " TEXT NOT NULL UNIQUE, " + //
          LONGITUDE + " DOUBLE, " +
          LATITUDE + " DOUBLE, " +
          MAXMEMBERS + " INTEGER, " +
          TTL + " INTEGER, " +
          MODE + " INTEGER, " +
          FID + " INTEGER, " +
          INVITED_MEMBERS + " TEXT, "+
          BLOCKED_MEMBERS + " TEXT, "+
          AVATAR_UFID  + " TEXT, "  + // ufsr uses string, not integer id
          FENCE_TYPE  + " INTEGER DEFAULT 2, "+    // 2 is user fence
          OWNER_UID  + " INTEGER DEFAULT 0, "+
          PRIVACY_MODE  + " INTEGER DEFAULT 0, "+ //0 public
          DELIVERY_MODE  + " INTEGER DEFAULT 0, "+ //0 many
          JOIN_MODE  + " INTEGER DEFAULT 0 "+//0 OPEN
          ");";

  public static final String[] CREATE_INDEXS = {
      "CREATE UNIQUE INDEX IF NOT EXISTS group_id_index ON " + TABLE_NAME + " (" + GROUP_ID + ");",
      "CREATE INDEX IF NOT EXISTS group_fid_index ON " + TABLE_NAME + " (" + FID + ");", //cannot be uniqueue as we can have several fid =0
      "CREATE UNIQUE INDEX IF NOT EXISTS group_cname_index ON " + TABLE_NAME + " (" + CNAME + ");",
  };

  private static final String[] GROUP_PROJECTION = {
          GROUP_ID, TITLE, MEMBERS, AVATAR, AVATAR_ID, AVATAR_KEY, AVATAR_CONTENT_TYPE, AVATAR_RELAY, AVATAR_DIGEST,
          TIMESTAMP, ACTIVE, MMS,
          //
          CNAME,
          LONGITUDE,
          LATITUDE,
          MAXMEMBERS,
          TTL,
          MODE,
          FID,
          INVITED_MEMBERS,
          BLOCKED_MEMBERS,
          AVATAR_UFID,
          FENCE_TYPE,
          OWNER_UID,
          PRIVACY_MODE,
          DELIVERY_MODE,
          JOIN_MODE
  };

  static final List<String> TYPED_GROUP_PROJECTION = Stream.of(GROUP_PROJECTION).map(columnName -> TABLE_NAME + "." + columnName).toList();

  public GroupDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public @Nullable GroupRecord getGroupByGroupId (String groupId) {
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                               new String[] {groupId},
                                                               null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();
    return record;
  }


  //
  public @Nullable GroupRecord getGroupByCname (String fcname)
  {
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, CNAME + " = ?",
            new String[] {fcname},
            null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();

    if (record==null) Log.d(TAG, "getGroupByCname: record was null for: "+fcname);

    return record;
  }

  //
  public boolean
  cleanUpGroup (@Nullable String groupId, long threadId)
  {
    databaseHelper.getReadableDatabase().beginTransaction();
    try
    {
      if (threadId > 0)
        DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).deleteConversation(threadId);
      if (groupId!=null)  deleteGroup(groupId, false); // in some instances we'd have an orphan group only reference in thread
      databaseHelper.getReadableDatabase().setTransactionSuccessful();
    }
    catch (Exception e)
    {
      databaseHelper.getReadableDatabase().endTransaction();
      Log.d(TAG, e.getMessage());
      return false;
    }

    //success
    databaseHelper.getReadableDatabase().endTransaction();

    notifyConversationListListeners();

    return true;
  }

  public boolean
  isGroupAvailableByCname (final String fcname, boolean flag_cleanup)
  {
    boolean isGroupExists = false;
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, CNAME + " = ? COLLATE NOCASE",
                                          new String[] {fcname}, null, null, null);
    try {
      if (cursor == null || !cursor.moveToNext()) {
        return isGroupExists;
      }

      String  groupId   = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID));
      boolean isActive  = cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1;
      long    fid       = cursor.getLong(cursor.getColumnIndexOrThrow(FID));
      int     mode      = cursor.getInt(cursor.getColumnIndexOrThrow(MODE));

      Recipient  recipient   = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(groupId), true);
      long        threadId   = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance()).getThreadIdIfExistsFor(recipient);

      //todo:slightly heavy handed: use some of he fetchd attributes to refine what needs to be done
      if (fid == 0|| !isActive || threadId==-1) {
        if (flag_cleanup) return cleanUpGroup(groupId, threadId);
      }

      isGroupExists = true;
    } finally {
      if (cursor!=null) cursor.close();
    }

    return isGroupExists;
  }

  //
  public @Nullable GroupRecord getGroupRecordByFid (long fid)
  {
    if (fid<=0) return null;

    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, FID + " = ?",
            new String[] {String.valueOf(fid)},
            null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();

    if (record==null) Log.e(TAG, "getGroupRecordByFid: ERROR: DATA INTEGRITY: Group Record was null for: "+fid);

    return record;
  }

  public Optional<GroupRecord> getGroup(String groupId) {
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                                    new String[] {groupId},
                                                                    null, null, null))
    {
      if (cursor != null && cursor.moveToNext()) {
        return getGroup(cursor);
      }

      return Optional.absent();
    }
  }

  Optional<GroupRecord> getGroup(Cursor cursor) {
    Reader reader = new Reader(cursor);
    return Optional.fromNullable(reader.getCurrent());
  }

  public boolean isUnknownGroup(String groupId) {
    return !getGroup(groupId).isPresent();
  }

  public Reader getGroupsFilteredByTitle(String constraint) {
    @SuppressLint("Recycle")
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, TITLE + " LIKE ?",
                                                               new String[]{"%" + constraint + "%"},
                                                               null, null, null);

    return new Reader(cursor);
  }

  public String getOrCreateGroupForMembers(List<Address> members, boolean mms) {
    Collections.sort(members);

    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {GROUP_ID},
                                                               MEMBERS + " = ? AND " + MMS + " = ?",
                                                               new String[] {Address.toSerializedList(members, ','), mms ? "1" : "0"},
                                                               null, null, null);
    try {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID));
      } else {
        String groupId = GroupUtil.getEncodedId(allocateGroupId(), mms);
        create(groupId, null, null, 0, 0);
        return groupId;
      }
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public Reader getGroups() {
    @SuppressLint("Recycle")
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, null, null, null, null, null);
    return new Reader(cursor);
  }

  public @NonNull List<Recipient> getGroupMembers(String groupId, boolean ignoreSelf) {
    return getGroupMembersByNumber(getCurrentMembers(groupId), ignoreSelf);
    //- abstracted the list generation
//    List<Address>   members     = getCurrentMembers(groupId);
//    List<Recipient> recipients  = new LinkedList<>();
//
//    for (Address member : members) {
//      if (!includeSelf && Util.isOwnNumber(context, member))
//        continue;
//
//      recipients.add(Recipient.from(context, member, false));
//    }
//
//    return RecipientFactory.getRecipientsFor(context, recipients, false);
  }

  public @NonNull List<Recipient> getGroupMembers(long fid, boolean ignoreSelf) {
    return getGroupMembersByNumber(getCurrentMembers(fid), ignoreSelf);
  }


  public @NonNull List<Recipient> getGroupMembersByNumber(List<Address> members, boolean ignoreSelf) {
    List<Recipient> recipients  = new LinkedList<>();

    for (Address member : members) {
      if (TextUtils.isEmpty(member.toString()) && ignoreSelf && Util.isOwnNumber(context, member)) continue; // isEmpty
        recipients.add(Recipient.from(context, member, false));
    }

    return recipients;
  }
  //

  //
  //generate Signal style Recipient object for the encoded group Id
  public Recipient getGroupRecipient (long fid)
  {
    String groupId=getGroupId(fid, null, false);
    if (groupId!=null) {
      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), false);

      return groupRecipient;
    }

    Log.e(TAG, String.format("getGroupRecipient: fid:'%d' RETURNED NULL RECIPIENT", fid));
    return null;
  }

  //
  public @NonNull List<Recipient> getGroupInvitedRecipientsByNumber (List<Address> members, boolean ignoreSelf) {
    String          localNumber = TextSecurePreferences.getLocalNumber(context);
    List<Recipient> recipients  = new LinkedList<>();

    for (Address member : members) {
      if (member.equals(localNumber) && ignoreSelf) continue;
      recipients.add(Recipient.from(context, member, false));
    }

    return recipients;
  }

  public @NonNull List<Recipient> getGroupInvitedMembers(@NonNull String groupId, boolean ignoreSelf) {
    return (getGroupInvitedRecipientsByNumber(getInvitedMembers(groupId), ignoreSelf));
  }
  //

  // changed return type to long + added extra params to original invited members
  public long create(@NonNull String groupId, String title, List<Address> members,  List<Address> invitedMembers,
                     SignalServiceAttachmentPointer avatar, String relay, String cname, double longitude,
                     double latitude, int mode, long fid, int maxMembers,
                     GroupType groupType, PrivacyMode privacyMode, DeliveryMode deliveryMode, JoinMode joinMode)// modes
  {
    Collections.sort(members);

    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, groupId);
    contentValues.put(TITLE, title);
    contentValues.put(MEMBERS, Address.toSerializedList(members, ','));
    //
    if (invitedMembers!=null)
    {
      contentValues.put(INVITED_MEMBERS, Address.toSerializedList(invitedMembers, ','));
      Log.d(TAG, String.format("create: Creating group id: '%s', group title:'%s', users count:'%d', INVITED:'%s'", groupId, title, members.size(), Address.toSerializedList(invitedMembers, ',')));
    }
    else
    {
      Log.d(TAG, String.format("create: Creating group id (NO INVITEES): '%s', group title:'%s', users count:'%d'", groupId, title, members.size()));
    }
    //

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orNull());
    }

    contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, System.currentTimeMillis());
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, GroupUtil.isMmsGroup(groupId));

    //
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

    long id=databaseHelper.getWritableDatabase().insert(TABLE_NAME, null, contentValues);

    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> {
      recipient.setName(title);
      recipient.setGroupAvatarId(avatar != null ? avatar.getId() : null, avatar!=null?avatar.getUfId():"");// second ufsrvaid
      recipient.setParticipants(Stream.of(members).map(memberAddress -> Recipient.from(context, memberAddress, true)).toList());
    });

    if (id!=-1) notifyConversationListListeners();
    return id;
  }


  //  extra params to original
  public long  create(@NonNull String groupId, String title, List<Address> members,
                     SignalServiceAttachmentPointer avatar, String relay, String cname,
                     double longitude, double latitude, int mode, long fid, int maxMembers,
                     GroupType groupType, PrivacyMode privacyMode, DeliveryMode deliveryMode, JoinMode joinMode)
  {
    return create(groupId, title, members, null,
            avatar, relay, cname, longitude, latitude, mode, fid, maxMembers, groupType, privacyMode, deliveryMode, joinMode);
  }

  // utilises the stable protobuf container for FenceRecord
  public void create(@NonNull String groupId,
                     FenceRecord fenceRecord,
                     SignalServiceAttachmentPointer avatar,
                     int mode,
                     long timestamp)
  {
    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, groupId);

    contentValues.put(TITLE, fenceRecord.getFname());

    List<Address> members= GroupMessageProcessor.UserRecordsToAddressList(fenceRecord.getMembersList(), Optional.<List<String>>absent());
//    contentValues.put(MEMBERS, Util.join(members, ","));
    contentValues.put(MEMBERS, Address.toSerializedList(members, ','));

    List<String>membersInvited  = UserRecordsToEncodedUfsrvUidList(fenceRecord.getInvitedMembersList(), Optional.<List<String>>absent());
    if (membersInvited!=null) {
      contentValues.put(INVITED_MEMBERS, Util.join(membersInvited, ","));
      Log.d(TAG, String.format("create: Creating group id: '%s', group title:'%s', users count:'%d', INVITED:'%s'", groupId, fenceRecord.getFname(), members.size(), Util.join(membersInvited, ",")));
    }else {
      Log.d(TAG, String.format("create: Creating group id (NO INVITEES): '%s', group title:'%s', users count:'%d'", groupId, fenceRecord.getFname(), members.size()));
    }
    //

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orNull());
    }

    //contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, timestamp);
    contentValues.put(ACTIVE, 1);
    contentValues.put(MMS, GroupUtil.isMmsGroup(groupId));

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

    databaseHelper.getWritableDatabase().insert(TABLE_NAME, null, contentValues);

//    Address   address   = Address.fromSerialized(groupId);
//    Recipient recipient = Recipient.from(context, Address.fromSerialized(groupId), false);
//
//    recipient.setName(fenceRecord.getFname());
//    if (avatar != null) recipient.setContactPhoto(new GroupRecordContactPhoto(address, avatar.getId(), null));
//    recipient.setParticipants(Stream.of(members).map(memberAddress -> Recipient.from(context, memberAddress, true)).toList());

    // note RecipientsDatabase only updated when confirmation is received from server
    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> {
      recipient.setName(fenceRecord.getFname());
      recipient.setGroupAvatarId(avatar != null ? avatar.getId() : null, avatar!=null?avatar.getUfId():"");// second ufsrvid
      recipient.setParticipants(Stream.of(members).map(memberAddress -> Recipient.from(context, memberAddress, true)).toList());
    });

    notifyConversationListListeners();
  }
  //


  public void update(String  groupId, String title, SignalServiceAttachmentPointer avatar) {
    ContentValues contentValues = new ContentValues();
    if (title != null) contentValues.put(TITLE, title);

    if (avatar != null) {
      Log.e(TAG, String.format("update (id:'%s): Updating avatar and notifying listeners...", avatar.getUfId()));
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_KEY, avatar.getKey());
      contentValues.put(AVATAR_DIGEST, avatar.getDigest().orNull());
      contentValues.put(AVATAR_UFID, avatar.getUfId()); //
    }

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues,
                                                GROUP_ID + " = ?",
                                                new String[] {groupId});

    //
    DatabaseFactory.getRecipientDatabase(context).setAvatarUfId(Recipient.from(context, Address.fromSerialized(groupId), false),
                                                                avatar.getUfId());
    notifyConversationListListeners();
  }

  public void updateTitle(String groupId, String title) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {groupId});

    Recipient recipient = Recipient.from(context, Address.fromSerialized(groupId), false);
    recipient.setName(title);
//    notifyConversationListListeners();
  }

  //

  public void updateTitle(long fid, String title) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {fid+""});

    Recipient recipient = Recipient.fromFid(context, fid, false);
    recipient.setName(title);
  }

  public void updateGroupId(long fid, byte[] id) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, GroupUtil.getEncodedId(id, false));
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});
  }
//

//
  public void updateCname(long fid, String cname) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(CNAME, cname);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

//    notifyDatabaseListeners();

  }

  public void updateCname(String groupId, String cname) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(CNAME, cname);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID_WHERE,
            new String[] {groupId});

//    notifyDatabaseListeners();

  }

  //
  //Given the unique constraint on cname, it saves to be judicious with updates to this field
  public void updateAndResolveCname (long fid, String cname)
  {
    reindexCname();

    GroupRecord groupRecord= getGroupByCname(cname);

    if (groupRecord!=null) {
      //found existing record with the same cname...
      Log.e(TAG, String.format("updateAndresolveCname (fid:'%d', cname:'%s', groupRecord.fid:'%d'): ERROR: DATA INTEGRITY: FOUND EXISTING RECORD WITH THE GIVEN CNAME...", fid, cname, groupRecord.getFid()));

      if (!groupRecord.isActive()) {
        //todo: we may need to be selective about what key we use as per below: fid, groupId...
        Log.e(TAG, String.format("updateAndresolveCname (fid:'%d', cname:'%s', groupRecord.fid:'%d' grouRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP INACTIVE: DELETING...", fid, cname, groupRecord.getFid(), groupRecord.getEncodedId()));
        deleteGroup(groupRecord.getEncodedId());
      } else {
        //todo: check if group is valid eg. fid >0 also do a synchronous call with the server
        String newCname=cname+ System.currentTimeMillis();

        if (groupRecord.getFid()>0) {
          Log.e(TAG, String.format("updateAndresolveCname (cname:'%s', newCname:'%s', groupRecord.fid:'%d' groupRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP ACTIVE: UPDATING USING _FID_ AS KEY...", cname, newCname, groupRecord.getFid(), groupRecord.getEncodedId()));
          updateCname(groupRecord.getFid(), newCname);
        } else if (groupRecord.getEncodedId()!=null) {
          Log.e(TAG, String.format("updateAndresolveCname (cname:'%s', newCname:'%s', groupRecord.fid:'%d' groupRecord.groupId:'%s'): ERROR: DATA INTEGRITY: GROUP ACTIVE: UPDATING USING _GROUPID_ AS KEY...", cname, newCname, groupRecord.getFid(), groupRecord.getEncodedId()));
          updateCname(groupRecord.getEncodedId(), newCname);
        } else {
          Log.e(TAG, String.format("updateAndresolveCname (fid:'%d', cname:'%s', newCname:'%s', groupRecord.fid:'%d' grouRecord.groupId:'%s'): ERROR: DATA INTEGRITY: UNABLE TO DELETE GROUP: NO VALID KEY FOUND...", fid, cname, newCname, groupRecord.getFid(), groupRecord.getEncodedId()));
        }
      }
    }
    else
    {
      //no duplicate entry with the same cname...
      updateCname(fid, cname);
    }

  }

  private void deleteGroup(String groupId)
  {
    deleteGroup(groupId, true);
  }

  //
  private void deleteGroup(String groupId, boolean invalidate_cache)
  {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, GROUP_ID_WHERE, new String[] {groupId});

    if (invalidate_cache)
    {
//      Recipient.clearCache(context);
      notifyConversationListListeners();
    }
  }

  //this is necessary sometimes when the index gets corrupted we just rebuild it
  private void reindexCname()
  {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL(String.format("DROP INDEX group_cname_index;"));
    db.execSQL(String.format("CREATE UNIQUE INDEX IF NOT EXISTS group_cname_index ON %s ( %s );", TABLE_NAME, CNAME));
    db.execSQL(String.format("REINDEX group_cname_index;"));
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
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, CNAME +  " = ?",
            new String[] {fcname});
  }

  //
  public void markGroupMode (long fid, int mode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(MODE, mode);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

//    notifyDatabaseListeners();
  }

  public int getGroupMode (long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MODE},
                                                          FID + " = ?",
                                                          new String[] {fid+""},
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
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupOwnerUid (long fid, long ownerUid) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(OWNER_UID, ownerUid);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupPrivacyMode (long fid, PrivacyMode privacyMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(PRIVACY_MODE, privacyMode.getValue());
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupType (long fid, GroupType groupType) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(FENCE_TYPE, groupType.getValue());
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupDeliveryMode (long fid, DeliveryMode deliveryModeMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(DELIVERY_MODE, deliveryModeMode.getValue());
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateGroupJoinMode (long fid, JoinMode joinMode) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(JOIN_MODE, joinMode.getValue());
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, FID +  " = ?",
            new String[] {Long.valueOf(fid).toString()});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }
  //

  //
  // have we received invitation to join this group
  public boolean isGroupInvitationPending(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MODE},
              FID + " = ?",
              new String[] {Long.valueOf(fid).toString()},
              null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return (cursor.getInt(cursor.getColumnIndexOrThrow(MODE))==GROUP_MODE_INVITATION);
      }

      return false;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //
  public void updateFenceLocation(String fcname, long fid, double longitude, double latitude) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(LONGITUDE, longitude);
    contentValues.put(LATITUDE, latitude);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, CNAME +  " = ?",
            new String[] {fcname});

    //Recipient.clearCache(context);
//    notifyDatabaseListeners();
  }

  public void updateAvatar(String groupId, Bitmap avatar) {
    updateAvatar(groupId, BitmapUtil.toByteArray(avatar), "0");//todo: update ufsrvId value for uploaded image
  }

  public void updateAvatar(String groupId, byte[] avatar, String ufsrvId) {
    long avatarId;

    if (avatar != null) avatarId = Math.abs(new SecureRandom().nextLong());
    else                avatarId = 0;


    ContentValues contentValues = new ContentValues(2);
    contentValues.put(AVATAR, avatar);
    contentValues.put(AVATAR_ID, avatarId);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {groupId});

    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> recipient.setGroupAvatarId(avatarId == 0 ? null : avatarId, ufsrvId));
  }

  public void updateMembers(String groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Address.toSerializedList(members, ','));
    contents.put(ACTIVE, 1);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[] {groupId});

    // todo: propably overkill because not in use. added from https://github.com/signalapp/Signal-Android/commit/d42c9b5dbc1276f18a29da5a024f7778e11363c9
    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> {
      recipient.setParticipants(Stream.of(members).map(a -> Recipient.from(context, a, false)).toList());
    });
  }

  // wholesale update
  public void updateInvitedMembers(String groupId, List<Address> members) {
    Collections.sort(members);

    ContentValues contents = new ContentValues();
    contents.put(INVITED_MEMBERS, Address.toSerializedList(members, ','));

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[] {groupId});
  }

  // Handles one member at a time for now
  public void updateInvitedMembers(long fid, List<Address> affectedMembers, MembershipUpdateMode updateMode) {
    List<Address> existingMembers=getInvitedMembers(fid);
    if (existingMembers.contains(affectedMembers.get(0))) {
      if (updateMode==MembershipUpdateMode.ADD_MEMBER) {
        Log.e(TAG, String.format("updateInvitedMembers: DATA ERROR: CURRENT INVITED LIST (size:'%d') ALREADY HAS '%s' in it", existingMembers.size(), affectedMembers.get(0).serialize()));
        return;
      }

      existingMembers.remove(affectedMembers.get(0));
    }else {
      if (updateMode==MembershipUpdateMode.ADD_MEMBER) {
        Log.d(TAG, String.format("updateInvitedMembers (size:'%d'): Adding invited member  '%s'", existingMembers.size(), affectedMembers.get(0).serialize()));
        existingMembers.add(affectedMembers.get(0));
      }else {
        Log.e(TAG, String.format("updateInvitedMembers (size;'%d'): DATA ERROR: MEMBER NOT IN LIST: CANNOT REMOVE  '%s' FROM INVITED LIST", existingMembers.size(), affectedMembers.get(0)));
        return;
      }
    }

    ContentValues contents = new ContentValues();
    contents.put(INVITED_MEMBERS, Address.toSerializedList(existingMembers, ','));

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, FID + " = ?",
            new String[] {String.valueOf(fid)});

//    Recipient.clearCache(context);
//    notifyDatabaseListeners();
    notifyConversationListListeners();
  }

  public void remove(String groupId, Address source) {
    List<Address> currentMembers = getCurrentMembers(groupId);
    currentMembers.remove(source);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Address.toSerializedList(currentMembers, ','));

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[] {groupId});

    // todo: propably overkill because not in use. added from https://github.com/signalapp/Signal-Android/commit/d42c9b5dbc1276f18a29da5a024f7778e11363c9
    Recipient.applyCached(Address.fromSerialized(groupId), recipient -> {
      List<Recipient> current = recipient.getParticipants();
      Recipient       removal = Recipient.from(context, source, false);

      current.remove(removal);
      recipient.setParticipants(current);
    });
  }

  //
  //move earth and heavens and dont comeback without the damned id
  public String getGroupId (FenceRecord fenceRecord, boolean allocate_if_none)
  {
    return getGroupId (fenceRecord.getFid(), fenceRecord.getCname(), allocate_if_none);
  }

  //
  //move earth and heavens and dont comeback without the damned id
  //todo: pass 'boolean mms' instead of default false: GroupUtil.getEncodedId(allocateGroupId(), false)
  public String getGroupId (long fid, String cname, boolean allocate_if_none)
  {
    String      id          = null;
    GroupRecord groupRecord = null;

    if (fid > 0 &&
        (groupRecord=getGroupRecordByFid(fid))!=null)
      id = groupRecord.getEncodedId();
    else if (!TextUtils.isEmpty(cname) &&
            ((groupRecord= getGroupByCname(cname))!=null))
      id = groupRecord.getEncodedId();
    else if (allocate_if_none)
    {
      id = GroupUtil.getEncodedId(allocateGroupId(), false);
//      id = allocateGroupId();
    }

    return id;

//    byte[] id=null;
//
//    if (fid > 0)
//      id = getGroupRecordByFid(fid).getEncodedId();
//    else if (!TextUtils.isEmpty(cname))
//      id = getGroupByCname(cname).getEncodedId();
//    else if (allocate_if_none)  id = allocateGroupId();
//
//    return id;
  }
//

  //
  //move earth and heavens and dont comeback without the damned id
  public String getEncodedGroupId (long fid, String cname, boolean allocate_if_none)
  {
    String id=null;
    boolean allocated=false;

    if (fid > 0)
      id = getGroupRecordByFid(fid).getEncodedId();
    else if (!TextUtils.isEmpty(cname))
      id = getGroupByCname(cname).getEncodedId();
    else if (allocate_if_none)
    {
      id = GroupUtil.getEncodedId(allocateGroupId(), false);
      allocated = true;
    }

    //Log.d(TAG, String.format(">> getEncodedGroupId: groupId: '%s'. Allocated:'%b'", id, allocated));

    return id;
  }
  //

  // group members(using their numbers) saved as one string, ',' seperated
  public  List<Address> getCurrentMembers(String groupId) { // public
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                          GROUP_ID + " = ?",
                                                          new String[] {groupId},
                                                          null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return Address.fromSerializedList(serializedMembers, ',');
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  //
  public  List<Address> getCurrentMembers(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
              FID + " = ?",
              new String[] {fid+""},
              null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS));
        return Address.fromSerializedList(serializedMembers, ',');
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  public long getOwnerUserId (long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {OWNER_UID},
                                                          FID + " = ?",
                                                          new String[] {fid+""},
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

  //
  // group members(using their numbers) saved as one string, ',' seperated
  public long getFid(byte[] id) {
    return getFid(GroupUtil.getEncodedId(id, false));
  }

  public long getFid(String groupId) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {FID},
              GROUP_ID + " = ?",
              new String[] {groupId},
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

  //

  class PrivateGroupForTwoQueryDescriptor
  {
    String       groupId;
    List<String> members;
    List<String> membersInvited;
    PrivacyMode  privacyMode;
    int          maxMembers;

    public PrivateGroupForTwoQueryDescriptor (String groupId, List<String> members, List<String> membersInvited, PrivacyMode  privacyMode, int maxMembers)
    {
      this.groupId         = groupId;
      this.members         = members;
      this.membersInvited  = membersInvited;
      this.privacyMode     = privacyMode;
      this.maxMembers      = maxMembers;
    }


    public String getGroupId ()
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
  }

  private PrivateGroupForTwoQueryDescriptor getPrivateGroupForTwoDescriptor (String groupId)
  {
    Cursor cursor = null;

    try {
    cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {FID, PRIVACY_MODE, INVITED_MEMBERS, MEMBERS, MAXMEMBERS},
                                                        GROUP_ID + " = ?",
                                                        new String[] {groupId},
                                                        null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        return new PrivateGroupForTwoQueryDescriptor(groupId,
                                                     Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ","),
                                                     Util.split(cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS)), ","),
                                                     PrivacyMode.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PRIVACY_MODE))],
                                                     cursor.getInt(cursor.getColumnIndexOrThrow(MAXMEMBERS)));
      }

      return null;
    } finally {
      if (cursor != null) cursor.close();
    }

  }

  public boolean
  isPrivateGroupForTwo (String groupId)
  {
    PrivateGroupForTwoQueryDescriptor result = getPrivateGroupForTwoDescriptor(groupId);
    if (result != null) {
      return (result.getPrivacyMode().equals(PrivacyMode.PRIVATE) &&
              (((result.getMaxMembers()==2) ||
                      (result.getMembers().size()==2 || (result.getMembers().size()==1 && result.getMembersInvited().size()==1)))));
    }

    return false;

  }

  public Optional <Object>
  isPrivateGroupForTwoWithStyliser (String groupId, PrivateGroupForTwoName.PrivateGroupForTwoTitleStyliser titleStyliser)
  {
    PrivateGroupForTwoQueryDescriptor result = getPrivateGroupForTwoDescriptor(groupId);
    if (result != null) {
      if (result.getPrivacyMode().equals(PrivacyMode.PRIVATE) &&
              (((result.getMaxMembers()==2) ||
                      (result.getMembers().size()==2 || (result.getMembers().size()==1 && result.getMembersInvited().size()==1))))) {
        return Optional.fromNullable(titleStyliser.formatTitle(groupId, result.getMembers(), result.getMembersInvited()));
      }
    }

    return Optional.absent();
  }

  public boolean
  isPrivateGroupForTwoReady (String groupId)
  {
    PrivateGroupForTwoQueryDescriptor result = getPrivateGroupForTwoDescriptor(groupId);
    if (result != null) {
      return  (result.getPrivacyMode().equals(PrivacyMode.PRIVATE) && result.getMaxMembers() == 2 && result.getMembers().size() == 2);
    }

    return false;

  }

  public List<Long> getForTwoGroups (@NonNull Recipient recipient, @NonNull Recipient recipientOther)
  {
    Reader reader        = getGroups();
    LinkedList<Long>    groups = new LinkedList<>();

    GroupDatabase.GroupRecord groupRecord;

    while ((groupRecord = reader.getNext()) != null) {
      if (groupRecord.active &&
          groupRecord.getMaxmembers()==2 &&
          groupRecord.getPrivacyMode()==PrivacyMode.PRIVATE.getValue())
      {
        List<Address> members          = groupRecord.getMembers();
        List<Address> membersInvited   = groupRecord.getMembersInvited();
        if ((members.contains(recipient.getAddress()) || membersInvited.contains(recipient.getAddress())) &&
            (members.contains(recipientOther.getAddress()) || membersInvited.contains(recipientOther.getAddress()))) groups.add(groupRecord.fid);
      }
    }

    reader.close();

    return groups;
  }

  public Optional<Address> privateGroupForTwoGetOther (String groupId, Address thisAddress, boolean isReadyOnly)
  {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {FID, PRIVACY_MODE, INVITED_MEMBERS, MEMBERS, MAXMEMBERS},
                                                          GROUP_ID + " = ?",
                                                          new String[] {groupId},
                                                          null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        List<String> members          = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ",");
        List<String> membersInvited   = Util.split(cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS)), ",");
        PrivacyMode  privacyMode      = PrivacyMode.values()[cursor.getInt(cursor.getColumnIndexOrThrow(PRIVACY_MODE))];
        int          maxMembers       = cursor.getInt(cursor.getColumnIndexOrThrow(MAXMEMBERS));

        if (privacyMode.equals(PrivacyMode.PRIVATE) && maxMembers==2) {
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

    return Optional.absent();
  }

  public boolean isInvitation(long fid) {
    GroupRecord record = getGroupRecordByFid(fid);
    return record != null && record.mode==GROUP_MODE_INVITATION;
  }

  public Cursor getInvitedGroupsList ()
  {
    //SELECT * FROM threads t INNER JOIN groups g ON threads.ufsrv_fid = groups.fid where g.mode=?"
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, MODE + " = ?",
            new String[] {String.valueOf(GROUP_MODE_INVITATION)},
            null, null, FID + " ASC");

    return cursor;
  }

  public int getInvitationListCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
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

  // group members(using their numbers) saved as one string, ',' seperated
  private List<Address> getInvitedMembers(@NonNull String groupId) {
    Cursor cursor = null;

    Log.d(TAG, ">> getInvitedMembers: getting inited members for GROUPID: "+groupId);
    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {INVITED_MEMBERS},
              GROUP_ID + " = ?",
              new String[] {groupId},
              null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS));
        if (TextUtils.isEmpty(serializedMembers)) return new  LinkedList<>();
        return Address.fromSerializedList(serializedMembers, ',');
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //
  public List<Address> getInvitedMembers(long fid) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {INVITED_MEMBERS},
              FID + " = ?",
              new String[] {String.valueOf(fid)},
              null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String serializedMembers = cursor.getString(cursor.getColumnIndexOrThrow(INVITED_MEMBERS));
        if (TextUtils.isEmpty(serializedMembers)) return new  LinkedList<>();
        return Address.fromSerializedList(serializedMembers, ',');
      }

      return new LinkedList<>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }
  //

  //
  public GroupRecord getGroupRecordfromThreadId (long threadId)
  {
    ThreadDatabase    threadDatabase    = DatabaseFactory.getThreadDatabase(ApplicationContext.getInstance());
    long              fid               = threadDatabase.getFidForThreadId(threadId);

    if (fid>0)
    {
      return getGroupRecordByFid(fid);
    }

    Log.e(TAG, String.format("getGroupRecordfromThreadId: COULD NOT LOCATE THRED RECORD FOR threadid :'%d'. NO VALID FID FOUND", threadId));
    return null;
  }
  //

  public boolean isActive(String groupId) {
    Optional<GroupRecord> record = getGroup(groupId);
    return record.isPresent() && record.get().isActive();
  }

  public void setActive(String groupId, boolean active) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(ACTIVE, active ? 1 : 0);
    database.update(TABLE_NAME, values, GROUP_ID + " = ?", new String[] {groupId});
  }


  public byte[] allocateGroupId() {
    byte[] groupId = new byte[16];
    new SecureRandom().nextBytes(groupId);
    return groupId;
  }

  public static class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public @Nullable GroupRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

      public @Nullable GroupRecord getCurrent() {
        if (cursor == null || cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)) == null) {
          return null;
        }

      return new GroupRecord(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)),
                             cursor.getString(cursor.getColumnIndexOrThrow(TITLE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR)),
                             cursor.getLong(cursor.getColumnIndexOrThrow(AVATAR_ID)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_KEY)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_CONTENT_TYPE)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_DIGEST)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(MMS)) == 1,
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_RELAY)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1,
              //
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
                              cursor.getLong(cursor.getColumnIndexOrThrow(OWNER_UID))
                              //
                            );
    }

    @Override
    public void close() {
      if (this.cursor != null)
        this.cursor.close();
    }
  }

  public static class GroupRecord {

    private final String       id;
    private final String       title;
    private final List<Address> members;
    private final byte[]       avatar;
    private final long         avatarId;
    private final byte[]       avatarKey;
    private final byte[]       avatarDigest;
    private final String       avatarContentType;
    private final String       relay;
    private final boolean      active;
    private final boolean       mms;

    //
    private final String        cname;
    private final double        longitude;
    private final double        latitude;
    private final int           maxmembers;
    private final long          ttl;
    private final int           mode;
    private final long          fid;
    private final String        avatarUfId;
    private final List<Address>        membersJoined;
    private final int           type;
    private final int           privacyMode;
    private final int           deliveryType;
    private final int           joinMode;
    private final List<Address>  membersInvited;
    private  long               eid;//this is currently not saved in this db
    private long                ownerUserId;

    public GroupRecord(String id, String title, String members, byte[] avatar,
                       long avatarId, byte[] avatarKey, String avatarContentType, byte[] avatarDigest, boolean mms,
                       String relay, boolean active, String cname, double longitude, double latitude, int maxmembers,
                       long ttl, int mode, int type, int privacy_mode, int deliveryType, int joinMode, long fid, String avatarUfId, String membersInvited, long ownerUserId)
    {
      this.id                = id;
      this.title             = title;
      if (!TextUtils.isEmpty(members))  this.members=Address.fromSerializedList(members, ',');
      else                              this.members=new LinkedList<>();
      this.avatar            = avatar;
      this.avatarId          = avatarId;
      this.avatarKey         = avatarKey;
      this.avatarContentType = avatarContentType;
      this.avatarDigest      = avatarDigest;
      this.relay             = relay;
      this.active            = active;
      this.mms               = mms;

      //
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
      if (members!=null)  this.membersJoined=Address.fromSerializedList(members, ',');
      else                this.membersJoined=new LinkedList<>();
      if (membersInvited!=null) this.membersInvited=Address.fromSerializedList(membersInvited, ',');
      else                      this.membersInvited=new LinkedList<>();
      this.ownerUserId        = ownerUserId;
      //
    }

    //
    public byte[] getId() {
      try {
        return GroupUtil.getDecodedId(id);
      } catch (IOException ioe) {
        throw new AssertionError(ioe);
      }
    }

    public String getEncodedId() {
      return id;
    }

    public String getTitle() {
      return title;
    }

    public List<Address> getMembers() {
      return members;
    }

    public byte[] getAvatar() {
      return avatar;
    }

    public long getAvatarId() {
      return avatarId;
    }

    public byte[] getAvatarKey() {
      return avatarKey;
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

    public int getMode () {return mode;}

    public int getType  ()
    {
      return type;
    }

    public int getDeliveryType ()
    {
      return deliveryType;
    }

    public int getPrivacyMode ()
    {
      return privacyMode;
    }

    public int getJoinMode ()
    {
      return joinMode;
    }

    public int getMaxmembers ()
    {
      return maxmembers;
    }

    public long getTtl ()
    {
      return ttl;
    }

    public  long getOwnerUserId()
    {
      return ownerUserId;
    }

    //todo: this is temporary treatment
    public void setEid (long eid)
    {
      this.eid = eid;
    }

    public long getEid ()
    {
      return eid;
    }
  }
}
