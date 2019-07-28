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

package com.unfacd.android.ufsrvcmd.events;

import com.unfacd.android.ufsrvcmd.UfsrvCommandWebSocketEvent;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;


public class LocationV1 extends UfsrvCommandWebSocketEvent//AUfsrvCommandEvent
{
  public  LocationV1 (int cmd, WebSocketProtos.WebSocketMessage wsm)
  {
    super (cmd, wsm);
  }

  public  LocationV1 (int cmd, SignalServiceProtos.UfsrvCommandWire ufsrvCommand)
  {
    super (cmd, ufsrvCommand);
  }


}
