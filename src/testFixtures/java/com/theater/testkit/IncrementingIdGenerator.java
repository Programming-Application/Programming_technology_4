package com.theater.testkit;

import com.theater.shared.kernel.IdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/** テスト用の決定的 ID 生成器 ({@code "id-1"}, {@code "id-2"}, ...)。 */
public final class IncrementingIdGenerator implements IdGenerator {

  private final String prefix;
  private final AtomicLong counter = new AtomicLong(0);

  public IncrementingIdGenerator() {
    this("id-");
  }

  public IncrementingIdGenerator(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public String newId() {
    return prefix + counter.incrementAndGet();
  }
}
