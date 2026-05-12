package com.theater.reservation.ui;

import com.theater.shared.di.Container;
import com.theater.shared.session.CurrentSelection;
import java.io.IOException;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/** Controller for the seat selection screen. */
public final class SeatSelectController {

  @FXML private Button cancelButton;
  @FXML private Label screeningLabel;
  @FXML private Label userLabel;

  private final CurrentSelection currentSelection;

  public SeatSelectController() {
    currentSelection = Container.global().resolve(CurrentSelection.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    screeningLabel.setText("Screening: " + currentSelection.currentScreening().value());
    userLabel.setText("User: " + currentSelection.currentUser().value());
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onCancelClicked() {
    Stage stage = (Stage) cancelButton.getScene().getWindow();
    stage.setScene(new Scene(loadScene("/ui/fxml/home.fxml")));
  }

  private static Parent loadScene(String path) {
    try {
      return FXMLLoader.load(SeatSelectController.class.getResource(path));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load FXML: " + path, e);
    }
  }
}
