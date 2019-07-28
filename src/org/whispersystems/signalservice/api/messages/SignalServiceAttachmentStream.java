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

import java.io.InputStream;

/**
 * Represents a local SignalServiceAttachment to be sent.
 */
public class SignalServiceAttachmentStream extends SignalServiceAttachment {

  private final InputStream      inputStream;
  private final long             length;
  private final Optional<String> fileName;
  private final ProgressListener listener;
  private final Optional<byte[]> preview;
  private final boolean          voiceNote;
  private final int              width;
  private final int              height;
  private final Optional<String> caption;

  //
  private final Optional<byte[]> key;
  private final long             keySize;

  // key
  public SignalServiceAttachmentStream(InputStream inputStream, String contentType, long length, Optional<String> fileName, boolean voiceNote, Optional<byte[]> preview,  int width, int height, Optional<String> caption, ProgressListener listener, Optional<byte[]>key, long keySize) {
    super(contentType);
    this.inputStream = inputStream;
    this.length      = length;
    this.fileName    = fileName;
    this.listener    = listener;
    this.voiceNote   = voiceNote;
    this.preview     = preview;
    this.width       = width;
    this.height      = height;
    this.caption     = caption;

    //
    this.key          = key;
    this.keySize      = keySize;
  }

  public SignalServiceAttachmentStream(InputStream inputStream, String contentType, long length, Optional<String> fileName, boolean voiceNote, ProgressListener listener) {
    this(inputStream, contentType, length, fileName, voiceNote, Optional.<byte[]>absent(), 0, 0, Optional.<String>absent(), listener, Optional.<byte[]>absent(), 0);// last optional param
  }

  public SignalServiceAttachmentStream(InputStream inputStream, String contentType, long length, Optional<String> fileName, boolean voiceNote, Optional<byte[]> preview, ProgressListener listener) {
    this(inputStream, contentType, length, fileName, voiceNote, preview, 0, 0, Optional.<String>absent(), listener, Optional.<byte[]>absent(), 0); // last param
   //-
    //    super(contentType);
//    this.inputStream = inputStream;
//    this.length      = length;
//    this.fileName    = fileName;
//    this.listener    = listener;
//    this.voiceNote   = voiceNote;
//    this.preview     = preview;
  }

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

  public boolean getVoiceNote() {
    return voiceNote;
  }

  //
  public Optional<byte[]> getKey() {
    return key;
  }

  public long getKeySize() {
    return keySize;
  }
}
