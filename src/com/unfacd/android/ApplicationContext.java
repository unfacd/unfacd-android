/*
 * Copyright (C) 2013 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.unfacd.android;

import android.annotation.SuppressLint;
import androidx.camera.camera2.Camera2AppConfig;
import androidx.camera.core.CameraX;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import androidx.annotation.NonNull;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.StrictMode.VmPolicy;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.logging.Log;

import com.google.android.gms.security.ProviderInstaller;

import org.conscrypt.Conscrypt;
import org.signal.aesgcmprovider.AesGcmProvider;

import com.facebook.stetho.Stetho;
import com.unfacd.android.service.PresenceReceiverListener;

import org.thoughtcrime.securesms.components.TypingStatusRepository;
import org.thoughtcrime.securesms.components.TypingStatusSender;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider;
import org.thoughtcrime.securesms.gcm.FcmJobService;
import org.thoughtcrime.securesms.jobmanager.DependencyInjector;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.FastJobStorage;
import org.thoughtcrime.securesms.jobs.JobManagerFactories;
import org.thoughtcrime.securesms.jobmanager.impl.JsonDataSerializer;
import org.thoughtcrime.securesms.jobs.CreateSignedPreKeyJob;
import org.thoughtcrime.securesms.jobs.FcmRefreshJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.jobs.PushNotificationReceiveJob;
import org.thoughtcrime.securesms.jobs.RefreshUnidentifiedDeliveryAbilityJob;
import org.thoughtcrime.securesms.logging.AndroidLogger;
import org.thoughtcrime.securesms.logging.CustomSignalProtocolLogger;
import org.thoughtcrime.securesms.logging.PersistentLogger;
import org.thoughtcrime.securesms.logging.UncaughtExceptionLogger;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.providers.BlobProvider;
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.service.IncomingMessageObserver;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.service.LocalBackupListener;
import org.thoughtcrime.securesms.revealable.RevealableMessageManager;
import org.thoughtcrime.securesms.service.RotateSenderCertificateListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.dynamiclanguage.DynamicLanguageContextWrapper;
import org.thoughtcrime.securesms.service.UpdateApkRefreshListener;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import org.whispersystems.libsignal.logging.SignalProtocolLoggerProvider;
import org.whispersystems.signalservice.api.util.CredentialsProvider;

import java.util.ArrayList;
import java.security.Security;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.greenrobot.eventbus.EventBus;


/**
 * Will be called once when the TextSecure process is created.
 *
 * We're using this as an insertion point to patch up the Android PRNG disaster,
 * to initialize the job manager, and to check for GCM registration freshness.
 *
 * @author Moxie Marlinspike
 */
public class ApplicationContext extends androidx.multidex.MultiDexApplication implements DefaultLifecycleObserver {

  private ExpiringMessageManager   expiringMessageManager;
  private RevealableMessageManager revealableMessageManager;
  private TypingStatusRepository   typingStatusRepository;
  private TypingStatusSender       typingStatusSender;
  private JobManager               jobManager;
  private IncomingMessageObserver  incomingMessageObserver;
  private PersistentLogger         persistentLogger;

  private volatile boolean isAppVisible;

  private EventBus ufsrvcmdEvents; //

  private static final String TAG = ApplicationContext.class.getSimpleName();

  //
  private static ApplicationContext instance = null;

  public static ApplicationContext getInstance() {
    if (instance != null) {
      return instance;
    } else {
      return new ApplicationContext();
    }
  }
  //

  public static ApplicationContext getInstance(Context context) {
    return (ApplicationContext)context.getApplicationContext();
  }

