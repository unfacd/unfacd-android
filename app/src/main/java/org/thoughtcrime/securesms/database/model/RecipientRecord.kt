package org.thoughtcrime.securesms.database.model

import android.net.Uri
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredential
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.IdentityDatabase.VerifiedStatus
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase.InsightsBannerTier
import org.thoughtcrime.securesms.database.RecipientDatabase.MentionSetting
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.Recipient.RecipientType
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import java.util.Optional
import org.whispersystems.signalservice.api.push.PNI
import org.whispersystems.signalservice.api.push.ServiceId

/**
 * Database model for [RecipientDatabase].
 */
data class RecipientRecord(
  val id: RecipientId,
  val serviceId: ServiceId?,
  val pni: PNI?,
  val username: String?,
  val e164: String?,
  val email: String?,
  val groupId: GroupId?,
  val distributionListId: DistributionListId?,
  val groupType: RecipientDatabase.GroupType,
  val isBlocked: Boolean,
  val muteUntil: Long,
  val messageVibrateState: VibrateState,
  val callVibrateState: VibrateState,
  val messageRingtone: Uri?,
  val callRingtone: Uri?,
  private val defaultSubscriptionId: Int,
  val expireMessages: Int,
  val registered: RegisteredState,
  val profileKey: ByteArray?,
  val profileKeyCredential: ProfileKeyCredential?,
  val systemProfileName: ProfileName,
  val systemDisplayName: String?,
  val systemContactPhotoUri: String?,
  val systemPhoneLabel: String?,
  val systemContactUri: String?,
  @get:JvmName("getProfileName")
  val signalProfileName: ProfileName,
  @get:JvmName("getProfileAvatar")
  val signalProfileAvatar: String?,
  @get:JvmName("hasProfileImage")
  val hasProfileImage: Boolean,
  @get:JvmName("isProfileSharing")
  val profileSharing: Boolean,
  val lastProfileFetch: Long,
  val notificationChannel: String?,
  val unidentifiedAccessMode: UnidentifiedAccessMode,
  @get:JvmName("isForceSmsSelection")
  val forceSmsSelection: Boolean,
  val rawCapabilities: Long,
  val groupsV1MigrationCapability: Recipient.Capability,
  val senderKeyCapability: Recipient.Capability,
  val announcementGroupCapability: Recipient.Capability,
  val changeNumberCapability: Recipient.Capability,
  val storiesCapability: Recipient.Capability,
  val insightsBannerTier: InsightsBannerTier,
  val storageId: ByteArray?,
  val mentionSetting: MentionSetting,
  val wallpaper: ChatWallpaper?,
  val chatColors: ChatColors?,
  val avatarColor: AvatarColor,
  val about: String?,
  val aboutEmoji: String?,
  val syncExtras: SyncExtras?,
  val extras: Recipient.Extras?,
  @get:JvmName("hasGroupsInCommon")
  val hasGroupsInCommon: Boolean,
  val badges: List<Badge>,

  //AA+
  val address: Address?,
  val ufsrvUidEncoded: String?,
  val ufsrvId: Long = 0,
  val ufsrvUname: String?,
  val eId: Long = 0,
  val recipientType: RecipientType?,
  val nickname: String?,
  val avatarUfsrvId: String?,
  val sticky: RecipientDatabase.GeogroupStickyState?,
  val guardianStatus: RecipientDatabase.GuardianStatus?,
  val profileShared: Boolean = false, //AA+
  val presenceSharing: Boolean = false,
  val presenceShared: Boolean = false,
  val presenceInformation: String?,
  val readReceiptSharing: Boolean = false,
  val readReceiptShared: Boolean = false,
  val typingIndicatorSharing: Boolean = false,
  val typingIndicatorShared: Boolean = false,
  val locationSharing: Boolean = false,
  val locationShared: Boolean = false,
  val blockShared: Boolean = false,
  val contactSharing: Boolean = false,
  val contactShared: Boolean = false,
  val locationInformation: String?,
  val permissionPresentation: List<String?>?,
  val permissionMembership: List<String?>?,
  val permissionMessaging: List<String?>?,
  val permissionAttaching: List<String?>?,
  val permissionCalling: List<String?>?,
  val permSemanticsPresentation: RecipientDatabase.PermissionListSemantics?,
  val permSemanticsMembership: RecipientDatabase.PermissionListSemantics?,
  val permSemanticsMessaging: RecipientDatabase.PermissionListSemantics?,
  val permSemanticsAttaching: RecipientDatabase.PermissionListSemantics?,
  val permSemanticsCalling: RecipientDatabase.PermissionListSemantics? = null
) {

  fun getDefaultSubscriptionId(): Optional<Int> {
    return if (defaultSubscriptionId != -1) Optional.of(defaultSubscriptionId) else Optional.empty()
  }

  /**
   * A bundle of data that's only necessary when syncing to storage service, not for a
   * [Recipient].
   */
  data class SyncExtras(
    val storageProto: ByteArray?,
    val groupMasterKey: GroupMasterKey?,
    val identityKey: ByteArray?,
    val identityStatus: VerifiedStatus,
    val isArchived: Boolean,
    val isForcedUnread: Boolean
  )


}