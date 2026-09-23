package com.g1do.ledger;

import com.g1do.ledger.GeneratedHistory.Command;
import com.g1do.ledger.GeneratedHistory.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent state machine: no repository reads, production hashes, or production transition code.
 */
final class GeneratedHistoryModel {
  record Reservation(int account, int amount, String status, boolean due) {}

  record Outcome(int status, boolean replay, String body, boolean resolvedUnknown) {
    boolean matches(Outcome expected) {
      if (resolvedUnknown
          && status >= 200
          && status < 300
          && expected.status >= 200
          && expected.status < 300) {
        return true;
      }
      return status == expected.status && replay == expected.replay;
    }
  }

  final int[][] capacity = {{100, 0, 0}, {100, 0, 0}};
  final Map<Integer, Reservation> reservations = new HashMap<>();
  final Map<Integer, String> operations = new HashMap<>();
  int expirations;

  GeneratedHistoryModel copy() {
    GeneratedHistoryModel copy = new GeneratedHistoryModel();
    for (int i = 0; i < capacity.length; i++) {
      copy.capacity[i] = capacity[i].clone();
    }
    copy.reservations.putAll(reservations);
    copy.operations.putAll(operations);
    copy.expirations = expirations;
    return copy;
  }

  void due(List<Integer> references) {
    for (int reference : references) {
      Reservation previous = reservations.get(reference);
      if (previous != null) {
        reservations.put(
            reference, new Reservation(previous.account, previous.amount, previous.status, true));
      }
    }
  }

  List<GeneratedHistoryModel> matchingOrders(List<Command> commands, List<Outcome> observed) {
    List<GeneratedHistoryModel> matches = new ArrayList<>();
    explore(commands, observed, new boolean[commands.size()], 0, matches);
    return matches;
  }

  private void explore(
      List<Command> commands,
      List<Outcome> observed,
      boolean[] used,
      int count,
      List<GeneratedHistoryModel> matches) {
    if (count == commands.size()) {
      matches.add(this);
      return;
    }
    for (int i = 0; i < commands.size(); i++) {
      if (!used[i]) {
        GeneratedHistoryModel next = copy();
        Outcome expected = next.apply(commands.get(i));
        if (observed.get(i).matches(expected)) {
          used[i] = true;
          next.explore(commands, observed, used, count + 1, matches);
          used[i] = false;
        }
      }
    }
  }

  Outcome apply(Command command) {
    if (command.kind() != Kind.EXPIRE && operations.containsKey(command.key())) {
      return result(operations.get(command.key()).equals(command.fingerprint()) ? 200 : 422, true);
    }
    int status;
    if (command.kind() == Kind.RESERVE) {
      if (available(command.account()) < command.amount()) {
        return result(409, false);
      }
      capacity[command.account()][1] += command.amount();
      reservations.put(
          command.key(), new Reservation(command.account(), command.amount(), "RESERVED", false));
      status = 201;
    } else if (command.kind() == Kind.TRANSFER) {
      if (available(command.account()) < command.amount()) {
        return result(409, false);
      }
      capacity[command.account()][0] -= command.amount();
      capacity[1 - command.account()][0] += command.amount();
      status = 200;
    } else {
      Reservation reservation = reservations.get(command.reservation());
      if (command.kind() == Kind.EXPIRE) {
        boolean expired =
            reservation != null && reservation.status.equals("RESERVED") && reservation.due;
        if (expired) {
          terminal(command.reservation(), reservation, "EXPIRED");
        }
        return result(expired ? 1 : 0, false);
      }
      if (reservation == null) {
        return result(404, false);
      }
      if (!reservation.status.equals("RESERVED")) {
        return result(409, false);
      }
      if (reservation.due) {
        terminal(command.reservation(), reservation, "EXPIRED");
        return result(409, false);
      }
      terminal(
          command.reservation(),
          reservation,
          command.kind() == Kind.COMMIT ? "COMMITTED" : "RELEASED");
      status = 200;
    }
    operations.put(command.key(), command.fingerprint());
    return result(status, false);
  }

  private void terminal(int reference, Reservation reservation, String status) {
    capacity[reservation.account][1] -= reservation.amount;
    if (status.equals("COMMITTED")) {
      capacity[reservation.account][2] += reservation.amount;
    } else if (status.equals("EXPIRED")) {
      expirations++;
    }
    reservations.put(
        reference,
        new Reservation(reservation.account, reservation.amount, status, reservation.due));
  }

  private int available(int account) {
    return capacity[account][0] - capacity[account][1] - capacity[account][2];
  }

  private static Outcome result(int status, boolean replay) {
    return new Outcome(status, status == 200 && replay, "", false);
  }
}
