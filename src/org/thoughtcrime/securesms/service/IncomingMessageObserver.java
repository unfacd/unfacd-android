package org.thoughtcrime.securesms.service;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import com.google.android.gms.location.DetectedActivity;
import com.google.protobuf.InvalidProtocolBufferException;

import com.unfacd.android.UfsrvCommandParser;
import com.unfacd.android.UfsrvEventsNames;
import com.unfacd.android.jobs.LocationRefreshJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.service.ReverseGeocodingListener;
import com.unfacd.android.service.ReverseGeocodingTask;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;

import android.app.Service;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Location;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraintObserver;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.PushContentReceiveJob;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nlopez.smartlocation.OnActivityUpdatedListener;
import io.nlopez.smartlocation.OnLocationUpdatedListener;
import io.nlopez.smartlocation.SmartLocation;

public class IncomingMessageObserver implements ConstraintObserver.Notifier,
                                                OnLocationUpdatedListener,
                                                OnActivityUpdatedListener,
                                                /*OnGeofencingTransitionListener,*/
                                                ReverseGeocodingListener
{

  private static final String TAG = IncomingMessageObserver.class.getSimpleName();

  public  static final  int FOREGROUND_ID            = 313399;
  private static final long REQUEST_TIMEOUT_MINUTES  = 1;

  private static SignalServiceMessagePipe pipe             = null;
  public static SignalServiceMessagePipe  unidentifiedPipe = null;

  private final Context                      context;
  private final NetworkConstraint            networkConstraint;
  private final SignalServiceMessageReceiver receiver;
  private final SignalServiceNetworkAccess   networkAccess;

  private boolean appVisible;

  //
  private final Object ufsrvParserLock = new Object();
  private UfsrvCommandParser ufsrvParser=new UfsrvCommandParser();

  private AtomicBoolean callbackDone = new AtomicBoolean(false);
  //

  public IncomingMessageObserver(@NonNull Context context) {
    this.context            = context;
    this.networkConstraint = new NetworkConstraint.Factory(ApplicationContext.getInstance(context)).create();
    this.receiver          = ApplicationDependencies.getSignalServiceMessageReceiver();
    this.networkAccess     = ApplicationDependencies.getSignalServiceNetworkAccess();

    new NetworkConstraintObserver(ApplicationContext.getInstance(context)).register(this);
    new MessageRetrievalThread().start();

    if (TextSecurePreferences.isFcmDisabled(context)) {
      ContextCompat.startForegroundService(context, new Intent(context, ForegroundService.class));
    }

    ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
      @Override
      public void onStart(@NonNull LifecycleOwner owner) {
        onAppForegrounded();
      }

      @Override
      public void onStop(@NonNull LifecycleOwner owner) {
        onAppBackgrounded();
      }
    });

    initLocation(); // this was inherited from now defunct MessageRetrievalService
  }

  @Override
  public void onConstraintMet(@NonNull String reason) {
    synchronized (this) {
      notifyAll();
    }
  }

  private synchronized void onAppForegrounded() {
    appVisible = true;
    notifyAll();
  }

  private synchronized void onAppBackgrounded() {
    appVisible = false;
    notifyAll();
  }

  private synchronized boolean isConnectionNecessary() {
    boolean isGcmDisabled = TextSecurePreferences.isFcmDisabled(context);

    Log.d(TAG, String.format("Network requirement: %s, app visible: %s, gcm disabled: %b",
                             networkConstraint.isMet(), appVisible, isGcmDisabled));

    return TextSecurePreferences.isPushRegistered(context)      &&
            TextSecurePreferences.isWebsocketRegistered(context) &&
            (appVisible || isGcmDisabled)                        &&
            networkConstraint.isMet()                       &&
            !networkAccess.isCensored(context);
  }

  private synchronized void waitForConnectionNecessary() {
    try {
      while (!isConnectionNecessary()) wait();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private void shutdown(SignalServiceMessagePipe pipe, SignalServiceMessagePipe unidentifiedPipe) {
    try {
      pipe.shutdown();
      if (unidentifiedPipe!=null) unidentifiedPipe.shutdown(); // conditional
    } catch (Throwable t) {
      Log.w(TAG, t);
    }
  }

  public static @Nullable SignalServiceMessagePipe getPipe() {
    return pipe;
  }


  //
  public boolean isConnected()
  {
    return ((pipe!=null) && (pipe.isConnected()));
  }

  //
  public void shutdownConnection ()
  {
    if ((pipe!=null) && (pipe.isConnected())) {
      Log.d(TAG, ">>> shutdownConnection: shutting down WebSocket Connection...");
      shutdown(pipe, unidentifiedPipe);
    } else {
      Log.d(TAG, ">>> shutdownConnection: WebSocket Connection WAS NOT RUNNING...");
    }

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
      ApplicationContext.getInstance().getJobManager().add(new LocationRefreshJob());

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

  private Location initLocation ()
  {
    Log.d(TAG, ">> initLocation entered...");

    SmartLocation smartLocation = new SmartLocation.Builder(ApplicationContext.getInstance().getApplicationContext()).logging(true).build();

    smartLocation.location().start(this);
    //smartLocation.activityRecognition().start(this);

        /*
        // currently disabled
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

  private void stopLocation ()
  {
    //    isCapturingLocation = false;

    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).location().stop();
    //  locationText.setText("Location stopped!");
    Log.d(TAG, "Stopping location detection...");
    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).activityRecognition().stop();
    Log.d(TAG, "Stopping activity detection...");
    //activityText.setText("Activity Recognition stopped!");

    SmartLocation.with(ApplicationContext.getInstance().getApplicationContext()).geofencing().stop();

    //geofenceText.setText("Geofencing stopped!");

  }

  // ----------------------------- END OF LOCATION -----------------------------------------------------

  public static @Nullable SignalServiceMessagePipe getUnidentifiedPipe() {
    return unidentifiedPipe;
  }

  private class MessageRetrievalThread extends Thread implements Thread.UncaughtExceptionHandler {

    MessageRetrievalThread() {
      super("MessageRetrievalService");
      setUncaughtExceptionHandler(this);
    }

    @Override
    public void run() {
      while (true) {
        Log.i(TAG, "Waiting for websocket state change....");
        waitForConnectionNecessary();

        Log.i(TAG, "Making websocket connection....");
        pipe              = receiver.createMessagePipe(callbackDone);// arg
//        unidentifiedPipe  = receiver.createUnidentifiedMessagePipe(callbackDone); //todo: what's callbackdone required for?

        SignalServiceMessagePipe localPipe = pipe;

        try {
          while (isConnectionNecessary()) {

            try {
              Log.i(TAG, "Reading message...");
              localPipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES, new MessageRetrievalThread.WebSocketCallback());// callback
            } catch (TimeoutException e) {
              Log.w(TAG, "Application level read timeout...");
            } catch (InvalidVersionException e) {
              Log.w(TAG, e);
            }
          }
        } catch (Throwable e) {
          Log.w(TAG, e);
        } finally {
          Log.w(TAG, "Shutting down pipe...");
          shutdown(localPipe, unidentifiedPipe);
        }

        Log.i(TAG, "Looping...");
      }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Log.w(TAG, "*** Uncaught exception!");
      Log.w(TAG, e);
    }

    // expanded anynomous class
    class WebSocketCallback implements SignalServiceMessagePipe.MessagePipeCallback
    {
      @Override
      public void onMessage (WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock)
      {
        synchronized (lock) {
          callbackDone.set(false);

          if (envelope != null) {
            PushContentReceiveJob receiveJob = new PushContentReceiveJob(ApplicationContext.getInstance());
            new PushContentReceiveJob(ApplicationContext.getInstance()).processEnvelope(envelope); // doesn't rely on JobManager, calls handle directly

//            decrementPushReceived(); not ported

          } else {
            Log.d(TAG, "onMessage: server control message: parsing command");
            try {
              ufsrvParser.invokeUfsrvCommandHandler(wsm);
            } catch (InvalidProtocolBufferException e) {
              Log.w(TAG, "protobuf problem...");
            }
            finally {
              callbackDone.set(true);
              lock.notifyAll();
            }//
          }

          Log.d(TAG, String.format("onMessage (%d): FINISHED CALLBACK --> CALLING NOTIFY", Thread.currentThread().getId()));
          callbackDone.set(true);
          lock.notifyAll();//
        }
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

      NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationChannels.OTHER);
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