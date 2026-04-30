package com.theater;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Application entry point.
 *
 * <p>Owner: Person A. See {@code docs/architecture.md} §7 for the bootstrap sequence.
 */
// pattern: Singleton (JavaFX Application は1プロセス1インスタンス)
public final class App extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        // TODO(A): Container.init() / Module.bindings(...) / Flyway.migrate() / scheduled jobs
        Parent root = FXMLLoader.load(App.class.getResource("/ui/fxml/login.fxml"));
        stage.setTitle("Theater");
        stage.setScene(new Scene(root));
        stage.show();
    }
}
