package org.thoughtcrime.securesms.contacts.sync;

import android.Manifest;
import android.accounts.Account;
import android.content.Context;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.text.TextUtils;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.unfacd.android.BuildConfig;
import com.unfacd.android.R;
import com.unfacd.android.locallyaddressable.LocallyAddressable;
import com.unfacd.android.locallyaddressable.LocallyAddressablePhoneNumber;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.contacts.SystemContactsRepository;
import org.signal.contacts.SystemContactsRepository.ContactDetails;
import org.signal.contacts.UfsrvUserContact;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.contacts.ContactAccessor;
import org.thoughtcrime.securesms.database.MessageDatabase.InsertResult;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase.BulkOperationsHandle;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.registration.RegistrationUtil;
import org.thoughtcrime.securesms.sms.IncomingJoinedMessage;
import org.thoughtcrime.securesms.util.Stopwatch;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import androidx.annotation.NonNull;

public class DirectoryHelper {

  private static final String TAG = Log.tag(DirectoryHelper.class);

  private static final int CONTACT_DISCOVERY_BATCH_SIZE = 2048;

  static void refreshAll(@NonNull Context context, boolean notifyOfNewUsers) throws IOException {
    if (TextUtils.isEmpty(SignalStore.account().getE164())) return;
    if (!Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) return;

    List<RecipientId> newlyActiveUsers = refresh(context, ApplicationDependencies.getSignalServiceAccountManager());

    if (TextSecurePreferences.isMultiDevice(context)) {
      ApplicationDependencies.getJobManager().add(new MultiDeviceContactUpdateJob());
    }

    if (notifyOfNewUsers) notifyNewUsers(context, newlyActiveUsers);
  }

