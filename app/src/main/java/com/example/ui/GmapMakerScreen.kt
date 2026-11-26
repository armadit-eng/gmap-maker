package com.example.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.TileSource
import com.example.gmap.MapStyles
import com.example.ui.components.GmapPreviewCanvas
import com.example.ui.components.SlippyMapView
import com.example.ui.theme.Emerald600
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate500
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmapMakerScreen(
    viewModel: GmapViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            pendingLocationAction?.invoke()
        } else {
            viewModel.onLocationPermissionDenied()
        }
        pendingLocationAction = null
    }

    fun runWithLocationPermission(action: () -> Unit) {
        if (viewModel.hasLocationPermission(context)) {
            action()
        } else {
            pendingLocationAction = action
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.saveGmapToUri(uri)
        }
    }

    var tileBoxExpanded by rememberSaveable { mutableStateOf(false) }
    var tileSrcDraft by remember(state.tileSettings) { mutableStateOf(state.tileSettings.source) }
    var tileKeyDraft by remember(state.tileSettings) { mutableStateOf(state.tileSettings.cartoKey) }
    var tileUrlDraft by remember(state.tileSettings) { mutableStateOf(state.tileSettings.urlTemplate) }

    val scrollState = rememberScrollState()
    val logScrollState = rememberScrollState()
    LaunchedEffect(state.logs) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Scaffold(
        containerColor = Slate900,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = Emerald600,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = stringResource(R.string.header_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Slate200
                    )
                }

                // Lat / Lon & GPS (only in Radius mode)
                if (state.mode == AppMode.RADIUS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GmapTextField(
                            value = state.latInput,
                            onValueChange = viewModel::setLatInput,
                            label = stringResource(R.string.label_lat),
                            keyboardType = KeyboardType.Decimal,
                            testTag = "lat_input",
                            modifier = Modifier.weight(1f)
                        )
                        GmapTextField(
                            value = state.lonInput,
                            onValueChange = viewModel::setLonInput,
                            label = stringResource(R.string.label_lon),
                            keyboardType = KeyboardType.Decimal,
                            testTag = "lon_input",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    SecondaryActionButton(
                        text = stringResource(R.string.btn_gps),
                        icon = Icons.Default.MyLocation,
                        onClick = {
                            runWithLocationPermission { viewModel.useDeviceLocation() }
                        },
                        testTag = "gps_button"
                    )
                }

                // Mode selector
                GmapDropdown(
                    label = stringResource(R.string.label_mode),
                    selectedLabel = if (state.mode == AppMode.RADIUS) {
                        stringResource(R.string.mode_radius)
                    } else {
                        stringResource(R.string.mode_nav)
                    },
                    options = listOf(
                        AppMode.RADIUS to stringResource(R.string.mode_radius),
                        AppMode.NAVIGATION to stringResource(R.string.mode_nav)
                    ),
                    onSelect = { viewModel.setMode(it) },
                    testTag = "mode_dropdown"
                )

                // Navigation tap target buttons
                if (state.mode == AppMode.NAVIGATION) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SecondaryActionButton(
                            text = if (state.pickTarget == PickTarget.FROM) {
                                stringResource(R.string.pick_origin_active)
                            } else {
                                stringResource(R.string.pick_origin)
                            },
                            highlighted = state.pickTarget == PickTarget.FROM,
                            onClick = { viewModel.setPickTarget(PickTarget.FROM) },
                            testTag = "pick_from_button",
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryActionButton(
                            text = if (state.pickTarget == PickTarget.TO) {
                                stringResource(R.string.pick_dest_active)
                            } else {
                                stringResource(R.string.pick_dest)
                            },
                            highlighted = state.pickTarget == PickTarget.TO,
                            onClick = { viewModel.setPickTarget(PickTarget.TO) },
                            testTag = "pick_to_button",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Interactive Slippy Map
                SlippyMapView(
                    tileClient = viewModel.apiClient.tileClient,
                    tileSettings = state.tileSettings,
                    isNavigationMode = state.mode == AppMode.NAVIGATION,
                    centerMarker = state.centerMarker,
                    fromMarker = state.fromMarker,
                    toMarker = state.toMarker,
                    navRoute = state.navRoute,
                    viewportTarget = state.viewportTarget,
                    viewportTrigger = state.viewportTrigger,
                    onMapTap = viewModel::onMapTap,
                    onTileError = viewModel::onTileError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )

                Text(
                    text = if (state.mode == AppMode.RADIUS) {
                        stringResource(R.string.hint_radius_map)
                    } else {
                        stringResource(R.string.hint_nav_map)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )

                // Collapsible Carto Tile Source Settings
                Surface(
                    color = Slate800.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Slate700, RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable { tileBoxExpanded = !tileBoxExpanded }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("tile_settings_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Slate400,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.tile_settings_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate400
                                )
                            }
                            Icon(
                                imageVector = if (tileBoxExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = Slate400
                            )
                        }

                        AnimatedVisibility(visible = tileBoxExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                GmapDropdown(
                                    label = stringResource(R.string.tile_source_label),
                                    selectedLabel = tileSrcDraft.label,
                                    options = TileSource.entries.map { it to it.label },
                                    onSelect = { tileSrcDraft = it },
                                    testTag = "tile_source_dropdown"
                                )
                                AnimatedVisibility(visible = tileSrcDraft == TileSource.CARTO) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GmapTextField(
                                            value = tileKeyDraft,
                                            onValueChange = { tileKeyDraft = it },
                                            label = stringResource(R.string.tile_key_label),
                                            placeholder = stringResource(R.string.tile_key_hint),
                                            testTag = "tile_key_input"
                                        )
                                        GmapTextField(
                                            value = tileUrlDraft,
                                            onValueChange = { tileUrlDraft = it },
                                            label = stringResource(R.string.tile_url_label),
                                            testTag = "tile_url_input"
                                        )
                                    }
                                }
                                SecondaryActionButton(
                                    text = stringResource(R.string.tile_apply_btn),
                                    onClick = {
                                        viewModel.applyTileSettings(tileSrcDraft, tileKeyDraft, tileUrlDraft)
                                    },
                                    testTag = "tile_apply_button"
                                )
                                Text(
                                    text = state.tileMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate500
                                )
                            }
                        }
                    }
                }

                // Navigation inputs
                if (state.mode == AppMode.NAVIGATION) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GmapTextField(
                            value = state.fromQuery,
                            onValueChange = viewModel::setFromQuery,
                            label = stringResource(R.string.label_from),
                            testTag = "nav_from_input"
                        )
                        GmapTextField(
                            value = state.toQuery,
                            onValueChange = viewModel::setToQuery,
                            label = stringResource(R.string.label_to),
                            testTag = "nav_to_input"
                        )
                        GmapCheckboxRow(
                            checked = state.avoidToll,
                            onCheckedChange = viewModel::setAvoidToll,
                            label = stringResource(R.string.label_notoll),
                            testTag = "avoid_toll_checkbox"
                        )
                        GmapDropdown(
                            label = stringResource(R.string.label_corridor_width),
                            selectedLabel = "${state.corridorBufM / 1000} km",
                            options = listOf(
                                2000 to "2 km",
                                3000 to "3 km",
                                5000 to "5 km"
                            ),
                            onSelect = viewModel::setCorridorBufM,
                            testTag = "corridor_width_dropdown"
                        )
                        SecondaryActionButton(
                            text = if (state.isFindingRoute) "Mencari rute..." else stringResource(R.string.btn_find_route),
                            icon = Icons.Default.Route,
                            enabled = !state.isWorking && !state.isFindingRoute,
                            onClick = {
                                if (state.fromQuery.isBlank() && !viewModel.hasLocationPermission(context)) {
                                    runWithLocationPermission { viewModel.findRoute() }
                                } else {
                                    viewModel.findRoute()
                                }
                            },
                            testTag = "find_route_button"
                        )
                        if (state.routeInfo.isNotEmpty()) {
                            Text(
                                text = state.routeInfo,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Emerald600,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Style selector
                GmapDropdown(
                    label = stringResource(R.string.label_style),
                    selectedLabel = MapStyles.styleNames.getOrElse(state.styleIndex) { MapStyles.styleNames[4] },
                    options = MapStyles.styleNames.mapIndexed { idx, name -> idx to name },
                    onSelect = viewModel::setStyleIndex,
                    testTag = "style_dropdown"
                )

                // Radius & Cell size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.mode == AppMode.RADIUS) {
                        GmapTextField(
                            value = state.radiusKmInput,
                            onValueChange = viewModel::setRadiusKmInput,
                            label = stringResource(R.string.label_radius_km),
                            keyboardType = KeyboardType.Number,
                            testTag = "radius_input",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    GmapDropdown(
                        label = stringResource(R.string.label_cell_size),
                        selectedLabel = if (state.cellSizeE6 == 9000) "1 km (tabel kecil)" else "0,5 km (sel padat)",
                        options = listOf(
                            9000 to "1 km (tabel kecil)",
                            4500 to "0,5 km (sel padat)"
                        ),
                        onSelect = viewModel::setCellSizeE6,
                        testTag = "cell_size_dropdown",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Tolerance
                GmapTextField(
                    value = state.toleranceInput,
                    onValueChange = viewModel::setToleranceInput,
                    label = stringResource(R.string.label_tolerance),
                    keyboardType = KeyboardType.Decimal,
                    testTag = "tolerance_input"
                )

                // Layer checkboxes
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Lapisan Peta",
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate400
                        )
                    }
                    GmapCheckboxRow(
                        checked = state.layers.track,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(track = c) } },
                        label = stringResource(R.string.opt_track),
                        testTag = "layer_track_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.paths,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(paths = c) } },
                        label = stringResource(R.string.opt_paths),
                        testTag = "layer_paths_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.streams,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(streams = c) } },
                        label = stringResource(R.string.opt_streams),
                        testTag = "layer_streams_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.ditch,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(ditch = c) } },
                        label = stringResource(R.string.opt_ditch),
                        testTag = "layer_ditch_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.areas,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(areas = c) } },
                        label = stringResource(R.string.opt_areas),
                        testTag = "layer_areas_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.farm,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(farm = c) } },
                        label = stringResource(R.string.opt_farm),
                        testTag = "layer_farm_checkbox"
                    )
                    GmapCheckboxRow(
                        checked = state.layers.built,
                        onCheckedChange = { c -> viewModel.updateLayers { it.copy(built = c) } },
                        label = stringResource(R.string.opt_built),
                        testTag = "layer_built_checkbox"
                    )
                }

                // Generate Button
                Button(
                    onClick = {
                        if (state.mode == AppMode.NAVIGATION &&
                            state.navRoute == null &&
                            state.fromQuery.isBlank() &&
                            !viewModel.hasLocationPermission(context)
                        ) {
                            runWithLocationPermission { viewModel.generatePackage() }
                        } else {
                            viewModel.generatePackage()
                        }
                    },
                    enabled = !state.isWorking && !state.isFindingRoute,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Emerald600,
                        contentColor = Color.White,
                        disabledContainerColor = Emerald600.copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("generate_package_button")
                ) {
                    if (state.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Membuat paket peta...")
                    } else {
                        Text(
                            text = stringResource(R.string.btn_generate),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Terminal Log Box
                if (state.logs.isNotEmpty() || state.isWorking || state.isFindingRoute) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate950)
                            .border(1.dp, Slate700.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .verticalScroll(logScrollState)
                            .padding(10.dp)
                            .testTag("log_output")
                    ) {
                        Text(
                            text = state.logs,
                            fontFamily = JetBrainsMono,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = Slate200
                        )
                    }
                }

                // Preview Canvas & Output Actions
                state.decodedMap?.let { decoded ->
                    GmapPreviewCanvas(
                        decoded = decoded,
                        styleIndex = state.styleIndex,
                        navRoute = state.navRoute,
                        isNavigationMode = state.mode == AppMode.NAVIGATION
                    )
                }

                if (state.generatedBytes != null) {
                    SecondaryActionButton(
                        text = stringResource(R.string.btn_download),
                        icon = Icons.Default.Download,
                        onClick = { createDocumentLauncher.launch("map.gmap") },
                        testTag = "download_gmap_button"
                    )
                    SecondaryActionButton(
                        text = if (state.isUploading) "Mengirim ke ESP32..." else stringResource(R.string.btn_upload),
                        icon = Icons.Default.CloudUpload,
                        enabled = !state.isUploading,
                        onClick = viewModel::uploadToEsp32,
                        testTag = "upload_esp32_button"
                    )
                }

                HorizontalDivider(color = Slate700.copy(alpha = 0.5f))

                Text(
                    text = stringResource(R.string.footer_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500
                )
            }
        }
    }
}

@Composable
private fun GmapTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    testTag: String
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = placeholder?.let { { Text(it, color = Slate500, fontSize = 13.sp) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = if (keyboardType == KeyboardType.Decimal || keyboardType == KeyboardType.Number) {
                    JetBrainsMono
                } else {
                    MaterialTheme.typography.bodyLarge.fontFamily
                },
                color = Slate200
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate800,
                unfocusedContainerColor = Slate800,
                focusedBorderColor = Emerald600,
                unfocusedBorderColor = Slate700,
                cursorColor = Emerald600
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> GmapDropdown(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Slate800,
                    unfocusedContainerColor = Slate800,
                    focusedBorderColor = Emerald600,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Slate200,
                    unfocusedTextColor = Slate200
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag(testTag)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Slate800
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = Slate200) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    highlighted: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (highlighted) Emerald600.copy(alpha = 0.22f) else Slate800,
            contentColor = Slate200
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (highlighted) Emerald600 else Slate700
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GmapCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Emerald600,
                uncheckedColor = Slate500,
                checkmarkColor = Color.White
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
            color = Slate200
        )
    }
}
