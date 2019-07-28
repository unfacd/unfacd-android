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

import android.location.Address;
import android.location.Location;
import org.thoughtcrime.securesms.logging.Log;

import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.Geofence;
import com.unfacd.android.ApplicationContext;

import org.thoughtcrime.securesms.util.TextSecurePreferences;

public class ufLocation
{
  static private final String TAG = ufLocation.class.getSimpleName();

  private static ufLocation instance = null;

  public enum LocationLevel {
    SELF_ZONED(0),
    LOCALITY(1),
    ADMINISTRATIVE(2),
    COUNTRY (3);

    private int value;

    LocationLevel(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  //set at initialisation from Lastknown
  private static Location lastLocation = null;
  private static Address lastAddress = null;

  //this is client's view only of its own location
  private static boolean isInitialised = false;
  private static boolean isCurrentLocationKnown = false;
  private static boolean isCurrentAddressKnown = false;
  private static Location currentLocation = null;
  private static Address currentAddress = null;

  //location info provided by server
  private static Address currentAddressByServer = null;
  private static Location currentLocationByServer = null;
  private static boolean isByServerInitialised = false;
  private static boolean isCurrentLocationByServerKnown = false;
  private static boolean isCurrentAddressByServerKnown = false;

  private static String currentActivity = null;
  private static String currentActivityConfidence = null;

  //user chosen homebaseloc which can always be defaulted to
  private static Location BaseLocation = null;
  private static Address BaseAddress = null;
  private static String baseLocationPrefix = null;
  private static boolean baseLocationPrefixDirty = false;//temporary hack to indicate if set by user. shoud be saved in preferences

  //supports creating a user specific domain after location locality prefix Country:admin area:locality:username:fence_name
  private static boolean selfZoned=false;

  private static String selfZoneName="";//empty

  //where in the basloc are we pegged: country x:::0: most inclusive, least inclusive x:y:z:u: only user's own zone
  private static int baselocStopMark=4;//include the value for selfzone

  //reserved
  private final static String failoverBaseloc ="unfacd:::0:";

  public void ufLocation ()
  {
        /*empty constructor*/
  }//

  public static ufLocation getInstance ()
  {
    if (instance != null) {
      return instance;
    } else {
      instance = new ufLocation();
      currentLocationByServer = new Location("");
      isCurrentLocationByServerKnown = false;
      selfZoneName=TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance());
      if (selfZoneName==null) selfZoneName="";

      Log.d(TAG, "ufLocation:getInstance() Singleton created");

      return instance;
    }

  }

  public void sanitiseLocationContext ()
  {
    if (currentLocation!=null)              isCurrentLocationKnown = true;
    else if (currentLocationByServer!=null) isCurrentLocationKnown  = true;
    else if (lastLocation!=null) {
      currentLocation         = lastLocation;
      isCurrentLocationKnown  = true;
    }
    if (currentAddress!=null)               isCurrentAddressKnown = true;
    else if (currentAddressByServer!=null)  isCurrentAddressKnown = true;
    else if (lastAddress!=null) {
      currentAddress        = lastAddress;
      isCurrentAddressKnown = true;
    }


    if (isCurrentAddressKnown && isCurrentAddressKnown)
    {
      makeBaseLocationPrefix(currentAddress, this.baselocStopMark);
      isInitialised = true;
    }
  }

  //only set if client has been able to determine valid location by itself
  private synchronized void setInitialised (Location inL)
  {

    if (inL != null)
    {
      //initially both are initialised to the same value, overtime, they will contain two values last know(current) and known (which is used now)
      ufLocation.lastLocation = inL;
      ufLocation.currentLocation = inL; //TODO: revise
      isCurrentLocationKnown = true;
      isInitialised = true;

      Log.d(TAG, ">> setInitialised seeding with last known Location value of: " + describeThisLocation(ufLocation.getInstance().currentLocation));
    }
    else
    {
      Log.d(TAG, ">> setInitialised was passed a null Location object or method called more than once");
    }
  }

  //only set if client has been able to determine valid location by itself
  public boolean isInitialised ()
  {
    return isInitialised;

  }

  //as set by last known available server
  public String getBaseLocationPrefix ()
  {

    return (baseLocationPrefix==null)?failoverBaseloc:baseLocationPrefix;
  }

  public void setBaseLocationPrefix (String baseLocIn)
  {
    if (baseLocIn != null && !baseLocIn.isEmpty())
    {
      baseLocationPrefix = baseLocIn;
    }
  }

  public boolean isSelfZoned ()
  {
    return selfZoned;
  }

  public String getSelfZoneName ()
  {
    return selfZoneName;
  }

