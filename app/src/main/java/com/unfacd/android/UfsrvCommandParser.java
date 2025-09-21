/**
 * Copyright (C) 2016-2019 unfacd works
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

package com.unfacd.android;

import android.content.Context;
import android.location.Address;
import android.location.Location;
import android.text.TextUtils;

import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.data.json.JsonEntityStateSync;
import com.unfacd.android.jobs.LocationRefreshJob;
import com.unfacd.android.location.JsonEntityLocation;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.events.FenceV1;
import com.unfacd.android.ufsrvcmd.events.LocationV1;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;
import com.unfacd.android.ufsrvcmd.events.StateSyncV1SystemEvent;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvFenceUtils;
import com.unfacd.android.utils.UserPrefsUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.PushNotificationReceiveJob;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;

public class UfsrvCommandParser
{
  private static final String TAG = Log.tag(UfsrvCommandParser.class);

  private final Object parserLock = new Object();

  public interface CommandParser
  {
    void parse (WebSocketProtos.WebSocketMessage object) throws InvalidProtocolBufferException;
  }

  public UfsrvCommandParser()
  {
  }

  HashMap<String, CommandParser> ufsrvCommandParserMap = new HashMap<String, CommandParser>()
  {
    {
      //Ignored events
      put("idle", null);

      put("/v1/VerifyNewAccount", (wsm) -> {
      });

      put("/v1/keepalive", (wsm) -> {
      });

      put("/V1/ActivityState", (wsm) -> {
      });

      put("/v1/SetAccountAttributes", (wsm) -> {
      });

      put("/V1/Location", (wsm) -> {
        if (isMessageTypeResponse(wsm)) {
          try {
            JsonEntityLocation jLoc = JsonUtil.fromJson(wsm.getResponse().getMessage(), JsonEntityLocation.class);
            if (jLoc.getOrigin().equals("server")) {
              ufLocation.getInstance().setCurrentAddressByServer(jLoc.getCountry(), jLoc.getAdminArea(), jLoc.getLocality());
              ufLocation.getInstance().setCurrentLocationByServer(jLoc.getLongitude(), jLoc.getLatitude());
              //Log.d(TAG, "parseMap: loc_updade: setCurrentAddressByServer initiate: longitude: " + object.getString("longitude") + " latitude: " + object.getString("latitude"));
              //Log.d(TAG, "parseMap: loc_updade: setCurrentAddressByServer initiate: longitude: " + ufLocation.getInstance().getCurrentLocationByServer().getLongitude() + " latitude: " + ufLocation.getInstance().getCurrentLocationByServer().getLatitude() + " country: " + ufLocation.getInstance().getCurrentAddressByServer().getCountryName());
            } else {
              Log.d(TAG, "/v1/Location: client location NOT updated...");
            }

            ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1(UfsrvEventsNames.EVENT_LOCATIONCHANGE, wsm));
          } catch (IOException ex) {
            Log.d(TAG, ex.getMessage());
          }
        } else if (wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) {
          //todo:
          //send curret location info
        }
      });

      put("/V1/Fence", (wsm) -> {
      });

      put("/v1/StateSync", (wsm) -> {
        Context context = ApplicationContext.getInstance();

        try {
          JsonEntityStateSync state = JsonUtil.fromJson(wsm.getRequest().getPath(), JsonEntityStateSync.class);
          if (state.getSessionState() >= 1) {
             Log.e(TAG, String.format(Locale. getDefault(), "/v1/StateSync: ERROR SessionState is set to: '%d', cookie:'%s'", state.getSessionState(), TextSecurePreferences.getUfsrvCookie(context)));
            ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new StateSyncV1SystemEvent(UfsrvEventsNames.EVENT_STATESYNC, state));
            return;
          }

          Recipient thisRecipient = Recipient.live(state.getUfsrvUid()).get();
          TextSecurePreferences.setUfsrvNickname(context, state.getNickname());
          if (!thisRecipient.isUndefined())  SignalDatabase.recipients().setNickname(thisRecipient, state.getNickname());
          else {
             Log.e(TAG, String.format(Locale.getDefault(), "/v1/StateSync: COULD NOT LOAD RECIPIENT FOR SELF (uid:'%s')", state.getUfsrvUid()));
          }
          UfsrvUid ufsrvUid = new UfsrvUid(state.getUfsrvUid());
          TextSecurePreferences.setUserId(context, ufsrvUid.getUfsrvSequenceId());
          TextSecurePreferences.setUfsrvUserId(context, state.getUfsrvUid());
          TextSecurePreferences.setUfsrvServerId(context, state.getSid());
          TextSecurePreferences.setUfsrvCid(context, state.getCid());

          if (state.getQueue_size() > 0) {
            Log.e(TAG, String.format(Locale.getDefault(), "/v1/StateSync: QueueSize ('%d')", state.getQueue_size()));
            ApplicationDependencies.getJobManager().add(new PushNotificationReceiveJob(true));
          }

          JsonEntityLocation jLoc = state.getLocation();
          if (jLoc != null) {
            String country    = jLoc.getCountry();
            String adminArea  = jLoc.getAdminArea();
            String locality   = jLoc.getLocality();

            if (!TextUtils.isEmpty(country)) {
              if (jLoc.getOrigin().equals("server")) {
                ufLocation.getInstance().setCurrentAddressByServer(jLoc.getCountry(), jLoc.getAdminArea(), jLoc.getLocality());
                ufLocation.getInstance().setCurrentLocationByServer(jLoc.getLongitude(), jLoc.getLatitude());

                //if we have our own location state initialised get the server to update its view
                if (ufLocation.getInstance().isInitialised()) {
                  Log.d(TAG, "/v1/StateSync: Syncing Server's location view using client's data...");
                  ApplicationDependencies.getJobManager().add(new LocationRefreshJob());
                }

                ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
              } else if (jLoc.getOrigin().equals("client")) {
                //ensure client's view are the same if that's what the server is reporting
                if (ufLocation.getInstance().isCurrentAddressKnown()) {
                  Address geoAddress = ufLocation.getInstance().getCurrentAddress();
                  //todo this logic is on the flawed side eg no check is performed if provided locality is not set, when own locality is not set
                  boolean isCountryMatching = !TextUtils.isEmpty(geoAddress.getCountryName()) && geoAddress.getCountryName().equalsIgnoreCase(country);
                  boolean isAdminAreaMatching = !TextUtils.isEmpty(geoAddress.getAdminArea()) && geoAddress.getAdminArea().equalsIgnoreCase(adminArea);
                  boolean islocalityMatching = !TextUtils.isEmpty(geoAddress.getLocality())   && geoAddress.getLocality().equalsIgnoreCase(locality);

                  if (!isCountryMatching || !isAdminAreaMatching || !islocalityMatching) {
                    Log.d(TAG, "/v1/StateSync: Syncing Server's client location view using client's data...");
                    ApplicationDependencies.getJobManager().add(new LocationRefreshJob());
                  }
                } else {
                  //client sent own location, yet internal state is not set as initialised
                  Log.e(TAG, "/v1/StateSync: INCONSISTENT LOCATION STATE: INTERNAL STATE REPORTED AS NOT INITIALISED YET SERVER LOCATION RECORD IS OF CLIENT ORIGIN");
                  Location  location  = new Location("");
                  location.setLatitude(jLoc.getLatitude());
                  location.setLongitude(jLoc.getLongitude());
                  ufLocation.getInstance().setCurrentLocation(location);

                  Address address = new Address(ApplicationContext.getInstance().getResources().getConfiguration().locale);
                  address.setCountryName(jLoc.getCountry());
                  address.setAdminArea(jLoc.getAdminArea());
                  address.setLocality(jLoc.getLocality());
                  ufLocation.getInstance().setCurrentAddress(address);

                  ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
                }
              }
            } else {
              Log.w(TAG, "/v1/StateSync: Location data inconsistent: missing Country designation...");
            }
          }

          UfsrvFenceUtils.synchroniseFenceList(state.getFences());
          UfsrvFenceUtils.synchroniseInvitedFenceList(state.getFencesInvited());
          UserPrefsUtils.synchroniseUserPreferences(state.getUserPrefs());
          UserPrefsUtils.synchroniseSharedLists(Optional.ofNullable(state.getShared_lists()));

          ApplicationContext.getInstance().getUfsrvcmdEvents().post(new FenceV1(UfsrvEventsNames.EVENT_STATESYNC, wsm));
        } catch (IOException ex) {
          Log.d(TAG, ex.getMessage());
        }
      });
    }
  };

  public void invokeUfsrvCommandHandler(WebSocketProtos.WebSocketMessage wsm) throws InvalidProtocolBufferException
  {
    String ufsrvCommand = wsm.getCommand();
    Log.w(TAG, "invokeUfsrvCommandHandler: retrieving parser for command: '"+ufsrvCommand+"'...");

    CommandParser p = ufsrvCommandParserMap.get(ufsrvCommand);

    if (p != null) {
      synchronized (parserLock) {
        p.parse(wsm);
      }
    } else {
      Log.w(TAG, "Unhandled Ufsrv command : " + ufsrvCommand);
      if (!ufsrvCommandParserMap.containsKey(ufsrvCommand)) {
        //Log.w(TAG, "Unhandled Ufsrv command : " + ufsrvCommand);
      }
    }

  }

  public boolean isMessageTypeRequest(WebSocketProtos.WebSocketMessage wsm)
  {
    return wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE;
  }

  public boolean isMessageTypeResponse(WebSocketProtos.WebSocketMessage wsm)
  {
    return wsm.getType().getNumber() == WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE;
  }
}
