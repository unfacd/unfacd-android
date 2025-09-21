package org.thoughtcrime.securesms.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import androidx.core.content.contentValuesOf
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.unfacd.android.ApplicationContext
import com.unfacd.android.data.json.JsonEntityProfile
import com.unfacd.android.fence.FenceDescriptor
import com.unfacd.android.fence.FencePermissions
import com.unfacd.android.locallyaddressable.LocallyAddressable
import com.unfacd.android.locallyaddressable.LocallyAddressableGroup
import com.unfacd.android.locallyaddressable.LocallyAddressablePhoneNumber
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid
import com.unfacd.android.locallyaddressable.LocallyAddressableUndefined
import com.unfacd.android.ufsrvuid.RecipientUfsrvId
import com.unfacd.android.ufsrvuid.UfsrvUid
import net.zetetic.database.sqlcipher.SQLiteConstraintException
import org.signal.core.util.Bitmask
import org.signal.core.util.CursorUtil
import org.signal.core.util.SqlUtil
import org.signal.core.util.logging.Log
import org.signal.core.util.optionalBlob
import org.signal.core.util.optionalBoolean
import org.signal.core.util.optionalInt
import org.signal.core.util.optionalString
import org.signal.core.util.or
import org.signal.core.util.requireBlob
import org.signal.core.util.requireBoolean
import org.signal.core.util.requireInt
import org.signal.core.util.requireLong
import org.signal.core.util.requireNonNullString
import org.signal.core.util.requireString
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.util.Pair
import org.signal.libsignal.zkgroup.InvalidInputException
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredential
import org.signal.storageservice.protos.groups.local.DecryptedGroup
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.badges.Badges.toDatabaseBadge
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.color.MaterialColor
import org.thoughtcrime.securesms.color.MaterialColor.UnknownColorException
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.colors.ChatColors.Companion.forChatColor
import org.thoughtcrime.securesms.conversation.colors.ChatColors.Id.Companion.forLongValue
import org.thoughtcrime.securesms.conversation.colors.ChatColorsMapper.getChatColors
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.GroupDatabase.MembershipUpdateMode
import org.thoughtcrime.securesms.database.IdentityDatabase.VerifiedStatus
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.distributionLists
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.groups
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.identities
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.messageLog
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.notificationProfiles
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.reactions
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.runPostSuccessfulTransaction
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.sessions
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.storySends
import org.thoughtcrime.securesms.database.SignalDatabase.Companion.threads
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BadgeList
import org.thoughtcrime.securesms.database.model.databaseprotos.ChatColor
import org.thoughtcrime.securesms.database.model.databaseprotos.DeviceLastResetTime
import org.thoughtcrime.securesms.database.model.databaseprotos.ProfileKeyCredentialColumnData
import org.thoughtcrime.securesms.database.model.databaseprotos.RecipientExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.Wallpaper
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.groups.BadGroupIdException
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupId.V1
import org.thoughtcrime.securesms.groups.GroupId.V2
import org.thoughtcrime.securesms.groups.v2.ProfileKeySet
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob
import org.thoughtcrime.securesms.jobs.RecipientChangedNumberJob
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.profiles.AvatarHelper
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.LiveRecipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.Recipient.RecipientType
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.storage.StorageRecordUpdate
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.storage.StorageSyncModels
import org.thoughtcrime.securesms.util.Base64
import org.thoughtcrime.securesms.util.DelimiterUtil
import org.thoughtcrime.securesms.util.GroupUtil
import org.thoughtcrime.securesms.util.IdentityUtil
import org.thoughtcrime.securesms.util.ProfileUtil
import org.thoughtcrime.securesms.util.StringUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import org.thoughtcrime.securesms.wallpaper.ChatWallpaperFactory
import org.thoughtcrime.securesms.wallpaper.WallpaperStorage
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile
import org.whispersystems.signalservice.api.push.ACI
import org.whispersystems.signalservice.api.push.ContactTokenDetails
import org.whispersystems.signalservice.api.push.PNI
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.storage.SignalAccountRecord
import org.whispersystems.signalservice.api.storage.SignalContactRecord
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record
import org.whispersystems.signalservice.api.storage.SignalGroupV2Record
import org.whispersystems.signalservice.api.storage.StorageId
import java.io.Closeable
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.LinkedList
import java.util.Objects
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.math.max

open class RecipientDatabase(context: Context, databaseHelper: SignalDatabase) : Database(context, databaseHelper) {

  companion object {
    private val TAG = Log.tag(RecipientDatabase::class.java)

    const val TABLE_NAME = "recipient"

    const val ID = "_id"
    private const val SERVICE_ID = "uuid"
    private const val PNI_COLUMN = "pni"
    private const val USERNAME = "username"
    const val PHONE = "phone"
    const val EMAIL = "email"
    const val GROUP_ID = "group_id"
    const val DISTRIBUTION_LIST_ID = "distribution_list_id"
    const val GROUP_TYPE = "group_type"
    const val BLOCKED = "blocked"
    private const val MESSAGE_RINGTONE = "message_ringtone"
    private const val MESSAGE_VIBRATE = "message_vibrate"
    private const val CALL_RINGTONE = "call_ringtone"
    private const val CALL_VIBRATE = "call_vibrate"
    private const val NOTIFICATION_CHANNEL = "notification_channel"
    private const val MUTE_UNTIL = "mute_until"
    private const val AVATAR_COLOR = "color"
    private const val SEEN_INVITE_REMINDER = "seen_invite_reminder"
    private const val DEFAULT_SUBSCRIPTION_ID = "default_subscription_id"
    private const val MESSAGE_EXPIRATION_TIME = "message_expiration_time"
    const val REGISTERED = "registered"
    const val SYSTEM_JOINED_NAME = "system_display_name"
    const val SYSTEM_FAMILY_NAME = "system_family_name"
    const val SYSTEM_GIVEN_NAME = "system_given_name"
    private const val SYSTEM_PHOTO_URI = "system_photo_uri"
    const val SYSTEM_PHONE_TYPE = "system_phone_type"
    const val SYSTEM_PHONE_LABEL = "system_phone_label"
    private const val SYSTEM_CONTACT_URI = "system_contact_uri"
    private const val SYSTEM_INFO_PENDING = "system_info_pending"
    private const val PROFILE_KEY = "profile_key"
    private const val PROFILE_KEY_CREDENTIAL = "profile_key_credential"
    private const val SIGNAL_PROFILE_AVATAR = "signal_profile_avatar"
    const val PROFILE_SHARING = "profile_sharing"
    private const val LAST_PROFILE_FETCH = "last_profile_fetch"
    private const val UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode"
    const val FORCE_SMS_SELECTION = "force_sms_selection"
    private const val CAPABILITIES = "capabilities"
    const val STORAGE_SERVICE_ID = "storage_service_key"
    private const val PROFILE_GIVEN_NAME = "signal_profile_name"
    private const val PROFILE_FAMILY_NAME = "profile_family_name"
    private const val PROFILE_JOINED_NAME = "profile_joined_name"
    private const val MENTION_SETTING = "mention_setting"
    private const val STORAGE_PROTO = "storage_proto"
    private const val LAST_SESSION_RESET = "last_session_reset"
    private const val WALLPAPER = "wallpaper"
    private const val WALLPAPER_URI = "wallpaper_file"
    const val ABOUT = "about"
    const val ABOUT_EMOJI = "about_emoji"
    private const val EXTRAS = "extras"
    private const val GROUPS_IN_COMMON = "groups_in_common"
    private const val CHAT_COLORS = "chat_colors"
    private const val CUSTOM_CHAT_COLORS_ID = "custom_chat_colors_id"
    private const val BADGES = "badges"
    const val SEARCH_PROFILE_NAME = "search_signal_profile"
    private const val SORT_NAME = "sort_name"
    private const val IDENTITY_STATUS = "identity_status"
    private const val IDENTITY_KEY = "identity_key"

    //AA+ -----------------
    private val SHARED_PROFILE = "shared_profile" //AA+ users who shared their profiles with us counterpart to PROFILE_SHARING

    private const val UFSRVID = "ufsrvid" //multiplexes shortform fid and uid

    private const val UFSRVUNAME = "ufsrvuname"
    private const val UFSRVUID = "ufsrvuid" //long form encoded ufrsvid

    private const val EVENTID = "eid" //multiplexes eid for user and fence

    private const val RECIPIENT_TYPE = "recipient_type"
    private const val NICKNAME = "nickname"
    private const val AVATAR_UFSRV_ID = "avatar_ufsrv_id"
    private const val BASELOC = "baseloc"
    private const val BUDDY = "buddy"
    private const val FOLLOW = "follow" //we follow

    private const val FOLLOWED = "followed" //following us

    private const val STICKY = "sticky"
    private const val SHARE_PRESENCE = "share_presence" //can see our presence

    private const val SHARE_LOCATION = "share_location" //can see our location

    private const val SHARED_PRESENCE = "shared_presence" //shared their presence with us

    private const val SHARED_LOCATION = "shared_location"
    private const val SHARED_BLOCK = "shared_block" //those who're blocking this user

    private const val SHARED_READ_RECEIPT = "shared_read_receipt"
    private const val SHARE_READ_RECEIPT = "share_read_receipt"
    private const val SHARED_ACTIVITY_STATE = "shared_activity_state"
    private const val SHARE_ACTIVITY_STATE = "share_activity_state"
    private const val SHARED_CONTACT = "shared_contact" //allwed us to share their contact info

    const val SHARE_CONTACT = "share_contact" //we allowed them to share my contact info

    const val SHARE_BLOCKED_FENCE = "share_blocked_fence"
    private const val PRESENCE_INFO = "presence_info" //status, timestamp(seconds)

    private const val LOCATION_INFO = "location_info" //long,lat

    private const val GUARDIAN_STATUS = "guardian_status"

    private const val PERM_PRESENTATION = "perm_presentation"
    private const val PREM_MEMBERSHIP = "perm_membership"
    private const val PREM_MESSAGING = "perm_messaging"
    private const val PREM_ATTACHING = "perm_attaching"
    private const val PREM_CALLING = "perm_calling"

    private const val PERM_PRESENTATION_LIST_SEMANTICS = "perm_presentation_list_semantics"
    private const val PREM_MEMBERSHIP_LIST_SEMANTICS = "perm_membership_list_semantics"
    private const val PREM_MESSAGING_LIST_SEMANTICS = "perm_messaging_list_semantics"
    private const val PREM_ATTACHING_LIST_SEMANTICS = "perm_attaching_list_semantics"
    private const val PREM_CALLING_LIST_SEMANTICS = "perm_calling_list_semantics"

    private const val PERM_PRESENTATION_BASELIST = "perm_presentation_baselist"
    private const val PREM_MEMBERSHIP_BASELIST = "perm_membership_baselist"
    private const val PREM_MESSAGING_BASELIST = "perm_messaging_baselist"
    private const val PREM_ATTACHING_BASELIST = "perm_attaching_baselist"
    private const val PREM_CALLING_BASELIST = "perm_calling_baselist"

    //AA+ $SHARED_BLOCK
    //AA+ $SHARED_PROFILE
    @JvmField
    val CREATE_TABLE =
      """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT,
        $SERVICE_ID TEXT UNIQUE DEFAULT NULL,
        $USERNAME TEXT UNIQUE DEFAULT NULL,
        $PHONE TEXT UNIQUE DEFAULT NULL,
        $EMAIL TEXT UNIQUE DEFAULT NULL,
        $GROUP_ID TEXT UNIQUE DEFAULT NULL,
        $GROUP_TYPE INTEGER DEFAULT ${GroupType.NONE.id},
        $BLOCKED INTEGER DEFAULT 0,
        $SHARED_BLOCK INTEGER DEFAULT 0, 
        $MESSAGE_RINGTONE TEXT DEFAULT NULL, 
        $MESSAGE_VIBRATE INTEGER DEFAULT ${VibrateState.DEFAULT.id}, 
        $CALL_RINGTONE TEXT DEFAULT NULL, 
        $CALL_VIBRATE INTEGER DEFAULT ${VibrateState.DEFAULT.id}, 
        $NOTIFICATION_CHANNEL TEXT DEFAULT NULL, 
        $MUTE_UNTIL INTEGER DEFAULT 0, 
        $AVATAR_COLOR TEXT DEFAULT NULL, 
        $SEEN_INVITE_REMINDER INTEGER DEFAULT ${InsightsBannerTier.NO_TIER.id},
        $DEFAULT_SUBSCRIPTION_ID INTEGER DEFAULT -1,
        $MESSAGE_EXPIRATION_TIME INTEGER DEFAULT 0,
        $REGISTERED INTEGER DEFAULT ${RegisteredState.UNKNOWN.id},
        $SYSTEM_GIVEN_NAME TEXT DEFAULT NULL, 
        $SYSTEM_FAMILY_NAME TEXT DEFAULT NULL, 
        $SYSTEM_JOINED_NAME TEXT DEFAULT NULL, 
        $SYSTEM_PHOTO_URI TEXT DEFAULT NULL, 
        $SYSTEM_PHONE_LABEL TEXT DEFAULT NULL, 
        $SYSTEM_PHONE_TYPE INTEGER DEFAULT -1, 
        $SYSTEM_CONTACT_URI TEXT DEFAULT NULL, 
        $SYSTEM_INFO_PENDING INTEGER DEFAULT 0, 
        $PROFILE_KEY TEXT DEFAULT NULL, 
        $PROFILE_KEY_CREDENTIAL TEXT DEFAULT NULL, 
        $PROFILE_GIVEN_NAME TEXT DEFAULT NULL, 
        $PROFILE_FAMILY_NAME TEXT DEFAULT NULL, 
        $PROFILE_JOINED_NAME TEXT DEFAULT NULL, 
        $SIGNAL_PROFILE_AVATAR TEXT DEFAULT NULL, 
        $PROFILE_SHARING INTEGER DEFAULT 0, 
        $SHARED_PROFILE INTEGER DEFAULT 0,
        $LAST_PROFILE_FETCH INTEGER DEFAULT 0, 
        $UNIDENTIFIED_ACCESS_MODE INTEGER DEFAULT 0, 
        $FORCE_SMS_SELECTION INTEGER DEFAULT 0, 
        $STORAGE_SERVICE_ID TEXT UNIQUE DEFAULT NULL, 
        $MENTION_SETTING INTEGER DEFAULT ${MentionSetting.ALWAYS_NOTIFY.id}, 
        $STORAGE_PROTO TEXT DEFAULT NULL,
        $CAPABILITIES INTEGER DEFAULT 0,
        $LAST_SESSION_RESET BLOB DEFAULT NULL,
        $WALLPAPER BLOB DEFAULT NULL,
        $WALLPAPER_URI TEXT DEFAULT NULL,
        $ABOUT TEXT DEFAULT NULL,
        $ABOUT_EMOJI TEXT DEFAULT NULL,
        $EXTRAS BLOB DEFAULT NULL,
        $GROUPS_IN_COMMON INTEGER DEFAULT 0,
        $CHAT_COLORS BLOB DEFAULT NULL,
        $CUSTOM_CHAT_COLORS_ID INTEGER DEFAULT 0,
        $BADGES BLOB DEFAULT NULL,
        $PNI_COLUMN TEXT DEFAULT NULL,
        $DISTRIBUTION_LIST_ID INTEGER DEFAULT NULL,
        
        
         $UFSRVID                   INTEGER, 
         $UFSRVUID                  TEXT UNIQUE, 
         $UFSRVUNAME                TEXT UNIQUE, 
         $EVENTID                   INTEGER,
         $RECIPIENT_TYPE            INTEGER DEFAULT 0, 
         $NICKNAME                  TEXT,
         $AVATAR_UFSRV_ID           TEXT DEFAULT 0,
         $BASELOC                   TEXT DEFAULT NULL, 
         $BUDDY                     INTEGER DEFAULT 0,
         $FOLLOW                    INTEGER DEFAULT 0, 
         $STICKY                    INTEGER DEFAULT 0, 
         $GUARDIAN_STATUS           INTEGER DEFAULT 0, 
         $SHARE_LOCATION            INTEGER DEFAULT 0, 
         $SHARED_LOCATION           INTEGER DEFAULT 0, 
         $LOCATION_INFO             TEXT DEFAULT NULL, 
         $SHARE_PRESENCE            INTEGER DEFAULT 0, 
         $SHARED_PRESENCE           INTEGER DEFAULT 0, 
         $SHARE_READ_RECEIPT        INTEGER DEFAULT 0, 
         $SHARED_READ_RECEIPT       INTEGER DEFAULT 0, 
         $SHARE_ACTIVITY_STATE      INTEGER DEFAULT 0, 
         $SHARED_ACTIVITY_STATE     INTEGER DEFAULT 0, 
         $SHARE_CONTACT             INTEGER DEFAULT 0, 
         $SHARED_CONTACT            INTEGER DEFAULT 0, 
         $SHARE_BLOCKED_FENCE       TEXT DEFAULT NULL, 
         $PRESENCE_INFO             TEXT DEFAULT NULL, 
         $PERM_PRESENTATION         TEXT DEFAULT NULL, 
         $PREM_MEMBERSHIP           TEXT DEFAULT NULL, 
         $PREM_MESSAGING            TEXT DEFAULT NULL, 
         $PREM_ATTACHING            TEXT DEFAULT NULL, 
         $PREM_CALLING              TEXT DEFAULT NULL, 
         $PERM_PRESENTATION_LIST_SEMANTICS  INTEGER DEFAULT 2, 
         $PREM_MEMBERSHIP_LIST_SEMANTICS    INTEGER DEFAULT 2, 
         $PREM_MESSAGING_LIST_SEMANTICS     INTEGER DEFAULT 2, 
         $PREM_ATTACHING_LIST_SEMANTICS     INTEGER DEFAULT 2, 
         $PREM_CALLING_LIST_SEMANTICS       INTEGER DEFAULT 2, 
         $PERM_PRESENTATION_BASELIST        INTEGER DEFAULT 0, 
         $PREM_MEMBERSHIP_BASELIST          INTEGER DEFAULT 0, 
         $PREM_MESSAGING_BASELIST           INTEGER DEFAULT 0, 
         $PREM_ATTACHING_BASELIST           INTEGER DEFAULT 0, 
         $PREM_CALLING_BASELIST             INTEGER DEFAULT 0 
      )
      """.trimIndent()

    val CREATE_INDEXS = arrayOf(
      "CREATE INDEX IF NOT EXISTS recipient_group_type_index ON $TABLE_NAME ($GROUP_TYPE);",
      "CREATE UNIQUE INDEX IF NOT EXISTS recipient_pni_index ON $TABLE_NAME ($PNI_COLUMN)"
    )

    private val RECIPIENT_PROJECTION: Array<String> = arrayOf(
      ID,
      SERVICE_ID,
      PNI_COLUMN,
      USERNAME,
      PHONE,
      EMAIL,
      GROUP_ID,
      GROUP_TYPE,
      BLOCKED,
      MESSAGE_RINGTONE,
      CALL_RINGTONE,
      MESSAGE_VIBRATE,
      CALL_VIBRATE,
      MUTE_UNTIL,
      AVATAR_COLOR,
      SEEN_INVITE_REMINDER,
      DEFAULT_SUBSCRIPTION_ID,
      MESSAGE_EXPIRATION_TIME,
      REGISTERED,
      PROFILE_KEY,
      PROFILE_KEY_CREDENTIAL,
      SYSTEM_JOINED_NAME,
      SYSTEM_GIVEN_NAME,
      SYSTEM_FAMILY_NAME,
      SYSTEM_PHOTO_URI,
      SYSTEM_PHONE_LABEL,
      SYSTEM_PHONE_TYPE,
      SYSTEM_CONTACT_URI,
      PROFILE_GIVEN_NAME,
      PROFILE_FAMILY_NAME,
      SIGNAL_PROFILE_AVATAR,
      PROFILE_SHARING,
      LAST_PROFILE_FETCH,
      NOTIFICATION_CHANNEL,
      UNIDENTIFIED_ACCESS_MODE,
      FORCE_SMS_SELECTION,
      CAPABILITIES,
      STORAGE_SERVICE_ID,
      MENTION_SETTING,
      WALLPAPER,
      WALLPAPER_URI,
      MENTION_SETTING,
      ABOUT,
      ABOUT_EMOJI,
      EXTRAS,
      GROUPS_IN_COMMON,
      CHAT_COLORS,
      CUSTOM_CHAT_COLORS_ID,
      BADGES,
      DISTRIBUTION_LIST_ID,

      //AA+ ------------
      SHARED_PROFILE,
      UFSRVUID,
      UFSRVID,
      UFSRVUNAME,
      EVENTID,
      RECIPIENT_TYPE,
      NICKNAME,
      AVATAR_UFSRV_ID,
      BASELOC,
      BUDDY,
      FOLLOW,
      STICKY,
      GUARDIAN_STATUS,
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
      SHARE_BLOCKED_FENCE,
      PRESENCE_INFO,
      PERM_PRESENTATION,
      PREM_MEMBERSHIP,
      PREM_MESSAGING,
      PREM_ATTACHING,
      PREM_CALLING,
      PERM_PRESENTATION_LIST_SEMANTICS,
      PREM_MEMBERSHIP_LIST_SEMANTICS,
      PREM_MESSAGING_LIST_SEMANTICS,
      PREM_ATTACHING_LIST_SEMANTICS,
      PREM_CALLING_LIST_SEMANTICS,
      PERM_PRESENTATION_BASELIST,
      PREM_MEMBERSHIP_BASELIST,
      PREM_MESSAGING_BASELIST,
      PREM_ATTACHING_BASELIST,
      PREM_CALLING_BASELIST,
    )

    private val ID_PROJECTION = arrayOf(ID)

    //AA+ NULLIF($UFSRVUNAME, ''), NULLIF($UFSRVUID, '')
    private val SEARCH_PROJECTION = arrayOf(
      ID,
      SYSTEM_JOINED_NAME,
      PHONE,
      EMAIL,
      SYSTEM_PHONE_LABEL,
      SYSTEM_PHONE_TYPE,
      REGISTERED,
      ABOUT,
      ABOUT_EMOJI,
      EXTRAS,
      GROUPS_IN_COMMON,
      "COALESCE(NULLIF($PROFILE_JOINED_NAME, ''), NULLIF($PROFILE_GIVEN_NAME, '')) AS $SEARCH_PROFILE_NAME",
      """
      LOWER(
        COALESCE(
          NULLIF($SYSTEM_JOINED_NAME, ''),
          NULLIF($SYSTEM_GIVEN_NAME, ''),
          NULLIF($PROFILE_JOINED_NAME, ''),
          NULLIF($PROFILE_GIVEN_NAME, ''),
          NULLIF($USERNAME, ''),
          NULLIF($UFSRVUNAME, ''),
          NULLIF($UFSRVUID, '')
        )
      ) AS $SORT_NAME
      """.trimIndent()
    )

    @JvmField
    val SEARCH_PROJECTION_NAMES = arrayOf(
      ID,
      SYSTEM_JOINED_NAME,
      PHONE,
      EMAIL,
      SYSTEM_PHONE_LABEL,
      SYSTEM_PHONE_TYPE,
      REGISTERED,
      ABOUT,
      ABOUT_EMOJI,
      EXTRAS,
      GROUPS_IN_COMMON,
      SEARCH_PROFILE_NAME,
      SORT_NAME
    )

    private val TYPED_RECIPIENT_PROJECTION: Array<String> = RECIPIENT_PROJECTION
      .map { columnName -> "$TABLE_NAME.$columnName" }
      .toTypedArray()

    @JvmField
    val TYPED_RECIPIENT_PROJECTION_NO_ID: Array<String> = TYPED_RECIPIENT_PROJECTION.copyOfRange(1, TYPED_RECIPIENT_PROJECTION.size)

    //AA+  NULLIF($UFSRVUNAME, '') and NICKNAME
    private val MENTION_SEARCH_PROJECTION = arrayOf(
      ID,
      """
      REPLACE(
        COALESCE(
          NULLIF($SYSTEM_JOINED_NAME, ''), 
          NULLIF($SYSTEM_GIVEN_NAME, ''), 
          NULLIF($PROFILE_JOINED_NAME, ''), 
          NULLIF($PROFILE_GIVEN_NAME, ''),
          NULLIF($USERNAME, ''),
          NULLIF($PHONE, ''),
          NULLIF($UFSRVUNAME, ''),
          NULLIF($NICKNAME, '')
          
        ),
        ' ',
        ''
      ) AS $SORT_NAME
      """.trimIndent()
    )

    private val INSIGHTS_INVITEE_LIST =
      """
      SELECT $TABLE_NAME.$ID
      FROM $TABLE_NAME INNER JOIN ${ThreadDatabase.TABLE_NAME} ON $TABLE_NAME.$ID = ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.RECIPIENT_ID}
      WHERE 
        $TABLE_NAME.$GROUP_ID IS NULL AND
        $TABLE_NAME.$REGISTERED = ${RegisteredState.NOT_REGISTERED.id} AND
        $TABLE_NAME.$SEEN_INVITE_REMINDER < ${InsightsBannerTier.TIER_TWO.id} AND
        ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.HAS_SENT} AND
        ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.DATE} > ?
      ORDER BY ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.DATE} DESC LIMIT 50
      """

    //AA+
      private val permissionsHashmap: HashMap<FencePermissions, String> =  HashMap()
      private var permissionsDbColumnHashMap: Map<FencePermissions, String>? = null
      init {
        permissionsHashmap.put(FencePermissions.PRESENTATION, "perm_presentation");
        permissionsHashmap.put(FencePermissions.MEMBERSHIP, "perm_membership");
        permissionsHashmap.put(FencePermissions.MESSAGING, "perm_messaging");
        permissionsHashmap.put(FencePermissions.ATTACHING, "perm_attaching");
        permissionsHashmap.put(FencePermissions.CALLING, "perm_calling");
        permissionsDbColumnHashMap = Collections.unmodifiableMap(permissionsHashmap);
      }
    //
  }

