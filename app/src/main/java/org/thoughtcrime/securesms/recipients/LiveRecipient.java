package org.thoughtcrime.securesms.recipients;

import android.content.Context;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvuid.RecipientUfsrvId;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.DistributionListDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.MissingRecipientException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.DistributionListRecord;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import io.reactivex.rxjava3.core.Observable;

public final class LiveRecipient {

  private static final String TAG = Log.tag(LiveRecipient.class);

  private final Context                       context;
  private final MutableLiveData<Recipient>    liveData;
  private final LiveData<Recipient>           observableLiveData;
  private final LiveData<Recipient>           observableLiveDataResolved;
  private final Set<RecipientForeverObserver> observers;
  private final Observer<Recipient>           foreverObserver;
  private final AtomicReference<Recipient>    recipient;
  private final RecipientDatabase             recipientDatabase;
  private final GroupDatabase                 groupDatabase;
  private final DistributionListDatabase      distributionListDatabase;
  private final MutableLiveData<Object>       refreshForceNotify;

  //AA+
  private static final Map<String, RecipientDetails> STATIC_DETAILS = new HashMap<String, RecipientDetails>() {{
    put("0", new RecipientDetails("unfacd", null, null, Optional.of(RecipientUfsrvId.from(Long.valueOf(0))), false, false, RecipientDatabase.RegisteredState.UNKNOWN, null, null, false));//AA+
  }};

  LiveRecipient(@NonNull Context context, @NonNull Recipient defaultRecipient) {
    this.context                  = context.getApplicationContext();
    this.liveData                 = new MutableLiveData<>(defaultRecipient);
    this.recipient                = new AtomicReference<>(defaultRecipient);
    this.recipientDatabase        = SignalDatabase.recipients();
    this.groupDatabase            = SignalDatabase.groups();
    this.distributionListDatabase = SignalDatabase.distributionLists();
    this.observers                = new CopyOnWriteArraySet<>();
    this.foreverObserver          = recipient -> {
      ThreadUtil.postToMain(() -> {
        for (RecipientForeverObserver o : observers) {
          o.onRecipientChanged(recipient);
        }
      });
    };
    this.refreshForceNotify = new MutableLiveData<>(new Object());
    this.observableLiveData = LiveDataUtil.combineLatest(LiveDataUtil.distinctUntilChanged(liveData, Recipient::hasSameContent),
                                                         refreshForceNotify,
                                                         (recipient, force) -> recipient);
    this.observableLiveDataResolved = LiveDataUtil.filter(this.observableLiveData, r -> !r.isResolving());
  }

  public @NonNull RecipientId getId() {
    return recipient.get().getId();
  }

  //AA+
  public @NonNull String getUfsrvUId() {
    return recipient.get().getUfsrvUid();
  }

  public @NonNull RecipientUfsrvId getUfsrvId() {
    return RecipientUfsrvId.from(recipient.get().getUfsrvId());
  }
  //

  /**
   * @return A recipient that may or may not be fully-resolved.
   */
  public @NonNull Recipient get() {
    return recipient.get();
  }

  /**
   * Watch the recipient for changes. The callback will only be invoked if the provided lifecycle is
   * in a valid state. No need to remove the observer. If you do wish to remove the observer (if,
   * for instance, you wish to remove the listener before the end of the owner's lifecycle), you can
   * use {@link #removeObservers(LifecycleOwner)}.
   */
  public void observe(@NonNull LifecycleOwner owner, @NonNull Observer<Recipient> observer) {
    ThreadUtil.postToMain(() -> observableLiveData.observe(owner, observer));
  }

  /**
   * Removes all observers of this data registered for the given LifecycleOwner.
   */
  public void removeObservers(@NonNull LifecycleOwner owner) {
    ThreadUtil.runOnMain(() -> observableLiveData.removeObservers(owner));
  }

  public Observable<Recipient> asObservable() {
    return Observable.create(emitter -> {
      Recipient current = recipient.get();
      if (current != null && current.getId() != RecipientId.UNKNOWN) {
        emitter.onNext(current);
      }

      RecipientForeverObserver foreverObserver = emitter::onNext;
      observeForever(foreverObserver);
      emitter.setCancellable(() -> removeForeverObserver(foreverObserver));
    });
  }

