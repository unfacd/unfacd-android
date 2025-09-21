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

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class MultiSelectPreferenceListGroupMembers implements IDynamicProvider
{
  private static final String TAG = Log.tag(MultiSelectPreferenceListGroupMembers.class);

  List<Recipient>   recipientGroupMembers = null;
  String            groupId               = null;//fid
  Set<Long>         ignoredMembers        = null;
  long              groupOwneruserId;

  @Override
  public void setCustomId  (String customId)
  {
    this.groupId=customId;
  }

  @Override
  public void setSelectedCriterion  (int setSelectedCriterion)
  {
  //empty
  }

  private void fetchGroupMembers (Context context)
  {
    List<Recipient> thisGroupMembers=new LinkedList<>();

    if (groupId!=null)
    {
      List<Recipient> recipientsGroupMembers=SignalDatabase.groups()
              .getGroupMembers(Long.valueOf(groupId), false);

      recipientGroupMembers=recipientsGroupMembers;

      groupOwneruserId=SignalDatabase.groups().getOwnerUserId (Long.valueOf(groupId));
    }
    else  recipientGroupMembers=thisGroupMembers;
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

  //not relevant for this context; see MultiSelectPreferenceListGroupMembersSelected
  @Override
  public  List<String> getSelectedItems (Context context) {
    return null;
  }

  @Override
  public void setIgnoredMembers (Set<Long> ignoredMembers)
  {
    this.ignoredMembers=ignoredMembers;
  }

    @Override
  public int getCount ()
  {
    return recipientGroupMembers.size();
  }
}
