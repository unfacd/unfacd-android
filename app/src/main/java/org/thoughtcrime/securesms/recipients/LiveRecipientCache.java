package org.thoughtcrime.securesms.recipients;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import com.annimon.stream.function.Consumer;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.MissingRecipientException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.signal.core.util.CursorUtil;
import org.thoughtcrime.securesms.util.LRUCache;
import org.thoughtcrime.securesms.util.Stopwatch;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.concurrent.FilteredExecutor;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.push.ACI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;

public final class LiveRecipientCache {

  private static final String TAG = Log.tag(LiveRecipientCache.class);

  private static final int CACHE_MAX              = 1000;
  private static final int THREAD_CACHE_WARM_MAX  = 500;
  private static final int CONTACT_CACHE_WARM_MAX = 50;

  private final Context                         context;
  private final RecipientDatabase               recipientDatabase;
  private final Map<RecipientId, LiveRecipient> recipients;
  private final LiveRecipient                   unknown;
  private final Executor                        resolveExecutor;

  private final Map<String, LiveRecipient>            recipientsUfsrvUid; //AA+
  private final Map<RecipientUfsrvId, LiveRecipient>  recipientsUfsrvId; //AA+
  private final LiveRecipient                         ufsrvUnknown;
  private final LiveRecipient                         ufsrvSystemUser;

  private final AtomicReference<RecipientId> localRecipientId;
  private final AtomicBoolean                warmedUp;

  @SuppressLint("UseSparseArrays")
  public LiveRecipientCache(@NonNull Context context) {
    this.context           = context.getApplicationContext();
    this.recipientDatabase = SignalDatabase.recipients();
    this.recipients        = new LRUCache<>(CACHE_MAX);
    this.recipientsUfsrvUid= new LRUCache<>(CACHE_MAX);//AA+
    this.recipientsUfsrvId = new LRUCache<>(CACHE_MAX);//AA+
    this.warmedUp          = new AtomicBoolean(false);
    this.localRecipientId  = new AtomicReference<>(null);
    this.ufsrvUnknown      = new LiveRecipient(context, Recipient.UFSRV);//AA+ denotes a user designated with an unknown ufrsvuid value
    this.ufsrvSystemUser   = new LiveRecipient(context, Recipient.UFSRV_SYSTEM_USER);//AA+ convenient recipient for ufsrv whole-of-network user
    this.unknown           = new LiveRecipient(context, Recipient.UNKNOWN);
    this.resolveExecutor   = ThreadUtil.trace(new FilteredExecutor(SignalExecutors.BOUNDED, () -> !SignalDatabase.inTransaction()));
  }

  @AnyThread
  @NonNull LiveRecipient getLive(@NonNull RecipientId id) {
    if (id.isUnknown()) return unknown;

    if (id.isUfsrv()) return ufsrvUnknown;//AA+

    LiveRecipient live;
    boolean       needsResolve;

    synchronized (recipients) {
      live = recipients.get(id);

      if (live == null) {
        live = new LiveRecipient(context, new Recipient(id));
        recipients.put(id, live);
        needsResolve = true;
      } else {
        needsResolve = false;
      }
    }

    if (needsResolve) {
      resolveExecutor.execute(live::resolve);
    }

    return live;
  }

  //AA+
  @AnyThread
  synchronized @NonNull LiveRecipient getLiveUfsrvUid(@NonNull String ufsrvUid) {
    if (TextUtils.isEmpty(ufsrvUid) || UfsrvUid.UndefinedUfsrvUidTruncated.equals(ufsrvUid)) {
      Log.e(TAG, String.format("getLiveUfsrvUid: INVALID UfsrvUid: '%s''", ufsrvUid));
      return ufsrvUnknown;
    }

    if (UfsrvUid.UndefinedUfsrvUid.equals(ufsrvUid)) {
      return ufsrvSystemUser;
    }

    LiveRecipient live;
    boolean       needsResolve;

    synchronized (recipientsUfsrvUid) {
      live = recipientsUfsrvUid.get(ufsrvUid);

      if (live == null) {
         live = new LiveRecipient(context, new Recipient(ufsrvUid)); //marks Recipient as resolving
        recipientsUfsrvUid.put(ufsrvUid, live);
        needsResolve = true;
      } else {
        needsResolve = false;
      }
    }

    if (needsResolve) {
      final LiveRecipient toResolve = live;

      MissingRecipientException prettyStackTraceError = new MissingRecipientException(ufsrvUid);
      resolveExecutor.execute(() -> {
        try {
          toResolve.resolveUfsrvUid();
        } catch (MissingRecipientException e) {
          throw prettyStackTraceError;
        }
      });
    }

    return live;
  }

