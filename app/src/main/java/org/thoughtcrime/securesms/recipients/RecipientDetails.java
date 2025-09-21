package org.thoughtcrime.securesms.recipients;

import android.content.Context;
import android.net.Uri;

import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredential;
import org.thoughtcrime.securesms.badges.models.Badge;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.conversation.colors.ChatColors;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.InsightsBannerTier;
import org.thoughtcrime.securesms.database.RecipientDatabase.MentionSetting;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.database.model.DistributionListId;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.ServiceId;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class RecipientDetails {

  final Address                    address;
  final ServiceId                  serviceId;
  final PNI                        pni;
  final String                     username;
  final String                     e164;
  final String                     email;
  final GroupId                    groupId;
  final DistributionListId         distributionListId;
  final String                     groupName;
  final String                     systemContactName;
  final String                     customLabel;
  final Uri                        systemContactPhoto;
  final Uri                        contactUri;
  final Optional<Long>             groupAvatarId;
  final Uri                        messageRingtone;
  final Uri                        callRingtone;
  final long                       mutedUntil;
  final VibrateState               messageVibrateState;
  final VibrateState               callVibrateState;
  final boolean                    blocked;
  final int                        expireMessages;
  final List<Recipient>            participants;
  final ProfileName                profileName;
  final Optional<Integer>          defaultSubscriptionId;
  final RegisteredState            registered;
  final byte[]                     profileKey;
  final ProfileKeyCredential       profileKeyCredential;
  final String                     profileAvatar;
  final boolean                    hasProfileImage;
  final boolean                    profileSharing;
  final long                       lastProfileFetch;
  final boolean                    systemContact;
  final boolean                    isSelf;
  final String                     notificationChannel;
  final UnidentifiedAccessMode     unidentifiedAccessMode;
  final boolean                    forceSmsSelection;
  final Recipient.Capability       senderKeyCapability;
  final Recipient.Capability       announcementGroupCapability;
  final Recipient.Capability       changeNumberCapability;
  final Recipient.Capability       storiesCapability;
  final InsightsBannerTier         insightsBannerTier;
  final byte[]                     storageId;
  final MentionSetting             mentionSetting;
  final ChatWallpaper              wallpaper;
  final ChatColors                 chatColors;
  final AvatarColor                avatarColor;
  final String                     about;
  final String                     aboutEmoji;
  final ProfileName                systemProfileName;
  final Optional<Recipient.Extras> extras;
  final boolean                    hasGroupsInCommon;
  final List<Badge>                badges;
  final boolean                    isReleaseChannel;

  //AA+
  boolean                          isUfsrvResolvingFailed = false;
  @Nullable  String                avatarUfsrvId;
  @Nullable public String          nickname;
  public           String          ufsrvUidEncoded;
  long                             ufsrvId; //fence id or user sequence id
  @Nullable final String           e164Number;
  @Nullable final String           ufsrvUname; //rego name
  final long                       eid;
  final RecipientDatabase.GuardianStatus guardianStatus;
  final boolean                    presenceSharing;
  final boolean                    presenceShared;
  @Nullable final String           presenceInformation;
  final  boolean                   readReceiptSharing;
  final  boolean                   readReceiptShared;
  final  boolean                   typingIndicatorSharing;
  final  boolean                   typingIndicatorShared;
  final  boolean                   blockShared;
  final  boolean                   contactSharing;
  final  boolean                   contactShared;
  final boolean                    locationSharing;
  final boolean                    locationShared;
  @Nullable final String           locationInformation;
  final boolean                    profileShared;
  public Recipient.RecipientType   recipientType;
  //

  public RecipientDetails(@Nullable String groupName,
                          @Nullable String systemContactName,
                          @NonNull Optional<Long> groupAvatarId,
                          @NonNull Optional<RecipientUfsrvId> ufsrvId,//AA+
                          boolean systemContact,
                          boolean isSelf,
                          @NonNull RegisteredState registeredState,
                          @NonNull RecipientRecord record,
                          @Nullable List<Recipient> participants,
                          boolean isReleaseChannel)
  {
    this.groupAvatarId                   = groupAvatarId;
    this.systemContactPhoto              = record     != null ? Util.uri(record.getSystemContactPhotoUri()) : null;
    this.customLabel                     = record     != null ? record.getSystemPhoneLabel() : null;
    this.contactUri                      = record     != null ? Util.uri(record.getSystemContactUri()) : null;
    this.address                         = record     != null ? record.getAddress() : null;
    this.serviceId                       = record     != null ? record.getServiceId() : null;
    this.pni                             = record     != null ? record.getPni() : null;
    this.username                        = record     != null ? record.getUsername() : null;
    this.e164                            = record     != null ? record.getE164() : null;
    this.email                           = record     != null ? record.getEmail() : null;
    this.groupId                         = record     != null ? record.getGroupId() : null;
    this.distributionListId              = record     != null ? record.getDistributionListId() : null;
    this.messageRingtone                 = record     != null ? record.getMessageRingtone() : null;
    this.callRingtone                    = record     != null ? record.getCallRingtone() : null;
    this.mutedUntil                      = record     != null ? record.getMuteUntil() : 0;
    this.messageVibrateState             = record     != null ? record.getMessageVibrateState() : null;
    this.callVibrateState                = record     != null ? record.getCallVibrateState() : null;
    this.blocked                         = record     != null ? record.isBlocked() : false;
    this.expireMessages                  = record     != null ? record.getExpireMessages() : 0;
    this.participants                    = participants != null ? participants : new LinkedList<>();
    this.profileName                     = record     != null ? record.getProfileName() : ProfileName.EMPTY; //AA++
    this.defaultSubscriptionId           = record     != null ? record.getDefaultSubscriptionId() : null;
    this.registered                      = registeredState;
    this.profileKey                      = record     != null ? record.getProfileKey()
                                                                : null;
    this.profileKeyCredential            = record     != null ? record.getProfileKeyCredential()
                                                                : null;
    this.profileAvatar                   = record     != null ? record.getProfileAvatar() : null;
    this.hasProfileImage                 = record     != null ? record.hasProfileImage() : false;
    this.profileSharing                  = record     != null ? record.isProfileSharing() : false;
    this.lastProfileFetch                = record     != null ? record.getLastProfileFetch() : 0;
    this.systemContact                   = systemContact;
    this.isSelf                          = isSelf;
    this.notificationChannel             = record     != null ? record.getNotificationChannel() : null;
    this.unidentifiedAccessMode          = record     != null ? record.getUnidentifiedAccessMode() : null;
    this.forceSmsSelection               = record     != null ? record.isForceSmsSelection() : false;
    this.senderKeyCapability             = record     != null ? record.getSenderKeyCapability() : Recipient.Capability.UNKNOWN;
    this.announcementGroupCapability     = record     != null ? record.getAnnouncementGroupCapability() : Recipient.Capability.UNKNOWN;
    this.changeNumberCapability          = record     != null ? record.getChangeNumberCapability() : Recipient.Capability.UNKNOWN;
    this.storiesCapability               = record     != null ? record.getStoriesCapability() : Recipient.Capability.UNKNOWN;
    this.insightsBannerTier              = record     != null ? record.getInsightsBannerTier(): InsightsBannerTier.TIER_TWO;
    this.storageId                       = record     != null ? record.getStorageId() : null;
    this.mentionSetting                  = record     != null ? record.getMentionSetting() : MentionSetting.ALWAYS_NOTIFY;
    this.wallpaper                       = record     != null ? record.getWallpaper() : null;
    this.chatColors                      = record     != null ? record.getChatColors() : null;
    this.avatarColor                     = record     != null ? record.getAvatarColor(): AvatarColor.UNKNOWN;
    this.about                           = record     != null ? record.getAbout() : null;
    this.aboutEmoji                      = record     != null ? record.getAboutEmoji() : null;
    this.systemProfileName               = record     != null ? record.getSystemProfileName() : ProfileName.EMPTY;
    this.systemContactName               = systemContactName;
    this.extras                          = record     != null ? Optional.ofNullable(record.getExtras()) : Optional.empty();
    this.hasGroupsInCommon               = record     != null ? record.hasGroupsInCommon() : false;
    this.badges                          = record     != null ? record.getBadges() : Collections.emptyList();
    this.isReleaseChannel                = isReleaseChannel;

    if (groupName == null && record != null) {
      if (record.getNickname() != null) this.groupName = record.getNickname();
      else this.groupName = record.getSystemProfileName().toString();
    } else if (groupName == null) { //AA+
      this.groupName = this.nickname;
    } else this.groupName = groupName;

    if (systemContact) {
      this.nickname = groupName;//AA+ special condition to enable ufsrv system recipient
    }

    this.profileShared          = record      != null && record.getProfileShared();
    this.avatarUfsrvId          = record      != null ? record.getAvatarUfsrvId():"0";
    this.nickname               = record      != null ? record.getNickname() : null;
    this.ufsrvUidEncoded        = record      != null ? record.getUfsrvUidEncoded() : "";
    if (ufsrvId.isPresent()) this.ufsrvId = ufsrvId.get().toId();
    else this.ufsrvId           = record      != null ? record.getUfsrvId() : 0;
    this.e164Number             = record      != null ? record.getE164() : null;
    this.ufsrvUname             = record      != null ? record.getUfsrvUname() : null;
    this.eid                    = record      != null ? record.getEId(): 0;
    this.guardianStatus         = record      != null ? record.getGuardianStatus(): RecipientDatabase.GuardianStatus.NONE;
    this.recipientType          = record      != null ? record.getRecipientType(): Recipient.RecipientType.UKNOWN;
    this.presenceSharing        = record      != null && record.getPresenceSharing();
    this.presenceShared         = record      != null && record.getPresenceShared();
    this.presenceInformation    = record      != null ? record.getPresenceInformation() : null;
    this.readReceiptSharing     = record      != null && record.getReadReceiptSharing();
    this.readReceiptShared      = record      != null && record.getReadReceiptShared();
    this.typingIndicatorSharing = record      != null && record.getTypingIndicatorSharing();
    this.typingIndicatorShared  = record      != null && record.getTypingIndicatorShared();
    this.blockShared            = record      != null && record.getBlockShared();
    this.contactSharing         = record      != null && record.getContactSharing();
    this.contactShared          = record      != null && record.getContactShared();
    this.locationSharing        = record      != null && record.getLocationSharing();
    this.locationShared         = record      != null && record.getLocationShared();
    this.locationInformation    = record      != null ? record.getLocationInformation() : null;
  }

  public RecipientDetails() {
    this.groupAvatarId               = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.address                     = Address.UNKNOWN;
    this.serviceId                   = null;
    this.pni                         = null;
    this.username                    = null;
    this.e164                        = null;
    this.email                       = null;
    this.groupId                     = null;
    this.distributionListId          = null;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.mutedUntil                  = 0;
    this.messageVibrateState         = VibrateState.DEFAULT;
    this.callVibrateState            = VibrateState.DEFAULT;
    this.blocked                     = false;
    this.expireMessages              = 0;
    this.participants                = new LinkedList<>();
    this.profileName                 = ProfileName.EMPTY;
    this.insightsBannerTier          = InsightsBannerTier.TIER_TWO;
    this.defaultSubscriptionId       = Optional.empty();
    this.registered                  = RegisteredState.UNKNOWN;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.profileAvatar               = null;
    this.hasProfileImage             = false;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.systemContact               = true;
    this.isSelf                      = false;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.UNKNOWN;
    this.forceSmsSelection           = false;
    this.groupName                   = null;
    this.senderKeyCapability         = Recipient.Capability.UNKNOWN;
    this.announcementGroupCapability = Recipient.Capability.UNKNOWN;
    this.changeNumberCapability      = Recipient.Capability.UNKNOWN;
    this.storiesCapability           = Recipient.Capability.UNKNOWN;
    this.storageId                   = null;
    this.mentionSetting              = MentionSetting.ALWAYS_NOTIFY;
    this.wallpaper                   = null;
    this.chatColors                  = null;
    this.avatarColor                 = AvatarColor.UNKNOWN;
    this.about                       = null;
    this.aboutEmoji                  = null;
    this.systemProfileName           = ProfileName.EMPTY;
    this.systemContactName           = null;
    this.extras                      = Optional.empty();
    this.hasGroupsInCommon           = false;
    this.badges                      = Collections.emptyList();
    this.isReleaseChannel            = false;

    this.profileShared               = false;
    this.avatarUfsrvId               = "0";
    this.nickname                    =  null;
    this.ufsrvUidEncoded             = UfsrvUid.UndefinedUfsrvUid;
    this.ufsrvId                     = 0;
    this.e164Number                  =  null;
    this.ufsrvUname                  =  null;
    this.eid                         = 0;
    this.guardianStatus              = RecipientDatabase.GuardianStatus.NONE;
    this.recipientType               = Recipient.RecipientType.UKNOWN;
    this.presenceSharing             = false;
    this.presenceShared              = false;
    this.presenceInformation         = null;
    this.readReceiptSharing          = false;
    this.readReceiptShared           = false;
    this.typingIndicatorSharing      = false;
    this.typingIndicatorShared       = false;
    this.blockShared                 = false;
    this.contactSharing              = false;
    this.contactShared               = false;
    this.locationSharing             = false;
    this.locationShared              = false;
    this.locationInformation         = null;
  }

  public static @NonNull RecipientDetails forIndividual(@NonNull Context context, RecipientRecord settings) {
    boolean systemContact = !settings.getSystemProfileName().isEmpty();
    boolean isSelf = settings.getUfsrvUidEncoded() != null && settings.getUfsrvUidEncoded().equals(TextSecurePreferences.getUfsrvUserId(context));
    boolean isReleaseChannel = settings.getId().equals(SignalStore.releaseChannelValues().getReleaseChannelRecipientId());
//    boolean isSelf        = (settings.getE164() != null && settings.getE164().equals(SignalStore.account().getE164())) ||
//            (settings.getAci() != null && settings.getAci().equals(SignalStore.account().getAci()));

    RegisteredState registeredState = settings.getRegistered();

    if (isSelf) {
      if (SignalStore.account().isRegistered() && !TextSecurePreferences.isUnauthorizedRecieved(context)) {
        registeredState = RegisteredState.REGISTERED;
      } else {
        registeredState = RegisteredState.NOT_REGISTERED;
      }
    }

    return new RecipientDetails(null, settings.getSystemDisplayName(), Optional.empty(), Optional.empty(), systemContact, isSelf, registeredState, settings, null, isReleaseChannel);
  }

  public static @NonNull RecipientDetails forDistributionList(String title, @Nullable List<Recipient> members, @NonNull RecipientRecord record) {
    return new RecipientDetails(title, null, Optional.empty(), Optional.empty(),  false, false, record.getRegistered(), record, members, false);
  }

  //AA+
  public static @NonNull RecipientDetails forUfsrvSystemUser(String nickname) {
    return new RecipientDetails(nickname, null, Optional.empty(), Optional.empty(),  true, false, RegisteredState.NOT_REGISTERED, null, Collections.emptyList(), false);
  }

  public static @NonNull RecipientDetails forUnknown() {
    return new RecipientDetails();
  }
}