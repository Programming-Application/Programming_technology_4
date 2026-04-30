package com.theater.shared.error;

/**
 * 全ドメイン例外の基底。
 *
 * <p>{@link RuntimeException} を継承するのは、Java の検査例外を Tx 境界 (UnitOfWork) で扱うのが
 * 煩雑になるため。意味のある業務例外は本クラスを継承して投げる。
 */
public abstract class DomainException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  protected DomainException(String message) {
    super(message);
  }

  protected DomainException(String message, Throwable cause) {
    super(message, cause);
  }
}
