package org.thoughtcrime.securesms.contacts.sync

import android.accounts.Account
import android.content.Context
import androidx.annotation.WorkerThread
import com.unfacd.android.R
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid
import org.signal.contacts.ContactLinkConfiguration
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.push.ContactTokenDetails
import java.io.IOException
import java.util.Optional

/**
 * Methods for discovering which users are registered and marking them as such in the database.
 */
object ContactDiscovery {

  private const val MESSAGE_MIMETYPE = "vnd.android.cursor.item/vnd.com.unfacd.android.contact"//AA+
  private const val CALL_MIMETYPE = "vnd.android.cursor.item/vnd.com.unfacd.android.call"
  private const val UFSRVUID_MIMETYPE = "vnd.android.cursor.item/vnd.com.unfacd.android.ufsrvuid";//AA+

  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refreshAll(context: Context, notifyOfNewUsers: Boolean) {
    DirectoryHelper.refreshAll(context, notifyOfNewUsers)
  }

  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refresh(context: Context, recipients: List<Recipient>, notifyOfNewUsers: Boolean) {
//    return DirectoryHelper.refresh(context, recipients, notifyOfNewUsers)//AA-
    return DirectoryHelper.refresh(recipients) as Unit
  }

  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refresh(context: Context, recipient: Recipient, notifyOfNewUsers: Boolean): RecipientDatabase.RegisteredState {
    return DirectoryHelper.refreshE164User(context, recipient, notifyOfNewUsers)//AA+ refreshE164User
  }

  //AA+
  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refresh(recipients:  List<Recipient>): List<RegisteredState>  {
    return DirectoryHelper.refresh(recipients)
  }

  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refresh(addressableUfsrvUid: LocallyAddressableUfsrvUid): RegisteredState {
    return DirectoryHelper.refreshDirectoryFor(addressableUfsrvUid)
  }

  @JvmStatic
  @Throws(IOException::class)
  @WorkerThread
  fun refreshWithContactDetails(addressableUfsrvUid: LocallyAddressableUfsrvUid): Optional<ContactTokenDetails> {
    return DirectoryHelper.refreshWithContactDetails(addressableUfsrvUid)
  }
  //

  @JvmStatic
  @WorkerThread
  fun syncRecipientInfoWithSystemContacts(context: Context) {
    DirectoryHelper.syncRecipientInfoWithSystemContacts(context)
  }

  @JvmStatic
  fun buildContactLinkConfiguration(context: Context, account: Account): ContactLinkConfiguration {
    return ContactLinkConfiguration(
      account = account,
      appName = context.getString(R.string.app_name),
      messagePrompt = { e164 -> context.getString(R.string.ContactsDatabase_message_s, e164) },
      callPrompt = { e164 -> context.getString(R.string.ContactsDatabase_signal_call_s, e164) },
      e164Formatter = { number -> PhoneNumberFormatter.get(context).format(number) },
      messageMimetype = MESSAGE_MIMETYPE,
      callMimetype = CALL_MIMETYPE,
      UFSRVUID_MIMETYPE//AA+
    )
  }
}