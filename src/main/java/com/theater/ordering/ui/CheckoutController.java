package com.theater.ordering.ui;

import com.theater.identity.domain.CurrentUserHolder;
import com.theater.ordering.application.CheckoutSummary;
import com.theater.ordering.application.CheckoutUseCase;
import com.theater.ordering.application.StartCheckoutUseCase;
import com.theater.reservation.domain.SeatState;
import com.theater.shared.di.Container;
import com.theater.shared.error.ConflictException;
import com.theater.shared.error.PaymentFailedException;
import com.theater.shared.kernel.UserId;
import com.theater.shared.session.CurrentSelection;
import com.theater.shared.ui.Screens;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

/** Controller for the checkout screen. */
public final class CheckoutController {

  @FXML private Label totalAmountLabel;
  @FXML private ListView<String> seatList;
  @FXML private Button payButton;
  @FXML private Label errorLabel;

  private final StartCheckoutUseCase startCheckout;
  private final CheckoutUseCase checkout;
  private final CurrentUserHolder currentUserHolder;
  private final CurrentSelection currentSelection;

  private CheckoutSummary summary;

  public CheckoutController() {
    Container container = Container.global();
    startCheckout = container.resolve(StartCheckoutUseCase.class);
    checkout = container.resolve(CheckoutUseCase.class);
    currentUserHolder = container.resolve(CurrentUserHolder.class);
    currentSelection = container.resolve(CurrentSelection.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    errorLabel.setText("");
    UserId userId = currentUserHolder.requireUserId();
    summary =
        startCheckout.execute(
            new StartCheckoutUseCase.Command(currentSelection.activeReservation(), userId));
    totalAmountLabel.setText("合計: " + summary.total().minorUnits() + " 円");
    seatList.setItems(
        FXCollections.observableArrayList(summary.seats().stream().map(this::formatSeat).toList()));
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onPayClicked() {
    errorLabel.setText("");
    payButton.setDisable(true);
    try {
      checkout.execute(
          new CheckoutUseCase.Command(
              summary.reservationId(), currentUserHolder.requireUserId(), summary.total()));
      Screens.switchTo(payButton, "/ui/fxml/tickets.fxml");
    } catch (PaymentFailedException e) {
      errorLabel.setText("決済に失敗しました");
      payButton.setDisable(false);
    } catch (ConflictException e) {
      errorLabel.setText("予約状態が変わりました。再度お試しください");
      payButton.setDisable(false);
    }
  }

  private String formatSeat(SeatState seat) {
    return seat.seatId().value() + " - " + seat.price() + " 円";
  }
}