   @NonNull LiveRecipient getLiveUfsrvId(RecipientUfsrvId ufsrvId, boolean isSynchronous) {
    if (ufsrvId.isUnknown()) return unknown;

     LiveRecipient live;
     boolean       needsResolve;

     synchronized (recipientsUfsrvId) {
       live  = recipientsUfsrvId.get(ufsrvId);

       if (live == null) {
         live = new LiveRecipient(context, new Recipient(ufsrvId)); //marks Recipient as resolving
         recipientsUfsrvId.put(ufsrvId, live);
         needsResolve = true;
       } else {
         needsResolve = false;
       }
     }

     if (needsResolve) {
       final LiveRecipient toResolve = live;

       MissingRecipientException prettyStackTraceError = new MissingRecipientException(ufsrvId);
       if (!isSynchronous) {
         resolveExecutor.execute(() -> {
           try {
             toResolve.resolveUfsrvId();
           }
           catch (MissingRecipientException e) {
             throw prettyStackTraceError;
           }
         });
       } else {
         try {
           Log.w(TAG, String.format("getLiveUfsrvId ('%s'): Resolving synchronously...", ufsrvId.serialize()));
           toResolve.resolveUfsrvId();
         }
         catch (MissingRecipientException e) {
           throw prettyStackTraceError;
         }
       }
     }

     return live;

  }

  /**
   *
   * @param id the recipient id to kill
   * @param consumer apply user callback
   */
  @AnyThread
  synchronized @NonNull void killLive(@NonNull RecipientId id, Consumer<Pair<RecipientId, Recipient>> consumer) {
    if (id.isUnknown()) return;

    LiveRecipient live = recipients.get(id);

    if (live != null) {
      synchronisedRemove(live.get());
      consumer.accept(new Pair(id, live.get()));
      //todo notify listeners?
    }

  }

  /**
   * Necessary due to the multiple indexing of the same LiveRecipient. Only invoke if underlying recipient if fully resolved.
   * @param liveRecipient
   */
  synchronized void synchroniseUfsrvCache(LiveRecipient liveRecipient) {
    Recipient recipient = liveRecipient.get();
    if (recipient.equals(Recipient.UNKNOWN)) return;
    if (!TextUtils.isEmpty(recipient.getUfsrvUid())) recipientsUfsrvUid.put(recipient.getUfsrvUid(), liveRecipient);
    if (recipient.getUfsrvId() > 0) recipientsUfsrvId.put(RecipientUfsrvId.from(recipient.getUfsrvId()), liveRecipient);

  }

  void synchronisedRemove(Recipient recipient) {
    synchronized ( recipient) {
      if (!TextUtils.isEmpty(recipient.getUfsrvUid())) recipientsUfsrvUid.remove(recipient.getUfsrvUid());
      recipientsUfsrvId.remove(RecipientUfsrvId.from(recipient.getUfsrvId()));
      recipients.remove(recipient.getId());
    }

  }

  synchronized void synchroniseUfsrvUidCache(LiveRecipient liveRecipient) {
    Recipient recipient = liveRecipient.get();
    if (recipient.equals(Recipient.UNKNOWN)) return;
    recipients.put(recipient.getId(), liveRecipient);
    if (!TextUtils.isEmpty(recipient.getUfsrvUid())) recipientsUfsrvUid.put(recipient.getUfsrvUid(), liveRecipient);

  }

  synchronized void synchroniseUfsrvIdCache(LiveRecipient liveRecipient) {
    Recipient recipient = liveRecipient.get();
    if (recipient.equals(Recipient.UNKNOWN)) return;
    recipients.put(recipient.getId(), liveRecipient);
    if (recipient.getUfsrvId() > 0) recipientsUfsrvId.put(RecipientUfsrvId.from(recipient.getUfsrvId()), liveRecipient);

  }

  //

  /**
   * Handles remapping cache entries when recipients are merged.
   */
  public void remap(@NonNull RecipientId oldId, @NonNull RecipientId newId) {
    synchronized (recipients) {
      if (recipients.containsKey(newId)) {
        recipients.put(oldId, recipients.get(newId));
      } else {
        recipients.remove(oldId);
      }
    }
  }