  fun containsPhoneOrUuid(id: String): Boolean {
    val query = "$SERVICE_ID = ? OR $PHONE = ?"
    val args = arrayOf(id, id)
    readableDatabase.query(TABLE_NAME, arrayOf(ID), query, args, null, null, null).use { cursor -> return cursor != null && cursor.moveToFirst() }
  }

  fun getByE164(e164: String): Optional<RecipientId> {
    return getByColumn(PHONE, e164)
  }

  fun getByEmail(email: String): Optional<RecipientId> {
    return getByColumn(EMAIL, email)
  }

  fun getByGroupId(groupId: GroupId): Optional<RecipientId> {
    return getByColumn(GROUP_ID, groupId.toString())
  }

  fun getByServiceId(serviceId: ServiceId): Optional<RecipientId> {
    return getByColumn(SERVICE_ID, serviceId.toString())
  }

  fun getByUsername(username: String): Optional<RecipientId> {
    return getByColumn(USERNAME, username)
  }

  fun getAndPossiblyMerge(serviceId: ServiceId?, e164: String?, highTrust: Boolean): RecipientId {
    return getAndPossiblyMerge(serviceId, e164, highTrust, false)
  }

  fun getAndPossiblyMerge(serviceId: ServiceId?, e164: String?, highTrust: Boolean, changeSelf: Boolean): RecipientId {
    require(!(serviceId == null && e164 == null)) { "Must provide an ACI or E164!" }

    val db = writableDatabase

    var transactionSuccessful = false
    var remapped: Pair<RecipientId, RecipientId>? = null
    var recipientsNeedingRefresh: List<RecipientId> = listOf()
    var recipientChangedNumber: RecipientId? = null

    db.beginTransaction()
    try {
      val fetch: RecipientFetch = fetchRecipient(serviceId, e164, highTrust, changeSelf)

      if (fetch.logBundle != null) {
        Log.w(TAG, fetch.toString())
      }

      val resolvedId: RecipientId = when (fetch) {
        is RecipientFetch.Match -> {
          fetch.id
        }
        is RecipientFetch.MatchAndUpdateE164 -> {
          setPhoneNumberOrThrowSilent(fetch.id, fetch.e164)
          recipientsNeedingRefresh = listOf(fetch.id)
          recipientChangedNumber = fetch.changedNumber
          fetch.id
        }
        is RecipientFetch.MatchAndReassignE164 -> {
          removePhoneNumber(fetch.e164Id, db)
          setPhoneNumberOrThrowSilent(fetch.id, fetch.e164)
          recipientsNeedingRefresh = listOf(fetch.id, fetch.e164Id)
          recipientChangedNumber = fetch.changedNumber
          fetch.id
        }
        is RecipientFetch.MatchAndUpdateAci -> {
          markRegistered(fetch.id, fetch.serviceId)
          recipientsNeedingRefresh = listOf(fetch.id)
          fetch.id
        }
        is RecipientFetch.MatchAndInsertAci -> {
          val id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(null, fetch.serviceId))
          RecipientId.from(id)
        }
        is RecipientFetch.MatchAndMerge -> {
          remapped = Pair(fetch.e164Id, fetch.sidId)
          val mergedId: RecipientId = merge(fetch.sidId, fetch.e164Id)
          recipientsNeedingRefresh = listOf(mergedId)
          recipientChangedNumber = fetch.changedNumber
          mergedId
        }
        is RecipientFetch.Insert -> {
          val id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(fetch.e164, fetch.serviceId))
          RecipientId.from(id)
        }
        is RecipientFetch.InsertAndReassignE164 -> {
          removePhoneNumber(fetch.e164Id, db)
          recipientsNeedingRefresh = listOf(fetch.e164Id)
          val id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(fetch.e164, fetch.serviceId))
          RecipientId.from(id)
        }
      }

      transactionSuccessful = true
      db.setTransactionSuccessful()
      return resolvedId
    } finally {
      db.endTransaction()

      if (transactionSuccessful) {
        if (recipientsNeedingRefresh.isNotEmpty()) {
          recipientsNeedingRefresh.forEach { ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(it) }
          RetrieveProfileJob.enqueue(recipientsNeedingRefresh.toSet())
        }

        if (remapped != null) {
          Recipient.live(remapped.first()).refresh(remapped.second())
          ApplicationDependencies.getRecipientCache().remap(remapped.first(), remapped.second())
        }

        if (recipientsNeedingRefresh.isNotEmpty() || remapped != null) {
          StorageSyncHelper.scheduleSyncForDataChange()
          RecipientId.clearCache()
        }
        if (recipientChangedNumber != null) {
          ApplicationDependencies.getJobManager().add(RecipientChangedNumberJob(recipientChangedNumber))
        }
      }
    }
  }

  private fun fetchRecipient(serviceId: ServiceId?, e164: String?, highTrust: Boolean, changeSelf: Boolean): RecipientFetch {
    val byE164 = e164?.let { getByE164(it) } ?: Optional.empty()
    val byAci = serviceId?.let { getByServiceId(it) } ?: Optional.empty()

    var logs = LogBundle(
      bySid = byAci.map { id -> RecipientLogDetails(id = id) }.orElse(null),
      byE164 = byE164.map { id -> RecipientLogDetails(id = id) }.orElse(null),
      label = "L0"
    )

    if (byAci.isPresent && byE164.isPresent && byAci.get() == byE164.get()) {
      return RecipientFetch.Match(byAci.get(), null)
    }

    if (byAci.isPresent && byE164.isAbsent()) {
      val aciRecord: RecipientRecord = getRecord(byAci.get())
      logs = logs.copy(bySid = aciRecord.toLogDetails())

      if (highTrust && e164 != null && (changeSelf || serviceId != SignalStore.account().aci)) {
        val changedNumber: RecipientId? = if (aciRecord.e164 != null && aciRecord.e164 != e164) aciRecord.id else null
        return RecipientFetch.MatchAndUpdateE164(byAci.get(), e164, changedNumber, logs.label("L1"))
      } else if (e164 == null) {
        return RecipientFetch.Match(byAci.get(), null)
      } else {
        return RecipientFetch.Match(byAci.get(), logs.label("L2"))
      }
    }

    if (byAci.isAbsent() && byE164.isPresent) {
      val e164Record: RecipientRecord = getRecord(byE164.get())
      logs = logs.copy(byE164 = e164Record.toLogDetails())

      if (highTrust && serviceId != null && e164Record.serviceId == null) {
        return RecipientFetch.MatchAndUpdateAci(byE164.get(), serviceId, logs.label("L3"))
      } else if (highTrust && serviceId != null && e164Record.serviceId != SignalStore.account().aci) {
        return RecipientFetch.InsertAndReassignE164(serviceId, e164, byE164.get(), logs.label("L4"))
      } else if (serviceId != null) {
        return RecipientFetch.Insert(serviceId, null, logs.label("L5"))
      } else {
        return RecipientFetch.Match(byE164.get(), null)
      }
    }

    if (byAci.isAbsent() && byE164.isAbsent()) {
      if (highTrust) {
        return RecipientFetch.Insert(serviceId, e164, logs.label("L6"))
      } else if (serviceId != null) {
        return RecipientFetch.Insert(serviceId, null, logs.label("L7"))
      } else {
        return RecipientFetch.Insert(null, e164, logs.label("L8"))
      }
    }

    require(byAci.isPresent && byE164.isPresent && byAci.get() != byE164.get()) { "Assumed conditions at this point." }

    val aciRecord: RecipientRecord = getRecord(byAci.get())
    val e164Record: RecipientRecord = getRecord(byE164.get())

    logs = logs.copy(bySid = aciRecord.toLogDetails(), byE164 = e164Record.toLogDetails())

    if (e164Record.serviceId == null) {
      if (highTrust) {
        val changedNumber: RecipientId? = if (aciRecord.e164 != null) aciRecord.id else null
        return RecipientFetch.MatchAndMerge(sidId = byAci.get(), e164Id = byE164.get(), changedNumber = changedNumber, logs.label("L9"))
      } else {
        return RecipientFetch.Match(byAci.get(), logs.label("L10"))
      }
    } else {
      if (highTrust && e164Record.serviceId != SignalStore.account().aci) {
        val changedNumber: RecipientId? = if (aciRecord.e164 != null) aciRecord.id else null
        return RecipientFetch.MatchAndReassignE164(id = byAci.get(), e164Id = byE164.get(), e164 = e164!!, changedNumber = changedNumber, logs.label("L11"))
      } else {
        return RecipientFetch.Match(byAci.get(), logs.label("L12"))
      }
    }
  }

  fun getOrInsertFromServiceId(serviceId: ServiceId): RecipientId {
    return getOrInsertByColumn(SERVICE_ID, serviceId.toString()).recipientId
  }

  fun getOrInsertFromE164(e164: String): RecipientId {
    return getOrInsertByColumn(PHONE, e164).recipientId
  }

  fun getOrInsertFromEmail(email: String): RecipientId {
    return getOrInsertByColumn(EMAIL, email).recipientId
  }

  fun getOrInsertFromDistributionListId(distributionListId: DistributionListId): RecipientId {
    return getOrInsertByColumn(
      DISTRIBUTION_LIST_ID,
      distributionListId.serialize(),
      ContentValues().apply {
        put(DISTRIBUTION_LIST_ID, distributionListId.serialize())
        put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()))
        put(PROFILE_SHARING, 1)
      }
    ).recipientId
  }

  fun getDistributionListRecipientIds(): List<RecipientId> {
    val recipientIds = mutableListOf<RecipientId>()
    readableDatabase.query(TABLE_NAME, arrayOf(ID), "$DISTRIBUTION_LIST_ID is not NULL", null, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        recipientIds.add(RecipientId.from(CursorUtil.requireLong(cursor, ID)))
      }
    }

    return recipientIds
  }

  //AA- removed V1 migration exception
  fun getOrInsertFromGroupId(groupId: GroupId): RecipientId {
    var existing = getByGroupId(groupId)

    if (existing.isPresent) {
      return existing.get()
    } else {
      val values = ContentValues().apply {
        put(GROUP_ID, groupId.toString())
        put(AVATAR_COLOR, AvatarColor.random().serialize())
      }

      val id = writableDatabase.insert(TABLE_NAME, null, values)
      if (id < 0) {
        existing = getByColumn(GROUP_ID, groupId.toString())
        if (existing.isPresent) {
          return existing.get()
        } else {
          throw AssertionError("Failed to insert recipient!")
        }
      } else {
        val groupUpdates = ContentValues().apply {
          if (groupId.isMms) {
            put(GROUP_TYPE, GroupType.MMS.id)
          } else {
            if (groupId.isV2) {
              put(GROUP_TYPE, GroupType.SIGNAL_V2.id)
            } else {
              put(GROUP_TYPE, GroupType.SIGNAL_V1.id)
            }
            put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()))
          }
        }

        val recipientId = RecipientId.from(id)
        update(recipientId, groupUpdates)

        return recipientId
      }
    }
  }

  /**
   * See [Recipient.externalPossiblyMigratedGroup].
   */
  fun getOrInsertFromPossiblyMigratedGroupId(groupId: GroupId): RecipientId {
    val db = writableDatabase
    db.beginTransaction()

    try {
      val existing = getByColumn(GROUP_ID, groupId.toString())
      if (existing.isPresent) {
        db.setTransactionSuccessful()
        return existing.get()
      }

      if (groupId.isV1) {
        val v2 = getByGroupId(groupId.requireV1().deriveV2MigrationGroupId())
        if (v2.isPresent) {
          db.setTransactionSuccessful()
          return v2.get()
        }
      }

      if (groupId.isV2) {
        val v1 = groups.getGroupV1ByExpectedV2(groupId.requireV2())
        if (v1.isPresent) {
          db.setTransactionSuccessful()
          return v1.get().recipientId
        }
      }

      val id = getOrInsertFromGroupId(groupId)
      db.setTransactionSuccessful()
      return id
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Only call once to create initial release channel recipient.
   */
  fun insertReleaseChannelRecipient(): RecipientId {
    val values = ContentValues().apply {
      put(AVATAR_COLOR, AvatarColor.random().serialize())
    }

    val id = writableDatabase.insert(TABLE_NAME, null, values)
    if (id < 0) {
      throw AssertionError("Failed to insert recipient!")
    } else {
      return GetOrInsertResult(RecipientId.from(id), true).recipientId
    }
  }

  fun getBlocked(): Cursor {
    return readableDatabase.query(TABLE_NAME, ID_PROJECTION, "$BLOCKED = 1", null, null, null, null)
  }

  fun readerForBlocked(cursor: Cursor): RecipientReader {
    return RecipientReader(cursor)
  }

  fun getRecipientsWithNotificationChannels(): RecipientReader {
    val cursor = readableDatabase.query(TABLE_NAME, ID_PROJECTION, "$NOTIFICATION_CHANNEL NOT NULL", null, null, null, null)
    return RecipientReader(cursor)
  }

  fun getRecord(id: RecipientId): RecipientRecord {
    val query = "$ID = ?"
    val args = arrayOf(id.serialize())

    readableDatabase.query(TABLE_NAME, RECIPIENT_PROJECTION, query, args, null, null, null).use { cursor ->
      return if (cursor != null && cursor.moveToNext()) {
        getRecord(context, cursor)
      } else {
        val remapped = RemappedRecords.getInstance().getRecipient(id)

        if (remapped.isPresent) {
          Log.w(TAG, "Missing recipient for $id, but found it in the remapped records as ${remapped.get()}")
          getRecord(remapped.get())
        } else {
          throw MissingRecipientException(id)
        }
      }
    }
  }

  fun getRecordForSync(id: RecipientId): RecipientRecord? {
    val query = "$TABLE_NAME.$ID = ?"
    val args = arrayOf(id.serialize())
    val recordForSync = getRecordForSync(query, args)

    if (recordForSync.isEmpty()) {
      return null
    }

    if (recordForSync.size > 1) {
      throw AssertionError()
    }

    return recordForSync[0]
  }

  fun getByStorageId(storageId: ByteArray): RecipientRecord? {
    val result = getRecordForSync("$TABLE_NAME.$STORAGE_SERVICE_ID = ?", arrayOf(Base64.encodeBytes(storageId)))

    return if (result.isNotEmpty()) {
      result[0]
    } else null
  }

  fun markNeedsSyncWithoutRefresh(recipientIds: Collection<RecipientId>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (recipientId in recipientIds) {
        rotateStorageId(recipientId)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun markNeedsSync(recipientId: RecipientId) {
    rotateStorageId(recipientId)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
  }

  fun applyStorageIdUpdates(storageIds: Map<RecipientId, StorageId>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val query = "$ID = ?"
      for ((key, value) in storageIds) {
        val values = ContentValues().apply {
          put(STORAGE_SERVICE_ID, Base64.encodeBytes(value.raw))
        }
        db.update(TABLE_NAME, values, query, arrayOf(key.serialize()))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    for (id in storageIds.keys) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun applyStorageSyncContactInsert(insert: SignalContactRecord) {
    val db = writableDatabase
    val threadDatabase = threads
    val values = getValuesForStorageContact(insert, true)
    val id = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)

    val recipientId: RecipientId
    if (id < 0) {
      Log.w(TAG, "[applyStorageSyncContactInsert] Failed to insert. Possibly merging.")
      recipientId = getAndPossiblyMerge(if (insert.address.hasValidServiceId()) insert.address.serviceId else null, insert.address.number.orElse(null), true)
      db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(recipientId))
    } else {
      recipientId = RecipientId.from(id)
    }

    if (insert.identityKey.isPresent && insert.address.hasValidServiceId()) {
      try {
        val identityKey = IdentityKey(insert.identityKey.get(), 0)
        identities.updateIdentityAfterSync(insert.address.identifier, recipientId, identityKey, StorageSyncModels.remoteToLocalIdentityStatus(insert.identityState))
      } catch (e: InvalidKeyException) {
        Log.w(TAG, "Failed to process identity key during insert! Skipping.", e)
      }
    }

    updateExtras(recipientId) {
      it.setHideStory(insert.shouldHideStory())
    }

    threadDatabase.applyStorageSyncUpdate(recipientId, insert)
  }

  fun applyStorageSyncContactUpdate(update: StorageRecordUpdate<SignalContactRecord>) {
    val db = writableDatabase
    val identityStore = ApplicationDependencies.getProtocolStore().aci().identities()
    val values = getValuesForStorageContact(update.new, false)

    try {
      val updateCount = db.update(TABLE_NAME, values, "$STORAGE_SERVICE_ID = ?", arrayOf(Base64.encodeBytes(update.old.id.raw)))
      if (updateCount < 1) {
        throw AssertionError("Had an update, but it didn't match any rows!")
      }
    } catch (e: SQLiteConstraintException) {
      Log.w(TAG, "[applyStorageSyncContactUpdate] Failed to update a user by storageId.")
      var recipientId = getByColumn(STORAGE_SERVICE_ID, Base64.encodeBytes(update.old.id.raw)).get()

      Log.w(TAG, "[applyStorageSyncContactUpdate] Found user $recipientId. Possibly merging.")
      recipientId = getAndPossiblyMerge(if (update.new.address.hasValidServiceId()) update.new.address.serviceId else null, update.new.address.number.orElse(null), true)

      Log.w(TAG, "[applyStorageSyncContactUpdate] Merged into $recipientId")
      db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(recipientId))
    }

    val recipientId = getByStorageKeyOrThrow(update.new.id.raw)
    if (StorageSyncHelper.profileKeyChanged(update)) {
      val clearValues = ContentValues(1).apply {
        putNull(PROFILE_KEY_CREDENTIAL)
      }
      db.update(TABLE_NAME, clearValues, ID_WHERE, SqlUtil.buildArgs(recipientId))
    }

    try {
      val oldIdentityRecord = identityStore.getIdentityRecord(recipientId)
      if (update.new.identityKey.isPresent && update.new.address.hasValidServiceId()) {
        val identityKey = IdentityKey(update.new.identityKey.get(), 0)
        identities.updateIdentityAfterSync(update.new.address.identifier, recipientId, identityKey, StorageSyncModels.remoteToLocalIdentityStatus(update.new.identityState))
      }

      val newIdentityRecord = identityStore.getIdentityRecord(recipientId)
      if (newIdentityRecord.isPresent && newIdentityRecord.get().verifiedStatus == VerifiedStatus.VERIFIED && (!oldIdentityRecord.isPresent || oldIdentityRecord.get().verifiedStatus != VerifiedStatus.VERIFIED)) {
        IdentityUtil.markIdentityVerified(context, Recipient.resolved(recipientId), true, true)
      } else if (newIdentityRecord.isPresent && newIdentityRecord.get().verifiedStatus != VerifiedStatus.VERIFIED && oldIdentityRecord.isPresent && oldIdentityRecord.get().verifiedStatus == VerifiedStatus.VERIFIED) {
        IdentityUtil.markIdentityVerified(context, Recipient.resolved(recipientId), false, true)
      }
    } catch (e: InvalidKeyException) {
      Log.w(TAG, "Failed to process identity key during update! Skipping.", e)
    }

    updateExtras(recipientId) {
      it.setHideStory(update.new.shouldHideStory())
    }

    threads.applyStorageSyncUpdate(recipientId, update.new)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
  }

  fun applyStorageSyncGroupV1Insert(insert: SignalGroupV1Record) {
    val id = writableDatabase.insertOrThrow(TABLE_NAME, null, getValuesForStorageGroupV1(insert, true))

    val recipientId = RecipientId.from(id)
    threads.applyStorageSyncUpdate(recipientId, insert)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
  }

  fun applyStorageSyncGroupV1Update(update: StorageRecordUpdate<SignalGroupV1Record>) {
    val values = getValuesForStorageGroupV1(update.new, false)

    val updateCount = writableDatabase.update(TABLE_NAME, values, STORAGE_SERVICE_ID + " = ?", arrayOf(Base64.encodeBytes(update.old.id.raw)))
    if (updateCount < 1) {
      throw AssertionError("Had an update, but it didn't match any rows!")
    }

    val recipient = Recipient.externalGroupExact(context, GroupId.v1orThrow(update.old.groupId))
    threads.applyStorageSyncUpdate(recipient.id, update.new)
    recipient.live().refresh()
  }

  fun applyStorageSyncGroupV2Insert(insert: SignalGroupV2Record) {
    val masterKey = insert.masterKeyOrThrow
    val groupId = GroupId.v2(masterKey)
    val values = getValuesForStorageGroupV2(insert, true)

    writableDatabase.insertOrThrow(TABLE_NAME, null, values)
    val recipient = Recipient.externalGroupExact(context, groupId)

    Log.i(TAG, "Creating restore placeholder for $groupId")
    groups.create(
      masterKey,
      DecryptedGroup.newBuilder()
        .setRevision(GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION)
        .build(),
      GroupDatabase.GroupControlsDescriptor(GroupDatabase.GroupType.USER, 0, GroupDatabase.PrivacyMode.PUBLIC, GroupDatabase.DeliveryMode.MANY, GroupDatabase.JoinMode.OPEN, null, 0)//AA+
    )

    updateExtras(recipient.id) {
      it.setHideStory(insert.shouldHideStory())
    }

    Log.i(TAG, "Scheduling request for latest group info for $groupId")
    ApplicationDependencies.getJobManager().add(RequestGroupV2InfoJob(groupId))
    threads.applyStorageSyncUpdate(recipient.id, insert)
    recipient.live().refresh()
  }

  fun applyStorageSyncGroupV2Update(update: StorageRecordUpdate<SignalGroupV2Record>) {
    val values = getValuesForStorageGroupV2(update.new, false)

    val updateCount = writableDatabase.update(TABLE_NAME, values, "$STORAGE_SERVICE_ID = ?", arrayOf(Base64.encodeBytes(update.old.id.raw)))
    if (updateCount < 1) {
      throw AssertionError("Had an update, but it didn't match any rows!")
    }

    val masterKey = update.old.masterKeyOrThrow
    val recipient = Recipient.externalGroupExact(context, GroupId.v2(masterKey))

    updateExtras(recipient.id) {
      it.setHideStory(update.new.shouldHideStory())
    }

    threads.applyStorageSyncUpdate(recipient.id, update.new)
    recipient.live().refresh()
  }

  fun applyStorageSyncAccountUpdate(update: StorageRecordUpdate<SignalAccountRecord>) {
    val profileName = ProfileName.fromParts(update.new.givenName.orElse(null), update.new.familyName.orElse(null))
    val localKey = ProfileKeyUtil.profileKeyOptional(update.old.profileKey.orElse(null))
    val remoteKey = ProfileKeyUtil.profileKeyOptional(update.new.profileKey.orElse(null))
    val profileKey = remoteKey.or(localKey).map { obj: ProfileKey -> obj.serialize() }.map { source: ByteArray? -> Base64.encodeBytes(source!!) }.orElse(null)
    if (!remoteKey.isPresent) {
      Log.w(TAG, "Got an empty profile key while applying an account record update!")
    }

    val values = ContentValues().apply {
      put(PROFILE_GIVEN_NAME, profileName.givenName)
      put(PROFILE_FAMILY_NAME, profileName.familyName)
      put(PROFILE_JOINED_NAME, profileName.toString())
      put(PROFILE_KEY, profileKey)
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(update.new.id.raw))
      if (update.new.hasUnknownFields()) {
        put(STORAGE_PROTO, Base64.encodeBytes(Objects.requireNonNull(update.new.serializeUnknownFields())))
      } else {
        putNull(STORAGE_PROTO)
      }
    }

    val updateCount = writableDatabase.update(TABLE_NAME, values, "$STORAGE_SERVICE_ID = ?", arrayOf(Base64.encodeBytes(update.old.id.raw)))
    if (updateCount < 1) {
      throw AssertionError("Account update didn't match any rows!")
    }

    if (remoteKey != localKey) {
      Log.i(TAG, "Our own profile key was changed during a storage sync.", Throwable())
      runPostSuccessfulTransaction { ProfileUtil.handleSelfProfileKeyChange() }
    }

    threads.applyStorageSyncUpdate(Recipient.self().id, update.new)
    Recipient.self().live().refresh()
  }

  fun updatePhoneNumbers(mapping: Map<String?, String?>) {
    if (mapping.isEmpty()) return
    val db = writableDatabase

    db.beginTransaction()
    try {
      val query = "$PHONE = ?"
      for ((key, value) in mapping) {
        val values = ContentValues().apply {
          put(PHONE, value)
        }
        db.updateWithOnConflict(TABLE_NAME, values, query, arrayOf(key), SQLiteDatabase.CONFLICT_IGNORE)
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  private fun getByStorageKeyOrThrow(storageKey: ByteArray): RecipientId {
    val query = "$STORAGE_SERVICE_ID = ?"
    val args = arrayOf(Base64.encodeBytes(storageKey))

    readableDatabase.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
      return if (cursor != null && cursor.moveToFirst()) {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(ID))
        RecipientId.from(id)
      } else {
        throw AssertionError("No recipient with that storage key!")
      }
    }
  }

  private fun getRecordForSync(query: String?, args: Array<String>?): List<RecipientRecord> {
    val table =
      """
      $TABLE_NAME LEFT OUTER JOIN ${IdentityDatabase.TABLE_NAME} ON $TABLE_NAME.$SERVICE_ID = ${IdentityDatabase.TABLE_NAME}.${IdentityDatabase.ADDRESS} 
                  LEFT OUTER JOIN ${GroupDatabase.TABLE_NAME} ON $TABLE_NAME.$GROUP_ID = ${GroupDatabase.TABLE_NAME}.${GroupDatabase.GROUP_ID} 
                  LEFT OUTER JOIN ${ThreadDatabase.TABLE_NAME} ON $TABLE_NAME.$ID = ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.RECIPIENT_ID}
      """.trimIndent()
    val out: MutableList<RecipientRecord> = ArrayList()
    val columns: Array<String> = TYPED_RECIPIENT_PROJECTION + arrayOf(
      "$TABLE_NAME.$STORAGE_PROTO",
      "${GroupDatabase.TABLE_NAME}.${GroupDatabase.V2_MASTER_KEY}",
      "${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.ARCHIVED}",
      "${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.READ}",
      "${IdentityDatabase.TABLE_NAME}.${IdentityDatabase.VERIFIED} AS $IDENTITY_STATUS",
      "${IdentityDatabase.TABLE_NAME}.${IdentityDatabase.IDENTITY_KEY} AS $IDENTITY_KEY"
    )

    readableDatabase.query(table, columns, query, args, "$TABLE_NAME.$ID", null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        out.add(getRecord(context, cursor))
      }
    }

    return out
  }

  /**
   * @return All storage ids for ContactRecords, excluding the ones that need to be deleted.
   */
  fun getContactStorageSyncIds(): List<StorageId> {
    return ArrayList(getContactStorageSyncIdsMap().values)
  }

  /**
   * @return All storage IDs for synced records, excluding the ones that need to be deleted.
   */
  fun getContactStorageSyncIdsMap(): Map<RecipientId, StorageId> {
    val query = """
      $STORAGE_SERVICE_ID NOT NULL AND (
        ($GROUP_TYPE = ? AND $SERVICE_ID NOT NULL AND $ID != ?)
        OR
        $GROUP_TYPE IN (?)
      )
    """.trimIndent()
    val args = SqlUtil.buildArgs(GroupType.NONE.id, Recipient.self().id, GroupType.SIGNAL_V1.id)
    val out: MutableMap<RecipientId, StorageId> = HashMap()

    readableDatabase.query(TABLE_NAME, arrayOf(ID, STORAGE_SERVICE_ID, GROUP_TYPE), query, args, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        val id = RecipientId.from(cursor.requireLong(ID))
        val encodedKey = cursor.requireNonNullString(STORAGE_SERVICE_ID)
        val groupType = GroupType.fromId(cursor.requireInt(GROUP_TYPE))
        val key = Base64.decodeOrThrow(encodedKey)

        when (groupType) {
          GroupType.NONE -> out[id] = StorageId.forContact(key)
          GroupType.SIGNAL_V1 -> out[id] = StorageId.forGroupV1(key)
          else -> throw AssertionError()
        }
      }
    }

    for (id in groups.allGroupV2Ids) {
      val recipient = Recipient.externalGroupExact(context, id!!)
      val recipientId = recipient.id
      val existing: RecipientRecord = getRecordForSync(recipientId) ?: throw AssertionError()
      val key = existing.storageId ?: throw AssertionError()
      out[recipientId] = StorageId.forGroupV2(key)
    }

    return out
  }

  fun beginBulkSystemContactUpdate(): BulkOperationsHandle {
    val db = writableDatabase
    val contentValues = ContentValues(1).apply {
      put(SYSTEM_INFO_PENDING, 1)
    }

    db.beginTransaction()
    db.update(TABLE_NAME, contentValues, "$SYSTEM_CONTACT_URI NOT NULL", null)
    return BulkOperationsHandle(db)
  }

  fun onUpdatedChatColors(chatColors: ChatColors) {
    val where = "$CUSTOM_CHAT_COLORS_ID = ?"
    val args = SqlUtil.buildArgs(chatColors.id.longValue)
    val updated: MutableList<RecipientId> = LinkedList()

    readableDatabase.query(TABLE_NAME, SqlUtil.buildArgs(ID), where, args, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        updated.add(RecipientId.from(cursor.requireLong(ID)))
      }
    }

    if (updated.isEmpty()) {
      Log.d(TAG, "No recipients utilizing updated chat color.")
    } else {
      val values = ContentValues(2).apply {
        put(CHAT_COLORS, chatColors.serialize().toByteArray())
        put(CUSTOM_CHAT_COLORS_ID, chatColors.id.longValue)
      }

      writableDatabase.update(TABLE_NAME, values, where, args)

      for (recipientId in updated) {
        ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
      }
    }
  }

  fun onDeletedChatColors(chatColors: ChatColors) {
    val where = "$CUSTOM_CHAT_COLORS_ID = ?"
    val args = SqlUtil.buildArgs(chatColors.id.longValue)
    val updated: MutableList<RecipientId> = LinkedList()

    readableDatabase.query(TABLE_NAME, SqlUtil.buildArgs(ID), where, args, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        updated.add(RecipientId.from(cursor.requireLong(ID)))
      }
    }

    if (updated.isEmpty()) {
      Log.d(TAG, "No recipients utilizing deleted chat color.")
    } else {
      val values = ContentValues(2).apply {
        put(CHAT_COLORS, null as ByteArray?)
        put(CUSTOM_CHAT_COLORS_ID, ChatColors.Id.NotSet.longValue)
      }

      writableDatabase.update(TABLE_NAME, values, where, args)

      for (recipientId in updated) {
        ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
      }
    }
  }

  fun getColorUsageCount(chatColorsId: ChatColors.Id): Int {
    val where = "$CUSTOM_CHAT_COLORS_ID = ?"
    val args = SqlUtil.buildArgs(chatColorsId.longValue)

    readableDatabase.query(TABLE_NAME, arrayOf("COUNT(*)"), where, args, null, null, null).use { cursor ->
      return if (cursor.moveToFirst()) {
        cursor.getInt(0)
      } else {
        0
      }
    }
  }

  fun clearAllColors() {
    val database = writableDatabase
    val where = "$CUSTOM_CHAT_COLORS_ID != ?"
    val args = SqlUtil.buildArgs(ChatColors.Id.NotSet.longValue)
    val toUpdate: MutableList<RecipientId> = LinkedList()

    database.query(TABLE_NAME, SqlUtil.buildArgs(ID), where, args, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        toUpdate.add(RecipientId.from(cursor.requireLong(ID)))
      }
    }

    if (toUpdate.isEmpty()) {
      return
    }

    val values = ContentValues().apply {
      put(CHAT_COLORS, null as ByteArray?)
      put(CUSTOM_CHAT_COLORS_ID, ChatColors.Id.NotSet.longValue)
    }
    database.update(TABLE_NAME, values, where, args)

    for (id in toUpdate) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun clearColor(id: RecipientId) {
    val values = ContentValues().apply {
      put(CHAT_COLORS, null as ByteArray?)
      put(CUSTOM_CHAT_COLORS_ID, ChatColors.Id.NotSet.longValue)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setColor(id: RecipientId, color: ChatColors) {
    val values = ContentValues().apply {
      put(CHAT_COLORS, color.serialize().toByteArray())
      put(CUSTOM_CHAT_COLORS_ID, color.id.longValue)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setDefaultSubscriptionId(id: RecipientId, defaultSubscriptionId: Int) {
    val values = ContentValues().apply {
      put(DEFAULT_SUBSCRIPTION_ID, defaultSubscriptionId)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setForceSmsSelection(id: RecipientId, forceSmsSelection: Boolean) {
    val contentValues = ContentValues(1).apply {
      put(FORCE_SMS_SELECTION, if (forceSmsSelection) 1 else 0)
    }
    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setBlocked(id: RecipientId, blocked: Boolean) {
    val values = ContentValues().apply {
      put(BLOCKED, if (blocked) 1 else 0)
    }
    if (update(id, values)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setMessageRingtone(id: RecipientId, notification: Uri?) {
    val values = ContentValues().apply {
      put(MESSAGE_RINGTONE, notification?.toString())
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setCallRingtone(id: RecipientId, ringtone: Uri?) {
    val values = ContentValues().apply {
      put(CALL_RINGTONE, ringtone?.toString())
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setMessageVibrate(id: RecipientId, enabled: VibrateState) {
    val values = ContentValues().apply {
      put(MESSAGE_VIBRATE, enabled.id)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setCallVibrate(id: RecipientId, enabled: VibrateState) {
    val values = ContentValues().apply {
      put(CALL_VIBRATE, enabled.id)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setMuted(id: RecipientId, until: Long) {
    val values = ContentValues().apply {
      put(MUTE_UNTIL, until)
    }

    if (update(id, values)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }

    StorageSyncHelper.scheduleSyncForDataChange()
  }

  fun setMuted(ids: Collection<RecipientId>, until: Long) {
    val db = writableDatabase

    db.beginTransaction()
    try {
      val query = SqlUtil.buildCollectionQuery(ID, ids)
      val values = ContentValues().apply {
        put(MUTE_UNTIL, until)
      }

      db.update(TABLE_NAME, values, query.where, query.whereArgs)
      for (id in ids) {
        rotateStorageId(id)
      }

      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    for (id in ids) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }

    StorageSyncHelper.scheduleSyncForDataChange()
  }

  fun setSeenFirstInviteReminder(id: RecipientId) {
    setInsightsBannerTier(id, InsightsBannerTier.TIER_ONE)
  }

  fun setSeenSecondInviteReminder(id: RecipientId) {
    setInsightsBannerTier(id, InsightsBannerTier.TIER_TWO)
  }

  fun setHasSentInvite(id: RecipientId) {
    setSeenSecondInviteReminder(id)
  }

  private fun setInsightsBannerTier(id: RecipientId, insightsBannerTier: InsightsBannerTier) {
    val query = "$ID = ? AND $SEEN_INVITE_REMINDER < ?"
    val args = arrayOf(id.serialize(), insightsBannerTier.toString())
    val values = ContentValues(1).apply {
      put(SEEN_INVITE_REMINDER, insightsBannerTier.id)
    }

    writableDatabase.update(TABLE_NAME, values, query, args)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
  }

  fun setExpireMessages(id: RecipientId, expiration: Int) {
    val values = ContentValues(1).apply {
      put(MESSAGE_EXPIRATION_TIME, expiration)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setUnidentifiedAccessMode(id: RecipientId, unidentifiedAccessMode: UnidentifiedAccessMode) {
    val values = ContentValues(1).apply {
      put(UNIDENTIFIED_ACCESS_MODE, unidentifiedAccessMode.mode)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setLastSessionResetTime(id: RecipientId, lastResetTime: DeviceLastResetTime) {
    val values = ContentValues(1).apply {
      put(LAST_SESSION_RESET, lastResetTime.toByteArray())
    }
    update(id, values)
  }

  fun getLastSessionResetTimes(id: RecipientId): DeviceLastResetTime {
    readableDatabase.query(TABLE_NAME, arrayOf(LAST_SESSION_RESET), ID_WHERE, SqlUtil.buildArgs(id), null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        return try {
          val serialized = cursor.requireBlob(LAST_SESSION_RESET)
          if (serialized != null) {
            DeviceLastResetTime.parseFrom(serialized)
          } else {
            DeviceLastResetTime.newBuilder().build()
          }
        } catch (e: InvalidProtocolBufferException) {
          Log.w(TAG, e)
          DeviceLastResetTime.newBuilder().build()
        }
      }
    }

    return DeviceLastResetTime.newBuilder().build()
  }

  fun setBadges(id: RecipientId, badges: List<Badge>) {
    val badgeListBuilder = BadgeList.newBuilder()
    for (badge in badges) {
      badgeListBuilder.addBadges(toDatabaseBadge(badge))
    }

    val values = ContentValues(1).apply {
      put(BADGES, badgeListBuilder.build().toByteArray())
    }

    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setCapabilities(id: RecipientId, capabilities: SignalServiceProfile.Capabilities) {
    var value: Long = 0
    value = Bitmask.update(value, Capabilities.GROUPS_V1_MIGRATION, Capabilities.BIT_LENGTH, Recipient.Capability.fromBoolean(capabilities.isGv1Migration).serialize().toLong())
    value = Bitmask.update(value, Capabilities.SENDER_KEY, Capabilities.BIT_LENGTH, Recipient.Capability.fromBoolean(capabilities.isSenderKey).serialize().toLong())
    value = Bitmask.update(value, Capabilities.ANNOUNCEMENT_GROUPS, Capabilities.BIT_LENGTH, Recipient.Capability.fromBoolean(capabilities.isAnnouncementGroup).serialize().toLong())
    value = Bitmask.update(value, Capabilities.CHANGE_NUMBER, Capabilities.BIT_LENGTH, Recipient.Capability.fromBoolean(capabilities.isChangeNumber).serialize().toLong())
    value = Bitmask.update(value, Capabilities.STORIES, Capabilities.BIT_LENGTH, Recipient.Capability.fromBoolean(capabilities.isStories).serialize().toLong())

    val values = ContentValues(1).apply {
      put(CAPABILITIES, value)
    }

    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setMentionSetting(id: RecipientId, mentionSetting: MentionSetting) {
    val values = ContentValues().apply {
      put(MENTION_SETTING, mentionSetting.id)
    }
    if (update(id, values)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  /**
   * Updates the profile key.
   *
   * If it changes, it clears out the profile key credential and resets the unidentified access mode.
   * @return true iff changed.
   */
  fun setProfileKey(id: RecipientId, profileKey: ProfileKey): Boolean {
    val selection = "$ID = ?"
    val args = arrayOf(id.serialize())
    val encodedProfileKey = Base64.encodeBytes(profileKey.serialize())
    val valuesToCompare = ContentValues(1).apply {
      put(PROFILE_KEY, encodedProfileKey)
    }
    val valuesToSet = ContentValues(3).apply {
      put(PROFILE_KEY, encodedProfileKey)
      putNull(PROFILE_KEY_CREDENTIAL)
      put(UNIDENTIFIED_ACCESS_MODE, UnidentifiedAccessMode.UNKNOWN.mode)
    }

    val updateQuery = SqlUtil.buildTrueUpdateQuery(selection, args, valuesToCompare)

    if (update(updateQuery, valuesToSet)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()

      if (id == Recipient.self().id) {
        Log.i(TAG, "Our own profile key was changed.", Throwable())
        runPostSuccessfulTransaction { /*ProfileUtil.handleSelfProfileKeyChange()*/ }//AA- not applicable
      }

      return true
    }
    return false
  }

  /**
   * Sets the profile key iff currently null.
   *
   * If it sets it, it also clears out the profile key credential and resets the unidentified access mode.
   * @return true iff changed.
   */
  fun setProfileKeyIfAbsent(id: RecipientId, profileKey: ProfileKey): Boolean {
    val selection = "$ID = ? AND $PROFILE_KEY is NULL"
    val args = arrayOf(id.serialize())
    val valuesToSet = ContentValues(3).apply {
      put(PROFILE_KEY, Base64.encodeBytes(profileKey.serialize()))
      putNull(PROFILE_KEY_CREDENTIAL)
      put(UNIDENTIFIED_ACCESS_MODE, UnidentifiedAccessMode.UNKNOWN.mode)
    }

    if (writableDatabase.update(TABLE_NAME, valuesToSet, selection, args) > 0) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      return true
    } else {
      return false
    }
  }

  /**
   * Updates the profile key credential as long as the profile key matches.
   */
  fun setProfileKeyCredential(
    id: RecipientId,
    profileKey: ProfileKey,
    profileKeyCredential: ProfileKeyCredential
  ): Boolean {
    val selection = "$ID = ? AND $PROFILE_KEY = ?"
    val args = arrayOf(id.serialize(), Base64.encodeBytes(profileKey.serialize()))
    val columnData = ProfileKeyCredentialColumnData.newBuilder()
      .setProfileKey(ByteString.copyFrom(profileKey.serialize()))
      .setProfileKeyCredential(ByteString.copyFrom(profileKeyCredential.serialize()))
      .build()
    val values = ContentValues(1).apply {
      put(PROFILE_KEY_CREDENTIAL, Base64.encodeBytes(columnData.toByteArray()))
    }
    val updateQuery = SqlUtil.buildTrueUpdateQuery(selection, args, values)

    val updated = update(updateQuery, values)
    if (updated) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }

    return updated
  }

  private fun clearProfileKeyCredential(id: RecipientId) {
    val values = ContentValues(1)
    values.putNull(PROFILE_KEY_CREDENTIAL)
    if (update(id, values)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  /**
   * Fills in gaps (nulls) in profile key knowledge from new profile keys.
   *
   *
   * If from authoritative source, this will overwrite local, otherwise it will only write to the
   * database if missing.
   */
  fun persistProfileKeySet(profileKeySet: ProfileKeySet): Set<RecipientId> {
    val profileKeys = profileKeySet.profileKeys
    val authoritativeProfileKeys = profileKeySet.authoritativeProfileKeys
    val totalKeys = profileKeys.size + authoritativeProfileKeys.size

    if (totalKeys == 0) {
      return emptySet()
    }

    Log.i(TAG, "Persisting $totalKeys Profile keys, ${authoritativeProfileKeys.size} of which are authoritative")

    val updated = HashSet<RecipientId>(totalKeys)
    val selfId = Recipient.self().id

    for ((key, value) in profileKeys) {
      val recipientId = getOrInsertFromServiceId(key)
      if (setProfileKeyIfAbsent(recipientId, value)) {
        Log.i(TAG, "Learned new profile key")
        updated.add(recipientId)
      }
    }

    for ((key, value) in authoritativeProfileKeys) {
      val recipientId = getOrInsertFromServiceId(key)

      if (selfId == recipientId) {
        Log.i(TAG, "Seen authoritative update for self")
        if (value != ProfileKeyUtil.getSelfProfileKey()) {
          Log.w(TAG, "Seen authoritative update for self that didn't match local, scheduling storage sync")
          StorageSyncHelper.scheduleSyncForDataChange()
        }
      } else {
        Log.i(TAG, "Profile key from owner $recipientId")
        if (setProfileKey(recipientId, value)) {
          Log.i(TAG, "Learned new profile key from owner")
          updated.add(recipientId)
        }
      }
    }

    return updated
  }

  fun getSimilarRecipientIds(recipient: Recipient): List<RecipientId> {
    val projection = SqlUtil.buildArgs(ID, "COALESCE(NULLIF($SYSTEM_JOINED_NAME, ''), NULLIF($PROFILE_JOINED_NAME, '')) AS checked_name")
    val where = "checked_name = ?"
    val arguments = SqlUtil.buildArgs(recipient.profileName.toString())

    readableDatabase.query(TABLE_NAME, projection, where, arguments, null, null, null).use { cursor ->
      if (cursor == null || cursor.count == 0) {
        return emptyList()
      }
      val results: MutableList<RecipientId> = ArrayList(cursor.count)
      while (cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.requireLong(ID)))
      }
      return results
    }
  }

  fun setSystemContactName(id: RecipientId, systemContactName: String) {
    val values = ContentValues().apply {
      put(SYSTEM_JOINED_NAME, systemContactName)
    }
    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setProfileName(id: RecipientId, profileName: ProfileName) {
    val contentValues = ContentValues(1).apply {
      put(PROFILE_GIVEN_NAME, profileName.givenName)
      put(PROFILE_FAMILY_NAME, profileName.familyName)
      put(PROFILE_JOINED_NAME, profileName.toString())
    }
    if (update(id, contentValues)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  fun setProfileAvatar(id: RecipientId, profileAvatar: String?) {
    val contentValues = ContentValues(1).apply {
      put(SIGNAL_PROFILE_AVATAR, profileAvatar)
    }
    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      if (id == Recipient.self().id) {
        rotateStorageId(id)
        StorageSyncHelper.scheduleSyncForDataChange()
      }
    }
  }

  fun setAbout(id: RecipientId, about: String?, emoji: String?) {
    val contentValues = ContentValues().apply {
      put(ABOUT, about)
      put(ABOUT_EMOJI, emoji)
    }

    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun setProfileSharing(id: RecipientId, enabled: Boolean) {
    val contentValues = ContentValues(1).apply {
      put(PROFILE_SHARING, if (enabled) 1 else 0)
    }
    val profiledUpdated = update(id, contentValues)

    if (profiledUpdated && enabled) {
      val group = groups.getGroup(id)
      if (group.isPresent) {
        setHasGroupsInCommon(group.get().membersRecipientId)//AA+ membersRecipientId
      }
    }

    if (profiledUpdated) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  fun setNotificationChannel(id: RecipientId, notificationChannel: String?) {
    val contentValues = ContentValues(1).apply {
      put(NOTIFICATION_CHANNEL, notificationChannel)
    }
    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun resetAllWallpaper() {
    val database = writableDatabase
    val selection = SqlUtil.buildArgs(ID, WALLPAPER_URI)
    val where = "$WALLPAPER IS NOT NULL"
    val idWithWallpaper: MutableList<Pair<RecipientId, String?>> = LinkedList()

    database.beginTransaction()
    try {
      database.query(TABLE_NAME, selection, where, null, null, null, null).use { cursor ->
        while (cursor != null && cursor.moveToNext()) {
          idWithWallpaper.add(
            Pair(
              RecipientId.from(cursor.requireInt(ID).toLong()),
              cursor.optionalString(WALLPAPER_URI).orElse(null)
            )
          )
        }
      }

      if (idWithWallpaper.isEmpty()) {
        return
      }

      val values = ContentValues(2).apply {
        putNull(WALLPAPER_URI)
        putNull(WALLPAPER)
      }

      val rowsUpdated = database.update(TABLE_NAME, values, where, null)
      if (rowsUpdated == idWithWallpaper.size) {
        for (pair in idWithWallpaper) {
          ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(pair.first())
          if (pair.second() != null) {
            WallpaperStorage.onWallpaperDeselected(context, Uri.parse(pair.second()))
          }
        }
      } else {
        throw AssertionError("expected " + idWithWallpaper.size + " but got " + rowsUpdated)
      }
    } finally {
      database.setTransactionSuccessful()
      database.endTransaction()
    }
  }

  fun setWallpaper(id: RecipientId, chatWallpaper: ChatWallpaper?) {
    setWallpaper(id, chatWallpaper?.serialize())
  }

  private fun setWallpaper(id: RecipientId, wallpaper: Wallpaper?) {
    val existingWallpaperUri = getWallpaperUri(id)
    val values = ContentValues().apply {
      put(WALLPAPER, wallpaper?.toByteArray())
      if (wallpaper != null && wallpaper.hasFile()) {
        put(WALLPAPER_URI, wallpaper.file.uri)
      } else {
        putNull(WALLPAPER_URI)
      }
    }

    if (update(id, values)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }

    if (existingWallpaperUri != null) {
      WallpaperStorage.onWallpaperDeselected(context, existingWallpaperUri)
    }
  }

  fun setDimWallpaperInDarkTheme(id: RecipientId, enabled: Boolean) {
    val wallpaper = getWallpaper(id) ?: throw IllegalStateException("No wallpaper set for $id")
    val updated = wallpaper.toBuilder()
      .setDimLevelInDarkTheme(if (enabled) ChatWallpaper.FIXED_DIM_LEVEL_FOR_DARK_THEME else 0f)
      .build()

    setWallpaper(id, updated)
  }

  private fun getWallpaper(id: RecipientId): Wallpaper? {
    readableDatabase.query(TABLE_NAME, arrayOf(WALLPAPER), ID_WHERE, SqlUtil.buildArgs(id), null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        val raw = cursor.requireBlob(WALLPAPER)
        return if (raw != null) {
          try {
            Wallpaper.parseFrom(raw)
          } catch (e: InvalidProtocolBufferException) {
            null
          }
        } else {
          null
        }
      }
    }

    return null
  }

  private fun getWallpaperUri(id: RecipientId): Uri? {
    val wallpaper = getWallpaper(id)

    return if (wallpaper != null && wallpaper.hasFile()) {
      Uri.parse(wallpaper.file.uri)
    } else {
      null
    }
  }

  fun getWallpaperUriUsageCount(uri: Uri): Int {
    val query = "$WALLPAPER_URI = ?"
    val args = SqlUtil.buildArgs(uri)

    readableDatabase.query(TABLE_NAME, arrayOf("COUNT(*)"), query, args, null, null, null).use { cursor ->
      if (cursor.moveToFirst()) {
        return cursor.getInt(0)
      }
    }

    return 0
  }

  /**
   * @return True if setting the phone number resulted in changed recipientId, otherwise false.
   */
  fun setPhoneNumber(id: RecipientId, e164: String): Boolean {
    val db = writableDatabase

    db.beginTransaction()
    return try {
      setPhoneNumberOrThrow(id, e164)
      db.setTransactionSuccessful()
      false
    } catch (e: SQLiteConstraintException) {
      Log.w(TAG, "[setPhoneNumber] Hit a conflict when trying to update $id. Possibly merging.")

      val existing: RecipientRecord = getRecord(id)
      val newId = getAndPossiblyMerge(existing.serviceId, e164, true)
      Log.w(TAG, "[setPhoneNumber] Resulting id: $newId")

      db.setTransactionSuccessful()
      newId != existing.id
    } finally {
      db.endTransaction()
    }
  }

  private fun removePhoneNumber(recipientId: RecipientId, db: SQLiteDatabase) {
    val values = ContentValues().apply {
      putNull(PHONE)
      putNull(PNI_COLUMN)
    }

    if (update(recipientId, values)) {
      rotateStorageId(recipientId)
    }
  }

  /**
   * Should only use if you are confident that this will not result in any contact merging.
   */
  @Throws(SQLiteConstraintException::class)
  fun setPhoneNumberOrThrow(id: RecipientId, e164: String) {
    val contentValues = ContentValues(1).apply {
      put(PHONE, e164)
    }
    if (update(id, contentValues)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  @Throws(SQLiteConstraintException::class)
  fun setPhoneNumberOrThrowSilent(id: RecipientId, e164: String) {
    val contentValues = ContentValues(1).apply {
      put(PHONE, e164)
    }
    if (update(id, contentValues)) {
      rotateStorageId(id)
    }
  }

  fun updateSelfPhone(e164: String) {
    val db = writableDatabase

    db.beginTransaction()
    try {
      val id = Recipient.self().id
      val newId = getAndPossiblyMerge(SignalStore.account().requireAci(), e164, highTrust = true, changeSelf = true)

      if (id == newId) {
        Log.i(TAG, "[updateSelfPhone] Phone updated for self")
      } else {
        throw AssertionError("[updateSelfPhone] Self recipient id changed when updating phone. old: $id new: $newId")
      }

      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun setUsername(id: RecipientId, username: String?) {
    if (username != null) {
      val existingUsername = getByUsername(username)
      if (existingUsername.isPresent && id != existingUsername.get()) {
        Log.i(TAG, "Username was previously thought to be owned by " + existingUsername.get() + ". Clearing their username.")
        setUsername(existingUsername.get(), null)
      }
    }

    val contentValues = ContentValues(1).apply {
      put(USERNAME, username)
    }
    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }
  }

  fun setHideStory(id: RecipientId, hideStory: Boolean) {
    updateExtras(id) { it.setHideStory(hideStory) }
    StorageSyncHelper.scheduleSyncForDataChange()
  }

  fun clearUsernameIfExists(username: String) {
    val existingUsername = getByUsername(username)
    if (existingUsername.isPresent) {
      setUsername(existingUsername.get(), null)
    }
  }

  fun getAllE164s(): Set<String> {
    val results: MutableSet<String> = HashSet()
    readableDatabase.query(TABLE_NAME, arrayOf(PHONE), null, null, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        val number = cursor.getString(cursor.getColumnIndexOrThrow(PHONE))
        if (!TextUtils.isEmpty(number)) {
          results.add(number)
        }
      }
    }
    return results
  }

  fun setPni(id: RecipientId, pni: PNI) {
    val values = ContentValues().apply {
      put(PNI_COLUMN, pni.toString())
    }
    writableDatabase.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(id))
  }

  /**
   * @return True if setting the UUID resulted in changed recipientId, otherwise false.
   */
  fun markRegistered(id: RecipientId, serviceId: ServiceId): Boolean {//AA+ see AA implementation below
    val db = writableDatabase

    db.beginTransaction()
    try {
      markRegisteredOrThrow(id, serviceId)
      db.setTransactionSuccessful()
      return false
    } catch (e: SQLiteConstraintException) {
      Log.w(TAG, "[markRegistered] Hit a conflict when trying to update $id. Possibly merging.")

      val existing = getRecord(id)
      val newId = getAndPossiblyMerge(serviceId, existing.e164, true)
      Log.w(TAG, "[markRegistered] Merged into $newId")

      db.setTransactionSuccessful()
      return newId != existing.id
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Should only use if you are confident that this shouldn't result in any contact merging.
   */
  fun markRegisteredOrThrow(id: RecipientId, serviceId: ServiceId) {
    val contentValues = ContentValues(2).apply {
      put(REGISTERED, RegisteredState.REGISTERED.id)
      put(SERVICE_ID, serviceId.toString().lowercase())
    }
    if (update(id, contentValues)) {
      setStorageIdIfNotSet(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun markUnregistered(id: RecipientId) {
    val contentValues = ContentValues(2).apply {
      put(REGISTERED, RegisteredState.NOT_REGISTERED.id)
      putNull(STORAGE_SERVICE_ID)
    }
    if (update(id, contentValues)) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun bulkUpdatedRegisteredStatus(registered: Map<RecipientId, ServiceId?>, unregistered: Collection<RecipientId>) {
    val db = writableDatabase

    db.beginTransaction()
    try {
      for ((recipientId, aci) in registered) {
        val values = ContentValues(2).apply {
          put(REGISTERED, RegisteredState.REGISTERED.id)
          if (aci != null) {
            put(SERVICE_ID, aci.toString().lowercase())
          }
        }

        try {
          if (update(recipientId, values)) {
            setStorageIdIfNotSet(recipientId)
          }
        } catch (e: SQLiteConstraintException) {
          Log.w(TAG, "[bulkUpdateRegisteredStatus] Hit a conflict when trying to update $recipientId. Possibly merging.")
          val e164 = getRecord(recipientId).e164
          val newId = getAndPossiblyMerge(aci, e164, true)
          Log.w(TAG, "[bulkUpdateRegisteredStatus] Merged into $newId")
        }
      }

      for (id in unregistered) {
        val values = ContentValues(2).apply {
          put(REGISTERED, RegisteredState.NOT_REGISTERED.id)
          putNull(STORAGE_SERVICE_ID)
        }
        update(id, values)
      }

      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /**
   * Handles inserts the (e164, UUID) pairs, which could result in merges. Does not mark users as
   * registered.
   *
   * @return A mapping of (RecipientId, UUID)
   */
  fun bulkProcessCdsResult(mapping: Map<String, ACI?>): Map<RecipientId, ACI?> {
    val db = writableDatabase
    val aciMap: MutableMap<RecipientId, ACI?> = mutableMapOf()

    db.beginTransaction()
    try {
      for ((e164, aci) in mapping) {
        var aciEntry = if (aci != null) getByServiceId(aci) else Optional.empty()

        if (aciEntry.isPresent) {
          val idChanged = setPhoneNumber(aciEntry.get(), e164)
          if (idChanged) {
            aciEntry = getByServiceId(aci!!)
          }
        }

        val id = if (aciEntry.isPresent) aciEntry.get() else getOrInsertFromE164(e164)
        aciMap[id] = aci
      }

      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    return aciMap
  }

  fun getUninvitedRecipientsForInsights(): List<RecipientId> {
    val results: MutableList<RecipientId> = LinkedList()
    val args = arrayOf((System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)).toString())

    readableDatabase.rawQuery(INSIGHTS_INVITEE_LIST, args).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))))
      }
    }

    return results
  }

  fun getRegistered(): List<RecipientId> {
    val results: MutableList<RecipientId> = LinkedList()

    readableDatabase.query(TABLE_NAME, ID_PROJECTION, "$REGISTERED = ?", arrayOf("1"), null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))))
      }
    }
    return results
  }

  fun getSystemContacts(): List<RecipientId> {
    val results: MutableList<RecipientId> = LinkedList()

    readableDatabase.query(TABLE_NAME, ID_PROJECTION, "$SYSTEM_JOINED_NAME IS NOT NULL AND $SYSTEM_JOINED_NAME != \"\"", null, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))))
      }
    }

    return results
  }

  /**
   * We no longer automatically generate a chat color. This method is used only
   * in the case of a legacy migration and otherwise should not be called.
   */
  @Deprecated("")
  fun updateSystemContactColors() {
    val db = readableDatabase
    val updates: MutableMap<RecipientId, ChatColors> = HashMap()

    db.beginTransaction()
    try {
      db.query(TABLE_NAME, arrayOf(ID, "color", CHAT_COLORS, CUSTOM_CHAT_COLORS_ID, SYSTEM_JOINED_NAME), "$SYSTEM_JOINED_NAME IS NOT NULL AND $SYSTEM_JOINED_NAME != \"\"", null, null, null, null).use { cursor ->
        while (cursor != null && cursor.moveToNext()) {
          val id = cursor.requireLong(ID)
          val serializedColor = cursor.requireString("color")
          val customChatColorsId = cursor.requireLong(CUSTOM_CHAT_COLORS_ID)
          val serializedChatColors = cursor.requireBlob(CHAT_COLORS)
          var chatColors: ChatColors? = if (serializedChatColors != null) {
            try {
              forChatColor(forLongValue(customChatColorsId), ChatColor.parseFrom(serializedChatColors))
            } catch (e: InvalidProtocolBufferException) {
              null
            }
          } else {
            null
          }

          if (chatColors != null) {
            return
          }

          chatColors = if (serializedColor != null) {
            try {
              getChatColors(MaterialColor.fromSerialized(serializedColor))
            } catch (e: UnknownColorException) {
              return
            }
          } else {
            return
          }

          val contentValues = ContentValues().apply {
            put(CHAT_COLORS, chatColors.serialize().toByteArray())
            put(CUSTOM_CHAT_COLORS_ID, chatColors.id.longValue)
          }
          db.update(TABLE_NAME, contentValues, "$ID = ?", arrayOf(id.toString()))
          updates[RecipientId.from(id)] = chatColors
        }
      }
    } finally {
      db.setTransactionSuccessful()
      db.endTransaction()
      updates.entries.forEach { ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(it.key) }
    }
  }

  fun getSignalContacts(includeSelf: Boolean): Cursor? {
    val searchSelection = ContactSearchSelection.Builder()
                                                .withRegistered(true)
                                                .withGroups(false)
                                                .excludeId(if (includeSelf) null else Recipient.self().id)
                                                .build()
    val selection = searchSelection.where
    val args = searchSelection.args
    val orderBy = "$SORT_NAME, $SYSTEM_JOINED_NAME, $SEARCH_PROFILE_NAME, $USERNAME, $PHONE"
    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy)
  }

  fun querySignalContacts(inputQuery: String, includeSelf: Boolean): Cursor? {
    val query = buildCaseInsensitiveGlobPattern(inputQuery)

    val searchSelection = ContactSearchSelection.Builder()
      .withRegistered(true)
      .withGroups(false)
      .excludeId(if (includeSelf) null else Recipient.self().id)
      .withSearchQuery(query)
      .build()
    val selection = searchSelection.where
    val args = searchSelection.args
    val orderBy = "$SORT_NAME, $SYSTEM_JOINED_NAME, $SEARCH_PROFILE_NAME, $PHONE"

    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy)
  }

  fun getNonSignalContacts(): Cursor? {
    val searchSelection = ContactSearchSelection.Builder().withNonRegistered(true)
      .withGroups(false)
      .build()
    val selection = searchSelection.where
    val args = searchSelection.args
    val orderBy = "$SYSTEM_JOINED_NAME, $PHONE"
    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy)
  }

  fun queryNonSignalContacts(inputQuery: String): Cursor? {
    val query = buildCaseInsensitiveGlobPattern(inputQuery)
    val searchSelection = ContactSearchSelection.Builder()
      .withNonRegistered(true)
      .withGroups(false)
      .withSearchQuery(query)
      .build()
    val selection = searchSelection.where
    val args = searchSelection.args
    val orderBy = "$SYSTEM_JOINED_NAME, $PHONE"
    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy)
  }

  fun getNonGroupContacts(includeSelf: Boolean): Cursor? {
    val searchSelection = ContactSearchSelection.Builder()
      .withRegistered(true)
      .withNonRegistered(true)
      .withGroups(false)
      .excludeId(if (includeSelf) null else Recipient.self().id)
      .build()
    val orderBy = orderByPreferringAlphaOverNumeric(SORT_NAME) + ", " + PHONE
    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, searchSelection.where, searchSelection.args, null, null, orderBy)
  }

  fun queryNonGroupContacts(inputQuery: String, includeSelf: Boolean): Cursor? {
    val query = buildCaseInsensitiveGlobPattern(inputQuery)

    val searchSelection = ContactSearchSelection.Builder()
      .withRegistered(true)
      .withNonRegistered(true)
      .withGroups(false)
      .excludeId(if (includeSelf) null else Recipient.self().id)
      .withSearchQuery(query)
      .build()
    val selection = searchSelection.where
    val args = searchSelection.args
    val orderBy = orderByPreferringAlphaOverNumeric(SORT_NAME) + ", " + PHONE

    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy)
  }

  fun queryAllContacts(inputQuery: String): Cursor? {
    val query = buildCaseInsensitiveGlobPattern(inputQuery)
    val selection =
      """
        $BLOCKED = ? AND 
        (
          $SORT_NAME GLOB ? OR 
          $USERNAME GLOB ? OR 
          $PHONE GLOB ? OR 
          $EMAIL GLOB ?
        )
      """.trimIndent()
    val args = SqlUtil.buildArgs("0", query, query, query, query)
    return readableDatabase.query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, null)
  }

  @JvmOverloads
  fun queryRecipientsForMentions(inputQuery: String, recipientIds: List<RecipientId>? = null): List<Recipient> {
    val query = buildCaseInsensitiveGlobPattern(inputQuery)
    var ids: String? = null

    if (Util.hasItems(recipientIds)) {
      ids = TextUtils.join(",", recipientIds?.map { it.serialize() }?.toList() ?: emptyList<String>())
    }

    val selection = "$BLOCKED = 0 AND ${if (ids != null) "$ID IN ($ids) AND " else ""}$SORT_NAME GLOB ?"
    val recipients: MutableList<Recipient> = ArrayList()

    RecipientReader(readableDatabase.query(TABLE_NAME, MENTION_SEARCH_PROJECTION, selection, SqlUtil.buildArgs(query), null, null, SORT_NAME)).use { reader ->
      var recipient: Recipient? = reader.getNext()
      while (recipient != null) {
        recipients.add(recipient)
        recipient = reader.getNext()
      }
    }

    return recipients
  }

  fun getRecipientsForMultiDeviceSync(): List<Recipient> {
    val subquery = "SELECT ${ThreadDatabase.TABLE_NAME}.${ThreadDatabase.RECIPIENT_ID} FROM ${ThreadDatabase.TABLE_NAME}"
    val selection = "$REGISTERED = ? AND $GROUP_ID IS NULL AND $ID != ? AND ($SYSTEM_CONTACT_URI NOT NULL OR $ID IN ($subquery))"
    val args = arrayOf(RegisteredState.REGISTERED.id.toString(), Recipient.self().id.serialize())
    val recipients: MutableList<Recipient> = ArrayList()

    readableDatabase.query(TABLE_NAME, ID_PROJECTION, selection, args, null, null, null).use { cursor ->
      while (cursor != null && cursor.moveToNext()) {
        recipients.add(Recipient.resolved(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID)))))
      }
    }
    return recipients
  }

  /**
   * @param lastInteractionThreshold Only include contacts that have been interacted with since this time.
   * @param lastProfileFetchThreshold Only include contacts that haven't their profile fetched after this time.
   * @param limit Only return at most this many contact.
   */
  fun getRecipientsForRoutineProfileFetch(lastInteractionThreshold: Long, lastProfileFetchThreshold: Long, limit: Int): List<RecipientId> {
    val threadDatabase = threads
    val recipientsWithinInteractionThreshold: MutableSet<Recipient> = LinkedHashSet()

    threadDatabase.readerFor(threadDatabase.getRecentPushConversationList(-1, false)).use { reader ->
      var record: ThreadRecord? = reader.next

      while (record != null && record.date > lastInteractionThreshold) {
        val recipient = Recipient.resolved(record.recipient.id)
        if (recipient.isGroup) {
          recipientsWithinInteractionThreshold.addAll(recipient.participants)
        } else {
          recipientsWithinInteractionThreshold.add(recipient)
        }
        record = reader.next
      }
    }

    return recipientsWithinInteractionThreshold
      .filterNot { it.isSelf }
      .filter { it.lastProfileFetchTime < lastProfileFetchThreshold }
      .take(limit)
      .map { it.id }
      .toMutableList()
  }

  fun markProfilesFetched(ids: Collection<RecipientId>, time: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val values = ContentValues(1).apply {
        put(LAST_PROFILE_FETCH, time)
      }

      for (id in ids) {
        db.update(TABLE_NAME, values, ID_WHERE, arrayOf(id.serialize()))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun applyBlockedUpdate(blocked: List<SignalServiceAddress>, groupIds: List<ByteArray?>) {
    val blockedE164 = blocked
      .filter { b: SignalServiceAddress -> b.number.isPresent }
      .map { b: SignalServiceAddress -> b.number.get() }
      .toList()

    val blockedUuid = blocked
      .map { b: SignalServiceAddress -> b.serviceId.toString().lowercase() }
      .toList()

    val db = writableDatabase
    db.beginTransaction()
    try {
      val resetBlocked = ContentValues().apply {
        put(BLOCKED, 0)
      }
      db.update(TABLE_NAME, resetBlocked, null, null)

      val setBlocked = ContentValues().apply {
        put(BLOCKED, 1)
        put(PROFILE_SHARING, 0)
      }

      for (e164 in blockedE164) {
        db.update(TABLE_NAME, setBlocked, "$PHONE = ?", arrayOf(e164))
      }

      for (uuid in blockedUuid) {
        db.update(TABLE_NAME, setBlocked, "$SERVICE_ID = ?", arrayOf(uuid))
      }

      val groupIdStrings: MutableList<V1> = ArrayList(groupIds.size)
      for (raw in groupIds) {
        try {
          groupIdStrings.add(GroupId.v1(raw))
        } catch (e: BadGroupIdException) {
          Log.w(TAG, "[applyBlockedUpdate] Bad GV1 ID!")
        }
      }

      for (groupId in groupIdStrings) {
        db.update(TABLE_NAME, setBlocked, "$GROUP_ID = ?", arrayOf(groupId.toString()))
      }

      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    ApplicationDependencies.getRecipientCache().clear()
  }

  fun updateStorageId(recipientId: RecipientId, id: ByteArray?) {
    updateStorageIds(Collections.singletonMap(recipientId, id))
  }

  private fun updateStorageIds(ids: Map<RecipientId, ByteArray?>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for ((key, value) in ids) {
        val values = ContentValues().apply {
          put(STORAGE_SERVICE_ID, Base64.encodeBytes(value!!))
        }
        db.update(TABLE_NAME, values, ID_WHERE, arrayOf(key.serialize()))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }

    for (id in ids.keys) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  fun markPreMessageRequestRecipientsAsProfileSharingEnabled(messageRequestEnableTime: Long) {
    val whereArgs = SqlUtil.buildArgs(messageRequestEnableTime, messageRequestEnableTime)
    val select =
      """
        SELECT r.$ID FROM $TABLE_NAME AS r 
        INNER JOIN ${ThreadDatabase.TABLE_NAME} AS t ON t.${ThreadDatabase.RECIPIENT_ID} = r.$ID 
        WHERE
          r.$PROFILE_SHARING = 0 AND (
            EXISTS(SELECT 1 FROM ${SmsDatabase.TABLE_NAME} WHERE ${SmsDatabase.THREAD_ID} = t.${ThreadDatabase.ID} AND ${SmsDatabase.DATE_RECEIVED} < ?) OR
            EXISTS(SELECT 1 FROM ${MmsDatabase.TABLE_NAME} WHERE ${MmsDatabase.THREAD_ID} = t.${ThreadDatabase.ID} AND ${MmsDatabase.DATE_RECEIVED} < ?)
          )
      """.trimIndent()

    val idsToUpdate: MutableList<Long> = ArrayList()
    readableDatabase.rawQuery(select, whereArgs).use { cursor ->
      while (cursor.moveToNext()) {
        idsToUpdate.add(cursor.requireLong(ID))
      }
    }

    if (Util.hasItems(idsToUpdate)) {
      val query = SqlUtil.buildCollectionQuery(ID, idsToUpdate)
      val values = ContentValues(1).apply {
        put(PROFILE_SHARING, 1)
      }

      writableDatabase.update(TABLE_NAME, values, query.where, query.whereArgs)

      for (id in idsToUpdate) {
        ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(RecipientId.from(id))
      }
    }
  }

  fun setHasGroupsInCommon(recipientIds: List<RecipientId?>) {
    if (recipientIds.isEmpty()) {
      return
    }

    var query = SqlUtil.buildCollectionQuery(ID, recipientIds)
    val db = writableDatabase

    db.query(TABLE_NAME, arrayOf(ID), "${query.where} AND $GROUPS_IN_COMMON = 0", query.whereArgs, null, null, null).use { cursor ->
      val idsToUpdate: MutableList<Long> = ArrayList(cursor.count)

      while (cursor.moveToNext()) {
        idsToUpdate.add(cursor.requireLong(ID))
      }

      if (Util.hasItems(idsToUpdate)) {
        query = SqlUtil.buildCollectionQuery(ID, idsToUpdate)
        val values = ContentValues().apply {
          put(GROUPS_IN_COMMON, 1)
        }

        val count = db.update(TABLE_NAME, values, query.where, query.whereArgs)
        if (count > 0) {
          for (id in idsToUpdate) {
            ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(RecipientId.from(id))
          }
        }
      }
    }
  }

  fun manuallyShowAvatar(recipientId: RecipientId) {
    updateExtras(recipientId) { b: RecipientExtras.Builder -> b.setManuallyShownAvatar(true) }
  }

  private fun updateExtras(recipientId: RecipientId, updater: java.util.function.Function<RecipientExtras.Builder, RecipientExtras.Builder>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.query(TABLE_NAME, arrayOf(ID, EXTRAS), ID_WHERE, SqlUtil.buildArgs(recipientId), null, null, null).use { cursor ->
        if (cursor.moveToNext()) {
          val state = getRecipientExtras(cursor)
          val builder = if (state != null) state.toBuilder() else RecipientExtras.newBuilder()
          val updatedState = updater.apply(builder).build().toByteArray()
          val values = ContentValues(1).apply {
            put(EXTRAS, updatedState)
          }
          db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(cursor.requireLong(ID)))
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipientId)
  }

  /**
   * Does not trigger any recipient refreshes -- it is assumed the caller handles this.
   * Will *not* give storageIds to those that shouldn't get them (e.g. MMS groups, unregistered
   * users).
   */
  fun rotateStorageId(recipientId: RecipientId) {
    val values = ContentValues(1).apply {
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()))
    }

    val query = "$ID = ? AND ($GROUP_TYPE IN (?, ?, ?) OR $REGISTERED = ?)"
    val args = SqlUtil.buildArgs(recipientId, GroupType.SIGNAL_V1.id, GroupType.SIGNAL_V2.id, GroupType.DISTRIBUTION_LIST, RegisteredState.REGISTERED.id)
    writableDatabase.update(TABLE_NAME, values, query, args)
  }

  /**
   * Does not trigger any recipient refreshes -- it is assumed the caller handles this.
   */
  fun setStorageIdIfNotSet(recipientId: RecipientId) {
    val values = ContentValues(1).apply {
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()))
    }

    val query = "$ID = ? AND $STORAGE_SERVICE_ID IS NULL"
    val args = SqlUtil.buildArgs(recipientId)
    writableDatabase.update(TABLE_NAME, values, query, args)
  }

  /**
   * Updates a group recipient with a new V2 group ID. Should only be done as a part of GV1->GV2
   * migration.
   */
  fun updateGroupId(v1Id: V1, v2Id: V2) {
    val values = ContentValues().apply {
      put(GROUP_ID, v2Id.toString())
      put(GROUP_TYPE, GroupType.SIGNAL_V2.id)
    }

    val query = SqlUtil.buildTrueUpdateQuery("$GROUP_ID = ?", SqlUtil.buildArgs(v1Id), values)
    if (update(query, values)) {
      val id = getByGroupId(v2Id).get()
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  /**
   * Will update the database with the content values you specified. It will make an intelligent
   * query such that this will only return true if a row was *actually* updated.
   */
  private fun update(id: RecipientId, contentValues: ContentValues): Boolean {
    val updateQuery = SqlUtil.buildTrueUpdateQuery(ID_WHERE, SqlUtil.buildArgs(id), contentValues)
    return update(updateQuery, contentValues)
  }

  /**
   * Will update the database with the {@param contentValues} you specified.
   *
   *
   * This will only return true if a row was *actually* updated with respect to the where clause of the {@param updateQuery}.
   */
  private fun update(updateQuery: SqlUtil.Query, contentValues: ContentValues): Boolean {
    return writableDatabase.update(TABLE_NAME, contentValues, updateQuery.where, updateQuery.whereArgs) > 0
  }

  private fun getByColumn(column: String, value: String): Optional<RecipientId> {
    val query = "$column = ?"
    val args = arrayOf(value)

    readableDatabase.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
      return if (cursor != null && cursor.moveToFirst()) {
        Optional.of(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))))
      } else {
        Optional.empty()
      }
    }
  }

  private fun getOrInsertByColumn(column: String, value: String, contentValues: ContentValues = contentValuesOf(column to value)): GetOrInsertResult {
    if (TextUtils.isEmpty(value)) {
      throw AssertionError("$column cannot be empty.")
    }

    var existing = getByColumn(column, value)

    if (existing.isPresent) {
      return GetOrInsertResult(existing.get(), false)
    } else {
      val id = writableDatabase.insert(TABLE_NAME, null, contentValues)
      if (id < 0) {
        existing = getByColumn(column, value)
        if (existing.isPresent) {
          return GetOrInsertResult(existing.get(), false)
        } else {
          throw AssertionError("Failed to insert recipient!")
        }
      } else {
        return GetOrInsertResult(RecipientId.from(id), true)
      }
    }
  }

  /**
   * Merges one ACI recipient with an E164 recipient. It is assumed that the E164 recipient does
   * *not* have an ACI.
   */
  private fun merge(byAci: RecipientId, byE164: RecipientId): RecipientId {
    ensureInTransaction()
    val db = writableDatabase
    val aciRecord = getRecord(byAci)
    val e164Record = getRecord(byE164)

    // Identities
    ApplicationDependencies.getProtocolStore().aci().identities().delete(e164Record.e164!!)

    // Group Receipts
    val groupReceiptValues = ContentValues()
    groupReceiptValues.put(GroupReceiptDatabase.RECIPIENT_ID, byAci.serialize())
    db.update(GroupReceiptDatabase.TABLE_NAME, groupReceiptValues, GroupReceiptDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164))

    // Groups
    val groupDatabase = groups
    for (group in groupDatabase.getGroupsContainingMember(Recipient.resolved(byE164).getUfsrvUid(), false, true)) {//AA+ ufsrv
      val newMembers = LinkedHashSet(group.getMembersRecipientId()).apply {//AA+ getMembersRecipientId
        remove(byE164)
        add(byAci)
      }

      val groupValues = ContentValues().apply {
        put(GroupDatabase.MEMBERS, RecipientId.toSerializedList(newMembers))
      }
      db.update(GroupDatabase.TABLE_NAME, groupValues, GroupDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(group.recipientId))

      if (group.isV2Group) {
//        groupDatabase.removeUnmigratedV1Members(group.id.requireV2(), listOf(byE164))//AA-
      }
    }

    // Threads
    val threadMerge = threads.merge(byAci, byE164)

    // SMS Messages
    val smsValues = ContentValues().apply {
      put(SmsDatabase.RECIPIENT_ID, byAci.serialize())
    }
    db.update(SmsDatabase.TABLE_NAME, smsValues, SmsDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164))

    if (threadMerge.neededMerge) {
      val values = ContentValues().apply {
        put(SmsDatabase.THREAD_ID, threadMerge.threadId)
      }
      db.update(SmsDatabase.TABLE_NAME, values, SmsDatabase.THREAD_ID + " = ?", SqlUtil.buildArgs(threadMerge.previousThreadId))
    }

    // MMS Messages
    val mmsValues = ContentValues().apply {
      put(MmsDatabase.RECIPIENT_ID, byAci.serialize())
    }
    db.update(MmsDatabase.TABLE_NAME, mmsValues, MmsDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164))

    if (threadMerge.neededMerge) {
      val values = ContentValues()
      values.put(MmsDatabase.THREAD_ID, threadMerge.threadId)
      db.update(MmsDatabase.TABLE_NAME, values, MmsDatabase.THREAD_ID + " = ?", SqlUtil.buildArgs(threadMerge.previousThreadId))
    }

    // Sessions
    val localAci: ACI = SignalStore.account().aci!!
    val sessionDatabase = sessions
    val hasE164Session = sessionDatabase.getAllFor(localAci, e164Record.e164).isNotEmpty()
    val hasAciSession = sessionDatabase.getAllFor(localAci, aciRecord.serviceId.toString()).isNotEmpty()

    if (hasE164Session && hasAciSession) {
      Log.w(TAG, "Had a session for both users. Deleting the E164.", true)
      sessionDatabase.deleteAllFor(localAci, e164Record.e164)
    } else if (hasE164Session && !hasAciSession) {
      Log.w(TAG, "Had a session for E164, but not ACI. Re-assigning to the ACI.", true)
      val values = ContentValues().apply {
        put(SessionDatabase.ADDRESS, aciRecord.serviceId.toString())
      }
      db.update(SessionDatabase.TABLE_NAME, values, "${SessionDatabase.ACCOUNT_ID} = ? AND ${SessionDatabase.ADDRESS} = ?", SqlUtil.buildArgs(localAci, e164Record.e164))
    } else if (!hasE164Session && hasAciSession) {
      Log.w(TAG, "Had a session for ACI, but not E164. No action necessary.", true)
    } else {
      Log.w(TAG, "Had no sessions. No action necessary.", true)
    }

    // MSL
    messageLog.remapRecipient(byE164, byAci)

    // Mentions
    val mentionRecipientValues = ContentValues().apply {
      put(MentionDatabase.RECIPIENT_ID, byAci.serialize())
    }
    db.update(MentionDatabase.TABLE_NAME, mentionRecipientValues, MentionDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164))

    if (threadMerge.neededMerge) {
      val mentionThreadValues = ContentValues().apply {
        put(MentionDatabase.THREAD_ID, threadMerge.threadId)
      }
      db.update(MentionDatabase.TABLE_NAME, mentionThreadValues, MentionDatabase.THREAD_ID + " = ?", SqlUtil.buildArgs(threadMerge.previousThreadId))
    }
    threads.setLastScrolled(threadMerge.threadId, 0)
    threads.update(threadMerge.threadId, false, false)

    // Reactions
    reactions.remapRecipient(byE164, byAci)

    // Notification Profiles
    notificationProfiles.remapRecipient(byE164, byAci)

    // DistributionLists
    distributionLists.remapRecipient(byE164, byAci)

    // Story Sends
    storySends.remapRecipient(byE164, byAci)

    // Recipient
    Log.w(TAG, "Deleting recipient $byE164", true)
    db.delete(TABLE_NAME, ID_WHERE, SqlUtil.buildArgs(byE164))
    RemappedRecords.getInstance().addRecipient(byE164, byAci)

    val uuidValues = ContentValues().apply {
      put(PHONE, e164Record.e164)
      put(BLOCKED, e164Record.isBlocked || aciRecord.isBlocked)
      put(MESSAGE_RINGTONE, Optional.ofNullable(aciRecord.messageRingtone).or(Optional.ofNullable(e164Record.messageRingtone)).map { obj: Uri? -> obj.toString() }.orElse(null))
      put(MESSAGE_VIBRATE, if (aciRecord.messageVibrateState != VibrateState.DEFAULT) aciRecord.messageVibrateState.id else e164Record.messageVibrateState.id)
      put(CALL_RINGTONE, Optional.ofNullable(aciRecord.callRingtone).or(Optional.ofNullable(e164Record.callRingtone)).map { obj: Uri? -> obj.toString() }.orElse(null))
      put(CALL_VIBRATE, if (aciRecord.callVibrateState != VibrateState.DEFAULT) aciRecord.callVibrateState.id else e164Record.callVibrateState.id)
      put(NOTIFICATION_CHANNEL, aciRecord.notificationChannel ?: e164Record.notificationChannel)
      put(MUTE_UNTIL, if (aciRecord.muteUntil > 0) aciRecord.muteUntil else e164Record.muteUntil)
      put(CHAT_COLORS, Optional.ofNullable(aciRecord.chatColors).or(Optional.ofNullable(e164Record.chatColors)).map { colors: ChatColors? -> colors!!.serialize().toByteArray() }.orElse(null))
      put(AVATAR_COLOR, aciRecord.avatarColor.serialize())
      put(CUSTOM_CHAT_COLORS_ID, Optional.ofNullable(aciRecord.chatColors).or(Optional.ofNullable(e164Record.chatColors)).map { colors: ChatColors? -> colors!!.id.longValue }.orElse(null))
      put(SEEN_INVITE_REMINDER, e164Record.insightsBannerTier.id)
      put(DEFAULT_SUBSCRIPTION_ID, e164Record.getDefaultSubscriptionId().orElse(-1))
      put(MESSAGE_EXPIRATION_TIME, if (aciRecord.expireMessages > 0) aciRecord.expireMessages else e164Record.expireMessages)
      put(REGISTERED, RegisteredState.REGISTERED.id)
      put(SYSTEM_GIVEN_NAME, e164Record.systemProfileName.givenName)
      put(SYSTEM_FAMILY_NAME, e164Record.systemProfileName.familyName)
      put(SYSTEM_JOINED_NAME, e164Record.systemProfileName.toString())
      put(SYSTEM_PHOTO_URI, e164Record.systemContactPhotoUri)
      put(SYSTEM_PHONE_LABEL, e164Record.systemPhoneLabel)
      put(SYSTEM_CONTACT_URI, e164Record.systemContactUri)
      put(PROFILE_SHARING, aciRecord.profileSharing || e164Record.profileSharing)
      put(CAPABILITIES, max(aciRecord.rawCapabilities, e164Record.rawCapabilities))
      put(MENTION_SETTING, if (aciRecord.mentionSetting != MentionSetting.ALWAYS_NOTIFY) aciRecord.mentionSetting.id else e164Record.mentionSetting.id)
    }

    if (aciRecord.profileKey != null) {
      updateProfileValuesForMerge(uuidValues, aciRecord)
    } else if (e164Record.profileKey != null) {
      updateProfileValuesForMerge(uuidValues, e164Record)
    }

    db.update(TABLE_NAME, uuidValues, ID_WHERE, SqlUtil.buildArgs(byAci))
    return byAci
  }

  private fun ensureInTransaction() {
    check(writableDatabase.inTransaction()) { "Must be in a transaction!" }
  }

  private fun buildContentValuesForNewUser(e164: String?, serviceId: ServiceId?): ContentValues {
    val values = ContentValues()
    values.put(PHONE, e164)
    if (serviceId != null) {
      values.put(SERVICE_ID, serviceId.toString().lowercase())
      values.put(REGISTERED, RegisteredState.REGISTERED.id)
      values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()))
      values.put(AVATAR_COLOR, AvatarColor.random().serialize())
    }
    return values
  }

  private fun getValuesForStorageContact(contact: SignalContactRecord, isInsert: Boolean): ContentValues {
    return ContentValues().apply {
      val profileName = ProfileName.fromParts(contact.givenName.orElse(null), contact.familyName.orElse(null))
      val username = contact.username.orElse(null)

      if (contact.address.hasValidServiceId()) {
        put(SERVICE_ID, contact.address.serviceId.toString())
      }

      put(PHONE, contact.address.number.orElse(null))
      put(PROFILE_GIVEN_NAME, profileName.givenName)
      put(PROFILE_FAMILY_NAME, profileName.familyName)
      put(PROFILE_JOINED_NAME, profileName.toString())
      put(PROFILE_KEY, contact.profileKey.map { source -> Base64.encodeBytes(source) }.orElse(null))
      put(USERNAME, if (TextUtils.isEmpty(username)) null else username)
      put(PROFILE_SHARING, if (contact.isProfileSharingEnabled) "1" else "0")
      put(BLOCKED, if (contact.isBlocked) "1" else "0")
      put(MUTE_UNTIL, contact.muteUntil)
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(contact.id.raw))

      if (contact.hasUnknownFields()) {
        put(STORAGE_PROTO, Base64.encodeBytes(Objects.requireNonNull(contact.serializeUnknownFields())))
      } else {
        putNull(STORAGE_PROTO)
      }

      if (isInsert) {
        put(AVATAR_COLOR, AvatarColor.random().serialize())
      }
    }
  }

  private fun getValuesForStorageGroupV1(groupV1: SignalGroupV1Record, isInsert: Boolean): ContentValues {
    return ContentValues().apply {
      put(GROUP_ID, GroupId.v1orThrow(groupV1.groupId).toString())
      put(GROUP_TYPE, GroupType.SIGNAL_V1.id)
      put(PROFILE_SHARING, if (groupV1.isProfileSharingEnabled) "1" else "0")
      put(BLOCKED, if (groupV1.isBlocked) "1" else "0")
      put(MUTE_UNTIL, groupV1.muteUntil)
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(groupV1.id.raw))

      if (groupV1.hasUnknownFields()) {
        put(STORAGE_PROTO, Base64.encodeBytes(groupV1.serializeUnknownFields()))
      } else {
        putNull(STORAGE_PROTO)
      }

      if (isInsert) {
        put(AVATAR_COLOR, AvatarColor.random().serialize())
      }
    }
  }

  private fun getValuesForStorageGroupV2(groupV2: SignalGroupV2Record, isInsert: Boolean): ContentValues {
    return ContentValues().apply {
      put(GROUP_ID, GroupId.v2(groupV2.masterKeyOrThrow).toString())
      put(GROUP_TYPE, GroupType.SIGNAL_V2.id)
      put(PROFILE_SHARING, if (groupV2.isProfileSharingEnabled) "1" else "0")
      put(BLOCKED, if (groupV2.isBlocked) "1" else "0")
      put(MUTE_UNTIL, groupV2.muteUntil)
      put(STORAGE_SERVICE_ID, Base64.encodeBytes(groupV2.id.raw))
      put(MENTION_SETTING, if (groupV2.notifyForMentionsWhenMuted()) MentionSetting.ALWAYS_NOTIFY.id else MentionSetting.DO_NOT_NOTIFY.id)

      if (groupV2.hasUnknownFields()) {
        put(STORAGE_PROTO, Base64.encodeBytes(groupV2.serializeUnknownFields()))
      } else {
        putNull(STORAGE_PROTO)
      }

      if (isInsert) {
        put(AVATAR_COLOR, AvatarColor.random().serialize())
      }
    }
  }

  fun getRecord(context: Context, cursor: Cursor): RecipientRecord {
    return getRecord(context, cursor, ID)
  }

  fun getRecord(context: Context, cursor: Cursor, idColumnName: String): RecipientRecord {
    val profileKeyString = cursor.requireString(PROFILE_KEY)
    val profileKeyCredentialString = cursor.requireString(PROFILE_KEY_CREDENTIAL)
    var profileKey: ByteArray? = null
    var profileKeyCredential: ProfileKeyCredential? = null

    if (profileKeyString != null) {
      try {
        profileKey = Base64.decode(profileKeyString)
      } catch (e: IOException) {
        Log.w(TAG, e)
      }

      if (profileKeyCredentialString != null) {
        try {
          val columnDataBytes = Base64.decode(profileKeyCredentialString)
          val columnData = ProfileKeyCredentialColumnData.parseFrom(columnDataBytes)
          if (Arrays.equals(columnData.profileKey.toByteArray(), profileKey)) {
            profileKeyCredential = ProfileKeyCredential(columnData.profileKeyCredential.toByteArray())
          } else {
            Log.i(TAG, "Out of date profile key credential data ignored on read")
          }
        } catch (e: InvalidInputException) {
          Log.w(TAG, "Profile key credential column data could not be read", e)
        } catch (e: IOException) {
          Log.w(TAG, "Profile key credential column data could not be read", e)
        }
      }
    }

    val serializedWallpaper = cursor.requireBlob(WALLPAPER)
    val chatWallpaper: ChatWallpaper? = if (serializedWallpaper != null) {
      try {
        ChatWallpaperFactory.create(Wallpaper.parseFrom(serializedWallpaper))
      } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, "Failed to parse wallpaper.", e)
        null
      }
    } else {
      null
    }

    val customChatColorsId = cursor.requireLong(CUSTOM_CHAT_COLORS_ID)
    val serializedChatColors = cursor.requireBlob(CHAT_COLORS)
    val chatColors: ChatColors? = if (serializedChatColors != null) {
      try {
        forChatColor(forLongValue(customChatColorsId), ChatColor.parseFrom(serializedChatColors))
      } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, "Failed to parse chat colors.", e)
        null
      }
    } else {
      null
    }

    val recipientId = RecipientId.from(cursor.requireLong(idColumnName))
    val capabilities = cursor.requireLong(CAPABILITIES)

    //AA+
    var locallyAddress : LocallyAddressable ? = addressFromCursor(cursor)
    //todo: the join query doesn't seem to load values from RecipinetDatabase
    val avatarUfsrvIdX: String?
    avatarUfsrvIdX = try {
      cursor.getString(cursor.getColumnIndexOrThrow(GroupDatabase.AVATAR_UFID))
    } catch (x: IllegalArgumentException) {
      cursor.getString(cursor.getColumnIndexOrThrow((AVATAR_UFSRV_ID)))
    }
    //

    return RecipientRecord(
      id = recipientId,
      serviceId = ServiceId.parseOrNull(cursor.requireString(SERVICE_ID)),
      pni = PNI.parseOrNull(cursor.requireString(PNI_COLUMN)),
      username = cursor.requireString(USERNAME),
      e164 = cursor.requireString(PHONE),
      email = cursor.requireString(EMAIL),
      groupId = GroupId.parseNullableOrThrow(cursor.requireString(GROUP_ID)),
      distributionListId = DistributionListId.fromNullable(cursor.requireLong(DISTRIBUTION_LIST_ID)),
      groupType = GroupType.fromId(cursor.requireInt(GROUP_TYPE)),
      isBlocked = cursor.requireBoolean(BLOCKED),
      muteUntil = cursor.requireLong(MUTE_UNTIL),
      messageVibrateState = VibrateState.fromId(cursor.requireInt(MESSAGE_VIBRATE)),
      callVibrateState = VibrateState.fromId(cursor.requireInt(CALL_VIBRATE)),
      messageRingtone = Util.uri(cursor.requireString(MESSAGE_RINGTONE)),
      callRingtone = Util.uri(cursor.requireString(CALL_RINGTONE)),
      defaultSubscriptionId = cursor.requireInt(DEFAULT_SUBSCRIPTION_ID),
      expireMessages = cursor.requireInt(MESSAGE_EXPIRATION_TIME),
      registered = RegisteredState.fromId(cursor.requireInt(REGISTERED)),
      profileKey = profileKey,
      profileKeyCredential = profileKeyCredential,
      systemProfileName = ProfileName.fromParts(cursor.requireString(SYSTEM_GIVEN_NAME), cursor.requireString(SYSTEM_FAMILY_NAME)),
      systemDisplayName = cursor.requireString(SYSTEM_JOINED_NAME),
      systemContactPhotoUri = cursor.requireString(SYSTEM_PHOTO_URI),
      systemPhoneLabel = cursor.requireString(SYSTEM_PHONE_LABEL),
      systemContactUri = cursor.requireString(SYSTEM_CONTACT_URI),
      signalProfileName = ProfileName.fromParts(cursor.requireString(PROFILE_GIVEN_NAME), cursor.requireString(PROFILE_FAMILY_NAME)),
      signalProfileAvatar = cursor.requireString(SIGNAL_PROFILE_AVATAR),
      hasProfileImage = AvatarHelper.hasAvatar(context, recipientId),
      profileSharing = cursor.requireBoolean(PROFILE_SHARING),
      lastProfileFetch = cursor.requireLong(LAST_PROFILE_FETCH),
      notificationChannel = cursor.requireString(NOTIFICATION_CHANNEL),
      unidentifiedAccessMode = UnidentifiedAccessMode.fromMode(cursor.requireInt(UNIDENTIFIED_ACCESS_MODE)),
      forceSmsSelection = cursor.requireBoolean(FORCE_SMS_SELECTION),
      rawCapabilities = capabilities,
      groupsV1MigrationCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, Capabilities.GROUPS_V1_MIGRATION, Capabilities.BIT_LENGTH).toInt()),
      senderKeyCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, Capabilities.SENDER_KEY, Capabilities.BIT_LENGTH).toInt()),
      announcementGroupCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, Capabilities.ANNOUNCEMENT_GROUPS, Capabilities.BIT_LENGTH).toInt()),
      changeNumberCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, Capabilities.CHANGE_NUMBER, Capabilities.BIT_LENGTH).toInt()),
      storiesCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, Capabilities.STORIES, Capabilities.BIT_LENGTH).toInt()),
      insightsBannerTier = InsightsBannerTier.fromId(cursor.requireInt(SEEN_INVITE_REMINDER)),
      storageId = Base64.decodeNullableOrThrow(cursor.requireString(STORAGE_SERVICE_ID)),
      mentionSetting = MentionSetting.fromId(cursor.requireInt(MENTION_SETTING)),
      wallpaper = chatWallpaper,
      chatColors = chatColors,
      avatarColor = AvatarColor.deserialize(cursor.requireString(AVATAR_COLOR)),
      about = cursor.requireString(ABOUT),
      aboutEmoji = cursor.requireString(ABOUT_EMOJI),
      syncExtras = getSyncExtras(cursor),
      extras = getExtras(cursor),
      hasGroupsInCommon = cursor.requireBoolean(GROUPS_IN_COMMON),
      badges = parseBadgeList(cursor.requireBlob(BADGES)),

      //AA+
      address = Address.fromSerialized(locallyAddress.toString()),
      profileShared = cursor.getInt(cursor.getColumnIndexOrThrow(SHARED_PROFILE)) == 1,
      ufsrvUidEncoded = cursor.getString(cursor.getColumnIndexOrThrow(UFSRVUID)),
      ufsrvId = cursor.getLong(cursor.getColumnIndexOrThrow((UFSRVID))),
      ufsrvUname = cursor.getString(cursor.getColumnIndexOrThrow((UFSRVUNAME))),
      eId = cursor.getLong(cursor.getColumnIndexOrThrow((EVENTID))),

      guardianStatus = GuardianStatus.values()[cursor.getInt(cursor.getColumnIndexOrThrow(GUARDIAN_STATUS))],

      recipientType = RecipientType.fromId(cursor.getInt(cursor.getColumnIndexOrThrow((RECIPIENT_TYPE)))),
      nickname = cursor.getString(cursor.getColumnIndexOrThrow((NICKNAME))),
      avatarUfsrvId = avatarUfsrvIdX,
      sticky = GeogroupStickyState.fromId(cursor.getInt(cursor.getColumnIndexOrThrow((STICKY)))),
      permissionPresentation = Util.split(cursor.getString(cursor.getColumnIndexOrThrow((PERM_PRESENTATION))), ","),
      permissionMembership = Util.split(cursor.getString(cursor.getColumnIndexOrThrow((PREM_MEMBERSHIP))), ","),
      permissionMessaging = Util.split(cursor.getString(cursor.getColumnIndexOrThrow((PREM_MESSAGING))), ","),
      permissionAttaching = Util.split(cursor.getString(cursor.getColumnIndexOrThrow((PREM_ATTACHING))), ","),
      permissionCalling = Util.split(cursor.getString(cursor.getColumnIndexOrThrow((PREM_CALLING))), ","),
      presenceSharing = cursor.getInt(cursor.getColumnIndexOrThrow((SHARE_PRESENCE))) == 1,
      presenceShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_PRESENCE))) == 1,
      presenceInformation = cursor.getString(cursor.getColumnIndexOrThrow((PRESENCE_INFO))),
      readReceiptSharing = cursor.getInt(cursor.getColumnIndexOrThrow((SHARE_READ_RECEIPT))) == 1,
      readReceiptShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_READ_RECEIPT))) == 1,
      typingIndicatorSharing = cursor.getInt(cursor.getColumnIndexOrThrow((SHARE_ACTIVITY_STATE))) == 1,
      typingIndicatorShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_ACTIVITY_STATE))) == 1,
      locationSharing = cursor.getInt(cursor.getColumnIndexOrThrow((SHARE_LOCATION))) == 1,
      locationShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_LOCATION))) == 1,
      blockShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_BLOCK))) == 1,
      contactSharing = cursor.getInt(cursor.getColumnIndexOrThrow((SHARE_CONTACT))) == 1,
      contactShared = cursor.getInt(cursor.getColumnIndexOrThrow((SHARED_CONTACT))) == 1,
      locationInformation = cursor.getString(cursor.getColumnIndexOrThrow((LOCATION_INFO))),
      permSemanticsPresentation = PermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow((PERM_PRESENTATION_LIST_SEMANTICS)))],
      permSemanticsMembership = PermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow((PREM_MEMBERSHIP_LIST_SEMANTICS)))],
      permSemanticsMessaging = PermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow((PREM_MESSAGING_LIST_SEMANTICS)))],
      permSemanticsAttaching = PermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow((PREM_ATTACHING_LIST_SEMANTICS)))],
      permSemanticsCalling = PermissionListSemantics.values()[cursor.getInt(cursor.getColumnIndexOrThrow((PREM_CALLING_LIST_SEMANTICS)))]
      //
    )
  }

  private fun parseBadgeList(serializedBadgeList: ByteArray?): List<Badge> {
    var badgeList: BadgeList? = null
    if (serializedBadgeList != null) {
      try {
        badgeList = BadgeList.parseFrom(serializedBadgeList)
      } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, e)
      }
    }

    val badges: List<Badge>
    if (badgeList != null) {
      val protoBadges = badgeList.badgesList
      badges = ArrayList(protoBadges.size)
      for (protoBadge in protoBadges) {
        badges.add(Badges.fromDatabaseBadge(protoBadge))
      }
    } else {
      badges = emptyList()
    }

    return badges
  }

  private fun getSyncExtras(cursor: Cursor): RecipientRecord.SyncExtras {
    val storageProtoRaw = cursor.optionalString(STORAGE_PROTO).orElse(null)
    val storageProto = if (storageProtoRaw != null) Base64.decodeOrThrow(storageProtoRaw) else null
    val archived = cursor.optionalBoolean(ThreadDatabase.ARCHIVED).orElse(false)
    val forcedUnread = cursor.optionalInt(ThreadDatabase.READ).map { status: Int -> status == ThreadDatabase.ReadStatus.FORCED_UNREAD.serialize() }.orElse(false)
    val groupMasterKey = cursor.optionalBlob(GroupDatabase.V2_MASTER_KEY).map { GroupUtil.requireMasterKey(it) }.orElse(null)
    val identityKey = cursor.optionalString(IDENTITY_KEY).map { Base64.decodeOrThrow(it) }.orElse(null)
    val identityStatus = cursor.optionalInt(IDENTITY_STATUS).map { VerifiedStatus.forState(it) }.orElse(VerifiedStatus.DEFAULT)

    return RecipientRecord.SyncExtras(storageProto, groupMasterKey, identityKey, identityStatus, archived, forcedUnread)
  }

  private fun getExtras(cursor: Cursor): Recipient.Extras? {
    return Recipient.Extras.from(getRecipientExtras(cursor))
  }

  private fun getRecipientExtras(cursor: Cursor): RecipientExtras? {
    return cursor.optionalBlob(EXTRAS).map { b: ByteArray? ->
      try {
        RecipientExtras.parseFrom(b)
      } catch (e: InvalidProtocolBufferException) {
        Log.w(TAG, e)
        throw AssertionError(e)
      }
    }.orElse(null)
  }

  /**
   * Builds a case-insensitive GLOB pattern for fuzzy text queries. Works with all unicode
   * characters.
   *
   * Ex:
   * cat -> [cC][aA][tT]
   */
  private fun buildCaseInsensitiveGlobPattern(query: String): String {
    if (TextUtils.isEmpty(query)) {
      return "*"
    }

    val pattern = StringBuilder()
    var i = 0
    val len = query.codePointCount(0, query.length)
    while (i < len) {
      val point = StringUtil.codePointToString(query.codePointAt(i))
      pattern.append("[")
      pattern.append(point.lowercase())
      pattern.append(point.uppercase())
      pattern.append(getAccentuatedCharRegex(point.lowercase()))
      pattern.append("]")
      i++
    }

    return "*$pattern*"
  }

  private fun getAccentuatedCharRegex(query: String): String {
    return when (query) {
      "a" -> "À-Åà-åĀ-ąǍǎǞ-ǡǺ-ǻȀ-ȃȦȧȺɐ-ɒḀḁẚẠ-ặ"
      "b" -> "ßƀ-ƅɃɓḂ-ḇ"
      "c" -> "çÇĆ-čƆ-ƈȻȼɔḈḉ"
      "d" -> "ÐðĎ-đƉ-ƍȡɖɗḊ-ḓ"
      "e" -> "È-Ëè-ëĒ-ěƎ-ƐǝȄ-ȇȨȩɆɇɘ-ɞḔ-ḝẸ-ệ"
      "f" -> "ƑƒḞḟ"
      "g" -> "Ĝ-ģƓǤ-ǧǴǵḠḡ"
      "h" -> "Ĥ-ħƕǶȞȟḢ-ḫẖ"
      "i" -> "Ì-Ïì-ïĨ-ıƖƗǏǐȈ-ȋɨɪḬ-ḯỈ-ị"
      "j" -> "ĴĵǰȷɈɉɟ"
      "k" -> "Ķ-ĸƘƙǨǩḰ-ḵ"
      "l" -> "Ĺ-łƚȴȽɫ-ɭḶ-ḽ"
      "m" -> "Ɯɯ-ɱḾ-ṃ"
      "n" -> "ÑñŃ-ŋƝƞǸǹȠȵɲ-ɴṄ-ṋ"
      "o" -> "Ò-ÖØò-öøŌ-őƟ-ơǑǒǪ-ǭǾǿȌ-ȏȪ-ȱṌ-ṓỌ-ợ"
      "p" -> "ƤƥṔ-ṗ"
      "q" -> ""
      "r" -> "Ŕ-řƦȐ-ȓɌɍṘ-ṟ"
      "s" -> "Ś-šƧƨȘșȿṠ-ṩ"
      "t" -> "Ţ-ŧƫ-ƮȚțȾṪ-ṱẗ"
      "u" -> "Ù-Üù-üŨ-ųƯ-ƱǓ-ǜȔ-ȗɄṲ-ṻỤ-ự"
      "v" -> "ƲɅṼ-ṿ"
      "w" -> "ŴŵẀ-ẉẘ"
      "x" -> "Ẋ-ẍ"
      "y" -> "ÝýÿŶ-ŸƔƳƴȲȳɎɏẎẏỲ-ỹỾỿẙ"
      "z" -> "Ź-žƵƶɀẐ-ẕ"
      "α" -> "\u0386\u0391\u03AC\u03B1\u1F00-\u1F0F\u1F70\u1F71\u1F80-\u1F8F\u1FB0-\u1FB4\u1FB6-\u1FBC"
      "ε" -> "\u0388\u0395\u03AD\u03B5\u1F10-\u1F15\u1F18-\u1F1D\u1F72\u1F73\u1FC8\u1FC9"
      "η" -> "\u0389\u0397\u03AE\u03B7\u1F20-\u1F2F\u1F74\u1F75\u1F90-\u1F9F\u1F20-\u1F2F\u1F74\u1F75\u1F90-\u1F9F\u1fc2\u1fc3\u1fc4\u1fc6\u1FC7\u1FCA\u1FCB\u1FCC"
      "ι" -> "\u038A\u0390\u0399\u03AA\u03AF\u03B9\u03CA\u1F30-\u1F3F\u1F76\u1F77\u1FD0-\u1FD3\u1FD6-\u1FDB"
      "ο" -> "\u038C\u039F\u03BF\u03CC\u1F40-\u1F45\u1F48-\u1F4D\u1F78\u1F79\u1FF8\u1FF9"
      "σ" -> "\u03A3\u03C2\u03C3"
      "ς" -> "\u03A3\u03C2\u03C3"
      "υ" -> "\u038E\u03A5\u03AB\u03C5\u03CB\u03CD\u1F50-\u1F57\u1F59\u1F5B\u1F5D\u1F5F\u1F7A\u1F7B\u1FE0-\u1FE3\u1FE6-\u1FEB"
      "ω" -> "\u038F\u03A9\u03C9\u03CE\u1F60-\u1F6F\u1F7C\u1F7D\u1FA0-\u1FAF\u1FF2-\u1FF4\u1FF6\u1FF7\u1FFA-\u1FFC"
      else -> ""
    }
  }

  private fun updateProfileValuesForMerge(values: ContentValues, record: RecipientRecord) {
    values.apply {
      put(PROFILE_KEY, if (record.profileKey != null) Base64.encodeBytes(record.profileKey) else null)
      putNull(PROFILE_KEY_CREDENTIAL)
      put(SIGNAL_PROFILE_AVATAR, record.signalProfileAvatar)
      put(PROFILE_GIVEN_NAME, record.signalProfileName.givenName)
      put(PROFILE_FAMILY_NAME, record.signalProfileName.familyName)
      put(PROFILE_JOINED_NAME, record.signalProfileName.toString())
    }
  }

  /**
   * By default, SQLite will prefer numbers over letters when sorting. e.g. (b, a, 1) is sorted as (1, a, b).
   * This order by will using a GLOB pattern to instead sort it as (a, b, 1).
   *
   * @param column The name of the column to sort by
   */
  private fun orderByPreferringAlphaOverNumeric(column: String): String {
    return "CASE WHEN $column GLOB '[0-9]*' THEN 1 ELSE 0 END, $column"
  }

  //AA+

  open fun generateNonDBRecipientRecord(tokenDetails: ContactTokenDetails): Optional<RecipientRecord> {
    var address: Address? = null
    if (!TextUtils.isEmpty(tokenDetails.username)) {
      address = Address.fromSerialized(tokenDetails.ufsrvUid)
    }
    val profileKey: ByteArray?
    profileKey = try {
      if (TextUtils.isEmpty(tokenDetails.profileKey)) null else Base64.decode(tokenDetails.profileKey)
    } catch (x: IOException) {
      Log.i(TAG, x.message)
      null
    }

    return Optional.of(RecipientRecord(
      id = RecipientId.UNKNOWN,
      serviceId = ACI.parseOrNull(tokenDetails.uuid),
      pni = null,
      username = null,
      e164 =  tokenDetails.e164number,
      email = tokenDetails.username?.let{tokenDetails.username},
      groupId = null,
      distributionListId = null,
      groupType = GroupType.NONE,
      isBlocked = false,
      muteUntil = 0,
      messageVibrateState = VibrateState.DEFAULT,
      callVibrateState = VibrateState.DEFAULT,
      messageRingtone = null,
      callRingtone = null,
      defaultSubscriptionId = 0,
      expireMessages = 0,
      registered = RegisteredState.REGISTERED,
      profileKey = profileKey,
      profileKeyCredential = null,
      systemProfileName =  ProfileName.EMPTY,
      systemDisplayName = null,
      systemContactPhotoUri = null,
      systemPhoneLabel = null,
      systemContactUri = null,
      signalProfileName = ProfileName.EMPTY,
      signalProfileAvatar = null,
      hasProfileImage = false,
      profileSharing = false,
      lastProfileFetch = 0,
      notificationChannel = null,
      unidentifiedAccessMode = UnidentifiedAccessMode.UNRESTRICTED,
      forceSmsSelection = false,
      rawCapabilities = Long.MIN_VALUE,
      groupsV1MigrationCapability = Recipient.Capability.UNKNOWN,
      senderKeyCapability = Recipient.Capability.UNKNOWN,
      announcementGroupCapability = Recipient.Capability.UNKNOWN,
      changeNumberCapability = Recipient.Capability.UNKNOWN,
      storiesCapability = Recipient.Capability.UNKNOWN,
      insightsBannerTier = InsightsBannerTier.TIER_TWO,
      storageId = null,
      mentionSetting = MentionSetting.ALWAYS_NOTIFY,
      wallpaper = null,
      chatColors = null,
      avatarColor = AvatarColor.UNKNOWN,
      about = null,
      aboutEmoji = null,
      syncExtras = null,
      extras = null,
      hasGroupsInCommon = false,
      badges = emptyList<Badge>(),

      //AA+
      address = address,
      profileShared = false,
      ufsrvUidEncoded = tokenDetails.ufsrvUid,
      ufsrvId = UfsrvUid.DecodeUfsrvSequenceId(UfsrvUid.DecodeUfsrvUid(tokenDetails.ufsrvUid)),
      ufsrvUname = tokenDetails.username?.let{tokenDetails.username},
      eId = tokenDetails.eid,

      guardianStatus = GuardianStatus.NONE,

      recipientType = RecipientType.USER,
      nickname = tokenDetails.nickname,
      avatarUfsrvId = tokenDetails.avatar,
      sticky = GeogroupStickyState.DEFAULT,
      permissionPresentation = null,
      permissionMembership = null,
      permissionMessaging = null,
      permissionAttaching = null,
      permissionCalling = null,
      presenceSharing = false,
      presenceShared = false,
      presenceInformation = null,
      readReceiptSharing = false,
      readReceiptShared = false,
      typingIndicatorSharing = false,
      typingIndicatorShared = false,
      locationSharing =false,
      locationShared = false,
      blockShared = false,
      contactSharing = false,
      contactShared = false,
      locationInformation = null,
      permSemanticsPresentation = PermissionListSemantics.NONE,
      permSemanticsMembership = PermissionListSemantics.NONE,
      permSemanticsMessaging = PermissionListSemantics.NONE,
      permSemanticsAttaching = PermissionListSemantics.NONE,
      permSemanticsCalling = PermissionListSemantics.NONE
      //
    ))

    /*return Optional.of(RecipientRecord(RecipientId.UNKNOWN,
      ACI.parseOrNull(tokenDetails.uuid),
      null,
      tokenDetails.e164number,
      null,
      null,
      GroupType.NONE,
      false,
      0,
      VibrateState.DEFAULT,
      VibrateState.DEFAULT,
      null,
      null,
      0,
      0,
      RegisteredState.REGISTERED,
      profileKey,
      null,
      ProfileName.EMPTY,
      null,
      null,
      null,
      null,
      ProfileName.EMPTY,
      null,
      false,
      false,
      0,
      null,
      UnidentifiedAccessMode.UNRESTRICTED,
      false,
      0,
      InsightsBannerTier.TIER_TWO,
      null,
      MentionSetting.ALWAYS_NOTIFY,
      null,
      null,
      AvatarColor.UNKNOWN,
      null,
      null,
      null,
      null,
      false,
      emptyList<Badge>(),
      false,
      address,
      tokenDetails.ufsrvUid, UfsrvUid.DecodeUfsrvSequenceId(UfsrvUid.DecodeUfsrvUid(tokenDetails.ufsrvUid)),
      tokenDetails.username,
      tokenDetails.eid,
      RecipientType.USER, tokenDetails.nickname, tokenDetails.avatar, GeogroupStickyState.DEFAULT,
      RecipientDatabase.GuardianStatus.NONE,
      false, false, null,
      false, false, false, false, false, false, false,
      false, false, null,
      null, null, null, null, null,
      RecipientDatabase.PermissionListSemantics.NONE, RecipientDatabase.PermissionListSemantics.NONE, RecipientDatabase.PermissionListSemantics.NONE,
      RecipientDatabase.PermissionListSemantics.NONE, RecipientDatabase.PermissionListSemantics.NONE))*/
  }

   open fun getRecipientSettingsFromFenceDescriptor(fenceDescriptor: FenceDescriptor) : RecipientRecord
  {
    val addressForFid = Address.fromSerialized(GroupId.ENCODED_SIGNAL_GROUP_V1_PREFIX + fenceDescriptor.fid)
    return RecipientRecord(
      id = RecipientId.from(0),
      serviceId = null,
      pni = null,
      username = null,
      e164 =  null,
      email = null,
      groupId = null,
      distributionListId = null,
      groupType = GroupType.NONE,
      isBlocked = false,
      muteUntil = 0,
      messageVibrateState = VibrateState.DEFAULT,
      callVibrateState = VibrateState.DEFAULT,
      messageRingtone = null,
      callRingtone = null,
      defaultSubscriptionId = 0,
      expireMessages = 0,
      registered = RegisteredState.REGISTERED,
      profileKey = null,
      profileKeyCredential = null,
      systemProfileName =  ProfileName.EMPTY,
      systemDisplayName = null,
      systemContactPhotoUri = null,
      systemPhoneLabel = null,
      systemContactUri = null,
      signalProfileName = ProfileName.EMPTY,
      signalProfileAvatar = null,
      hasProfileImage = false,
      profileSharing = false,
      lastProfileFetch = 0,
      notificationChannel = null,
      unidentifiedAccessMode = UnidentifiedAccessMode.UNRESTRICTED,
      forceSmsSelection = false,
      rawCapabilities = Long.MIN_VALUE,
      groupsV1MigrationCapability = Recipient.Capability.UNKNOWN,
      senderKeyCapability =Recipient.Capability.UNKNOWN,
      announcementGroupCapability = Recipient.Capability.UNKNOWN,
      changeNumberCapability = Recipient.Capability.UNKNOWN,
      storiesCapability = Recipient.Capability.UNKNOWN,
      insightsBannerTier = InsightsBannerTier.TIER_TWO,
      storageId = null,
      mentionSetting = MentionSetting.ALWAYS_NOTIFY,
      wallpaper = null,
      chatColors = null,
      avatarColor = AvatarColor.UNKNOWN,
      about = null,
      aboutEmoji = null,
      syncExtras = null,
      extras = null,
      hasGroupsInCommon = false,
      badges = emptyList<Badge>(),

      //AA+
      address = addressForFid,
      profileShared = false,
      ufsrvUidEncoded = null,
      ufsrvId = fenceDescriptor.fid,
      ufsrvUname =  fenceDescriptor.fcname,
      eId = fenceDescriptor.eid,

      guardianStatus = GuardianStatus.NONE,

      recipientType = RecipientType.USER,
      nickname = null,
      avatarUfsrvId = null,
      sticky = GeogroupStickyState.DEFAULT,
      permissionPresentation = null,
      permissionMembership = null,
      permissionMessaging = null,
      permissionAttaching = null,
      permissionCalling = null,
      presenceSharing = false,
      presenceShared = false,
      presenceInformation = null,
      readReceiptSharing = false,
      readReceiptShared = false,
      typingIndicatorSharing = false,
      typingIndicatorShared = false,
      locationSharing =false,
      locationShared = false,
      blockShared = false,
      contactSharing = false,
      contactShared = false,
      locationInformation = null,
      permSemanticsPresentation = PermissionListSemantics.NONE,
      permSemanticsMembership = PermissionListSemantics.NONE,
      permSemanticsMessaging = PermissionListSemantics.NONE,
      permSemanticsAttaching = PermissionListSemantics.NONE,
      permSemanticsCalling = PermissionListSemantics.NONE
      //
    )
  }

  //AA+ update setRegistered below if necessary until setRegistered is phased out as per  https://github.com/signalapp/Signal-Android/commit/a94d77d81e696ac95eb0b88a408f484b3e905fbe
  //AA+ update setRegistered below if necessary until setRegistered is phased out as per  https://github.com/signalapp/Signal-Android/commit/a94d77d81e696ac95eb0b88a408f484b3e905fbe
  /**
   * Marks the user as registered without providing a UUID. This should only be used when one
   * cannot be reasonably obtained. [.markRegistered] should be strongly
   * preferred.
   */
  open fun markRegistered(id: RecipientId) {
    val contentValues = ContentValues(1)
    contentValues.put(REGISTERED, RegisteredState.REGISTERED.id)
    if (update(id, contentValues)) {
      setStorageIdIfNotSet(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  open fun setRegistered(recipientId: RecipientId, registeredState: RegisteredState, contactTokenDetails: ContactTokenDetails) {
    val contentValues = ContentValues(1)
    contentValues.put(REGISTERED, registeredState.id)
    update(recipientId, contentValues)
    contactTokenDetails.let { setContactTokenDetails(recipientId, it) } //AA+
  }

  open fun setRegistered(activeAddresses: List<RecipientId>,
                         inactiveAddresses: List<RecipientId>,
                         activeTokens: List<ContactTokenDetails>) //A++ activeTokens)
  {
    for (activeAddress in activeAddresses) {
      val contentValues = ContentValues(1)
      contentValues.put(REGISTERED, RegisteredState.REGISTERED.id)
      update(activeAddress!!, contentValues)
      Recipient.live(activeAddress!!).refresh()
    }

    //AA+
    for (contactTokenDetails in activeTokens) {
      setContactTokenDetails(Recipient.live(contactTokenDetails.ufsrvUid).id, contactTokenDetails) //should use ufsrvid as this comes from device contacts that are registered
    }
    //
    for (inactiveAddress in inactiveAddresses) {
      val contentValues = ContentValues(1)
      contentValues.put(REGISTERED, RegisteredState.NOT_REGISTERED.id)
      update(inactiveAddress, contentValues)
      Recipient.live(inactiveAddress).refresh()
    }
  }
   open fun setContactTokenDetails(recipientId: RecipientId, token: ContactTokenDetails) {
    var liveRecipient: LiveRecipient? = null
    val ufsrvUid = UfsrvUid(token.ufsrvUid)
    val contentValues = ContentValues(6)
    contentValues.put(UFSRVUID, token.ufsrvUid)
    contentValues.put(UFSRVID, ufsrvUid.ufsrvSequenceId)
    contentValues.put(SERVICE_ID, token.uuid)
    if (!TextUtils.isEmpty(token.nickname)) {
      contentValues.put(NICKNAME, token.nickname)
    }
    if (!TextUtils.isEmpty(token.e164number)) {
      contentValues.put(PHONE, token.e164number)
    }
    contentValues.put(EVENTID, token.eid)
    if (!TextUtils.isEmpty(token.username)) {
      contentValues.put(UFSRVUNAME, token.username)
      contentValues.put(EMAIL, token.username)
    }
    contentValues.put(RECIPIENT_TYPE, RecipientType.USER.value)
    if (!TextUtils.isEmpty(token.profileKey) && token.profileKey != "0") {
      contentValues.put(PROFILE_KEY, token.profileKey)
    }
    if (!TextUtils.isEmpty(token.avatar)) {
      contentValues.put(AVATAR_UFSRV_ID, token.avatar)
    }
    try {
      liveRecipient = Recipient.live(recipientId)
      update(recipientId, contentValues)
      liveRecipient.refresh()
    } catch (x: SQLiteConstraintException) {
      Log.e(TAG, String.format(">> setContactTokenDetails: ERROR UPDATING RECIPIENT: recipientId: '%d', UFSRVUID: '%s', UUID: '%s', phone: '%s'", recipientId.toLong(), token.ufsrvUid, token.uuid, token.e164number))
      Log.e(TAG, x.message)
      val recipientConflicted = Recipient.externalContact(context, token.e164number)
      if (x.message!!.contains("recipient.phone")) { //todo: this is not surefire. Compare RecipientIds for recipientConflicted instead
        if (recipientConflicted.id.compareTo(recipientId) != 0) Recipient.consolidateUfsrvUidAndKill(LocallyAddressablePhoneNumber.from(recipientConflicted.id, token.e164number),
          LocallyAddressableUfsrvUid.from(recipientId, token.ufsrvUid))
        ApplicationDependencies.getJobManager().add(DirectoryRefreshJob(liveRecipient!!.get(), false, false)) //todo rerun the cycle or invoke the same functionction again?
      } else if (x.message!!.contains("recipient.uuid")) {
        Log.e(TAG, "setContactTokenDetails: UUID constraint violation...")
      }
    }
  }

  open fun setRecipientType(recipient: Recipient, recipientType: RecipientType) {
    val values = ContentValues(1)
    values.put(RECIPIENT_TYPE, recipientType.value)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun setE164Number(recipient: Recipient, e164Number: String?) {
    val contentValues = ContentValues(1)
    contentValues.put(PHONE, e164Number)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }
  //AA+ -------------------------------------------
  //AA+ emphasis on e164
  /*public Optional<RecipientSettings> getContactRecipientSettings (@NonNull String e164NUMBER) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = null;

    try {
      cursor = database.query(TABLE_NAME, null,  PHONE + " = ?", new String[] {e164NUMBER}, null, null, null);

      if (cursor != null && cursor.moveToNext()) {
        return Optional.ofNullable(getRecipientSettings(cursor));
      }

      return Optional.empty();
    } finally {
      if (cursor != null) cursor.close();
    }
  }*/
  open fun getContactRecord(e164NUMBER: String): Optional<RecipientRecord> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    return try {
      val table = TABLE_NAME // + " LEFT OUTER JOIN " + IdentityDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + ID + " = " + IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.RECIPIENT_ID;
      val query = TABLE_NAME + "." + PHONE + " = ?"
      val args = arrayOf(e164NUMBER)
      try {
        cursor = database.query(table, RECIPIENT_PROJECTION, query, args, null, null, null)
        if (cursor != null && cursor.moveToNext()) {
          return Optional.ofNullable(getRecord(ApplicationContext.getInstance(), cursor))
        } else {
        }
      } catch (x: Exception) {
        Log.d(TAG, x.message)
      }
      Optional.empty()
    } finally {
      cursor?.close()
    }
  }
  private fun addressFromCursor(cursor: Cursor): LocallyAddressable {
    val id = cursor.getLong(cursor.getColumnIndexOrThrow(ID))
    val phone = cursor.getString(cursor.getColumnIndexOrThrow(PHONE))
    val email = cursor.getString(cursor.getColumnIndexOrThrow(EMAIL))
    val groupId = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID))
    val ufsrvUid = cursor.getString(cursor.getColumnIndexOrThrow(UFSRVUID))
    val ufsrvId = cursor.getLong(cursor.getColumnIndexOrThrow(UFSRVID)) //fid or user sequence id
    return try {
      if (!TextUtils.isEmpty(ufsrvUid)) LocallyAddressableUfsrvUid.from(RecipientId.from(id), UfsrvUid.fromEncoded(ufsrvUid)) else if (!TextUtils.isEmpty(phone)) LocallyAddressablePhoneNumber.from(RecipientId.from(id), phone) else if (!TextUtils.isEmpty(email)) LocallyAddressablePhoneNumber.from(RecipientId.from(id), email) else if (!TextUtils.isEmpty(groupId)) LocallyAddressableGroup.from(RecipientId.from(id), groupId) else LocallyAddressableUndefined.require()
    } catch (x: java.lang.NumberFormatException) {
      Log.e(TAG, String.format("addressFromCursor: Error formatting identifier: id: '%d', ufsrvUid: '%s', phone: '%s'", id, ufsrvUid, phone))
      if (!TextUtils.isEmpty(phone)) LocallyAddressablePhoneNumber.from(RecipientId.from(id), phone) else if (!TextUtils.isEmpty(email)) LocallyAddressablePhoneNumber.from(RecipientId.from(id), email) else if (!TextUtils.isEmpty(groupId)) LocallyAddressableGroup.from(RecipientId.from(id), groupId) else LocallyAddressableUndefined.require()
    }
  }

  open fun getByUfsrvUid(e164: String): Optional<RecipientId> {
    return getByColumn(UFSRVUID, e164)
  }

  //
  /**
   * Build a [HashMap] keyed on user sequence ids and mapped to their corresponding [UfsrvUid]
   * @param shareListName Name corresponding with relevant column name for the sharelist
   * @param recipientType [org.thoughtcrime.securesms.database.MessageDatabase.ReceiptType] typically USER
   * @return constructed [HashMap]
   */
  open fun GetShareListFor(shareListName: String, recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append("$shareListName = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try  {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        try  {
          results[cursor.getLong(0)] = cursor.getString(1)
        }catch (x: java.lang.NumberFormatException) {
          continue
        }
      }
      results
    } finally  {
      cursor?.close()
    }
  }

  //presence
  open fun setPresenceInformation(recipient: Recipient, presenceInfo: String?) {
    val contentValues = ContentValues(1)
    contentValues.put(PRESENCE_INFO, presenceInfo)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setPresenceInformation(presenceInfo);
  }
  /**
   *
   * Construct a cursor for all records with which this user is sharing presence information.
   */
  open fun getPresenceSharing(): Cursor? {
    val database = databaseHelper.signalReadableDatabase
    return database.query(TABLE_NAME, arrayOf(ID, UFSRVUID, UFSRVID), SHARE_PRESENCE + " = 1",
      null, null, null, null, null)
  }

  /**
   *
   * Construct a cursor for all records which have shared their presence with this user.
   */
  open fun getPresenceShared(): Cursor? {
    val database = databaseHelper.signalReadableDatabase
    return database.query(TABLE_NAME, arrayOf(ID, UFSRVUID, UFSRVID), SHARED_PRESENCE + " = 1",
      null, null, null, null, null)
  }

  /**
   * Create a cursor-reader for presence-shared records.
   * @param cursor
   * @return
   */
  open fun readerForPresenceShared(cursor: Cursor?): PresenceSharedReader? {
    return PresenceSharedReader(context, cursor)
  }

  open fun setPresenceSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARE_PRESENCE, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