  //automatically invoked everytime an address is provided. If address is not provided it seeds which one is avaible, client->server
  ////TODO: the stop mark should be preference driven as set by user, rrently it dfaults to ocal area level
  static void makeBaseLocationPrefix (Address addressIn, int stopMark)
  {
    //fully qualifed: country:admin:locality:username:

    //priority given to client's view
    Address myA = (addressIn != null) ? addressIn : ((currentAddress!=null)?currentAddress:currentAddressByServer);

    if (myA==null)
    {
      Log.d(TAG, "makeBaseLocationPrefix: COULD NOT OBTAIN A VALID Address: current baselocprefix: '"+((baseLocationPrefix!=null)?baseLocationPrefix:"")+"'");

      return;
    }

    String baseLocationPrefixx;

    if (myA!=null && !myA.getCountryName().isEmpty())
    {
      baseLocationPrefixx = myA.getCountryName() + ":";//x:
    }
    else
    {
      baseLocationPrefixx = ":";//this is in valid case
    }

    if (stopMark == 1)
    {
      baseLocationPrefixx = baseLocationPrefixx + ":::";
      baseLocationPrefix=baseLocationPrefixx;

      return;
    }//x::::

    //input: 'x:' or ':'
    if (myA.getAdminArea() != null && !myA.getAdminArea().isEmpty())
    {
      baseLocationPrefixx = baseLocationPrefixx + myA.getAdminArea() + ":";//x:x:
    }
    else
    {
      baseLocationPrefixx = baseLocationPrefixx + ":";//x::
    }

    if (stopMark == 2)
    {
      baseLocationPrefix=baseLocationPrefixx+"::";
      return;
    }

    //input: 'x:y:' or 'x::'
    if (myA.getLocality() != null && !myA.getLocality().isEmpty())
    {
      baseLocationPrefixx = baseLocationPrefixx + myA.getLocality()+":"; //x:x:x:
    }
    else baseLocationPrefixx=baseLocationPrefixx+":";//x:x:x: or //x:::


    if (stopMark==3)
    {
      baseLocationPrefix = baseLocationPrefixx+":";
      return;
    }

    //input: 'x:y:z:' or 'x::z:' or 'x:::'
    if (selfZoned)
    {
      baseLocationPrefix=baseLocationPrefixx+selfZoneName+":";
    }
    else
      baseLocationPrefix=baseLocationPrefixx+"0:";

    //final: 'x:y:z:u:' or other valid permutaions as per above 'x:::u:' ':::u:'
    Log.d(TAG, String.format("makeBaseLocationPrefix: set baseLocationPrefix to: '%s'", baseLocationPrefix));

  }

  public void setBaseLocationPrefixDirty (boolean value)

  {
    baseLocationPrefixDirty = value;
  }

  public boolean isCurrentAddressKnown ()
  {
    return isCurrentAddressKnown;
  }

  public boolean isCurrentLocationKnown ()
  {
    return isCurrentLocationKnown;
  }

  // -----------------------------------


  /* client view, as set by onLocation change handler*/
  public synchronized void setCurrentLocation (Location inLocation)
  {
    if (inLocation != null)
    {
      //at this stage set set it if we lose connectivity
      if (!isInitialised())
      {
        setInitialised(inLocation);
      }
      lastLocation = currentLocation;
      currentLocation = inLocation;
      isCurrentLocationKnown = true;

      return;
    }

    Log.d(TAG, ">> setKnownLocation: contained null Location value");

  }

  //currently tracked by the system
  public Location getCurrentLocation ()
  {
    return currentLocation;

  }

  //as per above client's view
  public synchronized void setCurrentAddress (Address myAddress)
  {
    if (myAddress != null) {
      lastAddress = currentAddress;
      currentAddress = myAddress;
      isCurrentAddressKnown = true;

      if (!baseLocationPrefixDirty) {
        makeBaseLocationPrefix(currentAddress, this.baselocStopMark);
      }
    }
  }

  //location as seen by client
  public Address getCurrentAddress ()
  {
    return currentAddress;
  }

// Multiplexing views

  //multiplexes the value of Location based on what is known
  public Location getMyLocation ()
  {
    if (isCurrentLocationKnown)
    {
      return currentLocation;
    }

    //todo: last known location
    if (isCurrentLocationByServerKnown)
    {
      return currentLocationByServer;
    }

    return new Location("");
    //return null;
  }

  //multiplexes the value of Address based on what is known
  public Address getMyAddress ()
  {
    if (isCurrentAddressKnown)
    {
      return currentAddress;
    }

    //todo: last known location
    if (isCurrentAddressByServerKnown)
    {
      return currentAddressByServer;
    }

    return null;
  }

  //

  // -------------------- server side -----------

