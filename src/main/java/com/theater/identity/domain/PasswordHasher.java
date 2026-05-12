package com.theater.identity.domain;

/**
 * パスワードハッシュ化と検証の抽象。
 *
 * <p>パターン: Strategy。bcrypt 等の具体実装は {@code identity/infrastructure} 配下に置く。
 *
 * <p>**契約**:
 *
 * <ul>
 *   <li>平文 password は {@code String} で渡し、内部でハッシュ化して {@link PasswordHash} を返す。
 *   <li>{@link #verify} は副作用なし。タイミング攻撃耐性は実装 (bcrypt) に委ねる。
 *   <li>{@code null} / 空文字列の plain は {@link IllegalArgumentException}。
 * </ul>
 */
public interface PasswordHasher {

  /** 平文パスワードをハッシュ化して {@link PasswordHash} を返す。 */
  PasswordHash hash(String plain);

  /** 平文パスワードが既存ハッシュと一致するか検証。 */
  boolean verify(String plain, PasswordHash hash);
}
