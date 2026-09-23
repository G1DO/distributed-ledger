package com.g1do.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.g1do.ledger.GeneratedHistory.Command;
import com.g1do.ledger.GeneratedHistory.Fault;
import com.g1do.ledger.GeneratedHistory.Kind;
import com.g1do.ledger.GeneratedHistoryModel.Outcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedHistoryTest {
  @Test
  void generatedHistoriesAndSavedReprosAreDeterministic() {
    GeneratedHistory history = GeneratedHistory.generate(4210421);
    assertThat(GeneratedHistory.generate(4210421)).isEqualTo(history);
    assertThat(GeneratedHistory.decode(history.encode())).isEqualTo(history);
    assertThat(GeneratedHistory.generate(42)).isNotEqualTo(history);
    Command afterCommit = history.batches().get(0).commands().getFirst();
    Command beforeDispatch = history.batches().get(1).commands().getFirst();
    assertThat(afterCommit.fault()).isEqualTo(Fault.AFTER_COMPLETION);
    assertThat(beforeDispatch.fault()).isEqualTo(Fault.BEFORE_DISPATCH);
  }

  @Test
  void shrinkActuallyRemovesIrrelevantCommandsAndClockChanges() {
    GeneratedHistory history = GeneratedHistory.generate(42);
    var shrunk =
        history.shrink(
            candidate ->
                candidate.batches().stream()
                    .flatMap(batch -> batch.commands().stream())
                    .anyMatch(command -> command.kind() == Kind.TRANSFER),
            500);
    assertThat(shrunk.deletionMinimal()).isTrue();
    assertThat(shrunk.history().batches()).hasSize(1);
    assertThat(shrunk.history().batches().getFirst().due()).isEmpty();
    assertThat(shrunk.history().batches().getFirst().commands()).hasSize(1);
    assertThat(shrunk.history().batches().getFirst().commands().getFirst().kind())
        .isEqualTo(Kind.TRANSFER);
    assertThat(GeneratedHistory.decode(shrunk.history().encode())).isEqualTo(shrunk.history());
  }

  @Test
  void boundedShrinkNeverClaimsMinimalityWhenBudgetRunsOut() {
    assertThatThrownBy(() -> GeneratedHistory.generate(42).shrink(candidate -> true, -1))
        .isInstanceOf(IllegalArgumentException.class);
    var shrunk = GeneratedHistory.generate(42).shrink(candidate -> false, 3);
    assertThat(shrunk.attempts()).isEqualTo(3);
    assertThat(shrunk.deletionMinimal()).isFalse();
    assertThat(shrunk.history()).isEqualTo(GeneratedHistory.generate(42));
  }

  @Test
  void independentModelRejectsTwoTerminalWinnersAndImpossibleRejections() {
    GeneratedHistoryModel model = new GeneratedHistoryModel();
    model.apply(new Command(Kind.RESERVE, 0, 0, 10, 0, Fault.NONE));
    List<Command> race =
        List.of(
            new Command(Kind.COMMIT, 1, 0, 0, 0, Fault.NONE),
            new Command(Kind.RELEASE, 2, 0, 0, 0, Fault.NONE));
    Outcome success = new Outcome(200, false, "", false);
    Outcome conflict = new Outcome(409, false, "", false);
    assertThat(model.matchingOrders(race, List.of(success, success))).isEmpty();
    assertThat(model.matchingOrders(race, List.of(conflict, conflict))).isEmpty();
    assertThat(model.matchingOrders(race, List.of(success, conflict))).hasSize(1);
  }

  @Test
  void modelIncludesReplayMismatchTransferAndDueTerminalEffects() {
    GeneratedHistoryModel model = new GeneratedHistoryModel();
    Command reserve = new Command(Kind.RESERVE, 0, 0, 10, 0, Fault.NONE);
    assertThat(model.apply(reserve).status()).isEqualTo(201);
    assertThat(model.apply(reserve).replay()).isTrue();
    assertThat(model.apply(reserve.mismatch()).status()).isEqualTo(422);
    model.due(List.of(0));
    assertThat(model.apply(new Command(Kind.COMMIT, 1, 0, 0, 0, Fault.NONE)).status())
        .isEqualTo(409);
    assertThat(model.reservations.get(0).status()).isEqualTo("EXPIRED");
    assertThat(model.apply(new Command(Kind.EXPIRE, 2, 0, 0, 0, Fault.NONE)).status()).isZero();
    model.apply(new Command(Kind.TRANSFER, 3, 0, 100, 0, Fault.NONE));
    assertThat(model.capacity[0]).containsExactly(0, 0, 0);
    assertThat(model.capacity[1]).containsExactly(200, 0, 0);
    assertThat(model.apply(new Command(Kind.RESERVE, 4, 0, 1, 4, Fault.NONE)).status())
        .isEqualTo(409);
  }

  @Test
  void resolvedUnknownRequiresEvidenceOfAnActualOutcome() {
    Outcome expectedReserve = new Outcome(201, false, "", false);
    assertThat(new Outcome(200, true, "stored", true).matches(expectedReserve)).isTrue();
    assertThat(new Outcome(201, false, "retried", true).matches(expectedReserve)).isTrue();
    assertThat(new Outcome(0, false, "unknown", true).matches(expectedReserve)).isFalse();
    assertThat(new Outcome(409, false, "rejected", true).matches(expectedReserve)).isFalse();
  }
}
