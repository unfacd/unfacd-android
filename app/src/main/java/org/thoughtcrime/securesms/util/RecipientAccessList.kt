package org.thoughtcrime.securesms.util

import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.signalservice.api.push.ACI
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.SignalServiceAddress

/**
 * A list of Recipients, but with some helpful methods for retrieving them by various properties. Uses lazy properties to ensure that it will be as performant
 * as a regular list if you don't call any of the extra methods.
 */
class RecipientAccessList(private val recipients: List<Recipient>) : List<Recipient> by recipients {

  private val byServiceId: Map<ServiceId, Recipient> by lazy {
    recipients
      .filter { it.hasServiceId() }
      .associateBy { it.requireServiceId() }
  }

  private val byE164: Map<String, Recipient> by lazy {
    recipients
      .filter { it.hasE164() }
      .associateBy { it.requireE164() }
  }

  //AA+
  private val byUfsrvUid: Map<String, Recipient> by lazy {
    recipients
      .filter { it.hasUfsrvUid() }
      .associateBy { it.requireUfsrvUid() }
  }

  //AA++ modified to prioritise ufsrvuid
  fun requireByAddress(address: SignalServiceAddress): Recipient {
   if (address.number.isPresent && byUfsrvUid.containsKey(address.number.get())) {
      return byUfsrvUid[address.number.get()]!!
    } else if (byServiceId.containsKey(address.serviceId)) {
     return byServiceId[address.serviceId]!!
   } else if (address.number.isPresent && byE164.containsKey(address.number.get())) {
     return byE164[address.number.get()]!!
    } else {
      throw IllegalArgumentException("Could not find a matching recipient!")
    }
  }

  fun requireIdByAddress(address: SignalServiceAddress): RecipientId {
    return requireByAddress(address).id
  }
}