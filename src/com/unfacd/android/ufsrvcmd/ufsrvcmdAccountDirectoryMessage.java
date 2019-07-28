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

import androidx.annotation.NonNull;


public class ufsrvcmdAccountDirectoryMessage
{
//  private static final String TAG = ufsrvcmdAccountDirectoryMessage.class.getSimpleName();
//  private WebSocketProtos.WebSocketMessage mWsm=null;
//  private long mCid=0;
//  private String  mNumber;
//  //this reprsents the original number-token associative map sent to the server
//  private Map<String, String> mContactTokensMap;
//
//  public ufsrvcmdAccountDirectoryMessage (WebSocketProtos.WebSocketMessage wsm,  Map<String, String> ContactTokensMap, String  number) throws InvalidProtocolBufferException
//  {
//
//      mNumber=number;
//      mContactTokensMap=ContactTokensMap;
//
//     /* if (wsm.getType().getNumber() != WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE)
//      {
//        throw new InvalidProtocolBufferException("object is not of type response: type: "+wsm.getType().getNumber()+" command: "+wsm.getCommand()+" id: "+ wsm.getResponse().getEncodedId());
//      }
//
//    this.mWsm=wsm;
//    Log.d(TAG, ">>>> protobuf command "+wsm.getCommand());*/
//  }
//
//  public List<String> getContacts () throws InvalidProtocolBufferException
//  {
//    if (this.mWsm!=null)
//    {
//      return getContacts(this.mWsm);
//    }
//
//    return null;
//
//  }
//
//
//  public List<String> getContacts (WebSocketProtos.WebSocketMessage wsm) throws InvalidProtocolBufferException
//  {
//    if (wsm.getType().getNumber() != WebSocketProtos.WebSocketMessage.Type.RESPONSE_VALUE)
//    {
//      throw new InvalidProtocolBufferException("object is not of type response: type: "+wsm.getType().getNumber()+" command: "+wsm.getCommand()+" id: "+ wsm.getResponse().getEncodedId());
//    }
//
//    try
//    {
//      ContactTokenDetailsList activeTokens=JsonUtil.fromJson(mWsm.getResponse().getMessage(), ContactTokenDetailsList.class);
//      return DirectoryHelper.refreshDirectory(ApplicationContext.getInstance(), mNumber/*"61414436433"*/, activeTokens.getContacts());
//
//      //return activeTokens.getContacts();
//    }
//    catch (IOException e)
//    {
//      org.whispersystems.libsignal.logging.Log.w(TAG, e);
//      //throw new NonSuccessfulResponseCodeException("Unable to parse entity");
//    }
//
//    return null;
//  }
//
//  public String getNumber ()
//  {
//    return  mNumber;
//  }
//
//  //Given the supplied list which is a subset from original  mContactTokensMap: for each token find the correspondig number in the associative map
//  public List<ContactTokenDetails> getNumbersForActiveTokens(List<ContactTokenDetails> activeTokens)
//  {
//    for (ContactTokenDetails activeToken : activeTokens) {
//      activeToken.setNumber(this.mContactTokensMap.get(activeToken.getToken()));
//    }
//
//    return activeTokens;
//  }
}
