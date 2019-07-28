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
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class utils
{

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

}

