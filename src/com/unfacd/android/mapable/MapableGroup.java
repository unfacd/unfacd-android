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

package com.unfacd.android.mapable;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.maps.model.LatLng;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

public class MapableGroup extends MapableThing
{
  private GroupDatabase.GroupRecord groupRecord;
  static private ContactPhoto defaultContactPhoto = null;
  static private Bitmap defaultGroupIcon = BitmapFactory.decodeResource(ApplicationContext.getInstance().getResources(), R.drawable.ic_group_grey600_24dp);

  public MapableGroup (long fid)
  {
    GroupDatabase.GroupRecord group=DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).getGroupRecordByFid(fid);
    if (group!=null)  this.groupRecord  = group;
  }

  public MapableGroup (FenceRecord fenceRecord)
  {
    super();
    groupRecord = new GroupDatabase.GroupRecord(null,
                                                fenceRecord.getFname(),
                                                null,
                                                null,
                                                0,
                                                null,
                                                null,
                                                null,
                                                false,
                                                null,
                                                false,
                                                fenceRecord.getCname(),
                                                fenceRecord.getLocation().getLongitude(),
                                                fenceRecord.getLocation().getLatitude(),
                                                fenceRecord.getMaxmembers(),
                                                fenceRecord.getTtl(),
                                                0,/*mode*/
                                                fenceRecord.getFenceType().getNumber(),
                                                fenceRecord.getPrivacyMode().getNumber(),
                                                fenceRecord.getDeliveryMode().getNumber(),
                                                fenceRecord.getJoinMode().getNumber(),
                                                fenceRecord.getFid(),
                                                fenceRecord.getAvatar().getId(),
                                                null,
                                                UfsrvUid.DecodeUfsrvSequenceId(fenceRecord.getOwnerUid().toByteArray()));

    setPosition(new LatLng(fenceRecord.getLocation().getLatitude(), fenceRecord.getLocation().getLongitude()));
    setName(fenceRecord.getFname());
    groupRecord.setEid(fenceRecord.getEid());

    if (defaultContactPhoto == null) {
      setAvatar(defaultGroupIcon);
    }

  }

  public GroupDatabase.GroupRecord getGroupRecord ()
  {
    return groupRecord;
  }
}
