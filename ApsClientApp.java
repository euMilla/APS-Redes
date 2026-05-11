package aps.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public final class ApsClientApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(ApsClientApp.class.getResource("/aps/client/view/client-view.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 800);
        stage.setTitle("APS Redes - Comunicacao em Rede");
        stage.setMinWidth(920);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

