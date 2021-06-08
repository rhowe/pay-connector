package uk.gov.pay.connector.common.model.api.jsonpatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.pay.connector.util.JsonObjectMapper;

import java.util.Map;

import static uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchKeys.FIELD_OPERATION;
import static uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchKeys.FIELD_OPERATION_PATH;
import static uk.gov.pay.connector.common.model.api.jsonpatch.JsonPatchKeys.FIELD_VALUE;

public class JsonPatchRequest {

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static JsonObjectMapper jsonObjectMapper = new JsonObjectMapper(objectMapper);
    
    private JsonPatchOp op;
    private String path;
    private JsonNode value;

    public JsonPatchOp getOp() {
        return op;
    }

    public String getPath() {
        return path;
    }

    public String valueAsString() {
        return value.asText();
    }
    
    public long valueAsLong() {
        if (value != null && value.isNumber()) {
            return Long.parseLong(value.asText());
        }
        throw new JsonNodeNotCorrectTypeException("JSON node " + value + " is not of type number");
    }
    
    public int valueAsInt() {
        if(value != null && value.isNumber()) {
            return Integer.parseInt(value.asText());
        }
        throw new JsonNodeNotCorrectTypeException("JSON node " + value + " is not of type number");
    }

    public boolean valueAsBoolean() {
        if (value != null && value.isBoolean()) {
            return Boolean.parseBoolean(value.asText());
        }
        throw new JsonNodeNotCorrectTypeException("JSON node " + value + " is not of type boolean");
    }

    public Map<String, String> valueAsObject() {
        return jsonObjectMapper.getAsMap(value);
    }


    private JsonPatchRequest(JsonPatchOp op, String path, JsonNode value) {
        this.op = op;
        this.path = path;
        this.value = value;
    }

    public static JsonPatchRequest from(JsonNode payload) {
        return new JsonPatchRequest(
                JsonPatchOp.valueOf(payload.get(FIELD_OPERATION).asText().toUpperCase()),
                payload.get(FIELD_OPERATION_PATH).asText(),
                payload.get(FIELD_VALUE));

    }
    
    public class JsonNodeNotCorrectTypeException extends RuntimeException {
        public JsonNodeNotCorrectTypeException(String message) {
            super(message);
        }
    }
}
