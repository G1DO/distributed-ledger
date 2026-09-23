package com.g1do.ledger.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record TransferRequest(
    @NotBlank String fromAccountId,
    @NotBlank String toAccountId,
    @Min(1) @JsonDeserialize(using = AmountDeserializer.class) int amount,
    @NotBlank @Size(min = 1, max = 64) String idempotencyKey) {

  /** Reject coercion or truncation of a transfer amount without changing other endpoints. */
  public static class AmountDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
      if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
        return context.reportInputMismatch(Integer.class, "amount must be a JSON integer");
      }
      return parser.getIntValue();
    }
  }
}
