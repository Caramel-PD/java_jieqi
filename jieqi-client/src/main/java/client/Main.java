package client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class Main extends Application {
    private WsClient wsClient;
    private ChessBoard board;
    private GameStatusBar statusBar;
    private GameController controller;

    @Override
    public void start(Stage primaryStage) {
        board = new ChessBoard(null);
        statusBar = new GameStatusBar();

        BorderPane root = new BorderPane();
        root.setTop(statusBar);
        root.setCenter(board);

        Scene scene = new Scene(root, 700, 840);
        primaryStage.setTitle("揭棋客户端");
        primaryStage.setScene(scene);
        primaryStage.show();

        LoginDialog login = new LoginDialog(primaryStage);
        login.show();
        login.getStage().setUserData(this);
    }

    // 由LoginDialog调用
    public void connectAndLogin(String serverUrl, String userId, String password) {
        wsClient = new WsClient(serverUrl);
        controller = new GameController(wsClient, board, statusBar);
        board.setController(controller);
        wsClient.setOnOpenCallback(() ->
                wsClient.send(MessageBuilder.buildLogin(userId, password)));
        wsClient.connect();
    }

    @Override
    public void stop() {
        if (wsClient != null) wsClient.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}