  /**
   * Watch the recipient for changes. The callback could be invoked at any time. You MUST call
   * {@link #removeForeverObserver(RecipientForeverObserver)} when finished. You should use
   * {@link #observe(LifecycleOwner, Observer<Recipient>)} if possible, as it is lifecycle-safe.
   */
  public void observeForever(@NonNull RecipientForeverObserver observer) {
    ThreadUtil.postToMain(() -> {
      if (observers.isEmpty()) {
        observableLiveData.observeForever(foreverObserver);
      }
      observers.add(observer);
    });
  }

  /**
   * Unsubscribes the provided {@link RecipientForeverObserver} from future changes.
   */
  public void removeForeverObserver(@NonNull RecipientForeverObserver observer) {
    ThreadUtil.postToMain(() -> {
      observers.remove(observer);

      if (observers.isEmpty()) {
        observableLiveData.removeObserver(foreverObserver);
      }
    });
  }

  /**
   * @return A fully-resolved version of the recipient. May require reading from disk.
   */
//  @WorkerThread
//  public @NonNull Recipient resolve() {
//    Recipient recipient = get();
//
//    if (recipient.isResolving()) {
//      recipient = fetchRecipientFromDisk(defaultRecipient.getId());
//      ApplicationDependencies.getRecipientCache().synchroniseCache(this);//AA+
//      liveData.postValue(recipient);
//      Stream.of(recipient.getParticipants()).forEach(Recipient::resolve);
//    }
//
//    return recipient;
//  }

  @WorkerThread
  public @NonNull Recipient resolve() {
    Recipient current = recipient.get();

    if (!current.isResolving() || current.getId().isUnknown()) {
      return current;
    }

    if (ThreadUtil.isMainThread()) {
      Log.w(TAG, "[Resolve][MAIN] " + getId(), new Throwable());
    } else {
      Log.d(TAG, "[Resolve][" + Thread.currentThread().getName() + "] " + getId());
    }

    Recipient       updated      = fetchAndCacheRecipientFromDisk(getId());
    List<Recipient> participants = Stream.of(updated.getParticipants())
                                         .filter(Recipient::isResolving)
                                         .map(Recipient::getId)
                                         .map(this::fetchAndCacheRecipientFromDisk)
                                         .toList();

    for (Recipient participant : participants) {
      participant.live().set(participant);
    }

//    set(updated);

    //AA+
    this.recipient.set(updated);
    ApplicationDependencies.getRecipientCache().synchroniseUfsrvCache(this);
    this.liveData.postValue(updated);

    return updated;
  }

  //AA+
  /**
   * @return A fully-resolved version of the recipient. May require reading from disk, or network
   */
  @WorkerThread
  public @NonNull Recipient resolveUfsrvUid() {
    Recipient current = recipient.get();

    if (!current.isResolving()) {
      return current;
    }

    if (ThreadUtil.isMainThread()) {
      Log.w(TAG, "[ResolveUfsrvUid][MAIN] " + getId(), new Throwable());
    } else {
      Log.d(TAG, "[ResolveUfsrvUid][" + Thread.currentThread().getName() + "] " + getId());
    }

    Recipient       updated = fetchRecipientUfsrvUidFromDisk(getUfsrvUId());
    List<Recipient> participants = Stream.of(updated.getParticipants())
                                         .filter(Recipient::isResolving)
                                         .map(Recipient::getUfsrvUid)
                                         .map(this::fetchRecipientUfsrvUidFromDisk)
                                         .toList();

    for (Recipient participant : participants) {
      participant.live().set(participant);
    }

    this.recipient.set(updated);
    ApplicationDependencies.getRecipientCache().synchroniseUfsrvIdCache(this); //AA+
    this.liveData.postValue(updated);

    return updated;
  }

  //AA+
  /**
   * @return A fully-resolved version of the recipient with forced reading from the network regardless
   * of current values.
   */
  @WorkerThread
  public @NonNull Recipient resolveUfsrvUidByForcedNetwork() {
    if (ThreadUtil.isMainThread()) {
      Log.w(TAG, "[resolveUfsrvUidForcedNetwork][MAIN] " + getId(), new Throwable());
    } else {
      Log.d(TAG, "[resolveUfsrvUidForcedNetwork][" + Thread.currentThread().getName() + "] " + getId());
    }

    Recipient       updated = fetchRecipientUfsrvUidFromNetwork(getUfsrvUId());
    List<Recipient> participants = Stream.of(updated.getParticipants())
                                         .filter(Recipient::isResolving)
                                         .map(Recipient::getUfsrvUid)
                                         .map(this::fetchRecipientUfsrvUidFromDisk)
                                         .toList();

    for (Recipient participant : participants) {
      participant.live().set(participant);
    }

    this.recipient.set(updated);
    ApplicationDependencies.getRecipientCache().synchroniseUfsrvUidCache(this); //AA+
    this.liveData.postValue(updated);

    return updated;
  }

