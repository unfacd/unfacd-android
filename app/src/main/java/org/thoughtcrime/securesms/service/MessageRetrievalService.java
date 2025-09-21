package org.thoughtcrime.securesms.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Location;
import android.os.Binder;
import android.os.IBinder;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.messages.IncomingMessageProcessor;
import org.signal.core.util.logging.Log;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.DetectedActivity;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;
import com.unfacd.android.UfsrvCommandParser;
import com.unfacd.android.UfsrvEventsNames;
import com.unfacd.android.jobs.LocationRefreshJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.service.ReverseGeocodingListener;
import com.unfacd.android.service.ReverseGeocodingTask;
import com.unfacd.android.ufsrvcmd.events.LocationV1SystemEvent;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nlopez.smartlocation.OnActivityUpdatedListener;
import io.nlopez.smartlocation.OnLocationUpdatedListener;
import io.nlopez.smartlocation.SmartLocation;

//AA duplicated in IncomingMessageObserver.java. Not in use, but was reinstated in https://github.com/signalapp/Signal-Android/commit/5f31762220fa5ca4a00138e74500bbe4b5232578
public class MessageRetrievalService /*extends Service implements
        OnLocationUpdatedListener, OnActivityUpdatedListener,
        ReverseGeocodingListener*/
{

 /* private static final String TAG = Log.tag(MessageRetrievalService.class);

  public static final  String ACTION_ACTIVITY_STARTED  = "ACTIVITY_STARTED";
  public static final  String ACTION_ACTIVITY_FINISHED = "ACTIVITY_FINISHED";
  public static final  String ACTION_PUSH_RECEIVED     = "PUSH_RECEIVED";
  public static final  String ACTION_INITIALIZE        = "INITIALIZE";
  public static final  int    FOREGROUND_ID            = 313399;//todo: need to change this introduced with no-gcm support
  private static final long   REQUEST_TIMEOUT_MINUTES  = 1;

  //broadcast intent
  public static final String CONNECTEDMRS_EVENT        = "com.unfacd.android.CONNECTEDMRS_EVENT";

  //AA+
  private final Binder          binder   = new MessageRetrievalServiceBinder();
  public static TBDSignalServiceMessagePipe pipe = null;
  //

  //AA+
  private final Object ufsrvParserLock = new Object();
  private UfsrvCommandParser ufsrvParser=new UfsrvCommandParser();

  private AtomicBoolean callbackDone = new AtomicBoolean(false);
  //

  public SignalServiceMessageReceiver receiver = ApplicationDependencies.getSignalServiceMessageReceiver();

  private int                    activeActivities = 0;
  private List<Intent>           pushPending      = new LinkedList<>();
  private MessageRetrievalThread retrievalThread  = null;

  @Override
  public void onCreate() {
    super.onCreate();

    //AA+
    initLocation();

    retrievalThread = new MessageRetrievalThread();
    retrievalThread.start();

    setForegroundIfNecessary();

    //AA+ broadcast that we have started
    Intent intent = new Intent();
    intent.setAction(CONNECTEDMRS_EVENT);
    sendBroadcast(intent);
  }


  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_STICKY;

    if      (ACTION_ACTIVITY_STARTED.equals(intent.getAction()))  incrementActive();
    else if (ACTION_ACTIVITY_FINISHED.equals(intent.getAction())) decrementActive();
    else if (ACTION_PUSH_RECEIVED.equals(intent.getAction()))     incrementPushReceived(intent);

    return START_STICKY;
  }


  //AA+
  public static  @NonNull
  TBDSignalServiceMessagePipe getPipe()
  {
    return pipe;
  }
  //

  @Override
  public void onDestroy() {
    super.onDestroy();

    if (retrievalThread != null) {
      retrievalThread.stopThread();
    }

    sendBroadcast(new Intent("com.unfacd.android.RESTART"));
  }

  @Override
  public IBinder onBind(Intent intent) {
    //AA+ because we allow this service to be bound to
    return binder;
    //AA-
    //return null;
  }

  private void setForegroundIfNecessary() {
    if (TextSecurePreferences.isFcmDisabled(this)) {
      NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannels.OTHER);
      builder.setContentTitle(getString(R.string.MessageRetrievalService_signal));
      builder.setContentText(getString(R.string.MessageRetrievalService_background_connection_enabled));
      builder.setPriority(NotificationCompat.PRIORITY_MIN);
      builder.setWhen(0);
      builder.setSmallIcon(R.drawable.ic_signal_grey_24dp);
      startForeground(FOREGROUND_ID, builder.build());
    }
  }

  //AA+ expanded anonymous class
  class WebSocketCallback implements TBDSignalServiceMessagePipe.MessagePipeCallback
  {
    @Override
    public void onMessage (WebSocketProtos.WebSocketMessage wsm, SignalServiceEnvelope envelope, Object lock)
    {
      synchronized (lock) {
        callbackDone.set(false);

        if (envelope != null) {

          if ((envelope.getUfsrvType() == SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE_VALUE)) {
            SignalServiceProtos.FenceRecord fenceRecord = envelope.getUfsrvCommand().getFenceCommand().getFencesCount() > 0 ? envelope.getUfsrvCommand().getFenceCommand().getFences(0) : null;
            Log.w(TAG, String.format("(%d, %s) Retrieved envelope! source:'%s', command:'%d', eid:'%d' nFences:'%d', fid:'%d'", this.hashCode(), Thread.currentThread().getId(),
                    envelope.getSourceIdentifier(), envelope.getUfsrvCommand().getUfsrvtype().getNumber(), envelope.getUfsrvCommand().getHeader().getEid(),
                    envelope.getUfsrvCommand().getFenceCommand().getFencesCount(), fenceRecord != null ? fenceRecord.getFid() : "-1"));
          }
          try (IncomingMessageProcessor.Processor processor = ApplicationDependencies.getIncomingMessageProcessor().acquire()) {
            processor.processEnvelope(envelope);
          }

          decrementPushReceived();

        } else {
          //serever control message
          Log.d(TAG, "onMessage: server control message: parsing command");
          try {
            ufsrvParser.invokeUfsrvCommandHandler(wsm);
          } catch (InvalidProtocolBufferException e) {
            Log.w(TAG, "protobuf problem...");
          }
          finally {
            callbackDone.set(true);
            lock.notifyAll();
          }//AA+
        }

        Log.d(TAG, String.format("onMessage (%d): FINISHED CALLBACK --> CALLING NOTIFY", Thread.currentThread().getId()));
        callbackDone.set(true);
        lock.notifyAll();//AA+
      }
    }
  }

  private synchronized void incrementActive() {
    activeActivities++;
    Log.w(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void decrementActive() {
    activeActivities--;
    Log.w(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void incrementPushReceived(Intent intent) {
    pushPending.add(intent);
    notifyAll();
  }

  private synchronized void decrementPushReceived() {
    if (!pushPending.isEmpty()) {
      Intent intent = pushPending.remove(0);
//      FcmReceiveService.completeWakefulIntent(intent);
      notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    boolean isGcmDisabled = TextSecurePreferences.isFcmDisabled(this);

//    Log.w(TAG, String.format("Network requirement: %s, active activities: %s, push pending: %s, gcm disabled: %b",
//            networkRequirement.isPresent(), activeActivities, pushPending.size(), isGcmDisabled));
//
//    return TextSecurePreferences.isPushRegistered(this)                       &&
//            TextSecurePreferences.isWebsocketRegistered(this)                  &&
//            (activeActivities > 0 || !pushPending.isEmpty() || isGcmDisabled)  &&
//            networkRequirement.isPresent();
    return false;
  }

  private synchronized void waitForConnectionNecessary() {
    try {
      while (!isConnectionNecessary()) wait();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private void shutdown(TBDSignalServiceMessagePipe pipe) {
    try {
      pipe.shutdown();
    } catch (Throwable t) {
      Log.w(TAG, t);
    }
  }

  public static void registerActivityStarted(Context activity) {
    Log.d(TAG, ">>> registerActivityStarted: STARTING WebSocket connection...");
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_STARTED);
    activity.startService(intent);
  }

  public static void registerActivityStopped(Context activity) {
    Log.d(TAG, ">>> registerActivityStopped: STOPPING WebSocket connection...");
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_FINISHED);
    activity.startService(intent);
  }

  private class MessageRetrievalThread extends Thread implements Thread.UncaughtExceptionHandler {

    private AtomicBoolean stopThread = new AtomicBoolean(false);

    MessageRetrievalThread() {
      super("MessageRetrievalService");
      setUncaughtExceptionHandler(this);
    }

  @Override
  public void run() {
    //WebSocketCallback webSocketCallback=new WebSocketCallback();

    while (!stopThread.get())
    {
      Log.w(TAG, "Waiting for websocket state change....");
      waitForConnectionNecessary();

      Log.w(TAG, "Making websocket connection....");

      pipe = receiver.createMessagePipe(callbackDone);

      try
      {
        while (isConnectionNecessary() && !stopThread.get())
        {
          try
          {
            //Log.w(TAG, "Reading message...");
            //AA+
            WebSocketCallback webSocketCallback = new WebSocketCallback();

            pipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES, webSocketCallback);
            *//*
            //AA- orig
            Log.w(TAG, "Reading message...");
            pipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                      new TBDSignalServiceMessagePipe.MessagePipeCallback() {
                        @Override
                        public void onMessage(SignalServiceEnvelope envelope) {
                          Log.w(TAG, "Retrieved envelope! " + envelope.getSource());

                          PushContentReceiveJob receiveJob = new PushContentReceiveJob(MessageRetrievalService.this);
                          receiveJob.handle(envelope, false);

                          decrementPushReceived();
                        }
                      });
            *//*
          }
          catch (TimeoutException e)
          {
            //Log.w(TAG, "Application level read timeout...");
          }
          catch (InvalidVersionException e)
          {
            Log.w(TAG, e);
          }
        }

      }
      catch (Throwable e)
      {
        Log.w(TAG, e);
      }
      finally
      {
        Log.w(TAG, "Shutting down pipe...");
        shutdown(pipe);
      }
      Log.w(TAG, "Looping...");
    }
    Log.w(TAG, "Exiting...");
  }

//    @Override
//    public void run() {
//      while (!stopThread.get()) {
//        Log.w(TAG, "Waiting for websocket state change....");
//        waitForConnectionNecessary();
//
//        Log.w(TAG, "Making websocket connection....");
//        pipe = receiver.createMessagePipe();
//
//        TBDSignalServiceMessagePipe localPipe = pipe;
//
//        try {
//          while (isConnectionNecessary() && !stopThread.get()) {
//            try {
//              Log.w(TAG, "Reading message...");
//              localPipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES,
//                      new TBDSignalServiceMessagePipe.MessagePipeCallback() {
//                        @Override
//                        public void onMessage(SignalServiceEnvelope envelope) {
//                          Log.w(TAG, "Retrieved envelope! " + envelope.getSource());
//
//                          PushContentReceiveJob receiveJob = new PushContentReceiveJob(MessageRetrievalService.this);
//                          receiveJob.handle(envelope, false);
//
//                          decrementPushReceived();
//                        }
//                      });
//            } catch (TimeoutException e) {
//              Log.w(TAG, "Application level read timeout...");
//            } catch (InvalidVersionException e) {
//              Log.w(TAG, e);
//            }
//          }
//        } catch (Throwable e) {
//          Log.w(TAG, e);
//        } finally {
//          Log.w(TAG, "Shutting down pipe...");
//          shutdown(localPipe);
//        }
//
//        Log.w(TAG, "Looping...");
//      }
//
//      Log.w(TAG, "Exiting...");
//    }

    private void stopThread() {
      stopThread.set(true);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Log.w(TAG, "*** Uncaught exception!");
      Log.w(TAG, e);
    }
  }

  //AA+
  public boolean isConnected()
  {
    return ((pipe!=null) && (pipe.isConnected()));
  }

  //AA+
  public void shutdownConnection ()
  {
    if   ((pipe!=null) && (pipe.isConnected()))
    {
      Log.d(TAG, ">>> shutdownConnection: shutting down WebSocket Connection...");
      shutdown(pipe);
    }
    else
    {
      Log.d(TAG, ">>> shutdownConnection: WebSocket Connection WAS NOT RUNNING...");
    }

  }


  // AA+
  public class MessageRetrievalServiceBinder extends Binder
  {
    public MessageRetrievalService getService() {
      return MessageRetrievalService.this;
    }
  }


  // ------------------------------- LOCATION -----------------------------------------
  @Override
  public void onLocationUpdated (Location location)
  {
    if (location != null)
    {
      Log.i(TAG, ">> onLocationUpdated:" + String.format("Latitude %.6f, Longitude %.6f",
              location.getLatitude(),
              location.getLongitude()));

      ufLocation.getInstance().setCurrentLocation(location);

      new ReverseGeocodingTask(ApplicationContext.getInstance().getApplicationContext(), this).execute(location);

      //At this stage this isnot useful, as it transmits old address location. we'd better wait for geodecoding event
      //ApplicationContext.getInstance().getJobManager().add(new LocationRefreshJob(this));
      //ApplicationContext.getInstance().getUfsrvcmdEvents().post(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
    }
    else
    {
      Log.i(TAG, ">> Location: was NULL");
    }

  }


  @Override
  public void onAddressAvailable (Address address)
  {
    if (address == null)
    {
      Log.d(TAG, ">> onAddressAvailable: Available reverse geo returned null");
    }
    else
    {
      final String locality = String.format("%s, %s, %s", address.getLocality(), address.getAdminArea(), address.getCountryName());
      Log.d(TAG, ">> onAddressAvaialble: reverse geocding returned: " + locality);

      ufLocation.getInstance().setCurrentAddress(address);

      //inform server.
      ApplicationDependencies.getJobManager().add(new LocationRefreshJob());

      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new LocationV1SystemEvent(UfsrvEventsNames.EVENT_LOCATIONCHANGE, ufLocation.getInstance()));
    }

  }



  @Override
  public void onActivityUpdated (DetectedActivity detectedActivity)

  {
    ufLocation myL = ufLocation.getInstance();

    if (detectedActivity != null)
    {
      synchronized (this)
      {
        myL.setCurrentActivity(myL.describeActivity(detectedActivity));
        myL.setCurrentActivityConfidence(myL.describeActivityConfidence(detectedActivity));
      }
      return;
    }
    Log.d(TAG, ">> onActivityUpdated contained null Activity");

  }


  private Location initLocation ()
  {
    Log.d(TAG, ">> initLocation entered...");

    SmartLocation smartLocation = new SmartLocation.Builder(ApplicationContext.getInstance().getApplicationContext()).logging(true).build();

    smartLocation.location().start(this);

    //we'll probably get null for brand new install of the app
    Location myL = smartLocation.location().getLastLocation();
    //if (myL!=null) ufLocation.getInstance().setInitialised(myL);
    if (myL == null)
    {
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

// ----------------------------- END OF LOCATION -----------------------------------------------------*/

}