  @Override
  public void onCreate() {
    instance = this; //

    super.onCreate();
    //initializeDeveloperBuild();
    initializeSecurityProvider();
    initializeLogging();
    initializeCrashHandling();

    ufsrvcmdEvents = EventBus.builder().build(); //

    initializeAppDependencies();
    initializeJobManager();
    initializeMessageRetrieval();
    initializeExpiringMessageManager();
    initializeRevealableMessageManager();
    initializeTypingStatusRepository();
    initializeTypingStatusSender();
    initializeGcmCheck();
    initializeSignedPreKeyCheck();
    initializePeriodicTasks();
    initializeCircumvention();
    initializeWebRtc();
    initializePendingMessages();
    initializeUnidentifiedDeliveryAbilityRefresh();
    initializeBlobProvider();
    initializeCameraX();
    NotificationChannels.create(this);
    ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

    initialiseStetho(); //
    retrieveAppSignatureHashcode();//

  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {
    isAppVisible = true;
    Log.i(TAG, "App is now visible.");
    executePendingContactSync();
    KeyCachingService.onAppForegrounded(this);
    MessageNotifier.cancelMessagesPending(this);
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner)
  {
    isAppVisible = false;
    Log.i(TAG, "App is no longer visible.");
    KeyCachingService.onAppBackgrounded(this);
    MessageNotifier.setVisibleThread(-1);
  }

  void initialiseStetho ()
  {
    Stetho.initializeWithDefaults(this);
  }

  void retrieveAppSignatureHashcode ()
  {
    com.unfacd.android.utils.AppSignatureHelper helper = new  com.unfacd.android.utils.AppSignatureHelper(this);
    ArrayList<String> signatures=helper.getAppSignatures();
    for (String signature: signatures){
      Log.i(TAG, ">> signature code: "+signature);
    }
  }

  public EventBus getUfsrvcmdEvents() {return ufsrvcmdEvents;}

  public JobManager getJobManager() {
    return jobManager;
  }

  public ExpiringMessageManager getExpiringMessageManager() {
      return expiringMessageManager;
    }

  public RevealableMessageManager getRevealableMessageManager() {
    return revealableMessageManager;
  }

  public TypingStatusRepository getTypingStatusRepository() {
    return typingStatusRepository;
  }

  public TypingStatusSender getTypingStatusSender() {
    return typingStatusSender;
  }

  public boolean isAppVisible() {
    return isAppVisible;
  }

  private void initializeDeveloperBuild() {
    if (BuildConfig.DEV_BUILD) {
//      LeakCanary.install(this);
      StrictMode.setThreadPolicy(new ThreadPolicy.Builder().detectAll()
                                                           .penaltyLog()
                                                           .build());
      StrictMode.setVmPolicy(new VmPolicy.Builder().detectAll().penaltyLog().build());
    }
  }

  public PersistentLogger getPersistentLogger() {
    return persistentLogger;
  }

  private void initializeSecurityProvider() {
    try {
      Class.forName("org.signal.aesgcmprovider.AesGcmCipher");
    } catch (ClassNotFoundException e) {
      Log.e(TAG, "Failed to find AesGcmCipher class");
      throw new ProviderInitializationException();
    }

    int aesPosition = Security.insertProviderAt(new AesGcmProvider(), 1);
    Log.i(TAG, "Installed AesGcmProvider: " + aesPosition);

    if (aesPosition < 0) {
      Log.e(TAG, "Failed to install AesGcmProvider()");
      throw new ProviderInitializationException();
    }

    int conscryptPosition = Security.insertProviderAt(Conscrypt.newProvider(), 2);
    Log.i(TAG, "Installed Conscrypt provider: " + conscryptPosition);

    if (conscryptPosition < 0) {
      Log.w(TAG, "Did not install Conscrypt provider. May already be present.");
    }
  }

  private void initializeLogging() {
    persistentLogger = new PersistentLogger(this);
    org.thoughtcrime.securesms.logging.Log.initialize(new AndroidLogger(), persistentLogger);
    SignalProtocolLoggerProvider.setProvider(new CustomSignalProtocolLogger());
  }

  private void initializeCrashHandling() {
    final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionLogger(originalHandler));
  }

  private void initializeJobManager() {
    this.jobManager = new JobManager(this, new JobManager.Configuration.Builder()
            .setDataSerializer(new JsonDataSerializer())
            .setJobFactories(JobManagerFactories.getJobFactories(this))
            .setConstraintFactories(JobManagerFactories.getConstraintFactories(this))
            .setConstraintObservers(JobManagerFactories.getConstraintObservers(this))
            .setJobStorage(new FastJobStorage(DatabaseFactory.getJobDatabase(this)))
            .build());
  }


  public void initializeMessageRetrieval() {
    this.incomingMessageObserver = new IncomingMessageObserver(this);
  }

  public void notifyIncomingMessageObserver () { // signalling mechanism to kick off websock connection
    if (incomingMessageObserver!=null) {
      incomingMessageObserver.onConstraintMet("");
    }
  }

  private void initializeAppDependencies() {
    ApplicationDependencies.init(new ApplicationDependencyProvider(this, new SignalServiceNetworkAccess(this)));
  }

  private void initializeGcmCheck() {
    if (TextSecurePreferences.isPushRegistered(this)/* &&
        TextSecurePreferences.getGcmRegistrationId(this) == null*/) {
      long nextSetTime = TextSecurePreferences.getFcmTokenLastSetTime(this) + TimeUnit.HOURS.toMillis(6);

      if (TextSecurePreferences.getFcmToken(this) == null || nextSetTime <= System.currentTimeMillis()) {
        this.jobManager.add(new FcmRefreshJob());
      }
    }
  }

  private void initializeSignedPreKeyCheck() {
    if (!TextSecurePreferences.isSignedPreKeyRegistered(this)) {
      jobManager.add(new CreateSignedPreKeyJob(this));
    }
    else
    {
      Log.d(TAG, ">>> initializeSignedPreKeyCheck(): SignedPreKey already registered...");
    }
  }

