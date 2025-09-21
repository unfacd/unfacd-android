package org.whispersystems.signalservice.internal.push.http;


import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.whispersystems.signalservice.api.crypto.DigestingOutputStream;
import org.whispersystems.signalservice.api.crypto.ProfileCipherOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;

public class ProfileCipherOutputStreamFactory implements OutputStreamFactory {

  private final ProfileKey key;

  public ProfileCipherOutputStreamFactory(ProfileKey key) {
    this.key = key;
  }

  @Override
  public DigestingOutputStream createFor(OutputStream wrap) throws IOException {
    try {
      return new ProfileCipherOutputStream(wrap, key, ProfileCipherOutputStream::getIVParamSpecs);//AA+
    } catch (InvalidAlgorithmParameterException e) {
      try {
        return new ProfileCipherOutputStream(wrap, key, ProfileCipherOutputStream::getGCMParamSpecs);//AA+
      } catch (InvalidAlgorithmParameterException nestedE) {
        throw new AssertionError(nestedE);
      }
    }
  }

}