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

import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.logging.Log;

import com.annimon.stream.function.Consumer;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.data.json.JsonEntityBaseUserPref;
import com.unfacd.android.data.json.JsonEntitySharedList;
import com.unfacd.android.data.json.JsonEntityUserPrefAvatar;
import com.unfacd.android.data.json.JsonEntityUserPrefBlockShare;
import com.unfacd.android.data.json.JsonEntityUserPrefContactShare;
import com.unfacd.android.data.json.JsonEntityUserPrefE164Number;
import com.unfacd.android.data.json.JsonEntityUserPrefGeoGroupRoaming;
import com.unfacd.android.data.json.JsonEntityUserPrefNetStateShare;
import com.unfacd.android.data.json.JsonEntityUserPrefProfileShare;
import com.unfacd.android.data.json.JsonEntityUserPrefReadReceiptShare;
import com.unfacd.android.data.json.JsonEntityUserPrefActivityStateShare;
import com.unfacd.android.ufsrvcmd.events.AppEventPrefUserAvatar;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class UserPrefsUtils
{
  private static final String TAG = UserPrefsUtils.class.getSimpleName();

  //should align with ufsrv ids
  public enum RoamingMode {
    RM_UNSET(0),
    RM_WANDERER(1),//current default
    RM_CONQUERER(2),
    RM_JOURNALER(3);

    private int value;

    RoamingMode(int value) {
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

    public int getValue() {
      return value;
    }
  }

  //check UserPrefsDeserializer
  static public void synchroniseUserPreferences (List<JsonEntityBaseUserPref> prefs)
  {
    Context context                       = ApplicationContext.getInstance();
    boolean isProfileSharingSeen          = false;
    boolean isPresenceSharingSeen         = false;
    boolean isLocationSharingSeen         = false;
    boolean isReadReceiptSharingSeen      = false;
    boolean isBlockSharingSeen            = false;
    boolean isContactSharingSeen          = false;
    boolean isActivityStateSharingSeen    = false;

    if (prefs == null) prefs = new LinkedList<>();
    for (JsonEntityBaseUserPref pref : prefs) {
      if (pref == null) {
        Log.e(TAG, String.format("synchroniseUserPreferences (prefs_sz:'%d': ERROR null pref found: skipping...", prefs.size()));
        continue;
      }

      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefGeoGroupRoaming.class)) {
        RoamingMode roamingMode =  RoamingMode.values()[((JsonEntityUserPrefGeoGroupRoaming)pref).getRoamingMode()];
        setRoamingMode(ApplicationContext.getInstance(), roamingMode);
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefAvatar.class)) {
        Recipient thisUser = Recipient.from(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), false);
        String avatar = ((JsonEntityUserPrefAvatar)pref).getValue();
        String storedAvatar = thisUser.getGroupAvatarUfsrvId();
        if (!TextUtils.isEmpty(storedAvatar) && !storedAvatar.equals(avatar)) {
          DatabaseFactory.getRecipientDatabase(ApplicationContext.getInstance()).setAvatarUfId(thisUser, avatar);
          ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventPrefUserAvatar(TextSecurePreferences.getUserId(context), avatar));
        }
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefE164Number.class)) {
        Recipient thisUser = Recipient.from(context, Address.fromSerialized(TextSecurePreferences.getUfsrvUserId(context)), false);
        String e164Numebr = ((JsonEntityUserPrefE164Number)pref).getValue();
        String e164NumberThisUser = thisUser.getE164number();
        if (!TextUtils.isEmpty(e164NumberThisUser) && !e164NumberThisUser.equals(e164Numebr)) {
          DatabaseFactory.getRecipientDatabase(context).setE164Number(thisUser, e164Numebr);
        }
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefProfileShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefProfileShare)pref).getValue(),
                       DatabaseFactory.getRecipientDatabase(context).GetAllProfileSharing(Recipient.RecipientType.USER),
                       (p) -> DatabaseFactory.getRecipientDatabase(context).setProfileSharing((Recipient)p.first(), (Boolean)p.second()));
        isProfileSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefNetStateShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefNetStateShare)pref).getValue(),
                       DatabaseFactory.getRecipientDatabase(context).GetAllPresenceSharing(Recipient.RecipientType.USER),
                       (p) -> DatabaseFactory.getRecipientDatabase(context).setPresenceSharing((Recipient)p.first(), (Boolean)p.second()));
        isPresenceSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefReadReceiptShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefReadReceiptShare)pref).getValue(),
                       DatabaseFactory.getRecipientDatabase(context).GetAllReadReceiptSharing(Recipient.RecipientType.USER),
                       (p) -> DatabaseFactory.getRecipientDatabase(context).setReadReceiptSharing((Recipient)p.first(), (Boolean)p.second()));
        isReadReceiptSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefContactShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefContactShare)pref).getValue(),
                       DatabaseFactory.getRecipientDatabase(context).GetShareListFor(RecipientDatabase.SHARE_CONTACT, Recipient.RecipientType.USER),
                       (p) -> DatabaseFactory.getRecipientDatabase(context).setContactSharing((Recipient)p.first(), (Boolean)p.second()));
        isContactSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefBlockShare.class)) {
        setSharingList(context,
                       ((JsonEntityUserPrefBlockShare)pref).getValue(),
                       DatabaseFactory.getRecipientDatabase(context).GetShareListFor(RecipientDatabase.BLOCK, Recipient.RecipientType.USER),
                       (p) -> DatabaseFactory.getRecipientDatabase(context).setBlockSharing((Recipient)p.first(), (Boolean)p.second()));
        isBlockSharingSeen = true;
        continue;
      }
      if (pref.getClass().isAssignableFrom(JsonEntityUserPrefActivityStateShare.class)) {
//        setSharingList (context,
//                    ((JsonEntityUserPrefTypingIndicatorShare)pref).getValue(),
//                    DatabaseFactory.getRecipientDatabase(context).GetAllTypingIndicatorSharing(Recipient.RecipientType.USER),
//                    (p) -> DatabaseFactory.getRecipientDatabase(context).setTypingIndicatorSharing((Recipient)p.first(), (Boolean)p.second()));
        isActivityStateSharingSeen = true;
        continue;
      }
    }

    if (!isProfileSharingSeen) {
      int usersCleared=DatabaseFactory.getRecipientDatabase(context).ClearAllProfileSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from ProfileSharing list", usersCleared));
    }

    if (!isPresenceSharingSeen) {
      int usersCleared=DatabaseFactory.getRecipientDatabase(context).ClearAllPresenceSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from PresenceSharing list", usersCleared));
    }

    if (!isLocationSharingSeen) {

    }

    if (!isReadReceiptSharingSeen) {
      int usersCleared=DatabaseFactory.getRecipientDatabase(context).ClearAllReadReceiptSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from ReadReceiptSharing list", usersCleared));
    }
    if (!isBlockSharingSeen) {
      int usersCleared=DatabaseFactory.getRecipientDatabase(context).ClearAllBlockSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from BlockSharing list", usersCleared));
    }

    if (!isContactSharingSeen) {
      int usersCleared=DatabaseFactory.getRecipientDatabase(context).ClearAllContactSharing(context, Recipient.RecipientType.USER);
      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from ContactSharing list", usersCleared));
    }

    if (!isActivityStateSharingSeen) {
//      int usersCleared=DatabaseFactory.getRecipientDatabase(ApplicationContext.getInstance()).ClearAllTypingIndicatorSharing(ApplicationContext.getInstance(), Recipient.RecipientType.USER);
//      Log.d(TAG, String.format("synchroniseUserPreferences (users:'%d': Cleared all users from TypingIndicatorSharing list", usersCleared));
    }
  }

  //shared user lists
  static public void synchroniseSharedLists (Optional<List<JsonEntitySharedList>> sharedLists)
  {
    Context context = ApplicationContext.getInstance();

    if (sharedLists.isPresent()) {
      DatabaseFactory.getRecipientDatabase(context).ClearAllProfileShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllPresenceShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllReadReceiptShared(context, Recipient.RecipientType.USER);
//      DatabaseFactory.getRecipientDatabase(context).ClearAllTypingIndicatorShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllBlockShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllContactShared(context, Recipient.RecipientType.USER);

      for (JsonEntitySharedList sharedList : sharedLists.get()) {
        ShareListType shareListType = ShareListType.values()[sharedList.getType()];
        Recipient recipient = Recipient.from(context, Address.fromSerialized(sharedList.getUfsrvuid()), false);
        switch (shareListType) {
          case PROFILE:
            DatabaseFactory.getRecipientDatabase(context).setProfileShared(recipient, true);
            break;
          case NETSTATE:
            DatabaseFactory.getRecipientDatabase(context).setPresenceShared(recipient, true);
            break;
          case READ_RECEIPT:
            DatabaseFactory.getRecipientDatabase(context).setReadReceiptShared(recipient, true);
            break;
          case CONTACT:
            DatabaseFactory.getRecipientDatabase(context).setContactShared(recipient, true);
            break;
          case BLOCKED:
            DatabaseFactory.getRecipientDatabase(context).setBlockShared(recipient, true);
            break;
          case TYPING_INDICATOR:
//            DatabaseFactory.getRecipientDatabase(context).setTypingIndicatorShared(recipient, true);
          case LOCATION:
            break;
        }
      }

    } else {
      Log.w(TAG, String.format("synchroniseSharedLists: Clearing ALL SHARED lists"));
      DatabaseFactory.getRecipientDatabase(context).ClearAllProfileShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllPresenceShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllReadReceiptShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllContactShared(context, Recipient.RecipientType.USER);
      DatabaseFactory.getRecipientDatabase(context).ClearAllBlockShared(context, Recipient.RecipientType.USER);
    }

  }

  /**
   *
   * @param roamingMode if zero indicates roaming mode is disabled
   */
  public static void setRoamingMode (Context context, RoamingMode roamingMode) {
    TextSecurePreferences.setUfsrvGeoGroupRoamingMode(context, roamingMode.value);
  }

  /**
   * This is the list of userids this client is sharing our profile with (eg allowed to see avatar). This
   * is different from the list of userids who allowed their profiles to be shared with this client (shared)
   * @param context
   */
  private static void setSharingList (Context context, List<String> UfsrvUidsEncodedProvided, HashMap<Long, String> UfsrvUidsExisting, Consumer<Pair> consumer) {
    HashSet<Long>         ufsrvUidProvidedSet             = new HashSet(UfsrvUidsEncodedProvided);

    //iterate over existing profiles and remove ones not on the provided list
    for (Map.Entry<Long, String> entry : UfsrvUidsExisting.entrySet()) {
      if (entry.getKey().longValue() == TextSecurePreferences.getUserId(context)) continue;
      if (!ufsrvUidProvidedSet.contains(entry.getValue())) {
        Recipient recipient = Recipient.from(context, Address.fromSerialized(entry.getValue()), false);
        consumer.accept(new Pair(recipient, Boolean.FALSE));
      }
    }

    //iterate over provided ufsrvuids and add ones not on the existing list
    for (String ufsrvUidEncodedProvided : UfsrvUidsEncodedProvided) {
      if (!UfsrvUidsExisting.containsValue(ufsrvUidEncodedProvided)) {
        Recipient recipient = Recipient.from(context, Address.fromSerialized(ufsrvUidEncodedProvided), false);
        consumer.accept(new Pair(recipient, Boolean.TRUE));
      }
    }
  }
}