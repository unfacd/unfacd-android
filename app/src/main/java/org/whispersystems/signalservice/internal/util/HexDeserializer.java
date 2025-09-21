package org.whispersystems.signalservice.internal.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Jackson deserializes Json Strings to byte[] using Base64 by default, this allows Base16.
 */
public final class HexDeserializer extends JsonDeserializer<byte[]> {

  @Override
  public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    return Hex.fromStringCondensed(p.getText());
  }
}