  /**
   * Adds a recipient to the cache if we don't have an entry. This will also update a cache entry
   * if the provided recipient is resolved, or if the existing cache entry is unresolved.
   *
   * If the recipient you add is unresolved, this will enqueue a resolve on a background thread.
   */
  @AnyThread
  public void addToCache(@NonNull Collection<Recipient> newRecipients) {
    newRecipients.stream().filter(this::isValidForCache).forEach(recipient -> {
      LiveRecipient live;
      boolean       needsResolve;

      synchronized (recipients) {
        live = recipients.get(recipient.getId());

        if (live == null) {
          live = new LiveRecipient(context, recipient);
          recipients.put(recipient.getId(), live);
          needsResolve = recipient.isResolving();
        } else if (live.get().isResolving() || !recipient.isResolving()) {
          live.set(recipient);
          needsResolve = recipient.isResolving();
        } else {
          needsResolve = false;
        }
      }

      if (needsResolve) {
        LiveRecipient toResolve = live;

        MissingRecipientException prettyStackTraceError = new MissingRecipientException(toResolve.getId());
        resolveExecutor.execute(() -> {
          try {
            toResolve.resolve();
          } catch (MissingRecipientException e) {
            throw prettyStackTraceError;
          }
        });
      }
    });
  }

  @NonNull Recipient getSelf() {
    RecipientId selfId;
    synchronized (localRecipientId) {
      selfId = localRecipientId.get();
    }
    if (selfId == null) {
      ACI    localAci  = SignalStore.account().getAci();
      String localE164 = SignalStore.account().getE164();

      //AA+
      String selfUfsrvUid = TextSecurePreferences.getUfsrvUserId(context);
      if (selfUfsrvUid.equals(UfsrvUid.UndefinedUfsrvUid)) {
        throw new IllegalStateException("Tried to call getSelf() before local data was set! Are you registered yet?");
      }

      if (localAci == null && localE164 == null) {
        throw new IllegalStateException("Tried to call getSelf() before local data was set!");
      }

      if (localAci != null) {
        selfId = recipientDatabase.getByServiceId(localAci).orElse(null);
      }

      if (selfId == null) {
        selfId = recipientDatabase.getByUfsrvUid(selfUfsrvUid).orElse(null);//AA+
      }

      if (selfId == null && localE164 != null) {
        selfId = recipientDatabase.getByE164(localE164).orElse(null);
      }

      if (selfId == null) {
        throw new MissingRecipientException("null-> no id");//AA+
        //selfId = recipientDatabase.getAndPossiblyMerge(localAci, localE164, true);
      }

      synchronized (localRecipientId) {
        if (localRecipientId.get() == null) {
          localRecipientId.set(selfId);
        }
      }
    }
    return getLive(selfId).resolve();
  }

  @AnyThread
  public void warmUp() {
    if (warmedUp.getAndSet(true)) {
      return;
    }

    Stopwatch stopwatch = new Stopwatch("recipient-warm-up");

    SignalExecutors.BOUNDED.execute(() -> {
      ThreadDatabase  threadDatabase = SignalDatabase.threads();
      List<Recipient> recipients     = new ArrayList<>();

      try (ThreadDatabase.Reader reader = threadDatabase.readerFor(threadDatabase.getRecentConversationList(THREAD_CACHE_WARM_MAX, false))) {
        int          i      = 0;
        ThreadRecord record = null;

        while ((record = reader.getNext()) != null && i < THREAD_CACHE_WARM_MAX) {
          recipients.add(record.getRecipient());
          i++;
        }
      }

      Log.d(TAG, "Warming up " + recipients.size() + " thread recipients.");
      addToCache(recipients);

      stopwatch.split("thread");

      if (SignalStore.registrationValues().isRegistrationComplete() && SignalStore.account().getAci() != null) {
        try (Cursor cursor = SignalDatabase.recipients().getNonGroupContacts(false)) {
          int count = 0;
          while (cursor != null && cursor.moveToNext() && count < CONTACT_CACHE_WARM_MAX) {
            RecipientId id = RecipientId.from(CursorUtil.requireLong(cursor, RecipientDatabase.ID));
            Recipient.resolved(id);
            count++;
          }

          Log.d(TAG, "Warmed up " + count + " contact recipient.");

          stopwatch.split("contact");
        }
      }

      stopwatch.stop(TAG);
    });
  }

  @AnyThread
  public void clearSelf() {
    synchronized (localRecipientId) {
      localRecipientId.set(null);
    }
  }

  @AnyThread
  public void clear() {
    synchronized (recipients) {
      recipients.clear();
    }
  }

  private boolean isValidForCache(@NonNull Recipient recipient) {
    return !recipient.getId().isUnknown() && (recipient.hasServiceId() || recipient.getGroupId().isPresent() || recipient.hasSmsAddress());
  }

}