package com.theater.reservation.ui;

import com.theater.identity.domain.CurrentUserHolder;
import com.theater.reservation.application.HoldSeatsUseCase;
import com.theater.reservation.application.LoadSeatMapUseCase;
import com.theater.reservation.application.SeatMapEntry;
import com.theater.reservation.domain.SeatStateStatus;
import com.theater.shared.di.Container;
import com.theater.shared.error.ConflictException;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.SeatId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.session.CurrentSelection;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

/** Controller for the seat selection screen. */
public final class SeatSelectController {

  private static final String AVAILABLE_STYLE =
      "-fx-background-color: #2e7d32; -fx-text-fill: white;";
  private static final String HOLD_STYLE = "-fx-background-color: #f9a825; -fx-text-fill: #1f1f1f;";
  private static final String SOLD_STYLE = "-fx-background-color: #9e9e9e; -fx-text-fill: white;";
  private static final String BLOCKED_STYLE =
      "-fx-background-color: #212121; -fx-text-fill: white;";
  private static final String SELECTED_STYLE =
      "-fx-background-color: #1565c0; -fx-text-fill: white;";

  @FXML private Button cancelButton;
  @FXML private Button reserveButton;
  @FXML private Label screeningLabel;
  @FXML private Label userLabel;
  @FXML private Label errorLabel;
  @FXML private GridPane seatGrid;

  private final LoadSeatMapUseCase loadSeatMap;
  private final HoldSeatsUseCase holdSeats;
  private final CurrentUserHolder currentUserHolder;
  private final CurrentSelection currentSelection;
  private final Map<SeatId, Button> selectedSeats = new LinkedHashMap<>();

  public SeatSelectController() {
    Container container = Container.global();
    loadSeatMap = container.resolve(LoadSeatMapUseCase.class);
    holdSeats = container.resolve(HoldSeatsUseCase.class);
    currentUserHolder = container.resolve(CurrentUserHolder.class);
    currentSelection = container.resolve(CurrentSelection.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    ScreeningId screeningId = currentSelection.currentScreening();
    UserId userId = currentUserId();
    screeningLabel.setText("Screening: " + screeningId.value());
    userLabel.setText("User: " + userId.value());
    refresh();
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onReserveClicked() {
    errorLabel.setText("");
    if (selectedSeats.isEmpty()) {
      errorLabel.setText("座席を選んでください");
      return;
    }

    try {
      var result =
          holdSeats.execute(
              new HoldSeatsUseCase.Command(
                  currentUserId(),
                  currentSelection.currentScreening(),
                  selectedSeats.keySet().stream().toList()));
      currentSelection.setActiveReservation(result.reservationId());
      Stage stage = (Stage) reserveButton.getScene().getWindow();
      stage.setScene(new Scene(loadScene("/ui/fxml/checkout.fxml")));
    } catch (ConflictException e) {
      errorLabel.setText("選択した座席が他のお客様に取られました。再選択してください");
      refresh();
    }
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onCancelClicked() {
    Stage stage = (Stage) cancelButton.getScene().getWindow();
    stage.setScene(new Scene(loadScene("/ui/fxml/home.fxml")));
  }

  private void refresh() {
    selectedSeats.clear();
    seatGrid.getChildren().clear();
    var seats =
        loadSeatMap.execute(new LoadSeatMapUseCase.Command(currentSelection.currentScreening()));
    int index = 0;
    for (SeatMapEntry seat : seats) {
      Button button = new Button(seat.seatId().value());
      button.setMinSize(58, 38);
      button.setMaxSize(58, 38);
      button.setStyle(styleFor(seat.status()));
      button.setDisable(seat.status() != SeatStateStatus.AVAILABLE);
      if (seat.status() == SeatStateStatus.AVAILABLE) {
        SeatId seatId = seat.seatId();
        button.setOnAction(event -> toggleSeat(seatId, button));
      }
      SeatPosition position = SeatPosition.parse(seat.seatId().value(), index);
      seatGrid.add(button, position.column(), position.row());
      index++;
    }
    reserveButton.setDisable(false);
  }

  private void toggleSeat(SeatId seatId, Button button) {
    if (selectedSeats.remove(seatId) != null) {
      button.setStyle(AVAILABLE_STYLE);
      return;
    }
    selectedSeats.put(seatId, button);
    button.setStyle(SELECTED_STYLE);
  }

  private UserId currentUserId() {
    return currentUserHolder
        .current()
        .map(user -> user.id())
        .orElseGet(currentSelection::currentUser);
  }

  private static String styleFor(SeatStateStatus status) {
    return switch (status) {
      case AVAILABLE -> AVAILABLE_STYLE;
      case HOLD -> HOLD_STYLE;
      case SOLD -> SOLD_STYLE;
      case BLOCKED -> BLOCKED_STYLE;
    };
  }

  private static Parent loadScene(String path) {
    try {
      return FXMLLoader.load(SeatSelectController.class.getResource(path));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load FXML: " + path, e);
    }
  }

  private record SeatPosition(int row, int column) {
    static SeatPosition parse(String seatId, int fallbackIndex) {
      int split = 0;
      while (split < seatId.length() && !Character.isDigit(seatId.charAt(split))) {
        split++;
      }
      if (split == 0 || split == seatId.length()) {
        return new SeatPosition(fallbackIndex / 10, fallbackIndex % 10);
      }
      char rowChar = Character.toUpperCase(seatId.charAt(0));
      int row = rowChar >= 'A' && rowChar <= 'Z' ? rowChar - 'A' : fallbackIndex / 10;
      int column = Integer.parseInt(seatId.substring(split)) - 1;
      return new SeatPosition(row, Math.max(column, 0));
    }
  }
}
