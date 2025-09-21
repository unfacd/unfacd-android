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

package com.unfacd.android.service;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.AsyncTask;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class ReverseGeocodingTask extends AsyncTask<Location, Void, Void>
{

  private final ReverseGeocodingListener mListener;
  private final Context mContext;
  private Address mAddress;

  public ReverseGeocodingTask (Context context, ReverseGeocodingListener listener)
  {
    mContext = context;
    mListener = listener;
  }

  @Override
  protected Void doInBackground (Location... locations)
  {
    Geocoder geocoder = new Geocoder(mContext, Locale.getDefault());

    Location loc = locations[0];
    List<Address> addresses = null;
    try {
      // get all the addresses for the given latitude, and longitude
      addresses = geocoder.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
    } catch (IOException e) {
      mAddress = null;
    }

    // if we have at least one address, use it
    if (addresses != null && addresses.size() > 0) {
      mAddress = addresses.get(0);
    }
    return null;
  }

  @Override
  protected void onPostExecute (Void aVoid)
  {
    // set the address on the UI thread
    mListener.onAddressAvailable(mAddress);
  }
}