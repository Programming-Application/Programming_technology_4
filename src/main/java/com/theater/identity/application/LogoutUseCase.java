package com.theater.identity.application;

import com.theater.identity.domain.CurrentUserHolder;
import java.util.Objects;

/** セッションをクリアする UseCase。Tx 不要 (DB 書込なし) のため {@code TransactionalUseCase} を継承しない。 */
public final class LogoutUseCase {

  private final CurrentUserHolder session;

  public LogoutUseCase(CurrentUserHolder session) {
    this.session = Objects.requireNonNull(session, "session");
  }

  public void execute() {
    session.clear();
  }
}
