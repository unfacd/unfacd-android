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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the-=
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.recipients;

import com.unfacd.android.R;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.PrivateGroupForTwoName;
import com.unfacd.android.utils.UfsrvFenceUtils;

import android.content.Context;
import android.net.Uri;
import android.os.Parcelable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase.RecipientSettings;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.util.DirectoryHelper;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.SoftHashMap;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

public class RecipientProvider { // aded public

  private static final String TAG = RecipientProvider.class.getSimpleName();

  private static final RecipientCache  recipientCache         = new RecipientCache();
  //A+
  private static final RecipientUserIdCache recipientUserIdCache  = new RecipientUserIdCache();
  //
  private static final ExecutorService asyncRecipientResolver = Util.newSingleThreadedLifoExecutor();

  // static directory lookup of outside entities
  private static final Map<String, RecipientDetails> STATIC_DETAILS = new HashMap<String, RecipientDetails>() {{
    put("262966", new RecipientDetails("Amazon", null, false, false, null, null));
    put("0", new RecipientDetails("unfacd", null, false, false, null, null));//
  }};

  //
  @NonNull Recipient getRecipient(@NonNull Context context, @NonNull Address address, @NonNull Optional<RecipientSettings> settings, @NonNull Optional<GroupRecord> groupRecord, boolean asynchronous) {
    if (address.describeContents()== Parcelable.CONTENTS_FILE_DESCRIPTOR)  throw new AssertionError("address undefined");
    
    Recipient cachedRecipient = recipientCache.get(address);
    if (cachedRecipient != null && (asynchronous || !cachedRecipient.isResolving()) &&
            ((!groupRecord.isPresent() && !settings.isPresent()) || !cachedRecipient.isResolving() || cachedRecipient.getName() != null)) {
      if (!cachedRecipient.isUfsrvResolving()) return cachedRecipient;
      else Log.w(TAG, String.format("getRecipient(address:'%s', recipient:'%d', async:'%b'): Address marked with UfsrvResolvingFailed: re-fetching", address, System.identityHashCode(cachedRecipient), asynchronous));
    }

    Optional<RecipientDetails> prefetchedRecipientDetails = createPrefetchedRecipientDetails(context, address, settings, groupRecord);

    if (asynchronous) {
      cachedRecipient = new Recipient(address, cachedRecipient, prefetchedRecipientDetails, getRecipientDetailsAsync(context, address, settings, groupRecord));
    } else {
      cachedRecipient = new Recipient(address, getRecipientDetailsSync(context, address, settings, groupRecord, true));
    }

    {
      String recentSteps = "";
      StackTraceElement[] traceElements = Thread.currentThread().getStackTrace();
      final int maxStepCount = 4;
      final int skipCount = 2;

      for (int i = Math.min(maxStepCount + skipCount, traceElements.length) - 1; i >= skipCount; i--) {
        String className = traceElements[i].getClassName().substring(traceElements[i].getClassName().lastIndexOf(".") + 1);
        recentSteps += " >> " + className + "." + traceElements[i].getMethodName() + "()";
      }

      Log.d(TAG, String.format("getRecipient(address:'%s', recipient:'%d'): Address NOT FOUND in cache... (stack:'%s'", address, System.identityHashCode(cachedRecipient), recentSteps));
    }

    recipientCache.set(address, cachedRecipient);

    return cachedRecipient;
  }

