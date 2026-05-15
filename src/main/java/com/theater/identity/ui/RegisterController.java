package com.theater.identity.ui;

import com.theater.identity.application.RegisterUserUseCase;
import com.theater.shared.di.Container;
import com.theater.shared.error.ConflictException;
import com.theater.shared.ui.Screens;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** register.fxml の Controller。FXMLLoader が no-arg コンストラクタで生成するため DI bind は不要。 */
public final class RegisterController {

  @FXML private TextField nameField;
  @FXML private TextField emailField;
  @FXML private PasswordField passwordField;
  @FXML private Label errorLabel;
  @FXML private Label successLabel;
  @FXML private Button registerButton;

  private final RegisterUserUseCase registerUseCase;

  public RegisterController() {
    Container container = Container.global();
    this.registerUseCase = container.resolve(RegisterUserUseCase.class);
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void initialize() {
    errorLabel.setText("");
    successLabel.setText("");
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onRegisterClicked() {
    errorLabel.setText("");
    successLabel.setText("");
    String name = nameField.getText();
    String email = emailField.getText();
    String password = passwordField.getText();
    if (name.isBlank()) {
      errorLabel.setText("氏名を入力してください");
      return;
    }
    if (email.isBlank()) {
      errorLabel.setText("メールアドレスを入力してください");
      return;
    }
    if (password.length() < 8) {
      errorLabel.setText("パスワードは 8 文字以上で入力してください");
      return;
    }
    try {
      registerUseCase.execute(new RegisterUserUseCase.Command(email, name, password));
      successLabel.setText("登録が完了しました。ログインしてください。");
      nameField.clear();
      emailField.clear();
      passwordField.clear();
    } catch (ConflictException e) {
      errorLabel.setText("このメールアドレスは既に登録されています");
    } catch (IllegalArgumentException e) {
      errorLabel.setText("メールアドレスの形式を確認してください");
    } catch (RuntimeException e) {
      errorLabel.setText("登録に失敗しました");
    }
  }

  @FXML
  @SuppressWarnings("UnusedMethod")
  private void onLoginLinkClicked() {
    Screens.switchTo(registerButton, "/ui/fxml/login.fxml");
  }
}
