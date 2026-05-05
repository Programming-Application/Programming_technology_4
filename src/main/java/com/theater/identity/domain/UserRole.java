package com.theater.identity.domain;

/** docs/data_model.md §1 の {@code users.role CHECK (role IN ('USER','ADMIN'))} 対応。 */
public enum UserRole {
  USER,
  ADMIN
}