//    recipient.resolve().setPresenceSharing(enabled);
  }

  //user shared their presence with us
  open fun setPresenceShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_PRESENCE, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun GetAllPresenceSharing(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(SHARE_PRESENCE + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        try {
          results[cursor.getLong(0)] = cursor.getString(1)
        } catch (x: NumberFormatException) {
          continue
        }
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun GetAllPresenceShared(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(SHARED_PRESENCE + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        results[cursor.getLong(0)] = cursor.getString(1)
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun ClearAllPresenceSharing(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllPresenceSharing(recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setPresenceSharing(recipient, false)
    }
    return totalCleared
  }

  open fun ClearAllPresenceShared(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllPresenceShared(recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
      val recipient = Recipient.live(value!!).get()
      setPresenceShared(recipient, false)
    }
    return totalCleared
  }


  class PresenceSharedReader internal constructor(private val context: Context, private val cursor: Cursor?) {
    val current: Recipient
      get() {
        val serialized = cursor!!.getString(cursor.getColumnIndexOrThrow(UFSRVUID))
        return Recipient.live(serialized).get()
      }
    val next: Recipient?
      get() = if (cursor != null && !cursor.moveToNext()) {
        null
      } else current

    fun close() {
      cursor?.close()
    }
  }

  //

  //enable/disable read/viewed receipts with other recipients
  open fun setReadReceiptSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARE_READ_RECEIPT, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    //    recipient.resolve().setPresenceSharing(enabled);
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  //recipient who enabled/disabled shared their read/viewed receipt with us
  open fun setReadReceiptShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_READ_RECEIPT, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
//    recipient.resolve().setPresenceShared(enabled);
  }

  open fun GetAllReadReceiptSharing(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(SHARE_READ_RECEIPT + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        try {
          results[cursor.getLong(0)] = cursor.getString(1)
        } catch (x: java.lang.NumberFormatException) {
          continue
        }
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun GetAllReadReceiptShared(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(SHARED_READ_RECEIPT + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        results[cursor.getLong(0)] = cursor.getString(1)
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun ClearAllReadReceiptSharing(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllReadReceiptSharing(recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
      val recipient = Recipient.live(value!!).get()
      setReadReceiptSharing(recipient, false)
    }
    return totalCleared
  }

  open fun ClearAllReadReceiptShared(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllReadReceiptShared(recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
      val recipient = Recipient.live(value!!).get()
      setReadReceiptShared(recipient, false)
    }
    return totalCleared
  }
  //

  //contact sharing with other recipients
  //
  open fun setContactSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARE_CONTACT, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }


  open fun setContactShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_CONTACT, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun ClearAllContactSharing(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared: java.util.HashMap<Long, String> = GetShareListFor(SHARE_CONTACT, recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setReadReceiptSharing(recipient, false)
    }
    return totalCleared
  }

  open fun ClearAllContactShared(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared: java.util.HashMap<Long, String> = GetShareListFor(SHARED_CONTACT, recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setReadReceiptShared(recipient, false)
    }
    return totalCleared
  }
  //

  //location
  /**
   * Set location sharing flag for a given user (share location with user)
   */
  open fun setLocationSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARE_LOCATION, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setLocationSharing(enabled);
  }

  /**
   * Set location shared flag for a given user (who shared their location with this user)
   */
  open fun setLocationShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_LOCATION, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setLocationSharing(enabled);
  }

  open fun setLocationInformation(recipient: Recipient, locationInfo: String?) {
    val contentValues = ContentValues(1)
    contentValues.put(LOCATION_INFO, locationInfo)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setPresenceInformation(locationInfo);
  }

  open fun getLocationSharing(): Cursor? {
    val database = databaseHelper.signalReadableDatabase
    return database.query(TABLE_NAME, arrayOf(ID, UFSRVUID, UFSRVID), SHARE_LOCATION + " = 1",
      null, null, null, null, null)
  }

  open fun readerForLocationShared(cursor: Cursor?): LocationSharedReader? {
    return LocationSharedReader(context, cursor)
  }


  class LocationSharedReader internal constructor(private val context: Context, private val cursor: Cursor?) {

    val current: Recipient
      get() {
        val serialized = cursor!!.getString(cursor.getColumnIndexOrThrow(UFSRVUID))
        //      return Recipient.from(context, Address.fromSerialized(serialized), false);
        return Recipient.live(serialized).get()
      }
    val next: Recipient?
      get() = if (cursor != null && !cursor.moveToNext()) {
        null
      } else current

    fun close() {
      cursor?.close()
    }
  }
  //

  //guardian
  open fun getGuardiansCount(status: GuardianStatus): Int {
    val db = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    try {
      cursor = db.query(TABLE_NAME, arrayOf("COUNT(*)"), GUARDIAN_STATUS + " = ?", arrayOf(status.ordinal.toString()), null, null, null)
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0)
      }
    } finally {
      cursor?.close()
    }
    return 0
  }

  open fun setGuardianStatus(recipient: Recipient, guardianStatus: GuardianStatus) {
    val values = ContentValues()
    values.put(GUARDIAN_STATUS, guardianStatus.value)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun getGuardians(status: GuardianStatus): Cursor? {
    val database = databaseHelper.signalReadableDatabase
    return database.query(TABLE_NAME, arrayOf(ID, UFSRVUID, UFSRVID), GUARDIAN_STATUS + " = ?", arrayOf(status.ordinal.toString() + ""), null, null, null, null)
  }


  class GuardianReader internal constructor(private val context: Context, private val cursor: Cursor?) {

    val current: Recipient
      get() {
        val serialized = cursor!!.getString(cursor.getColumnIndexOrThrow(UFSRVUID))
        //      return Recipient.from(context, Address.fromSerialized(serialized), false);
        return Recipient.live(serialized).get()
      }
    val next: Recipient?
      get() = if (cursor != null && !cursor.moveToNext()) {
        null
      } else current

    fun close() {
      cursor?.close()
    }
  }

  //

  //permissions
  private fun GetCurrentMembers(groupRecipient: Recipient, permission: FencePermissions): MutableList<String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    return try {
      cursor = database.query(TABLE_NAME, arrayOf<String>(permissionsDbColumnHashMap?.get(permission)!!), UFSRVID + " = ?", arrayOf(groupRecipient.ufsrvId.toString() + ""),
        null, null, null)
      if (cursor != null && cursor.moveToFirst()) {
        Util.split(cursor.getString(cursor.getColumnIndexOrThrow(permissionsDbColumnHashMap?.get(permission))), ",")
      } else LinkedList<String>()
    } finally {
      cursor?.close()
    }
  }

  //default is blacklisting (open permission except for blacklisted), this's referred to as whitelisting semantic
  //logic is little inverted...
  open fun isUserPermitted(members: List<String?>, semantics: PermissionListSemantics?, userid: Long): Boolean {
    when (semantics) {
      PermissionListSemantics.WHITELIST -> return if (members.contains(userid.toString())) true else false
      PermissionListSemantics.BLACKLIST -> if (members.contains(userid.toString())) return false
      else -> return false//AA+ silence the error
    }
    return true
  }

  open fun getMembersWithPermission(context: Context, groupRecipient: Recipient, permission: FencePermissions, ignoreSelf: Boolean): List<Recipient> {
    val myUid = TextSecurePreferences.getUserId(context)
    val members: List<String?>? = GetCurrentMembers(groupRecipient, permission) //members saved in one column  'uid,uid,uid...'
    val recipientsMembers: MutableList<Recipient> = LinkedList()
    for (member in members!!) {
      if (member == myUid.toString() && ignoreSelf) {
        Log.d(TAG, String.format("getMembersWithPermission: found myself in the recipients list and 'ignoreSelf' flag is 'true'. Size:'%d'", members!!.size))
        continue
      }
      recipientsMembers.addAll(Recipient.listFromUfsrvIds(context, member!!, false))
    }
    return recipientsMembers
  }

  open fun isUserOnGroupPermissionsList(recipient: Recipient, permission: FencePermissions, uid: Long): Boolean {
    val members: List<String?>? = GetCurrentMembers(recipient, permission) //members saved in one column  'uid,uid,uid...'
    var alreadyIncluded = false
    for (member in members!!) {
      if (member == uid.toString()) {
        Log.d(TAG, String.format("SetGroupPermissionForMember (uid:'%d': User already in permissions list. Size:'%d'", uid, members!!.size))
        alreadyIncluded = true
        break
      }
    }
    return alreadyIncluded
  }

  open fun setGroupPermissions(recipient: Recipient, groupPermission: GroupPermission) {
    val values = ContentValues(2)
    values.put(permissionsDbColumnHashMap?.get(groupPermission.permission) + "_list_semantics", groupPermission.listSemantics.toString())
    values.put(permissionsDbColumnHashMap?.get(groupPermission.permission) + "_baselist", groupPermission.baseList.toString())
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun setListSemantics(recipient: Recipient, groupPermission: GroupPermission) {
    val values = ContentValues(1)
    values.put(permissionsDbColumnHashMap?.get(groupPermission.permission) + "_list_semantics", groupPermission.listSemantics.toString())
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  /*public void
  SetGroupPermissionForMembers (@NonNull Context context, @NonNull Recipient recipient, EnumFencePermissions permission, List<Address>members)
  {
    ContentValues values = new ContentValues();
    values.put(permissionsDbColumnHashMap.get(permission), Address.toSerializedList(members, ','));
    update(recipient.getId(), values);
    Recipient.live(recipient.getId()).refresh();//
  }*/

  /*public void
  SetGroupPermissionForMembers (@NonNull Context context, @NonNull Recipient recipient, EnumFencePermissions permission, List<Address>members)
  {
    ContentValues values = new ContentValues();
    values.put(permissionsDbColumnHashMap.get(permission), Address.toSerializedList(members, ','));
    update(recipient.getId(), values);
    Recipient.live(recipient.getId()).refresh();//
  }*/
  open fun SetGroupPermissionForMember(context: Context, recipient: Recipient, permission: FencePermissions, uid: Long): Long {
//    if (isUserInGroupPermissionsList(recipient, permission, uid))  return 0;
    val members = GetCurrentMembers(recipient, permission) //members saved in one column  'uid,uid,uid...'
    var alreadyIncluded = false
    for (member in members!!) {
      if (member == uid.toString()) {
        Log.d(TAG, String.format("SetGroupPermissionForMember (uid:'%d': User already in permissions list. Size:'%d'", uid, members!!.size))
        alreadyIncluded = true
        break
      }
    }
    if (alreadyIncluded) return 0
    members!!.add(uid.toString())
    val values = ContentValues()
    values.put(permissionsDbColumnHashMap?.get(permission), Util.join<String?>(members, ","))
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
    return uid
  }

  open fun DeleteGroupPermissionForMember(context: Context, recipient: Recipient, permission: FencePermissions, uid: Long): Long {
    val membersExisting: List<String?>? = GetCurrentMembers(recipient, permission) //members saved in one column  'uid,uid,uid...'
    val membersNew: MutableList<String?> = LinkedList()
    var alreadyIncluded = false
    for (member in membersExisting!!) {
      if (member == uid.toString()) {
        alreadyIncluded = true
        continue
      } else membersNew.add(member)
    }
    if (!alreadyIncluded) {
      Log.d(TAG, String.format("DeleteGroupPermissionForMember (uid:'%d': User NOT in permissions list. Size:'%d'", uid, membersExisting!!.size))
      membersNew.clear()
      return 0
    }
    val values = ContentValues()
    values.put(permissionsDbColumnHashMap?.get(permission), Util.join<String?>(membersNew, ","))
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //
    return uid
  }
  //

  //sticky groups
  open fun getStickyGeogroups(): Cursor? {
    val database = databaseHelper.signalReadableDatabase
    return database.query(TABLE_NAME, arrayOf(ID, UFSRVUID), "$STICKY = 1",
      null, null, null, null, null)
  }

  open fun readerForStickyGeogroups(cursor: Cursor?): StickyGeogroupsReader? {
    return StickyGeogroupsReader(context, cursor)
  }


  class StickyGeogroupsReader internal constructor(private val context: Context, private val cursor: Cursor?) {

    val current: Recipient
      get() {
        val id = cursor!!.getLong(cursor.getColumnIndexOrThrow(ID))
        return Recipient.live(RecipientId.from(id)).get()
      }
    val next: Recipient?
      get() = if (cursor != null && !cursor.moveToNext()) {
        null
      } else current
  }

  //

  //profile sharing
  open fun setProfileSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(PROFILE_SHARING, if (enabled) 1 else 0)
    val profiledUpdated = update(recipient.id, contentValues)
    if (profiledUpdated && enabled) {
      val group = groups.getGroup(recipient.id)
      if (group.isPresent) {
        setHasGroupsInCommon(group.get().membersRecipientId)
      }
    }
    if (profiledUpdated) {
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
    }
  }

  open fun setProfileShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_PROFILE, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun GetAllProfileSharing(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(PROFILE_SHARING + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        results[cursor.getLong(0)] = cursor.getString(1)
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun GetAllProfileShared(recipientType: RecipientType): java.util.HashMap<Long, String> {
    val database = databaseHelper.signalReadableDatabase
    var cursor: Cursor? = null
    val results = java.util.HashMap<Long, String>()
    val selectionBuilder = java.lang.StringBuilder()
    selectionBuilder.append(SHARED_PROFILE + " = 1")
    if (recipientType != RecipientType.UKNOWN) {
      selectionBuilder.append(" AND " + RECIPIENT_TYPE + " = " + recipientType.value)
    }
    //else return all
    return try {
      cursor = database.query(TABLE_NAME, arrayOf(UFSRVID, UFSRVUID), selectionBuilder.toString(),
        null, null, null, null)
      while (cursor != null && cursor.moveToNext()) {
        results[cursor.getLong(0)] = cursor.getString(1)
      }
      results
    } finally {
      cursor?.close()
    }
  }

  open fun ClearAllProfileSharing(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllProfileSharing(recipientType)
    val totalCleared = tobeCleared.size
    for (ufsrvUidValue in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(ufsrvUidValue), false);
      val recipient = Recipient.live(ufsrvUidValue!!).get()
      setProfileSharing(recipient.id, false)
    }
    return totalCleared
  }

  open fun ClearAllProfileShared(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared = GetAllProfileShared(recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setProfileShared(recipient, false)
    }
    return totalCleared
  }
  //

  open fun getAllAddresses(recipientType: RecipientType): Set<LocallyAddressable>? { //AA++
    val db = databaseHelper.signalReadableDatabase
    val results: MutableSet<LocallyAddressable> = java.util.HashSet()
    db.query(TABLE_NAME, arrayOf<String>(ID, SERVICE_ID, PHONE, EMAIL, GROUP_ID, UFSRVUID, UFSRVID), RECIPIENT_TYPE + " = ?", arrayOf<String>(recipientType.toString()), null, null, null).use { cursor ->  //AA++ selection
      while (cursor != null && cursor.moveToNext()) {
        results.add(addressFromCursor(cursor))
      }
    }
    return results
  }

  //default is 0, which is disabled (
  open fun setGeogroupSticky(recipient: Recipient, enabled: GeogroupStickyState) {
    val values = ContentValues()
    values.put(STICKY, enabled.id)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun setUfsrvUid(id: RecipientId, ufsrvUidEncoded: String?) {
    val uid = UfsrvUid(ufsrvUidEncoded)
    val ufsrvid = uid.ufsrvSequenceId
    val values = ContentValues()
    values.put(UFSRVUID, ufsrvUidEncoded)
    values.put(UFSRVID, uid.ufsrvSequenceId)
    update(id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
  }

  open fun setUfsrvId(recipient: Recipient, id: Long) {
    val values = ContentValues()
    values.put(UFSRVID, id)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setUfsrvId(id);
  }

  open fun setEid(recipient: Recipient, eid: Long) {
    val values = ContentValues()
    values.put(EVENTID, eid)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setEid(id);
  }

  open fun setNickname(recipient: Recipient, nickname: String) {
    val values = ContentValues(1)
    values.put(NICKNAME, nickname)
    update(recipient.id, values)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  open fun setAvatarUfId(recipient: Recipient, avatarUfId: String?) {
    val contentValues = ContentValues(1)
    contentValues.put(AVATAR_UFSRV_ID, avatarUfId)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id)
  }

  //AA was removed in https://github.com/signalapp/Signal-Android/commit/a94d77d81e696ac95eb0b88a408f484b3e905fbe
  @Deprecated("")
  open fun setRegistered(id: RecipientId, registeredState: RegisteredState) {
    val contentValues = ContentValues(1)
    contentValues.put(REGISTERED, registeredState.id)
    if (registeredState == RegisteredState.NOT_REGISTERED) {
      contentValues.putNull(STORAGE_SERVICE_ID)
    }
    if (update(id, contentValues)) {
      if (registeredState == RegisteredState.REGISTERED) {
        setStorageIdIfNotSet(id)
      }
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }
  open fun getRecipientSettingsUfsrvUid(ufsrvUid: String): RecipientRecord {
    val database = databaseHelper.signalReadableDatabase
    val table = TABLE_NAME // + " LEFT OUTER JOIN " + IdentityDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + ID + " = " + IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.RECIPIENT_ID;
    val query = TABLE_NAME + "." + UFSRVUID + " = ?"
    val args = arrayOf(ufsrvUid)
    database.query(table, TYPED_RECIPIENT_PROJECTION, query, args, null, null, null).use { cursor ->
      return if (cursor != null && cursor.moveToNext()) {
        getRecord(ApplicationContext.getInstance(), cursor)
      } else {
        Log.e(TAG, String.format("getRecipientSettingsUfsrvUid ('%s'): COULD NOT FIND UFSRVUID", ufsrvUid))
        throw MissingRecipientException(ufsrvUid)
      }
    }
  }

  open fun getRecipientSettingsUfsrvId(recipientUfsrvId: RecipientUfsrvId): RecipientRecord {
    val database = databaseHelper.signalReadableDatabase
    val table = TABLE_NAME // + " LEFT OUTER JOIN " + IdentityDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + ID + " = " + IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.RECIPIENT_ID;
    val query = TABLE_NAME + "." + UFSRVID + " = ?"
    val args = arrayOf(recipientUfsrvId.serialize())
    database.query(table, TYPED_RECIPIENT_PROJECTION, query, args, null, null, null).use { cursor ->
      return if (cursor != null && cursor.moveToNext()) {
        getRecord(ApplicationContext.getInstance(), cursor)
      } else {
        throw MissingRecipientException(recipientUfsrvId)
      }
    }
  }

  open fun clearProfileKey(id: RecipientId) {
    val values = ContentValues(1)
    values.putNull(PROFILE_KEY)
    if (update(id, values)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  open fun setUfsrvProfile(id: RecipientId, profile: JsonEntityProfile) {
    val contentValues = ContentValues(1)
    contentValues.put(NICKNAME, profile.nickname)
    contentValues.put(AVATAR_UFSRV_ID, profile.avatar)
    contentValues.put(UFSRVUNAME, profile.username)
    if (update(id, contentValues)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  open fun unsetUfsrvProfile(id: RecipientId) {
    val contentValues = ContentValues(1)
    contentValues.putNull(NICKNAME)
    contentValues.putNull(AVATAR_UFSRV_ID)
    contentValues.putNull(UFSRVUNAME)
    if (update(id, contentValues)) {
      rotateStorageId(id)
      ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id)
    }
  }

  /**
   * Tightly coupled operation. Presumably both recipientIds belong to the same user, so we need to consolidate
   * UfsrvUid value and kill the redundant record.
   * @param recipientIdTarget the recipient to be consolidated into
   * @param recipientFrom consolidate from
   */
  open fun copyUfsrvUidAndKill(recipientIdTarget: RecipientId, recipientFrom: Recipient) {
    val db = writableDatabase
    val whereClause = "$ID = ?"
    val whereArgs = arrayOf(recipientFrom.id.serialize())
    db.delete(TABLE_NAME, whereClause, whereArgs)

    val otherValues = ContentValues(1).apply {
      put(PHONE, recipientFrom.e164number)
      put(SYSTEM_JOINED_NAME, recipientFrom.systemProfileName.toString()) //getDisplayName());
      put(SYSTEM_FAMILY_NAME, recipientFrom.systemProfileName.familyName)
      if (recipientFrom.contactUri != null) put(SYSTEM_CONTACT_URI, recipientFrom.contactUri.toString())
      if (recipientFrom.contactPhoto != null) put(SYSTEM_PHOTO_URI, recipientFrom.contactPhoto!!.getUri(context).toString())
      put(SYSTEM_PHONE_TYPE, 7) //todo hard coded SYSTEM_PHONE_TYPE value
    }

    if (update(recipientIdTarget, otherValues)) {
      Log.e(TAG, String.format("copyUfsrvUidAndKill: successfully consolidated target Recipient '%s' from Recipient '%s'", recipientIdTarget.toString(), recipientFrom.id))
    }
  }

  //blocking
  open fun clearAllBlockedFences(recipientId: RecipientId) {
    val contents = ContentValues(1).apply {
      putNull(SHARE_BLOCKED_FENCE)
    }
    writableDatabase.update(TABLE_NAME, contents, ID + " = ?", arrayOf(recipientId.toString()))
  }

  /**
   * Whole-of-record update at once
   * @param recipientId target recipient
   * @param fids list of fids to serialise into the record
   */
  open fun updateBlockedFences(recipientId: RecipientId, fids: List<Long>) {
    Collections.sort(fids)
    val fidSerialised: MutableList<Long> = LinkedList()
    for (fid in fids) {
      fidSerialised.add(fid)
    }
    val contents = ContentValues()
    contents.put(SHARE_BLOCKED_FENCE, Util.join(fidSerialised, GroupDatabase.DELIMITER.toString() + ""))
    databaseHelper.signalWritableDatabase.update(TABLE_NAME, contents, ID + " = ?", arrayOf(recipientId.serialize()))
  }

  //Handles one fence at a time for now
  open fun updateBlockedFences(recipientId: RecipientId, blockedFids: List<Long>, updateMode: MembershipUpdateMode) {
    val storedBlokcedFences = getBlockedFences(recipientId)
    if (storedBlokcedFences!!.contains(blockedFids[0])) {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.e(TAG, String.format("updateBlockedFences: DATA ERROR: CURRENT LIST (size:'%d') ALREADY HAS '%s' in it", storedBlokcedFences!!.size, blockedFids[0]))
        return
      }
      storedBlokcedFences.remove(blockedFids[0])
    } else {
      if (updateMode == MembershipUpdateMode.ADD_MEMBER) {
        Log.d(TAG, String.format("updateBlockedFences (size:'%d'): Adding blocked fence  '%d'", storedBlokcedFences!!.size, blockedFids[0]))
        storedBlokcedFences.add(blockedFids[0])
      } else {
        Log.e(TAG, String.format("updateBlockedFences (size;'%d'): DATA ERROR: FENCE NOT IN LIST: CANNOT REMOVE '%d' FROM LIST", storedBlokcedFences!!.size, blockedFids[0]))
        return
      }
    }
    val contents = ContentValues()
    contents.put(SHARE_BLOCKED_FENCE, Util.join(storedBlokcedFences, GroupDatabase.DELIMITER.toString() + ""))
    databaseHelper.signalWritableDatabase.update(TABLE_NAME, contents, ID + " = ?", arrayOf(recipientId.serialize()))
  }

  open fun getBlockedFences(recipientId: RecipientId): LinkedList<Long> {
    var cursor: Cursor? = null
    return try {
      cursor = readableDatabase.query(TABLE_NAME, arrayOf(SHARE_BLOCKED_FENCE), ID + " = ?", arrayOf(recipientId.serialize()), null, null, null)
      if (cursor != null && cursor.moveToFirst()) {
        val serializedFids = cursor.getString(cursor.getColumnIndexOrThrow(SHARE_BLOCKED_FENCE))
        return if (TextUtils.isEmpty(serializedFids)) LinkedList<Long>() else blockedFencesfromSerializedList(serializedFids, GroupDatabase.DELIMITER)
      }
      LinkedList<Long>()
    } finally {
      cursor?.close()
    }
  }

  private fun blockedFencesfromSerializedList(serializedFids: String, delimiter: Char): LinkedList<Long> {
    val fids: LinkedList<Long> = LinkedList()
    val serializedFidsArray = DelimiterUtil.split(serializedFids, delimiter)
    for (serializedFid in serializedFidsArray) {
      if (!TextUtils.isEmpty(serializedFid)) fids.add(java.lang.Long.valueOf(serializedFid))
    }
    return fids
  }

  open fun setBlockSharing(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(BLOCKED, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setBlocked(enabled);
  }

  /**
   * set blocking flag for a given user (who have blocked this user)
   */
  open fun setBlockShared(recipient: Recipient, enabled: Boolean) {
    val contentValues = ContentValues(1)
    contentValues.put(SHARED_BLOCK, if (enabled) 1 else 0)
    update(recipient.id, contentValues)
    ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(recipient.id) //recipient.resolve().setBlockShared(enabled);
  }

  open fun ClearAllBlockSharing(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared: java.util.HashMap<Long, String> = GetShareListFor(BLOCKED, recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setBlockSharing(recipient, false)
    }
    return totalCleared
  }

  open fun ClearAllBlockShared(context: Context?, recipientType: RecipientType): Int {
    val tobeCleared: java.util.HashMap<Long, String> = GetShareListFor(SHARED_BLOCK, recipientType)
    val totalCleared = tobeCleared.size
    for (value in tobeCleared.values) {
//      Recipient recipient = Recipient.from(context, Address.fromSerialized(value), false);
      val recipient = Recipient.live(value!!).get()
      setBlockShared(recipient, false)
    }
    return totalCleared
  }
  open fun getRecipientsWithGuardianStatus(status: GuardianStatus): RecipientReader? {
    val database = databaseHelper.signalReadableDatabase
    val cursor = database.query(TABLE_NAME, arrayOf(ID, UFSRVUID), GUARDIAN_STATUS + " = ?", arrayOf(status.value.toString() + ""), null, null, null, null)
    return RecipientReader(cursor)
  }

  open fun getOrInsertFromUfsrvUid(ufsrvUid: UfsrvUid): RecipientId {
    return getOrInsertByColumn(UFSRVUID, ufsrvUid.toString()).recipientId
  }

  open fun getOrInsertFromUfsrvUid(ufsrvUid: String): RecipientDatabase.InsertResult? {
    if (TextUtils.isEmpty(ufsrvUid)) {
      throw java.lang.AssertionError("UfsrvUid cannot be empty")
    }
    val db = writableDatabase
    val query = UFSRVUID + " = ?"
    val args = arrayOf(ufsrvUid)
    db.beginTransaction()
    try {
      db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
        return if (cursor != null && cursor.moveToFirst()) {
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(cursor.getLong(0)), true)
        } else {
          val values = ContentValues()
          values.put(UFSRVUID, ufsrvUid)
          //        values.put(UUID, UuidUtil.generateSerialised());//provided through server call
          val id = db.insertOrThrow(TABLE_NAME, null, values)
          if (id < 0) throw java.lang.AssertionError("Failed to insert ufsrvUid! $ufsrvUid")
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(id), false)
        }
      }
    } finally {
      db.endTransaction()
    }
  }

  open fun getOrInsertFromUfsrvId(recipientUfsrvId: RecipientUfsrvId): RecipientDatabase.InsertResult? {
    if (recipientUfsrvId.isUnknown) {
      throw java.lang.AssertionError(String.format("Invalid RecipientUfsrvId.java ('%s')", recipientUfsrvId.serialize()))
    }
    val db = databaseHelper.signalWritableDatabase
    val query = UFSRVID + " = ?"
    val args = arrayOf(recipientUfsrvId.serialize())
    db.beginTransaction()
    try {
      db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
        return if (cursor != null && cursor.moveToFirst()) {
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(cursor.getLong(0)), true)
        } else {
          val values = ContentValues()
          values.put(UFSRVID, recipientUfsrvId.serialize())
          val id = db.insertOrThrow(TABLE_NAME, null, values)
          if (id < 0) throw java.lang.AssertionError("Failed to insert recipientUfsrvId! $recipientUfsrvId")
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(id), false)
        }
      }
    } finally {
      db.endTransaction()
    }
  }

  open fun getOrInsertFromFid(fid: Long): RecipientDatabase.InsertResult? {
    if (fid <= 0) {
      throw java.lang.AssertionError(String.format("Invalid fid ('%d')", fid))
    }
    val db = databaseHelper.signalWritableDatabase
    val query = UFSRVID + " = ?"
    val args = arrayOf(fid.toString() + "")
    db.beginTransaction()
    try {
      db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
        return if (cursor != null && cursor.moveToFirst()) {
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(cursor.getLong(0)), true)
        } else {
          val values = ContentValues()
          values.put(UFSRVID, fid)
          val id = db.insertOrThrow(TABLE_NAME, null, values)
          if (id < 0) throw java.lang.AssertionError("Failed to insert fid! $fid")
          db.setTransactionSuccessful()
         InsertResult(RecipientId.from(id), false)
        }
      }
    } finally {
      db.endTransaction()
    }
  }

  class InsertResult(val recipientId: RecipientId, val isResolved: Boolean)

  class GroupPermission(val permission: FencePermissions, val baseList: PermissionBaseList, val listSemantics: PermissionListSemantics)

  // end AA+

  private fun <T> Optional<T>.isAbsent(): Boolean {
    return !this.isPresent
  }

  private fun RecipientRecord.toLogDetails(): RecipientLogDetails {
    return RecipientLogDetails(
      id = this.id,
      serviceId = this.serviceId,
      e164 = this.e164
    )
  }

  inner class BulkOperationsHandle internal constructor(private val database: SQLiteDatabase) {
    private val pendingRecipients: MutableSet<RecipientId> = mutableSetOf()

    fun setSystemContactInfo(
      id: RecipientId,
      systemProfileName: ProfileName,
      systemDisplayName: String?,
      photoUri: String?,
      systemPhoneLabel: String?,
      systemPhoneType: Int,
      systemContactUri: String?
    ) {
      val joinedName = Util.firstNonNull(systemDisplayName, systemProfileName.toString())
      val refreshQualifyingValues = ContentValues().apply {
        put(SYSTEM_GIVEN_NAME, systemProfileName.givenName)
        put(SYSTEM_FAMILY_NAME, systemProfileName.familyName)
        put(SYSTEM_JOINED_NAME, joinedName)
        put(SYSTEM_PHOTO_URI, photoUri)
        put(SYSTEM_PHONE_LABEL, systemPhoneLabel)
        put(SYSTEM_PHONE_TYPE, systemPhoneType)
        put(SYSTEM_CONTACT_URI, systemContactUri)
      }

      val updatedValues = update(id, refreshQualifyingValues)
      if (updatedValues) {
        pendingRecipients.add(id)
      }

      val otherValues = ContentValues().apply {
        put(SYSTEM_INFO_PENDING, 0)
      }

      update(id, otherValues)
    }

    fun finish() {
      markAllRelevantEntriesDirty()
      clearSystemDataForPendingInfo()
      database.setTransactionSuccessful()
      database.endTransaction()
      pendingRecipients.forEach { id -> ApplicationDependencies.getDatabaseObserver().notifyRecipientChanged(id) }
    }

    private fun markAllRelevantEntriesDirty() {
      val query = "$SYSTEM_INFO_PENDING = ? AND $STORAGE_SERVICE_ID NOT NULL"
      val args = SqlUtil.buildArgs("1")

      database.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null).use { cursor ->
        while (cursor.moveToNext()) {
          val id = RecipientId.from(cursor.requireNonNullString(ID))
          rotateStorageId(id)
        }
      }
    }

    private fun clearSystemDataForPendingInfo() {
      val query = "$SYSTEM_INFO_PENDING = ?"
      val args = arrayOf("1")
      val values = ContentValues(5).apply {
        put(SYSTEM_INFO_PENDING, 0)
        put(SYSTEM_GIVEN_NAME, null as String?)
        put(SYSTEM_FAMILY_NAME, null as String?)
        put(SYSTEM_JOINED_NAME, null as String?)
        put(SYSTEM_PHOTO_URI, null as String?)
        put(SYSTEM_PHONE_LABEL, null as String?)
        put(SYSTEM_CONTACT_URI, null as String?)
      }

      database.update(TABLE_NAME, values, query, args)
    }
  }

  interface ColorUpdater {
    fun update(name: String, materialColor: MaterialColor?): ChatColors?
  }

  class RecipientReader internal constructor(private val cursor: Cursor) : Closeable {

    fun getCurrent(): Recipient {
      val id = RecipientId.from(cursor.requireLong(ID))
      return Recipient.resolved(id)
    }

    fun getNext(): Recipient? {
      return if (cursor.moveToNext()) {
        getCurrent()
      } else {
        null
      }
    }

    val count: Int
      get() = cursor.count

    override fun close() {
      cursor.close()
    }
  }

  class MissingRecipientException(id: RecipientId?) : IllegalStateException("Failed to find recipient with ID: $id") {
    constructor(ufsrvUid: String): this(RecipientId.UFSRV) {//AA+
      IllegalStateException("Failed to find recipient with ufsrvUid: $ufsrvUid")
    }
    constructor(ufsrvId: RecipientUfsrvId) : this(RecipientId.UFSRV) {//AA+
      IllegalStateException("Failed to find recipient with ufsrvId:   $ufsrvId")
    }
  }

  private class GetOrInsertResult(val recipientId: RecipientId, val neededInsert: Boolean)

  @VisibleForTesting
  internal class ContactSearchSelection private constructor(val where: String, val args: Array<String>) {

    @VisibleForTesting
    internal class Builder {
      private var includeRegistered = false
      private var includeNonRegistered = false
      private var excludeId: RecipientId? = null
      private var excludeGroups = false
      private var searchQuery: String? = null

      fun withRegistered(includeRegistered: Boolean): Builder {
        this.includeRegistered = includeRegistered
        return this
      }

      fun withNonRegistered(includeNonRegistered: Boolean): Builder {
        this.includeNonRegistered = includeNonRegistered
        return this
      }

      fun excludeId(recipientId: RecipientId?): Builder {
        excludeId = recipientId
        return this
      }

      fun withGroups(includeGroups: Boolean): Builder {
        excludeGroups = !includeGroups
        return this
      }

      fun withSearchQuery(searchQuery: String): Builder {
        this.searchQuery = searchQuery
        return this
      }

      fun build(): ContactSearchSelection {
        check(!(!includeRegistered && !includeNonRegistered)) { "Must include either registered or non-registered recipients in search" }
        val stringBuilder = StringBuilder("(")
        val args: MutableList<Any?> = LinkedList()

        if (includeRegistered) {
          stringBuilder.append("(")
          args.add(RegisteredState.REGISTERED.id)
//          args.add(1) //AA- removes profile sharing
          if (Util.isEmpty(searchQuery)) {
            stringBuilder.append(SIGNAL_CONTACT)
          } else {
            stringBuilder.append(QUERY_SIGNAL_CONTACT)
            args.add(searchQuery)
            args.add(searchQuery)
            args.add(searchQuery)
          }
          stringBuilder.append(")")
        }

        if (includeRegistered && includeNonRegistered) {
          stringBuilder.append(" OR ")
        }

        if (includeNonRegistered) {
          stringBuilder.append("(")
          args.add(RegisteredState.REGISTERED.id)

          if (Util.isEmpty(searchQuery)) {
            stringBuilder.append(NON_SIGNAL_CONTACT)
          } else {
            stringBuilder.append(QUERY_NON_SIGNAL_CONTACT)
            args.add(searchQuery)
            args.add(searchQuery)
            args.add(searchQuery)
          }

          stringBuilder.append(")")
        }

        stringBuilder.append(")")
        stringBuilder.append(FILTER_BLOCKED)
        args.add(0)

        if (excludeGroups) {
          stringBuilder.append(FILTER_GROUPS)
        }

        if (excludeId != null) {
          stringBuilder.append(FILTER_ID)
          args.add(excludeId!!.serialize())
        }

        return ContactSearchSelection(stringBuilder.toString(), args.map { obj: Any? -> obj.toString() }.toTypedArray())
      }
    }

    companion object {
      const val FILTER_GROUPS = " AND $GROUP_ID IS NULL"
      const val FILTER_ID = " AND $ID != ?"
      const val FILTER_BLOCKED = " AND $BLOCKED = ?"
      const val NON_SIGNAL_CONTACT = "$REGISTERED != ? AND $SYSTEM_CONTACT_URI NOT NULL AND ($PHONE NOT NULL OR $EMAIL NOT NULL)"
      const val QUERY_NON_SIGNAL_CONTACT = "$NON_SIGNAL_CONTACT AND ($PHONE GLOB ? OR $EMAIL GLOB ? OR $SYSTEM_JOINED_NAME GLOB ?)"
      const val SIGNAL_CONTACT = "$REGISTERED = ? AND ($SORT_NAME NOT NULL OR $UFSRVUID NOT NULL)"//AA+
//      const val SIGNAL_CONTACT = "$REGISTERED = ? AND (NULLIF($SYSTEM_JOINED_NAME, '') NOT NULL OR $PROFILE_SHARING = ?) AND ($SORT_NAME NOT NULL OR $USERNAME NOT NULL)"
      const val QUERY_SIGNAL_CONTACT = "$SIGNAL_CONTACT AND ($PHONE GLOB ? OR $SORT_NAME GLOB ? OR $NICKNAME GLOB ?)"//AA+ NICKNAME
    }
  }

  /**
   * Values that represent the index in the capabilities bitmask. Each index can store a 2-bit
   * value, which in this case is the value of [Recipient.Capability].
   */
  internal object Capabilities {
    const val BIT_LENGTH = 2
//    const val GROUPS_V2 = 0
    const val GROUPS_V1_MIGRATION = 1
    const val SENDER_KEY = 2
    const val ANNOUNCEMENT_GROUPS = 3
    const val CHANGE_NUMBER = 4
    const val STORIES = 5
  }

  enum class VibrateState(val id: Int) {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    companion object {
      fun fromId(id: Int): VibrateState {
        return values()[id]
      }

      fun fromBoolean(enabled: Boolean): VibrateState {
        return if (enabled) ENABLED else DISABLED
      }
    }
  }

  enum class RegisteredState(val id: Int) {
    UNKNOWN(0), REGISTERED(1), NOT_REGISTERED(2);

    companion object {
      fun fromId(id: Int): RegisteredState {
        return values()[id]
      }
    }
  }

  enum class UnidentifiedAccessMode(val mode: Int) {
    UNKNOWN(0), DISABLED(1), ENABLED(2), UNRESTRICTED(3);

    companion object {
      fun fromMode(mode: Int): UnidentifiedAccessMode {
        return values()[mode]
      }
    }
  }

  enum class InsightsBannerTier(val id: Int) {
    NO_TIER(0), TIER_ONE(1), TIER_TWO(2);

    fun seen(tier: InsightsBannerTier): Boolean {
      return tier.id <= id
    }

    companion object {
      fun fromId(id: Int): InsightsBannerTier {
        return values()[id]
      }
    }
  }

  enum class GroupType(val id: Int) {
    NONE(0), MMS(1), SIGNAL_V1(2), SIGNAL_V2(3), DISTRIBUTION_LIST(4);

    companion object {
      fun fromId(id: Int): GroupType {
        return values()[id]
      }
    }
  }

  enum class MentionSetting(val id: Int) {
    ALWAYS_NOTIFY(0), DO_NOT_NOTIFY(1);

    companion object {
      fun fromId(id: Int): MentionSetting {
        return values()[id]
      }
    }
  }

  private sealed class RecipientFetch(val logBundle: LogBundle?) {
    /**
     * We have a matching recipient, and no writes need to occur.
     */
    data class Match(val id: RecipientId, val bundle: LogBundle?) : RecipientFetch(bundle)

    /**
     * We found a matching recipient and can update them with a new E164.
     */
    data class MatchAndUpdateE164(val id: RecipientId, val e164: String, val changedNumber: RecipientId?, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * We found a matching recipient and can give them an E164 that used to belong to someone else.
     */
    data class MatchAndReassignE164(val id: RecipientId, val e164Id: RecipientId, val e164: String, val changedNumber: RecipientId?, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * We found a matching recipient and can update them with a new ACI.
     */
    data class MatchAndUpdateAci(val id: RecipientId, val serviceId: ServiceId, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * We found a matching recipient and can insert an ACI as a *new user*.
     */
    data class MatchAndInsertAci(val id: RecipientId, val serviceId: ServiceId, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * The ACI maps to ACI-only recipient, and the E164 maps to a different E164-only recipient. We need to merge the two together.
     */
    data class MatchAndMerge(val sidId: RecipientId, val e164Id: RecipientId, val changedNumber: RecipientId?, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * We don't have a matching recipient, so we need to insert one.
     */
    data class Insert(val serviceId: ServiceId?, val e164: String?, val bundle: LogBundle) : RecipientFetch(bundle)

    /**
     * We need to create a new recipient and give it the E164 of an existing recipient.
     */
    data class InsertAndReassignE164(val serviceId: ServiceId?, val e164: String?, val e164Id: RecipientId, val bundle: LogBundle) : RecipientFetch(bundle)
  }

  /**
   * Simple class for [fetchRecipient] to pass back info that can be logged.
   */
  private data class LogBundle(
    val label: String,
    val serviceId: ServiceId? = null,
    val e164: String? = null,
    val bySid: RecipientLogDetails? = null,
    val byE164: RecipientLogDetails? = null
  ) {
    fun label(label: String): LogBundle {
      return this.copy(label = label)
    }
  }

  /**
   * Minimal info about a recipient that we'd want to log. Used in [fetchRecipient].
   */
  private data class RecipientLogDetails(
    val id: RecipientId,
    val serviceId: ServiceId? = null,
    val e164: String? = null
  )

  enum class GuardianStatus(val value: Int) {
    NONE(0),
    LINKED(1),
    UNLINKED(2),
    REQUESTED(3),
    MUTED(4),
    GUARDIAN(5)

  }

  enum class PermissionListSemantics(//default: open permission. List is used as balacklist: users on it not permitted. except for those on blacklist
    val value: Int) {
    NONE(0),
    WHITELIST(1),

    //restricted permission: List is used as white list. only those on the list are permitted
    BLACKLIST(2)

  }

  //each permission can have a starting list
  enum class PermissionBaseList(val value: Int) {
    NONE(0),
    CONTACTS(1),
    BUDDIES(2),
    CONTACTS_BUDDIES(3),
    BLOCKED(4),
    CUSTOM(5)

  }

  enum class GeogroupStickyState(val id: Int) {
    DEFAULT(0),
    ENABLED(1),
    DISABLED(0);

    companion object {
      fun fromId(id: Int): GeogroupStickyState {
        return GeogroupStickyState.values()[id]
      }
    }
  }
}