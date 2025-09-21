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
import org.whispersystems.signalservice.internal.push.http.CancelationSignal;
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec;

import java.io.InputStream;

/**
 * Represents a local SignalServiceAttachment to be sent.
 */
public class SignalServiceAttachmentStream extends SignalServiceAttachment {

  private final InputStream                       inputStream;
  private final long                              length;
  private final Optional<String>                  fileName;
  private final ProgressListener                  listener;
  private final CancelationSignal                 cancelationSignal;
  private final Optional<byte[]>                  preview;
  private final boolean                           voiceNote;
  private final boolean                           borderless;
  private final boolean                           gif;
  private final int                               width;
  private final int                               height;
  private final long                              uploadTimestamp;
  private final Optional<String>                  caption;
  private final Optional<String>                  blurHash;
  private final Optional<ResumableUploadSpec>     resumableUploadSpec;

  //AA+
  private final Optional<byte[]> key;
  private final long             keySize;

  public SignalServiceAttachmentStream(InputStream inputStream,
                                       String contentType,
                                       long length,
                                       Optional<String> fileName,
                                       boolean voiceNote,
                                       boolean borderless,
                                       boolean gif,
                                       Optional<byte[]> preview,
                                       int width,
                                       int height,
                                       long uploadTimestamp,
                                       Optional<String> caption,
                                       Optional<String> blurHash,
                                       ProgressListener listener,
                                       CancelationSignal cancelationSignal,
                                       Optional<ResumableUploadSpec> resumableUploadSpec,
                                       Optional<byte[]>key, //AA+
                                       long keySize)
  {
    super(contentType);
    this.inputStream             = inputStream;
    this.length                  = length;
    this.fileName                = fileName;
    this.listener                = listener;
    this.voiceNote               = voiceNote;
    this.borderless              = borderless;
    this.gif                     = gif;
    this.preview                 = preview;
    this.width                   = width;
    this.height                  = height;
    this.uploadTimestamp         = uploadTimestamp;
    this.caption                 = caption;
    this.blurHash                = blurHash;
    this.cancelationSignal       = cancelationSignal;
    this.resumableUploadSpec     = resumableUploadSpec;

    //AA+
    this.key          = key;
    this.keySize      = keySize;
  }

  public SignalServiceAttachmentStream(InputStream inputStream,
                                       String contentType,
                                       long length,
                                       Optional<String> fileName,
                                       boolean voiceNote,
                                       boolean borderless,
                                       boolean gif,
                                       ProgressListener listener,
                                       CancelationSignal cancelationSignal)
  {
    this(inputStream, contentType, length, fileName, voiceNote, borderless, gif, Optional.<byte[]>empty(), 0, 0, System.currentTimeMillis(), Optional.<String>empty(),  Optional.<String>empty(), listener, cancelationSignal, Optional.empty(), Optional.<byte[]>empty(), 0);//AA+ last optional param
  }

  /*public SignalServiceAttachmentStream(InputStream inputStream,
                                       String contentType,
                                       long length,
                                       Optional<String> fileName,
                                       boolean voiceNote,
                                       boolean borderless,
                                       Optional<byte[]> preview,
                                       int width,
                                       int height,
                                       long uploadTimestamp,
                                       Optional<String> caption,
                                       Optional<String> blurHash,
                                       ProgressListener listener,
                                       CancelationSignal cancelationSignal,
                                       Optional<ResumableUploadSpec> resumableUploadSpec)
  {
    this(inputStream, contentType, length, fileName, voiceNote, borderless,  false, preview, 0, 0, System.currentTimeMillis(), Optional.<String>empty(),  Optional.<String>empty(), listener, cancelationSignal, Optional.empty(), Optional.<byte[]>empty(), 0); //AA+ last param
  }*/

  @Override
  public boolean isStream() {
    return true;
  }

  @Override
  public boolean isPointer() {
    return false;
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public long getLength() {
    return length;
  }

  public Optional<String> getFileName() {
    return fileName;
  }

  public ProgressListener getListener() {
    return listener;
  }

  public CancelationSignal getCancelationSignal() {
    return cancelationSignal;
  }

  public Optional<byte[]> getPreview() {
    return preview;
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

  public boolean getVoiceNote() {
    return voiceNote;
  }

  public boolean isBorderless() {
    return borderless;
  }

  public Optional<String> getBlurHash() {
    return blurHash;
  }

  public long getUploadTimestamp() {
    return uploadTimestamp;
  }

  public Optional<ResumableUploadSpec> getResumableUploadSpec() {
    return resumableUploadSpec;
  }

  //AA+
  public Optional<byte[]> getKey() {
    return key;
  }

  public long getKeySize() {
    return keySize;
  }
}
