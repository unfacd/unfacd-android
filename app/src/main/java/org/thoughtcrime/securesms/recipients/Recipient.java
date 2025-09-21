/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.recipients;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.unfacd.android.R;
import com.unfacd.android.fence.FenceDescriptor;
import com.unfacd.android.locallyaddressable.LocallyAddressable;
import com.unfacd.android.locallyaddressable.LocallyAddressableGroup;
import com.unfacd.android.locallyaddressable.LocallyAddressablePhoneNumber;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.PairedGroupName;
import com.unfacd.android.utils.UfsrvFenceUtils;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredential;
import org.thoughtcrime.securesms.badges.models.Badge;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GroupRecordContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.SystemContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.TransparentContactPhoto;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.conversation.colors.ChatColors;
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.InsightsBannerTier;
import org.thoughtcrime.securesms.database.RecipientDatabase.MentionSetting;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.DistributionListId;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.database.model.databaseprotos.RecipientExtras;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.phonenumbers.NumberUtil;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.util.AvatarUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.StringUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.PNI;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.util.OptionalUtil;
import org.whispersystems.signalservice.api.util.Preconditions;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import static org.thoughtcrime.securesms.database.GroupDatabase.DELIMITER;

public class Recipient {
  private static final String TAG = Log.tag(Recipient.class);

  public static final FallbackPhotoProvider DEFAULT_FALLBACK_PHOTO_PROVIDER = new FallbackPhotoProvider();

  private static final int MAX_MEMBER_NAMES = 10;

  public static final Recipient UNKNOWN = new Recipient(RecipientId.UNKNOWN, RecipientDetails.forUnknown(), true);

  public static final Recipient UFSRV = new Recipient(RecipientId.UFSRV, new RecipientDetails(), true);
  public static final Recipient UFSRV_SYSTEM_USER = new Recipient(RecipientId.UFSRV,  RecipientDetails.forUfsrvSystemUser("unfacd"), true);

  private final RecipientId            id;
  private final boolean                resolving;
  private final ServiceId              serviceId;
  private final PNI                    pni;
  private final String                 username;
  private final String                 e164;
  private final String                 email;
  private final GroupId                groupId;
  private final DistributionListId     distributionListId;
  private final Address                address;
  private final List<Recipient>        participants;
  private final Optional<Long>         groupAvatarId;
  private final boolean                isSelf;
  private final boolean                blocked;
  private final long                   muteUntil;
  private final VibrateState           messageVibrate;
  private final VibrateState           callVibrate;
  private final Uri                    messageRingtone;
  private final Uri                    callRingtone;
  private final Optional<Integer>      defaultSubscriptionId;
  private final int                    expireMessages;
  private final RegisteredState        registered;
  private final byte[]                 profileKey;
  private final ProfileKeyCredential   profileKeyCredential;
  private final String                 groupName;
  private final Uri                    systemContactPhoto;
  private final String                 customLabel;
  private final Uri                    contactUri;
  private final ProfileName            signalProfileName;
  private final String                 profileAvatar;
  private final boolean                profileSharing;
  private final long                   lastProfileFetch;
  private final String                 notificationChannel;
  private final UnidentifiedAccessMode unidentifiedAccessMode;
  private final boolean                forceSmsSelection;
  private final Capability             senderKeyCapability;
  private final Capability             announcementGroupCapability;
  private final Capability             changeNumberCapability;
  private final Capability             storiesCapability;
  private final InsightsBannerTier     insightsBannerTier;
  private final byte[]                 storageId;
  private final MentionSetting         mentionSetting;
  private final ChatWallpaper          wallpaper;
  private final ChatColors             chatColors;
  private final AvatarColor            avatarColor;
  private final String                 about;
  private final String                 aboutEmoji;
  private final ProfileName            systemProfileName;
  private final String                 systemContactName;
  private final Optional<Extras>       extras;
  private final boolean                hasGroupsInCommon;
  private final List<Badge>            badges;
  private final boolean                isReleaseNotesRecipient;

  //AA+
  private           boolean               isUfsrvResolving = false;
  private @Nullable String                avatarUfsrvId;
  private @Nullable @JsonProperty
  String  nickname;
  private @JsonProperty           long    ufsrvId; //fid or sequence uid
  private @JsonProperty           String  ufsrvUid; //encoded
  private @JsonProperty           long    eid;
  private @JsonProperty           String  e164number;
  private @JsonProperty           String  ufsrvuname; //rego name
  private RecipientType                   recipientType   = RecipientType.UKNOWN;
  private RecipientDatabase.GeogroupStickyState geogroupSticky  = RecipientDatabase.GeogroupStickyState.DEFAULT;
  private RecipientDatabase.GuardianStatus guardianStatus = RecipientDatabase.GuardianStatus.NONE;

  private           boolean               presenceSharing;
  private           boolean               presenceShared;
  private           boolean               readReceiptSharing;
  private           boolean               readReceiptShared;
  private           boolean               typingIndicatorSharing;
  private           boolean               typingIndicatorShared;
  private           boolean               blockShared;
  private           boolean               contactSharing;
  private           boolean               contactShared;
  private           String                presenceInformation; //"status, timestamp"
  private           boolean               locationSharing;
  private           boolean               locationShared;
  private           String                locationInformation; //"long, lat"
  private           boolean               profileShared;

  public enum RecipientType {
    UKNOWN(0),
    USER(1),
    GROUP (2);

    private int value;

    RecipientType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public static RecipientType fromId(int id) {
      return values()[id];
    }
  }

  //align with enum NetState in protobuf
  public enum PresenceType {
    DEREGISTERED(0),
    ONLINE(1),
    OFFLINE(2);

    private int value;

    PresenceType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public static PresenceType fromId (int id) {
      return values()[id];
    }
  }