  public synchronized void setCurrentLocationByServer (double longitude, double latitude)
  {
    currentLocationByServer.setLongitude(longitude);
    currentLocationByServer.setLatitude(latitude);

    isCurrentLocationByServerKnown = true;

    //we cannot do that as that overrides the client's side
    /*if (!isInitialised())
    {
      setInitialised(currentLocationByServer);
    }*/

  }

  public static Location getCurrentLocationByServer ()
  {
    return currentLocationByServer;
  }

  public synchronized void setCurrentAddressByServer (String country, String admin_area, String locality)
  {
    currentAddressByServer = new Address(ApplicationContext.getInstance().getResources().getConfiguration().locale);
    currentAddressByServer.setCountryName(country);
    currentAddressByServer.setAdminArea(admin_area);
    currentAddressByServer.setLocality(locality);
    isCurrentAddressByServerKnown = true;

    if (!baseLocationPrefixDirty) {
      makeBaseLocationPrefix(null, this.baselocStopMark);//TODO: the stop mark should be preference driven as set by user
    }

    isByServerInitialised = true;
  }

  public Address getCurrentAddressByServer ()
  {
    return currentAddressByServer;
  }

  public static boolean isCurrentLocationByServerKnown ()
  {
    return isCurrentLocationByServerKnown;
  }

  public static boolean isCurrentAddressByServerKnown ()
  {
    return isCurrentAddressByServerKnown;
  }

  public boolean isByServerInitialised ()
  {
    return isByServerInitialised;
  }
  // -------------------------------------

  public synchronized String describeThisLocation (Location inLocation)
  {
    if (inLocation != null)
    {
      return String.format("Latitude %.6f, Longitude %.6f", inLocation.getLatitude(),
              inLocation.getLongitude());
    }
    return null;
  }

  public synchronized String describeCurrentLocation ()
  {
    if (currentLocation != null)
    {
      return String.format("Latitude %.6f, Longitude %.6f", currentLocation.getLatitude(),
              currentLocation.getLongitude());
    }

    return null;
  }

  public String describeMyLocality ()
  {
    return getMyAddress()!=null?getMyAddress().getLocality():"";
  }

  public String describeMyAdminArea ()
  {
    return getMyAddress()!=null?getMyAddress().getAdminArea():"";
  }

  public String describeMyCountry ()
  {
    return getMyAddress()!=null?getMyAddress().getCountryName():"";
  }

  public synchronized String describeLastLocation ()
  {
    if (lastLocation != null)
    {
      return String.format("Latitude %.6f, Longitude %.6f", lastLocation.getLatitude(),
              lastLocation.getLongitude());
    }

    return null;
  }

  public String getCurrentActivity ()
  {
    return ufLocation.currentActivity;

  }

  public void setCurrentActivity (String inActivity)
  {
    ufLocation.currentActivity = inActivity;

  }

  public String getCurrentActivityConfidence ()
  {
    return ufLocation.currentActivityConfidence;

  }

  public void setCurrentActivityConfidence (String inActivity)
  {
    ufLocation.currentActivityConfidence = inActivity;

  }

  /* must be used with setting current activity for mats the value*/
  public String describeActivity (DetectedActivity inActivity)
  {
    String t = "_unset_";

    if (inActivity != null)
    {
      switch (inActivity.getType())
      {
        case DetectedActivity.IN_VEHICLE:
          t = "in_vehicle";
          break;
        case DetectedActivity.ON_BICYCLE:
          t = "on_bicycle";
          break;
        case DetectedActivity.ON_FOOT:
          t = "on_foot";
          break;
        case DetectedActivity.STILL:
          t = "still";
          break;
        case DetectedActivity.TILTING:
          t = "tilting";
          break;
        default:
          return "unknown";
      }

    }
    Log.i(TAG, ">>uf Activity type:" + t);

    return t;

  }

  /* format the value for confidence. Must be used with seeter method */
  public String describeActivityConfidence (DetectedActivity inActivity)
  {
    String t = "_unset_";

    if (inActivity != null)
    {
      t = String.valueOf(inActivity.getConfidence());
    }
    Log.i(TAG, ">>uf Confidence: %" + t);

    return t;

  }


  public void showGeofence (Geofence geofence, int transitionType)
  {
    if (geofence != null)
    {
      Log.i(TAG, ">>uf Location Geofencing:" + getTransitionNameFromType(transitionType) +
              " for Geofence with id = "
              + geofence.getRequestId());
    }
    else
    {
      Log.i(TAG, ">>uf Location Geofencoing null");
    }

  }

  public static String getTransitionNameFromType (int transitionType)
  {
    switch (transitionType)
    {
      case Geofence.GEOFENCE_TRANSITION_ENTER:
        return "enter";
      case Geofence.GEOFENCE_TRANSITION_EXIT:
        return "exit";
      default:
        return "dwell";
    }

  }

}
