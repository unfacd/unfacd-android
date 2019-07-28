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
package org.whispersystems.signalservice.internal.util;

import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.libsignal.util.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Trust manager that defers to a system X509 trust manager, and
 * additionally rejects certificates if they have a blacklisted
 * serial.
 *
 * @author Moxie Marlinspike
 */
public class BlacklistingTrustManager implements X509TrustManager {

  //TODO: AA look into name assignment
  private static final List<Pair<String, BigInteger>> BLACKLIST = new LinkedList<Pair<String, BigInteger>>() {{
    add(new Pair<>("Open Whisper Systems", new BigInteger("4098")));
  }};

  public static TrustManager[] createFor(TrustManager[] trustManagers) {
    for (TrustManager trustManager : trustManagers) {
      if (trustManager instanceof X509TrustManager) {
        TrustManager[] results = new BlacklistingTrustManager[1];
        results[0] = new BlacklistingTrustManager((X509TrustManager)trustManager);

        return results;
      }
    }

    throw new AssertionError("No X509 Trust Managers!");
  }

  public static TrustManager[] createFor(TrustStore trustStore) {
    try {
      InputStream keyStoreInputStream = trustStore.getKeyStoreInputStream();
      KeyStore keyStore            = KeyStore.getInstance("BKS");

      keyStore.load(keyStoreInputStream, trustStore.getKeyStorePassword().toCharArray());

      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
      trustManagerFactory.init(keyStore);

      return BlacklistingTrustManager.createFor(trustManagerFactory.getTrustManagers());
    } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private final X509TrustManager trustManager;

  public BlacklistingTrustManager(X509TrustManager trustManager) {
    this.trustManager = trustManager;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException
  {
    trustManager.checkClientTrusted(chain, authType);
  }


  //https://github.com/WhisperSystems/libsignal-service-java/commit/43fc72d2f1245a6ec44bdf88d421ea5dac07436d
  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException
  {
//    trustManager.checkServerTrusted(chain, authType);
//
//    for (X509Certificate certificate : chain) {
//      for (Pair<String, BigInteger> blacklistedSerial : BLACKLIST) {
//        if (certificate.getIssuerDN().getStylisedName().equals(blacklistedSerial.first()) &&
//                certificate.getSerialNumber().equals(blacklistedSerial.second()))
//        {
//          throw new CertificateException("Blacklisted Serial: " + certificate.getSerialNumber());
//        }
//      }
//    }

  }

  // pre domain-fronting
//  @Override
//  public void checkServerTrusted(X509Certificate[] chain, String authType)
//      throws CertificateException
//  {
//    //
///*    trustManager.checkServerTrusted(chain, authType);
//
//    for (X509Certificate certificate : chain) {
//      for (BigInteger blacklistedSerial : BLACKLIST) {
//        if (certificate.getSerialNumber().equals(blacklistedSerial)) {
//          throw new CertificateException("Blacklisted Serial: " + certificate.getSerialNumber());
//        }
//      }
//    }*/
//
//  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return trustManager.getAcceptedIssuers();
  }
}