  /**
   * Returns a {@link LiveRecipient}, which contains a {@link Recipient} that may or may not be
   * populated with data. However, you can observe the value that's returned to be notified when the
   * {@link Recipient} changes.
   */
  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull RecipientId id) {
    Preconditions.checkNotNull(id, "ID cannot be null.");
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  //AA+
  /**
   * Returns a {@link LiveRecipient}, which contains a {@link Recipient} that may or may not be
   * populated with data. However, you can observe the value that's returned to be notified when the
   * {@link Recipient} changes.
   */
  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull String ufsrvUid) {
    Preconditions.checkNotNull(ufsrvUid, "ufsrvUid cannot be null.");
    return ApplicationDependencies.getRecipientCache().getLiveUfsrvUid(ufsrvUid);
  }

  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull RecipientUfsrvId recipientUfsrvId) {
    Preconditions.checkNotNull(recipientUfsrvId, "recipientUfsrvId cannot be null.");
    return live(recipientUfsrvId, false);
  }

  @AnyThread
  public static @NonNull LiveRecipient live(@NonNull RecipientUfsrvId recipientUfsrvId, boolean isSynchronous) {
    Preconditions.checkNotNull(recipientUfsrvId, "recipientUfsrvId cannot be null.");
    return ApplicationDependencies.getRecipientCache().getLiveUfsrvId(recipientUfsrvId, isSynchronous);
  }

  @AnyThread
  public static @NonNull LiveRecipient live(long fid,  boolean isSynchronous) {
    return ApplicationDependencies.getRecipientCache().getLiveUfsrvId(RecipientUfsrvId.from(fid), isSynchronous);
  }

  @AnyThread
  public static @NonNull LiveRecipient live(long fid) {
    return ApplicationDependencies.getRecipientCache().getLiveUfsrvId(RecipientUfsrvId.from(fid), true);
  }

  /**
   * Consolidate addressableUfsrvUid into recipientIdTarget. Note how db update is done while lock is held on LiveRecipientCache in killLive()
   * @param addressablePhoneNumber
   * @param addressableUfsrvUid
   */
  public static void consolidateUfsrvUidAndKill (LocallyAddressablePhoneNumber addressablePhoneNumber, LocallyAddressableUfsrvUid addressableUfsrvUid)
  {
    ApplicationDependencies.getRecipientCache().killLive(addressablePhoneNumber.getRecipientId(), (pair) -> {
     SignalDatabase.recipients().copyUfsrvUidAndKill(addressableUfsrvUid.getRecipientId(), pair.second());
    });

  }
  //

  /**
   /**
   * A safety wrapper around {@link #external(Context, String)} for when you know you're using an
   * identifier for a system contact, and therefore always want to prevent interpreting it as a
   * UUID. This will crash if given a UUID.
   *
   * (This may seem strange, but apparently some devices are returning valid UUIDs for contacts)
   */
  @WorkerThread
  public static @NonNull Recipient externalContact(@NonNull Context context, @NonNull String identifier) {
    RecipientDatabase db = SignalDatabase.recipients();
    RecipientId       id = null;

    if (UuidUtil.isUuid(identifier)) {
      throw new AssertionError("UUIDs are not valid system contact identifiers!");
    } else if (NumberUtil.isValidEmail(identifier)) {
      id = db.getOrInsertFromEmail(identifier);
    } else {
      id = db.getOrInsertFromE164(identifier);
    }

    return Recipient.resolved(id);
  }

  /**
   * A version of {@link #external(Context, String)} that should be used when you know the
   * identifier is a groupId.
   */
  @WorkerThread
  public static @NonNull Recipient externalGroupExact (@NonNull Context context, @NonNull GroupId groupId) {
    return Recipient.resolved(SignalDatabase.recipients().getOrInsertFromGroupId(groupId));
  }

  @WorkerThread
  public static @NonNull List<Recipient> resolvedList(@NonNull Collection<RecipientId> ids) {
    List<Recipient> recipients = new ArrayList<>(ids.size());

    for (RecipientId recipientId : ids) {
      recipients.add(resolved(recipientId));
    }

    return recipients;
  }

  @WorkerThread
  public static @NonNull Recipient distributionList(@NonNull DistributionListId distributionListId) {
    RecipientId id = SignalDatabase.recipients().getOrInsertFromDistributionListId(distributionListId);
    return resolved(id);
  }

  /**
   * Returns a fully-populated {@link Recipient} and associates it with the provided username.
   */
  @WorkerThread
  public static @NonNull Recipient externalUsername( @NonNull ServiceId serviceId, @NonNull String username) {
    Recipient recipient = externalPush(serviceId, null, false);
    SignalDatabase.recipients().setUsername(recipient.getId(), username);
    return recipient;
  }

  /**
   * Returns a fully-populated {@link Recipient}. May hit the disk, and therefore should be
   * called on a background thread.
   */
  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull RecipientId id) {
    Preconditions.checkNotNull(id, "ID cannot be null.");

    return live(id).resolve();
  }

  //AA+
  @WorkerThread
  public static @NonNull Recipient resolvedFromUfsrvUid(@NonNull String ufsrvUid) {
    Preconditions.checkNotNull(ufsrvUid, "ufsrvUid cannot be null.");

    return live(ufsrvUid).resolveUfsrvUid();
  }

  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull LocallyAddressable locallyAddressable) {
    Preconditions.checkNotNull(locallyAddressable, "LocallyAddressable cannot be null.");

    return live(locallyAddressable.toString()).resolveUfsrvUid();
  }

  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull LocallyAddressableGroup locallyAddressable) {
    Preconditions.checkNotNull(locallyAddressable, "LocallyAddressableGroup cannot be null.");

    return live(locallyAddressable.getRecipientId()).resolveUfsrvUid();
  }

  @WorkerThread
  public static @NonNull Recipient resolved(@NonNull RecipientUfsrvId ufsrvId) {
    Preconditions.checkNotNull(ufsrvId, "ufsrvId cannot be null.");

    return live(ufsrvId).resolveUfsrvId();
  }
  //

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. Convenience overload of
   * {@link #externalPush(ServiceId, String, boolean)}
   */
  @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull SignalServiceAddress signalServiceAddress) {
    return external(ApplicationDependencies.getApplication(), signalServiceAddress.getNumber().orElse(null));//AA+
//    return externalPush(signalServiceAddress.getServiceId(), signalServiceAddress.getNumber().orElse(null), false);
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a {@link SignalServiceAddress},
   * creating one in the database if necessary. This should only used for high-trust sources,
   * which are limited to:
   * - Envelopes
   * - UD Certs
   * - CDS
   * - Storage Service
   */
  @WorkerThread
  public static @NonNull Recipient externalHighTrustPush(@NonNull Context context, @NonNull SignalServiceAddress signalServiceAddress) {
//    return externalPush(signalServiceAddress.getServiceId(), signalServiceAddress.getNumber().orElse(null), true);
    return external(ApplicationDependencies.getApplication(), signalServiceAddress.getNumber().orElse(null));//AA+
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of an ACI and phone number, creating one
   * in the database if necessary. We want both piece of information so we're able to associate them
   * both together, depending on which are available.
   * <p>
   * In particular, while we'll eventually get the ACI of a user created via a phone number
   * (through a directory sync), the only way we can store the phone number is by retrieving it from
   * sent messages and whatnot. So we should store it when available.
   *
   * @param serviceId
   * @param highTrust This should only be set to true if the source of the E164-ACI pairing is one
   *                  that can be trusted as accurate (like an envelope).
   */
  /* @WorkerThread
  public static @NonNull Recipient externalPush(@NonNull Context context, @Nullable ACI aci, @Nullable String e164, boolean highTrust) {
    if (ACI.UNKNOWN.equals(aci)) {
      throw new AssertionError();
    }

    RecipientDatabase db          = SignalDatabase.recipients();
    RecipientId       recipientId = db.getAndPossiblyMerge(aci, e164, highTrust);

    Recipient resolved = resolved(recipientId);

    if (!resolved.getId().equals(recipientId)) {
      Log.w(TAG, "Resolved " + recipientId + ", but got back a recipient with " + resolved.getId());
    }

    if (highTrust && !resolved.isRegistered() && aci != null) {
      Log.w(TAG, "External high-trust push was locally marked unregistered. Marking as registered.");
      db.markRegistered(recipientId, aci);
    } else if (highTrust && !resolved.isRegistered()) {
      Log.w(TAG, "External high-trust push was locally marked unregistered, but we don't have an ACI, so we can't do anything.", new Throwable());
    }

    return resolved;
  }*/

  @WorkerThread //AA e164 must always refer to serialised ufsrvuid. NOTE: this orig implementation prior to https://github.com/signalapp/Signal-Android/commit/bd078fc88330f24182fd353592bf68e9f6e6c5f5
  public static @NonNull Recipient externalPush(@Nullable ServiceId serviceId, @Nullable String e164, boolean highTrust) {
    RecipientDatabase     db       = SignalDatabase.recipients();
    Optional<RecipientId> uuidUser = serviceId != null ? db.getByServiceId(serviceId) : Optional.empty();
    Optional<RecipientId> e164User = e164 != null ? db.getByUfsrvUid(e164) : Optional.empty();//AA+

    if (uuidUser.isPresent()) {
      Recipient recipient = resolved(uuidUser.get());

      if (e164 != null /*&& !recipient.getE164().isPresent()*/ && !e164User.isPresent()) {//AA+ commented out
        db.setUfsrvUid(recipient.getId(), e164);//AA+ ufsrvUid
      }

      return resolved(recipient.getId());
    } else if (e164User.isPresent()) {
      Recipient recipient = resolved(e164User.get());

      if (serviceId != null && !recipient.getServiceId().isPresent()) {
        db.markRegistered(recipient.getId());//, uuid);//AA-
      } else if (!recipient.isRegistered()) {
        db.markRegistered(recipient.getId());

        Log.i(TAG, "No UUID! Scheduling a fetch.");
        ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(recipient, false, false));//AA+ isE164 false
      }

      return resolved(recipient.getId());
    } else if (serviceId != null) {
      RecipientId id = db.getOrInsertFromServiceId(serviceId);
      return Recipient.resolved(id);
    } else if (e164 != null) {
      Recipient recipient = resolved(db.getOrInsertFromUfsrvUid(UfsrvUid.fromEncoded(e164)));//AA+ ufsrvuid

      if (!recipient.isRegistered()) {
        db.markRegistered(recipient.getId());

        Log.i(TAG, "No UUID! Scheduling a fetch.");
        ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(recipient, false, false));//AA+ isE164 false
      }

      return resolved(recipient.getId());
    } else {
      throw new AssertionError("You must provide either a UUID or phone number!");
    }
  }

  /**
   * Returns a fully-populated {@link Recipient} based off of a string identifier, creating one in
   * the database if necessary. The identifier may be a uuid, phone number, email,
   * or serialized groupId.
   *
   * If the identifier is a UUID of a Signal user, prefer using
   * {@link #externalPush(Context, ACI, String, boolean)} or its overload, as this will let us associate
   * the phone number with the recipient.
   */
  @WorkerThread
  public static @NonNull Recipient external(@NonNull Context context, @NonNull String identifier) {
    Preconditions.checkNotNull(identifier, "Identifier cannot be null!");

    RecipientDatabase db = SignalDatabase.recipients();
    RecipientId       id = null;

    if (UuidUtil.isUuid(identifier)) {
      ServiceId serviceId = ServiceId.parseOrThrow(identifier);
      id = db.getOrInsertFromServiceId(serviceId);
    } else if (GroupId.isEncodedGroup(identifier)) {
      id = db.getOrInsertFromGroupId(GroupId.parseOrThrow(identifier));
    } else if (NumberUtil.isValidEmail(identifier)) {
      id = db.getOrInsertFromEmail(identifier);
    } else if (PhoneNumberFormatter.isLikelyE164Number(identifier)) { //AA+ likely
      String e164 = PhoneNumberFormatter.get(context).format(identifier);
      id = db.getOrInsertFromE164(e164);
    } else { //AA+
      RecipientDatabase.InsertResult insertResult  = db.getOrInsertFromUfsrvUid(identifier);
      id = insertResult.getRecipientId();
      if (!insertResult.isResolved()) {
        ufsrvUidResolve(context, insertResult.getRecipientId(), identifier); //synchronous network fetch + Recipient DB insertion
      }
    }

    return Recipient.resolved(id); //AA LiveCache insertion + reload Recipient DB
  }

  //AA+
  /**
   * Returns a fully-populated {@link Recipient} based off of fid identifier, creating one in
   * the database if necessary.
   */
  @WorkerThread
  public static @NonNull Recipient external(@NonNull Context context, long fid) {
    RecipientDatabase db = SignalDatabase.recipients();
    RecipientId       id = null;

    RecipientDatabase.InsertResult insertResult = db.getOrInsertFromFid(fid);

    return Recipient.resolved(id);
  }

  public static @NonNull Recipient self() {
    return ApplicationDependencies.getRecipientCache().getSelf();
  }

  //AA+

  public static String toSerializedUfsrvUidList(@NonNull List<Recipient> ids)
  {
    return Util.join(Stream.of(ids).map(Recipient::requireUfsrvUid).toList(), String.valueOf(DELIMITER));
  }

  //AA+ doesn't update LiveRecipient
  public static Optional<RecipientRecord> ufsrvUidResolve(@NonNull Context context, RecipientId recipientId, @NonNull String ufsrvUid)
  {
    RecipientDatabase recipientDatabase = SignalDatabase.recipients();

    try {
      Optional<ContactTokenDetails> tokenDetails = ContactDiscovery.refreshWithContactDetails(LocallyAddressableUfsrvUid.from(recipientId, UfsrvUid.fromEncoded(ufsrvUid)));
      if (tokenDetails.isPresent()) {
        Optional<RecipientRecord> settings = recipientDatabase.generateNonDBRecipientRecord(tokenDetails.get());
        if (settings.isPresent()) {
          recipientDatabase.setRegistered(recipientId, RegisteredState.REGISTERED, tokenDetails.get());
//          IdentityUtil.saveIdentity(tokenDetails.get()); //AA left it for future revision. Reverted to original behaviour: retrieve and save identity on first message contact
          return settings;
        } else  {
          Log.d(TAG, String.format("ufsrvResolve: RecipientSettings Reload failed for '%s': using defaults", ufsrvUid));
        }
      }
    } catch (IOException | UfsrvUid.UfsrvUidEncodingError ex) {
      Log.d(TAG, ex.getMessage());
    }

    return Optional.empty();
  }

  /**
   * Resolves fence ids only.  Doesn't update LiveRecipient
   * @param recipientId
   * @param recipientUfsrvId
   * @return
   */
  public static Optional<RecipientRecord> ufsrvIdNetworkResolve(@NonNull Context context, RecipientId recipientId, @NonNull RecipientUfsrvId recipientUfsrvId)
  {
    FenceDescriptor fenceDescriptor = ApplicationDependencies.getSignalServiceAccountManager().getFenceDescriptor(recipientUfsrvId.toId());
    if (fenceDescriptor != null) {
      RecipientRecord settingsFid = SignalDatabase.recipients().getRecipientSettingsFromFenceDescriptor(fenceDescriptor);
      return Optional.of(settingsFid);
    } else {
      return Optional.empty();
    }

  }
