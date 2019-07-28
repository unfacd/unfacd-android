/**
 * Copyright (C) 2014 Open Whisper Systems
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
package org.whispersystems.signalservice.api.messages;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;

/**
 * Represents a received SignalServiceAttachment "handle."  This
 * is a pointer to the actual attachment content, which needs to be
 * retrieved using {@link SignalServiceMessageReceiver#retrieveAttachment(SignalServiceAttachmentPointer, java.io.File, int)}
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAttachmentPointer extends SignalServiceAttachment {

  private final long              id;
  private final byte[]            key;
  private final Optional<Integer> size;
  private final Optional<byte[]>  preview;
  private final Optional<byte[]>  digest;
  private final Optional<String>  fileName;
  private final boolean           voiceNote;
  private final int               width;
  private final int               height;
  private final Optional<String>  caption;

  // we use string instead of Long id
  private final String            ufId;


  // ufID
  public SignalServiceAttachmentPointer(String ufId, long id, String contentType, byte[] key, Optional<byte[]> digest, Optional<String> fileName, boolean voiceNote) {
    this(ufId, id, contentType, key, Optional.<Integer>absent(), Optional.<byte[]>absent(), 0, 0, digest, fileName, voiceNote, Optional.absent());
  }


// ufid.
  public SignalServiceAttachmentPointer(String ufid, long id, String contentType, byte[] key,
                                        Optional<Integer> size, Optional<byte[]> preview,
                                        int width, int height,
                                        Optional<byte[]> digest, Optional<String> fileName,
                                        boolean voiceNote,
                                        Optional<String> caption)
  {
    super(contentType);
    this.id        = id;
    this.key       = key;
    this.size      = size;
    this.preview   = preview;
    this.width     = width;
    this.height    = height;
    this.digest    = digest;
    this.fileName  = fileName;
    this.voiceNote = voiceNote;
    this.caption   = caption;

    //
    this.ufId=ufid;
  }

  //
  public String getUfId() {
    return ufId;
  }

  public long getId() {
    return id;
  }


  public byte[] getKey() {
    return key;
  }

  @Override
  public boolean isStream() {
    return false;
  }

  @Override
  public boolean isPointer() {
    return true;
  }

  public Optional<Integer> getSize() {
    return size;
  }

  public Optional<String> getFileName() {
    return fileName;
  }

  public Optional<byte[]> getPreview() {
    return preview;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public Optional<String> getCaption() {
    return caption;
  }

  public Optional<byte[]> getDigest() {
    return digest;
  }

  public boolean getVoiceNote() {
    return voiceNote;
  }
}