  @NonNull Recipient getRecipient(@NonNull Context context, @NonNull Long userId, @NonNull Optional<RecipientSettings> settings, boolean asynchronous) {
    Recipient cachedRecipient = recipientUserIdCache.get(userId);
    if (cachedRecipient != null  && (asynchronous || !cachedRecipient.isResolving()) && ((/*!groupRecord.isPresent() && */!settings.isPresent()) || !cachedRecipient.isResolving() || cachedRecipient.getName() != null)) {
      return cachedRecipient;
    }

    //try provided settings object
    if (settings.isPresent() && settings.get().getAddress()!=null) {
      Optional<Recipient> recipient = getCached(settings.get().getAddress().get(0));
      if (recipient.isPresent()) {
        recipientUserIdCache.set(userId, recipient.get());
        return recipient.get();
      }
    }

    //attempt retrieving settings based on uid
    Optional<RecipientSettings> recipientSettings=DatabaseFactory.getRecipientDatabase(context).getRecipientSettings(userId);
    if (recipientSettings.isPresent() && recipientSettings.get().getAddress()!=null) {
      Optional<Recipient>recipient=getCached(recipientSettings.get().getAddress().get(0));
      if (recipient.isPresent()) {
        recipientUserIdCache.set(userId, recipient.get());
        return recipient.get();
      } else {
        // since we have the settings already + address convert to recipient and cache
        Address userIdAddress = recipientSettings.get().getAddress().get(0);
        Optional<RecipientDetails> prefetchedRecipientDetails = createPrefetchedRecipientDetails(context, userIdAddress, recipientSettings, Optional.absent());
        Recipient userIdRecipient = new Recipient(userIdAddress, prefetchedRecipientDetails.get());

        recipientCache.set(userIdAddress, userIdRecipient);
        recipientUserIdCache.set(userId, userIdRecipient);
      }
    }

    {
      String recentSteps = "";
      StackTraceElement[] traceElements = Thread.currentThread().getStackTrace();
      final int maxStepCount = 4;
      final int skipCount = 2;

      for (int i = Math.min(maxStepCount + skipCount, traceElements.length) - 1; i >= skipCount; i--) {
        String className = traceElements[i].getClassName().substring(traceElements[i].getClassName().lastIndexOf(".") + 1);
        recentSteps += " >> " + className + "." + traceElements[i].getMethodName() + "()";
      }
      Log.d(TAG, String.format("getRecipient(address:'%s', recipient:'%d'): UID NOT FOUND in cache (stack:'%s')...", userId, System.identityHashCode(cachedRecipient), recentSteps));
    }

    return cachedRecipient;//todo: recipient value is null
  }

  @NonNull Optional<Recipient> getCached(@NonNull Address address) {
    return Optional.fromNullable(recipientCache.get(address));
  }

  private @NonNull Optional<RecipientDetails> createPrefetchedRecipientDetails(@NonNull Context context, @NonNull Address address,
                                                                               @NonNull Optional<RecipientSettings> settings,
                                                                               @NonNull Optional<GroupRecord> groupRecord)
  {
    if (address.isGroup() && settings.isPresent() && groupRecord.isPresent()) {
      return Optional.of(getGroupRecipientDetails(context, address, groupRecord, settings, true));
    } else if (address.isE164Number() && settings.isPresent()) { //
      boolean isLocalNumber = address.serialize().equals(TextSecurePreferences.getLocalNumber(context));
      return Optional.of(new RecipientDetails(null, null, !TextUtils.isEmpty(settings.get().getSystemDisplayName()), isLocalNumber, settings.get(), null));
    } else if (!address.isGroup() && settings.isPresent()) {
      boolean isLocalNumber = address.serialize().equals(TextSecurePreferences.getUfsrvUsername(context));
      return Optional.of(new RecipientDetails(null, null, !TextUtils.isEmpty(settings.get().getSystemDisplayName()), isLocalNumber, settings.get(), null));
    }

    return Optional.absent();
  }

  private @NonNull ListenableFutureTask<RecipientDetails> getRecipientDetailsAsync(final Context context,
                                                                                   final Address address,
                                                                                   final @NonNull Optional<RecipientSettings> settings,
                                                                                   @NonNull Optional<GroupRecord> groupRecord)
  {
    Callable<RecipientDetails> task = () -> getRecipientDetailsSync(context, address, settings, groupRecord, true);//

    ListenableFutureTask<RecipientDetails> future = new ListenableFutureTask<>(task);
    asyncRecipientResolver.submit(future);
    return future;
  }

  private @NonNull RecipientDetails getRecipientDetailsSync(Context context, Address address, Optional<RecipientSettings> settings, Optional<GroupRecord> groupRecord, boolean nestedAsynchronous)
  {
    if (address!=null && address.isGroup())   return getGroupRecipientDetails(context, address, groupRecord, settings, nestedAsynchronous);
    else if (address!=null && address.isE164Number()) {//
      return getIndividualContactRecipientDetails(context, address, settings);
    } else  return getIndividualRecipientDetails(context, address, settings);
  }
//

