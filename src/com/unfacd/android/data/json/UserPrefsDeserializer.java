package com.unfacd.android.data.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class UserPrefsDeserializer extends StdDeserializer<JsonEntityBaseUserPref>
{

  public UserPrefsDeserializer() {
    this(null);
  }

  public UserPrefsDeserializer(Class<?> vc) {
    super(vc);
  }

  //\"user_prefs\": [ { \"id\": 0, \"value\": 3 }, { \"id\": 65, \"value\": \"b4cc5ff417618fb876bfea52b7b659a75307caa5\" }, { \"id\": 66, \"value\": [ 305 ] }, { \"id\": 69, \"value\": [ 305 ] } ]
  @Override
  public JsonEntityBaseUserPref deserialize(JsonParser jp, DeserializationContext ctxt)
          throws IOException, JsonProcessingException
  {
    JsonNode node = jp.getCodec().readTree(jp);
    int id = (Integer) node.get("id").numberValue();

    //by the time we landed here, jackson will have already moved to the array token and each invocation will contain successive array elements
    switch (id) {
      case 0: return new JsonEntityUserPrefGeoGroupRoaming(id, node.get("value").asInt(0));

      case 65: return new JsonEntityUserPrefAvatar(id, node.get("value").asText());
      case 66: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
            for (final JsonNode objNode : userIds) {
              userIdsList.add(objNode.asText());
            }

          return new JsonEntityUserPrefProfileShare(id, userIdsList);
        }
      }

      case 67: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefLocationShare(id, userIdsList);
        }
      }

      case 68: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefContactShare(id, userIdsList);
        }
      }

      case 69: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefNetStateShare(id, userIdsList);
        }
      }

      case 71: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefBlockShare(id, userIdsList);
        }
      }

      case 72: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefReadReceiptShare(id, userIdsList);
        }
      }

      case 73: {
        JsonNode userIds = node.get("value");
        if (userIds.isArray()) {
          List<String> userIdsList = new LinkedList<>();
          for (final JsonNode objNode : userIds) {
            userIdsList.add(objNode.asText());
          }

          return new JsonEntityUserPrefActivityStateShare(id, userIdsList);
        }
      }

      case 82:  return new JsonEntityUserPrefE164Number(id, node.get("value").asText());


      default: break;
    }

    return null;
  }
}