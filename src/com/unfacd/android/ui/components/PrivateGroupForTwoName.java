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

package com.unfacd.android.ui.components;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ImageSpan;

import com.mikepenz.community_material_typeface_library.CommunityMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

import java.util.LinkedList;
import java.util.List;


public class PrivateGroupForTwoName
{
  final private Recipient   groupRecipient;
        private Recipient   otherMember = null;
  final private Context     context;

  final static private String TAG  = PrivateGroupForTwoName.class.getSimpleName();

  public PrivateGroupForTwoName (Context context, Recipient groupRecipient)
  {
    this.context         = context;
    this.groupRecipient = groupRecipient;
  }

  public Recipient getOtherMember ()
  {
    return otherMember;
  }

  public Recipient getGroupRecipient ()
  {
    return groupRecipient;
  }

  public Optional<SpannableString> getStylisedName ()
  {
    if (GroupUtil.isEncodedGroup(groupRecipient.getAddress().serialize())) {
      GroupDatabase.GroupRecord groupRecord;
      String        groupNumberEncoded  = groupRecipient.getAddress().serialize();
      GroupDatabase groupDatabase       = DatabaseFactory.getGroupDatabase(context);

      groupRecord = groupDatabase.getGroupByGroupId(groupNumberEncoded);
      if (groupRecord!=null &&
              groupRecord.getPrivacyMode()==FenceRecord.PrivacyMode.PRIVATE_VALUE &&
              isPrivateGroupForTwo(groupRecord.getMembers(), groupRecord.getMembersInvited())) {
        List<Address> groupMembersFinal  = groupRecord.getMembers();
        if (groupMembersFinal.size() == 1)  groupMembersFinal.add(groupRecord.getMembersInvited().get(0));//user has not accepted invitation yet

        List<Recipient> groupMembersRecipients = new LinkedList<>();
        for (Address groupMemberAddress: groupMembersFinal) {
          groupMembersRecipients.add(Recipient.from(context, groupMemberAddress, true));
        }
        return Optional.of(buildPrivateGroupName(TextSecurePreferences.getUfsrvUserId(context), groupMembersRecipients, groupNumberEncoded));

      }
    }

    return Optional.absent();
  }

  private boolean isPrivateGroupForTwo (List<Address> members, List<Address>membersInvited)
  {
    return (members.size() == 2 || (members.size() == 1 && membersInvited.size() == 1));
  }

  private SpannableString buildPrivateGroupName (String ufsrvUid, List<Recipient> members, String groupNamefallback)
  {
    Pair <SpannableString, Recipient>formattedGroupNamePair = formatPrivateGroupName(ufsrvUid, members, groupNamefallback);
    if (formattedGroupNamePair != null) {
      if (formattedGroupNamePair.second() != null)
        this.otherMember = formattedGroupNamePair.second();

      return formattedGroupNamePair.first();
    }

    return new SpannableString("PrivateGroup_" + groupNamefallback);
  }

  static private Pair<SpannableString, Recipient> formatPrivateGroupName (String ufsrvUid, List<Recipient> members, String groupNamefallback)
  {
    Context thisContext = ApplicationContext.getInstance();

    Recipient otherMember = null;
    for (Recipient member: members) {
      String memberAddress = member.getAddress().serialize();
      if (!memberAddress.equals(ufsrvUid)) {
        otherMember = member;
        break;
      }
    }

    if (otherMember != null) {
      Drawable groupNamesConnectorIcon = new IconicsDrawable(thisContext)
              .icon(CommunityMaterial.Icon.cmd_swap_horizontal)
              .color(thisContext.getResources().getColor(R.color.gray27))
              .sizeDp(16);

      String otherMemberDesignator=!TextUtils.isEmpty(otherMember.getNickname())
                                   ?otherMember.getNickname()
                                   :!TextUtils.isEmpty(otherMember.getName())
                                    ?otherMember.getName()
                                    :" ..."; //this is most likely when async fetching

      String src = String.format("%s + %s", otherMemberDesignator, thisContext.getString(R.string.you));

      int index = src.indexOf("+");
      SpannableString iconifiedString = new SpannableString(src);
      iconifiedString.setSpan(new ImageSpan(groupNamesConnectorIcon), index, index + 1, ImageSpan.ALIGN_BASELINE);

      return new Pair<>(iconifiedString, otherMember);
    }

    return new Pair<>(new SpannableString("PrivateGroup_" + groupNamefallback), null);
  }

  static public SpannableString styliseGroupTitle (String groupId, List<String> members, List<String> membersInvited)
  {
    if (members.size() == 1 && membersInvited.size() > 0)  members.add(membersInvited.get(0));//user has not accepted invitation yet, or they may have uninvited themselves

    List<Recipient> groupMembersRecipients = new LinkedList<>();
    for (String memberUfsrvUid: members) {
      groupMembersRecipients.add(Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(memberUfsrvUid), false));
    }
    Pair<SpannableString, Recipient> formattedTitle = formatPrivateGroupName(TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance()), groupMembersRecipients, groupId);

    return formattedTitle.first();
  }

  @FunctionalInterface
  public interface PrivateGroupForTwoTitleStyliser
  {
    SpannableString formatTitle (String groupId, List<String> members, List<String> membersInvited);
  }
}
