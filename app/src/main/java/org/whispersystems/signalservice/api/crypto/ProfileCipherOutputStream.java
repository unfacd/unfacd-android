package org.whispersystems.signalservice.api.crypto;

import com.annimon.stream.function.Function;

import org.signal.libsignal.zkgroup.profiles.ProfileKey;

import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ProfileCipherOutputStream extends DigestingOutputStream {

  private final Cipher cipher;

  public ProfileCipherOutputStream(OutputStream out, ProfileKey key, Function<byte[], AlgorithmParameterSpec> parameterProvider) throws IOException, InvalidAlgorithmParameterException { //AA+ spec + excep
    super(out);
    try {
      this.cipher = Cipher.getInstance("AES/GCM/NoPadding");

      byte[] nonce  = generateNonce();
      this.cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.serialize(), "AES"), parameterProvider.apply(nonce));

      super.write(nonce, 0, nonce.length);
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw new AssertionError(e);
    } catch (InvalidKeyException e) {
      throw new IOException(e);
    }
  }

  //AA+ temporary remedy, as some devices want this as opposed to IVParamSpec (huawei). TAG_LENGTH_BIT  = (TAG_LENGTH_BYTES: 16 * 8) = 128
  //https://tools.ietf.org/html/rfc5288#section-3
  //this was addressed later on in https://github.com/signalapp/libsignal-service-java/commit/44d084bb98e4feac8d6cdca9ece96e7627e6b327, but we are keeping this mod as is for now
  static public AlgorithmParameterSpec getGCMParamSpecs(final byte[] nonce)
  {
    return new GCMParameterSpec(128, nonce);
  }

  static public AlgorithmParameterSpec getIVParamSpecs (final byte[] nonce)
  {
    return new  IvParameterSpec(nonce);
  }
  //

  @Override
  public void write(byte[] buffer) throws IOException {
    write(buffer, 0, buffer.length);
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    byte[] output = cipher.update(buffer, offset, length);
    super.write(output);
  }

  @Override
  public void write(int b) throws IOException {
    byte[] input = new byte[1];
    input[0] = (byte)b;

    byte[] output = cipher.update(input);
    super.write(output);
  }

  @Override
  public void flush() throws IOException {
    try {
      byte[] output = cipher.doFinal();

      super.write(output);
      super.flush();
    } catch (BadPaddingException | IllegalBlockSizeException e) {
      throw new AssertionError(e);
    }
  }

  private byte[] generateNonce() {
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    return nonce;
  }

  public static long getCiphertextLength(long plaintextLength) {
    return 12 + 16 + plaintextLength;
  }
}