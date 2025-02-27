/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.gis.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.gis.*;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceListener;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.virtual.DBVEntity;
import org.jkiss.dbeaver.model.virtual.DBVEntityAttribute;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.*;
import org.jkiss.dbeaver.ui.controls.lightgrid.GridPos;
import org.jkiss.dbeaver.ui.controls.resultset.AbstractPresentation;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetPresentation;
import org.jkiss.dbeaver.ui.css.CSSUtils;
import org.jkiss.dbeaver.ui.css.DBStyles;
import org.jkiss.dbeaver.ui.dialogs.DialogUtils;
import org.jkiss.dbeaver.ui.gis.GeometryDataUtils;
import org.jkiss.dbeaver.ui.gis.GeometryViewerConstants;
import org.jkiss.dbeaver.ui.gis.IGeometryValueEditor;
import org.jkiss.dbeaver.ui.gis.internal.GISMessages;
import org.jkiss.dbeaver.ui.gis.internal.GISViewerActivator;
import org.jkiss.dbeaver.ui.gis.registry.GeometryViewerRegistry;
import org.jkiss.dbeaver.ui.gis.registry.LeafletTilesDescriptor;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.locationtech.jts.geom.Geometry;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GISLeafletViewer implements IGeometryValueEditor, DBPPreferenceListener {
    private static final Log log = Log.getLog(GISLeafletViewer.class);

    private static final String PREF_RECENT_SRID_LIST = "srid.list.recent";

    private static final String[] SUPPORTED_FORMATS = new String[] { "png", "gif", "bmp" };

    private static final String PROP_FLIP_COORDINATES = "gis.flipCoords";
    private static final String PROP_SRID = "gis.srid";

    private static final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(DBDContent.class, new DBDContentAdapter()).create();

    private final DBDAttributeBinding[] bindings;
    private Browser browser;
    private DBGeometry[] lastValue;
    private int sourceSRID; // Explicitly set SRID
    private int actualSourceSRID; // SRID taken from geometry value
    private Path scriptFile;
    private final ToolBarManager toolBarManager;
    private int defaultSRID; // Target SRID used to render map

    private boolean toolsVisible = true;
    private boolean flipCoordinates = false;
    private final Composite composite;

    public GISLeafletViewer(Composite parent, @NotNull DBDAttributeBinding[] bindings, @Nullable SpatialDataProvider spatialDataProvider, @Nullable IResultSetPresentation presentation) {
        this.bindings = bindings;

        this.flipCoordinates = spatialDataProvider != null && spatialDataProvider.isFlipCoordinates();

        composite = UIUtils.createPlaceholder(parent, 1);
        CSSUtils.setCSSClass(composite, DBStyles.COLORED_BY_CONNECTION_TYPE);

        try {
            browser = new Browser(composite, SWT.NONE);
        } catch (SWTError error) {
            log.error("Internal web browser initialization failed", error);
            for (Control control : composite.getChildren()) {
                control.dispose();
            }
            browser = null;
            if (error.code != SWT.ERROR_NOT_IMPLEMENTED) {
                throw error;
            }
        }

        if (browser != null) {
            browser.setLayoutData(new GridData(GridData.FILL_BOTH));
            new BrowserFunction(browser, "setClipboardContents") {
                @Override
                public Object function(Object[] arguments) {
                    UIUtils.setClipboardContents(Display.getCurrent(), TextTransfer.getInstance(), arguments[0]);
                    return null;
                }
            };

            if (presentation instanceof AbstractPresentation) {
                new BrowserFunction(browser, "setPresentationSelection") {
                    @Override
                    public Object function(Object[] arguments) {
                        final List<GridPos> selection = new ArrayList<>();
                        for (Object pos : ((Object[]) arguments[0])) {
                            final String[] split = ((String) pos).split(":");
                            selection.add(new GridPos(CommonUtils.toInt(split[0]), CommonUtils.toInt(split[1])));
                        }
                        ((AbstractPresentation) presentation).setSelection(new StructuredSelection(selection), false);
                        return null;
                    }
                };
            }

            browser.addDisposeListener(e -> {
                cleanupFiles();
                GISViewerActivator.getDefault().getPreferences().removePropertyChangeListener(this);
            });
        }

        {
            Composite bottomPanel = UIUtils.createPlaceholder(composite, 1);//new Composite(composite, SWT.NONE);
            bottomPanel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            CSSUtils.setCSSClass(bottomPanel, DBStyles.COLORED_BY_CONNECTION_TYPE);

            ToolBar bottomToolbar = new ToolBar(bottomPanel, SWT.FLAT | SWT.HORIZONTAL | SWT.RIGHT);

            toolBarManager = new ToolBarManager(bottomToolbar);
        }

        {
            String recentSRIDString = GISViewerActivator.getDefault().getPreferences().getString(PREF_RECENT_SRID_LIST);
            if (!CommonUtils.isEmpty(recentSRIDString)) {
                for (String sridStr : recentSRIDString.split(",")) {
                    int recentSRID = CommonUtils.toInt(sridStr);
                    if (recentSRID == 0 || recentSRID == GeometryDataUtils.getDefaultSRID() || recentSRID == GisConstants.SRID_3857) {
                        continue;
                    }
                    GISEditorUtils.addRecentSRID(recentSRID);
                }
            }
        }

        {
            // TODO:
            //  Following code uses properties from very first attribute
            //  and ignores other attributes, if present. There's no clear
            //  vision of what we should do here instead.

            // Check for save settings
            DBDAttributeBinding binding = bindings[0];
            if (binding.getEntityAttribute() != null) {
                DBVEntity vEntity = DBVUtils.getVirtualEntity(binding, false);
                if (vEntity != null) {
                    DBVEntityAttribute vAttr = vEntity.getVirtualAttribute(binding, false);
                    if (vAttr != null) {
                        this.flipCoordinates = CommonUtils.getBoolean(vAttr.getProperty(PROP_FLIP_COORDINATES), this.flipCoordinates);
                        this.sourceSRID = CommonUtils.toInt(vAttr.getProperty(PROP_SRID), this.sourceSRID);
                    }
                }
            }
        }

        GISViewerActivator.getDefault().getPreferences().addPropertyChangeListener(this);
    }

    @Override
    public Control getEditorControl() {
        return composite;
    }

    @Override
    public int getValueSRID() {
        return actualSourceSRID;
    }

    @Override
    public void setValueSRID(int srid) {
        if (srid == sourceSRID) {
            //return;
        }
        int oldSRID = sourceSRID;
        this.sourceSRID = srid;
        try {
            reloadGeometryData(lastValue, true, true);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Setting SRID", "Can't change source SRID to " + srid, e);
            sourceSRID = oldSRID;
        }
        {
            // Save SRID to the list of recently used SRIDs
            if (srid != GeometryDataUtils.getDefaultSRID() && srid != GisConstants.SRID_3857) {
                GISEditorUtils.addRecentSRID(srid);
            }
            GISEditorUtils.curRecentSRIDs();
            StringBuilder sridListStr = new StringBuilder();
            for (Integer sridInt : GISEditorUtils.getRecentSRIDs()) {
                if (sridListStr.length() > 0) sridListStr.append(",");
                sridListStr.append(sridInt);
            }
            GISViewerActivator.getDefault().getPreferences().setValue(PREF_RECENT_SRID_LIST, sridListStr.toString());
        }
        saveAttributeSettings();
    }

    @Override
    public void refresh() {
        try {
            reloadGeometryData(lastValue, true, false);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Refresh", "Can't refresh value viewer", e);
        }
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent event) {
        refresh();
    }

    public void setGeometryData(@Nullable DBGeometry[] values) throws DBException {
        reloadGeometryData(values, false, true);
    }

    public void reloadGeometryData(@Nullable DBGeometry[] values, boolean force, boolean recenter) throws DBException {
        if (!force && CommonUtils.equalObjects(lastValue, values)) {
            return;
        }
        int maxObjects = GISViewerActivator.getDefault().getPreferences().getInt(GeometryViewerConstants.PREF_MAX_OBJECTS_RENDER);
        if (maxObjects <= 0) {
            maxObjects = GeometryViewerConstants.DEFAULT_MAX_OBJECTS_RENDER;
        }
        if (values != null && values.length > maxObjects) {
            // Truncate value list
            DBGeometry[] truncValues = new DBGeometry[maxObjects];
            System.arraycopy(values, 0, truncValues, 0, maxObjects);
            values = truncValues;
        }
        if (browser != null) {
            try {
                if (ArrayUtils.isEmpty(values)) {
                    browser.setUrl("about:blank");
                } else {
                    final Bounds bounds = recenter ? null : Bounds.tryExtractFromBrowser(browser);
                    final Path file = generateViewScript(values, bounds);
                    browser.setUrl(file.toFile().toURI().toURL().toString());
                }
            } catch (IOException e) {
                throw new DBException("Error generating viewer script", e);
            }
        }
        lastValue = values;
        updateToolbar();
    }

    private Path generateViewScript(DBGeometry[] values, @Nullable Bounds bounds) throws IOException {
        if (scriptFile == null) {
            Path tempDir = DBWorkbench.getPlatform().getTempFolder(new VoidProgressMonitor(), "gis-viewer-files");
            checkIncludesExistence(tempDir);

            scriptFile = Files.createTempFile(tempDir, "view", "gis.html");
        }

        int attributeSrid = GisConstants.SRID_SIMPLE;
        if (bindings[0].getAttribute() instanceof GisAttribute) {
            try {
                attributeSrid = ((GisAttribute) bindings[0].getAttribute())
                        .getAttributeGeometrySRID(new VoidProgressMonitor());
            } catch (DBCException e) {
                log.error(e);
            }
        }

        List<String> geomValues = new ArrayList<>();
        List<String> geomTipValues = new ArrayList<>();
        boolean showMap = false;
        for (int i = 0; i < values.length; i++) {
            DBGeometry value = values[i];
            if (DBUtils.isNullValue(value)) {
                continue;
            }
            if (flipCoordinates) {
                try {
                    value = value.flipCoordinates();
                } catch (DBException e) {
                    log.error(e);
                }
            }
            try {
                value = value.force2D();
            } catch (DBException e) {
                log.error("Error forcing geometry to 2D", e);
            }
            Object targetValue = value.getRawValue();
            int srid = sourceSRID == 0 ? value.getSRID() : sourceSRID;
            if (srid == GisConstants.SRID_SIMPLE) {
                srid = attributeSrid;
            }
            if (srid == 0) {
                srid = GeometryDataUtils.getDefaultSRID();
            }
            if (srid == GisConstants.SRID_SIMPLE) {
                showMap = false;
                actualSourceSRID = srid;
            } else if (srid == GisConstants.SRID_4326) {
                showMap = true;
                actualSourceSRID = srid;
            } else {
                Geometry geometry = value.getGeometry();
                if (geometry != null) {
                    try {
                        GisTransformRequest request = new GisTransformRequest(geometry, srid, GisConstants.SRID_4326);
                        GisTransformUtils.transformGisData(request);
                        targetValue = request.getTargetValue();
                        srid = request.getTargetSRID();
                        actualSourceSRID = request.getSourceSRID();
                        showMap = request.isShowOnMap();
                    } catch (DBException e) {
                        log.debug("Error transforming CRS", e);
                        actualSourceSRID = srid;
                        showMap = false;
                    }
                } else {
                    actualSourceSRID = srid;
                }
            }

            if (targetValue == null) {
                continue;
            }
            geomValues.add("'" + targetValue + "'");
            try {
                if (CommonUtils.isEmpty(value.getProperties())) {
                    geomTipValues.add("null");
                } else {
                    geomTipValues.add(gson.toJson(value.getProperties()));
                }
            } catch (Exception e) {
                log.debug(e);
            }
        }
        this.defaultSRID = actualSourceSRID;
        String geomValuesString = String.join(",", geomValues);
        String geomTipValuesString = String.join(",", geomTipValues);
        String geomCRS = actualSourceSRID == GisConstants.SRID_SIMPLE ? GisConstants.LL_CRS_SIMPLE : GisConstants.LL_CRS_3857;
        boolean isShowMap = showMap;

        InputStream fis = GISViewerActivator.getDefault().getResourceStream(GISBrowserViewerConstants.VIEW_TEMPLATE_PATH);
        if (fis == null) {
            throw new IOException("View template file not found (" + GISBrowserViewerConstants.VIEW_TEMPLATE_PATH + ")");
        }
        try (InputStreamReader isr = new InputStreamReader(fis)) {
            String viewTemplate = IOUtils.readToString(isr);
            viewTemplate = GeneralUtils.replaceVariables(viewTemplate, name -> {
                switch (name) {
                    case "geomValues":
                        return geomValuesString;
                    case "geomTipValues":
                        return geomTipValuesString;
                    case "geomSRID":
                        return String.valueOf(defaultSRID);
                    case "showMap":
                        return String.valueOf(isShowMap);
                    case "showTools":
                        return String.valueOf(toolsVisible);
                    case "geomCRS":
                        return geomCRS;
                    case "geomBounds":
                        return CommonUtils.toString(bounds, "undefined");
                    case "minZoomLevel":
                        return String.valueOf(GISViewerActivator.getDefault().getPreferences().getInt(GeometryViewerConstants.PREF_MIN_ZOOM_LEVEL));
                    case "defaultTiles":
                        LeafletTilesDescriptor descriptor = GeometryViewerRegistry.getInstance().getDefaultLeafletTiles();
                        if (descriptor == null) {
                            return null;
                        }
                        return GeometryViewerRegistry.getInstance().getDefaultLeafletTiles().getLayersDefinition();
                }
                return null;
            });
            try (OutputStream fos = Files.newOutputStream(scriptFile)) {
                fos.write(viewTemplate.getBytes(GeneralUtils.UTF8_CHARSET));
            }
        } finally {
            ContentUtils.close(fis);
        }

        return scriptFile;
    }

    private void checkIncludesExistence(Path scriptDir) throws IOException {
        Path incFolder = scriptDir.resolve("inc");
        if (!Files.exists(incFolder)) {
            Files.createDirectories(incFolder);
            for (String fileName : GISBrowserViewerConstants.INC_FILES) {
                InputStream fis = GISViewerActivator.getDefault().getResourceStream(GISBrowserViewerConstants.WEB_INC_PATH + fileName);
                if (fis != null) {
                    try (OutputStream fos = Files.newOutputStream(incFolder.resolve(fileName))) {
                        try {
                            IOUtils.copyStream(fis, fos);
                        } catch (Exception e) {
                            log.warn("Error copying inc file " + fileName, e);
                        } finally {
                            ContentUtils.close(fis);
                        }
                    }
                }
            }
        }
    }

    private void cleanupFiles() {
        if (scriptFile != null) {
            try {
                Files.delete(scriptFile);
            } catch (IOException e) {
                log.debug("Can't delete temp script file '" + scriptFile + "'", e);
            }
        }
    }

    public Composite getBrowserComposite() {
        return composite;
    }

    @Nullable
    public Browser getBrowser() {
        return browser;
    }

    public DBGeometry[] getCurrentValue() {
        return lastValue;
    }

    void updateToolbar() {
        if (browser == null) {
            return;
        }
        toolBarManager.removeAll();
        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_open, DBeaverIcons.getImageDescriptor(UIIcon.BROWSER)) {
            @Override
            public void run() {
                ShellUtils.launchProgram(scriptFile.toAbsolutePath().toString());
            }
        });
        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_copy_as, DBeaverIcons.getImageDescriptor(UIIcon.PICTURE)) {
            @Override
            public void run() {
                Image image = new Image(Display.getDefault(), browser.getBounds());
                GC gc = new GC(image);
                try {
                    browser.print(gc);
                } finally {
                    gc.dispose();
                }
                ImageTransfer imageTransfer = ImageTransfer.getInstance();
                Clipboard clipboard = new Clipboard(Display.getCurrent());
                clipboard.setContents(new Object[] {image.getImageData()}, new Transfer[]{imageTransfer});
            }
        });
        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_save_as, DBeaverIcons.getImageDescriptor(UIIcon.PICTURE_SAVE)) {
            @Override
            public void run() {
                final Shell shell = browser.getShell();
                FileDialog saveDialog = new FileDialog(shell, SWT.SAVE);
                String[] extensions = new String[SUPPORTED_FORMATS.length];
                String[] filterNames = new String[SUPPORTED_FORMATS.length];
                for (int i = 0; i < SUPPORTED_FORMATS.length; i++) {
                    extensions[i] = "*." + SUPPORTED_FORMATS[i];
                    filterNames[i] = SUPPORTED_FORMATS[i].toUpperCase() + " (*." + SUPPORTED_FORMATS[i] + ")";
                }
                saveDialog.setFilterExtensions(extensions);
                saveDialog.setFilterNames(filterNames);
                String filePath = DialogUtils.openFileDialog(saveDialog);
                if (filePath == null) {
                    return;
                }
                int imageType = SWT.IMAGE_BMP;
                {
                    String filePathLower = filePath.toLowerCase();
                    if (filePathLower.endsWith(".png")) {
                        imageType = SWT.IMAGE_PNG;
                    } else if (filePathLower.endsWith(".gif")) {
                        imageType = SWT.IMAGE_GIF;
                    }
                }

                Image image = new Image(Display.getDefault(), browser.getBounds());
                GC gc = new GC(image);
                try {
                    browser.print(gc);
                } finally {
                    gc.dispose();
                }
                ImageLoader imageLoader = new ImageLoader();
                imageLoader.data = new ImageData[1];
                imageLoader.data[0] = image.getImageData();
                File outFile = new File(filePath);
                try (OutputStream fos = new FileOutputStream(outFile)) {
                    imageLoader.save(fos, imageType);
                } catch (IOException e) {
                    DBWorkbench.getPlatformUI().showError("Image save error", "Error saving as picture", e);
                }
                ShellUtils.launchProgram(outFile.getAbsolutePath());
            }
        });

        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_print, DBeaverIcons.getImageDescriptor(UIIcon.PRINT)) {
            @Override
            public void run() {
                GC gc = new GC(browser.getDisplay());
                try {
                    browser.execute("javascript:window.print();");
                } finally {
                    gc.dispose();
                }
            }
        });

        toolBarManager.add(new Separator());

        Action crsSelectorAction = new SelectCRSAction(this);
        toolBarManager.add(ActionUtils.makeActionContribution(crsSelectorAction, true));

        //if geometries have different srid show warning and do nothing (see in SelectCRSAction)
        /*if (Arrays.stream(lastValue).map(DBGeometry::getSRID).distinct().count() > 1) {
            // Disallow changing srid if geometries have different srid
            // Maybe we should transform them into source srid first and then transmute into a desired one?
            crsSelectorAction.setEnabled(false);
        }*/

        Action tilesSelectorAction = new SelectTilesAction(this);
        toolBarManager.add(ActionUtils.makeActionContribution(tilesSelectorAction, true));

        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_flip, Action.AS_CHECK_BOX) {
            {
                setToolTipText(GISMessages.panel_leaflet_viewer_tool_bar_action_tool_tip_text_flip);
                setImageDescriptor(DBeaverIcons.getImageDescriptor(UIIcon.LINK_TO_EDITOR));
            }

            @Override
            public boolean isChecked() {
                return flipCoordinates;
            }

            @Override
            public void run() {
                flipCoordinates = !flipCoordinates;
                try {
                    reloadGeometryData(lastValue, true, true);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError("Render error", "Error rendering geometry", e);
                }
                saveAttributeSettings();
                updateToolbar();
            }
        });

        toolBarManager.add(new Separator());

        toolBarManager.add(new Action(GISMessages.panel_leaflet_viewer_tool_bar_action_text_show_hide, Action.AS_CHECK_BOX) {
            {
                setImageDescriptor(DBeaverIcons.getImageDescriptor(UIIcon.PALETTE));
            }

            @Override
            public boolean isChecked() {
                return toolsVisible;
            }

            @Override
            public void run() {
                toolsVisible = !toolsVisible;
                updateControlsVisibility();
                updateToolbar();
            }
        });

        toolBarManager.update(true);
    }

    private void saveAttributeSettings() {
        for (DBDAttributeBinding binding : bindings) {
            if (binding.getEntityAttribute() != null) {
                DBVEntity vEntity = DBVUtils.getVirtualEntity(binding, true);
                DBVEntityAttribute vAttr = vEntity.getVirtualAttribute(binding, true);
                if (vAttr != null) {
                    vAttr.setProperty(PROP_FLIP_COORDINATES, String.valueOf(flipCoordinates));
                    vAttr.setProperty(PROP_SRID, String.valueOf(getValueSRID()));
                }
            }
        }
        bindings[0].getDataSource().getContainer().persistConfiguration();
    }

    private void updateControlsVisibility() {
        if (browser == null) {
            return;
        }
        
        GC gc = new GC(browser.getDisplay());
        try {
            browser.execute("javascript:showTools(" + toolsVisible + ");");
        } finally {
            gc.dispose();
        }
    }

    private static class Bounds {
        private final double north;
        private final double east;
        private final double south;
        private final double west;

        private Bounds(double north, double east, double south, double west) {
            this.north = north;
            this.east = east;
            this.south = south;
            this.west = west;
        }

        @Nullable
        public static Bounds tryExtractFromBrowser(@NotNull Browser browser) {
            try {
                // https://leafletjs.com/reference-1.7.1.html#latlngbounds
                final Object[] bounds = (Object[]) browser.evaluate(
                    "if (typeof geoMap === 'undefined') {" +
                    "    return undefined;" +
                    "} else {" +
                    "    let b = geoMap.getBounds();" +
                    "    return [b.getNorth(), b.getEast(), b.getSouth(), b.getWest()];" +
                    "}"
                );
                if (bounds == null) {
                    // Variable 'geoMap' may be undefined during first run
                    return null;
                }
                return new Bounds(
                    CommonUtils.toDouble(bounds[0]),
                    CommonUtils.toDouble(bounds[1]),
                    CommonUtils.toDouble(bounds[2]),
                    CommonUtils.toDouble(bounds[3])
                );
            } catch (Throwable e) {
                log.error("Error retrieving map bounds", e);
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format("L.latLngBounds(L.latLng(%f, %f), L.latLng(%f, %f))", north, east, south, west);
        }
    }
}
