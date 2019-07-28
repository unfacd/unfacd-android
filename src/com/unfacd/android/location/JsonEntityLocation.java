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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;


@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonEntityLocation
{
    public static final int LOCSTATUS_KNOWN=0;
    public static final int LOCSTATUS_PARTIALLY_KNOWN=1;
    public static final int LOCSTATUS_UNKNOWN=2;

    @JsonProperty
    private double longitude;

    @JsonProperty
    private double latitude;

    @JsonProperty
    private String country;

    @JsonProperty
    private String adminArea;

    @JsonProperty
    private String locality;

    @JsonProperty
    private String origin;

    @JsonProperty
    private int status;//0 complete, 1 partial ie we could not reverse geocode, server must perform that, 2 unknown

    @JsonProperty
    private boolean isSelfZoned;

    @JsonProperty
    private String selfZoneName;

    @JsonProperty
    private String baseloc;

    public JsonEntityLocation ()
    {

    }

    public JsonEntityLocation (ufLocation ufLoc)
    {
      if (ufLoc != null) {
        prepareLocation (ufLoc);
      }
    }

    private void prepareLocation (ufLocation ufLoc)
    {
        Location loc; Address addr;

        //we know we have something to get on by
        if (ufLoc.isInitialised() || ufLoc.isCurrentLocationByServerKnown() || ufLoc.isCurrentAddressByServerKnown())
        {
            int flag = 3;//assuming complete

            loc=ufLoc.getMyLocation();
            addr=ufLoc.getMyAddress();

            this.isSelfZoned =ufLoc.isSelfZoned();
            if (isSelfZoned())  this.selfZoneName=ufLoc.getSelfZoneName();

            longitude=loc.getLongitude();
            latitude=loc.getLatitude();

            if (ufLoc.isInitialised())  this.origin="client";
            else                        this.origin="server";

            this.baseloc=ufLoc.getBaseLocationPrefix();

            if (addr != null) {
                if (addr.getLocality() != null) {
                    locality=addr.getLocality();
                }
                else {
                    --flag;
                }
                if (addr.getCountryName() != null) {
                    country=addr.getCountryName();
                }
                else {
                    --flag;
                }
                if (addr.getAdminArea() != null) {
                    adminArea=addr.getAdminArea();
                }
                else {
                    --flag;
                }
            }
            else {
                ++flag;
            }

            if (flag == 0) {
                status=LOCSTATUS_PARTIALLY_KNOWN;
            }
            else {
                status=LOCSTATUS_KNOWN;
            }
        }
        else {
            //Log.d(TAG, "Location COULD NOT be determined: server will send back its best guess...");
            status=LOCSTATUS_UNKNOWN;
        }
    }

    public double getLongitude (){ return longitude;}

    public double getLatitude() {return latitude;}

    public String getCountry() {return country;}

    public String getAdminArea(){return adminArea;}

    public String getLocality() {return locality;}

    public String getOrigin() {return origin;}

    public boolean isSelfZoned ()
    {
        return isSelfZoned;
    }

    public String getSelfZoneName ()
    {
        return selfZoneName;
    }

    public String getBaseloc ()
    {
        return baseloc;
    }

    public int getStatus ()
    {
        return status;
    }
}
