package com.theater.identity.ui;

import com.theater.identity.application.LoginUseCase;
import com.theater.shared.di.Container;
import com.theater.shared.error.AuthenticationException;
import com.theater.shared.session.CurrentSelection;
import com.theater.shared.ui.Screens;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** login.fxml の Controller。FXMLLoader が no-arg コンストラクタで生成するため DI bind は不要。 */
public final class LoginController {

  @FXML private TextField emailField;
  @FXML private PasswordField passwordField;
  @FXML private Label errorLabel;
  @FXML private Button loginButton;

  private final LoginUseCase loginUseCase;
  private final CurrentSelection currentSelection;

  public LoginController() {
    Container container = Container.global();
    this.loginUseCase = container.resolve(LoginUseCase.class);
    this.currentSelection = container.resolve(CurrentSelection.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    errorLabel.setText("");
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onLoginClicked() {
    errorLabel.setText("");
    try {
      LoginUseCase.Result result =
          loginUseCase.execute(
              new LoginUseCase.Command(emailField.getText(), passwordField.getText()));
      currentSelection.setCurrentUser(result.userId());
      Screens.switchTo(loginButton, "/ui/fxml/home.fxml");
    } catch (AuthenticationException e) {
      errorLabel.setText("メールアドレスまたはパスワードが違います");
    } catch (IllegalArgumentException e) {
      errorLabel.setText("メールアドレスとパスワードを入力してください");
    } catch (RuntimeException e) {
      errorLabel.setText("ログインに失敗しました");
    }
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onRegisterLinkClicked() {
    Screens.switchTo(loginButton, "/ui/fxml/register.fxml");
  }
}
