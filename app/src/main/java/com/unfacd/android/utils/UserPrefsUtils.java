/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.utils;

import android.content.Context;
import android.text.TextUtils;

import com.annimon.stream.function.Consumer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.data.json.JsonEntityBaseUserPref;
import com.unfacd.android.data.json.JsonEntitySharedList;
import com.unfacd.android.data.json.JsonEntityUserPrefActivityStateShare;
import com.unfacd.android.data.json.JsonEntityUserPrefAvatar;
import com.unfacd.android.data.json.JsonEntityUserPrefBaselocAnchorZone;
import com.unfacd.android.data.json.JsonEntityUserPrefBlockShare;
import com.unfacd.android.data.json.JsonEntityUserPrefBlockedFenceShare;
import com.unfacd.android.data.json.JsonEntityUserPrefContactShare;
import com.unfacd.android.data.json.JsonEntityUserPrefGeoGroupRoaming;
import com.unfacd.android.data.json.JsonEntityUserPrefGeolocTrigger;
import com.unfacd.android.data.json.JsonEntityUserPrefHomebaseLoc;
import com.unfacd.android.data.json.JsonEntityUserPrefNetStateShare;
import com.unfacd.android.data.json.JsonEntityUserPrefProfileShare;
import com.unfacd.android.data.json.JsonEntityUserPrefReadReceiptShare;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import androidx.annotation.NonNull;

public class UserPrefsUtils
{
  private static final String TAG = Log.tag(UserPrefsUtils.class);

  //should align with ufsrv ids
  public enum RoamingMode {
    RM_UNSET(0),
    RM_WANDERER(1),//current default
    RM_CONQUEROR(2),
    RM_JOURNALER(3);

    private int value;

