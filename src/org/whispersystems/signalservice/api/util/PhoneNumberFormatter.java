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
package org.whispersystems.signalservice.api.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

import org.whispersystems.libsignal.logging.Log;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phone number formats are a pain.
 *
 * @author Moxie Marlinspike
 *
 */
public class PhoneNumberFormatter {

  private static final String TAG = PhoneNumberFormatter.class.getSimpleName();

  private static final String COUNTRY_CODE_BR = "55";
  private static final String COUNTRY_CODE_US = "1";

  public static boolean isValidNumber(String e164Number, String countryCode) {
    if (!PhoneNumberUtil.getInstance().isPossibleNumber(e164Number, countryCode)) {
      Log.w(TAG, "Failed isPossibleNumber()");
      return false;
    }

    if (COUNTRY_CODE_US.equals(countryCode) && !Pattern.matches("^\\+1\\d{10}$", e164Number)) {
      Log.w(TAG, "Failed US number format check");
      return false;
    }

    if (COUNTRY_CODE_BR.equals(countryCode) && !Pattern.matches("^\\+55\\d{2}9?\\d{8}$", e164Number)) {
      Log.w(TAG, "Failed Brazil number format check");
      return false;
    }

    return e164Number.matches("^\\+[0-9]{10,}")  ||
            e164Number.matches("^\\+685[0-9]{5}") ||
            e164Number.matches("^\\+376[0-9]{6}") ||
            e164Number.matches("^\\+299[0-9]{6}") ||
            e164Number.matches("^\\+597[0-9]{6}") ||
            e164Number.matches("^\\+298[0-9]{6}") ||
            e164Number.matches("^\\+240[0-9]{6}") ||
            e164Number.matches("^\\+687[0-9]{6}") ||
            e164Number.matches("^\\+689[0-9]{6}");
  }

  private static String impreciseFormatNumber(String number, String localNumber)
      throws InvalidNumberException
  {
    number = number.replaceAll("[^0-9+]", "");

    if (number.charAt(0) == '+')
      return number;

    if (localNumber.charAt(0) == '+')
      localNumber = localNumber.substring(1);

    if (localNumber.length() == number.length() || number.length() > localNumber.length())
      return "+" + number;

    int difference = localNumber.length() - number.length();

    return "+" + localNumber.substring(0, difference) + number;
  }

  public static String formatNumberInternational(String number) {
    try {
      PhoneNumberUtil util     = PhoneNumberUtil.getInstance();
      PhoneNumber parsedNumber = util.parse(number, null);
      return util.format(parsedNumber, PhoneNumberFormat.INTERNATIONAL);
    } catch (NumberParseException e) {
      Log.w(TAG, e);
      return number;
    }
  }

  public static String formatNumber(String number, String localNumber)
      throws InvalidNumberException
  {
    if (number == null) {
      throw new InvalidNumberException("Null String passed as number.");
    }

    if (number.contains("@")) {
      throw new InvalidNumberException("Possible attempt to use email address.");
    }

    number = number.replaceAll("[^0-9+]", "");

    if (number.length() == 0) {
      throw new InvalidNumberException("No valid characters found.");
    }

    //https://github.com/WhisperSystems/libsignal-service-java/commit/9b8d34bf2e962a3aabb8d1ed8099154e4ef15304
    //if (number.charAt(0) == '+')
      //return number;

    try {
      PhoneNumberUtil util          = PhoneNumberUtil.getInstance();
      PhoneNumber localNumberObject = util.parse(localNumber, null);

      String localCountryCode       = util.getRegionCodeForNumber(localNumberObject);

      PhoneNumber numberObject      = util.parse(number, localCountryCode);
      return util.format(numberObject, PhoneNumberFormat.E164);
    } catch (NumberParseException e) {
      //Log.w(TAG, ">> formatNumber(): ERROR: couldnot format number: '"+number+"'  "+e);
      return impreciseFormatNumber(number, localNumber);
    }
  }

  public static String getRegionDisplayName(String regionCode) {
    return (regionCode == null || regionCode.equals("ZZ") || regionCode.equals(PhoneNumberUtil.REGION_CODE_FOR_NON_GEO_ENTITY))
        ? "Unknown country" : new Locale("", regionCode).getDisplayCountry(Locale.getDefault());
  }

  public static String formatE164(String countryCode, String number) {
    try {
      PhoneNumberUtil util     = PhoneNumberUtil.getInstance();
      int parsedCountryCode    = Integer.parseInt(countryCode);
      PhoneNumber parsedNumber = util.parse(number,
                                            util.getRegionCodeForCountryCode(parsedCountryCode));

      return util.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    } catch (NumberParseException | NumberFormatException npe) {
      Log.w(TAG, npe);
    }

    return "+"                                                     +
        countryCode.replaceAll("[^0-9]", "").replaceAll("^0*", "") +
        number.replaceAll("[^0-9]", "");
  }

  public static String getInternationalFormatFromE164(String e164number) {
    try {
      PhoneNumberUtil util     = PhoneNumberUtil.getInstance();
      PhoneNumber parsedNumber = util.parse(e164number, null);
      return util.format(parsedNumber, PhoneNumberFormat.INTERNATIONAL);
    } catch (NumberParseException e) {
      Log.w(TAG, e);
      return e164number;
    }
  }


}
