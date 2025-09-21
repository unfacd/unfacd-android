package org.whispersystems.signalservice.api.kbs;

import com.unfacd.android.utils.Utils;

/**
 * Construct from a {@link HashedPin}.
 */
public final class KbsData {
  private final MasterKey masterKey;
  private final byte[]    kbsAccessKey;//AA 2nd half of the hashed pin used to index/retrieve stored/encrypted masterkey at KBS service
  private final byte[]    cipherText; //AA masterkey IVC for encrypted with HashedPin.K (1st half)

  KbsData(MasterKey masterKey, byte[] kbsAccessKey, byte[] cipherText) {
    this.masterKey    = masterKey;
    this.kbsAccessKey = kbsAccessKey;
    this.cipherText   = cipherText;
  }

  public MasterKey getMasterKey() {
    return masterKey;
  }

  public byte[] getKbsAccessKey() {
    return kbsAccessKey;
  }

  public byte[] getCipherText() {
    return cipherText;
  }

  //AA+
  public String hexSerialisedCipherText()
  {
    return Utils.hexSerialise(cipherText);
  }
  //
}