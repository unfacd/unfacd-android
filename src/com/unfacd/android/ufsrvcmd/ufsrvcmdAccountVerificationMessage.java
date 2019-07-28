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

package com.unfacd.android.ufsrvcmd;

import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

public class ufsrvcmdAccountVerificationMessage
{
  private static final String TAG = ufsrvcmdAccountVerificationMessage.class.getSimpleName();
  private WebSocketProtos.WebSocketMessage mWsm=null;
  private long mCid=0;

  public ufsrvcmdAccountVerificationMessage (WebSocketProtos.WebSocketMessage wsm) throws InvalidProtocolBufferException
  {

      if (wsm.getType().getNumber() != WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE)
      {
        throw new InvalidProtocolBufferException("object is not of type response: type: "+wsm.getType().getNumber()+" command: "+wsm.getCommand()+" id: "+ wsm.getResponse().getId());
      }

    this.mWsm=wsm;
    Log.d(TAG, ">>>> protobuf command "+wsm.getCommand());

  }

  public boolean isVerified ()
  {
    if (this.mWsm!=null)
    {
      if (mWsm.getResponse().getStatus()==200)
      {
        return true;
      }
    }

    return false;
  }


  public long getCid ()
  {

    return mCid;
  }

}
