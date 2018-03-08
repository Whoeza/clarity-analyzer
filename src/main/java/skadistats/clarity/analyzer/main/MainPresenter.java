package skadistats.clarity.analyzer.main;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import javafx.util.converter.NumberStringConverter;
import org.controlsfx.dialog.Dialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skadistats.clarity.analyzer.Main;
import skadistats.clarity.analyzer.replay.ObservableEntity;
import skadistats.clarity.analyzer.replay.ObservableEntityList;
import skadistats.clarity.analyzer.replay.ObservableEntityProperty;
import skadistats.clarity.analyzer.replay.ReplayController;

import java.io.File;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.prefs.Preferences;

public class MainPresenter implements Initializable {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @FXML
    public Button buttonPlay;

    @FXML
    public Button buttonPause;

    @FXML
    public Slider slider;

    @FXML
    public Label labelTick;

    @FXML
    public Label labelLastTick;

    @FXML
    public TableView<ObservableEntity> entityTable;

    @FXML
    public TableView<ObservableEntityProperty> detailTable;

    @FXML
    public TextField entityNameFilter;

    @FXML
    public AnchorPane mapCanvasPane;

    private MapControl mapControl;

    private Preferences preferences;
    private ReplayController replayController;


    private FilteredList<ObservableEntity> filteredEntityList = null;

    private Predicate<ObservableEntity> allFilterFunc = new Predicate<ObservableEntity>() {
        @Override
        public boolean test(ObservableEntity e) {
            return true;
        }
    };

    private Predicate<ObservableEntity> filterFunc = new Predicate<ObservableEntity>() {
        @Override
        public boolean test(ObservableEntity e) {
            String filter = entityNameFilter.getText();
            if (filter.length() > 0) {
                return e != null && e.getName().toLowerCase().contains(filter.toLowerCase());
            }
            return true;
        }
    };

    public void initialize(java.net.URL location, java.util.ResourceBundle resources) {
        preferences = Preferences.userNodeForPackage(this.getClass());
        replayController = new ReplayController();

        BooleanBinding runnerIsNull = Bindings.createBooleanBinding(() -> replayController.getRunner() == null, replayController.runnerProperty());
        buttonPlay.disableProperty().bind(runnerIsNull.or(replayController.playingProperty()));
        buttonPause.disableProperty().bind(runnerIsNull.or(replayController.playingProperty().not()));
        slider.disableProperty().bind(runnerIsNull);
        replayController.changingProperty().bind(slider.valueChangingProperty());

        labelTick.textProperty().bindBidirectional(replayController.tickProperty(), new NumberStringConverter());
        labelLastTick.textProperty().bindBidirectional(replayController.lastTickProperty(), new NumberStringConverter());

        slider.maxProperty().bind(replayController.lastTickProperty());
        replayController.tickProperty().addListener((observable, oldValue, newValue) -> {
            if (!slider.isValueChanging()) {
                slider.setValue(newValue.intValue());
            }
        });
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            replayController.getRunner().setDemandedTick(newValue.intValue());
        });

        TableColumn<ObservableEntity, String> entityTableIdColumn = (TableColumn<ObservableEntity, String>) entityTable.getColumns().get(0);
        entityTableIdColumn.setCellValueFactory(param -> param.getValue() != null ? param.getValue().indexProperty() : new ReadOnlyStringWrapper(""));
        TableColumn<ObservableEntity, String> entityTableNameColumn = (TableColumn<ObservableEntity, String>) entityTable.getColumns().get(1);
        entityTableNameColumn.setCellValueFactory(param -> param.getValue() != null ? param.getValue().nameProperty() : new ReadOnlyStringWrapper(""));
        entityTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            log.info("entity table selection from {} to {}", oldValue, newValue);
            detailTable.setItems(newValue);
        });

        TableColumn<ObservableEntityProperty, String> idColumn =
            (TableColumn<ObservableEntityProperty, String>) detailTable.getColumns().get(0);
        idColumn.setCellValueFactory(param -> param.getValue().indexProperty());
        TableColumn<ObservableEntityProperty, String> nameColumn =
            (TableColumn<ObservableEntityProperty, String>) detailTable.getColumns().get(1);
        nameColumn.setCellValueFactory(param -> param.getValue().nameProperty());
        TableColumn<ObservableEntityProperty, String> valueColumn =
            (TableColumn<ObservableEntityProperty, String>) detailTable.getColumns().get(2);
        valueColumn.setCellValueFactory(param -> param.getValue().valueProperty());

        entityNameFilter.textProperty().addListener(observable -> {
            if (filteredEntityList != null) {
                filteredEntityList.setPredicate(allFilterFunc);
                filteredEntityList.setPredicate(filterFunc);
            }
        });

        mapControl = new MapControl();
        mapCanvasPane.getChildren().add(mapControl);

        mapCanvasPane.setTopAnchor(mapControl, 0.0);
        mapCanvasPane.setBottomAnchor(mapControl, 0.0);
        mapCanvasPane.setLeftAnchor(mapControl, 0.0);
        mapCanvasPane.setRightAnchor(mapControl, 0.0);
        mapCanvasPane.widthProperty().addListener(evt -> resizeMapControl());
        mapCanvasPane.heightProperty().addListener(evt -> resizeMapControl());

    }

    private void resizeMapControl() {
        double scale = Math.min(mapCanvasPane.getWidth() / mapControl.getSize(), mapCanvasPane.getHeight() / mapControl.getSize());

        double sw = mapControl.getSize() * scale;
        double dx = mapCanvasPane.getWidth() - sw;
        double dy = mapCanvasPane.getHeight() - sw;

        mapCanvasPane.getTransforms().clear();
        mapCanvasPane.getTransforms().add(new Scale(scale, scale));
        mapCanvasPane.getTransforms().add(new Translate(0.5 * dx / scale, 0.5 * dy / scale));
    }

    public void actionQuit(ActionEvent actionEvent) {
        replayController.haltIfRunning();
        Main.primaryStage.close();
    }

    public void actionOpen(ActionEvent actionEvent) throws IOException {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load a replay");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Dota 2 replay files", "*.dem"),
            new FileChooser.ExtensionFilter("All files", "*")
        );
        File dir = new File(preferences.get("fileChooserPath", "."));
        if (!dir.isDirectory()) {
            dir = new File(".");
        }
        fileChooser.setInitialDirectory(dir);
        File f = fileChooser.showOpenDialog(Main.primaryStage);
        if (f == null) {
            return;
        }
        preferences.put("fileChooserPath", f.getParent());
        try {
            ObservableEntityList entityList = replayController.load(f);
            mapControl.setEntities(entityList);
            filteredEntityList = entityList.filtered(filterFunc);
            entityTable.setItems(filteredEntityList);

        } catch (Exception e) {
            Dialogs.create().title("Error loading replay").showException(e);
        }
    }

    public void clickPlay(ActionEvent actionEvent) {
        replayController.setPlaying(true);
    }

    public void clickPause(ActionEvent actionEvent) {
        replayController.setPlaying(false);
    }

}