    RoamingMode(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  //ordinals should align with proto enum GeoLocRoamingTrigger
  public enum GeolocRoamingTrigger
  {
    UNDEFINED(0),
    NEIGHBOURHOOD(1),//current default
    MAJOR_REGIONS(2),
    COUNTRIES(3);



    private int value;

    GeolocRoamingTrigger (int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public String getDescriptiveName() {
      switch (this) {
        case UNDEFINED:
          return "Unset";
        case COUNTRIES:
          return "Countries";
        case MAJOR_REGIONS:
          return "Major regions";
        case NEIGHBOURHOOD:
          return "Neighbourhood";
        default:
          throw new AssertionError("Unknown type " + this);
      }
    }
  }

  //ordinals should align with proto enum BaseLocAnchorZones
  public enum BaselocAnchorZone
  {
    UNDEFINED(0),
    NEIGHBOURHOOD(1),//current default
    REGIONHOOD(2),
    COUNTRYHOOD(3),
    SELFZONE_EXCLUSIVE(4),
    SELFZONE_GEOLOC(5),
    GEOLOC_ROAMING(6);

    private int value;

    BaselocAnchorZone (int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  //align with ufsrv's "enum EnumShareListType"
  public enum ShareListType {
    PROFILE(0),
    LOCATION(1),
    CONTACT(2),
    NETSTATE(3),
    FRIENDS(4),
    BLOCKED(5),
    READ_RECEIPT(6),
    TYPING_INDICATOR(7);

    private int value;

    ShareListType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public enum UnsolicitedContactAction {
    BLOCK(0),
    ALLOW(1);

    private int value;

    UnsolicitedContactAction(int value) {
      this.value = value;
    }

    public Boolean asBoolean()
    {
      switch (this) {
        case ALLOW: return false;
        default: return true;
      }
    }

    public int getValue() {
      return value;
    }
  }

  /**
   * This works with json stream, comprising single type of pref, along with assigned values. The assigned values
   * are user_id->assigned value. Also see {@link com.unfacd.android.data.json.UserPrefsDeserializer#deserialize(JsonParser, DeserializationContext)}
   * @<code>{ \"id\": 69, \"value\": [ 305 ] }</code>
   *
   * @param prefs list of deserialised json entities representing user prefs
   */
  static public void synchroniseUserPreferences(List<JsonEntityBaseUserPref> prefs)
  {
    Context context                       = ApplicationContext.getInstance();
    RecipientDatabase database            = SignalDatabase.recipients();
    boolean isProfileSharingSeen          = false;
    boolean isPresenceSharingSeen         = false;
    boolean isLocationSharingSeen         = false;
    boolean isReadReceiptSharingSeen      = false;
    boolean isBlockSharingSeen            = false;
    boolean isContactSharingSeen          = false;
    boolean isActivityStateSharingSeen    = false;
    boolean isBlockedFenceSharingSeen     = false;

    if (prefs == null) prefs = new LinkedList<>();
    for (JsonEntityBaseUserPref pref : prefs) {
      if (pref == null) {
        Log.e(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (prefs_sz:'%d': ERROR null pref found (json field missing a processing logic): skipping...", prefs.size()));
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefGeoGroupRoaming.class)) {
        RoamingMode roamingMode =  RoamingMode.values()[((JsonEntityUserPrefGeoGroupRoaming)pref).getRoamingMode()];
        setRoamingMode(ApplicationContext.getInstance(), roamingMode);
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefGeolocTrigger.class)) {
        GeolocRoamingTrigger geolocRoamingTrigger = GeolocRoamingTrigger.values()[((JsonEntityUserPrefGeolocTrigger)pref).getGeolocTrigger()];
        setGeolocTrigger(geolocRoamingTrigger);
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefBaselocAnchorZone.class)) {
        BaselocAnchorZone baselocAnchorZone = BaselocAnchorZone.values()[((JsonEntityUserPrefBaselocAnchorZone)pref).getBaselocAnchorZone()];
        setBaselocZone(baselocAnchorZone);
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefHomebaseLoc.class)) {
        setHomebaseLocFromPacked(((JsonEntityUserPrefHomebaseLoc)pref).getHomebaseLoc());
        continue;
      }
      
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefAvatar.class)) {
        Recipient thisUser = Recipient.self();
        String avatar = ((JsonEntityUserPrefAvatar)pref).getValue();
        String storedAvatar = thisUser.getAvatarUfsrvId();
        if (!TextUtils.isEmpty(storedAvatar) && !storedAvatar.equals(avatar)) {
          database.setAvatarUfId(thisUser, avatar);
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), avatar));
        }
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefProfileShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefProfileShare)pref).getValue(),
                       database.GetAllProfileSharing(Recipient.RecipientType.USER),
                       (p) -> database.setProfileSharing((Recipient)p.first(), (Boolean)p.second()));
        isProfileSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefNetStateShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefNetStateShare)pref).getValue(),
                       database.GetAllPresenceSharing(Recipient.RecipientType.USER),
                       (p) -> database.setPresenceSharing((Recipient)p.first(), (Boolean)p.second()));
        isPresenceSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefReadReceiptShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefReadReceiptShare)pref).getValue(),
                       database.GetAllReadReceiptSharing(Recipient.RecipientType.USER),
                       (p) -> database.setReadReceiptSharing((Recipient)p.first(), (Boolean)p.second()));
        isReadReceiptSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefContactShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefContactShare)pref).getValue(),
                       database.GetShareListFor(RecipientDatabase.SHARE_CONTACT, Recipient.RecipientType.USER),
                       (p) -> database.setContactSharing((Recipient)p.first(), (Boolean)p.second()));
        isContactSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefBlockShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefBlockShare)pref).getValue(),
                       database.GetShareListFor(RecipientDatabase.BLOCKED, Recipient.RecipientType.USER),
                       (p) -> database.setBlockSharing((Recipient)p.first(), (Boolean)p.second()));
        isBlockSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefActivityStateShare.class)) {
//        setSharingList (context,
//                    ((JsonEntityUserPrefTypingIndicatorShare)pref).getValue(),
//                    database.GetAllTypingIndicatorSharing(Recipient.RecipientType.USER),
//                    (p) -> database.setTypingIndicatorSharing((Recipient)p.first(), (Boolean)p.second()));
        isActivityStateSharingSeen = true;
        continue;
      }

      //this is only done for self
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefBlockedFenceShare.class)) {
        database.clearAllBlockedFences(Recipient.self().getId());
        database.updateBlockedFences(Recipient.self().getId(), ((JsonEntityUserPrefBlockedFenceShare)pref).getValue());
        isBlockedFenceSharingSeen = true;
        continue;
      }
    }

    if (!isProfileSharingSeen) {
      int usersCleared = database.ClearAllProfileSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from ProfileSharing list", usersCleared));
    }

    if (!isPresenceSharingSeen) {
      int usersCleared = database.ClearAllPresenceSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from PresenceSharing list", usersCleared));
    }

    if (!isLocationSharingSeen) {

    }

    if (!isReadReceiptSharingSeen) {
      int usersCleared = database.ClearAllReadReceiptSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from ReadReceiptSharing list", usersCleared));
    }
    if (!isBlockSharingSeen) {
      int usersCleared = database.ClearAllBlockSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from BlockSharing list", usersCleared));
    }

    if (!isContactSharingSeen) {
      int usersCleared = database.ClearAllContactSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from ContactSharing list", usersCleared));
    }

    if (!isActivityStateSharingSeen) {
//      int usersCleared = database.ClearAllTypingIndicatorSharing(ApplicationContext.getInstance(), Recipient.RecipientType.USER);
//      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences (users:'%d': Cleared all users from TypingIndicatorSharing list", usersCleared));
    }

    if (!isBlockedFenceSharingSeen) {
      database.clearAllBlockedFences(Recipient.self().getId());
      Log.d(TAG, String.format(Locale.getDefault(), "synchroniseUserPreferences: Cleared all fences from blocked fences list"));
    }
  }

  //shared user lists
  static public void synchroniseSharedLists(Optional<List<JsonEntitySharedList>> sharedLists)
  {
    Context                           context = ApplicationDependencies.getApplication();
    RecipientDatabase                 database = SignalDatabase.recipients();
    LinkedList<Pair<Recipient, Long>> profileSharedList = new LinkedList<>();

    if (sharedLists.isPresent()) {
      database.ClearAllProfileShared(context, Recipient.RecipientType.USER);
      database.ClearAllPresenceShared(context, Recipient.RecipientType.USER);
      database.ClearAllReadReceiptShared(context, Recipient.RecipientType.USER);
//    databaset.ClearAllTypingIndicatorShared(context, Recipient.RecipientType.USER);
      database.ClearAllBlockShared(context, Recipient.RecipientType.USER);
      database.ClearAllContactShared(context, Recipient.RecipientType.USER);

      for (JsonEntitySharedList sharedList : sharedLists.get()) {
        ShareListType shareListType = ShareListType.values()[sharedList.getType()];
//        Recipient recipient = Recipient.from(context, Address.fromSerialized(sharedList.getUfsrvuid()), false);
        Recipient recipient = Recipient.live(sharedList.getUfsrvuid()).get();
        switch (shareListType) {
          case PROFILE:
            database.setProfileShared(recipient, true);
            profileSharedList.add(new Pair(recipient, sharedList.getEid()));
            break;
          case NETSTATE:
            database.setPresenceShared(recipient, true);
            break;
          case READ_RECEIPT:
            database.setReadReceiptShared(recipient, true);
            break;
          case CONTACT:
            database.setContactShared(recipient, true);
            break;
          case BLOCKED:
            database.setBlockShared(recipient, true);
            break;
          case TYPING_INDICATOR:
//            database.setTypingIndicatorShared(recipient, true);
          case LOCATION:
            break;
        }
      }

    } else {
      Log.w(TAG, String.format(Locale.getDefault(), "synchroniseSharedLists: Clearing ALL SHARED lists"));
      database.ClearAllProfileShared(context, Recipient.RecipientType.USER);
      database.ClearAllPresenceShared(context, Recipient.RecipientType.USER);
      database.ClearAllReadReceiptShared(context, Recipient.RecipientType.USER);
      database.ClearAllContactShared(context, Recipient.RecipientType.USER);
      database.ClearAllBlockShared(context, Recipient.RecipientType.USER);
    }

    if (!profileSharedList.isEmpty()) {
      for (Pair<Recipient, Long> profileShared : profileSharedList ) {
        long eidProvided = profileShared.second();
        if (eidProvided > 0 && eidProvided != profileShared.first().getEid()) {
          Log.d(TAG, String.format(Locale.getDefault(), "synchroniseSharedLists (eid: '%d', eid_provided: '%d'): Updating profile for: '%s' ", profileShared.first().getEid(), eidProvided, profileShared.first().getUfsrvUid()));
          ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(profileShared.first(), false, false));
        }
      }

      profileSharedList.clear();
    }
  }

  /**
   *
   * @param roamingMode if zero indicates roaming mode is disabled
   */
  public static void setRoamingMode(Context context, RoamingMode roamingMode) {
    SignalStore.settings().setRoamingMode(roamingMode);
  }

  public static void setGeolocTrigger(GeolocRoamingTrigger geolocTrigger) {
    SignalStore.settings().setGeolocRoamingTrigger(geolocTrigger);
  }

  public static void setBaselocZone(BaselocAnchorZone baselocZone) {
    SignalStore.settings().setBaselocZone(baselocZone);
  }

  //comes from backend as "lat,long:country:region:area:0:"
  public static void setHomebaseLocFromPacked(@NonNull String homebaseLocPacked) {
    if (!TextUtils.isEmpty(homebaseLocPacked) && !homebaseLocPacked.contains("*")) {
      int idx = homebaseLocPacked.indexOf(":");
      SignalStore.settings().setHomebaseLocLatLong(homebaseLocPacked.substring(0, idx));
      SignalStore.settings().setHomebaseLocAddress(homebaseLocPacked.substring(idx + 1));
    } else {
      SignalStore.settings().setHomebaseLocLatLong("");
      SignalStore.settings().setHomebaseLocAddress("");
    }
  }

  /**
   * This is the list of userids this client is sharing their profile with (eg allowed to see avatar). This
   * is different from the list of userids who allowed their profiles to be shared with this client (shared)
   * @param UfsrvUidsEncodedProvided collection of sequence ids representing users
   * @param UfsrvUidsExisting {@link HashMap} for mapping sequence uids (key) against {@link com.unfacd.android.ufsrvuid.UfsrvUid}
   * @param consumer consumer accepting a {@link Pair} with {@link Pair#first()} representing {@link Recipient} and
   *                 {@link Pair#second()} of type {@link Boolean} representing value of pref. The consumer will be called if a
   *                 pref entry is to be added or removed.
   *
   */
  private static void setSharingList(Context context,
                                     List<String> UfsrvUidsEncodedProvided,
                                     HashMap<Long, String> UfsrvUidsExisting,
                                     Consumer<Pair> consumer)
  {
    HashSet<Long>         ufsrvUidProvidedSet             = new HashSet(UfsrvUidsEncodedProvided);

    //iterate over existing profiles and remove ones not on the provided list
    for (Map.Entry<Long, String> entry : UfsrvUidsExisting.entrySet()) {
      if (entry.getKey().longValue() == TextSecurePreferences.getUserId(context)) continue;
      if (!ufsrvUidProvidedSet.contains(entry.getValue())) {
        Recipient recipient = Recipient.live(entry.getValue()).get();
        consumer.accept(new Pair(recipient, Boolean.FALSE));
      }
    }

    //iterate over provided ufsrvuids and add ones not on the existing list
    for (String ufsrvUidEncodedProvided : UfsrvUidsEncodedProvided) {
      if (!UfsrvUidsExisting.containsValue(ufsrvUidEncodedProvided)) {
        Recipient recipient = Recipient.live(ufsrvUidEncodedProvided).get();
        consumer.accept(new Pair(recipient, Boolean.TRUE));
      }
    }
  }
}