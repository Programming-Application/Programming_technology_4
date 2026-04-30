package com.theater.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.theater.testkit.Db;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionalUseCaseTest {

  private Db.TestDb testDb;
  private JdbcUnitOfWork uow;

  @BeforeEach
  void setup() {
    testDb = Db.openTempFile();
    uow = new JdbcUnitOfWork(testDb.dataSource());
  }

  @AfterEach
  void teardown() {
    testDb.close();
  }

  @Test
  void execute_runs_validate_then_handle_inside_tx() {
    var uc = new EchoUseCase(uow);
    String result = uc.execute(new EchoCommand("hi"));
    assertThat(result).isEqualTo("HI");
  }

  @Test
  void validate_failure_skips_handle() {
    var uc = new EchoUseCase(uow);
    assertThatThrownBy(() -> uc.execute(new EchoCommand("")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void null_command_is_rejected() {
    var uc = new EchoUseCase(uow);
    assertThatThrownBy(() -> uc.execute(null)).isInstanceOf(NullPointerException.class);
  }

  private record EchoCommand(String input) {}

  private static final class EchoUseCase extends TransactionalUseCase<EchoCommand, String> {
    EchoUseCase(UnitOfWork uow) {
      super(uow);
    }

    @Override
    protected void validate(EchoCommand command) {
      if (command.input().isEmpty()) {
        throw new IllegalArgumentException("input must not be blank");
      }
    }

    @Override
    protected String handle(EchoCommand command) {
      // currentConnection() を呼べることで Tx の中にいることを示す
      assertThat(uow.currentConnection()).isNotNull();
      return command.input().toUpperCase(Locale.ROOT);
    }
  }
}
