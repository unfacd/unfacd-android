package org.whispersystems.signalservice.internal.push.exceptions;

import java.util.Optional;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class ForbiddenException extends NonSuccessfulResponseCodeException {
  private Optional<String> reason;

  public ForbiddenException() {
    this(Optional.empty());
  }

  public ForbiddenException(Optional<String> reason) {
    super(403);
    this.reason = reason;
  }

  public Optional<String> getReason() {
    return reason;
  }
}