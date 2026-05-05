package com.theater.identity.domain;

import com.theater.shared.kernel.UserId;
import java.util.Optional;

/**
 * User 集約の永続化抽象。実装は {@code identity/infrastructure/JdbcUserRepository} (ID-01 で実装)。
 *
 * <p>{@code save} は楽観ロック失敗時に {@link com.theater.shared.error.OptimisticLockException} を投げる。
 */
public interface UserRepository {

  Optional<User> findById(UserId id);

  /** ID-02 RegisterUser の重複検出 / ID-03 Login の認証で使用。 */
  Optional<User> findByEmail(Email email);

  /** insert / update を吸収。version 不一致で OptimisticLockException。 */
  void save(User user);
}
