package com.theater.ticketing.ui;

import com.theater.identity.domain.CurrentUserHolder;
import com.theater.shared.di.Container;
import com.theater.shared.ui.Screens;
import com.theater.ticketing.application.GetTicketDetailUseCase;
import com.theater.ticketing.application.ListMyTicketsUseCase;
import com.theater.ticketing.application.TicketDetailView;
import com.theater.ticketing.application.TicketSummary;
import com.theater.ticketing.domain.TicketStatus;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;

/** Controller for the my-tickets screen (TK-03). */
public final class TicketsController {

  private static final String FILTER_ACTIVE = "ACTIVE のみ";
  private static final String FILTER_ALL = "すべて";
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

  @FXML private ComboBox<String> filterCombo;
  @FXML private Button backButton;
  @FXML private ListView<TicketSummary> ticketListView;
  @FXML private TextArea detailTextArea;

  private final ListMyTicketsUseCase listUseCase;
  private final GetTicketDetailUseCase detailUseCase;
  private final CurrentUserHolder session;

  public TicketsController() {
    Container container = Container.global();
    this.listUseCase = container.resolve(ListMyTicketsUseCase.class);
    this.detailUseCase = container.resolve(GetTicketDetailUseCase.class);
    this.session = container.resolve(CurrentUserHolder.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    ObservableList<String> options = FXCollections.observableArrayList(FILTER_ACTIVE, FILTER_ALL);
    filterCombo.setItems(options);
    filterCombo.getSelectionModel().select(FILTER_ACTIVE);
    filterCombo.valueProperty().addListener((obs, old, value) -> reload());

    ticketListView.setCellFactory(v -> new TicketCell());
    ticketListView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, summary) -> showDetail(summary));

    detailTextArea.clear();
    reload();
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onRefreshClicked() {
    reload();
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onBackClicked() {
    Screens.switchTo(backButton, "/ui/fxml/home.fxml");
  }

  private void reload() {
    detailTextArea.clear();
    if (session.current().isEmpty()) {
      ticketListView.getItems().clear();
      detailTextArea.setText("ログインが必要です。");
      return;
    }
    EnumSet<TicketStatus> statuses =
        FILTER_ALL.equals(filterCombo.getValue())
            ? EnumSet.allOf(TicketStatus.class)
            : EnumSet.of(TicketStatus.ACTIVE);
    List<TicketSummary> summaries =
        listUseCase.execute(new ListMyTicketsUseCase.Command(session.requireUserId(), statuses));
    ticketListView.getItems().setAll(summaries);
    if (summaries.isEmpty()) {
      detailTextArea.setText("該当するチケットはありません。");
    }
  }

  private void showDetail(TicketSummary summary) {
    if (summary == null) {
      detailTextArea.clear();
      return;
    }
    TicketDetailView detail =
        detailUseCase.execute(new GetTicketDetailUseCase.Command(summary.ticketId()));
    detailTextArea.setText(formatDetail(detail));
  }

  private static String formatDetail(TicketDetailView d) {
    StringBuilder sb = new StringBuilder();
    sb.append("チケット ID: ").append(d.ticketId().value()).append('\n');
    sb.append("注文 ID:    ").append(d.orderId().value()).append('\n');
    sb.append('\n');
    sb.append("作品:  ").append(d.movieTitle()).append('\n');
    sb.append("スクリーン: ").append(d.screenName()).append('\n');
    sb.append("上映:  ")
        .append(TIME_FORMATTER.format(d.screeningStartTime()))
        .append(" - ")
        .append(TIME_FORMATTER.format(d.screeningEndTime()))
        .append('\n');
    sb.append("座席:  ").append(d.seatLabel()).append('\n');
    sb.append("料金:  ¥").append(d.price().minorUnits()).append('\n');
    sb.append("状態:  ").append(d.status().name()).append('\n');
    sb.append("購入日時: ").append(TIME_FORMATTER.format(d.purchasedAt())).append('\n');
    if (d.usedAt() != null) {
      sb.append("使用日時: ").append(TIME_FORMATTER.format(d.usedAt())).append('\n');
    }
    return sb.toString();
  }

  private static final class TicketCell extends ListCell<TicketSummary> {
    @Override
    protected void updateItem(TicketSummary item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setText(null);
        return;
      }
      setText(
          TIME_FORMATTER.format(item.screeningStartTime())
              + "  "
              + item.movieTitle()
              + " / "
              + item.seatLabel()
              + "  ["
              + item.status().name()
              + "]");
    }
  }
}
