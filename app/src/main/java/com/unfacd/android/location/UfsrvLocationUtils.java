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

package com.unfacd.android.location;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.provider.Settings;
import android.text.TextUtils;

import com.google.protobuf.ByteString;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.LocationCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.LocationRecord;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import io.nlopez.smartlocation.SmartLocation;

public class UfsrvLocationUtils
{
  private static final String TAG = Log.tag(UfsrvLocationUtils.class);

  public static @Nullable
  Long processUfsrvLocationCommand(@NonNull Context context,
                                   @NonNull SignalServiceContent content,
                                   @NonNull SignalServiceEnvelope envelope,
                                   @NonNull SignalServiceDataMessage message,
                                   boolean outgoing) throws MmsException
  {
    LocationCommand locationCommand = message.getUfsrvCommand().getLocationCommand();

    if (locationCommand == null) {
      Log.e(TAG, String.format("processUfsrvLocationCommand (%d): Command was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (locationCommand.getHeader().getCommand())
    {
      case LocationCommand.CommandTypes.LOCATION_VALUE:
        return processLocationCommandLocation(context, envelope, message);

      case LocationCommand.CommandTypes.ADDRESS_VALUE:
//        return processReceiptCommandDelivery(context, envelope, message, recipientOriginator);

      default:
        Log.d(TAG, String.format("processUfsrvLocationCommand (type:'%d'): Received UNKNOWN LOCATION COMMAND TYPE", locationCommand.getHeader().getCommand()));
    }

    return (long) -1;
  }

  private static @Nullable
  Long processLocationCommandLocation(@NonNull Context context,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message)
  {
    LocationCommand   locationCommand = message.getUfsrvCommand().getLocationCommand();
    LocationRecord    locationRecord  = locationCommand.getLocation();

    if (locationCommand.getLocation().getSource() == LocationRecord.Source.BY_SERVER) {
      ufLocation location = ufLocation.getInstance();
      location.setCurrentAddressByServer(locationRecord.getCountry(), locationRecord.getAdminArea(), locationRecord.getLocality());
      location.setCurrentLocationByServer(locationRecord.getLongitude(), locationRecord.getLatitude());
//      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1(UfsrvEventsNames.EVENT_LOCATIONCHANGE, wsm));
    } else {
      Log.d(TAG, "processLocationCommandLocation: client location NOT updated...");
    }


    return (long) -1;
  }

  public static UfsrvCommand
  buildLocationCommand (Context context, ufLocation location, long timeSentInMillis, LocationCommand.CommandTypes type, UfsrvCommand.TransportType transportType)
  {
    LocationCommand.Builder locationBuilder = buildLocation(context, location, timeSentInMillis, type);

    if (locationBuilder != null) return (new UfsrvCommand(locationBuilder.build(), false, transportType));

    return null;
  }

  public static LocationCommand.Builder
  buildLocation (Context context, ufLocation location, long timeSentInMillis, LocationCommand.CommandTypes commandType)
  {
    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder          = SignalServiceProtos.CommandHeader.newBuilder();
    LocationCommand.Builder   locationCommandBuilder    = LocationCommand.newBuilder();

    commandHeaderBuilder.setCommand(commandType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setUname(TextSecurePreferences.getUfsrvUsername(context));
    commandHeaderBuilder.setCid(Long.valueOf(TextSecurePreferences.getUfsrvCid(context)));
    commandHeaderBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    locationCommandBuilder.setHeader(commandHeaderBuilder.build());

    locationCommandBuilder.setLocation(UfsrvLocationUtils.buildLocationRecord(location));

    return locationCommandBuilder;
  }

  private static LocationRecord buildLocationRecord (ufLocation location)
  {
    LocationRecord.Builder locationBuilder = SignalServiceProtos.LocationRecord.newBuilder();

    JsonEntityLocation myLocation = new JsonEntityLocation(location);
    if (myLocation.getStatus() != JsonEntityLocation.LOCSTATUS_UNKNOWN) {
      locationBuilder.setSource(SignalServiceProtos.LocationRecord.Source.BY_USER);
      locationBuilder.setBaseloc(myLocation.getBaseloc());

      Location loc = location.getMyLocation();
      if (myLocation.getStatus() == JsonEntityLocation.LOCSTATUS_KNOWN) {
        android.location.Address address = location.getMyAddress();

        if (loc != null) {
          locationBuilder.setLatitude(loc.getLatitude());
          locationBuilder.setLongitude(loc.getLongitude());

          if (address != null) {
            locationBuilder.setAdminArea(!TextUtils.isEmpty(address.getAdminArea())? address.getAdminArea() : "");
            locationBuilder.setCountry(!TextUtils.isEmpty(address.getCountryName())? address.getCountryName() : "");
            locationBuilder.setLocality(!TextUtils.isEmpty(address.getLocality())? address.getLocality() : "");
          }
        }
      } else {
        locationBuilder.setLatitude(loc.getLatitude());
        locationBuilder.setLongitude(loc.getLongitude());
      }
    } else {
      locationBuilder.setSource(LocationRecord.Source.BY_UNDEFINED);
    }

    return locationBuilder.build();
 }

  public static boolean isMockLocationOn(Context context) {
    if (Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION).equals("0"))
      return false;
    else
      return true;
  }

  // Executes the specified string command in a separate process
  private static boolean canExecuteCommand(String command) {
    boolean executedSuccesfully;
    try {
      Runtime.getRuntime().exec(command);
      executedSuccesfully = true;
    } catch (Exception e) {
      executedSuccesfully = false;
    }
    return executedSuccesfully;
  }

  public static ArrayList getListOfMockPermissionApps(Context context) {
    ArrayList mockPermissionApps = new ArrayList();
    PackageManager pm = context.getPackageManager();
    List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
    //List<ApplicationInfo> packages = context.getPackageManager().getInstalledApplications(0);

    for (ApplicationInfo applicationInfo : packages) {
      try {
        PackageInfo packageInfo = pm.getPackageInfo(
                applicationInfo.packageName,
                PackageManager.GET_PERMISSIONS);

        // Get Permissions
        String[] requestedPermissions = packageInfo.requestedPermissions;

        if (requestedPermissions != null) {
          for (int i = 0; i < requestedPermissions.length; i++) {
            if (requestedPermissions[i]
                    .equals("android.permission.ACCESS_MOCK_LOCATION")
                    && !applicationInfo.packageName.equals(context
                    .getPackageName())) {
              mockPermissionApps.add(applicationInfo.packageName);
            }
          }
        }
      } catch (PackageManager.NameNotFoundException e) {
      }
    }
    return mockPermissionApps;
  }


  public static boolean isLocationPermissionsGranted(@NonNull Context context)
  {
    return  ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean isLocationServiceAvailable() {
    return  SmartLocation.with(ApplicationDependencies.getApplication()).location().state().locationServicesEnabled() &&
            SmartLocation.with(ApplicationDependencies.getApplication()).location().state().isAnyProviderAvailable()  &&
            SmartLocation.with(ApplicationDependencies.getApplication()).location().state().isGpsAvailable()          &&
            SmartLocation.with(ApplicationDependencies.getApplication()).location().state().isNetworkAvailable()      &&
            SmartLocation.with(ApplicationDependencies.getApplication()).location().state().isPassiveAvailable();

//    SmartLocation.with(ApplicationDependencies.getApplication()).location().state().isMockSettingEnabled(); //deprecated
  }
}

