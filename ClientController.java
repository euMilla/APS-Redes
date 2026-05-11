package aps.client.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

import aps.client.net.MulticastAlertListener;
import aps.client.net.NetworkClient;
import aps.client.service.WebcamService;
import aps.shared.model.FileMetadata;
import aps.shared.model.MessageType;
import aps.shared.model.NetworkMessage;
import aps.shared.model.UserProfile;
import aps.shared.net.Ports;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

public final class ClientController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("#,##0.0");
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final int HISTORY_LIMIT = 200;
    private static final int STORED_HISTORY_LIMIT = 60;
    private static final long MAX_INLINE_REPORT_BYTES = 2L * 1024L * 1024L;
    private static final Path LOCAL_HISTORY_PATH = Path.of("server-storage", "client-history.log");
    private static final Path LOCAL_REPORT_DIR = Path.of("server-storage", "relatorios-criados");
    private static final Path LOCAL_DOWNLOAD_DIR = Path.of("server-storage", "downloads");
    private static final DateTimeFormatter REPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final String LOBBY_CHANNEL = "__lobby__";
    private static final String DEFAULT_MEMBER_PASSWORD = "membro123";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    private static final String THEME_DARK = "Preto";
    private static final String THEME_LIGHT = "Branco";
    private static final String PREF_THEME = "theme";
    private static final List<String> THEME_CLASSES = List.of("theme-dark", "theme-light");
    private static final List<String> DEFAULT_CHANNELS = List.of("Equipe Alfa", "Equipe Beta", "Equipe Gama", "Central Operacional");
    private static final List<String> ANALYTICS_BAR_CLASSES = List.of(
            "bar-reports",
            "bar-occurrences",
            "bar-critical",
            "bar-alerts",
            "bar-users");
    private static final List<String> ANALYTICS_PIE_CLASSES = List.of(
            "pie-reports",
            "pie-occurrences",
            "pie-alerts");
    private static final String BAR_VALUE_LABEL_CLASS = "bar-value-label";
    private static final String PIE_LEGEND_DOT_CLASS = "pie-legend-dot";
    private static final String ANALYTICS_TOOLTIP_KEY = "aps.analytics.tooltip";

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField messageField;
    @FXML private TextField profileNameField;
    @FXML private TextField loginNameField;
    @FXML private TextField adminNameField;
    @FXML private TextField searchField;
    @FXML private TextField roleNameField;
    @FXML private TextField reportTitleField;
    @FXML private TextField authMemberPasswordField;
    @FXML private TextField authAdminPasswordField;
    @FXML private TextField occurrenceIndustryField;
    @FXML private TextField occurrenceStretchField;
    @FXML private TextField occurrenceTypeField;
    @FXML private TextField occurrenceSeverityField;
    @FXML private PasswordField memberPasswordField;
    @FXML private PasswordField adminPasswordField;
    @FXML private TextArea reportBodyArea;
    @FXML private TextArea reportPreviewArea;
    @FXML private TextArea occurrenceDescriptionArea;
    @FXML private TextArea criticalAlertArea;
    @FXML private ComboBox<String> presenceBox;
    @FXML private ComboBox<String> themeBox;
    @FXML private Label statusLabel;
    @FXML private Label currentRoomLabel;
    @FXML private Label transferLabel;
    @FXML private Label sessionCountLabel;
    @FXML private Label reportListTitleLabel;
    @FXML private Label reportPreviewTitleLabel;
    @FXML private Label dashboardStatusLabel;
    @FXML private Label dashboardRoomLabel;
    @FXML private Label dashboardRoleLabel;
    @FXML private Label dashboardReportsLabel;
    @FXML private Label dashboardTodayReportsLabel;
    @FXML private Label dashboardUsersLabel;
    @FXML private Label profileStatusLabel;
    @FXML private Label loginStatusLabel;
    @FXML private Label roleStatusLabel;
    @FXML private Label authStatusLabel;
    @FXML private Label reportWorkflowStatusLabel;
    @FXML private Label auditStatusLabel;
    @FXML private Label analyticsTodayReportsLabel;
    @FXML private Label analyticsOccurrencesLabel;
    @FXML private Label analyticsCriticalAlertsLabel;
    @FXML private Label analyticsUsersLabel;
    @FXML private Label analyticsSummaryLabel;
    @FXML private Label mapSalesopolisLabel;
    @FXML private Label mapMogiLabel;
    @FXML private Label mapGuarulhosLabel;
    @FXML private Label mapSaoPauloLabel;
    @FXML private Label notificationCountLabel;
    @FXML private Label notificationToastTitle;
    @FXML private Label notificationToastMessage;
    @FXML private Region statusDot;
    @FXML private Button sendButton;
    @FXML private Button webcamButton;
    @FXML private Button createReportButton;
    @FXML private Button uploadButton;
    @FXML private Button downloadButton;
    @FXML private Button refreshFilesButton;
    @FXML private Button chooseProfileImageButton;
    @FXML private Button saveProfileButton;
    @FXML private Button memberLoginButton;
    @FXML private Button adminLoginButton;
    @FXML private Button adminAccessButton;
    @FXML private Button saveRoleButton;
    @FXML private Button saveAuthPasswordsButton;
    @FXML private Button joinChannelButton;
    @FXML private Button sidebarToggleButton;
    @FXML private Button saveDraftButton;
    @FXML private Button validateReportButton;
    @FXML private Button sendCriticalAlertButton;
    @FXML private Button toggleReportToolsButton;
    @FXML private Button notificationButton;
    @FXML private Button loginNavButton;
    @FXML private Button chatNavButton;
    @FXML private Button sessionNavButton;
    @FXML private Button reportsNavButton;
    @FXML private Button occurrencesNavButton;
    @FXML private Button analyticsNavButton;
    @FXML private Button rolesNavButton;
    @FXML private Button adminNavButton;
    @FXML private Button auditNavButton;
    @FXML private Button mapNavButton;
    @FXML private Button historyNavButton;
    @FXML private Button profileNavButton;
    @FXML private ListView<NetworkMessage> chatList;
    @FXML private ListView<FileMetadata> fileList;
    @FXML private ListView<UserProfile> usersList;
    @FXML private ListView<UserProfile> roleUsersList;
    @FXML private ListView<String> historyList;
    @FXML private ListView<String> channelsList;
    @FXML private ListView<String> occurrenceList;
    @FXML private ListView<String> auditList;
    @FXML private ListView<String> criticalAlertList;
    @FXML private ListView<String> mapOccurrenceList;
    @FXML private CheckBox roleAlfaBox;
    @FXML private CheckBox roleBetaBox;
    @FXML private CheckBox roleGamaBox;
    @FXML private CheckBox roleCentralBox;
    @FXML private ImageView profileImageView;
    @FXML private BorderPane rootShell;
    @FXML private VBox loginPage;
    @FXML private VBox adminLoginPage;
    @FXML private VBox chatPage;
    @FXML private VBox rolesPage;
    @FXML private VBox adminPage;
    @FXML private VBox sessionPage;
    @FXML private VBox reportsPage;
    @FXML private VBox occurrencesPage;
    @FXML private VBox analyticsPage;
    @FXML private VBox auditPage;
    @FXML private VBox mapPage;
    @FXML private VBox historyPage;
    @FXML private VBox profilePage;
    @FXML private VBox reportEditorBox;
    @FXML private VBox analyticsBarChartHost;
    @FXML private VBox analyticsPieChartHost;
    @FXML private VBox notificationToast;
    @FXML private VBox leftPanel;
    @FXML private ScrollPane leftScroll;
    @FXML private HBox topBar;
    @FXML private VBox skeletonOverlay;
    @FXML private HBox loginShell;
    @FXML private HBox adminLoginShell;
    @FXML private VBox loginHero;
    @FXML private VBox adminHero;
    @FXML private Label skeletonTitle;
    @FXML private Rectangle shimmerBar;
    @FXML private ProgressBar transferProgress;
    @FXML private ProgressBar analyticsReportsBar;
    @FXML private ProgressBar analyticsOccurrencesBar;
    @FXML private ProgressBar analyticsUsersBar;
    @FXML private ProgressBar mapSalesopolisBar;
    @FXML private ProgressBar mapMogiBar;
    @FXML private ProgressBar mapGuarulhosBar;
    @FXML private ProgressBar mapSaoPauloBar;
    @FXML private Pane tieteMapPane;

    private final NetworkClient networkClient = new NetworkClient();
    private final WebcamService webcamService = new WebcamService();
    private final Preferences preferences = Preferences.userNodeForPackage(ClientController.class);
    private final ObservableList<NetworkMessage> chatMessages = FXCollections.observableArrayList();
    private final Map<String, List<NetworkMessage>> messagesByChannel = new HashMap<>();
    private final ObservableList<FileMetadata> remoteFiles = FXCollections.observableArrayList();
    private final ObservableList<UserProfile> connectedUsers = FXCollections.observableArrayList();
    private final ObservableList<UserProfile> roleUsers = FXCollections.observableArrayList();
    private final ObservableList<String> connectionHistory = FXCollections.observableArrayList();
    private final ObservableList<String> auditEvents = FXCollections.observableArrayList();
    private final ObservableList<String> environmentalOccurrences = FXCollections.observableArrayList();
    private final ObservableList<String> criticalAlerts = FXCollections.observableArrayList();
    private final ObservableList<String> notifications = FXCollections.observableArrayList();
    private final ObservableList<String> channels = FXCollections.observableArrayList(DEFAULT_CHANNELS);
    private final Map<String, String> reportWorkflowById = new HashMap<>();
    private final long clientStartedAt = System.currentTimeMillis();
    private MulticastAlertListener multicastListener;
    private Timeline shimmerTimeline;
    private Timeline notificationTimeline;
    private Timeline automaticProblemTimeline;
    private BarChart<String, Number> analyticsBarChart;
    private PieChart analyticsPieChart;
    private VBox analyticsPieLegend;
    private NumberAxis analyticsNumberAxis;
    private Path avatarPath;
    private String activeRoom = "";
    private boolean adminSession;
    private boolean awaitingLogin;
    private boolean autoJoiningChannel;
    private boolean sidebarCollapsed;
    private String currentRole = "Membro";
    private volatile String pendingReportPreviewId = "";
    private long currentSessionStartedAt;
    private String presenceStatus = "Online";
    private String lastDraftTitle = "";
    private String lastDraftBody = "";
    private byte[] profileAvatar = new byte[0];

    @FXML
    private void initialize() {
        configureLists();
        loadProfile();
        loadHistory();
        fillDefaultPasswords();
        configurePresence();
        configureTheme();
        configureDefaultHost();
        authMemberPasswordField.clear();
        authAdminPasswordField.clear();
        showPage(loginPage, loginNavButton);

        messageField.setOnAction(event -> onSend());
        loginNameField.setOnAction(event -> onLoginMember());
        adminNameField.setOnAction(event -> onLoginAdmin());
        memberPasswordField.setOnAction(event -> onLoginMember());
        adminPasswordField.setOnAction(event -> onLoginAdmin());
        channelsList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && networkClient.isConnected()) {
                onJoinSelectedChannel();
            }
        });
        roleUsersList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                populateRoleForm(selected));
        fileList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            updateActionAvailability();
            if (selected == null) {
                clearReportPreview();
            } else if (reportsPage.isVisible()) {
                reportWorkflowStatusLabel.setText("Workflow: " + reportWorkflowById.getOrDefault(selected.getId(), "ENVIADO") + " - " + selected.getFileName());
                previewReport(selected);
            }
        });
        networkClient.addMessageListener(message -> Platform.runLater(() -> handleMessage(message)));
        networkClient.addStateListener((online, detail) -> Platform.runLater(() -> updateConnectionState(online, detail)));

        shimmerTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(shimmerBar.translateXProperty(), -340)),
                new KeyFrame(Duration.seconds(1.25), new KeyValue(shimmerBar.translateXProperty(), 620))
        );
        shimmerTimeline.setCycleCount(Timeline.INDEFINITE);
        notificationTimeline = new Timeline(new KeyFrame(Duration.seconds(6), event -> hideNotificationToast()));
        automaticProblemTimeline = new Timeline(
                new KeyFrame(Duration.seconds(6), event -> generateAutomaticProblem()),
                new KeyFrame(Duration.seconds(24), event -> generateAutomaticProblem())
        );
        automaticProblemTimeline.setCycleCount(Timeline.INDEFINITE);
        if (tieteMapPane != null) {
            tieteMapPane.widthProperty().addListener((observable, previous, width) -> updateMapSummary());
        }
        if (notificationToast != null) {
            notificationToast.setMaxHeight(130);
            notificationToast.setPrefHeight(Region.USE_COMPUTED_SIZE);
        }

        transferProgress.setVisible(false);
        transferProgress.setManaged(false);
        clearReportPreview();
        setConnected(false);
        configureResponsiveLayout();
        configureAnalyticsCharts();
        updateNotificationCount();
        updateDashboard();
        updateAnalytics();
        updateMapSummary();
        addLocalSystem("Cliente pronto. Configure o perfil, conecte-se ao servidor e escolha um canal.");
    }

    @FXML
    private void onLoginMember() {
        connectWithSession(false);
    }

    @FXML
    private void onLoginAdmin() {
        connectWithSession(true);
    }

    private void connectWithSession(boolean asAdmin) {
        connectWithSession(asAdmin, false);
    }

    private void connectWithSession(boolean asAdmin, boolean alreadyRetriedLocalServer) {
        if (networkClient.isConnected()) {
            return;
        }
        adminSession = asAdmin;
        awaitingLogin = true;
        syncProfileFromLogin();
        saveProfileSilently();
        String selectedHost = valueOrDefault(valueOf(hostField), detectLanAddress());
        String selectedPort = valueOrDefault(valueOf(portField), Integer.toString(Ports.DEFAULT_CHAT_PORT));
        syncServerFields(selectedHost, selectedPort);
        int chatPort = parsePort(selectedPort, Ports.DEFAULT_CHAT_PORT);
        int filePort = chatPort + 1;
        byte[] avatar;
        try {
            avatar = readAvatarBytes();
            profileAvatar = avatar.clone();
        } catch (IOException exception) {
            showError("Perfil", exception.getMessage());
            return;
        }

        showSkeleton("Abrindo canal TCP", false);
        loginStatusLabel.setText(asAdmin ? "Validando sessao admin..." : "Entrando como membro...");
        String password = passwordForSession(asAdmin);
        resetConversationState();
        activeRoom = "";
        currentRoomLabel.setText("Sem canal");
        networkClient.connect(profileName(), selectedHost, chatPort, filePort, avatar, asAdmin, password)
                .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                    hideSkeleton();
                    if (exception != null) {
                        awaitingLogin = false;
                        setConnected(false);
                        addHistory("Falha ao conectar: " + rootMessage(exception));
                        if (!alreadyRetriedLocalServer
                                && isLocalHost(selectedHost)
                                && isConnectionRefused(rootMessage(exception))
                                && startLocalServerWindow()) {
                            loginStatusLabel.setText("Servidor local iniciado. Tentando novamente...");
                            retryLoginAfterServerStart(asAdmin);
                            return;
                        }
                        loginStatusLabel.setText("Falha no login. Verifique se o servidor esta aberto.");
                        showError("Conexao", rootMessage(exception));
                    } else {
                        updateConnectionState(true, "Validando");
                        loginStatusLabel.setText("Aguardando confirmacao do servidor...");
                    }
                }));
    }

    private void retryLoginAfterServerStart(boolean asAdmin) {
        showSkeleton("Abrindo servidor local", false);
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(3500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }).whenComplete((ignored, exception) -> Platform.runLater(() -> {
            hideSkeleton();
            if (exception != null) {
                loginStatusLabel.setText("Falha ao iniciar servidor local.");
                showError("Conexao", rootMessage(exception));
                return;
            }
            connectWithSession(asAdmin, true);
        }));
    }

    @FXML
    private void onDisconnect() {
        String room = activeRoomText();
        networkClient.disconnect();
        stopMulticastListener();
        remoteFiles.clear();
        connectedUsers.clear();
        roleUsers.clear();
        messagesByChannel.clear();
        chatMessages.clear();
        activeRoom = "";
        networkClient.setConfirmedRoom("");
        awaitingLogin = false;
        adminSession = false;
        currentRole = "Membro";
        currentSessionStartedAt = 0L;
        updateSessionCount();
        setConnected(false);
        currentRoomLabel.setText("Sem canal");
        clearReportPreview();
        updateReportScopeLabel();
        updateDashboard();
        loginStatusLabel.setText("");
        addHistory(room.isBlank() ? "Saiu do servidor" : "Saiu de " + room);
        addLocalSystem("Conexao encerrada.");
    }

    @FXML
    private void onJoinSelectedChannel() {
        String channel = channelsList.getSelectionModel().getSelectedItem();
        if (channel == null || channel.isBlank()) {
            showError("Canal", "Selecione um canal.");
            return;
        }
        if (channel.equals(activeRoomText())) {
            renderChannel(channel);
            refreshFiles();
            showPage(chatPage, chatNavButton);
            return;
        }
        String previousRoom = activeRoomText();
        activeRoom = channel;
        currentRoomLabel.setText(channel);
        renderChannel(channel);
        remoteFiles.clear();
        connectedUsers.clear();
        updateSessionCount();
        showSkeleton("Entrando em " + channel, false);
        networkClient.joinRoom(channel).whenComplete((ignored, exception) -> Platform.runLater(() -> {
            hideSkeleton();
            if (exception != null) {
                activeRoom = previousRoom;
                currentRoomLabel.setText(previousRoom.isBlank() ? "Sem canal" : previousRoom);
                renderChannel(previousRoom);
                updateActionAvailability();
                showError("Canal", rootMessage(exception));
            } else {
                activeRoom = channel;
                currentRoomLabel.setText(channel);
                renderChannel(channel);
                addHistory("Entrou no canal " + channel);
                updateActionAvailability();
                refreshFiles();
                showPage(chatPage, chatNavButton);
            }
        }));
    }

    @FXML private void onShowLogin() {
        showPage(loginPage, loginNavButton);
    }
    @FXML private void onShowAdminLogin() {
        showPage(adminLoginPage, loginNavButton);
    }
    @FXML
    private void onShowChat() {
        if (!networkClient.isConnected()) {
            showPage(chatPage, chatNavButton);
            return;
        }
        if (!hasActiveRoom() && !channels.isEmpty()) {
            channelsList.getSelectionModel().select(0);
            onJoinSelectedChannel();
            return;
        }
        showPage(chatPage, chatNavButton);
    }
    @FXML private void onShowSession() { showPage(sessionPage, sessionNavButton); }
    @FXML private void onShowReports() {
        showPage(reportsPage, reportsNavButton);
        refreshFiles();
    }
    @FXML private void onShowOccurrences() { showPage(occurrencesPage, occurrencesNavButton); }
    @FXML private void onShowAnalytics() {
        updateAnalytics();
        showPage(analyticsPage, analyticsNavButton);
    }
    @FXML private void onShowRoles() { showPage(rolesPage, rolesNavButton); }
    @FXML private void onShowAdmin() { showPage(adminPage, adminNavButton); }
    @FXML private void onShowAudit() {
        auditList.refresh();
        showPage(auditPage, auditNavButton);
    }
    @FXML private void onShowMap() {
        updateMapSummary();
        showPage(mapPage, mapNavButton);
    }
    @FXML private void onShowHistory() {
        historyList.refresh();
        showPage(historyPage, historyNavButton);
    }
    @FXML private void onShowProfile() { showPage(profilePage, profileNavButton); }

    @FXML
    private void onToggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        updateShellChrome(networkClient.isConnected());
    }

    @FXML
    private void onLogoutToLogin() {
        if (networkClient.isConnected()) {
            onDisconnect();
        }
        showPage(loginPage, loginNavButton);
    }

    @FXML
    private void onShowNotifications() {
        if (notifications.isEmpty()) {
            showInfo("Notificacoes", "Nenhuma notificacao operacional registrada ainda.");
            return;
        }
        StringBuilder builder = new StringBuilder();
        notifications.stream().limit(12).forEach(line -> builder.append(line).append(System.lineSeparator()));
        showInfo("Notificacoes", builder.toString());
    }

    private void generateAutomaticProblem() {
        if (!networkClient.isConnected()) {
            return;
        }
        registerGeneratedProblem(true);
    }

    private void registerGeneratedProblem(boolean automatic) {
        String[] stretches = {"Salesopolis", "Mogi das Cruzes", "Guarulhos", "Grande Sao Paulo", "Barueri", "Osasco"};
        String[] industries = {"Textil Paulista", "Metalurgica Anchieta", "Quimica Horizonte", "Tinturaria Nova Margem", "Galvanica Industrial"};
        String[] types = {"Efluente fora do padrao", "Odor quimico intenso", "Espuma no curso d'agua", "Descarte irregular", "Alteracao de cor da agua"};
        long seed = System.currentTimeMillis() / (automatic ? 1000L : 1L);
        String stretch = stretches[(int) (Math.abs(seed) % stretches.length)];
        String industry = industries[(int) (Math.abs(seed / 3) % industries.length)];
        String type = types[(int) (Math.abs(seed / 5) % types.length)];
        String severity = automatic && seed % 4 != 0 ? "ALTA" : "CRITICA";
        String line = TIME_FORMAT.format(Instant.now())
                + " | " + severity + " | " + stretch
                + " | " + industry + " | " + type
                + (automatic ? " | Gerado automaticamente pelo monitoramento" : " | Notificacao gerada pelo mapa");
        environmentalOccurrences.add(0, line);
        addAuditEvent("OCORRENCIA", line);
        addProblemNotification("Problema no trecho " + stretch, severity + " - " + type + " em " + industry + ".");
        createAndPublishOperationalReport("Ocorrencia automatica - " + stretch,
                occurrenceReportBody(severity, stretch, industry, type, automatic ? "Gerado automaticamente pelo monitoramento." : ""));
        updateAnalytics();
        updateMapSummary();
    }

    @FXML
    private void onSaveRoleAccess() {
        if (!adminSession) {
            showError("Cargos", "Apenas admins podem alterar cargos.");
            return;
        }
        UserProfile selected = roleUsersList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Cargos", "Selecione um membro.");
            return;
        }
        if (selected.isAdmin()) {
            showError("Cargos", "Admins ja possuem acesso total.");
            return;
        }

        String role = roleNameField.getText() == null ? "" : roleNameField.getText().trim();
        if (role.isBlank()) {
            role = "Membro";
        }
        List<String> selectedChannels = selectedRoleChannels();
        roleStatusLabel.setText("Salvando cargo...");
        networkClient.updateRoleAccess(selected.getUsername(), role, selectedChannels)
                .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                    if (exception != null) {
                        roleStatusLabel.setText("Falha ao salvar cargo.");
                        showError("Cargos", rootMessage(exception));
                    } else {
                        roleStatusLabel.setText("Cargo salvo para " + selected.getDisplayName() + ".");
                    }
                }));
    }

    @FXML
    private void onSaveAuthPasswords() {
        if (!networkClient.isConnected()) {
            showError("Admin", "Conecte-se ao servidor antes de alterar senhas.");
            return;
        }
        if (!adminSession) {
            showError("Admin", "Apenas administradores podem alterar senhas do sistema.");
            return;
        }
        String memberPassword = valueOf(authMemberPasswordField);
        String adminPassword = valueOf(authAdminPasswordField);

        boolean changingMember = !memberPassword.isBlank();
        boolean changingAdmin = !adminPassword.isBlank();
        if (!changingMember && !changingAdmin) {
            showError("Admin", "Preencha pelo menos uma senha para alterar.");
            return;
        }
        if ((changingMember && !isStrongPassword(memberPassword)) || (changingAdmin && !isStrongPassword(adminPassword))) {
            showError("Admin", "As senhas precisam ter pelo menos 8 caracteres, com letras e numeros.");
            return;
        }
        authStatusLabel.setText("Salvando senhas no servidor...");

        networkClient.updatePasswords(memberPassword, adminPassword)
                .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                    if (exception != null) {
                        authStatusLabel.setText("Falha ao salvar senhas.");
                        showError("Admin", rootMessage(exception));
                    } else {
                        if (!memberPassword.isBlank()) {
                            memberPasswordField.setText(memberPassword);
                        }
                        if (adminSession) {
                            if (!adminPassword.isBlank()) {
                                adminPasswordField.setText(adminPassword);
                            }
                            authMemberPasswordField.clear();
                            authAdminPasswordField.clear();
                            authStatusLabel.setText("Senhas enviadas ao servidor.");
                        }
                    }
                }));
    }

    @FXML
    private void onToggleReportTools() {
        boolean nextVisible = !reportEditorBox.isVisible();
        reportEditorBox.setVisible(nextVisible);
        reportEditorBox.setManaged(nextVisible);
        toggleReportToolsButton.setText(nextVisible ? "Fechar editor" : "Abrir editor");
    }

    @FXML
    private void onSaveReportDraft() {
        lastDraftTitle = valueOf(reportTitleField);
        lastDraftBody = valueOf(reportBodyArea);
        if (lastDraftTitle.isBlank() && lastDraftBody.isBlank()) {
            showError("Relatorios", "Escreva um titulo ou conteudo para salvar o rascunho.");
            return;
        }
        String title = lastDraftTitle.isBlank() ? "Rascunho sem titulo" : lastDraftTitle;
        reportWorkflowStatusLabel.setText("Workflow: RASCUNHO salvo localmente - " + title);
        addAuditEvent("RASCUNHO", "Relatorio salvo como rascunho: " + title);
    }

    @FXML
    private void onValidateReport() {
        if (!networkClient.isConnected() || !adminSession) {
            showError("Relatorios", "Apenas administrador conectado pode validar relatorios.");
            return;
        }
        FileMetadata selected = fileList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Relatorios", "Selecione um relatorio para validar.");
            return;
        }
        reportWorkflowById.put(selected.getId(), "VALIDADO");
        reportWorkflowStatusLabel.setText("Workflow: VALIDADO pelo admin - " + selected.getFileName());
        addAuditEvent("VALIDACAO", "Admin validou " + selected.getFileName() + " (" + selected.getId() + ")");
        fileList.refresh();
        updateAnalytics();
    }

    @FXML
    private void onSaveOccurrence() {
        String industry = valueOf(occurrenceIndustryField);
        String stretch = valueOf(occurrenceStretchField);
        String type = valueOf(occurrenceTypeField);
        String severity = valueOf(occurrenceSeverityField);
        String description = valueOf(occurrenceDescriptionArea);
        if (industry.isBlank() || stretch.isBlank() || type.isBlank() || severity.isBlank()) {
            showError("Ocorrencias", "Preencha industria, trecho, tipo e gravidade.");
            return;
        }
        String line = TIME_FORMAT.format(Instant.now())
                + " | " + severity.toUpperCase(Locale.ROOT)
                + " | " + stretch
                + " | " + industry
                + " | " + type
                + (description.isBlank() ? "" : " | " + description);
        environmentalOccurrences.add(0, line);
        addAuditEvent("OCORRENCIA", line);
        addProblemNotification("Ocorrencia ambiental registrada", severity.toUpperCase(Locale.ROOT) + " em " + stretch + " - " + industry);
        createAndPublishOperationalReport("Ocorrencia ambiental - " + stretch,
                occurrenceReportBody(severity.toUpperCase(Locale.ROOT), stretch, industry, type, description));
        occurrenceIndustryField.clear();
        occurrenceStretchField.clear();
        occurrenceTypeField.clear();
        occurrenceSeverityField.clear();
        occurrenceDescriptionArea.clear();
        updateAnalytics();
        updateMapSummary();
    }

    @FXML
    private void onSendCriticalAlert() {
        if (!networkClient.isConnected()) {
            showError("Alerta critico", "Conecte-se ao servidor antes de emitir alerta critico.");
            return;
        }
        String text = valueOf(criticalAlertArea);
        if (text.isBlank()) {
            showError("Alerta critico", "Descreva o risco antes de enviar.");
            return;
        }
        sendCriticalAlertButton.setDisable(true);
        networkClient.sendCriticalAlert(text)
                .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                    sendCriticalAlertButton.setDisable(false);
                    if (exception != null) {
                        showError("Alerta critico", rootMessage(exception));
                    } else {
                        criticalAlertArea.clear();
                        String line = TIME_FORMAT.format(Instant.now()) + " | Emitido por " + profileName() + " | " + text;
                        criticalAlerts.add(0, line);
                        addAuditEvent("ALERTA_CRITICO", line);
                        addProblemNotification("Alerta critico enviado", text);
                        createAndPublishOperationalReport("Alerta critico - " + profileName(),
                                "Tipo: Alerta critico" + System.lineSeparator()
                                        + "Emitido por: " + profileName() + System.lineSeparator()
                                        + "Canal: " + activeRoomText() + System.lineSeparator()
                                        + "Descricao: " + text);
                        updateAnalytics();
                    }
                }));
    }

    @FXML
    private void onExportAuditCsv() {
        try {
            Files.createDirectories(Path.of("server-storage", "exportacoes"));
            Path target = Path.of("server-storage", "exportacoes", "auditoria-" + REPORT_FILE_FORMAT.format(Instant.now()) + ".csv");
            Files.writeString(target, formatCsvExport(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            auditStatusLabel.setText("CSV exportado: " + target.toAbsolutePath());
            addAuditEvent("EXPORTACAO", "CSV exportado em " + target.toAbsolutePath());
            showInfo("Exportacao", "CSV salvo em:\n" + target.toAbsolutePath());
        } catch (IOException exception) {
            showError("Exportacao", "Falha ao exportar CSV: " + exception.getMessage());
        }
    }

    @FXML
    private void onExportOperationsPdf() {
        try {
            Files.createDirectories(Path.of("server-storage", "exportacoes"));
            Path target = Path.of("server-storage", "exportacoes", "relatorio-operacional-" + REPORT_FILE_FORMAT.format(Instant.now()) + ".pdf");
            writeSimplePdf(target, pdfLines());
            auditStatusLabel.setText("PDF exportado: " + target.toAbsolutePath());
            addAuditEvent("EXPORTACAO", "PDF exportado em " + target.toAbsolutePath());
            showInfo("Exportacao", "PDF salvo em:\n" + target.toAbsolutePath());
        } catch (IOException exception) {
            showError("Exportacao", "Falha ao exportar PDF: " + exception.getMessage());
        }
    }

    @FXML
    private void onCreateCompleteOperationsReport() {
        String title = "Relatorio completo da operacao";
        String body = completeOperationsReportBody();
        try {
            Path reportPath = createLocalReport(title, body, hasActiveRoom() ? activeRoomText() : "Geral", profileName());
            previewLocalReport("Relatorio completo: " + reportPath.getFileName(), reportPath);
            addAuditEvent("RELATORIO_COMPLETO", "Relatorio completo criado: " + reportPath.getFileName());

            if (!networkClient.isConnected() || !hasActiveRoom()) {
                reportWorkflowStatusLabel.setText("Relatorio completo criado localmente. Conecte-se a um canal para publicar.");
                showInfo("Relatorio completo", "Relatorio criado em:\n" + reportPath.toAbsolutePath());
                return;
            }

            long reportSize = Files.size(reportPath);
            showSkeleton("Publicando relatorio completo", true);
            setTransferProgress(0, reportSize);
            networkClient.uploadFile(reportPath, (sent, total) -> Platform.runLater(() -> setTransferProgress(sent, total)))
                    .whenComplete((fileId, exception) -> Platform.runLater(() -> {
                        hideSkeleton();
                        clearTransferProgress();
                        if (exception != null) {
                            reportWorkflowStatusLabel.setText("Falha ao publicar relatorio completo.");
                            showError("Relatorio completo", rootMessage(exception));
                            return;
                        }
                        reportWorkflowById.put(fileId, "ENVIADO");
                        reportWorkflowStatusLabel.setText("Relatorio completo ENVIADO para validacao - ID " + fileId);
                        addLocalSystem("Relatorio completo publicado para leitura. ID do arquivo: " + fileId);
                        refreshFiles();
                    }));
        } catch (IOException exception) {
            showError("Relatorio completo", "Falha ao criar relatorio completo: " + exception.getMessage());
        }
    }

    @FXML
    private void onChooseProfileImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecionar imagem de perfil");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagens", "*.png", "*.jpg", "*.jpeg", "*.gif"));
        Optional.ofNullable(chooser.showOpenDialog(chooseProfileImageButton.getScene().getWindow()))
                .map(file -> file.toPath())
                .ifPresent(path -> {
                    avatarPath = path;
                    profileImageView.setImage(new Image(path.toUri().toString(), 116, 116, true, true));
                    profileStatusLabel.setText("Imagem selecionada. Salve o perfil.");
                });
    }

    @FXML
    private void onSaveProfile() {
        saveProfileSilently();
        loginNameField.setText(profileName());
        profileStatusLabel.setText(networkClient.isConnected()
                ? "Perfil salvo. Reconecte para trocar o nome na sessao atual."
                : "Perfil salvo localmente.");
    }

    @FXML
    private void onSend() {
        String text = messageField.getText().trim();
        if (text.isBlank()) {
            return;
        }
        messageField.clear();
        setSendingState("A enviar");
        networkClient.sendChat(text).whenComplete((ignored, exception) -> Platform.runLater(() -> {
            if (exception != null) {
                showError("Envio", rootMessage(exception));
                updateConnectionState(networkClient.isConnected(), networkClient.isConnected() ? "Online" : "Offline");
            } else {
                updateConnectionState(true, "Online");
            }
        }));
    }

    @FXML
    private void onCaptureWebcam() {
        if (!networkClient.isConnected() || !hasActiveRoom()) {
            showError("Webcam", "Entre em um canal antes de enviar foto da webcam.");
            return;
        }
        showSkeleton("Capturando imagem da webcam", false);
        webcamService.capturePng()
                .thenCompose(networkClient::sendImage)
                .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                    hideSkeleton();
                    if (exception != null) {
                        showError("Webcam", rootMessage(exception));
                    } else {
                        addLocalSystem("Foto da webcam enviada. Se a camera nao estiver disponivel, foi enviada uma imagem demonstrativa.");
                    }
                }));
    }

    @FXML
    private void onCreateReport() {
        if (!networkClient.isConnected() || !hasActiveRoom()) {
            showError("Relatorios", "Entre em um canal antes de criar relatorios.");
            return;
        }
        String title = valueOf(reportTitleField);
        String body = valueOf(reportBodyArea);
        if (body.isBlank()) {
            showError("Relatorios", "Escreva o conteudo do relatorio antes de criar.");
            return;
        }

        String room = activeRoomText();
        String author = profileName();
        String finalTitle = title.isBlank() ? "Relatorio " + TIME_FORMAT.format(Instant.now()) : title;
        showSkeleton("Criando relatorio", true);
        setTransferProgress(0, 1);
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return createLocalReport(finalTitle, body, room, author);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Falha ao criar relatorio local: " + exception.getMessage(), exception);
                    }
                })
                .thenCompose(path -> networkClient.uploadFile(path, (sent, total) ->
                        Platform.runLater(() -> setTransferProgress(sent, total))))
                .whenComplete((fileId, exception) -> Platform.runLater(() -> {
                    hideSkeleton();
                    clearTransferProgress();
                    if (exception != null) {
                        showError("Relatorios", rootMessage(exception));
                    } else {
                        reportTitleField.clear();
                        reportBodyArea.clear();
                        reportWorkflowById.put(fileId, "ENVIADO");
                        reportWorkflowStatusLabel.setText("Workflow: ENVIADO para validacao - ID " + fileId);
                        addAuditEvent("RELATORIO", "Relatorio enviado para validacao: " + fileId);
                        addLocalSystem("Relatorio criado e enviado. ID do arquivo: " + fileId);
                        refreshFiles();
                    }
                }));
    }

    @FXML
    private void onUploadFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecionar relatorio");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Relatorios e documentos", "*.txt", "*.pdf", "*.doc", "*.docx", "*.csv", "*.log", "*.md")
        );
        try {
            Files.createDirectories(LOCAL_REPORT_DIR);
            chooser.setInitialDirectory(LOCAL_REPORT_DIR.toFile());
        } catch (IOException ignored) {
            // A pasta local e apenas uma conveniencia para encontrar relatorios criados no app.
        }
        if (!networkClient.isConnected() || !hasActiveRoom()) {
            showError("Relatorios", "Entre em um canal antes de enviar relatorios.");
            return;
        }
        Optional.ofNullable(chooser.showOpenDialog(uploadButton.getScene().getWindow()))
                .map(file -> file.toPath())
                .ifPresent(path -> {
                    showSkeleton("Transmitindo relatorio por TCP", true);
                    setTransferProgress(0, 1);
                    networkClient.uploadFile(path, (sent, total) -> Platform.runLater(() -> setTransferProgress(sent, total)))
                            .whenComplete((fileId, exception) -> Platform.runLater(() -> {
                                hideSkeleton();
                                clearTransferProgress();
                                if (exception != null) {
                                    showError("Upload", rootMessage(exception));
                                } else {
                                    reportWorkflowById.put(fileId, "ENVIADO");
                                    reportWorkflowStatusLabel.setText("Workflow: ENVIADO para validacao - ID " + fileId);
                                    addAuditEvent("RELATORIO", "Arquivo enviado para validacao: " + fileId);
                                    addLocalSystem("Upload concluido. ID do arquivo: " + fileId);
                                    refreshFiles();
                                }
                            }));
                });
    }

    @FXML
    private void onDownloadFile() {
        FileMetadata selected = fileList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Download", "Selecione um relatorio na lista.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Escolher pasta para download");
        Optional.ofNullable(chooser.showDialog(downloadButton.getScene().getWindow()))
                .map(file -> file.toPath())
                .ifPresent(directory -> {
                    showSkeleton("Recebendo arquivo por stream TCP", true);
                    setTransferProgress(0, selected.getSize());
                    networkClient.downloadFile(selected, directory, (received, total) -> Platform.runLater(() -> setTransferProgress(received, total)))
                            .whenComplete((path, exception) -> Platform.runLater(() -> {
                                hideSkeleton();
                                clearTransferProgress();
                                if (exception != null) {
                                    showError("Download", rootMessage(exception));
                                } else {
                                    addLocalSystem("Download salvo em: " + path);
                                }
                            }));
                });
    }

    private void previewReport(FileMetadata selected) {
        if (selected == null) {
            clearReportPreview();
            return;
        }
        reportPreviewTitleLabel.setText("Leitura: " + selected.getFileName());
        pendingReportPreviewId = selected.getId();
        if (!canPreviewInline(selected)) {
            reportPreviewArea.setText("Este arquivo nao e texto simples.\n\n"
                    + "Arquivo: " + selected.getFileName() + "\n"
                    + "Canal: " + selected.getRoom() + "\n"
                    + "Autor: " + selected.getOwner() + "\n"
                    + "Tamanho: " + formatBytes(selected.getSize()) + "\n\n"
                    + "Use Download para salvar esse formato.");
            return;
        }

        reportPreviewArea.setText("Carregando relatorio...");
        showSkeleton("Carregando relatorio", true);
        setTransferProgress(0, selected.getSize());
        networkClient.downloadFile(selected, LOCAL_DOWNLOAD_DIR,
                        (received, total) -> Platform.runLater(() -> setTransferProgress(received, total)))
                .whenComplete((path, exception) -> Platform.runLater(() -> {
                    if (!selected.getId().equals(pendingReportPreviewId)) {
                        return;
                    }
                    hideSkeleton();
                    clearTransferProgress();
                    if (exception != null) {
                        reportPreviewArea.setText("Falha ao carregar relatorio: " + rootMessage(exception));
                        showError("Relatorios", rootMessage(exception));
                        return;
                    }
                    try {
                        reportPreviewArea.setText(Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException readException) {
                        reportPreviewArea.setText("Nao foi possivel ler este relatorio como texto.\n\n"
                                + "Arquivo baixado em: " + path.toAbsolutePath());
                    }
                }));
    }

    @FXML
    private void onRefreshFiles() {
        refreshFiles();
    }

    private void configureLists() {
        chatList.setItems(chatMessages);
        chatList.setCellFactory(list -> new MessageCell());
        chatList.setPlaceholder(new Label("Entre em um canal para iniciar o chat."));
        fileList.setItems(remoteFiles);
        fileList.setCellFactory(list -> new FileCell());
        usersList.setItems(connectedUsers);
        usersList.setCellFactory(list -> new UserCell());
        roleUsersList.setItems(roleUsers);
        roleUsersList.setCellFactory(list -> new UserCell());
        historyList.setItems(connectionHistory);
        installReadableList(historyList, "Historico");
        channelsList.setItems(channels);
        channelsList.setPlaceholder(new Label("Canais disponiveis apos login."));
        occurrenceList.setItems(environmentalOccurrences);
        installReadableList(occurrenceList, "Relatorio da ocorrencia");
        auditList.setItems(auditEvents);
        installReadableList(auditList, "Registro de auditoria");
        criticalAlertList.setItems(criticalAlerts);
        installReadableList(criticalAlertList, "Relatorio do alerta critico");
        mapOccurrenceList.setItems(environmentalOccurrences);
        installReadableList(mapOccurrenceList, "Relatorio da ocorrencia");
    }

    private void installReadableList(ListView<String> listView, String title) {
        listView.setCellFactory(list -> new ListCell<>() {
            private final Label label = new Label();

            {
                label.setWrapText(true);
                label.getStyleClass().add("readable-list-row");
                label.maxWidthProperty().bind(widthProperty().subtract(18));
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(item);
                setGraphic(label);
            }
        });
        listView.setOnMouseClicked(event -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && event.getClickCount() == 2) {
                showReadableText(title, selected);
            }
        });
    }

    private Path createLocalReport(String title, String body, String room, String author) throws IOException {
        Files.createDirectories(LOCAL_REPORT_DIR);
        String timestamp = REPORT_FILE_FORMAT.format(Instant.now());
        String fileName = timestamp + "-" + safeFileStem(title) + ".txt";
        Path target = uniqueLocalPath(LOCAL_REPORT_DIR.resolve(fileName));
        String contentWithoutSignature = ""
                + "Relatorio APS Redes - Monitoramento do Rio Tiete" + System.lineSeparator()
                + "Secretaria: Secretaria de Estado do Meio Ambiente" + System.lineSeparator()
                + "Titulo: " + title + System.lineSeparator()
                + "Canal: " + room + System.lineSeparator()
                + "Autor: " + author + System.lineSeparator()
                + "Workflow: ENVIADO" + System.lineSeparator()
                + "Gerado em: " + Instant.now() + System.lineSeparator()
                + System.lineSeparator()
                + body.strip() + System.lineSeparator();
        String content = contentWithoutSignature
                + System.lineSeparator()
                + "Assinatura SHA-256: " + sha256Hex(contentWithoutSignature) + System.lineSeparator();
        Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return target;
    }

    private void createAndPublishOperationalReport(String title, String body) {
        Path reportPath = createLocalOperationalReport(title, body);
        if (reportPath == null || !networkClient.isConnected() || !hasActiveRoom()) {
            return;
        }
        networkClient.uploadFile(reportPath, null)
                .whenComplete((fileId, exception) -> Platform.runLater(() -> {
                    if (exception != null) {
                        addHistory("RELATORIO: falha ao publicar " + reportPath.getFileName() + ": " + rootMessage(exception));
                    } else {
                        reportWorkflowById.put(fileId, "ENVIADO");
                        addHistory("RELATORIO: " + reportPath.getFileName() + " publicado para leitura.");
                        refreshFiles();
                    }
                }));
    }

    private Path createLocalOperationalReport(String title, String body) {
        try {
            Path reportPath = createLocalReport(title, body, hasActiveRoom() ? activeRoomText() : "Sem canal", profileName());
            addHistory("RELATORIO: " + reportPath.getFileName() + " criado.");
            return reportPath;
        } catch (IOException exception) {
            addHistory("RELATORIO: falha ao criar relatorio local: " + exception.getMessage());
            return null;
        }
    }

    private String completeOperationsReportBody() {
        StringBuilder builder = new StringBuilder();
        long now = System.currentTimeMillis();
        long today = remoteFiles.stream().filter(this::uploadedToday).count();
        long criticalOccurrences = environmentalOccurrences.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).contains("crit"))
                .count();

        appendReportSection(builder, "Identificacao");
        appendReportLine(builder, "Gerado em", Instant.now().toString());
        appendReportLine(builder, "Operador", profileName());
        appendReportLine(builder, "Sessao", adminSession ? "Administrador" : "Membro");
        appendReportLine(builder, "Cargo", currentRole);
        appendReportLine(builder, "Presenca", networkClient.isConnected() ? presenceStatus : "Offline");
        appendReportLine(builder, "Canal atual", hasActiveRoom() ? activeRoomText() : "Sem canal");
        appendReportLine(builder, "Servidor TCP", valueOrDefault(valueOf(hostField), "Nao informado")
                + ":" + valueOrDefault(valueOf(portField), String.valueOf(Ports.DEFAULT_CHAT_PORT)));

        appendReportSection(builder, "Resumo quantitativo");
        appendReportBullet(builder, "Relatorios carregados: " + remoteFiles.size());
        appendReportBullet(builder, "Relatorios de hoje: " + today);
        appendReportBullet(builder, "Ocorrencias ambientais: " + environmentalOccurrences.size());
        appendReportBullet(builder, "Ocorrencias criticas: " + criticalOccurrences);
        appendReportBullet(builder, "Alertas criticos: " + criticalAlerts.size());
        appendReportBullet(builder, "Usuarios no painel: " + connectedUsers.size());
        appendReportBullet(builder, "Eventos de auditoria: " + auditEvents.size());
        appendReportBullet(builder, "Itens de historico: " + connectionHistory.size());
        appendReportBullet(builder, "Notificacoes operacionais: " + notifications.size());

        appendReportSection(builder, "Tempo online / trabalhando");
        appendReportBullet(builder, "Aplicacao aberta ha: " + formatDurationMillis(now - clientStartedAt));
        appendReportBullet(builder, "Sessao conectada ha: " + (networkClient.isConnected() && currentSessionStartedAt > 0
                ? formatDurationMillis(now - currentSessionStartedAt)
                : "offline"));
        List<UserProfile> users = uniqueOperationUsers();
        if (users.isEmpty()) {
            appendReportBullet(builder, "Nenhum usuario online recebido do servidor.");
        } else {
            for (UserProfile user : users) {
                builder.append("- ")
                        .append(valueOrDefault(user.getDisplayName(), "Usuario"))
                        .append(" | usuario: ").append(valueOrDefault(user.getUsername(), "sem usuario"))
                        .append(" | cargo: ").append(valueOrDefault(user.getRole(), "Nao informado"))
                        .append(" | presenca: ").append(valueOrDefault(user.getPresence(), "Nao informada"))
                        .append(" | canal: ").append(valueOrDefault(user.getRoom(), "Sem canal"))
                        .append(" | desde: ").append(formatEpochTime(user.getJoinedAt()))
                        .append(" | tempo on: ").append(user.getJoinedAt() > 0L
                                ? formatDurationMillis(now - user.getJoinedAt())
                                : "Nao informado")
                        .append(System.lineSeparator());
            }
        }

        appendReportSection(builder, "Mapa por trecho");
        appendReportBullet(builder, "Salesopolis: " + countOccurrencesFor("Salesopolis"));
        appendReportBullet(builder, "Mogi: " + countOccurrencesFor("Mogi"));
        appendReportBullet(builder, "Guarulhos: " + countOccurrencesFor("Guarulhos"));
        appendReportBullet(builder, "Sao Paulo: " + countOccurrencesFor("Sao Paulo"));

        appendReportSection(builder, "Relatorios carregados");
        if (remoteFiles.isEmpty()) {
            appendReportBullet(builder, "Nenhum relatorio carregado no painel.");
        } else {
            for (FileMetadata file : remoteFiles) {
                builder.append("- ")
                        .append(file.getFileName())
                        .append(" | autor: ").append(valueOrDefault(file.getOwner(), "Nao informado"))
                        .append(" | canal: ").append(valueOrDefault(file.getRoom(), "Nao informado"))
                        .append(" | workflow: ").append(reportWorkflowById.getOrDefault(file.getId(), "ENVIADO"))
                        .append(" | tamanho: ").append(formatBytes(file.getSize()))
                        .append(" | enviado em: ").append(formatEpochTime(file.getUploadedAt()))
                        .append(System.lineSeparator());
            }
        }

        appendReportSection(builder, "Ocorrencias ambientais");
        appendReportItems(builder, environmentalOccurrences, "Nenhuma ocorrencia ambiental registrada.");

        appendReportSection(builder, "Alertas criticos");
        appendReportItems(builder, criticalAlerts, "Nenhum alerta critico registrado.");

        appendReportSection(builder, "Auditoria");
        appendReportItems(builder, auditEvents, "Nenhum evento de auditoria registrado.");

        appendReportSection(builder, "Historico");
        appendReportItems(builder, connectionHistory, "Nenhum evento de historico registrado.");

        appendReportSection(builder, "Notificacoes");
        appendReportItems(builder, notifications, "Nenhuma notificacao registrada.");

        appendReportSection(builder, "Chat por canal");
        if (messagesByChannel.isEmpty()) {
            appendReportBullet(builder, "Nenhuma mensagem de chat carregada.");
        } else {
            messagesByChannel.forEach((channel, messages) -> {
                builder.append("Canal: ").append(channelKey(channel)).append(System.lineSeparator());
                if (messages.isEmpty()) {
                    builder.append("  - Sem mensagens.").append(System.lineSeparator());
                    return;
                }
                for (NetworkMessage message : messages) {
                    builder.append("  - [")
                            .append(formatEpochTime(message.getTimestamp()))
                            .append("] ")
                            .append(message.getType())
                            .append(" | ")
                            .append(valueOrDefault(message.getFrom(), "SISTEMA"))
                            .append(": ")
                            .append(reportMessageText(message))
                            .append(System.lineSeparator());
                }
            });
        }

        return builder.toString().strip();
    }

    private void previewLocalReport(String title, Path path) {
        pendingReportPreviewId = "";
        reportPreviewTitleLabel.setText(title);
        try {
            reportPreviewArea.setText(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            reportPreviewArea.setText("Relatorio criado em: " + path.toAbsolutePath()
                    + System.lineSeparator() + "Nao foi possivel abrir a leitura automatica: " + exception.getMessage());
        }
    }

    private List<UserProfile> uniqueOperationUsers() {
        Map<String, UserProfile> users = new LinkedHashMap<>();
        connectedUsers.forEach(user -> putUniqueUser(users, user));
        roleUsers.forEach(user -> putUniqueUser(users, user));
        return new ArrayList<>(users.values());
    }

    private static void putUniqueUser(Map<String, UserProfile> users, UserProfile user) {
        if (user == null) {
            return;
        }
        String key = valueOrDefault(user.getUsername(), user.getDisplayName()).toLowerCase(Locale.ROOT);
        users.putIfAbsent(key, user);
    }

    private static void appendReportSection(StringBuilder builder, String title) {
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        builder.append("== ").append(title).append(" ==").append(System.lineSeparator());
    }

    private static void appendReportLine(StringBuilder builder, String label, String value) {
        builder.append(label).append(": ").append(valueOrDefault(value, "Nao informado")).append(System.lineSeparator());
    }

    private static void appendReportBullet(StringBuilder builder, String value) {
        builder.append("- ").append(value).append(System.lineSeparator());
    }

    private static void appendReportItems(StringBuilder builder, List<String> values, String emptyMessage) {
        if (values.isEmpty()) {
            appendReportBullet(builder, emptyMessage);
            return;
        }
        for (String value : values) {
            appendReportBullet(builder, value);
        }
    }

    private String reportMessageText(NetworkMessage message) {
        if (message.getType() == MessageType.IMAGE) {
            return "[foto webcam] " + valueOrDefault(message.getText(), "Imagem enviada.");
        }
        if (message.getType() == MessageType.FILE_NOTICE) {
            return valueOrDefault(message.getText(), "Arquivo publicado.") + " (" + formatBytes(message.getFileSize()) + ")";
        }
        return valueOrDefault(message.getText(), "Sem texto.");
    }

    private static String formatEpochTime(long epochMillis) {
        if (epochMillis <= 0L) {
            return "Sem horario";
        }
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatDurationMillis(long millis) {
        if (millis <= 0L) {
            return "menos de 1s";
        }
        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86_400L;
        totalSeconds %= 86_400L;
        long hours = totalSeconds / 3_600L;
        totalSeconds %= 3_600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder builder = new StringBuilder();
        if (days > 0L) {
            builder.append(days).append(days == 1L ? " dia " : " dias ");
        }
        if (hours > 0L) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0L) {
            builder.append(minutes).append("min ");
        }
        if (builder.length() == 0) {
            builder.append(seconds).append("s");
        }
        return builder.toString().trim();
    }

    private String occurrenceReportBody(String severity, String stretch, String industry, String type, String description) {
        return "Tipo: Ocorrencia ambiental" + System.lineSeparator()
                + "Gravidade: " + valueOrDefault(severity, "Nao informada") + System.lineSeparator()
                + "Trecho: " + valueOrDefault(stretch, "Nao informado") + System.lineSeparator()
                + "Industria: " + valueOrDefault(industry, "Nao informada") + System.lineSeparator()
                + "Ocorrencia: " + valueOrDefault(type, "Nao informada") + System.lineSeparator()
                + "Descricao: " + valueOrDefault(description, "Sem descricao adicional.");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel", exception);
        }
    }

    private static Path uniqueLocalPath(Path candidate) {
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String fileName = candidate.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        Path parent = candidate.getParent();
        for (int index = 2; index < 1000; index++) {
            Path next = parent.resolve(base + "-" + index + extension);
            if (!Files.exists(next)) {
                return next;
            }
        }
        return parent.resolve(base + "-" + System.currentTimeMillis() + extension);
    }

    private static String safeFileStem(String value) {
        String stem = value == null ? "" : value.trim();
        if (stem.isBlank()) {
            return "relatorio";
        }
        stem = stem.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "-")
                .replace('\t', '_');
        stem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
        if (stem.isBlank()) {
            return "relatorio";
        }
        return stem.substring(0, Math.min(stem.length(), 64));
    }

    private void loadProfile() {
        String suggestedName = System.getProperty("APS_PROFILE_NAME", System.getenv().getOrDefault("APS_PROFILE_NAME", ""));
        if (suggestedName == null || suggestedName.isBlank()) {
            suggestedName = "Inspetor APS";
        }
        profileNameField.setText(preferences.get("profileName", suggestedName.trim()));
        loginNameField.setText(profileNameField.getText());
        adminNameField.setText(profileNameField.getText());
        String savedAvatar = preferences.get("avatarPath", "");
        if (!savedAvatar.isBlank()) {
            avatarPath = Path.of(savedAvatar);
            if (Files.exists(avatarPath)) {
                profileImageView.setImage(new Image(avatarPath.toUri().toString(), 116, 116, true, true));
            }
        }
    }

    private static boolean isStrongPassword(String password) {
        if (password == null || password.length() < 8) {
            return false;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }

    private void fillDefaultPasswords() {
        if (memberPasswordField.getText() == null || memberPasswordField.getText().isBlank()) {
            memberPasswordField.setText(DEFAULT_MEMBER_PASSWORD);
        }
        if (adminPasswordField.getText() == null || adminPasswordField.getText().isBlank()) {
            adminPasswordField.setText(DEFAULT_ADMIN_PASSWORD);
        }
    }

    private void configurePresence() {
        if (presenceBox == null) {
            return;
        }
        presenceBox.setItems(FXCollections.observableArrayList("Online", "Ausente", "Offline"));
        presenceBox.setValue(presenceStatus);
        presenceBox.setCellFactory(list -> presenceCell());
        presenceBox.setButtonCell(presenceCell());
        presenceBox.valueProperty().addListener((observable, previous, selected) -> {
            if (selected == null || selected.isBlank()) {
                return;
            }
            presenceStatus = selected;
            applyPresenceState(networkClient.isConnected(), statusLabel.getText());
            updateDashboard();
            if (networkClient.isConnected()) {
                networkClient.updatePresence(selected)
                        .whenComplete((ignored, exception) -> Platform.runLater(() -> {
                            if (exception != null) {
                                addHistory("PRESENCA: falha ao atualizar status: " + rootMessage(exception));
                            } else {
                                addAuditEvent("PRESENCA", "Status alterado para " + selected);
                            }
                        }));
            }
        });
    }

    private ListCell<String> presenceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item);
                setStyle("-fx-background-color: #111923; -fx-text-fill: #d9e0ea; -fx-font-weight: 800;");
            }
        };
    }

    private void configureTheme() {
        String savedTheme = preferences.get(PREF_THEME, THEME_DARK);
        String selectedTheme = THEME_LIGHT.equals(savedTheme) ? THEME_LIGHT : THEME_DARK;
        if (themeBox != null) {
            themeBox.setItems(FXCollections.observableArrayList(THEME_DARK, THEME_LIGHT));
            themeBox.setValue(selectedTheme);
            themeBox.valueProperty().addListener((observable, previous, selected) -> applyTheme(selected));
        }
        applyTheme(selectedTheme);
    }

    private void applyTheme(String selected) {
        String theme = THEME_LIGHT.equals(selected) ? THEME_LIGHT : THEME_DARK;
        if (rootShell != null) {
            rootShell.getStyleClass().removeAll(THEME_CLASSES);
            rootShell.getStyleClass().add(THEME_LIGHT.equals(theme) ? "theme-light" : "theme-dark");
        }
        preferences.put(PREF_THEME, theme);
        if (themeBox != null && !theme.equals(themeBox.getValue())) {
            themeBox.setValue(theme);
        }
    }

    private void configureDefaultHost() {
        if (hostField == null) {
            return;
        }
        String configured = System.getProperty("APS_HOST", System.getenv().getOrDefault("APS_HOST", "")).trim();
        if (configured.isBlank()) {
            configured = detectLanAddress();
        }
        if (!configured.isBlank()) {
            syncServerFields(configured, valueOrDefault(valueOf(portField), Integer.toString(Ports.DEFAULT_CHAT_PORT)));
        }
    }

    private void syncServerFields(String host, String port) {
        String nextHost = valueOrDefault(host, detectLanAddress());
        String nextPort = valueOrDefault(port, Integer.toString(Ports.DEFAULT_CHAT_PORT));
        if (hostField != null) {
            hostField.setText(nextHost);
        }
        if (portField != null) {
            portField.setText(nextPort);
        }
    }

    private static String detectLanAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            String host = socket.getLocalAddress().getHostAddress();
            return host == null || host.isBlank() || host.startsWith("127.") ? "127.0.0.1" : host;
        } catch (IOException exception) {
            return "127.0.0.1";
        }
    }

    private void syncVisibleAuthPasswords(String memberPassword, String adminPassword) {
        if (authMemberPasswordField != null && memberPassword != null && !memberPassword.isBlank()) {
            authMemberPasswordField.setText(memberPassword);
        }
        if (authAdminPasswordField == null) {
            return;
        }
        if (adminSession && adminPassword != null && !adminPassword.isBlank()) {
            authAdminPasswordField.setText(adminPassword);
        } else if (!adminSession) {
            authAdminPasswordField.clear();
        }
    }

    private static String valueOf(TextInputControl field) {
        if (field == null) {
            return "";
        }
        return field.getText() == null ? "" : field.getText().trim();
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String passwordForSession(boolean asAdmin) {
        PasswordField field = asAdmin ? adminPasswordField : memberPasswordField;
        String typed = field.getText() == null ? "" : field.getText().trim();
        if (!typed.isBlank()) {
            return typed;
        }
        String fallback = asAdmin ? DEFAULT_ADMIN_PASSWORD : DEFAULT_MEMBER_PASSWORD;
        field.setText(fallback);
        return fallback;
    }

    private void saveProfileSilently() {
        if (profileNameField.getText() == null || profileNameField.getText().isBlank()) {
            String loginName = valueOf(loginNameField);
            profileNameField.setText(loginName.isBlank() ? valueOf(adminNameField) : loginName);
        }
        preferences.put("profileName", profileName());
        loginNameField.setText(profileName());
        adminNameField.setText(profileName());
        if (avatarPath != null) {
            preferences.put("avatarPath", avatarPath.toString());
        }
    }

    private void loadHistory() {
        try {
            if (Files.exists(LOCAL_HISTORY_PATH)) {
                connectionHistory.setAll(Files.readAllLines(LOCAL_HISTORY_PATH).stream()
                        .filter(value -> !value.isBlank())
                        .limit(HISTORY_LIMIT)
                        .toList());
            }
        } catch (IOException ignored) {
            connectionHistory.clear();
        }
    }

    private void addHistory(String text) {
        String line = TIME_FORMAT.format(Instant.now()) + " | " + text;
        connectionHistory.add(0, line);
        while (connectionHistory.size() > HISTORY_LIMIT) {
            connectionHistory.remove(connectionHistory.size() - 1);
        }
        saveHistoryToFile();
    }

    private void saveHistoryToFile() {
        try {
            Path parent = LOCAL_HISTORY_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(LOCAL_HISTORY_PATH, connectionHistory.stream().limit(STORED_HISTORY_LIMIT).toList());
        } catch (IOException ignored) {
            // Historico local e opcional; falha aqui nao pode impedir o cliente de abrir.
        }
    }

    private void refreshFiles() {
        if (!networkClient.isConnected()) {
            remoteFiles.clear();
            clearReportPreview();
            updateReportScopeLabel();
            return;
        }

        if (adminSession) {
            updateReportScopeLabel();
            refreshAllFilesForAdmin();
            return;
        }

        if (!hasActiveRoom()) {
            remoteFiles.clear();
            clearReportPreview();
            updateReportScopeLabel();
            return;
        }

        updateReportScopeLabel();
        networkClient.listRemoteFilesForRoom(activeRoomText())
                .whenComplete((files, exception) -> Platform.runLater(() -> {
                    if (exception != null) {
                        showError("Arquivos", rootMessage(exception));
                    } else {
                        setRemoteFiles(files);
                    }
                }));
    }

    private void refreshAllFilesForAdmin() {
        List<String> adminChannels = channels.isEmpty() ? DEFAULT_CHANNELS : List.copyOf(channels);
        List<CompletableFuture<List<FileMetadata>>> requests = adminChannels.stream()
                .map(room -> networkClient.listRemoteFilesForRoom(room).exceptionally(exception -> List.of()))
                .toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<FileMetadata> all = new ArrayList<>();
                    for (CompletableFuture<List<FileMetadata>> request : requests) {
                        all.addAll(request.join());
                    }
                    all.sort((a, b) -> Long.compare(b.getUploadedAt(), a.getUploadedAt()));
                    return all;
                })
                .whenComplete((files, exception) -> Platform.runLater(() -> {
                    if (exception != null) {
                        showError("Arquivos", rootMessage(exception));
                    } else {
                        setRemoteFiles(files);
                    }
                }));
    }

    private void setRemoteFiles(List<FileMetadata> files) {
        String selectedId = Optional.ofNullable(fileList.getSelectionModel().getSelectedItem())
                .map(FileMetadata::getId)
                .orElse("");
        files.forEach(file -> reportWorkflowById.putIfAbsent(file.getId(), "ENVIADO"));
        remoteFiles.setAll(files);
        FileMetadata nextSelection = remoteFiles.stream()
                .filter(file -> file.getId().equals(selectedId))
                .findFirst()
                .orElse(remoteFiles.isEmpty() ? null : remoteFiles.get(0));
        if (nextSelection == null) {
            fileList.getSelectionModel().clearSelection();
            clearReportPreview();
        } else {
            fileList.getSelectionModel().select(nextSelection);
            if (reportsPage.isVisible() && valueOf(reportPreviewArea).isBlank()) {
                previewReport(nextSelection);
            }
        }
        updateActionAvailability();
        updateReportScopeLabel();
        updateDashboard();
    }

    private void updateReportScopeLabel() {
        if (reportListTitleLabel == null) {
            return;
        }
        if (!networkClient.isConnected()) {
            reportListTitleLabel.setText("Relatorios");
        } else if (adminSession) {
            reportListTitleLabel.setText("Relatorios de todos os canais");
        } else if (hasActiveRoom()) {
            reportListTitleLabel.setText("Relatorios do canal: " + activeRoomText());
        } else {
            reportListTitleLabel.setText("Relatorios do canal");
        }
    }

    private void clearReportPreview() {
        if (reportPreviewTitleLabel != null) {
            reportPreviewTitleLabel.setText("Leitura do relatorio");
        }
        pendingReportPreviewId = "";
        if (reportPreviewArea != null) {
            reportPreviewArea.setText("");
        }
    }

    private static boolean canPreviewInline(FileMetadata metadata) {
        if (metadata.getSize() > MAX_INLINE_REPORT_BYTES) {
            return false;
        }
        String name = metadata.getFileName().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".log") || name.endsWith(".md");
    }

    private void handleMessage(NetworkMessage message) {
        if (message.getType() == MessageType.LOGIN_ACCEPTED) {
            boolean firstLoginAccepted = awaitingLogin;
            if (firstLoginAccepted) {
                awaitingLogin = false;
                currentSessionStartedAt = System.currentTimeMillis();
                setConnected(true);
                addHistory("Conectado ao servidor como " + profileName() + (adminSession ? " (admin)" : " (membro)"));
                loginStatusLabel.setText(adminSession ? "Sessao admin ativa." : "Sessao membro ativa.");
                addLocalSystem("Conectado ao servidor. O painel operacional sera aberto com um canal padrao.");
            }
            updateChannels(message);
            if (firstLoginAccepted) {
                autoJoinFirstChannelForDashboard();
            }
            appendHistoryMessage(message);
            return;
        }
        if (message.getType() == MessageType.AUTH_UPDATE) {
            authStatusLabel.setText(message.getText().isBlank() ? "Senhas atualizadas." : message.getText());
            addHistory("SENHAS: " + authStatusLabel.getText());
            return;
        }
        if (message.getType() == MessageType.USER_LIST) {
            String scope = message.getAttributes().getOrDefault("scope", "room");
            if ("admin".equals(scope)) {
                roleUsers.setAll(message.getUsers());
                if (adminSession && !hasActiveRoom()) {
                    connectedUsers.setAll(message.getUsers());
                    updateSessionCount();
                }
                return;
            }
            if (sameChannel(messageChannel(message), activeRoomText())) {
                connectedUsers.setAll(message.getUsers());
                updateSessionCount();
                chatList.refresh();
            }
            return;
        }
        if (message.getType() == MessageType.ERROR) {
            addHistory("ERRO: " + message.getText());
            if (message.getText().toLowerCase().contains("senha")) {
                awaitingLogin = false;
                networkClient.disconnect();
                setConnected(false);
                loginStatusLabel.setText("Senha invalida.");
            }
            showError("Rede", message.getText());
            return;
        }
        String channel = messageChannel(message);
        if (isHistoryOnly(message)) {
            appendHistoryMessage(message);
        } else {
            appendChannelMessage(channel, message);
        }
        if (message.getType() == MessageType.CHAT || message.getType() == MessageType.IMAGE) {
            chatList.refresh();
        }
        if (message.getType() == MessageType.CHAT && mentionsSelf(message) && !isSelfMessage(message)) {
            addLocalAlert("MENCAO: " + message.getFrom() + " mencionou voce em " + channelKey(channel) + ".");
            addProblemNotification("Mencao no chat", message.getFrom() + " chamou voce em " + channelKey(channel) + ".");
        }
        if (message.getType() == MessageType.ALERT && "true".equalsIgnoreCase(message.getAttributes().get("critical"))) {
            handleCriticalAlert(message);
        }
        if (message.getType() == MessageType.SYSTEM && "true".equalsIgnoreCase(message.getAttributes().get("alertAck"))) {
            criticalAlerts.add(0, TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())) + " | " + message.getText());
            updateAnalytics();
        }
        if (message.getType() == MessageType.FILE_NOTICE) {
            if (adminSession) {
                refreshFiles();
            } else if (sameChannel(channel, activeRoomText())) {
                refreshFiles();
            }
        }
    }

    private void updateChannels(NetworkMessage message) {
        String session = message.getAttributes().get("session");
        if (session != null && !session.isBlank()) {
            adminSession = "admin".equalsIgnoreCase(session);
        }
        String role = message.getAttributes().get("role");
        if (role != null && !role.isBlank()) {
            currentRole = role;
        }
        if (adminSession) {
            authMemberPasswordField.clear();
            authAdminPasswordField.clear();
        } else {
            authMemberPasswordField.clear();
            authAdminPasswordField.clear();
        }
        String raw = message.getAttributes().get("channels");
        if (raw != null) {
            List<String> parsedChannels = Arrays.stream(raw.split("\\|"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            channels.setAll(parsedChannels.isEmpty()
                    ? (adminSession ? DEFAULT_CHANNELS : List.of("Equipe Alfa"))
                    : parsedChannels);
        }
        String room = message.getAttributes().get("room");
        if (room != null && !room.isBlank()) {
            activeRoom = room;
            networkClient.setConfirmedRoom(room);
            currentRoomLabel.setText(room);
            renderChannel(room);
            updateActionAvailability();
        } else if (!activeRoomText().isBlank() && !channels.contains(activeRoomText())) {
            activeRoom = "";
            networkClient.setConfirmedRoom("");
            currentRoomLabel.setText("Sem canal");
            connectedUsers.clear();
            remoteFiles.clear();
            clearReportPreview();
            renderChannel("");
            updateSessionCount();
        }
        configureNavigationForSession();
        updateReportScopeLabel();
        updateDashboard();
    }

    private void autoJoinFirstChannelForDashboard() {
        if (!networkClient.isConnected() || hasActiveRoom() || channels.isEmpty() || autoJoiningChannel) {
            if (networkClient.isConnected()) {
                showPage(sessionPage, sessionNavButton);
            }
            return;
        }
        String channel = channels.get(0);
        autoJoiningChannel = true;
        activeRoom = channel;
        currentRoomLabel.setText(channel);
        channelsList.getSelectionModel().select(channel);
        renderChannel(channel);
        updateSessionCount();
        networkClient.joinRoom(channel).whenComplete((ignored, exception) -> Platform.runLater(() -> {
            autoJoiningChannel = false;
            if (exception != null) {
                activeRoom = "";
                networkClient.setConfirmedRoom("");
                currentRoomLabel.setText("Sem canal");
                renderChannel("");
                addLocalAlert("Nao foi possivel entrar automaticamente no canal: " + rootMessage(exception));
            } else {
                activeRoom = channel;
                currentRoomLabel.setText(channel);
                renderChannel(channel);
                addHistory("Canal padrao ativo: " + channel);
                refreshFiles();
            }
            updateActionAvailability();
            updateDashboard();
            showPage(sessionPage, sessionNavButton);
        }));
    }

    private void startMulticastListener() {
        stopMulticastListener();
        multicastListener = new MulticastAlertListener(alert -> Platform.runLater(() -> {
            if (!alert.toLowerCase().contains("multicast indisponivel")) {
                addLocalAlert("ALERTA MULTICAST: " + alert);
                addProblemNotification("Alerta multicast", alert);
            }
        }));
        multicastListener.start();
    }

    private void stopMulticastListener() {
        if (multicastListener != null) {
            multicastListener.close();
            multicastListener = null;
        }
    }

    private void startAutomaticProblems() {
        if (automaticProblemTimeline != null) {
            automaticProblemTimeline.playFromStart();
        }
    }

    private void stopAutomaticProblems() {
        if (automaticProblemTimeline != null) {
            automaticProblemTimeline.stop();
        }
    }

    private void updateSessionCount() {
        int count = connectedUsers.size();
        if (!networkClient.isConnected()) {
            sessionCountLabel.setText("Entre em um canal para ver a sessao");
            updateDashboard();
            return;
        }
        if (!hasActiveRoom()) {
            if (adminSession && count > 0) {
                sessionCountLabel.setText(count + (count == 1 ? " perfil online" : " perfis online") + " no painel admin");
                updateDashboard();
                return;
            }
            sessionCountLabel.setText("Entre em um canal para ver a sessao");
            updateDashboard();
            return;
        }
        sessionCountLabel.setText(count + (count == 1 ? " perfil online" : " perfis online") + " em " + activeRoomText());
        updateDashboard();
    }

    private void updateDashboard() {
        if (dashboardStatusLabel == null) {
            return;
        }
        boolean connected = networkClient.isConnected();
        dashboardStatusLabel.setText(connected ? valueOrDefault(statusLabel.getText(), "Online") : "Offline");
        dashboardRoomLabel.setText(hasActiveRoom() ? activeRoomText() : "Sem canal");
        dashboardRoleLabel.setText(adminSession ? "Administrador" : valueOrDefault(currentRole, "Membro"));
        int reports = remoteFiles == null ? 0 : remoteFiles.size();
        dashboardReportsLabel.setText(reports + (reports == 1 ? " arquivo" : " arquivos"));
        if (dashboardTodayReportsLabel != null) {
            long today = remoteFiles == null ? 0 : remoteFiles.stream().filter(this::uploadedToday).count();
            dashboardTodayReportsLabel.setText(today + (today == 1 ? " relatorio hoje" : " relatorios hoje"));
        }
        if (dashboardUsersLabel != null) {
            int users = connectedUsers == null ? 0 : connectedUsers.size();
            dashboardUsersLabel.setText(users + (users == 1 ? " perfil online" : " perfis online"));
        }
        updateAnalytics();
    }

    private void setConnected(boolean connected) {
        hostField.setDisable(connected);
        portField.setDisable(connected);
        loginNameField.setDisable(connected);
        adminNameField.setDisable(connected);
        memberPasswordField.setDisable(connected);
        adminPasswordField.setDisable(connected);
        memberLoginButton.setDisable(connected);
        adminLoginButton.setDisable(connected);
        adminAccessButton.setDisable(connected);
        profileNameField.setDisable(false);
        chooseProfileImageButton.setDisable(false);
        saveProfileButton.setDisable(false);
        presenceBox.setDisable(!connected);
        channelsList.setDisable(!connected);
        joinChannelButton.setDisable(!connected);
        if (connected) {
            sidebarCollapsed = false;
            startMulticastListener();
            startAutomaticProblems();
        } else {
            stopAutomaticProblems();
        }
        configureNavigationForSession();
        updateActionAvailability();
        updateShellChrome(connected);
        applyPresenceState(connected, connected ? presenceStatus : "Offline");
    }

    private void updateActionAvailability() {
        boolean connected = networkClient.isConnected();
        boolean channelReady = connected && hasActiveRoom();
        boolean fileListReady = connected && (hasActiveRoom() || adminSession);
        boolean hasSelectedReport = fileList.getSelectionModel().getSelectedItem() != null;
        sendButton.setDisable(!channelReady);
        webcamButton.setDisable(!channelReady);
        createReportButton.setDisable(!channelReady);
        saveDraftButton.setDisable(!connected);
        reportTitleField.setDisable(!channelReady);
        reportBodyArea.setDisable(!channelReady);
        uploadButton.setDisable(!channelReady);
        downloadButton.setDisable(!fileListReady || !hasSelectedReport);
        validateReportButton.setDisable(!connected || !adminSession || !hasSelectedReport);
        refreshFilesButton.setDisable(!fileListReady);
        sendCriticalAlertButton.setDisable(!connected);
        messageField.setDisable(!channelReady);
        UserProfile selectedRoleUser = roleUsersList.getSelectionModel().getSelectedItem();
        boolean roleFormReady = connected && adminSession && selectedRoleUser != null && !selectedRoleUser.isAdmin();
        saveRoleButton.setDisable(!roleFormReady);
        roleNameField.setDisable(!roleFormReady);
        List.of(roleAlfaBox, roleBetaBox, roleGamaBox, roleCentralBox)
                .forEach(box -> box.setDisable(!roleFormReady));
        boolean passwordAdminReady = connected && adminSession;
        saveAuthPasswordsButton.setDisable(!passwordAdminReady);
        authMemberPasswordField.setDisable(!passwordAdminReady);
        authAdminPasswordField.setDisable(!passwordAdminReady);
        authAdminPasswordField.setVisible(adminSession);
        authAdminPasswordField.setManaged(adminSession);
        if (!adminSession) {
            authStatusLabel.setText("Apenas administradores alteram senhas do sistema.");
        }
    }

    private void updateConnectionState(boolean online, String detail) {
        applyPresenceState(online, detail);
        updateDashboard();
    }

    private void applyPresenceState(boolean connected, String detail) {
        statusDot.getStyleClass().removeAll("status-online", "status-offline", "status-sending", "status-away");
        if (!connected) {
            statusDot.getStyleClass().add("status-offline");
            statusLabel.setText("Offline");
            return;
        }
        String visible = valueOrDefault(detail, presenceStatus);
        if ("Online".equalsIgnoreCase(visible) || "Reconectado".equalsIgnoreCase(visible)) {
            visible = presenceStatus;
        }
        if ("Ausente".equalsIgnoreCase(visible)) {
            statusDot.getStyleClass().add("status-away");
        } else if ("Offline".equalsIgnoreCase(visible)) {
            statusDot.getStyleClass().add("status-offline");
        } else if ("A enviar".equalsIgnoreCase(visible) || "Validando".equalsIgnoreCase(visible) || visible.startsWith("Reconectando")) {
            statusDot.getStyleClass().add("status-sending");
        } else {
            statusDot.getStyleClass().add("status-online");
        }
        statusLabel.setText(visible);
    }

    private void setSendingState(String detail) {
        statusDot.getStyleClass().removeAll("status-online", "status-offline", "status-sending", "status-away");
        statusDot.getStyleClass().add("status-sending");
        statusLabel.setText(detail);
    }

    private void showPage(VBox page, Button navButton) {
        List.of(loginPage, adminLoginPage, chatPage, sessionPage, reportsPage, occurrencesPage, analyticsPage, rolesPage, adminPage, auditPage, mapPage, historyPage, profilePage).forEach(candidate -> {
            candidate.setVisible(candidate == page);
            candidate.setManaged(candidate == page);
        });
        List.of(loginNavButton, chatNavButton, sessionNavButton, reportsNavButton, occurrencesNavButton, analyticsNavButton, rolesNavButton, adminNavButton, auditNavButton, mapNavButton, historyNavButton, profileNavButton)
                .forEach(button -> button.getStyleClass().remove("active"));
        if (!navButton.getStyleClass().contains("active")) {
            navButton.getStyleClass().add("active");
        }
    }

    private void configureNavigationForSession() {
        boolean connected = networkClient.isConnected();
        updateShellChrome(connected);
        setNavVisible(loginNavButton, !connected);
        setNavVisible(chatNavButton, connected);
        setNavVisible(sessionNavButton, connected);
        setNavVisible(reportsNavButton, true);
        setNavVisible(occurrencesNavButton, connected);
        setNavVisible(analyticsNavButton, connected);
        setNavVisible(rolesNavButton, connected && adminSession);
        setNavVisible(adminNavButton, connected && adminSession);
        setNavVisible(auditNavButton, connected && adminSession);
        setNavVisible(mapNavButton, connected);
        setNavVisible(historyNavButton, connected);
        setNavVisible(profileNavButton, true);

        if (!connected) {
            showPage(loginPage, loginNavButton);
        } else if (!currentPageIsVisibleForSession()) {
            showPage(sessionPage, sessionNavButton);
        }
    }

    private boolean currentPageIsVisibleForSession() {
        Map<VBox, Button> pages = Map.ofEntries(
                Map.entry(loginPage, loginNavButton),
                Map.entry(adminLoginPage, loginNavButton),
                Map.entry(chatPage, chatNavButton),
                Map.entry(sessionPage, sessionNavButton),
                Map.entry(reportsPage, reportsNavButton),
                Map.entry(occurrencesPage, occurrencesNavButton),
                Map.entry(analyticsPage, analyticsNavButton),
                Map.entry(rolesPage, rolesNavButton),
                Map.entry(adminPage, adminNavButton),
                Map.entry(auditPage, auditNavButton),
                Map.entry(mapPage, mapNavButton),
                Map.entry(historyPage, historyNavButton),
                Map.entry(profilePage, profileNavButton)
        );
        return pages.entrySet().stream()
                .filter(entry -> entry.getKey().isVisible())
                .findFirst()
                .map(entry -> entry.getValue().isVisible())
                .orElse(false);
    }

    private static void setNavVisible(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private void updateShellChrome(boolean visible) {
        if (topBar != null) {
            topBar.setVisible(visible);
            topBar.setManaged(visible);
        }
        if (leftPanel != null) {
            boolean showSidebar = visible && !sidebarCollapsed;
            leftPanel.setVisible(showSidebar);
            leftPanel.setManaged(showSidebar);
            if (leftScroll != null) {
                leftScroll.setVisible(showSidebar);
                leftScroll.setManaged(showSidebar);
            }
        }
        if (sidebarToggleButton != null) {
            sidebarToggleButton.setVisible(visible);
            sidebarToggleButton.setManaged(visible);
            sidebarToggleButton.setText(sidebarCollapsed ? "Abrir menu" : "Fechar menu");
        }
    }

    private void configureResponsiveLayout() {
        if (rootShell == null) {
            return;
        }
        rootShell.widthProperty().addListener((observable, previous, width) -> applyResponsiveLayout(width.doubleValue()));
        applyResponsiveLayout(rootShell.getWidth());
    }

    private void applyResponsiveLayout(double width) {
        boolean compact = width > 0 && width < 920;
        boolean narrowLogin = width > 0 && width < 1030;
        if (leftPanel != null) {
            leftPanel.setPrefWidth(compact ? 214 : 258);
            leftPanel.setMinWidth(compact ? 196 : 232);
            leftPanel.setMaxWidth(compact ? 224 : 286);
        }
        if (leftScroll != null) {
            leftScroll.setPrefWidth(compact ? 214 : 258);
            leftScroll.setMinWidth(compact ? 196 : 232);
            leftScroll.setMaxWidth(compact ? 224 : 286);
        }
        if (topBar != null) {
            topBar.setSpacing(compact ? 8 : 16);
        }
        if (searchField != null) {
            boolean showSearch = width <= 0 || width >= 1100;
            searchField.setVisible(showSearch);
            searchField.setManaged(showSearch);
        }
        setLoginShellWidth(loginShell, width, narrowLogin);
        setLoginShellWidth(adminLoginShell, width, narrowLogin);
        setHeroVisible(loginHero, !narrowLogin);
        setHeroVisible(adminHero, !narrowLogin);
    }

    private void setLoginShellWidth(HBox shell, double rootWidth, boolean narrow) {
        if (shell == null || rootWidth <= 0) {
            return;
        }
        double width = Math.max(320, Math.min(980, rootWidth - 56));
        shell.setPrefWidth(narrow ? width : 980);
        shell.setMaxWidth(narrow ? width : 980);
    }

    private static void setHeroVisible(VBox hero, boolean visible) {
        if (hero == null) {
            return;
        }
        hero.setVisible(visible);
        hero.setManaged(visible);
    }

    private void showSkeleton(String title, boolean progressVisible) {
        skeletonTitle.setText(title);
        skeletonOverlay.setVisible(true);
        skeletonOverlay.setManaged(true);
        shimmerTimeline.playFromStart();
        transferProgress.setVisible(progressVisible);
        transferProgress.setManaged(progressVisible);
    }

    private void hideSkeleton() {
        skeletonOverlay.setVisible(false);
        skeletonOverlay.setManaged(false);
        shimmerTimeline.stop();
    }

    private void setTransferProgress(long transferred, long total) {
        if (total <= 0) {
            transferProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            transferLabel.setText("Transferencia ativa");
            return;
        }
        transferProgress.setProgress(Math.min(1.0, (double) transferred / total));
        transferLabel.setText(formatBytes(transferred) + " / " + formatBytes(total));
    }

    private void clearTransferProgress() {
        transferProgress.setProgress(0);
        transferProgress.setVisible(false);
        transferProgress.setManaged(false);
        transferLabel.setText("Nenhuma transferencia ativa");
    }

    private void addLocalSystem(String text) {
        addHistory("SISTEMA: " + text);
    }

    private void addLocalAlert(String text) {
        addHistory("ALERTA: " + text);
    }

    private void appendHistoryMessage(NetworkMessage message) {
        addHistory(historyPrefix(message) + ": " + message.getText());
    }

    private void addAuditEvent(String type, String text) {
        String line = TIME_FORMAT.format(Instant.now()) + " | " + type + " | " + text;
        auditEvents.add(0, line);
        while (auditEvents.size() > HISTORY_LIMIT) {
            auditEvents.remove(auditEvents.size() - 1);
        }
        addHistory(type + ": " + text);
    }

    private void addProblemNotification(String title, String message) {
        String line = TIME_FORMAT.format(Instant.now()) + " | " + title + " | " + message;
        notifications.add(0, line);
        while (notifications.size() > 50) {
            notifications.remove(notifications.size() - 1);
        }
        updateNotificationCount();
        showNotificationToast(title, message);
    }

    private void updateNotificationCount() {
        if (notificationCountLabel == null) {
            return;
        }
        int count = notifications.size();
        notificationCountLabel.setText(Integer.toString(Math.min(count, 99)));
        notificationCountLabel.setVisible(count > 0);
        notificationCountLabel.setManaged(count > 0);
    }

    private void showNotificationToast(String title, String message) {
        if (notificationToast == null) {
            return;
        }
        notificationToastTitle.setText(title);
        notificationToastMessage.setText(message);
        notificationToast.setVisible(true);
        notificationToast.setManaged(false);
        notificationTimeline.stop();
        notificationTimeline.playFromStart();
    }

    private void hideNotificationToast() {
        if (notificationToast != null) {
            notificationToast.setVisible(false);
            notificationToast.setManaged(false);
        }
    }

    private void configureAnalyticsCharts() {
        if (analyticsBarChartHost == null || analyticsPieChartHost == null || analyticsBarChart != null) {
            return;
        }
        CategoryAxis categoryAxis = new CategoryAxis();
        categoryAxis.setLabel("Indicador");
        categoryAxis.setTickLabelGap(8);
        analyticsNumberAxis = new NumberAxis(0, 5, 1);
        analyticsNumberAxis.setLabel("Total");
        analyticsNumberAxis.setForceZeroInRange(true);
        analyticsNumberAxis.setMinorTickVisible(false);
        analyticsBarChart = new BarChart<>(categoryAxis, analyticsNumberAxis);
        analyticsBarChart.setAnimated(false);
        analyticsBarChart.setLegendVisible(false);
        analyticsBarChart.setTitle("Volumes por indicador");
        analyticsBarChart.setCategoryGap(24);
        analyticsBarChart.setBarGap(2);
        analyticsBarChart.setMinHeight(300);
        analyticsBarChart.getStyleClass().add("analytics-chart");
        VBox.setVgrow(analyticsBarChart, Priority.ALWAYS);
        analyticsBarChartHost.getChildren().setAll(analyticsBarChart);

        analyticsPieChart = new PieChart();
        analyticsPieChart.setAnimated(false);
        analyticsPieChart.setLegendVisible(false);
        analyticsPieChart.setLabelsVisible(false);
        analyticsPieChart.setClockwise(true);
        analyticsPieChart.setStartAngle(90);
        analyticsPieChart.setTitle("");
        analyticsPieChart.setMinHeight(250);
        analyticsPieChart.setPrefHeight(260);
        analyticsPieChart.getStyleClass().add("analytics-chart");
        VBox.setVgrow(analyticsPieChart, Priority.ALWAYS);
        analyticsPieLegend = new VBox(8);
        analyticsPieLegend.getStyleClass().add("pie-legend");
        analyticsPieChartHost.getChildren().setAll(analyticsPieChart, analyticsPieLegend);
    }

    private void handleCriticalAlert(NetworkMessage message) {
        String alertId = message.getAttributes().getOrDefault("alertId", "");
        String line = TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())) + " | " + message.getText();
        if (criticalAlerts.stream().noneMatch(value -> value.contains(alertId) || value.equals(line))) {
            criticalAlerts.add(0, alertId.isBlank() ? line : line + " | " + alertId);
        }
        addAuditEvent("ALERTA_CRITICO", message.getText());
        addProblemNotification("Alerta critico recebido", message.getText());
        createLocalOperationalReport("Alerta critico recebido",
                "Tipo: Alerta critico recebido" + System.lineSeparator()
                        + "Origem: " + message.getFrom() + System.lineSeparator()
                        + "Canal: " + messageChannel(message) + System.lineSeparator()
                        + "Descricao: " + message.getText());
        updateAnalytics();
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Alerta critico");
        alert.setHeaderText("Confirmar recebimento");
        alert.setContentText(message.getText());
        alert.showAndWait();
        if (!alertId.isBlank() && networkClient.isConnected()) {
            networkClient.confirmCriticalAlert(alertId).exceptionally(exception -> null);
        }
    }

    private boolean mentionsSelf(NetworkMessage message) {
        return mentionsSelf(message == null ? "" : message.getText());
    }

    private boolean mentionsSelf(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!normalized.contains("@")) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(profileName());
        candidates.add(networkClient.getDisplayName());
        candidates.add(networkClient.getUsername());
        return candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> mentionKeys(value).stream())
                .distinct()
                .anyMatch(key -> normalized.contains("@" + key));
    }

    private static List<String> mentionKeys(String value) {
        String clean = value.toLowerCase(Locale.ROOT).trim();
        String compact = clean.replaceAll("[^a-z0-9]", "");
        String first = clean.split("\\s+")[0].replaceAll("[^a-z0-9]", "");
        List<String> keys = new ArrayList<>();
        if (!compact.isBlank()) {
            keys.add(compact);
        }
        if (!first.isBlank()) {
            keys.add(first);
        }
        return keys;
    }

    private boolean isSelfMessage(NetworkMessage message) {
        String messageClientId = message.getAttributes().getOrDefault("clientId", "");
        if (!messageClientId.isBlank()) {
            return messageClientId.equals(networkClient.getClientId());
        }
        return message.getAttributes().getOrDefault("username", "").equalsIgnoreCase(networkClient.getUsername())
                || message.getFrom().equalsIgnoreCase(networkClient.getDisplayName());
    }

    private void updateAnalytics() {
        if (analyticsTodayReportsLabel == null) {
            return;
        }
        long today = remoteFiles == null ? 0 : remoteFiles.stream().filter(this::uploadedToday).count();
        int occurrences = environmentalOccurrences.size();
        long criticalOccurrences = environmentalOccurrences.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).contains("critica") || value.toLowerCase(Locale.ROOT).contains("alta"))
                .count();
        int alerts = criticalAlerts.size();
        int users = connectedUsers == null ? 0 : connectedUsers.size();
        analyticsTodayReportsLabel.setText(Long.toString(today));
        analyticsOccurrencesLabel.setText(Integer.toString(occurrences));
        analyticsCriticalAlertsLabel.setText(Integer.toString(alerts));
        analyticsUsersLabel.setText(Integer.toString(users));
        updateAnalyticsCharts(today, occurrences, criticalOccurrences, alerts, users);
        analyticsSummaryLabel.setText("Hoje: " + today + " relatorios, " + occurrences
                + " ocorrencias (" + criticalOccurrences + " criticas), " + alerts
                + " alertas criticos e " + users + " perfis no painel.");
    }

    private void updateAnalyticsCharts(long today, int occurrences, long criticalOccurrences, int alerts, int users) {
        configureAnalyticsCharts();
        if (analyticsBarChart == null || analyticsPieChart == null) {
            return;
        }
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.getData().add(new XYChart.Data<>("Relatorios", today));
        series.getData().add(new XYChart.Data<>("Ocorrencias", occurrences));
        series.getData().add(new XYChart.Data<>("Criticas", criticalOccurrences));
        series.getData().add(new XYChart.Data<>("Alertas", alerts));
        series.getData().add(new XYChart.Data<>("Equipe", users));
        analyticsBarChart.getData().setAll(series);
        if (analyticsNumberAxis != null) {
            double max = Math.max(5, Math.max(Math.max(today, occurrences), Math.max(Math.max(criticalOccurrences, alerts), users)) + 1);
            analyticsNumberAxis.setUpperBound(max);
            analyticsNumberAxis.setTickUnit(1);
        }
        Platform.runLater(() -> styleAnalyticsBars(series));

        ObservableList<PieChart.Data> pieData = buildPieData(today, occurrences, alerts);
        analyticsPieChart.setData(pieData);
        updatePieLegend(pieData);
        Platform.runLater(() -> {
            styleAnalyticsPie(pieData);
            updatePieLegend(pieData);
        });
    }

    private ObservableList<PieChart.Data> buildPieData(long today, int occurrences, int alerts) {
        long total = Math.max(0, today) + Math.max(0, occurrences) + Math.max(0, alerts);
        if (total == 0) {
            return FXCollections.observableArrayList(new PieChart.Data("Sem dados", 1));
        }

        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
        addPieSlice(data, "Relatorios", today, total);
        addPieSlice(data, "Ocorrencias", occurrences, total);
        addPieSlice(data, "Alertas", alerts, total);
        return data;
    }

    private void addPieSlice(ObservableList<PieChart.Data> data, String label, long value, long total) {
        if (value <= 0) {
            return;
        }
        long percent = Math.round((value * 100.0) / total);
        data.add(new PieChart.Data(label + " " + value + " (" + percent + "%)", value));
    }

    private void styleAnalyticsBars(XYChart.Series<String, Number> series) {
        for (int index = 0; index < series.getData().size(); index++) {
            XYChart.Data<String, Number> data = series.getData().get(index);
            Node node = data.getNode();
            if (node == null) {
                continue;
            }
            node.getStyleClass().removeAll(ANALYTICS_BAR_CLASSES);
            node.getStyleClass().add(ANALYTICS_BAR_CLASSES.get(index % ANALYTICS_BAR_CLASSES.size()));
            installAnalyticsTooltip(node, data.getXValue() + ": " + formatWholeNumber(data.getYValue()));
            if (node instanceof StackPane bar) {
                bar.getChildren().removeIf(child -> child.getStyleClass().contains(BAR_VALUE_LABEL_CLASS));
                Label valueLabel = new Label(formatWholeNumber(data.getYValue()));
                valueLabel.getStyleClass().add(BAR_VALUE_LABEL_CLASS);
                valueLabel.setMouseTransparent(true);
                StackPane.setAlignment(valueLabel, Pos.TOP_CENTER);
                bar.getChildren().add(valueLabel);
            }
        }
    }

    private void styleAnalyticsPie(ObservableList<PieChart.Data> pieData) {
        for (PieChart.Data data : pieData) {
            Node node = data.getNode();
            if (node == null) {
                continue;
            }
            node.getStyleClass().removeAll(ANALYTICS_PIE_CLASSES);
            node.getStyleClass().remove("pie-empty");
            if (data.getName().startsWith("Relatorios")) {
                node.getStyleClass().add("pie-reports");
            } else if (data.getName().startsWith("Ocorrencias")) {
                node.getStyleClass().add("pie-occurrences");
            } else if (data.getName().startsWith("Alertas")) {
                node.getStyleClass().add("pie-alerts");
            } else {
                node.getStyleClass().add("pie-empty");
            }
            node.setStyle("-fx-pie-color: " + pieColorFor(data.getName()) + ";");
            installAnalyticsTooltip(node, data.getName());
        }
    }

    private void updatePieLegend(ObservableList<PieChart.Data> pieData) {
        if (analyticsPieLegend == null) {
            return;
        }
        analyticsPieLegend.getChildren().clear();
        for (PieChart.Data data : pieData) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("pie-legend-row");
            Region dot = new Region();
            dot.getStyleClass().add(PIE_LEGEND_DOT_CLASS);
            dot.setStyle("-fx-background-color: " + pieColorFor(data.getName()) + ";");
            Label label = new Label(data.getName());
            label.getStyleClass().add("pie-legend-label");
            label.setWrapText(true);
            row.getChildren().addAll(dot, label);
            analyticsPieLegend.getChildren().add(row);
        }
    }

    private static String pieColorFor(String name) {
        if (name == null) {
            return "#9aa6b2";
        }
        if (name.startsWith("Relatorios")) {
            return "#2f756d";
        }
        if (name.startsWith("Ocorrencias")) {
            return "#bd8436";
        }
        if (name.startsWith("Alertas")) {
            return "#9b383b";
        }
        return "#9aa6b2";
    }

    private void installAnalyticsTooltip(Node node, String text) {
        Tooltip previous = (Tooltip) node.getProperties().get(ANALYTICS_TOOLTIP_KEY);
        if (previous != null) {
            Tooltip.uninstall(node, previous);
        }
        Tooltip tooltip = new Tooltip(text);
        node.getProperties().put(ANALYTICS_TOOLTIP_KEY, tooltip);
        Tooltip.install(node, tooltip);
    }

    private static String formatWholeNumber(Number number) {
        if (number == null) {
            return "0";
        }
        return Long.toString(Math.round(number.doubleValue()));
    }

    private void updateMapSummary() {
        if (mapSalesopolisLabel == null) {
            return;
        }
        int salesopolis = countOccurrencesFor("salesopolis");
        int mogi = countOccurrencesFor("mogi");
        int guarulhos = countOccurrencesFor("guarulhos");
        int saoPaulo = countOccurrencesFor("sao paulo") + countOccurrencesFor("barueri") + countOccurrencesFor("osasco");
        updateMapItem(mapSalesopolisLabel, mapSalesopolisBar, salesopolis);
        updateMapItem(mapMogiLabel, mapMogiBar, mogi);
        updateMapItem(mapGuarulhosLabel, mapGuarulhosBar, guarulhos);
        updateMapItem(mapSaoPauloLabel, mapSaoPauloBar, saoPaulo);
        drawTieteMap(salesopolis, mogi, guarulhos, saoPaulo);
    }

    private void drawTieteMap(int salesopolis, int mogi, int guarulhos, int saoPaulo) {
        if (tieteMapPane == null) {
            return;
        }
        double width = tieteMapPane.getWidth() > 120 ? tieteMapPane.getWidth() : 900;
        double height = 320;
        tieteMapPane.getChildren().clear();

        Label title = new Label("Situacao em tempo real por trecho monitorado");
        title.getStyleClass().add("map-title");
        title.setLayoutX(24);
        title.setLayoutY(18);
        tieteMapPane.getChildren().add(title);

        Polyline river = new Polyline(
                width * 0.08, height * 0.66,
                width * 0.22, height * 0.50,
                width * 0.40, height * 0.58,
                width * 0.58, height * 0.42,
                width * 0.78, height * 0.54,
                width * 0.93, height * 0.34
        );
        river.getStyleClass().add("river-line");
        tieteMapPane.getChildren().add(river);

        addMapPoint("Salesopolis", width * 0.12, height * 0.63, salesopolis);
        addMapPoint("Mogi das Cruzes", width * 0.36, height * 0.56, mogi);
        addMapPoint("Guarulhos", width * 0.60, height * 0.43, guarulhos);
        addMapPoint("Grande Sao Paulo", width * 0.82, height * 0.51, saoPaulo);

        Label flow = new Label("Nascente -> area industrial -> regiao metropolitana");
        flow.getStyleClass().add("map-caption");
        flow.setLayoutX(24);
        flow.setLayoutY(height - 44);
        tieteMapPane.getChildren().add(flow);
    }

    private void addMapPoint(String name, double x, double y, int count) {
        Circle pulse = new Circle(x, y, count > 0 ? 22 : 16);
        pulse.getStyleClass().add(count > 0 ? "map-pulse" : "map-pulse-clear");
        Circle point = new Circle(x, y, 9);
        point.getStyleClass().add("map-point");
        if (count >= 3) {
            point.getStyleClass().add("critical");
        } else if (count > 0) {
            point.getStyleClass().add("warning");
        }

        Label label = new Label(name);
        label.getStyleClass().add("map-point-label");
        label.setLayoutX(x - 46);
        label.setLayoutY(y + 18);

        Label badge = new Label(count > 0 ? "!" : "OK");
        badge.getStyleClass().add(count > 0 ? "map-problem-badge" : "map-ok-badge");
        badge.setLayoutX(x + 12);
        badge.setLayoutY(y - 28);

        tieteMapPane.getChildren().addAll(pulse, point, label, badge);
    }

    private int countOccurrencesFor(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return (int) environmentalOccurrences.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).contains(lower))
                .count();
    }

    private static void updateMapItem(Label label, ProgressBar bar, int count) {
        label.setText(count + (count == 1 ? " ocorrencia" : " ocorrencias"));
        bar.setProgress(Math.min(1.0, count / 6.0));
    }

    private String formatCsvExport() {
        StringBuilder builder = new StringBuilder();
        builder.append("tipo;data;descricao").append(System.lineSeparator());
        for (String line : auditEvents) {
            builder.append("auditoria;").append(csv(TIME_FORMAT.format(Instant.now()))).append(';').append(csv(line)).append(System.lineSeparator());
        }
        for (String line : environmentalOccurrences) {
            builder.append("ocorrencia;").append(csv(TIME_FORMAT.format(Instant.now()))).append(';').append(csv(line)).append(System.lineSeparator());
        }
        for (FileMetadata file : remoteFiles) {
            builder.append("relatorio;").append(csv(TIME_FORMAT.format(Instant.ofEpochMilli(file.getUploadedAt())))).append(';')
                    .append(csv(file.getFileName() + " | " + file.getOwner() + " | " + file.getRoom() + " | " + reportWorkflowById.getOrDefault(file.getId(), "ENVIADO")))
                    .append(System.lineSeparator());
        }
        for (String line : criticalAlerts) {
            builder.append("alerta;").append(csv(TIME_FORMAT.format(Instant.now()))).append(';').append(csv(line)).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private List<String> pdfLines() {
        List<String> lines = new ArrayList<>();
        lines.add("APS Redes - Relatorio Operacional");
        lines.add("Gerado em " + Instant.now());
        lines.add("Canal: " + (hasActiveRoom() ? activeRoomText() : "Todos / sem canal"));
        lines.add("Relatorios carregados: " + remoteFiles.size());
        lines.add("Ocorrencias ambientais: " + environmentalOccurrences.size());
        lines.add("Alertas criticos: " + criticalAlerts.size());
        lines.add("Usuarios no painel: " + connectedUsers.size());
        lines.add("");
        lines.add("Ultimas ocorrencias:");
        environmentalOccurrences.stream().limit(8).forEach(lines::add);
        lines.add("");
        lines.add("Auditoria recente:");
        auditEvents.stream().limit(8).forEach(lines::add);
        return lines;
    }

    private static String csv(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static void writeSimplePdf(Path target, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder("BT /F1 11 Tf 50 790 Td 14 TL ");
        for (String line : lines) {
            text.append('(').append(pdfEscape(line)).append(") Tj T* ");
        }
        text.append("ET");
        byte[] stream = text.toString().getBytes(StandardCharsets.US_ASCII);
        List<String> objects = new ArrayList<>();
        objects.add("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        objects.add("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n");
        objects.add("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n");
        objects.add("4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n");
        objects.add("5 0 obj << /Length " + stream.length + " >> stream\n" + new String(stream, StandardCharsets.US_ASCII) + "\nendstream endobj\n");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String object : objects) {
            offsets.add(pdf.length());
            pdf.append(object);
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n");
        pdf.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer << /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(xref).append("\n%%EOF\n");
        Files.writeString(target, pdf.toString(), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
    }

    private static String pdfEscape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    private boolean isHistoryOnly(NetworkMessage message) {
        return message.getType() == MessageType.SYSTEM
                || message.getType() == MessageType.ALERT
                || message.getType() == MessageType.LOGIN_ACCEPTED
                || message.getType() == MessageType.USER_JOINED
                || message.getType() == MessageType.USER_LEFT
                || message.getType() == MessageType.FILE_NOTICE;
    }

    private String historyPrefix(NetworkMessage message) {
        return switch (message.getType()) {
            case ALERT, USER_JOINED, USER_LEFT -> "ALERTA";
            case FILE_NOTICE -> "RELATORIO";
            case LOGIN_ACCEPTED -> "SESSAO";
            default -> "SISTEMA";
        };
    }

    private String formatConversationLog() {
        StringBuilder builder = new StringBuilder();
        builder.append("Log APS Redes").append(System.lineSeparator());
        builder.append("Gerado em ").append(Instant.now()).append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("Historico").append(System.lineSeparator());
        for (String line : connectionHistory) {
            builder.append(line).append(System.lineSeparator());
        }

        builder.append(System.lineSeparator()).append("Chat").append(System.lineSeparator());
        messagesByChannel.forEach((channel, messages) -> {
            builder.append("Canal: ").append(channelKey(channel)).append(System.lineSeparator());
            for (NetworkMessage message : messages) {
                builder.append("[")
                        .append(TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())))
                        .append("] ")
                        .append(message.getFrom())
                        .append(": ");
                if (message.getType() == MessageType.IMAGE) {
                    builder.append("[foto webcam] ").append(message.getText());
                } else {
                    builder.append(message.getText());
                }
                builder.append(System.lineSeparator());
            }
            builder.append(System.lineSeparator());
        });
        return builder.toString();
    }

    private String profileName() {
        String name = profileNameField.getText() == null ? "" : profileNameField.getText().trim();
        return name.isBlank() ? "Inspetor APS" : name;
    }

    private void syncProfileFromLogin() {
        TextField source = adminSession ? adminNameField : loginNameField;
        String loginName = source.getText() == null ? "" : source.getText().trim();
        if (!loginName.isBlank()) {
            profileNameField.setText(loginName);
        } else {
            source.setText(profileName());
        }
    }

    private String activeRoomText() {
        return activeRoom == null ? "" : activeRoom;
    }

    private boolean hasActiveRoom() {
        return !activeRoomText().isBlank();
    }

    private byte[] readAvatarBytes() throws IOException {
        if (avatarPath == null || !Files.exists(avatarPath)) {
            return new byte[0];
        }
        long size = Files.size(avatarPath);
        if (size > MAX_AVATAR_BYTES) {
            throw new IOException("A imagem de perfil precisa ter ate 512 KB.");
        }
        return Files.readAllBytes(avatarPath);
    }

    private Optional<UserProfile> profileFor(NetworkMessage message) {
        String username = message.getAttributes().getOrDefault("username", "");
        Optional<UserProfile> remoteProfile = connectedUsers.stream()
                .filter(user -> user.getUsername().equalsIgnoreCase(username) || user.getDisplayName().equalsIgnoreCase(message.getFrom()))
                .findFirst();
        if (remoteProfile.isPresent()) {
            return remoteProfile;
        }
        String messageClientId = message.getAttributes().getOrDefault("clientId", "");
        boolean self = !messageClientId.isBlank()
                ? messageClientId.equals(networkClient.getClientId())
                : username.equalsIgnoreCase(networkClient.getUsername())
                || message.getFrom().equalsIgnoreCase(networkClient.getDisplayName());
        if (self && profileAvatar.length > 0) {
            return Optional.of(new UserProfile(networkClient.getUsername(), networkClient.getDisplayName(), activeRoomText(), System.currentTimeMillis(), profileAvatar));
        }
        return Optional.empty();
    }

    private void resetConversationState() {
        messagesByChannel.clear();
        chatMessages.clear();
        remoteFiles.clear();
        connectedUsers.clear();
        roleUsers.clear();
        clearRoleForm();
    }

    private void populateRoleForm(UserProfile profile) {
        if (profile == null) {
            clearRoleForm();
            return;
        }
        roleNameField.setText(profile.getRole());
        List<String> allowedRooms = profile.isAdmin() ? DEFAULT_CHANNELS : profile.getAllowedRooms();
        roleAlfaBox.setSelected(allowedRooms.contains("Equipe Alfa"));
        roleBetaBox.setSelected(allowedRooms.contains("Equipe Beta"));
        roleGamaBox.setSelected(allowedRooms.contains("Equipe Gama"));
        roleCentralBox.setSelected(allowedRooms.contains("Central Operacional"));
        boolean locked = profile.isAdmin();
        roleNameField.setDisable(locked);
        List.of(roleAlfaBox, roleBetaBox, roleGamaBox, roleCentralBox).forEach(box -> box.setDisable(locked));
        saveRoleButton.setDisable(locked);
        roleStatusLabel.setText(locked ? "Admin possui acesso total." : "Editando " + profile.getDisplayName() + ".");
        updateActionAvailability();
    }

    private void clearRoleForm() {
        roleNameField.clear();
        List.of(roleAlfaBox, roleBetaBox, roleGamaBox, roleCentralBox).forEach(box -> box.setSelected(false));
        saveRoleButton.setDisable(true);
        roleStatusLabel.setText("Selecione um membro.");
        updateActionAvailability();
    }

    private List<String> selectedRoleChannels() {
        List<String> selected = new ArrayList<>();
        if (roleAlfaBox.isSelected()) {
            selected.add("Equipe Alfa");
        }
        if (roleBetaBox.isSelected()) {
            selected.add("Equipe Beta");
        }
        if (roleGamaBox.isSelected()) {
            selected.add("Equipe Gama");
        }
        if (roleCentralBox.isSelected()) {
            selected.add("Central Operacional");
        }
        return selected;
    }

    private void appendChannelMessage(String channel, NetworkMessage message) {
        String key = channelKey(channel);
        List<NetworkMessage> messages = messagesByChannel.computeIfAbsent(key, ignored -> new ArrayList<>());
        messages.add(message);
        if (messages.size() > 300) {
            messages.remove(0);
        }
        if (sameChannel(key, activeRoomText())) {
            chatMessages.setAll(messages);
            chatList.scrollTo(chatMessages.size() - 1);
        }
    }

    private void renderChannel(String channel) {
        chatMessages.setAll(messagesByChannel.getOrDefault(channelKey(channel), List.of()));
        if (!chatMessages.isEmpty()) {
            chatList.scrollTo(chatMessages.size() - 1);
        }
    }

    private String messageChannel(NetworkMessage message) {
        String room = message.getAttributes().get("room");
        if (room != null && !room.isBlank()) {
            return room;
        }
        if (message.getType() == MessageType.CHAT || message.getType() == MessageType.IMAGE) {
            return activeRoomText();
        }
        return activeRoomText();
    }

    private boolean sameChannel(String left, String right) {
        return channelKey(left).equals(channelKey(right));
    }

    private boolean uploadedToday(FileMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        LocalDate uploadedDate = Instant.ofEpochMilli(metadata.getUploadedAt())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        return uploadedDate.equals(LocalDate.now());
    }

    private String channelKey(String channel) {
        return channel == null || channel.isBlank() ? LOBBY_CHANNEL : channel;
    }

    private boolean startLocalServerWindow() {
        try {
            Path script = Path.of("run-server.bat").toAbsolutePath();
            if (!Files.exists(script)) {
                addHistory("Servidor local nao encontrado: " + script);
                return false;
            }
            new ProcessBuilder("cmd", "/c", "start \"APS Redes - Servidor\" cmd /k \"" + script + "\"")
                    .directory(Path.of(".").toAbsolutePath().toFile())
                    .start();
            addHistory("Servidor local iniciado automaticamente.");
            return true;
        } catch (IOException exception) {
            addHistory("Falha ao iniciar servidor local: " + exception.getMessage());
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase();
        return value.isBlank()
                || value.equals("localhost")
                || value.equals("127.0.0.1")
                || value.equals("192.168.1.14")
                || value.equals("::1");
    }

    private static boolean isConnectionRefused(String message) {
        String value = message == null ? "" : message.toLowerCase();
        return value.contains("refused") || value.contains("recus");
    }

    private static int parsePort(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.toString() : current.getMessage();
    }

    private static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static void showReadableText(String title, String message) {
        TextArea area = new TextArea(message);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(64);
        area.setPrefRowCount(14);
        area.getStyleClass().add("report-editor");

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().setContent(area);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024) {
            return SIZE_FORMAT.format(kib) + " KB";
        }
        return SIZE_FORMAT.format(kib / 1024.0) + " MB";
    }

    private final class MessageCell extends ListCell<NetworkMessage> {
        @Override
        protected void updateItem(NetworkMessage item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            setGraphic(messageNode(item));
        }

        private Node messageNode(NetworkMessage message) {
            if (isCentral(message)) {
                return centralMessageNode(message);
            }

            String messageClientId = message.getAttributes().getOrDefault("clientId", "");
            boolean self = (!messageClientId.isBlank() && messageClientId.equals(networkClient.getClientId()))
                    || (messageClientId.isBlank()
                    && message.getAttributes().getOrDefault("username", "").equalsIgnoreCase(networkClient.getUsername()));
            HBox row = new HBox(13);
            row.getStyleClass().add("message-row");
            row.setAlignment(self ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

            Node avatar = avatarNode(message);
            VBox bubble = new VBox(8);
            bubble.getStyleClass().add("message-bubble");
            if (self) {
                bubble.getStyleClass().add("self");
            }
            if (mentionsSelf(message) && !self) {
                bubble.getStyleClass().add("mentioned");
            }

            Label header = new Label(senderLabel(message) + "  " + TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())));
            header.getStyleClass().add("message-header");
            TextFlow textFlow = new TextFlow(new Text(messageText(message)));
            textFlow.getStyleClass().add("message-text");
            bubble.getChildren().addAll(header, textFlow);

            if (message.getType() == MessageType.IMAGE && message.getPayload().length > 0) {
                Image image = new Image(new ByteArrayInputStream(message.getPayload()), 480, 280, true, true);
                ImageView imageView = new ImageView(image);
                imageView.setPreserveRatio(true);
                imageView.setFitWidth(360);
                imageView.getStyleClass().add("chat-image");
                bubble.getChildren().add(imageView);
            }

            if (self) {
                row.getChildren().addAll(bubble, avatar);
            } else {
                row.getChildren().addAll(avatar, bubble);
            }
            return row;
        }

        private Node avatarNode(NetworkMessage message) {
            Optional<UserProfile> profile = profileFor(message);
            if (profile.isPresent() && profile.get().getAvatar().length > 0) {
                ImageView imageView = new ImageView(new Image(new ByteArrayInputStream(profile.get().getAvatar()), 38, 38, true, true));
                imageView.setFitWidth(38);
                imageView.setFitHeight(38);
                imageView.setPreserveRatio(true);
                imageView.getStyleClass().add("avatar-image");
                return imageView;
            }
            Label fallback = new Label(initials(message.getFrom()));
            fallback.getStyleClass().add("avatar");
            return fallback;
        }

        private Node centralMessageNode(NetworkMessage message) {
            HBox row = new HBox();
            row.setAlignment(Pos.CENTER);
            row.getStyleClass().add("central-row");

            VBox bubble = new VBox(5);
            bubble.getStyleClass().add("central-alert");
            if ("console".equals(message.getAttributes().get("source"))) {
                bubble.getStyleClass().add("console");
            }
            if (message.getType() == MessageType.FILE_NOTICE) {
                bubble.getStyleClass().add("file");
            }
            Label header = new Label(centralTitle(message) + "  " + TIME_FORMAT.format(Instant.ofEpochMilli(message.getTimestamp())));
            header.getStyleClass().add("message-header");
            TextFlow textFlow = new TextFlow(new Text(messageText(message)));
            textFlow.getStyleClass().add("message-text");
            bubble.getChildren().addAll(header, textFlow);
            row.getChildren().add(bubble);
            return row;
        }

        private boolean isCentral(NetworkMessage message) {
            return message.getType() == MessageType.SYSTEM
                    || message.getType() == MessageType.ALERT
                    || message.getType() == MessageType.LOGIN_ACCEPTED
                    || message.getType() == MessageType.USER_JOINED
                    || message.getType() == MessageType.USER_LEFT
                    || message.getType() == MessageType.FILE_NOTICE;
        }

        private String centralTitle(NetworkMessage message) {
            return switch (message.getType()) {
                case ALERT, USER_JOINED, USER_LEFT -> "ALERTA";
                case FILE_NOTICE -> "RELATORIO";
                default -> "SISTEMA";
            };
        }

        private String messageText(NetworkMessage message) {
            if (message.getType() == MessageType.FILE_NOTICE) {
                return message.getText() + " (" + formatBytes(message.getFileSize()) + ")";
            }
            return message.getText();
        }

        private String senderLabel(NetworkMessage message) {
            String role = message.getAttributes().getOrDefault("role", "").trim();
            if (role.isBlank()) {
                return message.getFrom();
            }
            return message.getFrom() + " (" + role + ")";
        }

        private String initials(String value) {
            if (value == null || value.isBlank()) {
                return "SM";
            }
            String clean = value.trim();
            return clean.substring(0, Math.min(clean.length(), 2)).toUpperCase();
        }
    }

    private static final class UserCell extends ListCell<UserProfile> {
        @Override
        protected void updateItem(UserProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            HBox root = new HBox(13);
            root.setAlignment(Pos.CENTER_LEFT);
            root.getStyleClass().add("user-row");
            Node avatar = avatarNode(item);
            VBox copy = new VBox(5);
            HBox.setHgrow(copy, Priority.ALWAYS);
            copy.setMaxWidth(Double.MAX_VALUE);
            Label name = new Label(item.getDisplayName());
            name.getStyleClass().add("user-name");
            name.setWrapText(true);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            Label meta = new Label(item.getRole() + " | " + item.getPresence() + " | " + item.getRoom()
                    + " | desde " + TIME_FORMAT.format(Instant.ofEpochMilli(item.getJoinedAt())));
            meta.getStyleClass().add("file-meta");
            meta.setWrapText(true);
            meta.setMaxWidth(430);
            meta.setTextOverrun(OverrunStyle.ELLIPSIS);
            copy.getChildren().addAll(name, meta);
            root.getChildren().addAll(avatar, copy);
            setGraphic(root);
        }

        private Node avatarNode(UserProfile item) {
            byte[] avatar = item.getAvatar();
            if (avatar.length > 0) {
                ImageView imageView = new ImageView(new Image(new ByteArrayInputStream(avatar), 42, 42, true, true));
                imageView.setFitWidth(42);
                imageView.setFitHeight(42);
                imageView.setPreserveRatio(true);
                imageView.getStyleClass().add("avatar-image");
                return imageView;
            }
            Label fallback = new Label(initials(item.getDisplayName()));
            fallback.getStyleClass().add("avatar");
            return fallback;
        }

        private String initials(String value) {
            if (value == null || value.isBlank()) {
                return "??";
            }
            String clean = value.trim();
            return clean.substring(0, Math.min(clean.length(), 2)).toUpperCase();
        }
    }

    private final class FileCell extends ListCell<FileMetadata> {
        @Override
        protected void updateItem(FileMetadata item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            HBox root = new HBox(13);
            root.getStyleClass().add("file-row");
            Label badge = new Label("DOC");
            badge.getStyleClass().add("file-badge");
            VBox copy = new VBox(5);
            HBox.setHgrow(copy, Priority.ALWAYS);
            copy.setMaxWidth(Double.MAX_VALUE);
            Label name = new Label(item.getFileName());
            name.getStyleClass().add("file-name");
            name.setWrapText(true);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            String workflow = reportWorkflowById.getOrDefault(item.getId(), "ENVIADO");
            Label meta = new Label(item.getOwner() + " | " + item.getRoom() + " | " + workflow
                    + " | " + formatBytes(item.getSize()) + " | " + TIME_FORMAT.format(Instant.ofEpochMilli(item.getUploadedAt())));
            meta.getStyleClass().add("file-meta");
            meta.setWrapText(true);
            meta.setMaxWidth(460);
            meta.setTextOverrun(OverrunStyle.ELLIPSIS);
            copy.getChildren().addAll(name, meta);
            root.getChildren().addAll(badge, copy);
            setGraphic(root);
        }
    }
}
