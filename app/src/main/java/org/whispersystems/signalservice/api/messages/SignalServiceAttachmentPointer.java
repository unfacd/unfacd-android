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

import java.util.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;

/**
 * Represents a received SignalServiceAttachment "handle."  This
 * is a pointer to the actual attachment content, which needs to be
 * * retrieved using {@link SignalServiceMessageReceiver#retrieveAttachment(SignalServiceAttachmentPointer, java.io.File, long)}
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAttachmentPointer extends SignalServiceAttachment {

  private final int                             cdnNumber;
  private final SignalServiceAttachmentRemoteId remoteId;
  private final byte[]                          key;
  private final Optional<Integer>               size;
  private final Optional<byte[]>                preview;
  private final Optional<byte[]>                digest;
  private final Optional<String>                fileName;
  private final boolean                         voiceNote;
  private final boolean                         borderless;
  private final boolean                         gif;
  private final int                             width;
  private final int                             height;
  private final Optional<String>                caption;
  private final Optional<String>                blurHash;
  private final long                            uploadTimestamp;

  private final String                          ufId;//AA++


  //AA+ ufID
  public SignalServiceAttachmentPointer(String ufId,
                                        long id,
                                        String contentType,
                                        byte[] key,
                                        Optional<byte[]> digest,
                                        Optional<String> fileName,
                                        boolean voiceNote,
                                        boolean borderless,
                                        boolean gif)
  {
    this(ufId, 0, SignalServiceAttachmentRemoteId.from("0"), contentType, key, Optional.<Integer>empty(), Optional.<byte[]>empty(), 0, 0, digest, fileName, voiceNote, borderless, gif, Optional.empty(), Optional.empty(), 0);
  }


//AA+ ufid.
public SignalServiceAttachmentPointer(String ufid,
                                      int cdnNumber,
                                      SignalServiceAttachmentRemoteId remoteId,
                                      String contentType,
                                      byte[] key,
                                      Optional<Integer> size,
                                      Optional<byte[]> preview,
                                      int width,
                                      int height,
                                      Optional<byte[]> digest,
                                      Optional<String> fileName,
                                      boolean voiceNote,
                                      boolean borderless,
                                      boolean gif,
                                      Optional<String> caption,
                                      Optional<String> blurHash,
                                      long uploadTimestamp)
{

    super(contentType);
    this.cdnNumber       = cdnNumber;
    this.remoteId        = remoteId;
    this.key             = key;
    this.size            = size;
    this.preview         = preview;
    this.width           = width;
    this.height          = height;
    this.digest          = digest;
    this.fileName        = fileName;
    this.voiceNote       = voiceNote;
    this.borderless      = borderless;
    this.gif             = gif;
    this.caption         = caption;
    this.blurHash        = blurHash;
    this.uploadTimestamp = uploadTimestamp;

    //AA+
    this.ufId            = ufid;
  }

  //AA+
  public String getUfId() {
    return ufId;
  }

  public int getCdnNumber() {
    return cdnNumber;
  }

  public SignalServiceAttachmentRemoteId getRemoteId() {
    return remoteId;
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

  public boolean isBorderless() {
    return borderless;
  }

  public boolean isGif() {
    return gif;
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

  public Optional<String> getBlurHash() {
    return blurHash;
  }

  public long getUploadTimestamp() {
    return uploadTimestamp;
  }
}