  //
  private @NonNull RecipientDetails getIndividualRecipientDetails(Context context, Address address, Optional<RecipientSettings> settings) {
    if (!settings.isPresent()) {
      if (address!=null) {
        settings = DatabaseFactory.getRecipientDatabase(context).getRecipientSettings(address);
      }
    }

    boolean isUfResolvedFailed = false;
    if (!settings.isPresent()) {
      Log.d(TAG, String.format("getIndividualRecipientDetails: NO RecipientSettings existed for '%s': Contacting unfacd directory for resolution", address.serialize()));
      try {
        Optional<ContactTokenDetails> tokenDetails = DirectoryHelper.refreshDirectoryForUser(context,  address);//address.toPhoneString());
        if (tokenDetails.isPresent()) {
          DatabaseFactory.getRecipientDatabase(context).setRegistered(address, RegisteredState.REGISTERED, tokenDetails.get());
          settings = DatabaseFactory.getRecipientDatabase(context).getRecipientSettings(address);

          if (!settings.isPresent())
          Log.d(TAG, String.format("getIndividualRecipientDetails: RecipientSettings Reload failed for '%s': using defaults", address.toPhoneString()));
        }
      } catch (IOException ex) {
        Log.d(TAG, ex.getMessage());
        isUfResolvedFailed = true;//
      }
    }

    if (!settings.isPresent() && STATIC_DETAILS.containsKey(address.toPhoneString())) {
      isUfResolvedFailed = false;
      return STATIC_DETAILS.get(address.toPhoneString());
    } else {
      boolean systemContact = settings.isPresent() && !TextUtils.isEmpty(settings.get().getSystemDisplayName());
      boolean isLocalNumber = address.serialize().equals(TextSecurePreferences.getUfsrvUserId(context));
      return new RecipientDetails(null, null, systemContact, isLocalNumber, settings.orNull(), null, isUfResolvedFailed);
    }
  }

  private @NonNull RecipientDetails getIndividualContactRecipientDetails(Context context, Address contactAddress, Optional<RecipientSettings> settings) {
    if (!settings.isPresent()) {
      settings = DatabaseFactory.getRecipientDatabase(context).getContactRecipientSettings(contactAddress.toE164PhoneString());
    }

    if (!settings.isPresent() && STATIC_DETAILS.containsKey(contactAddress.toPhoneString())) {
      return STATIC_DETAILS.get(contactAddress.toPhoneString());
    } else if (settings.isPresent()) {
      boolean systemContact = !TextUtils.isEmpty(settings.get().getSystemDisplayName());
      boolean isLocalNumber = contactAddress.serialize().equals(TextSecurePreferences.getUfsrvUserId(context));
      return new RecipientDetails(settings.get().getUsername(), null, systemContact, isLocalNumber, settings.get(), null);
    }

    return new RecipientDetails(null, null, false, contactAddress.serialize().equals(TextSecurePreferences.getUfsrvUserId(context)), settings.orNull(), null,
                                contactAddress, "", UfsrvUid.UndefinedUfsrvUid, Recipient.RecipientType.UKNOWN);

  }

  private @NonNull RecipientDetails getGroupRecipientDetails(Context context, Address groupId, Optional<GroupRecord> groupRecord, Optional<RecipientSettings> settings, boolean asynchronous) {

    if (!groupRecord.isPresent()) {
      groupRecord = DatabaseFactory.getGroupDatabase(context).getGroup(groupId.toGroupString());
    }

    if (!settings.isPresent()) {
      settings = DatabaseFactory.getRecipientDatabase(context).getRecipientSettings(groupId);
    }

    String          title;
    if (groupRecord.isPresent()) {
      Optional<Object> formattedTitle = DatabaseFactory.getGroupDatabase(context).isPrivateGroupForTwoWithStyliser(groupId.toGroupString(), PrivateGroupForTwoName::styliseGroupTitle);
      if (formattedTitle.isPresent()) {
        title = formattedTitle.get().toString();
      } else {
        title           = groupRecord.get().getTitle();
      }

      List<Address>   memberAddresses = groupRecord.get().getMembers();
      List<Recipient> members         = new LinkedList<>();
      Long            avatarId        = null;
      String          avatarUfsrvId   = "0";//

      for (Address memberAddress : memberAddresses) {
        members.add(getRecipient(context, memberAddress, Optional.absent(), Optional.absent(), asynchronous));// Pair
      }

      if (!groupId.isMmsGroup() && title == null) {
        title = context.getString(R.string.RecipientProvider_unnamed_group);
      }

      if (groupRecord.get().getAvatar() != null && groupRecord.get().getAvatar().length > 0) {
        avatarId = groupRecord.get().getAvatarId();
      }

      //
      if (UfsrvFenceUtils.isAvatarUfsrvIdLoaded(groupRecord.get().getAvatarUfId())) {
        avatarUfsrvId = groupRecord.get().getAvatarUfId();
      }
      //

      return new RecipientDetails(title, avatarId, false, false, settings.orNull(), members, settings.isPresent()?settings.get().getAddress().get(0):null, groupRecord.isPresent()?groupRecord.get().getFid():0, Recipient.RecipientType.GROUP);
    }

    return new RecipientDetails(context.getString(R.string.RecipientProvider_unnamed_group), null, false, false, settings.orNull(), null);
  }

