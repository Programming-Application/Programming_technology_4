package com.theater.catalog.ui;

import com.theater.catalog.application.GetMovieDetailUseCase;
import com.theater.catalog.application.GetScreeningDetailUseCase;
import com.theater.catalog.application.ListPublishedMoviesUseCase;
import com.theater.catalog.application.ListUpcomingScreeningsUseCase;
import com.theater.catalog.application.MovieSummary;
import com.theater.catalog.application.ScreeningDetailView;
import com.theater.catalog.application.ScreeningSummary;
import com.theater.catalog.application.SearchMoviesUseCase;
import com.theater.catalog.domain.CatalogQueryRepository;
import com.theater.shared.di.Container;
import com.theater.shared.kernel.Clock;
import com.theater.shared.kernel.ScreeningId;
import com.theater.shared.kernel.UserId;
import com.theater.shared.session.CurrentSelection;
import com.theater.shared.tx.UnitOfWork;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/** Controller for the catalog home screen. */
public final class HomeController {

  private static final UserId DEMO_USER = new UserId("demo-user");
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

  @FXML private TextField movieSearchField;
  @FXML private Button selectSeatButton;
  @FXML private ListView<MovieSummary> movieListView;
  @FXML private ListView<ScreeningSummary> screeningListView;
  @FXML private TextArea detailTextArea;

  private final ListPublishedMoviesUseCase listMoviesUseCase;
  private final SearchMoviesUseCase searchMoviesUseCase;
  private final ListUpcomingScreeningsUseCase listScreeningsUseCase;
  private final GetMovieDetailUseCase getMovieDetailUseCase;
  private final GetScreeningDetailUseCase getScreeningDetailUseCase;
  private final CurrentSelection currentSelection;

  public HomeController() {
    Container container = Container.global();
    UnitOfWork uow = container.resolve(UnitOfWork.class);
    CatalogQueryRepository repository = container.resolve(CatalogQueryRepository.class);
    Clock clock = container.resolve(Clock.class);
    listMoviesUseCase = new ListPublishedMoviesUseCase(uow, repository);
    searchMoviesUseCase = new SearchMoviesUseCase(uow, repository);
    listScreeningsUseCase = new ListUpcomingScreeningsUseCase(uow, repository, clock);
    getMovieDetailUseCase = new GetMovieDetailUseCase(uow, repository, clock);
    getScreeningDetailUseCase = new GetScreeningDetailUseCase(uow, repository);
    currentSelection = container.resolve(CurrentSelection.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    selectSeatButton.setDisable(true);
    movieListView.setCellFactory(view -> new MovieCell());
    screeningListView.setCellFactory(view -> new ScreeningCell());
    movieListView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, movie) -> showMovie(movie));
    screeningListView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((obs, old, screening) -> showScreening(screening));
    loadPublishedMovies();
    loadUpcomingScreenings();
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onSearchClicked() {
    String titlePart = movieSearchField.getText();
    movieListView
        .getItems()
        .setAll(searchMoviesUseCase.execute(new SearchMoviesUseCase.Command(titlePart)));
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onRefreshClicked() {
    movieSearchField.clear();
    detailTextArea.clear();
    loadPublishedMovies();
    loadUpcomingScreenings();
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onSelectSeatClicked() {
    ScreeningSummary selected = screeningListView.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    UserId userId = currentSelection.currentUserOptional().orElse(DEMO_USER);
    currentSelection.selectScreening(new ScreeningId(selected.screeningId()), userId);
    Stage stage = (Stage) selectSeatButton.getScene().getWindow();
    stage.setScene(new Scene(loadScene("/ui/fxml/seat_select.fxml")));
  }

  private void loadPublishedMovies() {
    movieListView
        .getItems()
        .setAll(listMoviesUseCase.execute(new ListPublishedMoviesUseCase.Command()));
  }

  private void loadUpcomingScreenings() {
    screeningListView
        .getItems()
        .setAll(listScreeningsUseCase.execute(new ListUpcomingScreeningsUseCase.Command()));
    selectSeatButton.setDisable(true);
  }

  private void showMovie(MovieSummary movie) {
    if (movie == null) {
      return;
    }
    var detail = getMovieDetailUseCase.execute(new GetMovieDetailUseCase.Command(movie.movieId()));
    detailTextArea.setText(movie.title() + "\n\n" + movie.description());
    screeningListView.getItems().setAll(detail.upcomingScreenings());
    selectSeatButton.setDisable(true);
  }

  private void showScreening(ScreeningSummary screening) {
    selectSeatButton.setDisable(screening == null);
    if (screening == null) {
      return;
    }
    ScreeningDetailView detail =
        getScreeningDetailUseCase.execute(
            new GetScreeningDetailUseCase.Command(screening.screeningId()));
    detailTextArea.setText(
        detail.movieTitle()
            + "\n"
            + detail.screenName()
            + " / "
            + TIME_FORMATTER.format(detail.startTime())
            + "\n\n"
            + detail.movieDescription()
            + "\n\n空席: "
            + detail.availableSeatCount()
            + " / 予約中: "
            + detail.reservedSeatCount()
            + " / 販売済: "
            + detail.soldSeatCount());
  }

  private static Parent loadScene(String path) {
    try {
      return FXMLLoader.load(HomeController.class.getResource(path));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load FXML: " + path, e);
    }
  }

  private static final class MovieCell extends ListCell<MovieSummary> {
    @Override
    protected void updateItem(MovieSummary item, boolean empty) {
      super.updateItem(item, empty);
      setText(empty || item == null ? null : item.title());
    }
  }

  private static final class ScreeningCell extends ListCell<ScreeningSummary> {
    @Override
    protected void updateItem(ScreeningSummary item, boolean empty) {
      super.updateItem(item, empty);
      setText(empty || item == null ? null : format(item));
    }

    private static String format(ScreeningSummary item) {
      return item.movieTitle()
          + " / "
          + item.screenName()
          + " / "
          + TIME_FORMATTER.format(item.startTime());
    }
  }
}
