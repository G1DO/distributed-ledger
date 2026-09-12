package com.g1do.ledger.api;

import com.g1do.ledger.service.QueryService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class QueryController {

  private final QueryService queryService;

  public QueryController(QueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> query(@RequestParam String accountId) {
    Map<String, Object> capacity = queryService.capacityByAccount(accountId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accountId", accountId);
    response.put("available", ((Number) capacity.get("available")).intValue());
    response.put("committed", ((Number) capacity.get("committed")).intValue());
    response.put("reserved", ((Number) capacity.get("reserved")).intValue());
    response.put("total", ((Number) capacity.get("total")).intValue());
    return response;
  }

  @GetMapping(value = "/operations/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> operationByKey(@PathVariable("key") String key) {
    String body = queryService.responseBodyByKey(key);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