  public static class RecipientDetails {
    @Nullable final String                 name;
    @Nullable final String                 customLabel;
    @Nullable final Uri                    systemContactPhoto;
    @Nullable final Uri                    contactUri;
    @Nullable final Long                   groupAvatarId;
    @Nullable final MaterialColor          color;
    @Nullable final Uri                    messageRingtone;
    @Nullable final Uri                    callRingtone;
    final long                   mutedUntil;
    @Nullable final VibrateState           messageVibrateState;
    @Nullable final VibrateState           callVibrateState;
    final boolean                blocked;
    final int                    expireMessages;
    @NonNull  final List<Recipient>        participants;
    @Nullable final String                 profileName;
    final boolean                seenInviteReminder;
    final Optional<Integer>      defaultSubscriptionId;
    @NonNull  final RegisteredState        registered;
    @Nullable final byte[]                 profileKey;
    @Nullable final String                 profileAvatar;
    final boolean                profileSharing;
    final boolean                systemContact;
    final boolean                isLocalNumber;
    @Nullable final String                 notificationChannel;
    @NonNull  final UnidentifiedAccessMode unidentifiedAccessMode;
    final boolean                forceSmsSelection;

    //
    boolean                   isUfsrvResolvingFailed = false;
    public Address            address;
    @Nullable  String         avatarUfsrvId;
    @Nullable public String   nickname;
    public           String   ufsrvUidEncoded;
    long                      ufsrvId; //fence id or user sequence id
    @Nullable final String    e164Number;
    @Nullable final String    username;
    final long                eid;
    final boolean             presenceSharing;
    final boolean             presenceShared;
    @Nullable final String    presenceInformation;
    final  boolean            readReceiptSharing;
    final  boolean            readReceiptShared;
    final  boolean            typingIndicatorSharing;
    final  boolean            typingIndicatorShared;
    final  boolean            blockShared;
    final  boolean            contactSharing;
    final  boolean            contactShared;
    final boolean             locationSharing;
    final boolean             locationShared;
    @Nullable final String    locationInformation;
    final boolean              profileShared;//
    public Recipient.RecipientType recipientType;
    //

