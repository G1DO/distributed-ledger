package com.g1do.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/** Symbolic commands survive fixture recreation and dependency deletion during shrinking. */
record GeneratedHistory(List<GeneratedHistory.Batch> batches) {
  enum Kind {
    RESERVE,
    COMMIT,
    RELEASE,
    TRANSFER,
    EXPIRE
  }

  enum Fault {
    NONE,
    BEFORE_DISPATCH,
    AFTER_COMPLETION
  }

  record Command(Kind kind, int key, int account, int amount, int reservation, Fault fault) {
    Command mismatch() {
      return new Command(kind, key, account, amount + 1, reservation + 1, Fault.NONE);
    }

    String fingerprint() {
      return switch (kind) {
        case RESERVE, TRANSFER -> kind + ":" + account + ":" + amount;
        case COMMIT, RELEASE, EXPIRE -> kind + ":" + reservation;
      };
    }
  }

  record Batch(List<Integer> due, List<Command> commands) {}

  static GeneratedHistory generate(long seed) {
    Random random = new Random(seed);
    List<Batch> batches = new ArrayList<>();
    List<Command> history = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int amount = 1 + random.nextInt(15);
      Fault fault = i == 0 ? Fault.AFTER_COMPLETION : i == 1 ? Fault.BEFORE_DISPATCH : Fault.NONE;
      Command reserve = new Command(Kind.RESERVE, i, i % 2, amount, i, fault);
      batches.add(new Batch(List.of(), List.of(reserve)));
      history.add(reserve);
    }
    batches.add(
        new Batch(
            List.of(),
            List.of(
                new Command(Kind.COMMIT, 4, 0, 0, 0, Fault.NONE),
                new Command(Kind.RELEASE, 5, 0, 0, 0, Fault.NONE))));
    batches.add(
        new Batch(
            List.of(1),
            List.of(
                new Command(Kind.EXPIRE, 6, 0, 0, 1, Fault.NONE),
                new Command(Kind.EXPIRE, 7, 0, 0, 1, Fault.NONE),
                new Command(
                    random.nextBoolean() ? Kind.COMMIT : Kind.RELEASE, 8, 0, 0, 1, Fault.NONE))));
    batches.add(
        new Batch(
            List.of(),
            List.of(
                new Command(Kind.TRANSFER, 9, 0, 1 + random.nextInt(70), 0, Fault.NONE),
                new Command(Kind.TRANSFER, 10, 1, 1 + random.nextInt(70), 0, Fault.NONE))));
    batches.add(new Batch(List.of(), List.of(history.get(0), history.get(0).mismatch())));
    int nextKey = 11;
    for (int round = 0; round < 5; round++) {
      List<Command> commands = new ArrayList<>();
      List<Integer> due = new ArrayList<>();
      for (int width = 1 + random.nextInt(3); width > 0; width--) {
        int choice = random.nextInt(7);
        if (choice >= 5) {
          Command previous = history.get(random.nextInt(history.size()));
          commands.add(choice == 5 ? previous : previous.mismatch());
        } else {
          Kind kind = Kind.values()[choice];
          int target = random.nextInt(4);
          Command command =
              new Command(
                  kind, nextKey++, random.nextInt(2), 1 + random.nextInt(80), target, Fault.NONE);
          commands.add(command);
          history.add(command);
          if (kind == Kind.EXPIRE) {
            due.add(target);
          }
        }
      }
      batches.add(new Batch(List.copyOf(due), List.copyOf(commands)));
    }
    return new GeneratedHistory(List.copyOf(batches));
  }

  String encode() {
    StringBuilder result = new StringBuilder();
    for (Batch batch : batches) {
      result.append("batch");
      for (int reference : batch.due()) {
        result.append(' ').append(reference);
      }
      result.append('\n');
      for (Command command : batch.commands()) {
        result
            .append(command.kind())
            .append(' ')
            .append(command.key())
            .append(' ')
            .append(command.account())
            .append(' ')
            .append(command.amount())
            .append(' ')
            .append(command.reservation())
            .append(' ')
            .append(command.fault())
            .append('\n');
      }
    }
    return result.toString();
  }

  static GeneratedHistory decode(String text) {
    List<Batch> batches = new ArrayList<>();
    for (String line : text.lines().filter(value -> !value.isBlank()).toList()) {
      String[] fields = line.split(" ");
      if (fields[0].equals("batch")) {
        List<Integer> due = new ArrayList<>();
        for (int i = 1; i < fields.length; i++) {
          due.add(Integer.parseInt(fields[i]));
        }
        batches.add(new Batch(due, new ArrayList<>()));
      } else {
        batches
            .getLast()
            .commands()
            .add(
                new Command(
                    Kind.valueOf(fields[0]),
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]),
                    Integer.parseInt(fields[4]),
                    Fault.valueOf(fields[5])));
      }
    }
    return new GeneratedHistory(batches);
  }

  record Shrunk(GeneratedHistory history, int attempts, boolean deletionMinimal) {}

  /**
   * Greedy deletion to a fixed point; a candidate is retained only after the same failure recurs.
   */
  Shrunk shrink(Predicate<GeneratedHistory> reproduces, int budget) {
    if (budget < 0) {
      throw new IllegalArgumentException("Shrink budget must be nonnegative");
    }
    GeneratedHistory current = this;
    int attempts = 0;
    while (true) {
      boolean changed = false;
      for (int b = 0; b < current.batches.size() && !changed; b++) {
        Batch batch = current.batches.get(b);
        // Try removing the whole batch, then individual commands and due-clock changes.
        int choices = 1 + batch.commands.size() + batch.due.size();
        for (int choice = 0; choice < choices; choice++) {
          if (attempts == budget) {
            return new Shrunk(current, attempts, false);
          }
          List<Batch> candidate = new ArrayList<>(current.batches);
          if (choice == 0) {
            candidate.remove(b);
          } else {
            List<Command> commands = new ArrayList<>(batch.commands);
            List<Integer> due = new ArrayList<>(batch.due);
            if (choice <= commands.size()) {
              commands.remove(choice - 1);
            } else {
              due.remove(choice - commands.size() - 1);
            }
            candidate.set(b, new Batch(due, commands));
          }
          GeneratedHistory smaller = new GeneratedHistory(candidate);
          attempts++;
          if (reproduces.test(smaller)) {
            current = smaller;
            changed = true;
            break;
          }
        }
      }
      if (!changed) {
        return new Shrunk(current, attempts, true);
      }
    }
  }
}
