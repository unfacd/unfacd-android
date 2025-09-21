package org.signal.contacts

import java.util.Optional

/**
 * Describes select contact information from the domain of a ufsrv contact.
 *
 * [ufsrvUid] the network ufsrv user id
 * [username] registration name (currently email)
 *
 */
class UfsrvUserContact (
  val ufsrvUid: String,
  val username: Optional<String>,
  val e164number: Optional<String>

)
