package com.g1do.ledger.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping(value = "/ready", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> ready() {
    return Map.of("status", "UP");
  }
}