  /**
   * @return A fully-resolved version of the recipient. May require reading from disk, or network.
   */
  public @NonNull Recipient resolveUfsrvId() {
    Recipient current = recipient.get();

    if (!current.isResolving()) {
      return current;
    }

    if (ThreadUtil.isMainThread()) {
      Log.w(TAG, "[ResolveUfsrvId][MAIN] " + getId(), new Throwable());
    } else {
      Log.d(TAG, "[ResolveUfsrvId][" + Thread.currentThread().getName() + "] " + getId());
    }


    Recipient       updated = fetchRecipientUfsrvIdFromDisk(getUfsrvId());

    List<Recipient> participants = Stream.of(updated.getParticipants())
                                         .filter(Recipient::isResolving)
                                         .map(Recipient::getUfsrvId)
                                         .map((r) -> fetchRecipientUfsrvIdFromDisk(RecipientUfsrvId.from(r)))
                                         .toList();

    for (Recipient participant : participants) {
      participant.live().set(participant);
    }

    this.recipient.set(updated);
    ApplicationDependencies.getRecipientCache().synchroniseUfsrvIdCache(this); //AA+
    this.liveData.postValue(updated);

    return updated;

  }
  //

  @WorkerThread
  public void refresh() {
    refresh(getId());
  }

  /**
   * Forces a reload of the underlying recipient.
   */
  @WorkerThread
  public void refresh(@NonNull RecipientId id) {
    if (!getId().equals(id)) {
      Log.w(TAG, "Switching ID from " + getId() + " to " + id);
    }

    if (getId().isUnknown()) return;

    if (ThreadUtil.isMainThread()) {
      Log.w(TAG, "[Refresh][MAIN] " + getId(), new Throwable());
    } else {
      Log.d(TAG, "[Refresh][" + Thread.currentThread().getName() + "] " + id);
    }

    Recipient       recipient    = fetchAndCacheRecipientFromDisk(id);
//    Recipient recipient;
//    if (!defaultRecipient.getId().isUnknown()) recipient = fetchRecipientFromDisk(defaultRecipient.getId());
//    else if (!TextUtils.isEmpty(defaultRecipient.getUfsrvUid())) recipient = fetchRecipientUfsrvUidFromDisk(defaultRecipient.getUfsrvUid());
//    else if (defaultRecipient.getUfsrvId() > 0) recipient = fetchRecipientUfsrvIdFromDisk(RecipientUfsrvId.from(defaultRecipient.getUfsrvId()));
//    else {
//      Log.e("LiveRecipient", "UNDEFINED LiveRecipient STATE");
//      return;
//    }
    List<Recipient> participants = Stream.of(recipient.getParticipants())
                                         .map(Recipient::getId)
                                         .map(this::fetchAndCacheRecipientFromDisk)
                                         .toList();

    for (Recipient participant : participants) {
      participant.live().set(participant);
    }

    set(recipient);
    refreshForceNotify.postValue(new Object());
  }

  public @NonNull LiveData<Recipient> getLiveData() {
    return observableLiveData;
  }

  public @NonNull LiveData<Recipient> getLiveDataResolved() {
    return observableLiveDataResolved;
  }

  private @NonNull Recipient fetchAndCacheRecipientFromDisk(@NonNull RecipientId id) {
    try { //AA++ try
      RecipientRecord  record  = recipientDatabase.getRecord(id);
      RecipientDetails details;
      if (record.getGroupId() != null) {
        details = getGroupRecipientDetails(record);
      } else if (record.getDistributionListId() != null) {
        details = getDistributionListRecipientDetails(record);
      } else {//AA+
        if (record.getAddress().isE164Number()) {
          details = getIndividualContactRecipientDetails(context, record.getAddress().toPhoneString(), Optional.ofNullable(record));
        } else {
          details = RecipientDetails.forIndividual(context, record);
        }
      }

      Recipient recipient = new Recipient(id, details, true);//AA this constructor marks as fully resolved due to presence of 'details'
      RecipientIdCache.INSTANCE.put(recipient);
      return recipient;
    } catch (MissingRecipientException error) {
      Log.e(TAG, error.toString());
      return Recipient.UNKNOWN;
    }
  }

