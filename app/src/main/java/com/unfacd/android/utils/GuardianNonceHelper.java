package com.unfacd.android.utils;


import androidx.annotation.NonNull;

import android.util.Base64;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.jsoup.helper.StringUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.util.JsonUtils;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GuardianNonceHelper {

  final public static int GuardianKeySize = 32;

  public static SealedData seal(@NonNull byte[] input, SecretKey key) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key);

      byte[] iv   = cipher.getIV();
      byte[] data = cipher.doFinal(input);

      return new SealedData(iv, data, key.getEncoded());
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
      throw new AssertionError(e);
    }
  }

  public static byte[] unseal(@NonNull SealedData sealedData, byte[] key) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealedData.iv));

      return cipher.doFinal(sealedData.data);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
      throw new AssertionError(e);
    }
  }

  public static SecretKey generateKey(){
    KeyGenerator aesKey = null;
    try {
      aesKey = KeyGenerator.getInstance("AES");
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
    aesKey.init(256);
    SecretKey secretKey = aesKey.generateKey();

    return secretKey;
  }

  public static class SealedData {

    @SuppressWarnings("unused")
    private static final String TAG = Log.tag(SealedData.class);

    @JsonProperty
    @JsonSerialize(using = ByteArraySerializer.class)
    @JsonDeserialize(using = ByteArrayDeserializer.class)
    private byte[] iv;

    @JsonProperty
    @JsonSerialize(using = ByteArraySerializer.class)
    @JsonDeserialize(using = ByteArrayDeserializer.class)
    private byte[] data;

    private byte[] key;

    SealedData(@NonNull byte[] iv, @NonNull byte[] data, @NonNull byte[] key) {
      this.iv   = iv;
      this.data = data;
      this.key = key;
    }

    @SuppressWarnings("unused")
    public SealedData() {}

    public String serialize() {
      try {
        return JsonUtils.toJson(this);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    public String serialiseKey () throws InvalidKeyException
    {
      if (key != null && key.length == GuardianKeySize)  {
        String serialisedKey = Base64.encodeToString(key, Base64.NO_WRAP | Base64.NO_PADDING);
        Log.e(TAG, String.format("serialiseKey: '%s'", serialisedKey));
        return serialisedKey;
      }

      throw new InvalidKeyException("Wrong key size");
    }

    static public byte[] deserialiseKey (String encodedKey) throws InvalidKeyException
    {
      if (!StringUtil.isBlank(encodedKey))  {
        return Base64.decode(encodedKey, Base64.NO_WRAP | Base64.NO_PADDING);
      }

      throw new InvalidKeyException("Wrong key size");
    }

    public static SealedData fromString(@NonNull String value) {
      try {
        return JsonUtils.fromJson(value, SealedData.class);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    private static class ByteArraySerializer extends JsonSerializer<byte[]> {
      @Override
      public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(Base64.encodeToString(value, Base64.NO_WRAP | Base64.NO_PADDING));
      }
    }

    private static class ByteArrayDeserializer extends JsonDeserializer<byte[]> {

      @Override
      public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return Base64.decode(p.getValueAsString(), Base64.NO_WRAP | Base64.NO_PADDING);
      }
    }

  }

}