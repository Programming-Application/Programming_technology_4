package com.theater.shared.ui;

import java.io.IOException;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * FXML 読込と {@link Stage} の Scene 切替を集約するユーティリティ。
 *
 * <p>各 Controller から重複コードを排除する目的。{@code FXMLLoader.load} が投げる {@link IOException} を 実行時例外に変換して再
 * throw する。本案件 (single-process / single-stage) では起動時に classpath が確定するため、 IO 例外が起きるのはリソース欠落 (= バグ)
 * に限られる。
 */
public final class Screens {

  private Screens() {}

  /** classpath 上の FXML を読み込んで Scene root を返す。 */
  public static Parent load(String fxmlPath) {
    try {
      return FXMLLoader.load(Screens.class.getResource(fxmlPath));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load FXML: " + fxmlPath, e);
    }
  }

  /** いま {@code anchor} が属している Stage のシーンを {@code fxmlPath} に切り替える。 */
  public static void switchTo(Node anchor, String fxmlPath) {
    Stage stage = (Stage) anchor.getScene().getWindow();
    stage.setScene(new Scene(load(fxmlPath)));
  }
}