    RecipientDetails(@Nullable String name, @Nullable Long groupAvatarId,
                     boolean systemContact, boolean isLocalNumber, @Nullable RecipientSettings settings,
                     @Nullable List<Recipient> participants)
    {
      this.groupAvatarId         = groupAvatarId;
      this.systemContactPhoto    = settings     != null ? Util.uri(settings.getSystemContactPhotoUri()) : null;
      this.customLabel           = settings     != null ? settings.getSystemPhoneLabel() : null;
      this.contactUri            = settings     != null ? Util.uri(settings.getSystemContactUri()) : null;
      this.color                 = settings     != null ? settings.getColor() : null;
      this.messageRingtone       = settings     != null ? settings.getMessageRingtone() : null;
      this.callRingtone          = settings     != null ? settings.getCallRingtone() : null;
      this.mutedUntil            = settings     != null ? settings.getMuteUntil() : 0;
      this.messageVibrateState   = settings     != null ? settings.getMessageVibrateState() : null;
      this.callVibrateState      = settings     != null ? settings.getCallVibrateState() : null;
      this.blocked               = settings     != null && settings.isBlocked();
      this.expireMessages        = settings     != null ? settings.getExpireMessages() : 0;
      this.participants          = participants == null ? new LinkedList<>() : participants;
      this.profileName           = settings     != null ? settings.getProfileName() : null;
      this.seenInviteReminder    = settings     != null && settings.hasSeenInviteReminder();
      this.defaultSubscriptionId = settings     != null ? settings.getDefaultSubscriptionId() : Optional.absent();
      this.registered            = settings     != null ? settings.getRegistered() : RegisteredState.UNKNOWN;
      this.profileKey            = settings     != null ? settings.getProfileKey() : null;
      this.profileAvatar         = settings     != null ? settings.getProfileAvatar() : null;
      this.profileSharing        = settings     != null && settings.isProfileSharing();
      this.profileShared         = settings     != null && settings.isProfileShared();//
      this.systemContact         = systemContact;
      this.isLocalNumber         = isLocalNumber;
      this.notificationChannel   = settings     != null ? settings.getNotificationChannel() : null;
      this.unidentifiedAccessMode          = settings     != null ? settings.getUnidentifiedAccessMode() : UnidentifiedAccessMode.DISABLED;
      this.forceSmsSelection               = settings     != null && settings.isForceSmsSelection();

      if (name == null && settings != null) this.name = settings.getSystemDisplayName();
      else                                  this.name = name;

      this.address                = (settings     != null && settings.getAddress()!=null)? settings.getAddress().get(0):null;//address check
      this.avatarUfsrvId          = settings      != null ? settings.getAvatarUfsrvId():"0";
      this.nickname               = settings      != null ? settings.getNickname() : null;
      this.ufsrvUidEncoded        = settings      != null ? settings.getUfsrvUid() : "";
      this.ufsrvId                = settings      != null ? settings.getUfsrvId() : 0;
      this.e164Number             = settings      != null ? settings.getE164Number() : null;
      this.username               = settings      != null ? settings.getUsername() : null;
      this.eid                    = settings      != null ? settings.getEid(): 0;
      this.recipientType          = settings      != null ? settings.getRecipientType(): Recipient.RecipientType.UKNOWN;
      this.presenceSharing        = settings      != null && settings.isPresenceSharing();
      this.presenceShared         = settings      != null && settings.isPresenceShared();
      this.presenceInformation    = settings      != null ? settings.getPresenceInformation() : null;
      this.readReceiptSharing     = settings      != null && settings.isReadReceiptSharing();
      this.readReceiptShared      = settings      != null && settings.isReadReceiptShared();
      this.typingIndicatorSharing = settings      != null && settings.isTypingIndicatorSharing();
      this.typingIndicatorShared  = settings      != null && settings.isTypingIndicatorShared();
      this.blockShared            = settings      != null && settings.isBlockShared();
      this.contactSharing         = settings      != null && settings.isContactSharing();
      this.contactShared          = settings      != null && settings.isContactShared();
      this.locationSharing        = settings      != null && settings.isLocationSharing();
      this.locationShared         = settings      != null && settings.isLocationShared();
      this.locationInformation    = settings      != null ? settings.getLocationInformation() : null;
      //
    }

    // indicate the state of ufsrvresolving
    RecipientDetails(@Nullable String name, @Nullable Long groupAvatarId,
                     boolean systemContact, boolean isLocalNumber, @Nullable RecipientSettings settings,
                     @Nullable List<Recipient> participants, boolean isUfsrvResolvingFailed)
    {
      this(name, groupAvatarId, systemContact, isLocalNumber, settings, participants);
      this.isUfsrvResolvingFailed = isUfsrvResolvingFailed;
    }

    //
    public RecipientDetails(@Nullable String name, @Nullable Long groupAvatarId,
                            boolean systemContact, boolean isLocalNumber, @Nullable RecipientSettings settings,
                            @Nullable List<Recipient> participants,
                            @Nullable Address address,
                            @Nullable String nickname,
                            String useridEncoded,
                            Recipient.RecipientType recipientType)
    {
      this(name,  groupAvatarId, systemContact, isLocalNumber, settings, participants);
      this.address          = address;
      this.nickname         = nickname;
      this.ufsrvUidEncoded  = useridEncoded;
      this.recipientType    = recipientType;
    }

    public RecipientDetails(@Nullable String name, @Nullable Long groupAvatarId,
                            boolean systemContact, boolean isLocalNumber, @Nullable RecipientSettings settings,
                            @Nullable List<Recipient> participants,
                            @Nullable Address address,
                            long ufsrvId,
                            Recipient.RecipientType recipientType)
    {
      this(name,  groupAvatarId, systemContact, isLocalNumber, settings, participants);
      this.address          = address;
      this.ufsrvId          = ufsrvId;
      this.recipientType    = recipientType;
    }
    //
  }

  private static class RecipientCache {

    private final Map<Address,Recipient> cache = new SoftHashMap<>(1000);;

    public synchronized Recipient get(Address address) {
      return cache.get(address);
    }

    public synchronized void set(Address address, Recipient recipient) {
      cache.put(address, recipient);
    }

  }

  //
  private static class RecipientUserIdCache {

    private final Map<Long,Recipient> cache = new SoftHashMap<>(1000);

    public synchronized Recipient get(long recipientUserId) {
      return cache.get(recipientUserId);
    }

    public synchronized void set(long recipientUserId, Recipient recipient) {
      cache.put(recipientUserId, recipient);
    }

  }
  //


}