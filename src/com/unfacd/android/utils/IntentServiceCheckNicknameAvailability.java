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
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.BuildConfig;

import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;


/**
 * This must be added to Manifest file
 *  <service
 android:name=".utils.IntentServiceCheckNicknameAvailability"
 android:exported="false"/>
 http://stackoverflow.com/questions/6343166/how-to-fix-android-os-networkonmainthreadexception
 */
public class IntentServiceCheckNicknameAvailability extends IntentService {

  private static final String TAG = IntentServiceCheckNicknameAvailability.class.getSimpleName();

  public static final String PENDING_RESULT_EXTRA = "pending_result"; //the reply intent holder

  public static final String NICKNAME_EXTRA = "nickname";//nickname to check
  public static final String RESULT_NICKNAME_EXTRA = "nickname_available"; //result back
  public static final String RESULT_CODE_EXTRA = "result_code"; //result back

  public static final int RESULT_CODE = 0;
  public static final int INVALID_URL_CODE = 1;
  public static final int ERROR_CODE = 2;

  private PushServiceSocket socket;
  private String nickname;
  private boolean isNicknameAvailable=false;

  public IntentServiceCheckNicknameAvailability() {
    super(TAG);

    // make one and re-use, in the case where more than one intent is queued
    /*socket = new PushServiceSocket(BuildConfig.TEXTSECURE_URL,
            new TextSecurePushTrustStore(this),
            new StaticCredentialsProvider(null, null, null), BuildConfig.USER_AGENT);*/
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    //PendingIntent reply = intent.getParcelableExtra(PENDING_RESULT_EXTRA);//for communicating result back to activity created by the initiating party
    ResultReceiver  resultReceiver  = intent.getParcelableExtra(PENDING_RESULT_EXTRA);

    ApplicationContext.DynamicCredentialsProvider creds=new ApplicationContext.DynamicCredentialsProvider();
    SignalServiceNetworkAccess networkAccess= new SignalServiceNetworkAccess(ApplicationContext.getInstance());

    try {
      try {
        socket = new PushServiceSocket(networkAccess.getConfiguration(ApplicationContext.getInstance())/*BuildConfig.UFSRVAPI_URL*/,

                new StaticCredentialsProvider(creds.getUser(), creds.getPassword(), null), BuildConfig.USER_AGENT);

        nickname=intent.getStringExtra(NICKNAME_EXTRA);

        if (socket.isNicknamekAvailable(nickname))  isNicknameAvailable=true;
        else  isNicknameAvailable=false;

//        Intent result = new Intent();
//        result.putExtra(RESULT_NICKNAME_EXTRA, isNicknameAvailable);
//        reply.send(this, RESULT_CODE, result);//success in terms of network op, but not in terms name availability

        Bundle bundle = new Bundle();
        bundle.putBoolean(RESULT_NICKNAME_EXTRA, isNicknameAvailable);
        resultReceiver.send(RESULT_CODE, bundle/*result*/);//success in terms of network op, but not in terms name availability
      } catch (Exception exc) {
        // try and specialise the error code for better informative feedback to the user
//        reply.send(ERROR_CODE);
        Bundle bundle = new Bundle();
        bundle.putInt(RESULT_CODE_EXTRA, 0);
        resultReceiver.send(RESULT_CODE, bundle);
      }
    }finally
    {
      /*catch (PendingIntent.CanceledException exc) {
      Log.i(TAG, "reply cancelled", exc);
    }*/
    }
  }
}