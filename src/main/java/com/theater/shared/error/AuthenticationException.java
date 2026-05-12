package com.theater.shared.error;

/**
 * 認証失敗。bad email / bad password を区別しないため、メッセージは共通。
 *
 * <p>呼出側 (UseCase) が email 不在と password 不一致を別の例外で投げ分けると、 メッセージ差分から「その email は登録されているか」を推定できてしまうため、
 * {@code identity/application/LoginUseCase} はどちらも本例外を投げる。
 */
public final class AuthenticationException extends DomainException {

  private static final long serialVersionUID = 1L;

  public AuthenticationException() {
    super("Invalid credentials");
  }
}