//

  Recipient(@NonNull RecipientId id) {
    this.id                          = id;
    this.resolving                   = true;
    this.serviceId                   = null;
    this.pni                         = null;
    this.username                    = null;
    this.e164                        = null;
    this.distributionListId          = null;
    this.email                       = null;
    this.groupId                     = null;
    this.address                     = null;
    this.participants                = Collections.emptyList();
    this.groupAvatarId               = Optional.empty();
    this.isSelf                      = false;
    this.blocked                     = false;
    this.muteUntil                   = 0;
    this.messageVibrate              = VibrateState.DEFAULT;
    this.callVibrate                 = VibrateState.DEFAULT;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.insightsBannerTier          = RecipientDatabase.InsightsBannerTier.TIER_TWO;
    this.defaultSubscriptionId       = Optional.empty();
    this.expireMessages              = 0;
    this.registered                  = RegisteredState.UNKNOWN;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.groupName                   = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.signalProfileName           = ProfileName.EMPTY;
    this.profileAvatar               = null;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.DISABLED;
    this.forceSmsSelection           = false;
    this.senderKeyCapability         = Capability.UNKNOWN;
    this.announcementGroupCapability = Capability.UNKNOWN;
    this.changeNumberCapability      = Capability.UNKNOWN;
    this.storiesCapability           = Capability.UNKNOWN;
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
    this.isReleaseNotesRecipient     = false;

    //AA+
    setUfsrvRecipientDefaults(this, null, RecipientType.UKNOWN);
  }

  public Recipient(@NonNull RecipientId id, @NonNull RecipientDetails details, boolean resolved) {
    this.id                          = id;
    this.resolving                   = !resolved;
    this.serviceId                   = details.serviceId;
    this.pni                         = details.pni;
    this.username                    = details.username;
    this.e164                        = details.e164;
    this.email                       = details.email;
    this.groupId                     = details.groupId;
    this.distributionListId          = details.distributionListId;
    this.address                     = details.address;
    this.participants                = details.participants;
    this.groupAvatarId               = details.groupAvatarId;
    this.isSelf                      = details.isSelf;
    this.blocked                     = details.blocked;
    this.muteUntil                   = details.mutedUntil;
    this.messageVibrate              = details.messageVibrateState;
    this.callVibrate                 = details.callVibrateState;
    this.messageRingtone             = details.messageRingtone;
    this.callRingtone                = details.callRingtone;
    this.insightsBannerTier          = details.insightsBannerTier;
    this.defaultSubscriptionId       = details.defaultSubscriptionId;
    this.expireMessages              = details.expireMessages;
    this.registered                  = details.registered;
    this.profileKey                  = details.profileKey;
    this.profileKeyCredential        = details.profileKeyCredential;
    this.groupName                   = details.groupName;
    this.systemContactPhoto          = details.systemContactPhoto;
    this.customLabel                 = details.customLabel;
    this.contactUri                  = details.contactUri;
    this.signalProfileName           = details.profileName;
    this.profileAvatar               = details.profileAvatar;
    this.profileSharing              = details.profileSharing;
    this.lastProfileFetch            = details.lastProfileFetch;
    this.notificationChannel         = details.notificationChannel;
    this.unidentifiedAccessMode      = details.unidentifiedAccessMode;
    this.forceSmsSelection           = details.forceSmsSelection;
    this.senderKeyCapability         = details.senderKeyCapability;
    this.announcementGroupCapability = details.announcementGroupCapability;
    this.changeNumberCapability      = details.changeNumberCapability;
    this.storiesCapability           = details.storiesCapability;
    this.storageId                   = details.storageId;
    this.mentionSetting              = details.mentionSetting;
    this.wallpaper                   = details.wallpaper;
    this.chatColors                  = details.chatColors;
    this.avatarColor                 = details.avatarColor;
    this.about                       = details.about;
    this.aboutEmoji                  = details.aboutEmoji;
    this.systemProfileName           = details.systemProfileName;
    this.systemContactName           = details.systemContactName;
    this.extras                      = details.extras;
    this.hasGroupsInCommon           = details.hasGroupsInCommon;
    this.badges                      = details.badges;
    this.isReleaseNotesRecipient     = details.isReleaseChannel;;

    //AA+
    this.avatarUfsrvId               = details.avatarUfsrvId;
    this.ufsrvUid                    = details.ufsrvUidEncoded;
    this.ufsrvId                     = details.ufsrvId;
    this.e164number                  = details.e164Number;
    this.ufsrvuname                  = details.ufsrvUname;
    this.eid                         = details.eid;
    this.nickname                    = details.nickname;
    this.recipientType               = details.recipientType;
    this.presenceSharing             = details.presenceSharing;
    this.presenceShared              = details.presenceShared;
    this.presenceInformation         = details.presenceInformation;
    this.readReceiptSharing          = details.readReceiptSharing;
    this.readReceiptShared           = details.readReceiptShared;
    this.typingIndicatorSharing      = details.typingIndicatorSharing;
    this.typingIndicatorShared       = details.typingIndicatorShared;
    this.locationSharing             = details.locationSharing;
    this.locationShared              = details.locationShared;
    this.locationInformation         = details.locationInformation;
    this.guardianStatus              = details.guardianStatus;
    this.profileShared               = details.profileShared;
  }

  //AA+
  Recipient(@NonNull RecipientUfsrvId ufsrvId) {
    this.id                          = RecipientId.from(-1);
    this.resolving                   = true;
    this.address                     = null;
    this.serviceId                   = null;
    this.pni                         = null;
    this.username                    = null;
    this.e164                        = null;
    this.email                       = null;
    this.groupId                     = null;
    this.distributionListId          = null;
    this.participants                = Collections.emptyList();
    this.groupAvatarId               = Optional.empty();
    this.isSelf                      = false;
    this.blocked                     = false;
    this.muteUntil                   = 0;
    this.messageVibrate              = VibrateState.DEFAULT;
    this.callVibrate                 = VibrateState.DEFAULT;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.insightsBannerTier          = RecipientDatabase.InsightsBannerTier.TIER_TWO;
    this.defaultSubscriptionId       = Optional.empty();
    this.expireMessages              = 0;
    this.registered                  = RegisteredState.UNKNOWN;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.groupName                   = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.signalProfileName           = ProfileName.EMPTY;
    this.profileAvatar               = null;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.DISABLED;
    this.forceSmsSelection           = false;
    this.senderKeyCapability         = Capability.UNKNOWN;
    this.announcementGroupCapability = Capability.UNKNOWN;
    this.changeNumberCapability      = Capability.UNKNOWN;
    this.storiesCapability           = Capability.UNKNOWN;
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
    this.isReleaseNotesRecipient =  false;

    //AA+
    setUfsrvRecipientDefaults(this, null,  RecipientType.UKNOWN);
    this.ufsrvId = ufsrvId.toId();
  }

  Recipient(@NonNull String ufsrvUid) {
    this.id                          = RecipientId.from(-1);
    this.resolving                   = true;
    this.address                     = null;
    this.serviceId                   = null;
    this.pni                         = null;
    this.username                    = null;
    this.e164                        = null;
    this.email                       = null;
    this.groupId                     = null;
    this.distributionListId          = null;
    this.participants                = Collections.emptyList();
    this.groupAvatarId               = Optional.empty();
    this.isSelf                      = false;
    this.blocked                     = false;
    this.muteUntil                   = 0;
    this.messageVibrate              = VibrateState.DEFAULT;
    this.callVibrate                 = VibrateState.DEFAULT;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.insightsBannerTier          = RecipientDatabase.InsightsBannerTier.TIER_TWO;;
    this.defaultSubscriptionId       = Optional.empty();
    this.expireMessages              = 0;
    this.registered                  = RegisteredState.REGISTERED;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.groupName                   = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.signalProfileName           = ProfileName.EMPTY;
    this.profileAvatar               = null;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.DISABLED;
    this.forceSmsSelection           = false;
    this.senderKeyCapability         = Capability.UNKNOWN;
    this.announcementGroupCapability = Capability.UNKNOWN;
    this.changeNumberCapability      = Capability.UNKNOWN;
    this.storiesCapability           = Capability.UNKNOWN;
    this.storageId                   = null;
    this.mentionSetting              = MentionSetting.ALWAYS_NOTIFY;
    this.wallpaper                   = null;
    this.avatarColor                 = AvatarColor.UNKNOWN;
    this.chatColors                  = null;
    this.about                       = null;
    this.aboutEmoji                  = null;
    this.systemProfileName           = ProfileName.EMPTY;
    this.systemContactName           = null;
    this.extras                      = Optional.empty();
    this.hasGroupsInCommon           = false;
    this.badges                      = Collections.emptyList();
    this.isReleaseNotesRecipient     = false;

    //AA+
    setUfsrvRecipientDefaults(this, ufsrvUid,  RecipientType.USER);
  }

  private void setUfsrvRecipientDefaults(Recipient recipient, String ufsrvUid, RecipientType recipientType)
  {
    recipient.ufsrvId                  = !TextUtils.isEmpty(ufsrvUid)? UfsrvUid.fromEncoded(ufsrvUid).getUfsrvSequenceId() : 0;
    recipient.ufsrvUid                 = ufsrvUid;
    recipient.nickname                 = null;
    recipient.e164number               = null;
    recipient.ufsrvuname               = null;
    recipient.eid                      = 0;
    recipient.recipientType            = recipientType;
    recipient.avatarUfsrvId            = null;

    recipient.presenceSharing          = false;
    recipient.presenceShared           = false;
    recipient.presenceInformation      = null;

    recipient.readReceiptSharing       = false;
    recipient.readReceiptShared        = false;
    recipient.typingIndicatorSharing   = false;
    recipient.typingIndicatorShared    = false;

    recipient.locationSharing          = false;
    recipient.locationShared           = false;
    recipient.locationInformation      = null;

    recipient.guardianStatus           = RecipientDatabase.GuardianStatus.NONE;
    recipient.profileShared            = false;
  }
  //

  public @NonNull RecipientId getId() {
    return id;
  }

  public boolean isSelf() {
    return isSelf;
  }

  public @Nullable Uri getContactUri() {
    return contactUri;
  }

  //AA TBD see https://github.com/signalapp/Signal-Android/commit/5e3bbb0e643bc40e8b8bcede05e548e8874925cc
  /*public @Nullable String getGroupName() {
    if (this.groupName == null && groupId != null && groupId.isMms()) {
      List<String> names = new LinkedList<>();

      for (Recipient recipient : participants) {
        names.add(recipient.getDisplayName());
      }

      return Util.join(names, ", ");
    } else if (groupName == null && groupId != null && groupId.isPush()) {
      return ApplicationContext.getInstance().getString(R.string.RecipientProvider_unnamed_group);
    } else {
      return this.groupName;
    }

  }*/

  public @Nullable String getGroupName(@NonNull Context context) {
    if (groupId != null && Util.isEmpty(this.groupName)) {
      List<Recipient> others = participants.stream()
                                           .filter(r -> !r.isSelf())
                                           .limit(MAX_MEMBER_NAMES)
                                           .collect(Collectors.toList());

      Map<String, Integer> shortNameCounts = new HashMap<>();

      for (Recipient participant : others) {
        String shortName = participant.getShortDisplayName(context);
        int    count     = Objects.requireNonNull(shortNameCounts.getOrDefault(shortName, 0));

        shortNameCounts.put(shortName, count + 1);
      }

      List<String> names = new LinkedList<>();

      for (Recipient participant : others) {
        String shortName = participant.getShortDisplayName(context);
        int    count     = Objects.requireNonNull(shortNameCounts.getOrDefault(shortName, 0));

        if (count <= 1) {
          names.add(shortName);
        } else {
          names.add(participant.getDisplayName(context));
        }
      }

      if (participants.stream().anyMatch(Recipient::isSelf)) {
        names.add(context.getString(R.string.Recipient_you));
      }

      return Util.join(names, ", ");
    } else if (!resolving && isMyStory()) {
      return context.getString(R.string.Recipient_my_story);
    } else {
      return this.groupName;
    }
  }

 /* private @NonNull String getUsername() {
    if (FeatureFlags.usernames()) {
      // TODO [greyson] Replace with actual username
      return "@caycepollard";
    }
    return "";
  }*/

  public @NonNull String getDisplayNameOrUsername(@NonNull Context context) {
//    String name = getGroupName(context);
//
//    if (Util.isEmpty(name)) {
//      name = systemContactName;
//    }
//
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(getProfileName().toString());
//    }
//    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
//      name = PhoneNumberFormatter.prettyPrint(e164);
//    }
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(email);
//    }
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(username);
//    }
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(context.getString(R.string.Recipient_unknown));
//    }
//    return name;

    String name = getDisplayName();//AA+

    return StringUtil.isolateBidi(name);
  }

  public @NonNull String getMentionDisplayName(@NonNull Context context) {
//   String name = isSelf ? getProfileName().toString() : getGroupName(context);
//    name = StringUtil.isolateBidi(name);
//
//    if (Util.isEmpty(name)) {
//      name = isSelf ? getGroupName(context) : systemContactName;
//      name = StringUtil.isolateBidi(name);
//    }
//
//    if (Util.isEmpty(name)) {
//      name = isSelf ? getGroupName(context) : getProfileName().toString();
//      name = StringUtil.isolateBidi(name);
//    }
//
//    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
//      name = PhoneNumberFormatter.prettyPrint(e164);
//    }
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(email);
//    }
//    if (Util.isEmpty(name)) {
//      name = StringUtil.isolateBidi(context.getString(R.string.Recipient_unknown));
//    }
//    return name;

    String name = getDisplayName();//AA+

    return StringUtil.isolateBidi(name);
  }

  public @NonNull String getShortDisplayName(@NonNull Context context) {
    String name = Util.getFirstNonEmpty(getDisplayName(context),//AA+ get that first
                                        getSystemProfileName().getGivenName(),
                                        getGroupName(context),
                                        getProfileName().getGivenName(),
                                        getDisplayName(context));

    return StringUtil.isolateBidi(name);
  }

  public @NonNull String getShortDisplayNameIncludingUsername(@NonNull Context context) {
    String name = Util.getFirstNonEmpty(getDisplayName(context),//AA+ get that first
                                        getGroupName(context),
                                        getSystemProfileName().getGivenName(),
                                        getDisplayName(context),
                                        getUsername().orElse(null));

    return StringUtil.isolateBidi(name);
  }

  public @NonNull Optional<String> getUfsrvUidx() {
    return Optional.ofNullable(ufsrvUid);
  }

  public @NonNull Optional<ServiceId> getServiceId() {
    return Optional.ofNullable(serviceId);
  }

  public @NonNull Optional<PNI> getPni() {
    return Optional.ofNullable(pni);
  }

  public @NonNull Optional<String> getUsername() {
    if (FeatureFlags.usernames()) {
      return Optional.ofNullable(presentNickname());//AA+ presentNickname
    } else {
      return Optional.empty();
    }
  }

  public @NonNull Optional<String> getE164() {
    return Optional.ofNullable(e164);
  }

  public @NonNull Optional<String> getEmail() {
    return Optional.ofNullable(email);
  }

  public @NonNull Optional<GroupId> getGroupId() {
    return Optional.ofNullable(groupId);
  }

  public @NonNull Optional<DistributionListId> getDistributionListId() {
    return Optional.ofNullable(distributionListId);
  }

  public @NonNull Optional<String> getSmsAddress() {
    return OptionalUtil.or(Optional.ofNullable(e164), Optional.ofNullable(email));
  }

  public @NonNull PNI requirePni() {
    PNI resolved = resolving ? resolve().pni : pni;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public @NonNull String requireE164() {
    String resolved = resolving ? resolve().e164 : e164;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public @NonNull String requireEmail() {
    String resolved = resolving ? resolve().email : email;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  public @NonNull String requireSmsAddress() {
    Recipient recipient = resolving ? resolve() : this;

    if (recipient.getE164().isPresent()) {
      return recipient.getE164().get();
    } else if (recipient.getEmail().isPresent()) {
      return recipient.getEmail().get();
    } else {
      throw new MissingAddressError(id);
    }
  }

  public boolean hasUfsrvUid() {
    return getUfsrvUidx().isPresent();
  }

  public boolean hasSmsAddress() {
    return OptionalUtil.or(getE164(), getEmail()).isPresent();
  }

  public boolean hasE164() {
    return getE164().isPresent();
  }

  public boolean hasServiceId() {
    return getServiceId().isPresent();
  }

  public boolean hasPni() {
    return getPni().isPresent();
  }

  public boolean isServiceIdOnly() {
    return hasServiceId() && !hasSmsAddress();
  }

  public boolean shouldHideStory() {
    return extras.map(Extras::hideStory).orElse(false);
  }

  public @NonNull GroupId requireGroupId() {
    GroupId resolved = resolving ? resolve().groupId : groupId;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  //AA+
  public long getCorrespondingThread(@NonNull Context context)
  {
    return SignalDatabase.threads().getOrCreateThreadIdFor(this);
  }
  //

  public @NonNull DistributionListId requireDistributionListId() {
    DistributionListId resolved = resolving ? resolve().distributionListId : distributionListId;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  /**
   * The {@link ServiceId} of the user if available, otherwise throw.
   */
  public @NonNull ServiceId requireServiceId() {
    ServiceId resolved = resolving ? resolve().serviceId : serviceId;

    if (resolved == null) {
      throw new MissingAddressError(id);
    }

    return resolved;
  }

  /**
   * @return A string identifier able to be used with the Signal service.
   */
  public @NonNull String requireServiceIdUfsrv() {
    return requireUfsrvUid();//AA+
  }

  public @NonNull Address requireAddress() {
    if (resolving) {
      return resolve().address;
    } else {
      return address;
    }
  }

  public @NonNull String requireUfsrvUid() {
    if (resolving) {
      return resolve().ufsrvUid;
    } else {
      return ufsrvUid;
    }
  }

  /**
   * @return A single string to represent the recipient, in order of precedence:
   *
   * Group ID > UUID > Phone > Email
   */
  public @NonNull String requireStringId() {
    Recipient resolved = resolving ? resolve() : this;

    if (resolved.isGroup()) {
      return resolved.requireGroupId().toString();
    } else if (resolved.getUfsrvUidx().isPresent()) {
      return resolved.getUfsrvUid();//AA+
    } else if (resolved.getServiceId().isPresent()) {
      return resolved.getServiceId().get().toString();
    }

    return requireSmsAddress();
  }

  public Optional<Integer> getDefaultSubscriptionId() {
    return defaultSubscriptionId;
  }

  public @NonNull ProfileName getProfileName() {
    return signalProfileName;
  }

  public @NonNull ProfileName getSystemProfileName() {
    return systemProfileName;
  }

  public @Nullable String getProfileAvatar() {
    return profileAvatar;
  }

  public boolean isProfileSharing() {
    return profileSharing;
  }

  public long getLastProfileFetchTime() {
    return lastProfileFetch;
  }

  public boolean isGroup() {
    return resolve().groupId != null;
  }

  private boolean isGroupInternal() {
    return groupId != null;
  }

  public boolean isMmsGroup() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isMms();
  }

  public boolean isPushGroup() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isPush();
  }

  public boolean isPushV1Group() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isV1();
  }

  public boolean isPushV2Group() {
    GroupId groupId = resolve().groupId;
    return groupId != null && groupId.isV2();
  }

  public boolean isDistributionList() {
    return resolve().distributionListId != null;
  }

  public boolean isMyStory() {
    return Objects.equals(resolve().distributionListId, DistributionListId.from(DistributionListId.MY_STORY_ID));
  }

  public boolean isActiveGroup() {
    return Stream.of(getParticipants()).anyMatch(Recipient::isSelf);
  }

  public @NonNull List<Recipient> getParticipants() {
    return new ArrayList<>(participants);
  }

  public @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getFallbackContactPhotoDrawable(context, inverted, DEFAULT_FALLBACK_PHOTO_PROVIDER, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getSmallFallbackContactPhotoDrawable(context, inverted, DEFAULT_FALLBACK_PHOTO_PROVIDER);
  }

  public @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    return getFallbackContactPhoto(Util.firstNonNull(fallbackPhotoProvider, DEFAULT_FALLBACK_PHOTO_PROVIDER), targetSize).asDrawable(context, avatarColor, inverted);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider) {
    return getSmallFallbackContactPhotoDrawable(context, inverted, fallbackPhotoProvider, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull Drawable getSmallFallbackContactPhotoDrawable(Context context, boolean inverted, @Nullable FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    return getFallbackContactPhoto(Util.firstNonNull(fallbackPhotoProvider, DEFAULT_FALLBACK_PHOTO_PROVIDER), targetSize).asSmallDrawable(context, avatarColor, inverted);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto() {
    return getFallbackContactPhoto(DEFAULT_FALLBACK_PHOTO_PROVIDER);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto(@NonNull FallbackPhotoProvider fallbackPhotoProvider) {
    return getFallbackContactPhoto(fallbackPhotoProvider, AvatarUtil.UNDEFINED_SIZE);
  }

  public @NonNull FallbackContactPhoto getFallbackContactPhoto(@NonNull FallbackPhotoProvider fallbackPhotoProvider, int targetSize) {
    if      (isSelf)                                return fallbackPhotoProvider.getPhotoForLocalNumber();
    else if (isResolving())                         return fallbackPhotoProvider.getPhotoForResolvingRecipient();
    else if (isDistributionList())                  return fallbackPhotoProvider.getPhotoForDistributionList();
    else if (isGroupInternal())                     return fallbackPhotoProvider.getPhotoForGroup();
    else if (isGroup())                             return fallbackPhotoProvider.getPhotoForGroup();
    else if (!TextUtils.isEmpty(groupName))         return fallbackPhotoProvider.getPhotoForRecipientWithName(groupName, targetSize);
    else if (!TextUtils.isEmpty(systemContactName)) return fallbackPhotoProvider.getPhotoForRecipientWithName(systemContactName, targetSize);
    else if (!signalProfileName.isEmpty())          return fallbackPhotoProvider.getPhotoForRecipientWithName(signalProfileName.toString(), targetSize);
    else                                            return fallbackPhotoProvider.getPhotoForRecipientWithoutName();
  }

  public @Nullable ContactPhoto getContactPhoto() {
    if      (isSelf)                                    return null;
    else if (isGroupInternal() && groupAvatarId.isPresent() && UfsrvFenceUtils.isAvatarUfsrvIdLoaded(avatarUfsrvId)) return new GroupRecordContactPhoto(groupId, groupAvatarId.get(), avatarUfsrvId);
    else if (systemContactPhoto != null && SignalStore.settings().isPreferSystemContactPhotos()) return new SystemContactPhoto(id, systemContactPhoto, 0);
    else if (UfsrvFenceUtils.isAvatarUfsrvIdLoaded(avatarUfsrvId))                       return new ProfileContactPhoto(this, avatarUfsrvId);
    else if (!TextUtils.isEmpty(avatarUfsrvId) && !avatarUfsrvId.equals("0"))              return new ProfileContactPhoto(this, avatarUfsrvId);//AA++
    else if (systemContactPhoto != null)                     return new SystemContactPhoto(id, systemContactPhoto, 0);
    else                                                     return null;
  }

  public @Nullable Uri getMessageRingtone() {
    if (messageRingtone != null && messageRingtone.getScheme() != null && messageRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return messageRingtone;
  }

  public @Nullable Uri getCallRingtone() {
    if (callRingtone != null && callRingtone.getScheme() != null && callRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return callRingtone;
  }

  public boolean isMuted() {
    return System.currentTimeMillis() <= muteUntil;
  }

  public long getMuteUntil() {
    return muteUntil;
  }

  public boolean isBlocked() {
    return blocked;
  }

  public @NonNull VibrateState getMessageVibrate() {
    return messageVibrate;
  }

  public @NonNull VibrateState getCallVibrate() {
    return callVibrate;
  }

  public int getExpiresInSeconds () {
    return expireMessages;
  }

  public boolean hasSeenFirstInviteReminder() {
    return insightsBannerTier.seen(RecipientDatabase.InsightsBannerTier.TIER_ONE);
  }

  public boolean hasSeenSecondInviteReminder() {
    return insightsBannerTier.seen(RecipientDatabase.InsightsBannerTier.TIER_TWO);
  }

  public @NonNull RegisteredState getRegistered() {
    if      (isPushGroup()) return RegisteredState.REGISTERED;
    else if (isMmsGroup())  return RegisteredState.NOT_REGISTERED;

    return registered;
  }

  public boolean isRegistered() {
    return registered == RegisteredState.REGISTERED || isPushGroup();
  }

  public boolean isMaybeRegistered() {
    return registered != RegisteredState.NOT_REGISTERED || isPushGroup();
  }

  public boolean isUnregistered() {
    return registered == RegisteredState.NOT_REGISTERED && !isPushGroup();
  }

  //AA+
  public boolean isUndefined() {
    return id.equals(RecipientId.UNKNOWN);
  }

  public @Nullable String getNotificationChannel() {
    return !NotificationChannels.supported() ? null : notificationChannel;
  }

  public boolean isForceSmsSelection() {
    return forceSmsSelection;
  }

  public @NonNull Capability getSenderKeyCapability() {
    return senderKeyCapability;
  }

  public @NonNull Capability getAnnouncementGroupCapability() {
    return announcementGroupCapability;
  }

  public @NonNull Capability getChangeNumberCapability() {
    return changeNumberCapability;
  }

  public @NonNull Capability getStoriesCapability() {
    return storiesCapability;
  }

  /**
   * True if this recipient supports the message retry system, or false if we should use the legacy session reset system.
   */
  public boolean supportsMessageRetries() {
    return getSenderKeyCapability() == Capability.SUPPORTED;
  }

  public @Nullable byte[] getProfileKey() {
    return profileKey;
  }

  public @Nullable ProfileKeyCredential getProfileKeyCredential() {
    return profileKeyCredential;
  }

  public boolean hasProfileKeyCredential() {
    return profileKeyCredential != null;
  }

  public @Nullable byte[] getStorageServiceId() {
    return storageId;
  }

  public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
    return unidentifiedAccessMode;
  }

  public @Nullable ChatWallpaper getWallpaper() {
    if (wallpaper != null) {
      return wallpaper;
    } else {
      return SignalStore.wallpaper().getWallpaper();
    }
  }

  public boolean hasOwnWallpaper() {
    return wallpaper != null;
  }

  /**
   * A cheap way to check if wallpaper is set without doing any unnecessary proto parsing.
   */
  public boolean hasWallpaper() {
    return wallpaper != null || SignalStore.wallpaper().hasWallpaperSet();
  }

  public boolean hasOwnChatColors() {
    return chatColors != null;
  }

  public @NonNull ChatColors getChatColors() {
    if (chatColors != null && !(chatColors.getId() instanceof ChatColors.Id.Auto)) {
      return chatColors;
    } if (chatColors != null) {
      return getAutoChatColor();
    } else {
      ChatColors global = SignalStore.chatColorsValues().getChatColors();
      if (global != null && !(global.getId() instanceof ChatColors.Id.Auto)) {
        return global;
      } else {
        return getAutoChatColor();
      }
    }
  }

  private @NonNull ChatColors getAutoChatColor() {
    if (getWallpaper() != null) {
      return getWallpaper().getAutoChatColors();
    } else {
      return ChatColorsPalette.Bubbles.getDefault().withId(ChatColors.Id.Auto.INSTANCE);
    }
  }

  public @NonNull AvatarColor getAvatarColor() {
    return avatarColor;
  }

  public boolean isSystemContact() {
    return contactUri != null;
  }

  //AA+ ------------------------------------
  public synchronized boolean isPairedGroup ()
  {
    if (!isGroup()) return false;

    GroupDatabase groupDatabase = SignalDatabase.groups();
    if (!groupDatabase.isPairedGroup(groupId)) return false;

    return true;
  }

  public synchronized boolean isGuardianGroup ()
  {
    if (!isGroup()) return false;

    GroupDatabase groupDatabase =SignalDatabase.groups();
    if (!groupDatabase.isPairedGroup(groupId)) return false;

    return true;
  }

  public String getAvatarUfsrvId ()
  {
    return this.avatarUfsrvId;
  }

  public boolean isUfsrvResolving () {
    return this.isUfsrvResolving;
  }

  public synchronized RecipientDatabase.GeogroupStickyState getGeogroupSticky() {
    return geogroupSticky;
  }

  public String getNickname() {
    return this.nickname;
  }

  public long getUfsrvId() {
    return ufsrvId;
  }

  public String getUfsrvUid() {
    return ufsrvUid;
  }

  public byte[] getUfrsvUidRaw()
  {
    if (!TextUtils.isEmpty(ufsrvUid)) {
      return UfsrvUid.DecodeUfsrvUid(ufsrvUid);
    } else {
      return null;
    }
  }

  public String getE164number ()
  {
    return e164number;
  }

  public String getUfsrvUname ()
  {
    return ufsrvuname;
  }

  public boolean hasName() {
    return groupName != null;
  }

  /**
   * False iff it {@link #getDisplayName} would fall back to e164, email or unknown.
   */
  public boolean hasAUserSetDisplayName(@NonNull Context context) {
    return !TextUtils.isEmpty(getGroupName(context))             ||
            !TextUtils.isEmpty(systemContactName)                ||
            !TextUtils.isEmpty(getProfileName().toString());
  }


  /**
   * Try all available strategies to come up with a presentable identifier for this recipient.
   */
  public @NonNull String getDisplayName() {
    if (isGroup()) return StringUtil.isolateBidi(toShortStylisedGroupName(groupId).toString());
    if (isNicknameSet())  return StringUtil.isolateBidi(this.nickname);
    if (!TextUtils.isEmpty(getSystemProfileName().toString()))  return StringUtil.isolateBidi(getSystemProfileName().toString());
    if (!TextUtils.isEmpty(this.ufsrvuname)) return StringUtil.isolateBidi(this.ufsrvuname);

    if (!TextUtils.isEmpty(this.ufsrvUid)) return StringUtil.isolateBidi(this.ufsrvUid);

    return StringUtil.isolateBidi("unfacd");

  }

  //AA+
  public @NonNull String getDisplayName(@NonNull Context context) {
    return getDisplayName();
    /*String name = getGroupName(context);

    if (Util.isEmpty(name)) {
      name = systemContactName;
    }

    if (Util.isEmpty(name)) {
      name = getProfileName().toString();
    }
    if (Util.isEmpty(name) && !Util.isEmpty(e164)) {
      name = PhoneNumberFormatter.prettyPrint(e164);
    }
    if (Util.isEmpty(name)) {
      name = email;
    }
    if (Util.isEmpty(name)) {
      name = context.getString(R.string.Recipient_unknown);
    }
    return StringUtil.isolateBidi(name);*/
  }

  private boolean isNicknameSet()
  {
    return   !TextUtils.isEmpty(this.nickname) && this.nickname.length() > 1 && !this.nickname.startsWith("*");
  }

  private boolean isNameSet()
  {
    return   !TextUtils.isEmpty(this.groupName) && this.groupName.length() > 1 && !this.groupName.startsWith("*");
  }

  public Optional<String> getNicknameMaybe() {

    if (!isNicknameSet()) {
      return Optional.empty();
    }

    return Optional.of(nickname);
  }

  /**
   * Where nickname is not set, return a presentable format suitable for display
   */
  public String presentNickname() {
    Optional<String> nickname = getNicknameMaybe();
    if (nickname.isPresent()) {
      return StringUtil.isolateBidi(nickname.get());
    } else {
      return ApplicationDependencies.getApplication().getString(R.string.ManageProfileFragment_unset_nickname);
    }
  }

  /** Try various presentation strategies to represent a give gropo's name, failing through to id */
  public synchronized SpannableString toShortStylisedGroupName(@NonNull GroupId groupId) {
    Optional<Object> formattedTitle = SignalDatabase.groups().isPairedGroupWithStyliser(groupId, PairedGroupName::styliseGroupTitle);
    if (formattedTitle.isPresent()) {
      return (SpannableString)formattedTitle.get();
    }

    return new SpannableString(TextUtils.isEmpty(this.groupName) ? this.groupId.toString() : this.groupName);
  }

  public long getEid () {
    return eid;
  }

  public RecipientDatabase.GuardianStatus getGuardianStatus ()
  {
    return guardianStatus;
  }

  public synchronized boolean isProfileShared() {
    return profileShared;
  }

  public boolean isPresenceSharing () {
    return presenceSharing;
  }

  public boolean isPresenceShared () {
    return presenceShared;
  }

  public String getPresenceInformation() {
    return this.presenceInformation;
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

  public static @NonNull List<Recipient> listFromUfsrvIds (Context context, @NonNull String rawText, boolean asynchronous) {
    List<String>  elements  = Util.split(rawText, ",");
    List<Address> addresses = new LinkedList<>();
    List<Recipient> recipientList = new LinkedList<>();

    for (String element: elements) {
      Recipient recipient = Recipient.live(RecipientUfsrvId.from(Long.valueOf(element))).get();//, Optional.empty(), asynchronous, false);
      recipientList.add(recipient);
      addresses.add(recipient.requireAddress());
    }

    return recipientList;
  }
  // --------------------------------------------------

  public @Nullable String getAbout() {
    return about;
  }

  public @Nullable String getAboutEmoji() {
    return aboutEmoji;
  }

  public @NonNull List<Badge> getBadges() {
    return FeatureFlags.displayDonorBadges() || isSelf() ? badges : Collections.emptyList();
  }

  public @Nullable Badge getFeaturedBadge() {
    if (getBadges().isEmpty()) {
      return null;
    } else {
      return getBadges().get(0);
    }
  }

  public @Nullable String getCombinedAboutAndEmoji() {
    if (!Util.isEmpty(aboutEmoji)) {
      if (!Util.isEmpty(about)) {
        return aboutEmoji + " " + about;
      } else {
        return aboutEmoji;
      }
    } else if (!Util.isEmpty(about)) {
      return about;
    } else {
      return null;
    }
  }

  public boolean shouldBlurAvatar() {
    boolean showOverride = false;
    if (extras.isPresent()) {
      showOverride = extras.get().manuallyShownAvatar();
    }
    return !showOverride && !isSelf() && !isProfileSharing() && !isSystemContact() && !hasGroupsInCommon && isRegistered();
  }

  public boolean hasGroupsInCommon() {
    return hasGroupsInCommon;
  }

  /**
   * If this recipient is missing crucial data, this will return a populated copy. Otherwise it
   * returns itself.
   */
  public @NonNull Recipient resolve() {
    if (resolving) {
      return live().resolve();
    } else {
      return this;
    }
  }

  public boolean isResolving() {
    return resolving;
  }

  /**
   * Forces retrieving a fresh copy of the recipient, regardless of its state.
   */
  public @NonNull Recipient fresh() {
    return live().resolve();
  }

  public @NonNull LiveRecipient live() {
    return ApplicationDependencies.getRecipientCache().getLive(id);
  }

  public @NonNull MentionSetting getMentionSetting() {
    return mentionSetting;
  }

  public boolean isReleaseNotes() {
    return isReleaseNotesRecipient;
  }

  public boolean showVerified() {
    return isReleaseNotesRecipient || isSelf;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Recipient recipient = (Recipient) o;
    return id.equals(recipient.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  public enum Capability {
    UNKNOWN(0),
    SUPPORTED(1),
    NOT_SUPPORTED(2);

    private final int value;

    Capability(int value) {
      this.value = value;
    }

    public int serialize() {
      return value;
    }

    public static Capability deserialize(int value) {
      switch (value) {
        case 0:  return UNKNOWN;
        case 1:  return SUPPORTED;
        case 2:  return NOT_SUPPORTED;
        default: throw new IllegalArgumentException();
      }
    }

    public static Capability fromBoolean(boolean supported) {
      return supported ? SUPPORTED : NOT_SUPPORTED;
    }
  }

  public static final class Extras {
    private final RecipientExtras recipientExtras;

    public static @Nullable Extras from(@Nullable RecipientExtras recipientExtras) {
      if (recipientExtras != null) {
        return new Extras(recipientExtras);
      } else {
        return null;
      }
    }

    private Extras(@NonNull RecipientExtras extras) {
      this.recipientExtras = extras;
    }

    public boolean manuallyShownAvatar() {
      return recipientExtras.getManuallyShownAvatar();
    }

    public boolean hideStory() {
      return recipientExtras.getHideStory();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      final Extras that = (Extras) o;
      return manuallyShownAvatar() == that.manuallyShownAvatar() && hideStory() == that.hideStory();
    }

    @Override
    public int hashCode() {
      return Objects.hash(manuallyShownAvatar(), hideStory());
    }
  }

  public boolean hasSameContent(@NonNull Recipient other) {
    return Objects.equals(id, other.id) &&

            //AA+
            eid == other.eid &&
            ufsrvId == other.ufsrvId &&
            Objects.equals(ufsrvUid, other.ufsrvUid) &&
            Objects.equals(avatarUfsrvId, other.avatarUfsrvId) &&
            presenceSharing == other.presenceSharing &&
            presenceShared == other.presenceShared &&
            readReceiptSharing == other.readReceiptSharing &&
            readReceiptShared == other.readReceiptShared &&
            typingIndicatorSharing == other.typingIndicatorSharing &&
            typingIndicatorShared == other.typingIndicatorShared &&
            blockShared == other.blockShared &&
            contactSharing == other.contactSharing &&
            contactShared == other.contactShared &&
            Objects.equals(presenceInformation, other.presenceInformation) &&
            locationSharing == other.locationSharing &&
            locationShared == other.locationShared &&
            Objects.equals(locationInformation, other.locationInformation) &&
            profileShared == other.profileShared &&
            //

            resolving == other.resolving &&
            isSelf == other.isSelf &&
            blocked == other.blocked &&
            muteUntil == other.muteUntil &&
            expireMessages == other.expireMessages &&
//            hasProfileImage == other.hasProfileImage &&
            profileSharing == other.profileSharing &&
            lastProfileFetch == other.lastProfileFetch &&
            forceSmsSelection == other.forceSmsSelection &&
            Objects.equals(serviceId, other.serviceId) &&
            Objects.equals(username, other.username) &&
            Objects.equals(e164, other.e164) &&
            Objects.equals(email, other.email) &&
            Objects.equals(groupId, other.groupId) &&
            allContentsAreTheSame(participants, other.participants) &&
            Objects.equals(groupAvatarId, other.groupAvatarId) &&
            messageVibrate == other.messageVibrate &&
            callVibrate == other.callVibrate &&
            Objects.equals(messageRingtone, other.messageRingtone) &&
            Objects.equals(callRingtone, other.callRingtone) &&
            Objects.equals(defaultSubscriptionId, other.defaultSubscriptionId) &&
            registered == other.registered &&
            Arrays.equals(profileKey, other.profileKey) &&
            Objects.equals(profileKeyCredential, other.profileKeyCredential) &&
            Objects.equals(groupName, other.groupName) &&
            Objects.equals(systemContactPhoto, other.systemContactPhoto) &&
            Objects.equals(customLabel, other.customLabel) &&
            Objects.equals(contactUri, other.contactUri) &&
            Objects.equals(signalProfileName, other.signalProfileName) &&
            Objects.equals(systemProfileName, other.systemProfileName) &&
            Objects.equals(profileAvatar, other.profileAvatar) &&
            Objects.equals(notificationChannel, other.notificationChannel) &&
            unidentifiedAccessMode == other.unidentifiedAccessMode &&
//            groupsV1MigrationCapability == other.groupsV1MigrationCapability &&
            insightsBannerTier == other.insightsBannerTier &&
            Arrays.equals(storageId, other.storageId) &&
            Objects.equals(wallpaper, other.wallpaper) &&
            Objects.equals(chatColors, other.chatColors) &&
            Objects.equals(avatarColor, other.avatarColor) &&
            Objects.equals(about, other.about) &&
            Objects.equals(aboutEmoji, other.aboutEmoji) &&
            Objects.equals(extras, other.extras) &&
            hasGroupsInCommon == other.hasGroupsInCommon &&
            Objects.equals(badges, other.badges);

  }

  private static boolean allContentsAreTheSame(@NonNull List<Recipient> a, @NonNull List<Recipient> b)
  {
    if (a.size() != b.size()) {
      return false;
    }

    for (int i = 0, len = a.size(); i < len; i++) {
      if (!a.get(i).hasSameContent(b.get(i))) {
        return false;
      }
    }

    return true;
  }

  public static class FallbackPhotoProvider {
    public @NonNull FallbackContactPhoto getPhotoForLocalNumber() {
      return new ResourceContactPhoto(R.drawable.ic_note_34, R.drawable.ic_note_24);
    }

    public @NonNull FallbackContactPhoto getPhotoForResolvingRecipient() {
      return new TransparentContactPhoto();
    }

    public @NonNull FallbackContactPhoto getPhotoForGroup() {
      return new ResourceContactPhoto(R.drawable.ic_group_outline_34, R.drawable.ic_group_outline_20, R.drawable.ic_group_outline_48);
    }

    public @NonNull FallbackContactPhoto getPhotoForRecipientWithName(String name, int targetSize) {
      return new GeneratedContactPhoto(name, R.drawable.ic_profile_outline_40, targetSize);
    }

    public @NonNull FallbackContactPhoto getPhotoForRecipientWithoutName() {
      return new ResourceContactPhoto(R.drawable.ic_profile_outline_40, R.drawable.ic_profile_outline_20, R.drawable.ic_profile_outline_48);
    }

    public @NonNull FallbackContactPhoto getPhotoForDistributionList() {
      return new ResourceContactPhoto(R.drawable.ic_group_outline_34, R.drawable.ic_group_outline_20, R.drawable.ic_group_outline_48);
    }
  }

  private static class MissingAddressError extends AssertionError {
    MissingAddressError(@NonNull RecipientId recipientId) {
      super("Missing address for " + recipientId.serialize());
    }
  }
}