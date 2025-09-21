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

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.signal.libsignal.protocol.util.Pair;
import java.util.Optional;

import java.util.LinkedList;
import java.util.List;


public class PairedGroupName
{
  final private LiveRecipient groupRecipient;
        private LiveRecipient otherMember;
  final private Context     context;

  final static private String TAG  = Log.tag(PairedGroupName.class);

  public PairedGroupName (Context context, LiveRecipient groupRecipient)
  {
    this.context         = context;
    this.groupRecipient = groupRecipient;
  }

  public LiveRecipient getOtherMember ()
  {
    return otherMember;
  }

  public LiveRecipient getGroupRecipient ()
  {
    return groupRecipient;
  }

  public Optional<SpannableString> getStylisedName()
  {
    if (groupRecipient.get().isGroup()) {
      GroupDatabase.GroupRecord groupRecord;
      GroupId                   groupId = groupRecipient.get().getGroupId().get();
      GroupDatabase groupDatabase       = SignalDatabase.groups();

      groupRecord = groupDatabase.getGroupByGroupId(groupId);
      if (groupRecord != null && groupDatabase.isPairedGroup(groupId)) {
        List<RecipientId> groupMembersFinal  = groupRecord.getMembersRecipientId();
        if (groupMembersFinal.size() == 1)  groupMembersFinal.add(groupRecord.getMembersInvitedRecipientId().get(0));//user has not accepted invitation yet

        return Optional.of(buildPairedGroupName(TextSecurePreferences.getUfsrvUserId(context), groupMembersFinal, groupId.toString()));

      }
    }

    return Optional.empty();
  }

  private SpannableString buildPairedGroupName (String myUfsrvUid, List<RecipientId> members, String groupNamefallback)
  {
    Pair <SpannableString, LiveRecipient>formattedPairedGroupName = formatPairedGroupName(myUfsrvUid, members, groupNamefallback);
    if (formattedPairedGroupName != null) {
      if (formattedPairedGroupName.second() != null)
        this.otherMember = formattedPairedGroupName.second();

      return formattedPairedGroupName.first();
    }

    return new SpannableString("PrivateGroup_" + groupNamefallback);
  }

  static private Pair<SpannableString, LiveRecipient> formatPairedGroupName (String myUfsrvUid, List<RecipientId> members, String groupNamefallback)
  {
    Context thisContext = ApplicationContext.getInstance();
    //Log.d (TAG, String.format("formatPairedGroupName: formatting for group '%s'", groupNamefallback));
    RecipientId recipientIdOtherMember = RecipientId.UNKNOWN;

    LiveRecipient liveRecipient = Recipient.live(myUfsrvUid);
    for (RecipientId member: members) {
      if (!member.equals(liveRecipient.getId())) {
        recipientIdOtherMember = member;
        break;
      }
    }

    Recipient otherMember = Recipient.resolved(recipientIdOtherMember);
    Drawable groupNamesConnectorIcon = new IconicsDrawable(thisContext)
            .icon(CommunityMaterial.Icon.cmd_swap_horizontal)
            .color(thisContext.getResources().getColor(R.color.core_grey_30))
            .sizeDp(16);

    String otherMemberDesignator =  !TextUtils.isEmpty(otherMember.getNickname()) ?
                                    otherMember.getNickname()
                                    : !TextUtils.isEmpty(otherMember.getGroupName(ApplicationContext.getInstance())) ?
                                      otherMember.getGroupName(ApplicationContext.getInstance())
                                                                                     : !TextUtils.isEmpty(otherMember.requireUfsrvUid() ) ?
                                        otherMember.requireUfsrvUid().substring(0, 9) + "..."
                                        :" ..."; //this is most likely when async fetching

    String src = String.format("%s + %s", otherMemberDesignator, thisContext.getString(R.string.you));

    int index = src.indexOf("+");
    SpannableString iconifiedString = new SpannableString(src);
    iconifiedString.setSpan(new ImageSpan(groupNamesConnectorIcon), index, index + 1, ImageSpan.ALIGN_BASELINE);

    return new Pair<>(iconifiedString, otherMember.live());
  }

  static public SpannableString styliseGroupTitle (String groupId, List<String> members, List<String> membersInvited)
  {
    if (members.size() == 1 && membersInvited.size() > 0)  members.add(membersInvited.get(0));//user has not accepted invitation yet, or they may have uninvited themselves

    List<RecipientId> groupMembersRecipientIds = new LinkedList<>();
    for (String memberUfsrvUid: members) {
      groupMembersRecipientIds.add(Recipient.live(memberUfsrvUid).getId());
    }
    Pair<SpannableString, LiveRecipient> formattedTitle = formatPairedGroupName(TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance()), groupMembersRecipientIds, groupId);

    return formattedTitle.first();
  }

  @FunctionalInterface
  public interface PairedGroupTitleStyliser
  {
    SpannableString formatTitle (String groupId, List<String> members, List<String> membersInvited);
  }
}
