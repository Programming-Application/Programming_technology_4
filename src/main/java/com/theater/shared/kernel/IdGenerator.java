package com.theater.shared.kernel;

import java.util.UUID;

/**
 * 集約のIDを生成する戦略。
 *
 * <p>本案件は UUID v4 (Java 標準) を採用。タイムオーダ性を持たせたい場合は v7 への差替も容易。
 *
 * <p>パターン: Strategy。
 */
@FunctionalInterface
public interface IdGenerator {

    /** UUID v4 文字列を返す既定実装。 */
    IdGenerator UUID_V4 = () -> UUID.randomUUID().toString();

    String newId();
}
