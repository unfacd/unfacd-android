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

package com.unfacd.android.preferences;

import android.content.Context;

import com.unfacd.android.fence.EnumFencePermissions;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * This provides a subset of users selected based on assigned criterion.
 */

public class MultiSelectPreferenceListGroupMembersSelected implements IDynamicProvider
{
  private static final String TAG = MultiSelectPreferenceListGroupMembersSelected.class.getSimpleName();

  List<Recipient> recipientGroupMembers = null;
  Set<Long>       ignoredMembers        = null;
  String          groupId               = null;
  int             selectedCriterion;
  long            groupOwneruserId;

  @Override
  public void setCustomId  (String customId)
  {
    this.groupId=customId;
  }

  @Override
  public void setSelectedCriterion  (int selectedCriterion)
  {
    this.selectedCriterion=selectedCriterion;
  }

  @Override
  public List<Recipient> getItems()
  {
    return recipientGroupMembers;
  }

  @Override
  public void populate (Context context)
  {
    fetchGroupMembers(context);
  }

  @Override
  public List<String> getItemsForDisplay ()
  {
    List<String> thisGroupMembers=new LinkedList<>();

    for (Recipient recipient: recipientGroupMembers)
    {
      if (ignoredMembers.contains(recipient.getUfsrvId()) || recipient.getUfsrvId()==groupOwneruserId)  continue;
      thisGroupMembers.add(recipient.getDisplayName());
    }
    return thisGroupMembers;
  }

  @Override
  public List<String> getItemsForId ()
  {
    List<String> thisGroupMembers=new LinkedList<>();

    for (Recipient recipient: recipientGroupMembers)
    {
      if (ignoredMembers.contains(recipient.getUfsrvId()) || recipient.getUfsrvId()==groupOwneruserId)  continue;
      thisGroupMembers.add(String.valueOf(recipient.getUfsrvId()));
    }
    return thisGroupMembers;
  }

  @Override
  public List<String> getSelectedItems (Context context)
  {
    List<Recipient> recipientsMembers = DatabaseFactory.getRecipientDatabase(context)
            .getMembersWithPermission(context,
                                      Recipient.fromFid(context, Long.valueOf(groupId), false),
                                      EnumFencePermissions.values()[selectedCriterion],
                                      false);
    List<String> members = new LinkedList<>();
    for (Recipient member : recipientsMembers)
    {
      if (ignoredMembers.contains(Long.valueOf(member.getUfsrvId())) || member.getUfsrvId()==groupOwneruserId)  continue;
      members.add(String.valueOf(member.getUfsrvId()));
    }

    return members;
  }

  @Override
  public int getCount ()
  {
    return recipientGroupMembers.size();
  }

  @Override
  public void setIgnoredMembers (Set<Long> ignoredMembers)
  {
    this.ignoredMembers=ignoredMembers;
  }

  private void fetchGroupMembers (Context context)
  {
    List<Recipient> recipientMembers=DatabaseFactory.getRecipientDatabase(context)
                                                      .getMembersWithPermission(context,
                                                                                Recipient.fromFid(context, Long.valueOf(groupId), false),
                                                                                EnumFencePermissions.values()[selectedCriterion],
                                                                                false);
    recipientGroupMembers=recipientMembers;
  }
}
