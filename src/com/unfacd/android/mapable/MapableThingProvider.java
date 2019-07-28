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


import android.content.Context;
import android.graphics.Bitmap;

import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.util.FutureTaskListener;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.SoftHashMap;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MapableThingProvider
{
  private static final String TAG = MapableThingProvider.class.getSimpleName();

  private static final MapableThingCache  mapableThingCache         = new MapableThingCache();

  static public MapableThing getMapbleGroup (Context context, SignalServiceProtos.FenceRecord fenceRecord)
  {
    MapableThing mapableThingCached = mapableThingCache.get(fenceRecord.getFid());
    if (mapableThingCached != null && ((MapableGroup)mapableThingCached).getGroupRecord().getEid() >= fenceRecord.getEid())   return mapableThingCached;

    MapableGroup mapableGroup = new MapableGroup(fenceRecord);

    mapableThingCache.set(fenceRecord.getFid(), mapableGroup);

    if (fenceRecord.hasAvatar() && fenceRecord.getAvatar().hasDigest()) {
      Optional<ListenableFutureTask<Bitmap>> future = mapableGroup.BitmapFromAttachmentPointer(fenceRecord.getFid(), fenceRecord);
      if (future.isPresent()) {
        future.get().addListener(new FutureTaskListener<Bitmap>() {
          @Override
          public void onSuccess(Bitmap result) {
            mapableGroup.setAvatar(result);
          }
          @Override
          public void onFailure(ExecutionException error) {
            Log.w(TAG, "onFailure: " + error);
          }
        });
      }
    }

    return mapableGroup;
  }

  static public Optional<MapableGroup> getMapbleGroup (long fid)
  {
    MapableThing mapableThingCached = mapableThingCache.get(fid);
    if (mapableThingCached != null) return Optional.of((MapableGroup) mapableThingCached);

    return Optional.absent();
  }

  public void setMapableThing (long fid, MapableThing mapableThing)
  {
    mapableThingCache.set(fid, mapableThing);
  }

  private static class MapableThingCache {

    private final Map<Long,MapableThing> cache = new SoftHashMap<>(1000);

    public synchronized MapableThing get(long fid) {
      return cache.get(fid);
    }

    public synchronized void set(long fid, MapableThing mapableThing) {
      cache.put(fid, mapableThing);
    }

    public synchronized void reset() {
      for (MapableThing mapableThing : cache.values()) {
        //rec.setStale();
      }
    }

  }
}
