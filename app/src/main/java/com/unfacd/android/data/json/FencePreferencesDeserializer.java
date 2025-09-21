package com.unfacd.android.data.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.util.Optional;

import java.io.IOException;
import java.util.HashMap;

import static com.unfacd.android.data.json.JsonEntityFencePreferenceGeogroupStickiness.FENCEPREF_GEOGROUP_STICKINESS;
import static com.unfacd.android.data.json.JsonEntityFencePreferenceProfileSharing.FENCEPREF_PROFILE_SHARING;

public class FencePreferencesDeserializer extends StdDeserializer<JsonEntityFencePreference> {

  public FencePreferencesDeserializer() {
    this(null);
  }

  public FencePreferencesDeserializer(Class<?> vc) {
    super(vc);
  }

  //fence_preferences\": [ { \"name\": \"sticky_geogroup\", \"value\": false }, { \"name\": \"profile_sharing\", \"value\": true } ]
  @Override
  public JsonEntityFencePreference deserialize(JsonParser jp, DeserializationContext ctxt)
          throws IOException, JsonProcessingException
  {
    JsonNode node = jp.getCodec().readTree(jp);
    JsonNode jsonValue = node.get("name");
    FencePreferenceParser parser = fencePreferencesParserMap.get(jsonValue.asText());
    if (parser != null) {
      return parser.parse(jp, ctxt).get();
    }

    return null;
  }

  static HashMap<String, FencePreferenceParser> fencePreferencesParserMap = new HashMap<String, FencePreferenceParser>()
  {
    {
      put(FENCEPREF_GEOGROUP_STICKINESS, (jp, ctx) -> {
        JsonNode node = jp.getCodec().readTree(jp);
        JsonNode jsonValue = node.get("value");
        if (jsonValue.isBoolean()) {
          Boolean prefValue = jsonValue.booleanValue();
          return Optional.of(new JsonEntityFencePreferenceGeogroupStickiness(prefValue));
        }

        return Optional.empty();
      });

      put(FENCEPREF_PROFILE_SHARING, (jp, ctx) -> {
        JsonNode node = jp.getCodec().readTree(jp);
        JsonNode jsonValue = node.get("value");
        if (jsonValue.isBoolean()) {
          Boolean prefValue = jsonValue.booleanValue();
          return Optional.of(new JsonEntityFencePreferenceProfileSharing(prefValue));
        }

        return Optional.empty();
      });
    }
  };

  public interface FencePreferenceParser
  {
    Optional<JsonEntityFencePreference> parse (JsonParser jp, DeserializationContext ctx) throws IOException;
  }
}