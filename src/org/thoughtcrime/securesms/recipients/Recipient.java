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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.content.Context;
import android.graphics.drawable.Drawable;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase.GeogroupStickyState; //

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.SpannableString;
import android.text.TextUtils;

import org.thoughtcrime.securesms.logging.Log;

import com.annimon.stream.function.Consumer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.ui.components.PrivateGroupForTwoName;

import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.GroupRecordContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ProfileContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.ResourceContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.SystemContactPhoto;
import org.thoughtcrime.securesms.contacts.avatars.TransparentContactPhoto;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.RecipientSettings;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import org.thoughtcrime.securesms.database.RecipientDatabase.VibrateState;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.recipients.RecipientProvider.RecipientDetails;
import org.thoughtcrime.securesms.util.FutureTaskListener;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;

public class Recipient  implements RecipientModifiedListener {

  private static final String            TAG      = Recipient.class.getSimpleName();
  private static final RecipientProvider provider = new RecipientProvider();

  private final Set<RecipientModifiedListener> listeners = Collections.newSetFromMap(new WeakHashMap<RecipientModifiedListener, Boolean>());

//- lookup by uid passes null address, so we ned to be able to set in multiple initialisation contexts (async,etc..)
  private  @NonNull Address address;
  private final @NonNull List<Recipient> participants = new LinkedList<>();

  private @Nullable @JsonProperty String  name;
  private @Nullable String  customLabel;

  private           boolean resolving;
  private           boolean isLocalNumber;

  private @Nullable Uri                  systemContactPhoto;
  private @Nullable Long                 groupAvatarId;
  private           Uri                  contactUri;
  private @Nullable Uri                  messageRingtone       = null;
  private @Nullable Uri                  callRingtone          = null;
  private           long                 mutedUntil            = 0;
  private           boolean              blocked               = false;
  private           VibrateState         messageVibrate        = VibrateState.DEFAULT;
  private           VibrateState         callVibrate           = VibrateState.DEFAULT;
  private           int                  expireMessages        = 0;
  private           Optional<Integer>    defaultSubscriptionId = Optional.absent();
  private @NonNull  RegisteredState      registered            = RegisteredState.UNKNOWN;

  private @Nullable MaterialColor  color;
  private           boolean        seenInviteReminder;
  private @Nullable byte[]         profileKey;
  private @Nullable String         profileName;
  private @Nullable String         profileAvatar;
  private           boolean        profileSharing;
  private           String         notificationChannel;
  private           boolean        forceSmsSelection;
  private @NonNull  UnidentifiedAccessMode unidentifiedAccessMode = UnidentifiedAccessMode.DISABLED;

