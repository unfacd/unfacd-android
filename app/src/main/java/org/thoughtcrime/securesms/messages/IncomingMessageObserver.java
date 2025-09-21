package org.thoughtcrime.securesms.messages;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Address;
import android.location.Location;
import android.net.ConnectivityManager;
import android.os.IBinder;

import com.google.android.gms.location.DetectedActivity;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.UfsrvCommandParser;
import com.unfacd.android.UfsrvEventsNames;
import com.unfacd.android.jobs.LocationRefreshJob;
import com.unfacd.android.location.UfsrvLocationUtils;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.service.ReverseGeocodingListener;
import com.unfacd.android.service.ReverseGeocodingTask;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.PushDecryptDrainedJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.messages.IncomingMessageProcessor.Processor;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.util.AppForegroundObserver;
import org.whispersystems.signalservice.api.SignalWebSocket;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.websocket.WebSocketUnavailableException;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import io.nlopez.smartlocation.OnActivityUpdatedListener;
import io.nlopez.smartlocation.OnLocationUpdatedListener;
import io.nlopez.smartlocation.SmartLocation;

/**
 * The application-level manager of our websocket connection.
 *
 * This class is responsible for opening/closing the websocket based on the app's state and observing new inbound messages received on the websocket.
 */
public class IncomingMessageObserver implements OnLocationUpdatedListener,
                                                OnActivityUpdatedListener,
                                                /*OnGeofencingTransitionListener,*/
                                                ReverseGeocodingListener
{

  private static final String TAG = Log.tag(IncomingMessageObserver.class);

  public static final  int  FOREGROUND_ID           = 313399;
  private static final long REQUEST_TIMEOUT_MINUTES = 1;

  private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(0);

  private final Application                context;
  private final SignalServiceNetworkAccess networkAccess;
  private final List<Runnable>             decryptionDrainedListeners;
  private final BroadcastReceiver          connectionReceiver;

  private boolean appVisible;

  private volatile boolean networkDrained;
  private volatile boolean decryptionDrained;
  private volatile boolean terminated;

  //AA+
  private UfsrvCommandParser ufsrvParser = new UfsrvCommandParser();
  private AtomicBoolean callbackDone = new AtomicBoolean(false);
  //

  public IncomingMessageObserver(@NonNull Application context) {
    if (INSTANCE_COUNT.incrementAndGet() != 1) {
      throw new AssertionError("Multiple observers!");
    }

    this.context                    = context;
    this.networkAccess              = ApplicationDependencies.getSignalServiceNetworkAccess();
    this.decryptionDrainedListeners = new CopyOnWriteArrayList<>();

    new MessageRetrievalThread().start();

    if (!SignalStore.account().isFcmEnabled()) {
      ContextCompat.startForegroundService(context, new Intent(context, ForegroundService.class));
    }

    ApplicationDependencies.getAppForegroundObserver().addListener(new AppForegroundObserver.Listener() {
      @Override
      public void onForeground() {
        onAppForegrounded();
      }

      @Override
      public void onBackground() {
        onAppBackgrounded();
      }
    });

    connectionReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        synchronized (IncomingMessageObserver.this) {
          if (!NetworkConstraint.isMet(context)) {
            Log.w(TAG, "Lost network connection. Shutting down our websocket connections and resetting the drained state.");
            networkDrained    = false;
            decryptionDrained = false;
            disconnect();
          }
          IncomingMessageObserver.this.notifyAll();
        }
      }
    };

    ContextCompat.registerReceiver(context, connectionReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_EXPORTED);
  }

  public synchronized void notifyRegistrationChanged() {
    notifyAll();
  }

  public synchronized void addDecryptionDrainedListener(@NonNull Runnable listener) {
    decryptionDrainedListeners.add(listener);
    if (decryptionDrained) {
      listener.run();
    }
  }

  public boolean isDecryptionDrained() {
    return decryptionDrained || networkAccess.isCensored();
  }

  public void notifyDecryptionsDrained() {
    List<Runnable> listenersToTrigger = new ArrayList<>(decryptionDrainedListeners.size());

    synchronized (this) {
      if (networkDrained && !decryptionDrained) {
        Log.i(TAG, "Decryptions newly drained.");
        decryptionDrained = true;
        listenersToTrigger.addAll(decryptionDrainedListeners);
      }
    }

    for (Runnable listener : listenersToTrigger) {
      listener.run();
    }
  }

  private synchronized void onAppForegrounded() {
    appVisible = true;
    initLocation();//AA+
    notifyAll();
  }

  private synchronized void onAppBackgrounded() {
    appVisible = false;
    stopLocation();//AA+
    notifyAll();
  }

  //AA+ this was deleted in https://github.com/signalapp/Signal-Android/commit/662f0b8fb60e23999b580618584e0c0b9c6bce94#diff-48b92e1c48515ee2aa6a9b1cff7f21ff
  //but it is currently necessary to reliably kick off the websocket connection. This semantics need to be reviewed to confirm if it is still necessary
  //AA 220720 yes required see https://github.com/signalapp/Signal-Android/commit/fb0243a0293e870e4344600606e162f3898d142e
  public void onConstraintMet(@NonNull String reason) {
    synchronized (this) {
      notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    boolean registered = SignalStore.account().isRegistered();
    boolean fcmEnabled = SignalStore.account().isFcmEnabled();
    boolean hasNetwork = NetworkConstraint.isMet(context);
    boolean hasProxy   = SignalStore.proxy().isProxyEnabled();

    Log.d(TAG, String.format("Network: %s, Foreground: %s, FCM: %s, Censored: %s, Registered: %s, Proxy: %s",
                             hasNetwork, appVisible, fcmEnabled, networkAccess.isCensored(), registered, hasProxy));

    return registered &&
            (appVisible || !fcmEnabled) &&
            hasNetwork &&
            !networkAccess.isCensored();
  }

  private synchronized void waitForConnectionNecessary() {
    try {
      while (!isConnectionNecessary()) wait();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void terminateAsync() {
    INSTANCE_COUNT.decrementAndGet();

    context.unregisterReceiver(connectionReceiver);

    SignalExecutors.BOUNDED.execute(() -> {
      Log.w(TAG, "Beginning termination.");
      terminated = true;
      disconnect();
    });
  }

  private void disconnect() {
    ApplicationDependencies.getSignalWebSocket().disconnect();
  }

  //AA+
  public boolean isConnected()
  {
   return ApplicationDependencies.getSignalWebSocket().isConnected();
  }

  // ------------------------------- LOCATION -----------------------------------------
  @Override
  public void onLocationUpdated (Location location)
  {
    if (location != null) {
      Log.i(TAG, ">> onLocationUpdated:" + String.format("Latitude %.6f, Longitude %.6f",
                                                         location.getLatitude(),
                                                         location.getLongitude()));

      ufLocation.getInstance().setCurrentLocation(location);

      new ReverseGeocodingTask(ApplicationContext.getInstance().getApplicationContext(), this).execute(location);

      //At this stage this is not useful, as it transmits old address location. we'd better wait for geodecoding event
      //ApplicationContext.getInstance().getJobManager().add(new LocationRefreshJob(this));
      //ApplicationContext.getInstance().getUfsrvcmdEvents().post(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
    } else {
      Log.i(TAG, ">> Location: was NULL");
    }

  }

  @Override
  public void onAddressAvailable (Address address)
  {
    if (address == null) {
      Log.d(TAG, ">> onAddressAvailable: Available reverse geo returned null");
    } else {
      final String locality = String.format("%s, %s, %s", address.getLocality(), address.getAdminArea(), address.getCountryName());
      Log.d(TAG, ">> onAddressAvaialble: reverse geocding returned: " + locality);

      ufLocation.getInstance().setCurrentAddress(address);
      ApplicationDependencies.getJobManager().add(new LocationRefreshJob());

      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
    }
  }

  @Override
  public void onActivityUpdated (DetectedActivity detectedActivity)
  {
    ufLocation myL = ufLocation.getInstance();

    if (detectedActivity != null) {
      synchronized (this) {
        myL.setCurrentActivity(myL.describeActivity(detectedActivity));
        myL.setCurrentActivityConfidence(myL.describeActivityConfidence(detectedActivity));
      }
      return;
    }

    Log.d(TAG, ">> onActivityUpdated contained null Activity");

  }

/*
  @Override
  public void OnGeofencingTransition  ()
  {
    // showGeofence(geofence, transitionType);

  }
*/

  private Location initLocation()
  {
    if (!UfsrvLocationUtils.isLocationPermissionsGranted(ApplicationDependencies.getApplication())) {
      Log.w(TAG, ">> Cannot enable location detection: no permissions.");
      return null;
    }

    Log.d(TAG, ">> Starting location detection.");

    SmartLocation smartLocation = new SmartLocation.Builder(ApplicationContext.getInstance().getApplicationContext()).logging(true).build();

    smartLocation.location().start(this);
    //smartLocation.activityRecognition().start(this);

        /*
        //AA currently disabled
        GeofenceModel mestalla = new GeofenceModel.Builder("1").

                setTransition(Geofence.GEOFENCE_TRANSITION_ENTER).setLatitude(-33.849206).
                setLongitude(151.023190).setRadius(500).build();
        smartLocation.geofencing().add(mestalla).start(this);
        */

    Location myL = smartLocation.location().getLastLocation();
    if (myL == null) {
      Log.d(TAG, ">> initLocation: not seeding from getLastLocation null");
      return null;
    }

    return myL;

  }

  private void stopLocation()
  {
    //    isCapturingLocation = false;

    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).location().stop();
    Log.d(TAG, "Stopping location detection...");
    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).activityRecognition().stop();
    Log.d(TAG, "Stopping activity detection...");

    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).geofencing().stop();

    //geofenceText.setText("Geofencing stopped!");

  }

  // ----------------------------- END OF LOCATION -----------------------------------------------------

  private class MessageRetrievalThread extends Thread implements Thread.UncaughtExceptionHandler {
    MessageRetrievalThread() {
      super("MessageRetrievalService");
      Log.i(TAG, "Initializing! (" + this.hashCode() + ")");
      setUncaughtExceptionHandler(this);
    }

    @Override
    public void run() {
      int attempts = 0;

      while (!terminated) {
        Log.i(TAG, "Waiting for websocket state change....");
        if (attempts > 1) {
          long backoff = BackoffUtil.exponentialBackoff(attempts, TimeUnit.SECONDS.toMillis(30));
          Log.w(TAG, "Too many failed connection attempts,  attempts: " + attempts + " backing off: " + backoff);
          ThreadUtil.sleep(backoff);
        }
        waitForConnectionNecessary();

        Log.i(TAG, "Making websocket connection....");
        SignalWebSocket signalWebSocket = ApplicationDependencies.getSignalWebSocket();
        signalWebSocket.connect();

        try {
          while (isConnectionNecessary()) {
            try {
              Log.d(TAG, "Reading message...");
              Optional<SignalServiceEnvelope> result = signalWebSocket.readOrEmpty(TimeUnit.MINUTES.toMillis(REQUEST_TIMEOUT_MINUTES), new MessageRetrievalThread.WebSocketCallback());//AA+ callback

              attempts = 0;

              if (!result.isPresent() && !networkDrained) {
                Log.i(TAG, "Network was newly-drained. Enqueuing a job to listen for decryption draining.");
                networkDrained = true;
                ApplicationDependencies.getJobManager().add(new PushDecryptDrainedJob());
              }
            } catch (WebSocketUnavailableException e) {
              Log.i(TAG, "Pipe unexpectedly unavailable, connecting");
              signalWebSocket.connect();
            } catch (TimeoutException e) {
              Log.w(TAG, "Application level read timeout...");
              attempts = 0;
            }
          }
        } catch (Throwable e) {
          attempts++;
          Log.w(TAG, e);
        } finally {
          Log.w(TAG, "Shutting down pipe...");
          disconnect();
        }

        Log.i(TAG, "Looping...");
      }

      Log.w(TAG, "Terminated! (" + this.hashCode() + ")");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Log.w(TAG, "*** Uncaught exception!");
      Log.w(TAG, e);
    }

    //AA+ expanded anonymous class
    class WebSocketCallback implements SignalWebSocket.MessageReceivedCallback {
      @Override
      public void onMessage(WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope) {
        callbackDone.set(false);

        if (envelope != null) {
          try (Processor processor = ApplicationDependencies.getIncomingMessageProcessor().acquire()) {
            processor.processEnvelope(envelope);
          }
        } else {
          Log.d(TAG, "onMessage: server control message: parsing command");
          try {
            ufsrvParser.invokeUfsrvCommandHandler(wsm);
          } catch (InvalidProtocolBufferException e) {
            Log.w(TAG, "protobuf problem...");
          }
          finally {
            callbackDone.set(true);
          }//AA+
        }

        Log.d(TAG, String.format("onMessage (%d): FINISHED CALLBACK --> CALLING NOTIFY", Thread.currentThread().getId()));
        callbackDone.set(true);
      }
    }
  }

  public static class ForegroundService extends Service {

    @Override
    public @Nullable IBinder onBind(Intent intent) {
      return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
      super.onStartCommand(intent, flags, startId);

      NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationChannels.BACKGROUND);
      builder.setContentTitle(getApplicationContext().getString(R.string.MessageRetrievalService_signal));
      builder.setContentText(getApplicationContext().getString(R.string.MessageRetrievalService_background_connection_enabled));
      builder.setPriority(NotificationCompat.PRIORITY_MIN);
      builder.setWhen(0);
      builder.setSmallIcon(R.drawable.ic_signal_background_connection);
      startForeground(FOREGROUND_ID, builder.build());

      return Service.START_STICKY;
    }
  }
}