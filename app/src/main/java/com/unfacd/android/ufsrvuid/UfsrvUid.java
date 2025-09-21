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

package com.unfacd.android.ufsrvuid;

import android.text.TextUtils;

import com.unfacd.android.utils.crockford32.Crockford32;

import org.signal.core.util.logging.Log;

import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class UfsrvUid implements Serializable
{
  private String ufsrvUidEncoded;
  private ByteBuffer ufsrvUidRaw;

  final public static String UndefinedUfsrvUid  = "01000000000000000000000000";
  final public static String UndefinedUfsrvUidTruncated  = "0";
  final public static int ufsrvUidEncodedLength = 26;

  public final static UfsrvUid undefinedUfsrvUid;
  static {
    undefinedUfsrvUid = UfsrvUid.getUndefinedUfsrvUid();
  }

  public UfsrvUid (String ufsrvUidEncoded) {
    this.ufsrvUidEncoded = ufsrvUidEncoded;

    try {
      byte[] ufsrvUidBytes = Crockford32.decode(ufsrvUidEncoded).toByteArray();
      //This is necessary as BigInteger.toByteArray() returns sign bit, as a whole extra byte, in the most significant
      //location, which is always 0 for positive numbers
      if (ufsrvUidBytes[0] == 0) {
        byte[] tmp = Arrays.copyOfRange(ufsrvUidBytes, 1, ufsrvUidBytes.length); //keep it at 16bytes
        ufsrvUidBytes = tmp;
      }
      this.ufsrvUidRaw = ByteBuffer.wrap(ufsrvUidBytes).order(ByteOrder.LITTLE_ENDIAN);
    } catch (NumberFormatException x) {
      Log.e("UfsrvUid", String.format("UfsrvUid ('%s'):  Bad encoded UfrsvUid provided...", ufsrvUidEncoded));
      this.ufsrvUidEncoded = UndefinedUfsrvUid;
    }

//    Log.d("UfsrvUid", String.format("UfsrvUid:  Rencoded: '%s'...", Crockford32.encode(new BigInteger(this.ufsrvUidRaw.array()))));
  }

  public UfsrvUid (byte[] ufsrvUidBytes) {
    int byteArrayLength = ufsrvUidBytes.length;
    if (byteArrayLength < 16 || byteArrayLength > 17) {
      if (ufsrvUidBytes.length == 0) {
        ufsrvUidBytes = UfsrvUid.DecodeUfsrvUid(UndefinedUfsrvUid);
      } else {
        throw new AssertionError("Byte array for UfsrvUid invalid, or zero-size: " + byteArrayLength);
      }
    }

    if (byteArrayLength == 16) {
      //add two's complement for BigInteger and leave first byte as '0' for signedness (ie always positive)
      byte[] tmp = new byte[byteArrayLength +1];
      System.arraycopy(ufsrvUidBytes, 0, tmp, 1, byteArrayLength);
      this.ufsrvUidEncoded = Crockford32.encode(new BigInteger(tmp));
    } else {
      this.ufsrvUidEncoded = Crockford32.encode(new BigInteger(ufsrvUidBytes));
    }

    if (this.ufsrvUidEncoded.length() == ufsrvUidEncodedLength - 1) this.ufsrvUidEncoded = "0" + this.ufsrvUidEncoded; //this is crude, BigInt strips leading '0'

    this.ufsrvUidRaw = ByteBuffer.wrap(ufsrvUidBytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  public long getUfsrvSequenceId () {
    if (!this.ufsrvUidRaw.hasArray()) {
      throw new IllegalStateException("ufsrvUidRaw undefined");
    }

    return toLong2();
//    byte[] sequenceId = new byte[8];
//    this.ufsrvUidRaw.position(8);
//    this.ufsrvUidRaw.get(sequenceId, 0, 8);
//    this.ufsrvUidRaw.rewind();
//
//    return ByteBuffer.wrap(sequenceId).order(ByteOrder.LITTLE_ENDIAN).getLong();
  }

  private static final String    HEXES    = "0123456789ABCDEF";

  public String toHex() {
    byte[] raw = this.ufsrvUidRaw.array();
    final StringBuilder hex = new StringBuilder(2 * raw.length);
    for (final byte b : raw) {
      hex.append(HEXES.charAt((b & 0xF0) >> 4)).append(HEXES.charAt((b & 0x0F)));
    }
    return hex.toString();
  }

  public  final long toLong (int offset, int len)
  {
    byte[] byteArray = this.ufsrvUidRaw.array();
    long val = 0;
    len = Math.min(len, 8);
    for (int i = (len - 1); i >= 0; i--)
    {
      val <<= 8;
      val |= (byteArray [offset + i] & 0x00FF);
    }
    return val;
  }

  public  final long toLong2 ()
  {
    byte[] b = this.ufsrvUidRaw.array();
    long   l = 0;
    try {
      l =
//            ((long) b[15] << 56)
//            | ((long) b[14] & 0xff) << 48
//            | ((long) b[13] & 0xff) << 40
//            | ((long) b[12] & 0xff) << 32
//            | ((long) b[11] & 0xff) << 24
//            | ((long) b[10] & 0xff) << 16
//            | ((long) b[9] & 0xff) << 8
//            | ((long) b[8] & 0xff);

              ((long) b[8] & 0xff)
                      | ((long) b[9] & 0xff) << 8
                      | ((long) b[10] & 0xff) << 16
                      | ((long) b[11] & 0xff) << 24
                      | ((long) b[12] & 0xff) << 32
                      | ((long) b[13] & 0xff) << 40
                      | ((long) b[14] & 0xff) << 48
                      | ((long) b[15] << 56);

      return l;
    } catch (ArrayIndexOutOfBoundsException ex) {
      Log.d("UfsrvUid", ex.getMessage());
    }

    return l;
  }

  public byte[] fromLong (long lng)
  {
    byte[] b = new byte[]{
            (byte) lng,
            (byte) (lng >> 8),
            (byte) (lng >> 16),
            (byte) (lng >> 24),
            (byte) (lng >> 32),
            (byte) (lng >> 40),
            (byte) (lng >> 48),
            (byte) (lng >> 56)};
    return b;
  }

  public String getUfsrvUidEncoded () {
    return this.ufsrvUidEncoded;
  }

  public ByteBuffer getUfsrvUidRaw () {
    return this.ufsrvUidRaw;
  }

  public static long DecodeUfsrvSequenceId (byte[] ufsrvUidRaw) {
    int byteArrayLength = ufsrvUidRaw.length;
    if (byteArrayLength<16 || byteArrayLength>17) {
      if (ufsrvUidRaw.length==0) {
        ufsrvUidRaw = UfsrvUid.DecodeUfsrvUid(UndefinedUfsrvUid);
      } else {
        throw new AssertionError("Byte array for UfsrvUid invalid, or zero-size: " + byteArrayLength);
      }
    }

    ByteBuffer ufsrvUidByteBuffer = ByteBuffer.wrap(ufsrvUidRaw).order(ByteOrder.LITTLE_ENDIAN);
    byte[] sequenceId = new byte[8];

    ufsrvUidByteBuffer.position(8);
    ufsrvUidByteBuffer.get(sequenceId, 0, 8);

    return ByteBuffer.wrap(sequenceId).order(ByteOrder.LITTLE_ENDIAN).getLong();
  }

  public static String EncodedfromSerialisedBytes (byte[] ufsrvUidRaw)
  {
    return new UfsrvUid(ufsrvUidRaw).toString();
  }

  public static UfsrvUid fromEncoded (String ufsrvUidEncoded)
  {
    if (ufsrvUidEncoded != null) return new UfsrvUid(ufsrvUidEncoded);

    return null;
  }

  public static byte[] DecodeUfsrvUid (String ufsrvUidEncoded) {
    try {
      byte[] ufsrvUidBytes = Crockford32.decode(ufsrvUidEncoded).toByteArray();
      if (ufsrvUidBytes[0] == 0) {
        byte[] tmp = Arrays.copyOfRange(ufsrvUidBytes, 1, ufsrvUidBytes.length);
        ufsrvUidBytes = tmp;
      }

      return ufsrvUidBytes;
    } catch (NumberFormatException ex) {
      throw new UfsrvUidEncodingError(ufsrvUidEncoded);
    }
  }

  public static UfsrvUid getUndefinedUfsrvUid ()
  {
    return new UfsrvUid(UfsrvUid.UndefinedUfsrvUid);
  }

  public boolean isUndefined ()
  {
    return this.ufsrvUidEncoded.equals(UfsrvUid.UndefinedUfsrvUidTruncated) || this.ufsrvUidEncoded.equals(UfsrvUid.UndefinedUfsrvUid);
  }

  public String toString () {
    if (TextUtils.isEmpty(this.ufsrvUidEncoded)) return UndefinedUfsrvUid;
    else return this.ufsrvUidEncoded;
  }

  public static boolean serializedListContains(@NonNull String serialized, @NonNull String ufsrvUid) {
    return Pattern.compile("\\b" + ufsrvUid + "\\b")
            .matcher(serialized)
            .find();
  }

  public static class UfsrvUidEncodingError extends AssertionError {
    public UfsrvUidEncodingError(@Nullable String ufsrvUid) {
      super("Encoding error for: " + ufsrvUid);
    }


  }
}