  //AA+
  /**
   * Specific for ufsrv style encoded user ids.
   * @param ufsrvUid ufsrv style encoded user id
   */
  private @NonNull Recipient fetchRecipientUfsrvUidFromDisk(String ufsrvUid) {
    try {
      RecipientRecord settings = recipientDatabase.getRecipientSettingsUfsrvUid(ufsrvUid);
      RecipientDetails  details  = RecipientDetails.forIndividual(context, settings);

      return new Recipient(settings.getId(), details, true); //AA this constructor marks as fully resolved due to presence of 'details'
    } catch (MissingRecipientException error) {
      return fetchRecipientUfsrvUidFromNetwork(ufsrvUid);
    }
  }

  private @NonNull Recipient fetchRecipientUfsrvUidFromNetwork(String ufsrvUid) {
    RecipientDatabase.InsertResult insertResult  = recipientDatabase.getOrInsertFromUfsrvUid(ufsrvUid);
    Optional<RecipientRecord> optionalSettings = Recipient.ufsrvUidResolve(context, insertResult.getRecipientId(), ufsrvUid); //synchronous network fetch
    if (optionalSettings.isPresent()) {
      RecipientDetails details = RecipientDetails.forIndividual(context, optionalSettings.get());

      Recipient recipient = new Recipient(optionalSettings.get().getId(), details, true);
      RecipientIdCache.INSTANCE.put(recipient);

      return recipient;
    }

    return Recipient.UNKNOWN;
  }

  /**
   *
   * @param recipientUfsrvId Numerical id. Could be user sequence id, or fence id.
   */
  private @NonNull Recipient fetchRecipientUfsrvIdFromDisk(RecipientUfsrvId recipientUfsrvId) {
    Recipient recipient = fetchRecipientUfsrvIdFromRecipientsDb(recipientUfsrvId);
    if (recipient.equals(Recipient.UNKNOWN)) {
      GroupRecord groupRecord = SignalDatabase.groups().getGroupRecordByFid(recipientUfsrvId.toId());
      if (groupRecord != null) {
        recipient = fetchRecipientIdFromRecipientsDb(groupRecord.getRecipientId());
        if (recipient.equals(Recipient.UNKNOWN)) {
          return fetchRecipientUfsrvIdFromNetwork(recipientUfsrvId);
        } else return recipient;
      }
    } else return recipient;//Already cached

    return Recipient.UNKNOWN;
  }

  /**
   * Retrieve Recipient's record based on identifieble ufsrv ID. For groups that's the fence ID.
   * @param recipientUfsrvId
   * @return
   */
  private @NonNull Recipient fetchRecipientUfsrvIdFromRecipientsDb(RecipientUfsrvId recipientUfsrvId)
  {
    try {
      RecipientDetails details;
      RecipientRecord settings = recipientDatabase.getRecipientSettingsUfsrvId(recipientUfsrvId);

      if (settings.getAddress().isGroup()) {
        details = getGroupRecipientDetails(settings);
      } else {
        details = RecipientDetails.forIndividual(context, settings);
      }

      Recipient recipient = new Recipient(settings.getId(), details, true); //AA this constructor marks as fully resolved due to presence of 'details'
      RecipientIdCache.INSTANCE.put(recipient);
      return recipient;

    } catch (MissingRecipientException error) {
      return Recipient.UNKNOWN;
    }
  }

  private @NonNull Recipient fetchRecipientIdFromRecipientsDb(RecipientId recipientId)
  {
    try {
      RecipientDetails details;
      RecipientRecord settings = recipientDatabase.getRecord(recipientId);

      if (settings.getAddress().isGroup()) {
        details = getGroupRecipientDetails(settings);
      } else {
        details = RecipientDetails.forIndividual(context, settings);
      }

      Recipient recipient = new Recipient(settings.getId(), details, true); //AA this constructor marks as fully resolved due to presence of 'details'
      RecipientIdCache.INSTANCE.put(recipient);
      return recipient;
    } catch (MissingRecipientException error) {
      return Recipient.UNKNOWN;
    }
  }

