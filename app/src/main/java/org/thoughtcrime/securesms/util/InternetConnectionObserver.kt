package org.thoughtcrime.securesms.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import io.reactivex.rxjava3.core.Observable
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint

object InternetConnectionObserver {
  fun observe(): Observable<Boolean> = Observable.create {
    val application = ApplicationDependencies.getApplication()

    val observer = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (!it.isDisposed) {
          it.onNext(NetworkConstraint.isMet(application))
        }
      }
    }

    it.setCancellable { application.unregisterReceiver(observer) }
    ContextCompat.registerReceiver(application,observer, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_EXPORTED)
  }
}