  private void initializeExpiringMessageManager() {
    this.expiringMessageManager = new ExpiringMessageManager(this);
  }

  private void initializeRevealableMessageManager() {
    this.revealableMessageManager = new RevealableMessageManager(this);
  }

  private void initializeTypingStatusRepository() {
    this.typingStatusRepository = new TypingStatusRepository();
  }

  private void initializeTypingStatusSender() {
    this.typingStatusSender = new TypingStatusSender(this);
  }

  private void initializePeriodicTasks() {
    RotateSignedPreKeyListener.schedule(this);
    DirectoryRefreshListener.schedule(this);
    LocalBackupListener.schedule(this);
    RotateSenderCertificateListener.schedule(this);

    if (BuildConfig.PLAY_STORE_DISABLED) {
      UpdateApkRefreshListener.schedule(this);
    }

    //
    PresenceReceiverListener.schedule(this);
    //
  }

  private void initializeWebRtc() {
    try {
      Set<String> HARDWARE_AEC_BLACKLIST = new HashSet<String>() {{
        add("Pixel");
        add("Pixel XL");
        add("Moto G5");
        add("Moto G (5S) Plus");
        add("Moto G4");
        add("TA-1053");
        add("Mi A1");
        add("E5823"); // Sony z5 compact
        add("Redmi Note 5");
        add("FP2"); // Fairphone FP2
        add("MI 5");
      }};

      Set<String> OPEN_SL_ES_WHITELIST = new HashSet<String>() {{
        add("Pixel");
        add("Pixel XL");
      }};

      if (HARDWARE_AEC_BLACKLIST.contains(Build.MODEL)) {
        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
      }

      if (!OPEN_SL_ES_WHITELIST.contains(Build.MODEL)) {
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
      }

      PeerConnectionFactory.initialize(InitializationOptions.builder(this).createInitializationOptions());
    } catch (UnsatisfiedLinkError e) {
      Log.w(TAG, e);
    }
  }

  @SuppressLint("StaticFieldLeak")
  private void initializeCircumvention() {
    AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
          if (new SignalServiceNetworkAccess(ApplicationContext.this).isCensored(ApplicationContext.this)) {
         try {
             ProviderInstaller.installIfNeeded(ApplicationContext.this);
           } catch (Throwable t) {
             Log.w(TAG, t);
           }
       }
          return null;
        }
    };

    task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private void executePendingContactSync() {
    if (TextSecurePreferences.needsFullContactSync(this)) {
      ApplicationContext.getInstance(this).getJobManager().add(new MultiDeviceContactUpdateJob(this, true));
    }
  }

  private void initializePendingMessages() {
    if (TextSecurePreferences.getNeedsMessagePull(this)) {
      Log.i(TAG, "Scheduling a message fetch.");
      if (Build.VERSION.SDK_INT >= 26) {
        FcmJobService.schedule(this);
      } else {
        ApplicationContext.getInstance(this).getJobManager().add(new PushNotificationReceiveJob(this));
      }
      TextSecurePreferences.setNeedsMessagePull(this, false);
    }
  }

  private void initializeUnidentifiedDeliveryAbilityRefresh() {
    if (TextSecurePreferences.isMultiDevice(this) && !TextSecurePreferences.isUnidentifiedDeliveryEnabled(this)) {
      jobManager.add(new RefreshUnidentifiedDeliveryAbilityJob());
    }
  }

  private void initializeBlobProvider() {
    AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
      BlobProvider.getInstance().onSessionStart(this);
    });
  }

  @SuppressLint("RestrictedApi")
  private void initializeCameraX() {
    if (Build.VERSION.SDK_INT >= 21) {
      try {
        CameraX.init(this, Camera2AppConfig.create(this));
      } catch (Throwable t) {
        Log.w(TAG, "Failed to initialize CameraX.");
      }
    }
  }

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(DynamicLanguageContextWrapper.updateContext(base, TextSecurePreferences.getLanguage(base)));
    androidx.multidex.MultiDex.install(this);//
  }

  private static class ProviderInitializationException extends RuntimeException {
  }

  // provides basic creditials across the app
  public static class DynamicCredentialsProvider implements CredentialsProvider
  {

    private final Context context;

    public DynamicCredentialsProvider() {
      this.context = ApplicationContext.instance;
    }

    @Override
    public String getUser() {
      return TextSecurePreferences.getUfsrvUserId(context);
    }

    @Override
    public String getPassword() {
      return TextSecurePreferences.getPushServerPassword(context);
    }

    @Override
    public String getSignalingKey() {
      return TextSecurePreferences.getSignalingKey(context);
    }

    //
    @Override
    public String getCookie() {return TextSecurePreferences.getUfsrvCookie(context);}

    @Override
    public void setCookie (String cookie)
    {
      //this.cookie=cookie;
    }
  }
  //
}