  private static @NonNull List<RecipientId> refresh(@NonNull Context context, @NonNull SignalServiceAccountManager accountManager) throws IOException
  {
    if (TextUtils.isEmpty(SignalStore.account().getE164())) {
      return new LinkedList<>();
    }

    if (!Permissions.hasAll(context, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) {
      Log.w(TAG, "No contact permissions. Skipping.");
      return new LinkedList<>();
    }

    if (!SignalStore.registrationValues().isRegistrationComplete()) {
      Log.w(TAG, "Registration is not yet complete. Skipping, but running a routine to possibly mark it complete.");
      RegistrationUtil.maybeMarkRegistrationComplete(context);

      return new LinkedList<>();
    }

    RecipientDatabase recipientDatabase                       = SignalDatabase.recipients();
    //AA investigate if can be used
    /*Set<String>       databaseE164s     = sanitizeNumbers(recipientDatabase.getAllE164s());
    Set<String>       systemE164s       = sanitizeNumbers(Stream.of(SystemContactsRepository.getAllDisplayNumbers(context))
                                                                .map(number -> PhoneNumberFormatter.get(context).format(number))
                                                                .collect(Collectors.toSet()));*/
    //AA+  param Recipient.RecipientType.USER
    Stream<String>    eligibleRecipientDatabaseContactNumbers = Stream.of(recipientDatabase.getAllAddresses(Recipient.RecipientType.USER)).filter(LocallyAddressable::isPhone).map(LocallyAddressable::toString);
    Stream<String>    eligibleSystemDatabaseContactNumbers    = Stream.of(ContactAccessor.getInstance().getAllContactsWithNumbers(context));

    Set<String>       eligibleContactNumbers                  = Stream.concat(eligibleRecipientDatabaseContactNumbers, eligibleSystemDatabaseContactNumbers).collect(Collectors.toSet());

    List<ContactTokenDetails> activeTokens = accountManager.getContacts(eligibleContactNumbers);

    if (activeTokens != null) {
      List<RecipientId> activeAddresses   = new LinkedList<>();
      List<RecipientId> inactiveAddresses = new LinkedList<>();

      Set<String>  inactiveContactNumbers = new HashSet<>(eligibleContactNumbers);

      for (ContactTokenDetails activeToken : activeTokens) {
        activeAddresses.add(Recipient.external(context, activeToken.getUfsrvUid()).getId());
        inactiveContactNumbers.remove(activeToken.getE164number()); //AA note we remove by 'number' key not ufsrvuid because the list is from systems contacts list
      }

      for (String inactiveContactNumber : inactiveContactNumbers) {
        inactiveAddresses.add(Recipient.external(context, inactiveContactNumber).getId());
      }

      Set<RecipientId>  currentActiveAddresses = new HashSet<>(recipientDatabase.getRegistered());
      Set<RecipientId>  contactAddresses       = new HashSet<>(recipientDatabase.getSystemContacts());
      List<RecipientId> newlyActiveAddresses   = Stream.of(activeAddresses)
                                                       .filter(address -> !currentActiveAddresses.contains(address))
                                                       .filter(contactAddresses::contains)
                                                       .toList();

      recipientDatabase.setRegistered(activeAddresses, inactiveAddresses, activeTokens);//AA+ active tokens
      updateContactsDatabase(context, activeAddresses, true, Collections.emptyMap(), activeTokens);//AA+ active tokens

      if (TextSecurePreferences.hasSuccessfullyRetrievedDirectory(context)) {
        return newlyActiveAddresses;
      } else {
        TextSecurePreferences.setHasSuccessfullyRetrievedDirectory(context, true);
        return new LinkedList<>();
      }
    }

    return new LinkedList<>();
  }

  //AA++
  static List<RegisteredState> refresh(@NonNull  List<Recipient> recipients) throws IOException
  {
    LinkedList<RegisteredState> registeredStates = new LinkedList<>();
    Stream.of(recipients).forEach(r -> {
      try {
        registeredStates.add(refreshDirectoryFor(LocallyAddressableUfsrvUid.from(r.requireUfsrvUid())));
      } catch (IOException x) {
        Log.d(TAG, String.format("refreshDirectoryFor: %s", r));
      }
    });

    return registeredStates;
  }

  /**
   * This is currently only used for e16 based number lookup, which requires hashing the number
   * @param recipient
   * @param notifyOfNewUsers
   * @return
   * @throws IOException
   */
  static RegisteredState refreshE164User(@NonNull Context context, @NonNull Recipient recipient, boolean notifyOfNewUsers) throws IOException
  {
    //AA+
    if (recipient.isGroup()) return RegisteredState.REGISTERED;

    RecipientDatabase             recipientDatabase = SignalDatabase.recipients();
    SignalServiceAccountManager   accountManager    = ApplicationDependencies.getSignalServiceAccountManager();;
    boolean                       activeUser        = recipient.resolve().getRegistered() == RegisteredState.REGISTERED;
    boolean                       systemContact     = recipient.isSystemContact();
    String                        number            = recipient.getE164number();
    Optional<ContactTokenDetails> details           = accountManager.getContact(number);//for non registered users this is an e164 number, not ufsrvuid

    if (details.isPresent()) {
      if (Permissions.hasAll(context, Manifest.permission.WRITE_CONTACTS)) {
        updateContactsDatabase(context, Util.asList(recipient.getId()),false, Collections.emptyMap(), Util.asList(details.get()));//AA+ second last list
      }

      if (!activeUser  && TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(new MultiDeviceContactUpdateJob());
      }

      if (!activeUser && systemContact /*&& !TextSecurePreferences.getNeedsSqlCipherMigration(context)*/) {//AA- https://github.com/signalapp/Signal-Android/commit/df2c5d38b0f8729ee35daa93e0364b3794dc4b5a
        notifyNewUsers(context, Collections.singletonList(recipient.getId()));
      }

      return RegisteredState.REGISTERED;
    } else {
      return RegisteredState.NOT_REGISTERED;
    }
  }

  static RegisteredState refreshDirectoryFor(@NonNull LocallyAddressableUfsrvUid locallyAddressable) throws IOException
  {
    SignalServiceAccountManager   accountManager    = ApplicationDependencies.getSignalServiceAccountManager();
    Optional<ContactTokenDetails> details           = accountManager.getContact(UfsrvUid.fromEncoded(locallyAddressable.toString()));

    if (!details.isPresent()) {
      RecipientDatabase recipientDatabase = SignalDatabase.recipients();
      return RegisteredState.NOT_REGISTERED;
    }

    return RegisteredState.REGISTERED;

  }

  static Optional<ContactTokenDetails> refreshWithContactDetails(@NonNull LocallyAddressableUfsrvUid addressableUfsrvUid) throws IOException
  {
    SignalServiceAccountManager   accountManager    = ApplicationDependencies.getSignalServiceAccountManager();
    Optional<ContactTokenDetails> details           = accountManager.getContact(UfsrvUid.fromEncoded(addressableUfsrvUid.toString()));

    if (details.isPresent()) {
      RecipientDatabase             recipientDatabase = SignalDatabase.recipients();
//      recipientDatabase.setRegistered(addressableUfsrvUid.getRecipientId(), RegisteredState.REGISTERED, details.get());
    }

    return details;
  }
  //

  /**
   * Reads the system contacts and copies over any matching data (like names) int our local store.
   */
  static void syncRecipientInfoWithSystemContacts(@NonNull Context context) {
    syncRecipientInfoWithSystemContacts(context, Collections.emptyMap(), Collections.EMPTY_LIST);
  }

  public static boolean hasSession(@NonNull RecipientId id) {
    Recipient recipient = Recipient.resolved(id);

    if (!recipient.hasServiceId()) {
      return false;
    }

    SignalProtocolAddress protocolAddress = Recipient.resolved(id).requireServiceId().toProtocolAddress(SignalServiceAddress.DEFAULT_DEVICE_ID);

    return ApplicationDependencies.getProtocolStore().aci().containsSession(protocolAddress) ||
            ApplicationDependencies.getProtocolStore().pni().containsSession(protocolAddress);
  }

  private static Set<String> sanitizeNumbers(@NonNull Set<String> numbers) {
    return Stream.of(numbers).filter(number -> {
      try {
        return number.startsWith("+") && number.length() > 1 && number.charAt(1) != '0' && Long.parseLong(number.substring(1)) > 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }).collect(Collectors.toSet());
  }

  private static boolean hasCommunicatedWith(@NonNull Recipient recipient) {
    ACI localAci = SignalStore.account().requireAci();

    return SignalDatabase.threads().hasThread(recipient.getId()) || (recipient.hasServiceId() && SignalDatabase.sessions().hasSessionFor(localAci, recipient.requireServiceId().toString()));
  }

  static class DirectoryResult {
    private final Map<String, ACI>    registeredNumbers;
    private final Map<String, String> numberRewrites;
    private final Set<String>         ignoredNumbers;

    DirectoryResult(@NonNull Map<String, ACI> registeredNumbers,
                    @NonNull Map<String, String> numberRewrites,
                    @NonNull Set<String> ignoredNumbers)
    {
      this.registeredNumbers = registeredNumbers;
      this.numberRewrites    = numberRewrites;
      this.ignoredNumbers    = ignoredNumbers;
    }


    @NonNull Map<String, ACI> getRegisteredNumbers() {
      return registeredNumbers;
    }

    @NonNull Map<String, String> getNumberRewrites() {
      return numberRewrites;
    }

    @NonNull Set<String> getIgnoredNumbers() {
      return ignoredNumbers;
    }
  }
  private static class UnlistedResult {
    private final Set<RecipientId> possiblyActive;
    private final Set<RecipientId> retries;
    private UnlistedResult(@NonNull Set<RecipientId> possiblyActive, @NonNull Set<RecipientId> retries) {
      this.possiblyActive = possiblyActive;
      this.retries        = retries;
    }
    @NonNull Set<RecipientId> getPossiblyActive() {
      return possiblyActive;
    }
    @NonNull Set<RecipientId> getRetries() {
      return retries;
    }

    private static class Builder {
      final Set<RecipientId> potentiallyActiveIds = new HashSet<>();
      final Set<RecipientId> retries              = new HashSet<>();

      @NonNull UnlistedResult build() {
        return new UnlistedResult(potentiallyActiveIds, retries);
      }
    }
  }

  private static void updateContactsDatabase(@NonNull Context context,
                                             @NonNull Collection<RecipientId> activeIds,
                                             boolean removeMissing,
                                             @NonNull Map<String, String> rewrites,
                                             List<ContactTokenDetails> tokenDetails)//AA+
  {
    if (!Permissions.hasAll(context, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) {
      Log.w(TAG, "[updateContactsDatabase] No contact permissions. Skipping.");
      return;
    }

    Stopwatch stopwatch = new Stopwatch("contacts");

    Account account = SystemContactsRepository.getOrCreateSystemAccount(context, BuildConfig.APPLICATION_ID, context.getString(R.string.app_name));
    stopwatch.split("account");

    if (account == null) {
      Log.w(TAG, "Failed to create an account!");
      return;
    }

    try {
      Set<String> activeE164s = Stream.of(activeIds)
                                      .map(Recipient::resolved)
                                      .filter(Recipient::hasE164)
                                      .map(Recipient::requireE164)
                                      .collect(Collectors.toSet());

      Set<UfsrvUserContact> ufsrvUserContacts = tokenDetails.stream()
                                                      .map(t -> new UfsrvUserContact(t.getUfsrvUid(), Optional.ofNullable(t.getUsername()), Optional.ofNullable(t.getE164number())))
                                                      .collect( java.util.stream.Collectors.toSet());//AA+

      SystemContactsRepository.removeDeletedRawContactsForAccount(context, account);
      stopwatch.split("remove-deleted");
      SystemContactsRepository.addMessageAndCallLinksToContacts(context,
                                                                ContactDiscovery.buildContactLinkConfiguration(context, account),
                                                                activeE164s,
                                                                removeMissing,
                                                                ufsrvUserContacts);//AA+
      stopwatch.split("add-links");

      syncRecipientInfoWithSystemContacts(context, rewrites, tokenDetails);//AA+
      stopwatch.split("sync-info");
      stopwatch.stop(TAG);
    } catch (RemoteException | OperationApplicationException e) {
      Log.w(TAG, "Failed to update contacts.", e);
    }
  }

  private static void syncRecipientInfoWithSystemContacts(@NonNull Context context, @NonNull Map<String, String> rewrites, List<ContactTokenDetails> tokenDetails) {//AA+ tokenDetails
    RecipientDatabase     recipientDatabase = SignalDatabase.recipients();
    BulkOperationsHandle  handle            = recipientDatabase.beginBulkSystemContactUpdate();

    try (SystemContactsRepository.ContactIterator iterator = SystemContactsRepository.getAllSystemContacts(context, rewrites, (number) -> PhoneNumberFormatter.get(context).format(number))) {
      while (iterator.hasNext()) {
        ContactDetails          contact = iterator.next();
        ContactHolder           holder  = new ContactHolder();
        StructuredNameRecord    name    = new StructuredNameRecord(contact.getGivenName(), contact.getFamilyName());
        List<PhoneNumberRecord> phones  = Stream.of(contact.getNumbers())
                                                .map(number -> {
                                                  return new PhoneNumberRecord.Builder()
                                                          .withRecipientId(Recipient.externalContact(context, number.getNumber()).getId())
                                                          .withContactUri(number.getContactUri())
                                                          .withDisplayName(number.getDisplayName())
                                                          .withContactPhotoUri(number.getPhotoUri())
                                                          .withContactLabel(number.getLabel())
                                                          .build();
                                                }).toList();

        holder.setStructuredNameRecord(name);
        holder.addPhoneNumberRecords(phones);
        holder.commit(handle);

        resolveRecipientDuplicateNumbers(context, contact, tokenDetails);//AA+

      }
    } catch (IllegalStateException e) {
      Log.w(TAG, "Hit an issue with the cursor while reading!", e);
    } finally {
      handle.finish();
    }

    if (NotificationChannels.supported()) {
      try (RecipientDatabase.RecipientReader recipients = SignalDatabase.recipients().getRecipientsWithNotificationChannels()) {
        Recipient recipient;
        while ((recipient = recipients.getNext()) != null) {
          NotificationChannels.updateContactChannelName(context, recipient);
        }
      }
    }
  }

  //AA+ consolidate system number against known userid
  private static void resolveRecipientDuplicateNumbers(@NonNull Context context, ContactDetails contact, List<ContactTokenDetails> tokenDetails) {
    Stream.of(contact.getNumbers())
          .map(number -> {
            RecipientId recipientId     = Recipient.external(context, number.getNumber()).getId();
            LocallyAddressableUfsrvUid addressableUfsrvUid;
            LocallyAddressablePhoneNumber addressablePhoneNumber = new LocallyAddressablePhoneNumber(recipientId, number.getNumber());

            List <ContactTokenDetails> matchingToken = Stream.of(tokenDetails).filter(t -> t.getE164number().equals(addressablePhoneNumber)).toList();
            if (!matchingToken.isEmpty()) {
              Recipient recipient = Recipient.live(matchingToken.get(0).getUfsrvUid()).get();
              addressableUfsrvUid = LocallyAddressableUfsrvUid.from(recipient.getId(), UfsrvUid.fromEncoded(matchingToken.get(0).getUfsrvUid()));
            } else {
              //fetch based on e164number
              Optional<RecipientRecord> recipientSettings = SignalDatabase.recipients().getContactRecord(addressablePhoneNumber.toString());
              if (recipientSettings.isPresent() && !TextUtils.isEmpty(recipientSettings.get().getUfsrvUidEncoded())) {
                addressableUfsrvUid = LocallyAddressableUfsrvUid.from(recipientSettings.get().getId(), UfsrvUid.fromEncoded(recipientSettings.get().getUfsrvUidEncoded()));
              } else { //pure system contact
                addressableUfsrvUid = LocallyAddressableUfsrvUid.requireUndefined();
              }
            }

            if (!addressablePhoneNumber.isUndefined() && !addressableUfsrvUid.isUndefined() && !addressablePhoneNumber.hasSameIdAs(addressableUfsrvUid)) {
              Log.i(TAG, "resolveRecipientDuplicateNumbers: Resolving duplicate for " + addressablePhoneNumber + " and " + addressableUfsrvUid);
              Recipient.consolidateUfsrvUidAndKill(addressablePhoneNumber, addressableUfsrvUid);//AA contact with two records on in the Recipients db
//                ApplicationDependencies.getJobManager().add(new DirectoryRefreshJob(addressableUfsrvUid.toString(), false, false));
            }


            return null;
          }).close();

  }

  private static void notifyNewUsers(@NonNull  Context context,
                                     @NonNull  List<RecipientId> newUsers)
  {
    if (!SignalStore.settings().isNotifyWhenContactJoinsSignal()) return;

    for (RecipientId newUser: newUsers) {
      Recipient recipient = Recipient.resolved(newUser);
      if (!recipient.isSelf() &&
              recipient.hasAUserSetDisplayName(context) &&
              !hasSession(recipient.getId()))
      {
        IncomingJoinedMessage  message      = new IncomingJoinedMessage(recipient.getId());
        Optional<InsertResult> insertResult = SignalDatabase.sms().insertMessageInbox(message);

        if (insertResult.isPresent()) {
          int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
          if (hour >= 9 && hour < 23) {
            ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId(), true);
          } else {
            ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId(), false);
          }
        }
      }
    }
  }
}