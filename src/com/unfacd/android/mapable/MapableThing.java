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
import org.thoughtcrime.securesms.logging.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.clustering.ClusterItem;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.jobs.MapableAvatarDownloadJob;

import org.thoughtcrime.securesms.mms.AttachmentStreamUriLoader;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.ListenableFutureTask;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import com.bumptech.glide.load.resource.bitmap.RoundedCorners;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;


public class MapableThing implements ClusterItem
{
  private static final String TAG = MapableThing.class.getSimpleName();

  private static final ExecutorService asyncAvatarLoader = Util.newSingleThreadedLifoExecutor();

  Recipient         recipient;
  public String     name;
  public int        profilePhoto;
  private LatLng    position;
  private Bitmap    avatar;

  private final Set<MapableThing.MapableThingModifiedListener> listeners = Collections.newSetFromMap(new WeakHashMap<MapableThing.MapableThingModifiedListener, Boolean>());

  public MapableThing () {

  }

  public MapableThing (LatLng position)
  {
    this.position = position;
  }

  public MapableThing (LatLng position, String name, int pictureResource) {
    this.name = name;
    profilePhoto = pictureResource;
    this.position = position;
  }

  public Recipient getRecipient ()
  {
    return recipient;
  }

  public String getSnippet() {
    return "__snippet__";
  }

  public String getTitle() {
    return "__TITLE__";
  }

  @Override
  public LatLng getPosition() {
    return this.position;
  }

  //todo: consider using blobprovider
//  Uri    uri  = BlobProvider.getInstance()
//          .forData(data)
//          .withMimeType(MediaUtil.IMAGE_JPEG)
//          .createForSingleSessionOnDisk(requireContext(), e -> Log.w(TAG, "Failed to persist image.", e));
  Optional<ListenableFutureTask<Bitmap>> BitmapFromAttachmentPointer (long fid, SignalServiceProtos.FenceRecord fenceRecord)
  {
    SignalServiceAttachmentPointer avatarPointer =  new SignalServiceAttachmentPointer(fenceRecord.getAvatar().getId(),
                                                                                       0,
                                                                                       fenceRecord.getAvatar().getContentType(),
                                                                                       fenceRecord.getAvatar().getKey().toByteArray(),
                                                                                       fenceRecord.getAvatar().hasDigest() ? Optional.of(fenceRecord.getAvatar().getDigest().toByteArray()) : Optional.absent(),
                                                                                       fenceRecord.getAvatar().hasFileName()? Optional.of(fenceRecord.getAvatar().getFileName()):Optional.absent(),
                                                                                       false);
    File storedAvatar = GroupAvatarHelper.getGroupAvatarFile(ApplicationContext.getInstance(), MapableAvatarDownloadJob.constructAvatarFilePrefix(fid, avatarPointer));
    if (storedAvatar != null && storedAvatar.exists()) {
      try {
        Callable<Bitmap> task = () -> BitmapUtil.createScaledBitmap(ApplicationContext.getInstance(), new AttachmentStreamUriLoader.AttachmentModel(storedAvatar, avatarPointer.getKey(), 0, avatarPointer.getDigest()), 500, 500);
        ListenableFutureTask<Bitmap> future = new ListenableFutureTask<>(task);
        asyncAvatarLoader.submit(future);
        Log.e(TAG, String.format("BitmapFromAttachmentPointer (fid:'%d', id:'%s'): Using existing cached group avatar ", fid, fenceRecord.getAvatar().getId()));
        return Optional.of(future);
      } finally {

      }
    }

    if (fenceRecord.getAvatar().getKey() != null && fenceRecord.getAvatar().getKey().size() == 64) {
      ApplicationContext.getInstance(ApplicationContext.getInstance()).getJobManager()
              .add(new MapableAvatarDownloadJob(fid, Base64.encodeBytes(fenceRecord.toByteArray())));//this.avatarPointer.asPointer()));
    } else {
      Log.e(TAG, String.format("BitmapFromAttachmentPointer (fid:'%d', id:'%s'): Attachment has corrupt key ", fenceRecord.getFid(), fenceRecord.getAvatar().getId()));
    }

    return Optional.absent();
  }

  public Bitmap getAvatar ()
  {
    return avatar;
  }

  public void setName (String name) {
    synchronized (this) {
      this.name = name;
    }

    notifyListeners();
  }

  public void setAvatar (Bitmap avatar)
  {
    synchronized (this) {
      this.avatar = avatar;
    }

    notifyListeners();
  }


  public void setPosition (LatLng position)
  {
    this.position = position;
  }


  public synchronized void addListener(MapableThing.MapableThingModifiedListener listener) {
    listeners.add(listener);
  }

  public synchronized void removeListener(MapableThing.MapableThingModifiedListener listener) {
    listeners.remove(listener);
  }

  private void notifyListeners() {
    Set<MapableThing.MapableThingModifiedListener> localListeners;

    synchronized (this) {
      localListeners = new HashSet<>(listeners);
    }

    for (MapableThing.MapableThingModifiedListener listener : localListeners)
      listener.onModified(this);
  }

  public interface MapableThingModifiedListener {
    public void onModified(MapableThing mapableThing);
  }
}
