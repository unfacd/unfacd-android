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

package com.unfacd.android.utils;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.os.ResultReceiver;
import org.signal.core.util.logging.Log;

import com.ning.compress.lzf.LZFDecoder;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.data.json.JsonEntityFencesNearBy;

import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FencesSearch;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;

import java.io.IOException;


/**
 * This must be added to Manifest file
 *  <service
 android:name=".utils.IntentServiceGetFencesNearBy"
 android:exported="false"/>
 http://stackoverflow.com/questions/6343166/how-to-fix-android-os-networkonmainthreadexception
 */
public class IntentServiceSearchFences extends IntentService {

  private static final String TAG = Log.tag(IntentServiceGetFencesNearBy.class);

  public static final String PENDING_RESULT_EXTRA = "pending_result"; //the reply intent holder
  public static final String SEARCHTEXT_EXTRA = "searchtext_result";
  public static final String RESULT_RAW_RESPONSE_EXTRA = "raw_response"; //result back
  public static final String RESULT_CODE_EXTRA = "result_code"; //result back

  public static final int RESULT_CODE       = 0;
  public static final int INVALID_URL_CODE  = 1;
  public static final int ERROR_CODE        = 2;
  public static final int EMPTYSET_CODE     = 3;

  private PushServiceSocket socket;
  private String searchText;

  public IntentServiceSearchFences() {
    super(TAG);
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    int resultCode = 0;
    ResultReceiver  resultReceiver  = intent.getParcelableExtra(PENDING_RESULT_EXTRA);
    searchText = intent.getStringExtra(SEARCHTEXT_EXTRA);

    ApplicationContext.DynamicCredentialsProvider creds=new ApplicationContext.DynamicCredentialsProvider();
    SignalServiceNetworkAccess networkAccess= new SignalServiceNetworkAccess(ApplicationContext.getInstance());

    try {
      try {
        socket = new PushServiceSocket(networkAccess.getConfiguration(),
                                       new StaticCredentialsProvider(null, null, creds.getUser(), SignalServiceAddress.DEFAULT_DEVICE_ID, creds.getPassword(), null), BuildConfig.USER_AGENT, null, FeatureFlags.okHttpAutomaticRetry());

        Bundle bundle = new Bundle();
        String jsonResponse;

        if ((jsonResponse=socket.SearchfencesByName(searchText))!=null)
        {
          JsonEntityFencesNearBy jsonEntityFencesNearBy = JsonUtil.fromJson(jsonResponse, JsonEntityFencesNearBy.class);
          if (jsonEntityFencesNearBy.getSuccess()>0)
          {
            Log.d(TAG, String.format("Received json payload: '%s'", jsonEntityFencesNearBy.getPayload()));
            if (false)//with compression
            {
              byte[] fencesNearByCompressedPayload = b64DecodeResponse(jsonEntityFencesNearBy.getPayload());
              byte[] fencesNearByRawBytes = LZFDecoder.decode(fencesNearByCompressedPayload);

              //test block
              {
                FencesSearch fencesSearchResponse = FencesSearch.parseFrom(fencesNearByRawBytes);
                if (fencesSearchResponse != null)
                  Log.d(TAG, String.format("Inflated proto: fences:'%d'", fencesSearchResponse.getRawResultsCount()));
              }

              if (fencesNearByRawBytes != null)
                bundle.putByteArray(RESULT_RAW_RESPONSE_EXTRA, fencesNearByRawBytes);
              else
                resultCode = ERROR_CODE;
            }
            else
            {
              byte[] fencesNearByRawBytes = b64DecodeResponse(jsonEntityFencesNearBy.getPayload());

              //test block
              {
                FencesSearch fencesSearchResponse = FencesSearch.parseFrom(fencesNearByRawBytes);
                if (fencesSearchResponse != null)
                  Log.d(TAG, String.format("Inflated proto: SUCCESS TEST B64DECODING/PROTO_INFLATING: fences:'%d'", fencesSearchResponse.getRawResultsCount()));
              }

              if (fencesNearByRawBytes != null)
                bundle.putByteArray(RESULT_RAW_RESPONSE_EXTRA, fencesNearByRawBytes);
              else
                resultCode = ERROR_CODE;
            }
          }
          else resultCode = EMPTYSET_CODE;
        }
        else  resultCode=ERROR_CODE;

        bundle.putInt(RESULT_CODE_EXTRA, resultCode);
        resultReceiver.send(RESULT_CODE, bundle);//success in terms of network op, but not in terms name availability
      } catch (Exception exc) {
        Log.e(TAG, "onHandleIntent: "+ exc.getMessage());
        Bundle bundle = new Bundle();
        bundle.putInt(RESULT_CODE_EXTRA, ERROR_CODE);
        resultReceiver.send(RESULT_CODE, bundle);
      }
    }finally
    {
      /*catch (PendingIntent.CanceledException exc) {
      Log.i(TAG, "reply cancelled", exc);
    }*/
    }
  }

  byte[] b64DecodeResponse (String b64Encoded)
  {
    try
    {
      return Base64.decode(b64Encoded);
//      byte[] message = Base64.decode(b64Encoded);
//      FencesNearBy fencesNearByResponse = FencesNearBy.parseFrom(message);
//      return fencesNearByResponse;
    }
    catch (IOException ex)
    {
      Log.e(TAG, ex.getMessage());
    }

    return null;
  }
}