  /**
   * This should only be invoked when it is known for sure no Group or Recipient records are present locally. This network fetch
   * is fence id fetch only, since ufsrv doesn't have an api end for querying sequence ids.
   * @param recipientUfsrvId Numerical id. Could be user sequence id, or fence id.
   */
  private Recipient fetchRecipientUfsrvIdFromNetwork(RecipientUfsrvId recipientUfsrvId)
  {
    RecipientDatabase.InsertResult insertResult = recipientDatabase.getOrInsertFromUfsrvId(recipientUfsrvId);
    Optional<RecipientRecord> optionalSettings = Recipient.ufsrvIdNetworkResolve(context, insertResult.getRecipientId(), recipientUfsrvId); //synchronous network fetch
    if (optionalSettings.isPresent()) {
      RecipientDetails details = getGroupRecipientDetails(optionalSettings.get());

      Recipient recipient = new Recipient(optionalSettings.get().getId(), details, true);
      RecipientIdCache.INSTANCE.put(recipient);
      return recipient;
    }

    return Recipient.UNKNOWN;
  }
  //

  //AA+
  private @NonNull
  RecipientDetails getIndividualContactRecipientDetails(Context context, String contactAddress, Optional<RecipientRecord> settings) {
    if (!settings.isPresent()) {
      settings = SignalDatabase.recipients().getContactRecord(contactAddress);
    }

    if (!settings.isPresent() && STATIC_DETAILS.containsKey(contactAddress)) {
      return STATIC_DETAILS.get(contactAddress);
    } else if (settings.isPresent()) {
      boolean systemContact = !TextUtils.isEmpty(settings.get().getSystemProfileName().toString());
      boolean isSelf = contactAddress.equals(TextSecurePreferences.getUfsrvUserId(context));
      return new RecipientDetails(settings.get().getUfsrvUname(), null, Optional.empty(), Optional.empty(), systemContact, isSelf, settings.get().getRegistered(), settings.get(), null, false);
    }

    return new RecipientDetails(null, null, Optional.empty(), Optional.empty(), false, contactAddress.equals(TextSecurePreferences.getUfsrvUserId(context)), settings.get().getRegistered(), settings.orElse(null), null, false);
  }
  //

  @WorkerThread
  private @NonNull RecipientDetails getGroupRecipientDetails(@NonNull RecipientRecord record) {
    Optional<GroupRecord> groupRecord = groupDatabase.getGroup(record.getId());

    if (groupRecord.isPresent()) {
      String title = null; //AA+ paired group branch

      if (groupDatabase.isPairedGroup(record.getGroupId())) {//AA+
        title = get().toShortStylisedGroupName(record.getGroupId()).toString();//retrieving Recipient object to access the method.
      } else {
        title = groupRecord.get().getTitle();
      }

      List<Recipient> members  = Stream.of(groupRecord.get().getMembers()).map(address  -> Recipient.resolved(LocallyAddressableUfsrvUid.from(address.toString()))).toList(); //AA++ members saved as ufsrvuid, not recipientids
      Optional<Long>  avatarId = Optional.empty();

      if (groupRecord.get().hasAvatar()) {
        avatarId = Optional.of(groupRecord.get().getAvatarId());
      }

      return new RecipientDetails(title, null, avatarId, Optional.of(RecipientUfsrvId.from(groupRecord.get().getFid())), false, false, record.getRegistered(), record, members, false);
    }

    return new RecipientDetails(null, null, Optional.empty(), Optional.empty(), false, false, record.getRegistered(), record, null, false);
  }

  @WorkerThread
  private @NonNull RecipientDetails getDistributionListRecipientDetails(@NonNull RecipientRecord record) {
    DistributionListRecord groupRecord = distributionListDatabase.getList(Objects.requireNonNull(record.getDistributionListId()));

    // TODO [stories] We'll have to see what the perf is like for very large distribution lists. We may not be able to support fetching all the members.
    if (groupRecord != null) {
      String          title    = groupRecord.getName();
      List<Recipient> members  = Stream.of(groupRecord.getMembers()).filterNot(RecipientId::isUnknown).map(this::fetchAndCacheRecipientFromDisk).toList();

      return RecipientDetails.forDistributionList(title, members, record);
    }

    return RecipientDetails.forDistributionList(null, null, record);
  }

  synchronized void set(@NonNull Recipient recipient) {
    this.recipient.set(recipient);
    this.liveData.postValue(recipient);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LiveRecipient that = (LiveRecipient) o;
    return recipient.equals(that.recipient);
  }

  @Override
  public int hashCode() {
    return Objects.hash(recipient);
  }
}