  //
  private           boolean               isUfsrvResolving = false;
  private @Nullable String                avatarUfsrvId;
  private @Nullable @JsonProperty String  nickname;
  private @JsonProperty           long    ufsrvId; //fid or sequence uid
  private @JsonProperty           String  ufsrvUid; //encoded
  private @JsonProperty           long    eid;
  private @JsonProperty           String  e164number;
  private @JsonProperty           String  username; //rego name
  private RecipientType                   recipientType   = RecipientType.UKNOWN;
  private GeogroupStickyState             geogroupSticky  = GeogroupStickyState.DEFAULT;
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
  }

  private           boolean        presenceSharing;
  private           boolean        presenceShared;
  private           boolean        readReceiptSharing;
  private           boolean        readReceiptShared;
  private           boolean        typingIndicatorSharing;
  private           boolean        typingIndicatorShared;
  private           boolean        blockShared;
  private           boolean        contactSharing;
  private           boolean        contactShared;
  private           String         presenceInformation; //"status, timestamp"
  private           boolean        locationSharing;
  private           boolean        locationShared;
  private           String         locationInformation; //"long, lat"
  private           boolean        profileShared;


  @SuppressWarnings("ConstantConditions")
  public static @NonNull Recipient from(@NonNull Context context, @NonNull Address address, boolean asynchronous) {
    return Recipient.from(context,  address, Optional.absent(), Optional.absent(), asynchronous);
  }

  @SuppressWarnings("ConstantConditions")
  public static @NonNull Recipient from(@NonNull Context context, @NonNull Address address, @NonNull Optional<RecipientSettings> settings, @NonNull Optional<GroupDatabase.GroupRecord> groupRecord, boolean asynchronous) {
    if (address == null) throw new AssertionError(address);
    return provider.getRecipient(context,  address, settings, groupRecord, asynchronous);// Pair
  }

  public static void applyCached(@NonNull Address address, Consumer<Recipient> consumer) {
    Optional<Recipient> recipient = provider.getCached(address);
    if (recipient.isPresent()) consumer.accept(recipient.get());
  }

  // mock recipient
  public static Recipient makeNullRecipient ()
  {
    return new Recipient(null, new RecipientDetails(null, Long.valueOf(0), false,false, null, new LinkedList<>()));
  }

  //
  public static @NonNull Recipient fromFid (Context context, long fid, boolean asynchronous) {
    String groupId=DatabaseFactory.getGroupDatabase(context).getGroupId(fid, null, false);
    if (groupId!=null) {
      return Recipient.from(context, Address.fromSerialized(groupId), asynchronous);
    }

    Log.e (TAG, String.format("fromFid {fid:'%d': ERROR: COULD NOT GENERATE RECIEPIENTS FROM PROVIDED fid", fid));
    return makeNullRecipient();
  }

  public static @NonNull Recipient fromUfsrvUid (@NonNull Context context, @NonNull UfsrvUid ufsrvUid, boolean asynchronous) {
    return fromUfsrvUid(context, ufsrvUid, Optional.absent(), asynchronous);
  }

  public static @NonNull Recipient fromUfsrvUid (@NonNull Context context, @NonNull UfsrvUid ufsrvUid, @NonNull Optional<RecipientSettings> settings, boolean asynchronous) {
    return Recipient.from(context, Address.fromExternal(context, ufsrvUid.toString()), settings, Optional.absent(), asynchronous);
  }

  public static @NonNull Recipient fromUfsrvId (@NonNull Context context, @NonNull long ufsrvId, @NonNull Optional<RecipientSettings> settings, boolean asynchronous) {
    return provider.getRecipient(context, ufsrvId, settings,  asynchronous);
  }

  public static @NonNull List<Recipient> listFromUfsrvIds (Context context, @NonNull String rawText, boolean asynchronous) {
    List<String>  elements  = Util.split(rawText, ",");
    List<Address> addresses = new LinkedList<>();
    List<Recipient> recipientList = new LinkedList<>();

    for (String element: elements) {
      Recipient recipient=Recipient.fromUfsrvId(context, Long.valueOf(element), Optional.absent(), asynchronous);
      recipientList.add(recipient);
      addresses.add(recipient.getAddress());
    }

    return recipientList;
  }
  //

  Recipient(Address adresss,
            @Nullable Recipient stale,
            @NonNull  Optional<RecipientDetails> details,
            @NonNull  ListenableFutureTask<RecipientDetails> future)
  {
    this.address      = adresss;
    this.color        = null;
    this.resolving    = true;

    if (stale != null) {
      this.address               = stale.address;
      this.name                  = stale.name;
      this.contactUri            = stale.contactUri;
      this.systemContactPhoto    = stale.systemContactPhoto;
      this.groupAvatarId         = stale.groupAvatarId;
      this.isLocalNumber         = stale.isLocalNumber;
      this.color                 = stale.color;
      this.customLabel           = stale.customLabel;
      this.messageRingtone       = stale.messageRingtone;
      this.callRingtone          = stale.callRingtone;
      this.mutedUntil            = stale.mutedUntil;
      this.blocked               = stale.blocked;
      this.messageVibrate        = stale.messageVibrate;
      this.callVibrate           = stale.callVibrate;
      this.expireMessages        = stale.expireMessages;
      this.seenInviteReminder    = stale.seenInviteReminder;
      this.defaultSubscriptionId = stale.defaultSubscriptionId;
      this.registered            = stale.registered;
      this.notificationChannel   = stale.notificationChannel;
      this.profileKey            = stale.profileKey;
      this.profileName           = stale.profileName;
      this.profileAvatar         = stale.profileAvatar;
      this.profileSharing        = stale.profileSharing;
      this.unidentifiedAccessMode = stale.unidentifiedAccessMode;
      this.forceSmsSelection      = stale.forceSmsSelection;

      this.participants.clear();
      this.participants.addAll(stale.participants);

      //
      this.ufsrvId              = stale.ufsrvId;
      this.ufsrvUid             = stale.ufsrvUid;
      this.nickname             = stale.nickname;
      this.e164number           = stale.e164number;
      this.username             = stale.username;
      this.eid                  = stale.eid;
      this.recipientType        = stale.recipientType;
      this.avatarUfsrvId        = stale.avatarUfsrvId;

      this.presenceSharing      = stale.presenceSharing;
      this.presenceShared       = stale.presenceShared;
      this.presenceInformation  = stale.presenceInformation;

      this.readReceiptSharing      = stale.readReceiptSharing;
      this.readReceiptShared       = stale.readReceiptShared;
      this.typingIndicatorSharing  = stale.typingIndicatorSharing;
      this.typingIndicatorShared   = stale.typingIndicatorShared;

      this.locationSharing      = stale.locationSharing;
      this.locationShared       = stale.locationShared;
      this.locationInformation  = stale.locationInformation;

      this.profileShared        = stale.profileShared;
      //
    }

    if (details.isPresent()) {
      this.address               = details.get().address;
      this.name                  = details.get().name;
      this.systemContactPhoto    = details.get().systemContactPhoto;
      this.groupAvatarId         = details.get().groupAvatarId;
      this.color                 = details.get().color;
      this.isLocalNumber         = details.get().isLocalNumber;
      this.messageRingtone       = details.get().messageRingtone;
      this.callRingtone          = details.get().callRingtone;
      this.mutedUntil            = details.get().mutedUntil;
      this.blocked               = details.get().blocked;
      this.messageVibrate        = details.get().messageVibrateState;
      this.callVibrate           = details.get().callVibrateState;
      this.expireMessages        = details.get().expireMessages;
      this.seenInviteReminder    = details.get().seenInviteReminder;
      this.defaultSubscriptionId = details.get().defaultSubscriptionId;
      this.registered            = details.get().registered;
      this.notificationChannel   = details.get().notificationChannel;
      this.profileKey            = details.get().profileKey;
      this.profileName           = details.get().profileName;
      this.profileAvatar         = details.get().profileAvatar;
      this.profileSharing        = details.get().profileSharing;
      this.unidentifiedAccessMode = details.get().unidentifiedAccessMode;
      this.forceSmsSelection      = details.get().forceSmsSelection;

      this.participants.clear();
      this.participants.addAll(details.get().participants);

      //
      this.avatarUfsrvId  = details.get().avatarUfsrvId;
      this.ufsrvUid       = details.get().ufsrvUidEncoded;
      this.ufsrvId        = details.get().ufsrvId;
      this.e164number     = details.get().e164Number;
      this.username       = details.get().username;
      this.eid            = details.get().eid;
      this.nickname       = details.get().nickname;
      this.recipientType  = details.get().recipientType;

      this.presenceSharing= details.get().presenceSharing;
      this.presenceShared = details.get().presenceShared;
      this.presenceInformation = details.get().presenceInformation;

      this.readReceiptSharing      = details.get().readReceiptSharing;
      this.readReceiptShared       = details.get().readReceiptShared;
      this.typingIndicatorSharing  = details.get().typingIndicatorSharing;
      this.typingIndicatorShared   = details.get().typingIndicatorShared;

      this.locationSharing= details.get().locationSharing;
      this.locationShared= details.get().locationShared;
      this.locationInformation = details.get().locationInformation;

      this.profileShared        = details.get().profileShared;//
      //
    }

    future.addListener(new FutureTaskListener<RecipientDetails>() {
      @Override
      public void onSuccess(RecipientDetails result) {
        if (result != null) {
          synchronized (Recipient.this) {
            Recipient.this.name                   = result.name;
            Recipient.this.contactUri             = result.contactUri;
            Recipient.this.systemContactPhoto     = result.systemContactPhoto;
            Recipient.this.groupAvatarId          = result.groupAvatarId;
            Recipient.this.isLocalNumber          = result.isLocalNumber;
            Recipient.this.color                  = result.color;
            Recipient.this.customLabel            = result.customLabel;
            Recipient.this.messageRingtone        = result.messageRingtone;
            Recipient.this.callRingtone           = result.callRingtone;
            Recipient.this.mutedUntil             = result.mutedUntil;
            Recipient.this.blocked                = result.blocked;
            Recipient.this.messageVibrate         = result.messageVibrateState;
            Recipient.this.callVibrate            = result.callVibrateState;
            Recipient.this.expireMessages         = result.expireMessages;
            Recipient.this.seenInviteReminder     = result.seenInviteReminder;
            Recipient.this.defaultSubscriptionId  = result.defaultSubscriptionId;
            Recipient.this.registered             = result.registered;
            Recipient.this.notificationChannel    = result.notificationChannel;
            Recipient.this.profileKey             = result.profileKey;
            Recipient.this.profileName            = result.profileName;
            Recipient.this.profileAvatar          = result.profileAvatar;
            Recipient.this.profileSharing         = result.profileSharing;
            Recipient.this.unidentifiedAccessMode = result.unidentifiedAccessMode;
            Recipient.this.forceSmsSelection      = result.forceSmsSelection;

            Recipient.this.participants.clear();
            Recipient.this.participants.addAll(result.participants);

            //
            Recipient.this.isUfsrvResolving       = result.isUfsrvResolvingFailed;
            Recipient.this.avatarUfsrvId          = result.avatarUfsrvId;
            Recipient.this.ufsrvUid               = result.ufsrvUidEncoded;
            Recipient.this.ufsrvId                = result.ufsrvId;
            Recipient.this.e164number             = result.e164Number;
            Recipient.this.username               = result.username;
            Recipient.this.eid                    = result.eid;
            Recipient.this.nickname               = result.nickname;
            Recipient.this.recipientType          = result.recipientType;

            Recipient.this.presenceSharing        = result.presenceSharing;
            Recipient.this.presenceShared         = result.presenceShared;
            Recipient.this.presenceInformation    = result.presenceInformation;

            Recipient.this.readReceiptSharing     = result.readReceiptSharing;
            Recipient.this.readReceiptShared      = result.readReceiptShared;
            Recipient.this.typingIndicatorSharing = result.typingIndicatorSharing;
            Recipient.this.typingIndicatorShared  = result.typingIndicatorShared;

            Recipient.this.blockShared            = result.blockShared;
            Recipient.this.contactSharing         = result.contactSharing;
            Recipient.this.contactShared          = result.contactShared;

            Recipient.this.locationSharing        = result.locationSharing;
            Recipient.this.locationShared         = result.locationShared;
            Recipient.this.locationInformation    = result.locationInformation;

            Recipient.this.profileShared          = result.profileShared;
            //

            Recipient.this.resolving              = false;

            if (!listeners.isEmpty()) {
              for (Recipient recipient : participants) recipient.addListener(Recipient.this);
            }

            Recipient.this.notifyAll();
          }

          notifyListeners();
        }
      }

      @Override
      public void onFailure(ExecutionException error) {
        Log.w(TAG, error);
      }
    });
  }

  Recipient(@NonNull Address address,  @NonNull RecipientDetails details) {
    this.address                = address;
    this.contactUri             = details.contactUri;
    this.name                   = details.name;
    this.systemContactPhoto     = details.systemContactPhoto;
    this.groupAvatarId          = details.groupAvatarId;
    this.isLocalNumber          = details.isLocalNumber;
    this.color                  = details.color;
    this.customLabel            = details.customLabel;
    this.messageRingtone        = details.messageRingtone;
    this.callRingtone           = details.callRingtone;
    this.mutedUntil             = details.mutedUntil;
    this.blocked                = details.blocked;
    this.messageVibrate         = details.messageVibrateState;
    this.callVibrate            = details.callVibrateState;
    this.expireMessages         = details.expireMessages;
    this.seenInviteReminder     = details.seenInviteReminder;
    this.defaultSubscriptionId  = details.defaultSubscriptionId;
    this.registered             = details.registered;
    this.notificationChannel    = details.notificationChannel;
    this.profileKey             = details.profileKey;
    this.profileName            = details.profileName;
    this.profileAvatar          = details.profileAvatar;
    this.profileSharing         = details.profileSharing;
    this.unidentifiedAccessMode = details.unidentifiedAccessMode;
    this.forceSmsSelection      = details.forceSmsSelection;

    this.participants.addAll(details.participants);

    //
    this.isUfsrvResolving       = details.isUfsrvResolvingFailed;
    this.avatarUfsrvId          = details.avatarUfsrvId;
    this.ufsrvId                = details.ufsrvId;
    this.ufsrvUid               = details.ufsrvUidEncoded;
    this.e164number             = details.e164Number;
    this.username               = details.username;
    this.nickname               = details.nickname;
    this.eid                    = details.eid;
    this.recipientType          = details.recipientType;

    this.presenceSharing        = details.presenceSharing;
    this.presenceShared         = details.presenceShared;
    this.presenceInformation    = details.presenceInformation;

    this.readReceiptSharing     = details.readReceiptSharing;
    this.readReceiptShared      = details.readReceiptShared;
    this.typingIndicatorSharing = details.typingIndicatorSharing;
    this.typingIndicatorShared  = details.typingIndicatorShared;

    this.blockShared            = details.blockShared;
    this.contactSharing         = details.contactSharing;
    this.contactShared          = details.contactShared;

    this.locationSharing        = details.locationSharing;
    this.locationShared         = details.locationShared;
    this.locationInformation    = details.locationInformation;

    this.profileShared          = details.profileShared;
    //

    this.resolving    = false;
  }

  public boolean isLocalNumber() {
    return isLocalNumber;
  }

  public synchronized @Nullable Uri getContactUri() {
    return this.contactUri;
  }

  public void setContactUri(@Nullable Uri contactUri) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(contactUri, this.contactUri)) {
        this.contactUri = contactUri;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized @Nullable String getName() {
    if (this.name == null && isMmsGroupRecipient()) {
      List<String> names = new LinkedList<>();

      for (Recipient recipient : participants) {
        names.add(recipient.toShortString());
      }

      return Util.join(names, ", ");
    }

    return this.name;
  }

  public synchronized @Nullable String getDisplayName() {
    if (!TextUtils.isEmpty(this.nickname))  return this.nickname;
    if (!TextUtils.isEmpty(this.name))  return this.name;
    //todo: check if buddy before displaying telephone
    if (/*resolving && */address == null) {
      Log.e(TAG, String.format("getDisplayName {uid:'%d'}: address unavailable due to Recipient being in 'resolving' state", ufsrvId));
      return ufsrvId +"*";
    }

    return this.address.serialize();

  }

  public void setName(@Nullable String name) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.name, name)) {
        this.name = name;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized @NonNull MaterialColor getColor() {
    if      (isGroupRecipient()) return MaterialColor.GROUP;
    else if (color != null)      return color;
    else if (name != null)       return ContactColors.generateFor(name);
    else                         return ContactColors.UNKNOWN_COLOR;
  }

  public synchronized boolean isPrivateGroupForTwo ()
  {
    if (!isGroupRecipient()) return false;

    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance());
    if (!groupDatabase.isPrivateGroupForTwo(address.toGroupString())) return false;

    return true;
  }

  public void setColor(@NonNull MaterialColor color) {
    synchronized (this) {
      this.color = color;
    }

    notifyListeners();
  }

  public @NonNull Address getAddress() {
    return address;
  }

  //
  public String getNickname () {
    return this.nickname;
  }

  public long getUfsrvId () {
    return ufsrvId;
  }

  public void setUfsrvId (long uid) {
    synchronized (this) {
      this.ufsrvId = uid;
    }

    notifyListeners();
  }

  public void setUfsrvUId (String ufsrvUid, long ufsrvId) {
    synchronized (this) {
      this.ufsrvId  = ufsrvId;
      this.ufsrvUid =  ufsrvUid;
    }

    notifyListeners();
  }

  public String getUfsrvUid () {
    return ufsrvUid;
  }

  public byte[] getUfrsvUidRaw ()
  {
    if (!TextUtils.isEmpty(ufsrvUid)) {
      return UfsrvUid.DecodeUfsrvUid(ufsrvUid);
    } else {
      return null;
    }
  }

  public void setE164Number (String e164Number) {
    synchronized (this) {
      this.e164number = e164Number;
    }

    notifyListeners();
  }

  public String getE164number ()
  {
    return e164number;
  }

  public void setUsername (String username) {
    synchronized (this) {
      this.username = username;
    }

    notifyListeners();
  }

  public String getUsername ()
  {
    return username;
  }


  public long getEid () {
    return eid;
  }

  public void setEid(long uid) {
    synchronized (this) {
      this.eid = uid;
    }

    notifyListeners();
  }

  public boolean isPresenceSharing () {
    return presenceSharing;
  }

  public void setPresenceSharing(boolean value) {
    synchronized (this) {
      this.presenceSharing = value;
    }

    notifyListeners();
  }

  public boolean isPresenceShared () {
    return presenceShared;
  }

  public void setPresenceShared(boolean value) {
    synchronized (this) {
      this.presenceShared = value;
    }

    notifyListeners();
  }

  public void setPresenceInformation(String presenceInformation) {
    synchronized (this) {
      this.presenceInformation = presenceInformation;
    }

    notifyListeners();
  }

  public String getPresenceInformation() {
      return this.presenceInformation;
  }

  public boolean isReadReceiptSharing () {
    return readReceiptSharing;
  }

  public void setReadReceiptSharing(boolean value) {
    synchronized (this) {
      this.readReceiptSharing = value;
    }

    notifyListeners();
  }

  public boolean isReadReceiptShared () {
    return readReceiptShared;
  }

  public void setReadReceiptShared(boolean value) {
    synchronized (this) {
      this.readReceiptShared = value;
    }

    notifyListeners();
  }

  public boolean isTypingIndicatorSharing () {
    return typingIndicatorSharing;
  }

  public void setTypingIndicatorSharing(boolean value) {
    synchronized (this) {
      this.typingIndicatorSharing = value;
    }

    notifyListeners();
  }

  public boolean isTypingIndicatorShared () {
    return typingIndicatorShared;
  }

  public void setTypingIndicatorShared(boolean value) {
    synchronized (this) {
      this.typingIndicatorShared = value;
    }

    notifyListeners();
  }

  public boolean isBlockShared () {
    return blockShared;
  }

  public void setBlockShared(boolean value) {
    synchronized (this) {
      this.blockShared = value;
    }

    notifyListeners();
  }

  public boolean isContactSharing () {
    return contactSharing;
  }

  public void setContactSharing(boolean value) {
    synchronized (this) {
      this.contactSharing = value;
    }

    notifyListeners();
  }

  public boolean isContactShared () {
    return contactShared;
  }

  public void setContactShared(boolean value) {
    synchronized (this) {
      this.contactShared = value;
    }

    notifyListeners();
  }

  public boolean isLocationSharing () {
    return locationSharing;
  }

  public void setLocationSharing(boolean value) {
    synchronized (this) {
      this.locationSharing = value;
    }

    notifyListeners();
  }

  public boolean isLocationShared () {
    return locationShared;
  }

  public void setLocationShared(boolean value) {
    synchronized (this) {
      this.locationShared = value;
    }

    notifyListeners();
  }

  public void setLocationInformation(float longt, float lat) {
    synchronized (this) {
      this.locationInformation = String.format("%f,%f", longt, lat);
    }

    notifyListeners();
  }

  public String getLocationInformation() {
    return this.locationInformation;
  }
  //

  public long getRecipintId () {
    return getUfsrvId();
  }

  public void setNickname(@NonNull String nickname) {
    synchronized (this) {
      this.nickname = nickname;
    }

    notifyListeners();
  }

  public void setRecipientType(RecipientType recipientType) {
    synchronized (this) {
      this.recipientType = recipientType;
    }
  }

  public synchronized RecipientType getRecipientType () {
      return this.recipientType;
  }

  //

  public synchronized @Nullable String getCustomLabel() {
    return customLabel;
  }

  public void setCustomLabel(@Nullable String customLabel) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(customLabel, this.customLabel)) {
        this.customLabel = customLabel;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized Optional<Integer> getDefaultSubscriptionId() {
    return defaultSubscriptionId;
  }

  public void setDefaultSubscriptionId(Optional<Integer> defaultSubscriptionId) {
    synchronized (this) {
      this.defaultSubscriptionId = defaultSubscriptionId;
    }

    notifyListeners();
  }

  public synchronized @Nullable String getProfileName() {
    return profileName;
  }

  public void setProfileName(@Nullable String profileName) {
    synchronized (this) {
      this.profileName = profileName;
    }

    notifyListeners();
  }

  public synchronized @Nullable String getProfileAvatar() {
    return profileAvatar;
  }

  public void setProfileAvatar(@Nullable String profileAvatar) {
    synchronized (this) {
      this.profileAvatar = profileAvatar;
    }

    notifyListeners();
  }

  public synchronized boolean isProfileSharing() {
    return profileSharing;
  }

  public synchronized boolean isProfileShared() {
    return profileShared;
  }
  public void setProfileSharing(boolean value) {
    synchronized (this) {
      this.profileSharing = value;
    }

    notifyListeners();
  }

  public void setProfileShared(boolean value) {
    synchronized (this) {
      this.profileShared = value;
    }

    notifyListeners();
  }

  public boolean isGroupRecipient() {
    return address.isGroup();
  }

  public boolean isMmsGroupRecipient() {
    return address.isMmsGroup();
  }

  public boolean isPushGroupRecipient() {
    return address.isGroup() && !address.isMmsGroup();
  }

  public @NonNull synchronized List<Recipient> getParticipants() {
    return new LinkedList<>(participants);
  }

  public void setParticipants(@NonNull List<Recipient> participants) {
    synchronized (this) {
      this.participants.clear();
      this.participants.addAll(participants);
    }

    notifyListeners();
  }

  public synchronized void addListener(RecipientModifiedListener listener) {
    if (listeners.isEmpty()) {
      for (Recipient recipient : participants) recipient.addListener(this);
    }
    listeners.add(listener);
  }

  public synchronized void removeListener(RecipientModifiedListener listener) {
    listeners.remove(listener);

    if (listeners.isEmpty()) {
      for (Recipient recipient : participants) recipient.removeListener(this);
    }
  }

  public synchronized String toShortString() {
    return (getName() == null ? address.serialize() : getName());
  }

  //
  public synchronized SpannableString toShortStringStylised() {
    if (address.isGroup()) {
      Optional<Object> formattedTitle = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).isPrivateGroupForTwoWithStyliser(address.toGroupString(), PrivateGroupForTwoName::styliseGroupTitle);
      if (formattedTitle.isPresent()) {
        return (SpannableString)formattedTitle.get();
      }
    }

    return new SpannableString(toShortString());
  }

  public synchronized @NonNull Drawable getFallbackContactPhotoDrawable(Context context, boolean inverted) {
    return getFallbackContactPhoto().asDrawable(context, getColor().toAvatarColor(context), inverted);
  }

  public synchronized @NonNull FallbackContactPhoto getFallbackContactPhoto() {
    if      (isLocalNumber)            return new ResourceContactPhoto(R.drawable.ic_note_to_self);
    if      (isResolving())            return new TransparentContactPhoto();
    else if (isGroupRecipient())       return new ResourceContactPhoto(R.drawable.ic_group_white_24dp, R.drawable.ic_group_large);
    else if (!TextUtils.isEmpty(name)) return new GeneratedContactPhoto(name, R.drawable.ic_profile_default);
    else                               return new ResourceContactPhoto(R.drawable.ic_profile_default, R.drawable.ic_person_large);
  }

  public synchronized @Nullable ContactPhoto getContactPhoto() {
    if      (isLocalNumber)                               return null;
    // additional conditionals
    else if      (isGroupRecipient() &&  groupAvatarId!=null && UfsrvFenceUtils.isAvatarUfsrvIdLoaded(avatarUfsrvId)) return new GroupRecordContactPhoto(address, groupAvatarId, avatarUfsrvId);
//    else if (profileAvatar != null)                       return new ProfileContactPhoto(address, profileAvatar);//-
    else if (UfsrvFenceUtils.isAvatarUfsrvIdLoaded(avatarUfsrvId))                       return new ProfileContactPhoto(address, avatarUfsrvId);
    else if (systemContactPhoto != null)                  return new SystemContactPhoto(address, systemContactPhoto, 0);
//    else if (profileAvatar != null)                       return new ProfileContactPhoto(address, profileAvatar);//- changed order
    else                                                  return null;
  }

  public void setSystemContactPhoto(@Nullable Uri systemContactPhoto) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(systemContactPhoto, this.systemContactPhoto)) {
        this.systemContactPhoto = systemContactPhoto;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  //
  public void setGroupAvatarId(@Nullable Long groupAvatarId, @Nullable String avatarUfsrvId) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.groupAvatarId, groupAvatarId)) {
        this.groupAvatarId = groupAvatarId;
        notify = true;
      }

      if (!Util.equals(this.avatarUfsrvId, avatarUfsrvId)) {
        this.avatarUfsrvId = avatarUfsrvId;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public void setGroupAvatarUfsrvId(@NonNull String avatarUfsrvId) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.avatarUfsrvId, avatarUfsrvId)) {
        this.avatarUfsrvId = avatarUfsrvId;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public String getGroupAvatarUfsrvId ()
  {
    return this.avatarUfsrvId;
  }

  public boolean isUfsrvResolving () {
    return this.isUfsrvResolving;
  }
  //

  public synchronized @Nullable Uri getMessageRingtone() {
    if (messageRingtone != null && messageRingtone.getScheme() != null && messageRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return messageRingtone;
  }

  public void setMessageRingtone(@Nullable Uri ringtone) {
    synchronized (this) {
      this.messageRingtone = ringtone;
    }

    notifyListeners();
  }

  public synchronized @Nullable Uri getCallRingtone() {
    if (callRingtone != null && callRingtone.getScheme() != null && callRingtone.getScheme().startsWith("file")) {
      return null;
    }

    return callRingtone;
  }

  public void setCallRingtone(@Nullable Uri ringtone) {
    synchronized (this) {
      this.callRingtone = ringtone;
    }

    notifyListeners();
  }

  public synchronized boolean isMuted() {
    return System.currentTimeMillis() <= mutedUntil;
  }

  public void setMuted(long mutedUntil) {
    synchronized (this) {
      this.mutedUntil = mutedUntil;
    }

    notifyListeners();
  }

  public synchronized boolean isBlocked() {
    return blocked;
  }

  public void setBlocked(boolean blocked) {
    synchronized (this) {
      this.blocked = blocked;
    }

    notifyListeners();
  }

  public synchronized VibrateState getMessageVibrate() {
    return messageVibrate;
  }

  public void setMessageVibrate(VibrateState vibrate) {
    synchronized (this) {
      this.messageVibrate = vibrate;
    }

    notifyListeners();
  }

  public synchronized  VibrateState getCallVibrate() {
    return callVibrate;
  }

  public void setCallVibrate(VibrateState vibrate) {
    synchronized (this) {
      this.callVibrate = vibrate;
    }

    notifyListeners();
  }

  public synchronized int getExpireMessages() {
    return expireMessages;
  }

  public void setExpireMessages(int expireMessages) {
    synchronized (this) {
      this.expireMessages = expireMessages;
    }

    notifyListeners();
  }

  public synchronized boolean hasSeenInviteReminder() {
    return seenInviteReminder;
  }

  public void setHasSeenInviteReminder(boolean value) {
    synchronized (this) {
      this.seenInviteReminder = value;
    }

    notifyListeners();
  }

  public synchronized RegisteredState getRegistered() {
    if      (isPushGroupRecipient() || recipientType==RecipientType.GROUP) return RegisteredState.REGISTERED;// second ||
    else if (isMmsGroupRecipient())  return RegisteredState.NOT_REGISTERED;

    return registered;
  }

  public void setRegistered(@NonNull RegisteredState value) {
    boolean notify = false;

    synchronized (this) {
      if (this.registered != value) {
        this.registered = value;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public synchronized @Nullable String getNotificationChannel() {
    return !NotificationChannels.supported() ? null : notificationChannel;
  }

  public void setNotificationChannel(@Nullable String value) {
    boolean notify = false;

    synchronized (this) {
      if (!Util.equals(this.notificationChannel, value)) {
        this.notificationChannel = value;
        notify = true;
      }
    }

    if (notify) notifyListeners();
  }

  public boolean isForceSmsSelection() {
    return forceSmsSelection;
  }

  public void setForceSmsSelection(boolean value) {
    synchronized (this) {
      this.forceSmsSelection = value;
    }

    notifyListeners();
  }

  public synchronized @Nullable byte[] getProfileKey() {
    return profileKey;
  }

  public void setProfileKey(@Nullable byte[] profileKey) {
    synchronized (this) {
      this.profileKey = profileKey;
    }

    notifyListeners();
  }

  public synchronized UnidentifiedAccessMode getUnidentifiedAccessMode() {
    return unidentifiedAccessMode;
  }

  public void setUnidentifiedAccessMode(@NonNull UnidentifiedAccessMode unidentifiedAccessMode) {
    synchronized (this) {
      this.unidentifiedAccessMode = unidentifiedAccessMode;
    }

    notifyListeners();
  }

  public synchronized boolean isSystemContact() {
    return contactUri != null;
  }

  public synchronized Recipient resolve() {
    while (resolving) Util.wait(this, 0);
    return this;
  }

  //
  public synchronized GeogroupStickyState getGeogroupSticky() {
    return geogroupSticky;
  }

  public void setGeogroupSticky(GeogroupStickyState sticky) {
    synchronized (this) {
      this.geogroupSticky = sticky;
    }

    notifyListeners();
  }
//  //

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || !(o instanceof Recipient)) return false;

    Recipient that = (Recipient) o;

    return this.address.equals(that.address);
  }

  @Override
  public int hashCode() {
    return this.address.hashCode();
  }

  private void notifyListeners() {
    Set<RecipientModifiedListener> localListeners;

    synchronized (this) {
      localListeners = new HashSet<>(listeners);
    }

    for (RecipientModifiedListener listener : localListeners)
      listener.onModified(this);
  }

  @Override
  public void onModified(Recipient recipient) {
    notifyListeners();
  }

  public synchronized boolean isResolving() {
    return resolving;
  }
}
