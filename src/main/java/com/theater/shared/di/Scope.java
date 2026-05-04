package com.theater.shared.di;

/** バインディングのライフサイクル。 */
public enum Scope {
  /** コンテナ内で1回だけ生成し、以降同一インスタンスを返す。 */
  SINGLETON,
  /** 解決のたびに新しいインスタンスを生成する。 */
  PROTOTYPE
}
